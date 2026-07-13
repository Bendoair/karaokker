from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Callable

import pysubs2

from .lyrics import LyricLine


def _audio_duration_sec(audio_path: Path) -> float:
    result = subprocess.run(
        [
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            str(audio_path),
        ],
        capture_output=True,
        text=True,
        check=True,
    )
    return float(result.stdout.strip())


def _build_ass(
    lines: list[LyricLine],
    ass_path: Path,
    width: int = 1280,
    height: int = 720,
) -> None:
    subs = pysubs2.SSAFile()
    subs.info["PlayResX"] = str(width)
    subs.info["PlayResY"] = str(height)

    style = pysubs2.SSAStyle()
    style.fontname = "DejaVu Sans"
    style.fontsize = 56
    style.primarycolor = pysubs2.Color(255, 255, 255)
    style.outlinecolor = pysubs2.Color(0, 0, 0)
    style.backcolor = pysubs2.Color(0, 0, 0, 128)
    style.bold = True
    style.outline = 3
    style.shadow = 1
    style.alignment = 2  # bottom center
    style.marginv = 80
    subs.styles["Default"] = style

    for line in lines:
        start = max(0.0, line.start_sec)
        end = max(start + 0.2, line.end_sec)
        event = pysubs2.SSAEvent(
            start=pysubs2.make_time(s=start),
            end=pysubs2.make_time(s=end),
            text=line.text,
            style="Default",
        )
        subs.events.append(event)

    subs.save(str(ass_path))


def render_video(
    instrumental_path: Path,
    lyrics_json: Path,
    output_mp4: Path,
    bg_color: str = "#1a1a2e",
    on_progress: Callable[[str], None] | None = None,
) -> Path:
    """Render a simple line-at-a-time karaoke MP4."""
    if on_progress:
        on_progress("Rendering lyrics video with ffmpeg...")

    lines_data = json.loads(lyrics_json.read_text(encoding="utf-8"))
    lines = [LyricLine(**item) for item in lines_data]

    work_dir = output_mp4.parent
    work_dir.mkdir(parents=True, exist_ok=True)
    ass_path = work_dir / "lyrics.ass"
    _build_ass(lines, ass_path)

    duration = _audio_duration_sec(instrumental_path)
    color = bg_color if bg_color.startswith("#") else f"#{bg_color}"
    ffmpeg_color = f"0x{color.lstrip('#')}"

    # Escape path for ffmpeg filter on Linux.
    ass_filter_path = str(ass_path).replace("\\", "/").replace(":", "\\:")

    cmd = [
        "ffmpeg",
        "-y",
        "-f",
        "lavfi",
        "-i",
        f"color=c={ffmpeg_color}:s=1280x720:d={duration:.3f}",
        "-i",
        str(instrumental_path),
        "-vf",
        f"ass={ass_filter_path}",
        "-c:v",
        "libx264",
        "-pix_fmt",
        "yuv420p",
        "-c:a",
        "aac",
        "-b:a",
        "192k",
        "-shortest",
        str(output_mp4),
    ]

    subprocess.run(cmd, check=True, capture_output=True, text=True)
    return output_mp4
