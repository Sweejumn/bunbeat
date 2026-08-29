"""Tiny SQLite layer. Plain stdlib sqlite3, one connection per call —
simple, thread-safe enough for this MVP, and dependency-free."""
from __future__ import annotations

import sqlite3
import uuid
from datetime import datetime, timezone
from typing import Any

from .config import settings


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _conn() -> sqlite3.Connection:
    settings.ensure_dirs()
    conn = sqlite3.connect(settings.DB_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def _row_to_dict(row: sqlite3.Row | None) -> dict[str, Any] | None:
    return dict(row) if row is not None else None


def init_db() -> None:
    settings.ensure_dirs()
    with _conn() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS songs (
                id            TEXT PRIMARY KEY,
                filename      TEXT NOT NULL,
                title         TEXT NOT NULL,
                artist        TEXT NOT NULL DEFAULT '',
                duration      REAL,
                original_bpm  REAL,
                bpm_confidence REAL,
                bpm_status    TEXT NOT NULL DEFAULT 'pending', -- pending|analyzing|done|failed
                bpm_error     TEXT,
                beat_offset   REAL,
                beat_times    TEXT,
                file_path     TEXT NOT NULL,
                mime_type     TEXT,
                size          INTEGER NOT NULL DEFAULT 0,
                created_at    TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS processing_tasks (
                id          TEXT PRIMARY KEY,
                song_id     TEXT NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
                target_bpm  REAL NOT NULL,
                status      TEXT NOT NULL DEFAULT 'pending', -- pending|processing|done|failed
                error       TEXT,
                output_path TEXT,
                processed_bpm REAL,
                processed_beat_times TEXT,
                created_at  TEXT NOT NULL,
                updated_at  TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_tasks_song_bpm
                ON processing_tasks(song_id, target_bpm);
            """
        )
        _migrate(conn)


def _migrate(conn: sqlite3.Connection) -> None:
    """Additive migrations for DBs created before a column existed."""
    cols = {row[1] for row in conn.execute("PRAGMA table_info(songs)")}
    if "beat_offset" not in cols:
        conn.execute("ALTER TABLE songs ADD COLUMN beat_offset REAL")
    if "beat_times" not in cols:
        conn.execute("ALTER TABLE songs ADD COLUMN beat_times TEXT")
    tcols = {row[1] for row in conn.execute("PRAGMA table_info(processing_tasks)")}
    if "processed_bpm" not in tcols:
        conn.execute("ALTER TABLE processing_tasks ADD COLUMN processed_bpm REAL")
    if "processed_beat_times" not in tcols:
        conn.execute("ALTER TABLE processing_tasks ADD COLUMN processed_beat_times TEXT")


# --------------------------------------------------------------------------
# songs
# --------------------------------------------------------------------------

def create_song(
    *,
    filename: str,
    title: str,
    artist: str,
    file_path: str,
    mime_type: str,
    size: int,
) -> dict[str, Any]:
    song_id = uuid.uuid4().hex
    now = _now()
    with _conn() as conn:
        conn.execute(
            "INSERT INTO songs (id, filename, title, artist, file_path, mime_type, size, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            (song_id, filename, title, artist, file_path, mime_type, size, now),
        )
    return get_song(song_id)  # type: ignore[return-value]


def get_song(song_id: str) -> dict[str, Any] | None:
    with _conn() as conn:
        row = conn.execute("SELECT * FROM songs WHERE id = ?", (song_id,)).fetchone()
    return _row_to_dict(row)


def list_songs() -> list[dict[str, Any]]:
    with _conn() as conn:
        rows = conn.execute("SELECT * FROM songs ORDER BY created_at DESC").fetchall()
    return [dict(r) for r in rows]


def update_song(song_id: str, **fields: Any) -> dict[str, Any] | None:
    if not fields:
        return get_song(song_id)
    allowed = {
        "title", "artist", "duration", "original_bpm", "bpm_confidence",
        "bpm_status", "bpm_error", "beat_offset", "beat_times",
        "file_path", "mime_type", "size",
    }
    cols = [k for k in fields if k in allowed]
    if not cols:
        return get_song(song_id)
    sets = ", ".join(f"{c} = ?" for c in cols)
    with _conn() as conn:
        conn.execute(f"UPDATE songs SET {sets} WHERE id = ?", (*[fields[c] for c in cols], song_id))
    return get_song(song_id)


def delete_song(song_id: str) -> bool:
    with _conn() as conn:
        cur = conn.execute("DELETE FROM songs WHERE id = ?", (song_id,))
        return cur.rowcount > 0


# --------------------------------------------------------------------------
# processing tasks
# --------------------------------------------------------------------------

def create_task(song_id: str, target_bpm: float) -> dict[str, Any]:
    task_id = uuid.uuid4().hex
    now = _now()
    with _conn() as conn:
        conn.execute(
            "INSERT INTO processing_tasks (id, song_id, target_bpm, status, created_at, updated_at) "
            "VALUES (?, ?, ?, 'pending', ?, ?)",
            (task_id, song_id, target_bpm, now, now),
        )
    task = get_task(task_id)
    assert task is not None
    return task


def get_task(task_id: str) -> dict[str, Any] | None:
    with _conn() as conn:
        row = conn.execute("SELECT * FROM processing_tasks WHERE id = ?", (task_id,)).fetchone()
    return _row_to_dict(row)


def find_task(song_id: str, target_bpm: float) -> dict[str, Any] | None:
    with _conn() as conn:
        row = conn.execute(
            "SELECT * FROM processing_tasks WHERE song_id = ? AND target_bpm = ? "
            "ORDER BY created_at DESC LIMIT 1",
            (song_id, target_bpm),
        ).fetchone()
    return _row_to_dict(row)


def update_task(task_id: str, **fields: Any) -> dict[str, Any] | None:
    allowed = {"status", "error", "output_path", "processed_bpm", "processed_beat_times", "updated_at"}
    cols = [k for k in fields if k in allowed]
    if not cols:
        return get_task(task_id)
    sets = ", ".join(f"{c} = ?" for c in cols)
    values = [fields[c] for c in cols]
    values.append(_now())
    with _conn() as conn:
        conn.execute(f"UPDATE processing_tasks SET {sets}, updated_at = ? WHERE id = ?", (*values, task_id))
    return get_task(task_id)
