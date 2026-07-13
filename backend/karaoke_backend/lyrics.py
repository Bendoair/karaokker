from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass
from difflib import SequenceMatcher
from pathlib import Path
from typing import Callable

from faster_whisper import WhisperModel

from .device import apply_cuda_env, whisper_config
from .models import WHISPER_MODEL

MAX_WORDS_PER_LINE = 8
PAUSE_GAP_SEC = 0.5


@dataclass
class LyricLine:
    text: str
    start_sec: float
    end_sec: float


def _normalize(text: str) -> str:
    return re.sub(r"[^a-z0-9\s]", "", text.lower()).strip()


def safe_song_slug(title: str, fallback: str = "song") -> str:
    slug = "".join(
        ch if ch.isalnum() or ch in (" ", "-", "_") else "_"
        for ch in title
    ).strip().replace(" ", "_")
    return (slug[:80] if slug else fallback)


def lyrics_file_stem(song_title: str, fallback: str) -> str:
    return f"{safe_song_slug(song_title, fallback)}_lyrics"


def find_lyrics_json(output_dir: Path) -> Path | None:
    candidates: list[Path] = list(output_dir.glob("*_lyrics.json"))
    legacy = output_dir / "lyrics.json"
    if legacy.exists():
        candidates.append(legacy)
    if not candidates:
        return None
    return max(candidates, key=lambda path: path.stat().st_mtime)


def lyrics_paths_for_dir(
    output_dir: Path,
    song_title: str | None = None,
    fallback: str | None = None,
) -> tuple[Path, Path]:
    fb = fallback or output_dir.name
    title = song_title
    if title is None:
        from .work_store import load_job_meta

        title = load_job_meta(output_dir).get("title")
    stem = lyrics_file_stem(str(title or fb), fb)
    return output_dir / f"{stem}.json", output_dir / f"{stem}.txt"


def _group_words_into_lines(words: list[dict]) -> list[LyricLine]:
    if not words:
        return []

    lines: list[LyricLine] = []
    bucket: list[dict] = []

    def flush() -> None:
        if not bucket:
            return
        lines.append(
            LyricLine(
                text=" ".join(w["text"].strip() for w in bucket).strip(),
                start_sec=bucket[0]["start"],
                end_sec=bucket[-1]["end"],
            )
        )
        bucket.clear()

    for word in words:
        if bucket:
            gap = word["start"] - bucket[-1]["end"]
            if gap > PAUSE_GAP_SEC or len(bucket) >= MAX_WORDS_PER_LINE:
                flush()
        bucket.append(word)
    flush()
    return [line for line in lines if line.text]


def _transcribe_words(
    vocals_path: Path,
    language: str | None = None,
    on_progress: Callable[[str], None] | None = None,
) -> tuple[list[dict], str | None]:
    apply_cuda_env()
    device, compute_type = whisper_config()
    lang_label = language or "auto-detect"
    if on_progress:
        on_progress(
            f"Transcribing vocals with faster-whisper ({WHISPER_MODEL}, "
            f"{device}/{compute_type}, language={lang_label})...",
        )

    model = WhisperModel(WHISPER_MODEL, device=device, compute_type=compute_type)
    transcribe_kwargs: dict = {
        "word_timestamps": True,
        "vad_filter": True,
    }
    if language:
        transcribe_kwargs["language"] = language

    try:
        segments, info = model.transcribe(str(vocals_path), **transcribe_kwargs)
    except RuntimeError as exc:
        if device != "cuda":
            raise
        if on_progress:
            on_progress(f"Whisper CUDA failed ({exc}); retrying on CPU...")
        model = WhisperModel(WHISPER_MODEL, device="cpu", compute_type="int8")
        segments, info = model.transcribe(str(vocals_path), **transcribe_kwargs)

    words: list[dict] = []
    for segment in segments:
        if segment.words:
            for word in segment.words:
                token = (word.word or "").strip()
                if token:
                    words.append(
                        {
                            "text": token,
                            "start": float(word.start),
                            "end": float(word.end),
                        }
                    )

    language = getattr(info, "language", None)
    return words, language


def _split_tokens(text: str) -> list[str]:
    return re.findall(r"\S+", text)


def _fill_word_mappings(mappings: list[int | None]) -> list[int]:
    """Fill gaps so every user word maps to a whisper word index."""
    filled: list[int | None] = list(mappings)
    last: int | None = None
    for idx, value in enumerate(filled):
        if value is not None:
            last = value
        elif last is not None:
            filled[idx] = last

    next_value: int | None = None
    for idx in range(len(filled) - 1, -1, -1):
        if filled[idx] is not None:
            next_value = filled[idx]
        elif next_value is not None:
            filled[idx] = next_value

    known = [value for value in filled if value is not None]
    fallback = known[0] if known else 0
    return [value if value is not None else fallback for value in filled]


def _align_manual_lines(
    user_lines: list[str],
    whisper_words: list[dict],
) -> list[LyricLine]:
    """Map each user lyric line to whisper word timestamps (not grouped lines)."""
    display_lines = [line.strip() for line in user_lines if line.strip()]
    if not display_lines:
        return []
    if not whisper_words:
        return [
            LyricLine(text=line, start_sec=0.0, end_sec=2.0)
            for line in display_lines
        ]

    line_tokens = [_split_tokens(line) for line in display_lines]
    user_flat = [token for tokens in line_tokens for token in tokens]
    if not user_flat:
        return []

    user_norm = [_normalize(token) for token in user_flat]
    whisper_norm = [_normalize(word["text"]) for word in whisper_words]

    matcher = SequenceMatcher(None, user_norm, whisper_norm, autojunk=False)
    mapped: list[int | None] = [None] * len(user_flat)
    for start_a, start_b, size in matcher.get_matching_blocks():
        for offset in range(size):
            mapped[start_a + offset] = start_b + offset

    filled = _fill_word_mappings(mapped)

    aligned: list[LyricLine] = []
    cursor = 0
    for line_idx, tokens in enumerate(line_tokens):
        if not tokens:
            continue
        indices = filled[cursor : cursor + len(tokens)]
        cursor += len(tokens)
        start = float(whisper_words[indices[0]]["start"])
        end = float(whisper_words[indices[-1]]["end"])
        if aligned:
            prev = aligned[-1]
            if start < prev.end_sec:
                start = prev.end_sec
        aligned.append(
            LyricLine(
                text=display_lines[line_idx],
                start_sec=start,
                end_sec=max(end, start + 0.2),
            )
        )

    return aligned


def save_lyrics(
    output_dir: Path,
    lines: list[LyricLine],
    song_title: str | None = None,
) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path, txt_path = lyrics_paths_for_dir(output_dir, song_title=song_title)
    payload = [asdict(line) for line in lines]
    json_path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    txt_path.write_text(
        "\n".join(line.text for line in lines),
        encoding="utf-8",
    )
    return json_path, txt_path


def load_lyrics(output_dir: Path) -> list[LyricLine]:
    lyrics_json = find_lyrics_json(output_dir)
    if lyrics_json is None:
        return []
    payload = json.loads(lyrics_json.read_text(encoding="utf-8"))
    return [LyricLine(**item) for item in payload]


def apply_edited_lyric_text(output_dir: Path, lyrics_text: str) -> list[LyricLine]:
    """Update lyric line text from edited multiline string; keep timings by line index."""
    existing = load_lyrics(output_dir)
    if not existing:
        raise RuntimeError("No lyrics file found to edit")

    edited_lines = [line for line in lyrics_text.splitlines() if line.strip()]
    if not edited_lines:
        raise RuntimeError("Edited lyrics are empty")

    updated: list[LyricLine] = []
    for idx, text in enumerate(edited_lines):
        if idx < len(existing):
            source = existing[idx]
            updated.append(
                LyricLine(text=text.strip(), start_sec=source.start_sec, end_sec=source.end_sec)
            )
        elif updated:
            last = updated[-1]
            updated.append(
                LyricLine(
                    text=text.strip(),
                    start_sec=last.end_sec,
                    end_sec=last.end_sec + 2.0,
                )
            )
        else:
            updated.append(LyricLine(text=text.strip(), start_sec=0.0, end_sec=2.0))
    return updated


def sync_lyrics(
    timing_audio_path: Path,
    output_dir: Path,
    manual_lyrics: str | None = None,
    language: str | None = None,
    song_title: str | None = None,
    on_progress: Callable[[str], None] | None = None,
) -> list[LyricLine]:
    """Transcribe or align lyrics and write song-titled lyrics files."""
    if on_progress:
        on_progress(f"Transcribing for lyric timing: {timing_audio_path.name}")
    words, detected_language = _transcribe_words(
        timing_audio_path,
        language=language,
        on_progress=on_progress,
    )
    if on_progress and detected_language:
        on_progress(f"Detected language: {detected_language}")

    if manual_lyrics and manual_lyrics.strip():
        user_lines = [line for line in manual_lyrics.splitlines()]
        if on_progress:
            on_progress("Aligning provided lyrics to word timings...")
        lines = _align_manual_lines(user_lines, words)
    else:
        if on_progress:
            on_progress("Grouping transcribed words into display lines...")
        lines = _group_words_into_lines(words)

    if not lines:
        raise RuntimeError("No lyrics lines were produced from the audio track")

    save_lyrics(output_dir, lines, song_title=song_title)
    return lines
