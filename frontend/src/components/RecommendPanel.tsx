import { useEffect, useState } from 'react'
import type { Recommendation } from '../types'
import { formatBpm, formatDuration } from '../lib/format'

interface Props {
  recs: Recommendation[] | null
  targetBpm: number
  processing: { total: number; done: number; failed: string[] } | null
  onSelected: (songIds: string[]) => void
  onPlay: (songIds: string[]) => void
}

function Stars({ n }: { n: number }) {
  return (
    <span className="text-accent" title={`${n} 星推荐`}>
      {'★'.repeat(n)}
      <span className="text-white/15">{'★'.repeat(Math.max(0, 5 - n))}</span>
    </span>
  )
}

export function RecommendPanel({ recs, targetBpm, processing, onSelected, onPlay }: Props) {
  const [selected, setSelected] = useState<Set<string>>(new Set())

  useEffect(() => {
    if (recs) setSelected(new Set(recs.map((r) => r.song.id)))
  }, [recs])

  if (!recs) return null

  const toggle = (id: string) => {
    const next = new Set(selected)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setSelected(next)
    onSelected(Array.from(next))
  }

  const selectedIds = Array.from(selected)
  const allSelected = recs.length > 0 && selectedIds.length === recs.length

  return (
    <section>
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-lg font-bold text-white">推荐歌曲（目标 {targetBpm} BPM）</h2>
        <button
          className="text-xs text-white/50 hover:text-run"
          onClick={() => {
            const next = allSelected ? new Set<string>() : new Set(recs.map((r) => r.song.id))
            setSelected(next)
            onSelected(Array.from(next))
          }}
        >
          {allSelected ? '全不选' : '全选'}
        </button>
      </div>

      <div className="overflow-hidden rounded-xl border border-line bg-panel">
        <ul className="divide-y divide-line">
          {recs.map((r, i) => (
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
              <div className="w-24 text-right font-mono text-sm text-run">
                {formatBpm(r.song.original_bpm)} BPM
              </div>
              <div className="w-14 text-right">
                <Stars n={r.score} />
              </div>
              <div className="hidden w-14 text-right text-xs text-white/30 md:block">
                差 {r.distance.toFixed(0)}
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
      </div>
    </section>
  )
}
