from __future__ import annotations

import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .lyrics import (
    LyricLine,
    apply_edited_lyric_text,
    find_lyrics_json,
    load_lyrics,
    save_lyrics,
)

PROJECT_ROOT = Path(__file__).resolve().parents[2]
WORK_ROOT = PROJECT_ROOT / "work"


def work_dir(work_id: str) -> Path:
    return WORK_ROOT / work_id


def save_job_meta(work_path: Path, meta: dict[str, Any]) -> None:
    work_path.mkdir(parents=True, exist_ok=True)
    (work_path / "job_meta.json").write_text(
        json.dumps(meta, indent=2),
        encoding="utf-8",
    )


def load_job_meta(work_path: Path) -> dict[str, Any]:
    meta_path = work_path / "job_meta.json"
    if not meta_path.exists():
        return {}
    return json.loads(meta_path.read_text(encoding="utf-8"))


def _lyrics_ready(path: Path) -> bool:
    return find_lyrics_json(path) is not None


def list_work_runs() -> list[dict[str, Any]]:
    if not WORK_ROOT.exists():
        return []

    runs: list[dict[str, Any]] = []
    for path in sorted(WORK_ROOT.iterdir(), key=lambda p: p.stat().st_mtime, reverse=True):
        if not path.is_dir() or path.name.startswith("_"):
            continue
        backing = path / "backing.mp3"
        vocals = path / "vocals.mp3"
        if not backing.exists() and not vocals.exists():
            continue
        meta = load_job_meta(path)
        runs.append(
            {
                "work_id": path.name,
                "title": meta.get("title") or path.name,
                "artist": meta.get("artist"),
                "language": meta.get("language"),
                "lyrics_ready": _lyrics_ready(path),
                "has_backing": backing.exists(),
                "has_video": any(path.glob("*_karaoke.mp4")),
                "updated_at": datetime.fromtimestamp(
                    path.stat().st_mtime,
                    tz=timezone.utc,
                ).isoformat(),
            }
        )
    return runs


def get_work_run(work_id: str) -> dict[str, Any]:
    path = work_dir(work_id)
    if not path.is_dir():
        raise FileNotFoundError(f"Work folder not found: {work_id}")

    meta = load_job_meta(path)
    lyrics_json = find_lyrics_json(path)
    lines = load_lyrics(path) if lyrics_json is not None else []
    lyrics_txt = lyrics_json.with_suffix(".txt") if lyrics_json else None
    if lyrics_txt is not None and not lyrics_txt.exists():
        legacy_txt = path / "lyrics.txt"
        lyrics_txt = legacy_txt if legacy_txt.exists() else lyrics_txt
    return {
        "work_id": work_id,
        "meta": meta,
        "lyrics": [
            {"text": line.text, "start_sec": line.start_sec, "end_sec": line.end_sec}
            for line in lines
        ],
        "lyrics_text": "\n".join(line.text for line in lines),
        "files": {
            "original": (path / "original.mp3").exists(),
            "vocals": (path / "vocals.mp3").exists(),
            "backing": (path / "backing.mp3").exists(),
            "non_vocal": (path / "non_vocal.mp3").exists(),
            "lyrics_json": lyrics_json is not None and lyrics_json.exists(),
            "lyrics_txt": lyrics_txt is not None and lyrics_txt.exists(),
        },
    }


def update_work_lyrics(work_id: str, lyrics_text: str) -> list[LyricLine]:
    path = work_dir(work_id)
    if not path.is_dir():
        raise FileNotFoundError(f"Work folder not found: {work_id}")
    meta = load_job_meta(path)
    lines = apply_edited_lyric_text(path, lyrics_text)
    json_path, txt_path = save_lyrics(path, lines, song_title=meta.get("title"))
    save_job_meta(
        path,
        {
            **meta,
            "lyrics_json": json_path.name,
            "lyrics_txt": txt_path.name,
        },
    )
    return lines


def resolve_lyrics_text_for_resync(work_id: str, lyrics_text: str | None = None) -> str | None:
    """Return lyric text to align, or None to auto-transcribe from audio."""
    if lyrics_text and lyrics_text.strip():
        return lyrics_text.strip()

    path = work_dir(work_id)
    lyrics_json = find_lyrics_json(path)
    if lyrics_json is not None:
        txt_path = lyrics_json.with_suffix(".txt")
        if txt_path.exists():
            return txt_path.read_text(encoding="utf-8").strip()
        legacy_txt = path / "lyrics.txt"
        if legacy_txt.exists():
            return legacy_txt.read_text(encoding="utf-8").strip()
    return None


def copy_lyrics_to_output(work_id: str, output_dir: Path) -> tuple[Path, Path] | None:
    path = work_dir(work_id)
    lyrics_json = find_lyrics_json(path)
    if lyrics_json is None:
        return None
    lyrics_txt = lyrics_json.with_suffix(".txt")
    if not lyrics_txt.exists():
        return None
    output_dir.mkdir(parents=True, exist_ok=True)
    out_json = output_dir / lyrics_json.name
    out_txt = output_dir / lyrics_txt.name
    shutil.copy2(lyrics_json, out_json)
    shutil.copy2(lyrics_txt, out_txt)
    return out_json, out_txt
