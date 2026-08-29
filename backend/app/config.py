"""Central configuration for the RunBPM backend.

All values can be overridden with environment variables so the project
never hard-codes machine-specific paths. See backend/.env.example.
"""
from __future__ import annotations

import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent  # .../backend


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name, "")
    try:
        return int(raw) if raw else default
    except ValueError:
        return default


class Settings:
    # --- paths -----------------------------------------------------------
    DATA_DIR: Path = Path(os.environ.get("RUNBPM_DATA_DIR", str(BASE_DIR / "data")))
    DB_PATH: Path = DATA_DIR / "runbpm.db"
    UPLOAD_DIR: Path = DATA_DIR / "uploads"
    PROCESSED_DIR: Path = DATA_DIR / "processed"

    # --- upload limits ---------------------------------------------------
    MAX_UPLOAD_MB: int = _env_int("RUNBPM_MAX_UPLOAD_MB", 100)
    ALLOWED_EXTENSIONS: set[str] = {".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac", ".opus"}

    # --- task concurrency ------------------------------------------------
    MAX_CONCURRENT_ANALYZE: int = _env_int("RUNBPM_MAX_CONCURRENT_ANALYZE", 2)
    MAX_CONCURRENT_PROCESS: int = _env_int("RUNBPM_MAX_CONCURRENT_PROCESS", 1)

    # --- ffmpeg ----------------------------------------------------------
    # Priority: RUNBPM_FFMPEG_PATH env var -> `ffmpeg` on PATH -> bundled
    # binary shipped by the imageio-ffmpeg package (a static gyan.dev build).
    FFMPEG_PATH: str | None = os.environ.get("RUNBPM_FFMPEG_PATH") or None

    # --- BPM analysis ----------------------------------------------------
    # Only the first N seconds are analysed to keep uploads snappy.
    ANALYZE_DURATION_SECONDS: float = 90.0
    ANALYZE_SR: int = 22050

    # MIME types used when streaming stored files back to the browser.
    MIME_BY_EXT: dict[str, str] = {
        ".mp3": "audio/mpeg",
        ".wav": "audio/wav",
        ".m4a": "audio/mp4",
        ".aac": "audio/aac",
        ".ogg": "audio/ogg",
        ".flac": "audio/flac",
        ".opus": "audio/ogg",
    }

    def ensure_dirs(self) -> None:
        for d in (self.UPLOAD_DIR, self.PROCESSED_DIR):
            d.mkdir(parents=True, exist_ok=True)


settings = Settings()
