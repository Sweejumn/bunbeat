"""Processing (time-stretch) endpoints."""
from __future__ import annotations

from fastapi import APIRouter, HTTPException

from .. import database
from ..schemas import ProcessBatchOut, ProcessBatchRequest, ProcessRequest, ProcessTaskOut
from ..services.processor import ensure_processed, processed_path

router = APIRouter(prefix="/api/process", tags=["process"])


def _task_out(task: dict) -> ProcessTaskOut:
    out = ProcessTaskOut(**task)
    if task.get("status") == "done" and task.get("output_path"):
        out.processed_url = f"/api/audio/{task['song_id']}/processed?target_bpm={task['target_bpm']:g}"
    return out


@router.post("", response_model=ProcessTaskOut)
async def process_one(payload: ProcessRequest) -> ProcessTaskOut:
    song = database.get_song(payload.song_id)
    if song is None:
        raise HTTPException(status_code=404, detail="歌曲不存在")
    if not song.get("original_bpm"):
        raise HTTPException(status_code=422, detail="歌曲没有 BPM，无法处理（请先分析或手动设置）")
    task = ensure_processed(payload.song_id, payload.target_bpm)
    return _task_out(task)


@router.post("/batch", response_model=ProcessBatchOut)
async def process_batch(payload: ProcessBatchRequest) -> ProcessBatchOut:
    if not payload.song_ids:
        raise HTTPException(status_code=400, detail="song_ids 不能为空")
    tasks = []
    for sid in payload.song_ids:
        song = database.get_song(sid)
        if song is None or not song.get("original_bpm"):
            continue
        tasks.append(ensure_processed(sid, payload.target_bpm))
    return ProcessBatchOut(tasks=[_task_out(t) for t in tasks])


@router.get("/tasks/{task_id}", response_model=ProcessTaskOut)
async def get_task(task_id: str) -> ProcessTaskOut:
    task = database.get_task(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="任务不存在")
    return _task_out(task)
