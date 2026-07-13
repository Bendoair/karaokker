from __future__ import annotations

import gc
import os
import subprocess
import sys
from pathlib import Path
from typing import Callable

import numpy as np
import soundfile as sf
from demucs_onnx.inference import separate, session_pool

from .device import apply_cuda_env, demucs_providers, vocal_blend_ratio

# Single-file 4-stem model: one inference pass, outputs drums/bass/other/vocals.
# Instrumental = drums + bass + other (demucs-onnx --karaoke).
DEMUCS_MODEL = os.environ.get("KARAOKE_DEMUCS_MODEL", "htdemucs")
INSTRUMENTAL_STEMS = ("drums", "bass", "other")


def _align_stereo(audio: np.ndarray) -> np.ndarray:
    if audio.ndim == 1:
        return audio[np.newaxis, :]
    if audio.shape[0] > audio.shape[1]:
        return audio.T
    return audio


def _write_mp3(path: Path, audio: np.ndarray, sample_rate: int) -> None:
    wav_path = path.with_name(f".{path.stem}.tmp.wav")
    stereo = _align_stereo(audio)
    sf.write(str(wav_path), stereo.T, sample_rate, format="WAV")
    try:
        subprocess.run(
            [
                "ffmpeg",
                "-y",
                "-i",
                str(wav_path),
                "-b:a",
                "192k",
                str(path),
            ],
            check=True,
            capture_output=True,
            text=True,
        )
    finally:
        wav_path.unlink(missing_ok=True)


def _mix_stems(stems: dict[str, np.ndarray], names: tuple[str, ...]) -> np.ndarray:
    missing = [name for name in names if name not in stems]
    if missing:
        raise RuntimeError(f"Separation missing stems: {missing}")
    return np.sum(np.stack([stems[name] for name in names], axis=0), axis=0).astype(np.float32)


def _normalize_peak(audio: np.ndarray, target: float = 0.99) -> np.ndarray:
    peak = float(np.max(np.abs(audio)))
    if peak > 1.0:
        return (audio / peak * target).astype(np.float32)
    return audio


def _separate_vocals_impl(
    input_mp3: Path,
    output_dir: Path,
    vocal_blend: float,
    providers: str | None = None,
) -> dict[str, Path]:
    """Separate 4 stems and build instrumental from drums+bass+other."""
    output_dir.mkdir(parents=True, exist_ok=True)
    onnx_providers = providers or demucs_providers()
    blend = max(0.0, min(1.0, vocal_blend))

    try:
        stems = separate(
            str(input_mp3),
            output_dir=None,
            model=DEMUCS_MODEL,
            providers=onnx_providers,
            verbose=False,
            progress=False,
            output_format="mp3",
        )
    finally:
        session_pool().clear()
        gc.collect()

    vocals = _align_stereo(stems["vocals"])
    instrumental = _align_stereo(_mix_stems(stems, INSTRUMENTAL_STEMS))
    backing = instrumental + blend * vocals
    backing = _normalize_peak(backing)

    _, sample_rate = sf.read(str(input_mp3), dtype="float32")

    vocals_path = output_dir / "vocals.mp3"
    non_vocal_path = output_dir / "non_vocal.mp3"
    backing_path = output_dir / "backing.mp3"
    _write_mp3(vocals_path, vocals, sample_rate)
    _write_mp3(non_vocal_path, instrumental, sample_rate)
    _write_mp3(backing_path, backing, sample_rate)

    return {
        "vocals": vocals_path,
        "non_vocal": non_vocal_path,
        "instrumental": backing_path,
    }


def _run_in_subprocess(
    input_mp3: Path,
    output_dir: Path,
    vocal_blend: float,
    providers: str,
) -> dict[str, Path]:
    apply_cuda_env()
    env = os.environ.copy()
    env["LD_LIBRARY_PATH"] = os.environ.get("LD_LIBRARY_PATH", "")

    cmd = [
        sys.executable,
        "-m",
        "karaoke_backend.separate_worker",
        str(input_mp3),
        str(output_dir),
        str(vocal_blend),
        "--providers",
        providers,
    ]
    completed = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        env=env,
        cwd=str(Path(__file__).resolve().parents[1]),
    )

    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout or "").strip()
        if completed.returncode in {-9, 137}:
            raise RuntimeError(
                "Stem separation ran out of memory. "
                "Close other apps or try a shorter song. "
                f"{detail}".strip()
            )
        raise RuntimeError(
            detail or f"Stem separation failed (exit {completed.returncode})",
        )

    vocals_path = output_dir / "vocals.mp3"
    backing_path = output_dir / "backing.mp3"
    non_vocal_path = output_dir / "non_vocal.mp3"
    if not vocals_path.exists() or not backing_path.exists():
        raise FileNotFoundError("Separation subprocess finished but output files are missing")

    return {
        "vocals": vocals_path,
        "non_vocal": non_vocal_path,
        "instrumental": backing_path,
    }


def separate_vocals(
    input_mp3: Path,
    output_dir: Path,
    vocal_blend: float | None = None,
    on_progress: Callable[[str], None] | None = None,
) -> dict[str, Path]:
    """Extract vocals + instrumental stems, blend vocals into final backing."""
    output_dir.mkdir(parents=True, exist_ok=True)
    providers = demucs_providers()
    blend = vocal_blend_ratio() if vocal_blend is None else max(0.0, min(1.0, vocal_blend))

    if on_progress:
        on_progress(
            f"Separating stems ({DEMUCS_MODEL}, {providers}) — "
            f"instrumental = drums+bass+other, backing = instrumental + {blend:.0%} vocals",
        )
        from .device import runtime_info

        runtime = runtime_info()
        demucs_note = (
            f"demucs GPU ({runtime.get('demucs_providers', 'cuda')})"
            if runtime.get("onnx_cuda_available")
            else "demucs CPU (cuDNN 9 not installed — ONNX GPU unavailable)"
        )
        whisper_note = (
            "Whisper GPU"
            if runtime.get("whisper_cuda_available")
            else "Whisper CPU"
        )
        on_progress(f"Inference: {demucs_note}, {whisper_note}")
        on_progress("Running separation in isolated process (protects backend from OOM)...")

    in_process = os.environ.get("KARAOKE_SEPARATE_INPROCESS") == "1"
    if in_process:
        result = _separate_vocals_impl(input_mp3, output_dir, blend, providers)
    else:
        result = _run_in_subprocess(input_mp3, output_dir, blend, providers)

    if on_progress:
        on_progress(
            f"Saved vocals, instrumental, and backing ({blend:.0%} vocal blend in final audio)",
        )

    return result
