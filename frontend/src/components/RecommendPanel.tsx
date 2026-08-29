import { useEffect, useMemo, useState } from 'react'
import type { Recommendation } from '../types'
import { formatDuration } from '../lib/format'
import { TempoArrow } from './TempoArrow'

interface Props {
  recs: Recommendation[] | null
  targetBpm: number
  processing: { total: number; done: number; failed: string[] } | null
  onSelected: (songIds: string[]) => void
  onPlay: (songIds: string[]) => void
}

/** Signed tempo difference in percent (relative to the original):
 *  + = original sits above the target (must slow down), - = below (speed up). */
function signedDiffPct(orig: number | null | undefined, target: number): number {
  if (orig == null || orig <= 0) return 0
  return Math.round(((orig - target) / orig) * 1000) / 10
}

/** Unrounded percent — used for sorting by relative tempo difference. */
function rawDiffPct(orig: number | null | undefined, target: number): number {
  if (orig == null || orig <= 0) return Number.POSITIVE_INFINITY
  return Math.abs(orig - target) / orig
}

/** Processable = not a red ✕, i.e. tempo difference within 12% (matches
 *  TempoArrow's ✕ threshold). These are the songs that get auto-selected. */
function isProcessable(orig: number | null | undefined, target: number): boolean {
  return rawDiffPct(orig, target) * 100 <= 12
}

export function RecommendPanel({ recs, targetBpm, processing, onSelected, onPlay }: Props) {
  const [selected, setSelected] = useState<Set<string>>(new Set())

  // Auto-select every processable song (skip the ✕ ones automatically).
  useEffect(() => {
    if (recs) {
      setSelected(
        new Set(recs.filter((r) => isProcessable(r.song.original_bpm, targetBpm)).map((r) => r.song.id)),
      )
    }
  }, [recs, targetBpm])

  // Sort by relative tempo difference (percent), ascending.
  const sorted = useMemo(() => {
    if (!recs) return null
    return [...recs].sort(
      (a, b) =>
        rawDiffPct(a.song.original_bpm, targetBpm) - rawDiffPct(b.song.original_bpm, targetBpm),
    )
  }, [recs, targetBpm])

  if (!recs || !sorted) return null

  const toggle = (id: string) => {
    const next = new Set(selected)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setSelected(next)
    onSelected(Array.from(next))
  }

  const selectedIds = Array.from(selected)
  const processable = sorted.filter((r) => isProcessable(r.song.original_bpm, targetBpm))
  // "全选" means every processable song (✕ songs are never bulk-selected).
  const allSelected = processable.length > 0 && processable.every((r) => selected.has(r.song.id))

  // BPM shown as "155+5" — the target is 155 and +5 is how far this song
  // sits above it; the "差多少" column is gone, only the percent remains.
  const bpmText = (orig: number | null | undefined): string => {
    if (orig == null || orig <= 0) return '—'
    const target = Math.round(targetBpm)
    const delta = Math.round(orig - target)
    if (delta > 0) return `${target}+${delta}`
    if (delta < 0) return `${target}${delta}`
    return `${target}`
  }

  // BPM color follows the same grading as the tempo arrow (by absolute diff).
  const bpmColor = (orig: number | null | undefined): string => {
    const p = Math.abs(signedDiffPct(orig, targetBpm))
    if (p < 5) return 'text-run'
    if (p < 8) return 'text-amber-400'
    return 'text-red-400'
  }

  // Percent with sign: + = above target (slow down), - = below target (speed up).
  const pctText = (orig: number | null | undefined): string => {
    const p = signedDiffPct(orig, targetBpm)
    if (p === 0) return '0%'
    return `${p > 0 ? '+' : ''}${p}%`
  }

  return (
    <section>
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-lg font-bold text-white">推荐歌曲</h2>
        <button
          className="text-xs text-white/50 hover:text-run"
          onClick={() => {
            const next = allSelected
              ? new Set<string>()
              : new Set(processable.map((r) => r.song.id))
            setSelected(next)
            onSelected(Array.from(next))
          }}
        >
          {allSelected ? '全不选' : '全选'}
        </button>
      </div>

      <div className="overflow-hidden rounded-xl border border-line bg-panel">
        <ul className="divide-y divide-line">
          {sorted.map((r, i) => (
            <li key={r.song.id} className="flex items-center gap-3 px-4 py-3">
              <input
                type="checkbox"
                checked={selected.has(r.song.id)}
                onChange={() => toggle(r.song.id)}
                className="size-4 accent-[#34d399]"
              />
              <span className="w-6 text-right font-mono text-xs text-white/30">{i + 1}</span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-white">{r.song.title}</p>
                <p className="text-xs text-white/40">{r.song.artist}</p>
              </div>
              <div className="hidden text-xs text-white/30 sm:block">{formatDuration(r.song.duration)}</div>
              <div
                className={`w-16 text-right font-mono text-sm ${bpmColor(r.song.original_bpm)}`}
                title={`目标 ${targetBpm} BPM`}
              >
                {bpmText(r.song.original_bpm)}
              </div>
              <div className="w-10 text-center text-sm">
                <TempoArrow originalBpm={r.song.original_bpm} targetBpm={targetBpm} />
              </div>
              <div className="w-12 text-right font-mono text-xs text-white/40">
                {pctText(r.song.original_bpm)}
              </div>
            </li>
          ))}
        </ul>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-3">
        <button
          disabled={selectedIds.length === 0 || processing != null}
          onClick={() => onPlay(selectedIds)}
          className="rounded-xl bg-run px-6 py-3 font-bold text-ink transition-colors hover:bg-run-dim disabled:cursor-not-allowed disabled:opacity-50"
        >
          ▶ {processing ? '处理中…' : `变速并播放（${selectedIds.length} 首）`}
        </button>
        {processing && (
          <div className="flex items-center gap-2 text-sm text-white/70">
            <div className="h-2 w-40 overflow-hidden rounded-full bg-line">
              <div
                className="h-full bg-run transition-all"
                style={{ width: `${(processing.done / Math.max(1, processing.total)) * 100}%` }}
              />
            </div>
            <span>
              正在处理 {processing.done} / {processing.total}
            </span>
          </div>
        )}
        {processing && processing.failed.length > 0 && (
          <p className="text-sm text-amber-400">
            {processing.failed.length} 首处理失败（已自动跳过）：{processing.failed.join('、')}
          </p>
        )}
        <p className="w-full text-xs text-white/30">
          已自动勾选可变速歌曲（<span className="text-red-400">✕</span> &gt;12% 不适合变速，默认不选）· 图例：
          <span className="text-run">=</span> 差&lt;3% · <span className="text-run">↑/↓</span> 3–5% ·{' '}
          <span className="text-amber-400">↑/↓</span> 5–8% · <span className="text-red-400">↑/↓</span>{' '}
          8–12% · <span className="text-red-400">✕</span> &gt;12%
        </p>
      </div>
    </section>
  )
}
