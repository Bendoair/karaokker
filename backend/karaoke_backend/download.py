from __future__ import annotations

from pathlib import Path
from typing import Callable

import yt_dlp


def download_audio(
    youtube_url: str,
    output_dir: Path,
    on_progress: Callable[[str], None] | None = None,
) -> dict:
    """Download YouTube audio as MP3. Returns metadata and output path."""
    output_dir.mkdir(parents=True, exist_ok=True)
    output_template = str(output_dir / "original.%(ext)s")

    def hook(status: dict) -> None:
        if status.get("status") == "downloading" and on_progress:
            total = status.get("total_bytes") or status.get("total_bytes_estimate")
            downloaded = status.get("downloaded_bytes", 0)
            if total:
                pct = downloaded / total * 100
                on_progress(f"Downloading audio: {pct:.0f}%")
            else:
                on_progress("Downloading audio...")

    ydl_opts = {
        "format": "bestaudio/best",
        "outtmpl": output_template,
        "postprocessors": [
            {
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "192",
            }
        ],
        "quiet": True,
        "no_warnings": True,
        "progress_hooks": [hook],
    }

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(youtube_url, download=True)

    mp3_path = output_dir / "original.mp3"
    if not mp3_path.exists():
        candidates = list(output_dir.glob("original.*"))
        if not candidates:
            raise FileNotFoundError("Download finished but original.mp3 was not created")
        mp3_path = candidates[0]

    title = info.get("title") or "Unknown"
    uploader = info.get("uploader") or info.get("channel") or "Unknown"
    duration = info.get("duration")

    return {
        "path": mp3_path,
        "title": title,
        "artist": uploader,
        "duration_sec": duration,
    }
