"""RunBPM backend entry point.

Start with:  uvicorn app.main:app --host 127.0.0.1 --port 8000
(or `python run.py`)
"""
from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from . import database
from .api import audio, process, recommend, songs
from .config import settings
from .schemas import HealthOut
from .services import tasks
from .services.ffmpeg_tools import ffmpeg_available, ffmpeg_version

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
logger = logging.getLogger("runbpm")

APP_VERSION = "0.1.0"


@asynccontextmanager
async def lifespan(app: FastAPI):
    database.init_db()
    settings.ensure_dirs()
    tasks.analysis_queue.start()
    tasks.process_queue.start()
    ffmpeg_ok = ffmpeg_available()
    logger.info("RunBPM v%s started | ffmpeg: %s", APP_VERSION, "OK" if ffmpeg_ok else "MISSING")
    diag_task = asyncio.create_task(_diagnostics_loop())
    yield
    diag_task.cancel()


async def _diagnostics_loop(interval: float = 30.0) -> None:
    """Periodic resource health log: background queues, task counts and
    process CPU/memory. Helps locate runaway work if the machine freezes."""
    import psutil

    proc = psutil.Process()
    while True:
        try:
            proc.cpu_percent(None)  # prime the counter
            cpu = proc.cpu_percent(None)
            mem = proc.memory_info().rss / 1024 / 1024
            counts = database._counts_by_status()
            logger.info(
                "diag | cpu=%.0f%% mem=%.0fMB | queues: analyze=%d process=%d | tasks: %s",
                cpu, mem, tasks.analysis_queue.pending, tasks.process_queue.pending,
                " ".join(f"{k}={v}" for k, v in sorted(counts.items())),
            )
        except Exception:  # noqa: BLE001
            logger.exception("diagnostics loop error")
        await asyncio.sleep(interval)


app = FastAPI(title="RunBPM", version=APP_VERSION, lifespan=lifespan)

# Dev convenience: the Vite dev server (http://localhost:5173) talks to this
# API through its proxy, but keep CORS open anyway for local tooling.
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:5173",
        "http://127.0.0.1:5173",
        "http://localhost:4173",
        "http://127.0.0.1:4173",
    ],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(songs.router)
app.include_router(recommend.router)
app.include_router(process.router)
app.include_router(audio.router)


@app.get("/api/health", response_model=HealthOut)
async def health() -> HealthOut:
    return HealthOut(
        status="ok",
        version=APP_VERSION,
        ffmpeg=ffmpeg_version(),
        data_dir=str(settings.DATA_DIR),
        songs=len(database.list_songs()),
    )


# Serve the built frontend (frontend/dist) when it exists — production mode.
_frontend_dist = Path(__file__).resolve().parent.parent.parent / "frontend" / "dist"
if _frontend_dist.exists():
    app.mount(
        "/",
        StaticFiles(directory=str(_frontend_dist), html=True),
        name="frontend",
    )


@app.exception_handler(Exception)
async def unhandled_exception_handler(request, exc: Exception):
    logger.exception("unhandled error on %s", request.url.path)
    return JSONResponse(status_code=500, content={"detail": "服务器内部错误"})
