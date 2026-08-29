import { useEffect, useRef, useState } from 'react'
import type { BeatMode, PlaylistItem } from '../types'
import { BEAT_MODES } from '../types'
import { formatBpm, formatDuration } from '../lib/format'
import { TempoArrow } from './TempoArrow'
import { BeatRuler } from './BeatRuler'

interface Props {
  item: PlaylistItem | null
  playing: boolean
  currentTime: number
  duration: number
  volume: number
  metronomeOn: boolean
  metronomeVolume: number
  /** manual phase fine-tune in percent of a beat (-50..50) */
  phaseNudge: number
  /** selectable beat-map mode */
  beatMode: BeatMode
  /** manual beat calibration (null = auto) */
  calBpm: number | null
  calFirstBeat: number | null
  /** 0..1 agreement of independent phase signals (low = suggest calibration) */
  songPhaseReliability: number | null
  /** simple beat ruler toggle + data */
  rulerOn: boolean
  rulerBeats: number[]
  getCurrentTime: () => number
  onTogglePlay: () => void
  onPrev: () => void
  onNext: () => void
  onSeek: (t: number) => void
  onVolume: (v: number) => void
  onMetronome: (on: boolean) => void
  onMetronomeVolume: (v: number) => void
  onPhaseNudge: (pct: number) => void
  onBeatMode: (m: BeatMode) => void
  onSetCal: (bpm: number | null, firstBeat: number | null) => void
  onResetCal: () => void
  onToggleRuler: () => void
}

function TapTempo({
  onCalibrate,
  getCurrentTime,
  onTaps,
}: {
  onCalibrate: (bpm: number | null, firstBeat: number | null) => void
  getCurrentTime: () => number
  onTaps: (taps: number[]) => void
}) {
  // Armed switch: tapping only takes effect while enabled (default off).
  const [armed, setArmed] = useState(false)
  const [taps, setTaps] = useState<number[]>([])
  const lastTapRef = useRef(0)

  const handleTap = () => {
    if (!armed) return
    const t = getCurrentTime()
    const now = performance.now()
    const next = now - lastTapRef.current > 2000 ? [t] : [...taps, t]
    lastTapRef.current = now
    setTaps(next)
    onTaps(next)
  }

  useEffect(() => {
    // BPM is computed after 8 taps (median interval), first tap = first beat.
    if (taps.length < 8) return
    const ivs = taps.slice(1).map((x, i) => x - taps[i]).filter((x) => x > 0.1)
    if (ivs.length >= 2) {
      const sorted = [...ivs].sort((a, b) => a - b)
      const period = sorted[Math.floor(sorted.length / 2)] // median
      onCalibrate(Math.round(60 / period), Math.max(0, Math.round(taps[0] * 1000) / 1000))
    }
    setTaps([])
    onTaps([])
    setArmed(false)
  }, [taps, onCalibrate, onTaps])

  return (
    <span className="flex items-center gap-1">
      <button
        onClick={() => setArmed((v) => !v)}
        className={`rounded px-2 py-1 transition-colors ${
          armed ? 'bg-accent text-ink' : 'bg-line text-white/60 hover:text-white'
        }`}
        title="开启后点按打拍才生效（默认关闭）；敲 8 下自动计算 BPM 并设置首拍"
      >
        🎯 拍打 {armed ? '开' : '关'}
      </button>
      <button
        onClick={handleTap}
        disabled={!armed}
        className={`rounded px-2 py-1 transition-colors ${
          armed ? 'bg-line text-white/70 hover:text-white' : 'cursor-not-allowed bg-line/50 text-white/25'
        }`}
        title="先开启「拍打」开关，再跟着音乐点按 8 下"
      >
        👆 点按打拍（{taps.length}/8）
      </button>
    </span>
  )
}

export function PlayerBar(p: Props) {
  const { item } = p
  const [tapMarks, setTapMarks] = useState<number[]>([])

  // Clear tap marks when the track changes.
  useEffect(() => {
    setTapMarks([])
  }, [item?.song.id])

  if (!item) return null

  const nudgeLabel =
    p.phaseNudge === 0
      ? '0'
      : p.phaseNudge < 0
        ? `${p.phaseNudge}% 提前`
        : `+${p.phaseNudge}% 滞后`

  return (
    <div className="fixed inset-x-0 bottom-0 z-40 border-t border-line bg-panel/95 backdrop-blur">
      <div className="mx-auto max-w-5xl px-4 py-3">
        {/* simple beat ruler */}
        {p.rulerOn && (
          <BeatRuler beats={p.rulerBeats} taps={tapMarks} getCurrentTime={p.getCurrentTime} />
        )}
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

          {/* metronome toggle */}
          <button
            onClick={() => p.onMetronome(!p.metronomeOn)}
            className={`rounded-lg px-3 py-1.5 text-sm font-semibold transition-colors ${
              p.metronomeOn ? 'bg-accent text-ink' : 'bg-line text-white/60 hover:text-white'
            }`}
            title="节拍器开关"
          >
            🥁 节拍器
          </button>
          {/* simple beat ruler toggle */}
          <button
            onClick={p.onToggleRuler}
            className={`rounded-lg px-3 py-1.5 text-sm font-semibold transition-colors ${
              p.rulerOn ? 'bg-run text-ink' : 'bg-line text-white/60 hover:text-white'
            }`}
            title="拍点标尺：绿色线为拍点，穿过中心线即当前拍"
          >
            📏 拍点
          </button>
        </div>

        {/* metronome settings (visible while enabled) */}
        {p.metronomeOn && (
          <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-line/60 pt-2 text-xs text-white/50">
            <span className="flex items-center gap-2">
              音量
              <input
                type="range"
                min={0}
                max={1}
                step={0.05}
                value={p.metronomeVolume}
                onChange={(e) => p.onMetronomeVolume(Number(e.target.value))}
                className="w-24"
              />
            </span>
            <span className="flex items-center gap-2">
              相位微调
              <input
                type="range"
                min={-50}
                max={50}
                step={1}
                value={p.phaseNudge}
                onChange={(e) => p.onPhaseNudge(Number(e.target.value))}
                className="w-32 accent-[#fbbf24]"
                title="点击偏早/偏晚时，整体前后移动节拍（最多半个拍距）"
              />
              <span className={`w-20 font-mono ${p.phaseNudge !== 0 ? 'text-accent' : 'text-white/40'}`}>
                {nudgeLabel}
              </span>
              {p.phaseNudge !== 0 && (
                <button
                  className="rounded bg-line px-2 py-0.5 text-white/60 hover:text-white"
                  onClick={() => p.onPhaseNudge(0)}
                >
                  归零
                </button>
              )}
            </span>
          </div>
        )}
        {/* beat-mode selector (metronome-related) */}
        {p.metronomeOn && (
        <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-line/60 pt-2 text-xs text-white/50">
          <span>拍点模式</span>
          {BEAT_MODES.map((m) => (
            <button
              key={m.id}
              onClick={() => p.onBeatMode(m.id)}
              title={m.desc}
              className={`rounded px-2 py-0.5 ${
                p.beatMode === m.id ? 'bg-run text-ink' : 'bg-line text-white/60 hover:text-white'
              }`}
            >
              {m.label}
            </button>
          ))}
          <span className="text-white/25">
            {BEAT_MODES.find((m) => m.id === p.beatMode)?.desc}
          </span>
          {p.songPhaseReliability != null && p.songPhaseReliability < 0.6 && (
            <span className="rounded bg-amber-500/15 px-2 py-0.5 text-amber-400" title="两个独立信号对拍点相位判断不一致，自动对齐可能不准，建议手动校准">
              ⚠ 相位可靠性低
            </span>
          )}
        </div>
        )}

        {/* manual beat calibration (metronome-related) */}
        {p.metronomeOn && (
        <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-line/60 pt-2 text-xs text-white/50">
          <span className="flex items-center gap-1">
            校准
          </span>
          <TapTempo
            onCalibrate={p.onSetCal}
            getCurrentTime={p.getCurrentTime}
            onTaps={setTapMarks}
          />
          <span className="flex items-center gap-1">
            BPM
            <input
              type="number"
              min={40}
              max={320}
              value={p.calBpm ?? ''}
              placeholder={p.calBpm == null ? '自动' : undefined}
              onChange={(e) => {
                const v = Number(e.target.value)
                if (Number.isFinite(v) && v >= 40 && v <= 320) p.onSetCal(v, null)
              }}
              className="w-16 rounded-md border border-line bg-ink px-2 py-1 text-white outline-none focus:border-run"
            />
          </span>
          <button
            onClick={() => p.onSetCal(null, Math.max(0, Math.round(p.getCurrentTime() * 1000) / 1000))}
            className="rounded bg-line px-2 py-1 text-white/70 hover:text-white"
            title="把当前播放位置设为第 1 拍，整条拍点网格从这里开始"
          >
            设首拍
          </button>
          {p.calFirstBeat != null && (
            <span className="flex items-center gap-1">
              首拍 {p.calFirstBeat.toFixed(2)}s
              <button
                className="rounded bg-line px-1.5 py-0.5 text-white/70 hover:text-white"
                onClick={() => p.onSetCal(null, Math.max(0, p.calFirstBeat! - 0.02))}
              >
                −
              </button>
              <button
                className="rounded bg-line px-1.5 py-0.5 text-white/70 hover:text-white"
                onClick={() => p.onSetCal(null, p.calFirstBeat! + 0.02)}
              >
                +
              </button>
            </span>
          )}
          {(p.calBpm != null || p.calFirstBeat != null) && (
            <button
              onClick={p.onResetCal}
              className="rounded bg-line px-2 py-1 text-amber-400 hover:text-amber-300"
              title="恢复自动检测的拍点"
            >
              复位
            </button>
          )}
          <span className="text-white/25">校准即时生效，边听边调</span>
        </div>
        )}
      </div>
    </div>
  )
}
