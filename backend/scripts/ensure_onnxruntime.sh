#!/usr/bin/env bash
# Ensure a working onnxruntime install in the project venv.
# Tries GPU only when import + CUDA provider both succeed; otherwise CPU.
set -euo pipefail

VENV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.venv"
PYTHON="$VENV_DIR/bin/python"
PIP="$VENV_DIR/bin/pip"

probe_onnxruntime() {
  "$PYTHON" - <<'PY'
try:
    import onnxruntime as ort
    providers = ort.get_available_providers()
    print("OK", ",".join(providers))
except Exception as exc:
    print("FAIL", exc)
PY
}

install_cpu() {
  echo "==> Installing onnxruntime (CPU)"
  "$PIP" uninstall -y onnxruntime-gpu onnxruntime >/dev/null 2>&1 || true
  "$PIP" install onnxruntime
}

try_gpu=false
if command -v nvidia-smi >/dev/null 2>&1; then
  try_gpu=true
fi

if $try_gpu; then
  echo "==> NVIDIA GPU detected — probing onnxruntime-gpu"
  "$PIP" uninstall -y onnxruntime-gpu onnxruntime >/dev/null 2>&1 || true
  if ! "$PIP" install onnxruntime-gpu; then
    echo "WARNING: onnxruntime-gpu install failed"
    install_cpu
  else
    result="$(probe_onnxruntime)"
    if [[ "$result" == OK* ]] && [[ "$result" == *CUDAExecutionProvider* ]]; then
      echo "==> Using onnxruntime-gpu ($result)"
      exit 0
    fi
    echo "WARNING: onnxruntime-gpu not usable ($result)"
    echo "         CUDA runtime missing? Install CUDA toolkit in WSL or use CPU."
    install_cpu
  fi
else
  install_cpu
fi

result="$(probe_onnxruntime)"
if [[ "$result" != OK* ]]; then
  echo "ERROR: onnxruntime still broken after CPU install: $result"
  exit 1
fi
echo "==> Using onnxruntime CPU ($result)"
