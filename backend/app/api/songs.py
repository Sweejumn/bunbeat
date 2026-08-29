"""Song upload / listing / metadata endpoints."""
from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, File, HTTPException, UploadFile
from pydantic import BaseModel

from .. import database
from ..config import settings
from ..services import tasks
from ..services.ffmpeg_tools import clean_filename_stem
from ..schemas import SongOut, SongUpdate

router = APIRouter(prefix="/api/songs", tags=["songs"])

MAX_UPLOAD_BYTES = settings.MAX_UPLOAD_MB * 1024 * 1024


def _to_song_out(row: dict) -> SongOut:
    return SongOut(**row)


def _extract_tags(file_path: Path, filename: str) -> tuple[str, str]:
    """Best-effort title/artist from ID3 tags, falling back to the filename."""
    try:
        from mutagen import File as MutagenFile

        meta = MutagenFile(str(file_path), easy=True)
        if meta is not None:
            title = str(meta.get("title", [""])[0] or "").strip()
            artist = str(meta.get("artist", [""])[0] or "").strip()
            if title:
                return title, artist
    except Exception:  # noqa: BLE001
        pass
    stem = clean_filename_stem(filename)
    parts = [p.strip() for p in stem.split(" - ", 1)]
    if len(parts) == 2:
        return parts[1], parts[0]
    return stem, ""


@router.post("/upload", response_model=list[SongOut])
async def upload_songs(files: list[UploadFile] = File(...)) -> list[SongOut]:
    if not files:
        raise HTTPException(status_code=400, detail="没有收到任何文件")
    if len(files) > 50:
        raise HTTPException(status_code=400, detail="一次最多上传 50 个文件")

    created: list[SongOut] = []
    for up in files:
        ext = Path(up.filename or "").suffix.lower()
        if ext not in settings.ALLOWED_EXTENSIONS:
            raise HTTPException(
                status_code=415,
                detail=f"不支持的文件类型：{ext or '(无后缀)'}（支持 {', '.join(sorted(settings.ALLOWED_EXTENSIONS))}）",
            )

        data = await up.read()
        if len(data) == 0:
            raise HTTPException(status_code=400, detail=f"文件为空：{up.filename}")
        if len(data) > MAX_UPLOAD_BYTES:
            raise HTTPException(
                status_code=413,
                detail=f"文件过大：{up.filename}（{len(data) / 1024 / 1024:.1f} MB，上限 {settings.MAX_UPLOAD_MB} MB）",
            )

        settings.ensure_dirs()
        song = database.create_song(
            filename=up.filename or "未命名",
            title="",
            artist="",
            file_path="",
            mime_type=settings.MIME_BY_EXT.get(ext, "application/octet-stream"),
            size=len(data),
        )
        target = settings.UPLOAD_DIR / f"{song['id']}{ext}"
        target.write_bytes(data)
        title, artist = _extract_tags(target, up.filename or "")
        database.update_song(
            song["id"],
            file_path=str(target),
            title=title or clean_filename_stem(up.filename or ""),
            artist=artist or "未知歌手",
        )
        tasks.enqueue_analysis(song["id"])
        created.append(_to_song_out(database.get_song(song["id"])))  # type: ignore[arg-type]

    return created


@router.get("", response_model=list[SongOut])
async def list_all() -> list[SongOut]:
    return [_to_song_out(s) for s in database.list_songs()]


@router.get("/{song_id}", response_model=SongOut)
async def get_one(song_id: str) -> SongOut:
    song = database.get_song(song_id)
    if song is None:
        raise HTTPException(status_code=404, detail="歌曲不存在")
    return _to_song_out(song)


@router.patch("/{song_id}", response_model=SongOut)
async def update_one(song_id: str, payload: SongUpdate) -> SongOut:
    song = database.get_song(song_id)
    if song is None:
        raise HTTPException(status_code=404, detail="歌曲不存在")
    fields = payload.model_dump(exclude_none=True)
    if "original_bpm" in fields and fields["original_bpm"] is not None:
        fields["bpm_status"] = "done"
        fields["bpm_error"] = None
        fields["bpm_confidence"] = fields.get("bpm_confidence") or (song.get("bpm_confidence") or 0.5)
    updated = database.update_song(song_id, **fields)
    return _to_song_out(updated)  # type: ignore[arg-type]


@router.post("/{song_id}/analyze", response_model=SongOut)
async def analyze_one(song_id: str) -> SongOut:
    song = database.get_song(song_id)
    if song is None:
        raise HTTPException(status_code=404, detail="歌曲不存在")
    tasks.enqueue_analysis(song_id)
    return _to_song_out(database.update_song(song_id, bpm_status="analyzing", bpm_error=None))  # type: ignore[arg-type]


@router.delete("/{song_id}", status_code=204)
async def delete_one(song_id: str) -> None:
    song = database.get_song(song_id)
    if song is None:
        raise HTTPException(status_code=404, detail="歌曲不存在")
    from ..services.processor import cleanup_song_files

    cleanup_song_files(song)
    database.delete_song(song_id)
