from __future__ import annotations

import os
import shutil
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
CUDA_SHIM_DIR = PROJECT_ROOT / "backend" / "cuda_shims"


def nvidia_gpu_available() -> bool:
    return shutil.which("nvidia-smi") is not None


def _cuda_lib_dirs() -> list[Path]:
    dirs: list[Path] = []
    for candidate in (
        os.environ.get("CUDA_HOME"),
        "/usr/local/cuda",
        "/usr/local/cuda-13",
        "/usr/local/cuda-13.2",
    ):
        if not candidate:
            continue
        lib64 = Path(candidate) / "lib64"
        if lib64.is_dir():
            dirs.append(lib64)
    return dirs


def ensure_cuda_shims() -> Path:
    """Symlink CUDA 12 sonames to installed CUDA 13 libs for ctranslate2."""
    CUDA_SHIM_DIR.mkdir(parents=True, exist_ok=True)
    for lib_dir in _cuda_lib_dirs():
        for base in ("libcublas", "libcublasLt"):
            source = lib_dir / f"{base}.so.13"
            if not source.exists():
                source = lib_dir / f"{base}.so"
            if not source.exists():
                continue
            link = CUDA_SHIM_DIR / f"{base}.so.12"
            if link.exists() or link.is_symlink():
                continue
            link.symlink_to(source)
    return CUDA_SHIM_DIR


def preload_cuda_shims() -> None:
    """Load CUDA 12 shim libraries by absolute path (works after Python already started)."""
    import ctypes

    ensure_cuda_shims()
    for name in ("libcublas.so.12", "libcublasLt.so.12"):
        shim = CUDA_SHIM_DIR / name
        if shim.exists():
            ctypes.CDLL(str(shim), mode=ctypes.RTLD_GLOBAL)


def cuda_library_path() -> str:
    """Library paths for ONNX + ctranslate2 in GUI-spawned processes."""
    ensure_cuda_shims()
    paths: list[str] = []
    if CUDA_SHIM_DIR.is_dir():
        paths.append(str(CUDA_SHIM_DIR))
    paths.extend(str(path) for path in _cuda_lib_dirs())
    wsl_lib = Path("/usr/lib/wsl/lib")
    if wsl_lib.is_dir():
        paths.append(str(wsl_lib))
    existing = os.environ.get("LD_LIBRARY_PATH", "")
    if existing:
        paths.extend(part for part in existing.split(":") if part)
    seen: set[str] = set()
    ordered: list[str] = []
    for path in paths:
        if path and path not in seen:
            seen.add(path)
            ordered.append(path)
    return ":".join(ordered)


def apply_cuda_env() -> None:
    os.environ["LD_LIBRARY_PATH"] = cuda_library_path()
    preload_cuda_shims()


def onnx_runtime_providers() -> list[str]:
    try:
        import onnxruntime as ort

        return ort.get_available_providers()
    except Exception:
        return []


def onnx_runtime_error() -> str | None:
    try:
        import onnxruntime as ort

        ort.get_available_providers()
        return None
    except Exception as exc:
        return str(exc)


def cuda_inference_available() -> bool:
    """True when ONNX Runtime can load CUDAExecutionProvider (requires cuDNN)."""
    if "CUDAExecutionProvider" not in onnx_runtime_providers():
        return False
    return _cudnn_available()


def gpu_acceleration_available() -> bool:
    """True when either demucs (ONNX CUDA) or Whisper (ctranslate2 CUDA) can use the GPU."""
    return cuda_inference_available() or whisper_cuda_available()


_cudnn_available_cache: bool | None = None


def _cudnn_available() -> bool:
    global _cudnn_available_cache
    if _cudnn_available_cache is not None:
        return _cudnn_available_cache

    import ctypes.util

    if ctypes.util.find_library("cudnn"):
        _cudnn_available_cache = True
        return True

    search_dirs = list(_cuda_lib_dirs())
    wsl_lib = Path("/usr/lib/wsl/lib")
    if wsl_lib.is_dir():
        search_dirs.append(wsl_lib)

    for lib_dir in search_dirs:
        for name in ("libcudnn.so.9", "libcudnn.so"):
            if (lib_dir / name).exists():
                _cudnn_available_cache = True
                return True

    _cudnn_available_cache = False
    return False


def demucs_providers() -> str:
    explicit = os.environ.get("KARAOKE_ONNX_PROVIDERS")
    if explicit:
        return explicit
    if cuda_inference_available():
        return "cuda"
    # Avoid a failed CUDA init (missing cuDNN) before CPU fallback — saves time and RAM.
    return "cpu"


_whisper_cuda_works: bool | None = None


def whisper_cuda_available() -> bool:
    global _whisper_cuda_works
    if _whisper_cuda_works is not None:
        return _whisper_cuda_works
    if not nvidia_gpu_available():
        _whisper_cuda_works = False
        return False
    try:
        import ctypes

        apply_cuda_env()
        shim = CUDA_SHIM_DIR / "libcublas.so.12"
        if not shim.exists():
            _whisper_cuda_works = False
            return False
        ctypes.CDLL(str(shim), mode=ctypes.RTLD_GLOBAL)
        _whisper_cuda_works = True
    except OSError:
        _whisper_cuda_works = False
    return _whisper_cuda_works


def whisper_config() -> tuple[str, str]:
    explicit_device = os.environ.get("KARAOKE_WHISPER_DEVICE")
    if explicit_device:
        device = explicit_device
    elif whisper_cuda_available():
        device = "cuda"
    else:
        device = "cpu"

    if device == "cuda":
        compute_type = os.environ.get("KARAOKE_WHISPER_COMPUTE", "float16")
    else:
        compute_type = os.environ.get("KARAOKE_WHISPER_COMPUTE", "int8")
    return device, compute_type


def vocal_blend_ratio() -> float:
    return float(os.environ.get("KARAOKE_VOCAL_BLEND", "0.2"))


def runtime_info() -> dict:
    device, compute = whisper_config()
    load_error = onnx_runtime_error()
    onnx_cuda = cuda_inference_available()
    whisper_cuda = whisper_cuda_available()
    return {
        "onnx_providers": onnx_runtime_providers(),
        "onnx_load_error": load_error,
        "demucs_providers": demucs_providers(),
        "whisper_device": device,
        "whisper_compute_type": compute,
        "onnx_cuda_available": onnx_cuda,
        "cuda_available": gpu_acceleration_available(),
        "whisper_cuda_available": whisper_cuda,
        "cuda_library_path": cuda_library_path(),
        "nvidia_smi": nvidia_gpu_available(),
        "vocal_blend": vocal_blend_ratio(),
    }
