from __future__ import annotations

import os
from pathlib import Path
from typing import Callable, Iterator

from .device import apply_cuda_env, demucs_providers, runtime_info, whisper_config
from .separate import DEMUCS_MODEL

WHISPER_MODEL = os.environ.get("KARAOKE_WHISPER_MODEL", "small")
PROJECT_ROOT = Path(__file__).resolve().parents[2]


def _hf_cache_dir() -> Path:
    hf_home = os.environ.get("HF_HOME")
    if hf_home:
        return Path(hf_home) / "hub"
    return Path.home() / ".cache" / "huggingface" / "hub"


def check_models() -> dict:
    """Return model readiness without downloading."""
    missing: list[str] = []
    notes: list[str] = []

    try:
        from huggingface_hub import scan_cache_dir

        cache_info = scan_cache_dir()
        repo_ids = {repo.repo_id for repo in cache_info.repos}
        demucs_repos = [
            repo for repo in repo_ids if repo.startswith("StemSplitio/htdemucs")
        ]
        has_htdemucs = any("htdemucs-onnx" in repo for repo in demucs_repos)
        has_ft_vocals = any("htdemucs-ft-vocals-onnx" in repo for repo in demucs_repos)
        if not has_htdemucs and not has_ft_vocals:
            missing.append(f"demucs-onnx ({DEMUCS_MODEL})")
        else:
            notes.append(f"demucs cache: {len(demucs_repos)} repo(s)")
    except Exception as exc:
        missing.append(f"demucs-onnx ({exc})")

    whisper_cache = _hf_cache_dir()
    whisper_markers = list(whisper_cache.glob("models--Systran--faster-whisper-*"))
    if not any(WHISPER_MODEL in marker.name for marker in whisper_markers):
        missing.append(f"faster-whisper ({WHISPER_MODEL})")
    else:
        notes.append(f"whisper cache present for {WHISPER_MODEL}")

    return {
        "models_ready": len(missing) == 0,
        "missing": missing,
        "notes": notes,
        "whisper_model": WHISPER_MODEL,
        "demucs_model": DEMUCS_MODEL,
        **runtime_info(),
    }


def prefetch_models(
    on_progress: Callable[[str, str], None] | None = None,
) -> Iterator[dict]:
    """Download and warm up models. Yields progress events."""
    apply_cuda_env()

    def emit(step: str, message: str, **extra: object) -> dict:
        event = {"step": step, "message": message, **extra}
        if on_progress:
            on_progress(step, message)
        return event

    providers = demucs_providers()
    yield emit(
        "demucs",
        f"Downloading demucs-onnx {DEMUCS_MODEL} model (~150 MB), inference={providers}...",
    )
    try:
        from demucs_onnx.inference import separate

        import numpy as np
        import soundfile as sf

        warmup_dir = PROJECT_ROOT / "work" / "_model_warmup"
        warmup_dir.mkdir(parents=True, exist_ok=True)
        warmup_wav = warmup_dir / "silence.wav"
        if not warmup_wav.exists():
            silence = np.zeros((2, 44100), dtype=np.float32)
            sf.write(warmup_wav, silence.T, 44100)

        separate(
            str(warmup_wav),
            output_dir=None,
            model=DEMUCS_MODEL,
            providers=providers,
            verbose=False,
            progress=False,
        )
        yield emit("demucs", "demucs-onnx model ready", done=True)
    except Exception as exc:
        yield emit("demucs", f"demucs-onnx setup failed: {exc}", error=True)
        raise

    device, compute_type = whisper_config()
    yield emit(
        "whisper",
        f"Downloading faster-whisper {WHISPER_MODEL} ({device}/{compute_type})...",
    )
    try:
        from faster_whisper import WhisperModel

        WhisperModel(WHISPER_MODEL, device=device, compute_type=compute_type)
        yield emit("whisper", "faster-whisper model ready", done=True)
    except Exception as exc:
        yield emit("whisper", f"faster-whisper setup failed: {exc}", error=True)
        raise

    status = check_models()
    yield emit(
        "complete",
        "All models ready" if status["models_ready"] else "Model validation incomplete",
        models_ready=status["models_ready"],
        missing=status["missing"],
        runtime=runtime_info(),
    )
