from __future__ import annotations

import threading
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Any

from .download import download_audio
from .languages import is_valid_language
from .lyrics import find_lyrics_json, lyrics_file_stem, sync_lyrics
from .render import render_video
from .separate import separate_vocals
from .work_store import (
    copy_lyrics_to_output,
    load_job_meta,
    resolve_lyrics_text_for_resync,
    save_job_meta,
    update_work_lyrics,
    work_dir,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
WORK_ROOT = PROJECT_ROOT / "work"


class JobStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    LYRICS_READY = "lyrics_ready"
    COMPLETED = "completed"
    FAILED = "failed"


@dataclass
class Job:
    id: str
    youtube_url: str | None
    lyrics_text: str | None
    bg_color: str
    output_dir: Path
    vocal_blend_percent: float = 20.0
    language: str | None = None
    work_id: str | None = None
    title: str | None = None
    status: JobStatus = JobStatus.PENDING
    step: str = "queued"
    message: str = "Queued"
    output_mp4: Path | None = None
    error: str | None = None
    events: list[dict[str, Any]] = field(default_factory=list)
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def emit(self, step: str, message: str, **extra: Any) -> dict[str, Any]:
        event = {
            "job_id": self.id,
            "status": self.status.value,
            "step": step,
            "message": message,
            **extra,
        }
        with self._lock:
            self.step = step
            self.message = message
            self.events.append(event)
        return event


class JobStore:
    def __init__(self) -> None:
        self._jobs: dict[str, Job] = {}
        self._lock = threading.Lock()

    def create_prepare(
        self,
        youtube_url: str,
        lyrics_text: str | None,
        bg_color: str,
        output_dir: str | None,
        vocal_blend_percent: float = 20.0,
        language: str | None = None,
    ) -> Job:
        if language is not None and not is_valid_language(language):
            raise ValueError(f"Unsupported language code: {language}")

        job_id = str(uuid.uuid4())
        resolved_output = Path(output_dir).expanduser() if output_dir else PROJECT_ROOT / "output"
        resolved_output.mkdir(parents=True, exist_ok=True)
        job = Job(
            id=job_id,
            youtube_url=youtube_url,
            lyrics_text=lyrics_text,
            bg_color=bg_color,
            output_dir=resolved_output,
            vocal_blend_percent=max(0.0, min(100.0, vocal_blend_percent)),
            language=language,
            work_id=job_id,
        )
        with self._lock:
            self._jobs[job_id] = job
        thread = threading.Thread(target=self._run_prepare, args=(job,), daemon=True)
        thread.start()
        return job

    def create_render(
        self,
        work_id: str,
        lyrics_text: str,
        bg_color: str | None = None,
        output_dir: str | None = None,
    ) -> Job:
        path = work_dir(work_id)
        if not path.is_dir():
            raise FileNotFoundError(f"Work folder not found: {work_id}")
        if not (path / "backing.mp3").exists():
            raise FileNotFoundError(f"backing.mp3 missing in work folder: {work_id}")

        meta = load_job_meta(path)
        job_id = f"render-{uuid.uuid4()}"
        resolved_output = (
            Path(output_dir).expanduser()
            if output_dir
            else Path(meta.get("output_dir", PROJECT_ROOT / "output")).expanduser()
        )
        resolved_output.mkdir(parents=True, exist_ok=True)

        job = Job(
            id=job_id,
            youtube_url=meta.get("youtube_url"),
            lyrics_text=lyrics_text,
            bg_color=bg_color or meta.get("bg_color", "#1a1a2e"),
            output_dir=resolved_output,
            vocal_blend_percent=float(meta.get("vocal_blend_percent", 20.0)),
            language=meta.get("language"),
            work_id=work_id,
            title=meta.get("title"),
        )
        with self._lock:
            self._jobs[job_id] = job
        thread = threading.Thread(target=self._run_render, args=(job,), daemon=True)
        thread.start()
        return job

    def create_resync(
        self,
        work_id: str,
        lyrics_text: str | None = None,
    ) -> Job:
        path = work_dir(work_id)
        if not path.is_dir():
            raise FileNotFoundError(f"Work folder not found: {work_id}")
        if not (path / "original.mp3").exists():
            raise FileNotFoundError(
                f"original.mp3 missing in work folder: {work_id}. Run full prepare first.",
            )

        meta = load_job_meta(path)
        job_id = f"resync-{uuid.uuid4()}"
        job = Job(
            id=job_id,
            youtube_url=meta.get("youtube_url"),
            lyrics_text=lyrics_text,
            bg_color=meta.get("bg_color", "#1a1a2e"),
            output_dir=Path(meta.get("output_dir", PROJECT_ROOT / "output")).expanduser(),
            vocal_blend_percent=float(meta.get("vocal_blend_percent", 20.0)),
            language=meta.get("language"),
            work_id=work_id,
            title=meta.get("title"),
        )
        with self._lock:
            self._jobs[job_id] = job
        thread = threading.Thread(target=self._run_resync, args=(job,), daemon=True)
        thread.start()
        return job

    def get(self, job_id: str) -> Job | None:
        with self._lock:
            return self._jobs.get(job_id)

    def events_since(self, job_id: str, index: int) -> tuple[list[dict[str, Any]], int]:
        job = self.get(job_id)
        if not job:
            return [], index
        with job._lock:
            return job.events[index:], len(job.events)

    def _run_prepare(self, job: Job) -> None:
        work_path = work_dir(job.work_id or job.id)
        work_path.mkdir(parents=True, exist_ok=True)

        try:
            job.status = JobStatus.RUNNING
            job.emit("download", "Downloading audio from YouTube...")

            meta = download_audio(
                job.youtube_url or "",
                work_path,
                on_progress=lambda msg: job.emit("download", msg),
            )
            job.title = meta["title"]
            job.emit(
                "download",
                f"Downloaded: {meta['title']}",
                title=meta["title"],
                artist=meta["artist"],
            )

            job.emit("separate", "Separating vocals and instrumental...")
            stems = separate_vocals(
                meta["path"],
                work_path,
                vocal_blend=job.vocal_blend_percent / 100.0,
                on_progress=lambda msg: job.emit("separate", msg),
            )
            job.emit("separate", "Stem separation complete")

            job.emit(
                "lyrics",
                f"Syncing lyrics from full mix ({work_path / 'original.mp3'})...",
            )
            lines = sync_lyrics(
                work_path / "original.mp3",
                work_path,
                manual_lyrics=job.lyrics_text,
                language=job.language,
                song_title=meta["title"],
                on_progress=lambda msg: job.emit("lyrics", msg),
            )
            lyrics_text = "\n".join(line.text for line in lines)
            lyrics_stem = lyrics_file_stem(meta["title"], job.work_id or job.id)

            save_job_meta(
                work_path,
                {
                    "work_id": job.work_id,
                    "title": meta["title"],
                    "artist": meta.get("artist"),
                    "youtube_url": job.youtube_url,
                    "language": job.language,
                    "bg_color": job.bg_color,
                    "vocal_blend_percent": job.vocal_blend_percent,
                    "output_dir": str(job.output_dir),
                    "lyrics_json": f"{lyrics_stem}.json",
                    "lyrics_txt": f"{lyrics_stem}.txt",
                    "created_at": datetime.now(timezone.utc).isoformat(),
                },
            )

            job.status = JobStatus.LYRICS_READY
            job.emit(
                "lyrics_ready",
                "Lyrics ready for review — edit and render when ready",
                work_id=job.work_id,
                lyrics_text=lyrics_text,
                lyrics=[asdict(line) for line in lines],
                work_dir=str(work_path),
            )
        except Exception as exc:
            job.status = JobStatus.FAILED
            job.error = str(exc)
            job.emit("error", str(exc), error=True)

    def _run_render(self, job: Job) -> None:
        work_path = work_dir(job.work_id or job.id)

        try:
            job.status = JobStatus.RUNNING
            job.emit("lyrics", "Applying edited lyrics...")
            lines = update_work_lyrics(job.work_id or job.id, job.lyrics_text or "")
            job.emit("lyrics", f"Saved {len(lines)} lyric lines to work folder")

            backing = work_path / "backing.mp3"
            meta = load_job_meta(work_path)
            title = job.title or meta.get("title") or job.work_id or job.id
            safe_title = "".join(
                ch if ch.isalnum() or ch in (" ", "-", "_") else "_"
                for ch in title
            ).strip().replace(" ", "_")[:80] or job.id
            output_mp4 = job.output_dir / f"{safe_title}_karaoke.mp4"
            lyrics_json = find_lyrics_json(work_path)
            if lyrics_json is None:
                raise FileNotFoundError("Lyrics file missing in work folder")

            job.emit("render", "Rendering karaoke video...")
            render_video(
                backing,
                lyrics_json,
                output_mp4,
                bg_color=job.bg_color,
                on_progress=lambda msg: job.emit("render", msg),
            )

            copied = copy_lyrics_to_output(job.work_id or job.id, job.output_dir)
            if copied:
                job.emit(
                    "render",
                    f"Copied lyrics to {copied[0].name} and {copied[1].name}",
                )

            job.output_mp4 = output_mp4
            job.status = JobStatus.COMPLETED
            job.emit(
                "complete",
                "Karaoke video ready",
                output_path=str(output_mp4),
                work_dir=str(work_path),
                work_id=job.work_id,
            )
        except Exception as exc:
            job.status = JobStatus.FAILED
            job.error = str(exc)
            job.emit("error", str(exc), error=True)

    def _run_resync(self, job: Job) -> None:
        work_path = work_dir(job.work_id or job.id)

        try:
            job.status = JobStatus.RUNNING
            meta = load_job_meta(work_path)
            manual_lyrics = resolve_lyrics_text_for_resync(
                job.work_id or job.id,
                job.lyrics_text,
            )
            if manual_lyrics:
                job.emit("lyrics", "Re-syncing provided lyrics to audio timings...")
            else:
                job.emit("lyrics", "Re-transcribing lyrics from saved audio...")

            lines = sync_lyrics(
                work_path / "original.mp3",
                work_path,
                manual_lyrics=manual_lyrics,
                language=job.language or meta.get("language"),
                song_title=meta.get("title"),
                on_progress=lambda msg: job.emit("lyrics", msg),
            )
            lyrics_text = "\n".join(line.text for line in lines)
            lyrics_stem = lyrics_file_stem(
                meta.get("title") or job.work_id or job.id,
                job.work_id or job.id,
            )

            save_job_meta(
                work_path,
                {
                    **meta,
                    "lyrics_json": f"{lyrics_stem}.json",
                    "lyrics_txt": f"{lyrics_stem}.txt",
                },
            )

            job.status = JobStatus.LYRICS_READY
            job.emit(
                "lyrics_ready",
                "Lyrics re-synced — review timings and render when ready",
                work_id=job.work_id,
                lyrics_text=lyrics_text,
                lyrics=[asdict(line) for line in lines],
                work_dir=str(work_path),
            )
        except Exception as exc:
            job.status = JobStatus.FAILED
            job.error = str(exc)
            job.emit("error", str(exc), error=True)


job_store = JobStore()
