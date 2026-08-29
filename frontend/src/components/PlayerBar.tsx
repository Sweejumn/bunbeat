import type { PlaylistItem } from '../types'
import { formatBpm, formatDuration } from '../lib/format'
import { TempoArrow } from './TempoArrow'

interface Props {
  item: PlaylistItem | null
  playing: boolean
  currentTime: number
  duration: number
  volume: number
  metronomeOn: boolean
  metronomeVolume: number
  onTogglePlay: () => void
  onPrev: () => void
  onNext: () => void
  onSeek: (t: number) => void
  onVolume: (v: number) => void
  onMetronome: (on: boolean) => void
  onMetronomeVolume: (v: number) => void
}

export function PlayerBar(p: Props) {
  const { item } = p
  if (!item) return null

  return (
    <div className="fixed inset-x-0 bottom-0 z-40 border-t border-line bg-panel/95 backdrop-blur">
      <div className="mx-auto max-w-5xl px-4 py-3">
        {/* progress */}
        <div className="flex items-center gap-3">
          <span className="w-12 text-right font-mono text-xs text-white/40">
            {formatDuration(p.currentTime)}
          </span>
          <input
            type="range"
            min={0}
            max={p.duration || 0}
            step={0.1}
            value={p.currentTime}
            onChange={(e) => p.onSeek(Number(e.target.value))}
            className="w-full"
          />
          <span className="w-12 font-mono text-xs text-white/40">{formatDuration(p.duration)}</span>
        </div>

        <div className="mt-2 flex items-center gap-4">
          {/* track info */}
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-semibold text-white">{item.song.title}</p>
            <p className="truncate text-xs text-white/40">
              {item.song.artist} · {formatBpm(item.song.original_bpm)} →{' '}
              <span className="text-run">{Math.round(item.targetBpm)} BPM</span>{' '}
              <TempoArrow originalBpm={item.song.original_bpm} targetBpm={item.targetBpm} />
              {p.metronomeOn && <span className="ml-1 text-accent">🥁 节拍器</span>}
            </p>
          </div>

          {/* transport */}
          <div className="flex items-center gap-2">
            <button
              className="rounded-full p-2 text-white/70 hover:bg-line hover:text-white"
              onClick={p.onPrev}
              title="上一首"
            >
              <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor">
                <path d="M6 5h2v14H6zM20 5v14l-11-7z" />
              </svg>
            </button>
            <button
              className="flex size-12 items-center justify-center rounded-full bg-run text-ink transition-transform hover:scale-105"
              onClick={p.onTogglePlay}
              title={p.playing ? '暂停' : '播放'}
            >
              {p.playing ? (
                <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M7 5h4v14H7zM13 5h4v14h-4z" />
                </svg>
              ) : (
                <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
                  <path d="M7 5v14l12-7z" />
                </svg>
              )}
            </button>
            <button
              className="rounded-full p-2 text-white/70 hover:bg-line hover:text-white"
              onClick={p.onNext}
              title="下一首"
            >
              <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor">
                <path d="M16 5h2v14h-2zM4 5v14l11-7z" />
              </svg>
            </button>
          </div>

          {/* volume */}
          <div className="hidden items-center gap-2 sm:flex">
            <span className="text-white/50">🔊</span>
            <input
              type="range"
              min={0}
              max={1}
              step={0.05}
              value={p.volume}
              onChange={(e) => p.onVolume(Number(e.target.value))}
              className="w-20"
            />
          </div>

          {/* metronome */}
          <div className="flex items-center gap-2">
            <button
              onClick={() => p.onMetronome(!p.metronomeOn)}
              className={`rounded-lg px-3 py-1.5 text-sm font-semibold transition-colors ${
                p.metronomeOn ? 'bg-accent text-ink' : 'bg-line text-white/60 hover:text-white'
              }`}
              title="节拍器开关"
            >
              🥁 节拍器
            </button>
            {p.metronomeOn && (
              <input
                type="range"
                min={0}
                max={1}
                step={0.05}
                value={p.metronomeVolume}
                onChange={(e) => p.onMetronomeVolume(Number(e.target.value))}
                className="w-16"
                title="节拍器音量"
              />
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
