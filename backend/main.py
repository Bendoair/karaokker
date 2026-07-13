"""Uvicorn entry point for the karaoke backend."""

from karaoke_backend.device import apply_cuda_env

apply_cuda_env()

import uvicorn

if __name__ == "__main__":
    uvicorn.run(
        "karaoke_backend.server:app",
        host="127.0.0.1",
        port=8765,
        reload=False,
    )
