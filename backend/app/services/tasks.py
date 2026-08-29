"""Asynchronous background task manager.

Two bounded queues (BPM analysis, tempo processing) each run by a small
worker pool, so uploads never block and the server cannot be flooded with
unlimited concurrent FFmpeg/librosa processes.
"""
from __future__ import annotations

import asyncio
import json
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
    """Serialise N async jobs with `concurrency` workers.

    The pending queue is capped so a buggy caller cannot flood the server
    with unbounded background work.
    """

    MAX_PENDING = 300

    def __init__(self, name: str, concurrency: int):
        self.name = name
        self.concurrency = concurrency
        self._queue: asyncio.Queue[Callable[[], Awaitable[None]]] = asyncio.Queue(maxsize=self.MAX_PENDING)
        self._workers: list[asyncio.Task] = []

    @property
    def pending(self) -> int:
        return self._queue.qsize()

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

    def submit(self, job: Callable[[], Awaitable[None]]) -> bool:
        self.start()
        try:
            self._queue.put_nowait(job)
            return True
        except asyncio.QueueFull:
            logger.error("[%s] 后台任务队列已满（%d），拒绝新任务——可能存在失控提交",
                         self.name, self.MAX_PENDING)
            return False


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
            beat_times=json.dumps(result.beat_times) if result.beat_times else None,
            beat_maps=json.dumps(result.beat_maps) if result.beat_maps else None,
            phase_reliability=result.phase_reliability,
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
        # Analyse the STRETCHED file for its phase-anchored maps (light/snap
        # modes). The DEFAULT grid mode is calibrated separately below because
        # a fresh onset analysis of the stretched file carries a systematic
        # period bias (measured -0.5..-1% on real music, accumulating into
        # audible drift).
        try:
            result = await asyncio.to_thread(analyze_bpm, out)
            processed_bpm = result.bpm if result.bpm else None
            processed_maps = result.beat_maps or {}
            # Calibrate the grid variant: map the ORIGINAL grid through the
            # MEASURED duration ratio (d_orig / d_proc), which is exact up to
            # atempo's uniformity (~0.1%) and has no onset-detection bias.
            try:
                d_proc = probe_duration(out)
                d_orig = song.get("duration")
                orig_grid = json.loads(song.get("beat_times") or "[]") if song.get("beat_times") else []
                if d_proc and d_orig and len(orig_grid) >= 10:
                    ratio = d_orig / d_proc
                    processed_maps["grid"] = [round(t / ratio, 3) for t in orig_grid]
            except Exception:  # noqa: BLE001
                logger.warning("grid calibration failed for %s", song["filename"])
            processed_beats = json.dumps(processed_maps.get("grid")) if processed_maps.get("grid") else None
        except Exception:  # noqa: BLE001
            logger.warning("post-process beat analysis failed for %s", song["filename"])
            processed_bpm, processed_beats, processed_maps = None, None, None
        database.update_task(
            task_id,
            status="done",
            error=None,
            output_path=str(out),
            processed_bpm=processed_bpm,
            processed_beat_times=processed_beats,
            processed_beat_maps=json.dumps(processed_maps) if processed_maps else None,
        )
        logger.info("processed %s -> %s", song["filename"], out)
    except Exception as exc:  # noqa: BLE001
        logger.exception("processing failed for %s", song["filename"])
        database.update_task(task_id, status="failed", error=str(exc))


# --------------------------------------------------------------------------
# public entry points (used by the API layer)
# --------------------------------------------------------------------------

def enqueue_analysis(song_id: str) -> None:
    ok = analysis_queue.submit(lambda: run_analysis(song_id))
    if not ok:
        # Queue full (runaway submission guard): mark failed so the row does
        # not hang in 'analyzing' forever.
        database.update_song(song_id, bpm_status="failed", bpm_error="分析队列已满，请稍后重试")


def enqueue_processing(song_id: str, target_bpm: float) -> dict:
    task = database.create_task(song_id, target_bpm)
    ok = process_queue.submit(lambda: run_processing(task["id"]))
    if not ok:
        database.update_task(task["id"], status="failed", error="处理队列已满，请稍后重试")
    return task
