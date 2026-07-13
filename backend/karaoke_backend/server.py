from __future__ import annotations

from karaoke_backend.device import apply_cuda_env

apply_cuda_env()

import asyncio
import json
from typing import AsyncIterator

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from .languages import is_valid_language, list_languages
from .models import check_models, prefetch_models
from .pipeline import JobStatus, job_store
from .work_store import get_work_run, list_work_runs, update_work_lyrics

app = FastAPI(title="Karaoke Video Generator Backend", version="0.2.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class PrepareJobRequest(BaseModel):
    youtube_url: str = Field(min_length=8)
    lyrics_text: str | None = None
    bg_color: str = "#1a1a2e"
    output_dir: str | None = None
    vocal_blend_percent: float = Field(default=20.0, ge=0.0, le=100.0)
    language: str | None = None


class RenderWorkRequest(BaseModel):
    lyrics_text: str = Field(min_length=1)
    bg_color: str | None = None
    output_dir: str | None = None


class UpdateLyricsRequest(BaseModel):
    lyrics_text: str = Field(min_length=1)


class ResyncLyricsRequest(BaseModel):
    lyrics_text: str | None = None


@app.get("/health")
def health() -> dict:
    status = check_models()
    return {"ok": True, **status}


@app.get("/languages")
def languages() -> dict:
    return {"languages": list_languages()}


@app.get("/work")
def list_work() -> dict:
    return {"runs": list_work_runs()}


@app.get("/work/{work_id}")
def get_work(work_id: str) -> dict:
    try:
        return get_work_run(work_id)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.put("/work/{work_id}/lyrics")
def save_work_lyrics(work_id: str, request: UpdateLyricsRequest) -> dict:
    try:
        lines = update_work_lyrics(work_id, request.lyrics_text)
        return {
            "work_id": work_id,
            "line_count": len(lines),
            "lyrics_text": "\n".join(line.text for line in lines),
        }
    except (FileNotFoundError, RuntimeError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/setup/models")
def setup_models() -> StreamingResponse:
    def event_stream():
        try:
            for event in prefetch_models():
                yield f"data: {json.dumps(event)}\n\n"
        except Exception as exc:
            payload = {"step": "error", "message": str(exc), "error": True}
            yield f"data: {json.dumps(payload)}\n\n"

    return StreamingResponse(event_stream(), media_type="text/event-stream")


@app.post("/jobs/prepare")
def prepare_job(request: PrepareJobRequest) -> dict:
    status = check_models()
    if not status["models_ready"]:
        raise HTTPException(
            status_code=409,
            detail={
                "message": "Models are not ready. Run setup first.",
                "missing": status["missing"],
            },
        )
    if request.language is not None and not is_valid_language(request.language):
        raise HTTPException(status_code=400, detail=f"Unsupported language: {request.language}")

    try:
        job = job_store.create_prepare(
            youtube_url=request.youtube_url,
            lyrics_text=request.lyrics_text,
            bg_color=request.bg_color,
            output_dir=request.output_dir,
            vocal_blend_percent=request.vocal_blend_percent,
            language=request.language,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    return {
        "job_id": job.id,
        "work_id": job.work_id,
        "status": job.status.value,
    }


@app.post("/work/{work_id}/resync-lyrics")
def resync_work_lyrics(work_id: str, request: ResyncLyricsRequest) -> dict:
    status = check_models()
    if not status["models_ready"]:
        raise HTTPException(status_code=409, detail="Models are not ready.")

    try:
        job = job_store.create_resync(
            work_id=work_id,
            lyrics_text=request.lyrics_text,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    return {
        "job_id": job.id,
        "work_id": work_id,
        "status": job.status.value,
    }


@app.post("/work/{work_id}/render")
def render_work(work_id: str, request: RenderWorkRequest) -> dict:
    status = check_models()
    if not status["models_ready"]:
        raise HTTPException(status_code=409, detail="Models are not ready.")

    try:
        job = job_store.create_render(
            work_id=work_id,
            lyrics_text=request.lyrics_text,
            bg_color=request.bg_color,
            output_dir=request.output_dir,
        )
    except FileNotFoundError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    return {
        "job_id": job.id,
        "work_id": work_id,
        "status": job.status.value,
    }


# Backward-compatible alias
@app.post("/jobs")
def create_job(request: PrepareJobRequest) -> dict:
    return prepare_job(request)


@app.get("/jobs/{job_id}")
def get_job(job_id: str) -> dict:
    job = job_store.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")
    return {
        "job_id": job.id,
        "work_id": job.work_id,
        "status": job.status.value,
        "step": job.step,
        "message": job.message,
        "output_path": str(job.output_mp4) if job.output_mp4 else None,
        "error": job.error,
    }


def _is_terminal_status(status: JobStatus) -> bool:
    return status in {JobStatus.LYRICS_READY, JobStatus.COMPLETED, JobStatus.FAILED}


@app.get("/jobs/{job_id}/events")
async def job_events(job_id: str) -> StreamingResponse:
    job = job_store.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="Job not found")

    async def stream() -> AsyncIterator[str]:
        index = 0
        while True:
            events, index = job_store.events_since(job_id, index)
            for event in events:
                yield f"data: {json.dumps(event)}\n\n"
                if event.get("step") in {"complete", "error"}:
                    return

            current = job_store.get(job_id)
            if current and _is_terminal_status(current.status):
                remaining, index = job_store.events_since(job_id, index)
                for event in remaining:
                    yield f"data: {json.dumps(event)}\n\n"
                return

            await asyncio.sleep(0.5)

    return StreamingResponse(stream(), media_type="text/event-stream")
