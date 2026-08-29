import { useState } from 'react'
import type { Song } from '../types'
import { api } from '../api'
import { confidenceLabel, formatBpm, formatDuration } from '../lib/format'

interface Props {
  songs: Song[]
  onChanged: () => void
  onToast: (msg: string, kind?: 'error' | 'ok') => void
}

function BpmCell({ song, onChanged, onToast }: { song: Song; onChanged: () => void; onToast: Props['onToast'] }) {
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState('')

  const save = async () => {
    const n = Number(value)
    if (!Number.isFinite(n) || n < 20 || n > 400) {
      onToast('BPM 需在 20–400 之间', 'error')
      return
    }
    try {
      await api.updateSong(song.id, { original_bpm: n })
      onToast(`已手动设置 BPM 为 ${n}`, 'ok')
      onChanged()
    } catch (e) {
      onToast(e instanceof Error ? e.message : '保存失败', 'error')
    } finally {
      setEditing(false)
    }
  }

  if (song.bpm_status === 'analyzing')
    return (
      <span className="inline-flex items-center gap-1.5 text-sm text-white/60">
        <span className="inline-block size-3 animate-spin rounded-full border-2 border-run border-t-transparent" />
        分析中…
      </span>
    )
  if (song.bpm_status === 'pending')
    return <span className="text-sm text-white/40">排队中…</span>
  if (song.bpm_status === 'failed')
    return (
      <span className="flex items-center gap-2 text-sm text-red-400">
        分析失败
        <button
          className="rounded-md bg-card px-2 py-0.5 text-xs text-run hover:bg-line"
          title={song.bpm_error ?? ''}
          onClick={async () => {
            try {
              await api.analyzeSong(song.id)
              onChanged()
            } catch (e) {
              onToast(e instanceof Error ? e.message : '重试失败', 'error')
            }
          }}
        >
          重试
        </button>
      </span>
    )

  const conf = song.bpm_confidence ?? 0
  const lowConf = conf < 0.6
  return (
    <div className="flex items-center gap-2">
      {editing ? (
        <input
          autoFocus
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onBlur={save}
          onKeyDown={(e) => {
            if (e.key === 'Enter') save()
            if (e.key === 'Escape') setEditing(false)
          }}
          className="w-20 rounded-md border border-run bg-ink px-2 py-1 text-sm text-white outline-none"
          placeholder={formatBpm(song.original_bpm)}
        />
      ) : (
        <button
          className={`rounded-lg px-2.5 py-1 font-mono text-base font-bold ${
            lowConf ? 'bg-amber-500/15 text-accent' : 'bg-run/10 text-run'
          }`}
          title="点击手动修改 BPM"
          onClick={() => {
            setValue(String(song.original_bpm ?? ''))
            setEditing(true)
          }}
        >
          {formatBpm(song.original_bpm)}
        </button>
      )}
      <span className={`text-xs ${lowConf ? 'text-amber-400' : 'text-white/40'}`}>
        可信度{confidenceLabel(conf)}
      </span>
    </div>
  )
}

export function SongTable({ songs, onChanged, onToast }: Props) {
  if (songs.length === 0) return null
  return (
    <section>
      <h2 className="mb-3 text-lg font-bold text-white">我的音乐库</h2>
      <div className="overflow-hidden rounded-xl border border-line bg-panel">
        <ul className="divide-y divide-line">
          {songs.map((s) => (
            <li key={s.id} className="flex items-center gap-3 px-4 py-3">
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-white">{s.title}</p>
                <p className="truncate text-xs text-white/40">
                  {s.artist} · {s.filename}
                </p>
              </div>
              <div className="hidden w-14 text-right text-xs text-white/40 sm:block">
                {formatDuration(s.duration)}
              </div>
              <div className="w-44 text-right">
                <BpmCell song={s} onChanged={onChanged} onToast={onToast} />
              </div>
              <button
                className="rounded-md p-1.5 text-white/30 hover:bg-red-500/10 hover:text-red-400"
                title="删除"
                onClick={async () => {
                  if (!window.confirm(`删除「${s.title}」？`)) return
                  try {
                    await api.deleteSong(s.id)
                    onChanged()
                  } catch (e) {
                    onToast(e instanceof Error ? e.message : '删除失败', 'error')
                  }
                }}
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6" />
                </svg>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}
