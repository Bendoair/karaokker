"""Isolated subprocess entrypoint for stem separation.

Runs in its own process so an OOM during demucs does not kill the API server.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from karaoke_backend.device import apply_cuda_env

apply_cuda_env()


def main() -> int:
    parser = argparse.ArgumentParser(description="Run vocal separation in an isolated process")
    parser.add_argument("input_mp3", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("vocal_blend", type=float)
    parser.add_argument("--providers", default="")
    args = parser.parse_args()

    from karaoke_backend.separate import _separate_vocals_impl

    providers = args.providers or None
    try:
        result = _separate_vocals_impl(
            input_mp3=args.input_mp3,
            output_dir=args.output_dir,
            vocal_blend=args.vocal_blend,
            providers=providers,
        )
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 1

    print(json.dumps({key: str(path) for key, path in result.items()}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
