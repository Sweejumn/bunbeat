"""Thin wrapper around FFmpeg.

FFmpeg is located in this order:
  1. RUNBPM_FFMPEG_PATH environment variable
  2. `ffmpeg` / `ffprobe` on PATH
  3. the static binary bundled with the imageio-ffmpeg package

Subprocess calls never capture piped stdout/stderr (which the DSH Windows
sandbox forbids); stderr goes to DEVNULL or a log file.
"""
from __future__ import annotations

import logging
import os
import shutil
import subprocess
from pathlib import Path

from ..config import settings

logger = logging.getLogger(__name__)


def get_ffmpeg() -> str:
    if settings.FFMPEG_PATH and Path(settings.FFMPEG_PATH).exists():
        return settings.FFMPEG_PATH
    on_path = shutil.which("ffmpeg")
    if on_path:
        return on_path
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception as exc:  # pragma: no cover
        raise RuntimeError("FFmpeg 不可用：请安装 ffmpeg 或设置 RUNBPM_FFMPEG_PATH") from exc


def ffmpeg_available() -> bool:
    try:
        subprocess.run(
            [get_ffmpeg(), "-version"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=15,
        )
        return True
    except Exception:
        return False


def ffmpeg_version() -> str:
    try:
        out = subprocess.run(
            [get_ffmpeg(), "-version"],
            capture_output=True,
            text=True,
            timeout=15,
        )
        first = (out.stdout or "").splitlines()
        return first[0] if first else "unknown"
    except Exception:
        return "unavailable"


def probe_duration(path: Path) -> float | None:
    """Duration in seconds via ffprobe-style `ffmpeg -i` parse.

    Note: stderr goes to a temp file (not a pipe) because piped stdio is
    blocked in restricted sandboxes.
    """
    import tempfile

    cmd = [get_ffmpeg(), "-hide_banner", "-i", str(path)]
    fd, tmp = tempfile.mkstemp(suffix=".log")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", errors="replace") as f:
            proc = subprocess.run(
                cmd, stdout=subprocess.DEVNULL, stderr=f, timeout=60
            )
        text = Path(tmp).read_text(encoding="utf-8", errors="replace")
        for line in text.splitlines():
            if "Duration:" in line:
                token = line.split("Duration:")[1].split(",")[0].strip()
                parts = token.split(":")
                if len(parts) == 3:
                    return int(parts[0]) * 3600 + int(parts[1]) * 60 + float(parts[2])
    except Exception as exc:
        logger.warning("probe_duration failed for %s: %s", path, exc)
    finally:
        try:
            Path(tmp).unlink(missing_ok=True)
        except OSError:
            pass
    return None


def build_atempo_chain(ratio: float) -> str:
    """atempo accepts 0.5..2.0; chain filters for ratios outside that range."""
    filters: list[str] = []
    while ratio > 2.0:
        filters.append("atempo=2.0")
        ratio /= 2.0
    while ratio < 0.5:
        filters.append("atempo=0.5")
        ratio /= 0.5
    filters.append(f"atempo={ratio:.6f}")
    return ",".join(filters)


def time_stretch(
    src: Path,
    dst: Path,
    ratio: float,
    *,
    out_ext: str = ".mp3",
    log_file: Path | None = None,
) -> None:
    """Pitch-preserving tempo change via the FFmpeg `atempo` filter.

    Raises RuntimeError with a readable message on failure.
    """
    if ratio <= 0:
        raise ValueError(f"invalid tempo ratio: {ratio}")
    if not (0.05 <= ratio <= 20):
        raise ValueError(f"tempo ratio out of supported range: {ratio}")

    atempo = build_atempo_chain(ratio)
    codec_args = ["-c:a", "libmp3lame", "-q:a", "2"] if out_ext == ".mp3" else []
    dst = dst.with_suffix(out_ext)

    cmd = [
        get_ffmpeg(),
        "-y",
        "-hide_banner",
        "-loglevel", "error",
        "-i", str(src),
        "-vn",
        "-af", atempo,
        *codec_args,
        str(dst),
    ]
    stderr_target: object
    if log_file is not None:
        log_file.parent.mkdir(parents=True, exist_ok=True)
        stderr_target = log_file.open("w", encoding="utf-8")
    else:
        stderr_target = subprocess.DEVNULL

    try:
        proc = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=stderr_target, timeout=1800)
    finally:
        if hasattr(stderr_target, "close"):
            stderr_target.close()  # type: ignore[union-attr]

    if proc.returncode != 0:
        detail = ""
        if log_file is not None and log_file.exists():
            detail = log_file.read_text(encoding="utf-8", errors="replace")[-500:]
        raise RuntimeError(f"FFmpeg time-stretch 失败 (exit {proc.returncode}) {detail}")
    if not dst.exists() or dst.stat().st_size == 0:
        raise RuntimeError("FFmpeg time-stretch 未生成输出文件")


def clean_filename_stem(filename: str) -> str:
    return Path(filename).stem.strip() or "未命名歌曲"
