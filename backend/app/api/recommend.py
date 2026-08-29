"""Recommendation endpoint.

Rule-based and fully explainable:
    distance = abs(song_bpm - target_bpm)
Songs whose BPM is manually set but whose confidence is low get a small
penalty, then everything is sorted by distance (closest first).
"""
from __future__ import annotations

from fastapi import APIRouter, HTTPException

from .. import database
from ..schemas import RecommendRequest, Recommendation, SongOut

router = APIRouter(prefix="/api/recommend", tags=["recommend"])


def _stars(distance: float) -> int:
    if distance <= 3:
        return 5
    if distance <= 8:
        return 4
    if distance <= 16:
        return 3
    if distance <= 28:
        return 2
    return 1


def _adjusted_distance(song: dict, target_bpm: float) -> float:
    distance = abs(float(song["original_bpm"]) - target_bpm)
    confidence = song.get("bpm_confidence") or 0.0
    if confidence < 0.4:
        distance += 4.0  # low-confidence songs sink a bit in the ranking
    return distance


@router.post("", response_model=list[Recommendation])
async def recommend(payload: RecommendRequest) -> list[Recommendation]:
    if payload.song_ids:
        songs = [database.get_song(sid) for sid in payload.song_ids]
        songs = [s for s in songs if s is not None]
    else:
        songs = database.list_songs()

    eligible = [
        s for s in songs
        if s.get("bpm_status") == "done" and s.get("original_bpm")
    ]
    if not eligible:
        raise HTTPException(
            status_code=422,
            detail="没有可用于推荐的歌曲（请先上传并等待 BPM 分析完成，或手动设置 BPM）",
        )

    scored = []
    for s in eligible:
        dist = _adjusted_distance(s, payload.target_bpm)
        scored.append(
            Recommendation(
                song=SongOut(**s),
                distance=round(dist, 1),
                score=_stars(dist),
            )
        )
    scored.sort(key=lambda r: (r.distance, -float(r.song.bpm_confidence or 0)))
    return scored
