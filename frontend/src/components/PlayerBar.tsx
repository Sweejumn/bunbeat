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
  /** beat ruler data (always visible, no toggle) */
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
}

/** Tap button: always usable, counts 1..8, computes BPM silently. Every tap
 *  is reported via onTap for the ruler marks. Setting the first beat is
 *  controlled by the「设首拍」switch: while it is ON, every tap directly
 *  re-anchors the first beat; while OFF, taps never touch the first beat. */
function TapBpm({
  onCalibrate,
  getCurrentTime,
  onTap,
  onComputed,
  onProgress,
  setFirstBeatOn,
}: {
  onCalibrate: (bpm: number | null, firstBeat: number | null) => void
  getCurrentTime: () => number
  onTap: (t: number) => void
  onComputed: (bpm: number | null) => void
  onProgress: (n: number) => void
  setFirstBeatOn: boolean
}) {
  const [taps, setTaps] = useState<number[]>([])
  const lastTapRef = useRef(0)

  const handleTap = () => {
    const t = getCurrentTime()
    const now = performance.now()
    setTaps((prev) => {
      const next = now - lastTapRef.current > 2000 ? [t] : [...prev, t]
      lastTapRef.current = now
      onProgress(next.length)
      return next
    })
    onTap(t)
    // First beat is only touched while the「设首拍」switch is ON.
    if (setFirstBeatOn) {
      onCalibrate(null, Math.max(0, Math.round(t * 1000) / 1000))
    }
  }

  useEffect(() => {
    // After 8 taps (median interval): compute the BPM. It is NOT applied
    // automatically — the「设置 BPM」button does that when pressed.
    if (taps.length < 8) return
    const ivs = taps.slice(1).map((x, i) => x - taps[i]).filter((x) => x > 0.1)
    if (ivs.length >= 2) {
      const sorted = [...ivs].sort((a, b) => a - b)
      const period = sorted[Math.floor(sorted.length / 2)] // median
      onComputed(Math.round(60 / period))
    }
    setTaps([])
    onProgress(0)
  }, [taps, onComputed, onProgress])

  return (
    <button
      onClick={handleTap}
      className="rounded-lg bg-line px-4 py-2.5 text-base text-white hover:bg-line/80"
      title={
        setFirstBeatOn
          ? '开关已开启：每次点按都会把当前位置设为首拍；敲满 8 下自动计算 BPM'
          : '点按敲 8 下自动计算 BPM（不设首拍）；打开「设首拍」开关后，每次点按都会把当前位置设为首拍'
      }
    >
      👆 点按打拍
    </button>
  )
}

export function PlayerBar(p: Props) {
  const { item } = p
  const [tapMarks, setTapMarks] = useState<number[]>([])
  const [computedBpm, setComputedBpm] = useState<number | null>(null)
  const [tapCount, setTapCount] = useState(0)
  // First-beat setting is switch-controlled (default off).
  const [setFirstBeatOn, setSetFirstBeatOn] = useState(false)

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
        {/* beat ruler — always visible, no toggle button */}
        <BeatRuler beats={p.rulerBeats} taps={tapMarks} getCurrentTime={p.getCurrentTime} />
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
        </div>

        {/* volume row: music / metronome / phase — three sliders side by
            side so phones (where the transport row is too short for a
            separate music volume) can still adjust all of them */}
        <div className="mt-2 grid grid-cols-1 gap-x-6 gap-y-2 border-t border-line/60 pt-2 text-xs text-white/50 sm:grid-cols-3">
          <span className="flex items-center gap-2">
            <span className="shrink-0">🔊 音乐</span>
            <input
              type="range"
              min={0}
              max={1}
              step={0.05}
              value={p.volume}
              onChange={(e) => p.onVolume(Number(e.target.value))}
              className="w-full min-w-0"
              title="音乐音量"
            />
          </span>
          <span className="flex items-center gap-2">
            <span className="shrink-0">🥁 拍子</span>
            <input
              type="range"
              min={0}
              max={1}
              step={0.05}
              value={p.metronomeVolume}
              onChange={(e) => p.onMetronomeVolume(Number(e.target.value))}
              className="w-full min-w-0"
              title="节拍器音量"
            />
          </span>
          <span className="flex items-center gap-2">
            <span className="shrink-0">🎯 偏差</span>
            <input
              type="range"
              min={-50}
              max={50}
              step={1}
              value={p.phaseNudge}
              onChange={(e) => p.onPhaseNudge(Number(e.target.value))}
              className="w-full min-w-0 accent-[#fbbf24]"
              title="点击偏早/偏晚时，整体前后移动节拍（最多半个拍距）"
            />
            <span
              className={`w-16 shrink-0 text-right font-mono ${
                p.phaseNudge !== 0 ? 'text-accent' : 'text-white/40'
              }`}
            >
              {nudgeLabel}
            </span>
            <button
              onClick={() => p.onPhaseNudge(0)}
              disabled={p.phaseNudge === 0}
              className={`shrink-0 rounded px-1.5 py-0.5 transition-colors ${
                p.phaseNudge !== 0
                  ? 'bg-run text-ink hover:bg-run-dim'
                  : 'cursor-not-allowed bg-line/50 text-white/25'
              }`}
              title="把相位微调归零"
            >
              归零
            </button>
          </span>
        </div>
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
        <div className="mt-2 border-t border-line/60 pt-2 text-xs text-white/50">
          {/* row 1: tap button — always usable */}
          <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
            <span className="flex items-center gap-1">校准</span>
            <TapBpm
              onCalibrate={p.onSetCal}
              getCurrentTime={p.getCurrentTime}
              onTap={(t) =>
                setTapMarks((prev) => {
                  const next = [...prev, t]
                  // Keep only the most recent 20 marks on the ruler.
                  return next.length > 20 ? next.slice(next.length - 20) : next
                })
              }
              onComputed={setComputedBpm}
              onProgress={setTapCount}
              setFirstBeatOn={setFirstBeatOn}
            />
          </div>

          {/* row 2: BPM tuning — manual box unchanged + computed value + apply button */}
          <div className="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-2">
            <span className="w-14 shrink-0 text-white/35">调试 BPM</span>
            <span className="flex items-center gap-1">
              BPM
              <input
                type="number"
                min={40}
                max={320}
                value={p.calBpm ?? computedBpm ?? ''}
                placeholder={p.calBpm == null && computedBpm == null ? '自动' : undefined}
                onChange={(e) => {
                  const v = Number(e.target.value)
                  if (Number.isFinite(v) && v >= 40 && v <= 320) p.onSetCal(v, null)
                }}
                className="w-16 rounded-md border border-line bg-ink px-2 py-1 text-white outline-none focus:border-run"
              />
            </span>
            <button
              onClick={() => {
                if (computedBpm != null) p.onSetCal(computedBpm, null)
              }}
              disabled={computedBpm == null}
              className={`rounded px-2 py-1 transition-colors ${
                computedBpm != null
                  ? 'bg-run text-ink hover:bg-run-dim'
                  : 'cursor-not-allowed bg-line/50 text-white/25'
              }`}
              title={
                computedBpm != null
                  ? `把打拍计算出的 BPM（${computedBpm}）应用到校准`
                  : '先点按打拍 8 下，计算出 BPM 后这里才能设置'
              }
            >
              ✅ 设置 BPM
            </button>
            <span
              className={`font-mono text-sm ${
                tapCount > 0 ? 'text-accent' : 'text-white/30'
              }`}
            >
              敲拍 {tapCount}/8
            </span>
          </div>

          {/* row 3: first-beat tuning — switch controls whether taps set it */}
          <div className="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-2">
            <span className="w-14 shrink-0 text-white/35">调试首拍</span>
            <button
              onClick={() => setSetFirstBeatOn((v) => !v)}
              className={`rounded px-2 py-1 transition-colors ${
                setFirstBeatOn ? 'bg-accent text-ink' : 'bg-line text-white/60 hover:text-white'
              }`}
              title="开启后，每次点按打拍都会把当前位置设为首拍（默认关闭）"
            >
              🎯 设首拍 {setFirstBeatOn ? '开' : '关'}
            </button>
            {p.calFirstBeat != null ? (
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
            ) : null}
            {(p.calBpm != null || p.calFirstBeat != null) && (
              <button
                onClick={p.onResetCal}
                className="rounded bg-line px-2 py-1 text-amber-400 hover:text-amber-300"
                title="恢复自动检测的拍点"
              >
                复位
              </button>
            )}
          </div>
        </div>
        )}
      </div>
    </div>
  )
}
