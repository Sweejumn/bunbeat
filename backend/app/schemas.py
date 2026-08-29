"""Pydantic request/response schemas."""
from __future__ import annotations

from typing import Any, Optional

from pydantic import BaseModel, Field


# --- songs ---------------------------------------------------------------

class SongOut(BaseModel):
    id: str
    filename: str
    title: str
    artist: str
    duration: Optional[float] = None
    original_bpm: Optional[float] = None
    bpm_confidence: Optional[float] = None
    bpm_status: str
    bpm_error: Optional[str] = None
    beat_offset: Optional[float] = None
    mime_type: Optional[str] = None
    size: int
    created_at: str


class SongUpdate(BaseModel):
    title: Optional[str] = None
    artist: Optional[str] = None
    original_bpm: Optional[float] = Field(default=None, ge=20, le=400)


# --- recommendation ------------------------------------------------------

class RecommendRequest(BaseModel):
    target_bpm: float = Field(ge=40, le=320)
    song_ids: Optional[list[str]] = None  # if omitted, all analysed songs


class Recommendation(BaseModel):
    song: SongOut
    distance: float
    score: int  # 1..5 stars


# --- processing ----------------------------------------------------------

class ProcessRequest(BaseModel):
    song_id: str
    target_bpm: float = Field(ge=40, le=320)


class ProcessTaskOut(BaseModel):
    id: str
    song_id: str
    target_bpm: float
    status: str
    error: Optional[str] = None
    created_at: str
    updated_at: str
    processed_url: Optional[str] = None


class ProcessBatchRequest(BaseModel):
    song_ids: list[str]
    target_bpm: float = Field(ge=40, le=320)


class ProcessBatchOut(BaseModel):
    tasks: list[ProcessTaskOut]


# --- health --------------------------------------------------------------

class HealthOut(BaseModel):
    status: str
    version: str
    ffmpeg: str
    data_dir: str
    songs: int
