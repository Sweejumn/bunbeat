"""Asynchronous background task manager.

Two bounded queues (BPM analysis, tempo processing) each run by a small
worker pool, so uploads never block and the server cannot be flooded with
unlimited concurrent FFmpeg/librosa processes.
"""
from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from pathlib import Path

from .. import database
from ..config import settings
from .bpm import analyze_bpm
from .ffmpeg_tools import probe_duration
from .processor import stretch_to_bpm

logger = logging.getLogger(__name__)


class TaskQueue:
    """Serialise N async jobs with `concurrency` workers."""

    def __init__(self, name: str, concurrency: int):
        self.name = name
        self.concurrency = concurrency
        self._queue: asyncio.Queue[Callable[[], Awaitable[None]]] = asyncio.Queue()
        self._workers: list[asyncio.Task] = []

    def start(self) -> None:
        if self._workers:
            return
        for i in range(self.concurrency):
            self._workers.append(asyncio.create_task(self._worker(i), name=f"{self.name}-{i}"))

    async def _worker(self, idx: int) -> None:
        while True:
            job = await self._queue.get()
            try:
                await job()
            except Exception:  # noqa: BLE001
                logger.exception("[%s-%d] background job crashed", self.name, idx)
            finally:
                self._queue.task_done()

    def submit(self, job: Callable[[], Awaitable[None]]) -> None:
        self.start()
        self._queue.put_nowait(job)


analysis_queue = TaskQueue("analyze", settings.MAX_CONCURRENT_ANALYZE)
process_queue = TaskQueue("process", settings.MAX_CONCURRENT_PROCESS)


# --------------------------------------------------------------------------
# jobs
# --------------------------------------------------------------------------

async def run_analysis(song_id: str) -> None:
    song = database.get_song(song_id)
    if song is None:
        return
    database.update_song(song_id, bpm_status="analyzing")
    logger.info("analyzing %s", song["filename"])
    try:
        result = await asyncio.to_thread(analyze_bpm, Path(song["file_path"]))
        if result.error:
            database.update_song(song_id, bpm_status="failed", bpm_error=result.error)
            return
        duration = result.duration or probe_duration(Path(song["file_path"]))
        database.update_song(
            song_id,
            bpm_status="done",
            bpm_error=None,
            original_bpm=result.bpm,
            bpm_confidence=result.confidence,
            duration=duration,
            beat_offset=result.beat_offset,
        )
        logger.info("song %s -> %s BPM (conf=%.2f)", song["filename"], result.bpm, result.confidence)
    except Exception as exc:  # noqa: BLE001
        logger.exception("analysis failed for %s", song["filename"])
        database.update_song(song_id, bpm_status="failed", bpm_error=str(exc))


async def run_processing(task_id: str) -> None:
    task = database.get_task(task_id)
    if task is None:
        return
    song = database.get_song(task["song_id"])
    if song is None:
        database.update_task(task_id, status="failed", error="歌曲不存在")
        return
    database.update_task(task_id, status="processing")
    logger.info("processing %s -> %s BPM", song["filename"], task["target_bpm"])
    try:
        out = await asyncio.to_thread(stretch_to_bpm, song, task["target_bpm"])
        database.update_task(task_id, status="done", error=None, output_path=str(out))
        logger.info("processed %s -> %s", song["filename"], out)
    except Exception as exc:  # noqa: BLE001
        logger.exception("processing failed for %s", song["filename"])
        database.update_task(task_id, status="failed", error=str(exc))


# --------------------------------------------------------------------------
# public entry points (used by the API layer)
# --------------------------------------------------------------------------

def enqueue_analysis(song_id: str) -> None:
    analysis_queue.submit(lambda: run_analysis(song_id))


def enqueue_processing(song_id: str, target_bpm: float) -> dict:
    task = database.create_task(song_id, target_bpm)
    process_queue.submit(lambda: run_processing(task["id"]))
    return task
