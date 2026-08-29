"""Audio streaming endpoints (original + processed)."""
from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import FileResponse

from .. import database
from ..services.processor import processed_path

router = APIRouter(prefix="/api/audio", tags=["audio"])


def _file_response(path, mime: str, filename: str) -> FileResponse:
    return FileResponse(str(path), media_type=mime, filename=filename)


@router.get("/{song_id}")
async def audio_original(song_id: str) -> FileResponse:
    song = database.get_song(song_id)
    if song is None:
        raise HTTPException(status_code=404, detail="歌曲不存在")
    path = song.get("file_path")
    if not path or not Path(path).exists():
        raise HTTPException(status_code=404, detail="音频文件不存在")
    return _file_response(
        path,
        song.get("mime_type") or "application/octet-stream",
        song["filename"],
    )


@router.get("/{song_id}/processed")
async def audio_processed(
    song_id: str,
    target_bpm: float = Query(ge=40, le=320),
) -> FileResponse:
    song = database.get_song(song_id)
    if song is None:
        raise HTTPException(status_code=404, detail="歌曲不存在")
    task = database.find_task(song_id, target_bpm)
    out = processed_path(song_id, target_bpm)
    if task and task["status"] == "done" and out.exists():
        return _file_response(out, "audio/mpeg", f"{song['filename'].rsplit('.', 1)[0]}__{target_bpm:g}bpm.mp3")
    if task and task["status"] == "failed":
        raise HTTPException(status_code=422, detail=f"处理失败：{task.get('error') or '未知错误'}")
    raise HTTPException(status_code=409, detail="处理尚未完成，请稍后重试")
