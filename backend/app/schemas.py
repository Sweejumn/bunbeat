"""Pydantic request/response schemas."""
from __future__ import annotations

import json
from typing import Any, Optional

from pydantic import BaseModel, Field, field_validator


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
    beat_times: Optional[list[float]] = None
    beat_maps: Optional[dict[str, list[float]]] = None
    phase_reliability: Optional[float] = None
    mime_type: Optional[str] = None
    size: int
    created_at: str

    @field_validator("beat_times", "beat_maps", mode="before")
    @classmethod
    def _parse_json(cls, v: Any) -> Any:
        if isinstance(v, str):
            try:
                return json.loads(v)
            except ValueError:
                return None
        return v


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
    processed_bpm: Optional[float] = None
    processed_beat_times: Optional[list[float]] = None
    processed_beat_maps: Optional[dict[str, list[float]]] = None

    @field_validator("processed_beat_times", "processed_beat_maps", mode="before")
    @classmethod
    def _parse_beats(cls, v: Any) -> Any:
        if isinstance(v, str):
            try:
                return json.loads(v)
            except ValueError:
                return None
        return v


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
