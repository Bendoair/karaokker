#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
VENV_DIR="$BACKEND_DIR/.venv"

echo "==> Karaoke Video Generator setup"

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 is required."
  echo "Install with: sudo apt install python3 python3-venv python3-pip"
  exit 1
fi

if ! python3 -c "import venv" >/dev/null 2>&1; then
  echo "ERROR: python3-venv is not available."
  echo "Install with: sudo apt install python3-venv"
  exit 1
fi

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "WARNING: ffmpeg not found. Install with: sudo apt install ffmpeg"
  echo "         Download, separation output, and video rendering require ffmpeg/ffprobe."
fi

if ! command -v ffprobe >/dev/null 2>&1; then
  echo "WARNING: ffprobe not found (usually installed with ffmpeg)."
fi

if [[ -d "$VENV_DIR" && ! -x "$VENV_DIR/bin/python" ]]; then
  echo "==> Removing incomplete virtual environment"
  rm -rf "$VENV_DIR"
fi

if [[ ! -d "$VENV_DIR" ]]; then
  echo "==> Creating Python virtual environment"
  python3 -m venv "$VENV_DIR"
fi

echo "==> Installing Python dependencies"
"$VENV_DIR/bin/pip" install --upgrade pip
"$VENV_DIR/bin/pip" install -r "$BACKEND_DIR/requirements.txt"

chmod +x "$BACKEND_DIR/scripts/ensure_onnxruntime.sh"
"$BACKEND_DIR/scripts/ensure_onnxruntime.sh"

echo "==> Preparing CUDA 12 library shims for faster-whisper (if CUDA 13 is installed)"
PYTHONPATH="$BACKEND_DIR" "$VENV_DIR/bin/python" - <<'PY'
from karaoke_backend.device import ensure_cuda_shims, cuda_library_path

shim_dir = ensure_cuda_shims()
print(f"CUDA shims: {shim_dir}")
print(f"LD_LIBRARY_PATH preview: {cuda_library_path()}")
PY

echo "==> Prefetching ML models (this may download ~800 MB on first run)"
cd "$BACKEND_DIR"
PYTHONPATH="$BACKEND_DIR" "$VENV_DIR/bin/python" - <<'PY'
from karaoke_backend.models import prefetch_models

for event in prefetch_models():
    print(f"[{event.get('step')}] {event.get('message')}")
PY

echo ""
echo "Setup complete."
echo ""
echo "Run backend only:"
echo "  cd $BACKEND_DIR && $VENV_DIR/bin/python main.py"
echo ""
echo "Run desktop GUI:"
echo "  cd $ROOT_DIR && ./gradlew :composeApp:run"
