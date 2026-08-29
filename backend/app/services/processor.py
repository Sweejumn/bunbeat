"""Time-stretch songs to a target BPM with pitch preserved (FFmpeg atempo).

Results are cached on disk: one processed file per (song_id, target_bpm)
pair, so the same request never re-runs FFmpeg.
"""
from __future__ import annotations

import logging
from pathlib import Path

from ..config import settings
from ..database import find_task, get_song, update_task
from .ffmpeg_tools import time_stretch

logger = logging.getLogger(__name__)

#: sanity bound for tempo ratio — beyond this we refuse instead of producing
#: garbage audio.
MAX_RATIO = 3.0


def processed_path(song_id: str, target_bpm: float) -> Path:
    return settings.PROCESSED_DIR / f"{song_id}__{target_bpm:.1f}.mp3"


def stretch_to_bpm(song: dict, target_bpm: float) -> Path:
    """Blocking call (run inside a worker thread). Returns output path."""
    src = Path(song["file_path"])
    if not src.exists():
        raise FileNotFoundError("源音频文件不存在")

    source_bpm = song.get("original_bpm")
    if not source_bpm or source_bpm <= 0:
        raise RuntimeError("歌曲没有可用的 BPM，无法调整速度")

    ratio = target_bpm / float(source_bpm)
    if not (1 / MAX_RATIO <= ratio <= MAX_RATIO):
        raise RuntimeError(
            f"目标 BPM {target_bpm:g} 与歌曲 BPM {source_bpm:g} 差距过大，"
            f"为避免严重失真已取消处理（允许比例 {1 / MAX_RATIO:g}–{MAX_RATIO:g}）"
        )

    out = processed_path(song["id"], target_bpm)
    if out.exists() and out.stat().st_size > 0:
        return out  # cache hit

    log_file = settings.DATA_DIR / "logs" / f"stretch_{song['id']}_{target_bpm:.1f}.log"
    time_stretch(src, out, ratio, out_ext=".mp3", log_file=log_file)
    return out


def ensure_processed(song_id: str, target_bpm: float) -> dict:
    """Create/find a processing task row; called from the API layer."""
    existing = find_task(song_id, target_bpm)
    if existing and existing["status"] in ("pending", "processing", "done"):
        return existing
    # A failed task can be retried by creating a fresh one.
    from ..services.tasks import enqueue_processing

    task = enqueue_processing(song_id, target_bpm)
    return task


def cleanup_song_files(song: dict) -> None:
    """Delete a song's upload plus every processed variant."""
    src = Path(song["file_path"])
    if src.exists():
        try:
            src.unlink()
        except OSError:
            logger.warning("could not delete %s", src)
    for f in settings.PROCESSED_DIR.glob(f"{song['id']}__*.mp3"):
        try:
            f.unlink()
        except OSError:
            logger.warning("could not delete %s", f)
