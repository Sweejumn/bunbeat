import { useEffect, useState } from 'react'
import type { ModeId } from '../types'
import { MODES } from '../types'

interface Props {
  mode: ModeId
  targetBpm: number
  onMode: (m: ModeId) => void
  onTarget: (bpm: number) => void
  disabled?: boolean
}

// The slider covers exactly the four mode zones; values outside the zones
// are entered manually in the number box (custom mode).
const MIN = 100
const MAX = 185
const MANUAL_MIN = 40
const MANUAL_MAX = 300

// Zone colors (in slider-value order) — the track shows ONLY these.
const ZONES: { from: number; to: number; color: string; label: string; icon: string }[] = [
  { from: 100, to: 120, color: '#38bdf8', label: '走路', icon: '🚶' },
  { from: 120, to: 145, color: '#34d399', label: '慢跑', icon: '🏃' },
  { from: 145, to: 165, color: '#fbbf24', label: '跑步', icon: '🏃‍♂️' },
  { from: 165, to: 185, color: '#f87171', label: '快跑', icon: '⚡' },
]

function modeFromBpm(bpm: number): ModeId {
  if (bpm >= 100 && bpm < 120) return 'walk'
  if (bpm >= 120 && bpm < 145) return 'jog'
  if (bpm >= 145 && bpm < 165) return 'run'
  if (bpm >= 165 && bpm <= 185) return 'sprint'
  return 'custom'
}

const pct = (v: number) => ((v - MIN) / (MAX - MIN)) * 100

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v))

export function ModePicker({ mode, targetBpm, onMode, onTarget, disabled }: Props) {
  const active = MODES.find((m) => m.id === mode) ?? MODES[0]
  // Draft text for the manual number box; commits on blur / Enter.
  const [draft, setDraft] = useState(String(targetBpm))

  useEffect(() => {
    setDraft(String(targetBpm))
  }, [targetBpm])

  // Colored track: only the four zones, end to end (no custom segments).
  const stops: string[] = []
  for (const z of ZONES) stops.push(`${z.color} ${pct(z.from)}% ${pct(z.to)}%`)
  const trackGradient = `linear-gradient(to right, ${stops.join(', ')})`

  const handleSlider = (v: number) => {
    onTarget(v)
    onMode(modeFromBpm(v))
  }

  const handleChip = (m: ModeId) => {
    const def = MODES.find((x) => x.id === m)
    if (!def) return
    onMode(m)
    onTarget(def.defaultBpm)
  }

  // Live-commit while typing once the number is meaningful; clamp on blur.
  const commitDraft = (raw: string) => {
    const v = Number(raw)
    if (!Number.isFinite(v) || raw.trim() === '') {
      setDraft(String(targetBpm))
      return
    }
    const c = clamp(Math.round(v), MANUAL_MIN, MANUAL_MAX)
    onTarget(c)
    onMode(modeFromBpm(c))
    setDraft(String(c))
  }

  const onDraftChange = (raw: string) => {
    setDraft(raw)
    const v = Number(raw)
    if (
      raw.trim() !== '' &&
      Number.isInteger(v) &&
      v >= MANUAL_MIN &&
      v <= MANUAL_MAX
    ) {
      onTarget(v)
      onMode(modeFromBpm(v))
    }
  }

  return (
    <section>
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-lg font-bold text-white">今天想怎么跑？</h2>
        <div className="flex items-center gap-2">
          <input
            type="text"
            inputMode="numeric"
            value={draft}
            disabled={disabled}
            onChange={(e) => onDraftChange(e.target.value)}
            onBlur={(e) => commitDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
            }}
            className="w-24 rounded-lg border border-line bg-card px-2 py-1 text-right font-mono text-2xl font-bold text-run outline-none transition-colors focus:border-run-dim disabled:opacity-50"
            aria-label="目标 BPM（可手动输入任意值）"
          />
          <span className="text-sm text-white/40">BPM</span>
        </div>
      </div>

      {/* one long slider covering exactly the four zones (100-185) */}
      <input
        type="range"
        className="bpm-slider w-full"
        min={MIN}
        max={MAX}
        step={1}
        value={clamp(targetBpm, MIN, MAX)}
        disabled={disabled}
        onChange={(e) => handleSlider(Number(e.target.value))}
        style={{ background: trackGradient }}
      />

      {/* zone labels under the slider */}
      <div className="relative mt-1 h-5 text-[11px] text-white/45">
        <span className="absolute -translate-x-1/2" style={{ left: `${pct(MIN)}%` }}>{MIN}</span>
        {ZONES.map((z) => (
          <span
            key={z.label}
            className="absolute -translate-x-1/2 whitespace-nowrap"
            style={{ left: `${pct((z.from + z.to) / 2)}%` }}
          >
            {z.icon} {z.from}–{z.to}
          </span>
        ))}
        <span className="absolute -translate-x-1/2" style={{ left: `${pct(MAX)}%` }}>{MAX}</span>
      </div>

      {/* current mode banner */}
      <div className="mt-2 flex items-center justify-between rounded-xl border border-line bg-panel px-4 py-3">
        <span className="text-sm text-white/60">
          {active.icon} <span className="font-semibold text-white">{active.label}</span>
          {mode === 'custom' ? (
            ' · 区间外自定义（手动输入）'
          ) : (
            ` · ${active.range[0]}–${active.range[1]} BPM`
          )}
        </span>
        <span className="text-xs text-white/35">拖动滑块选区间 · 输入框可设任意 BPM</span>
      </div>

      {/* quick-jump chips: the four zones only (custom is manual-only) */}
      <div className="mt-2 grid grid-cols-4 gap-2">
        {MODES.filter((m) => m.id !== 'custom').map((m) => (
          <button
            key={m.id}
            disabled={disabled}
            onClick={() => handleChip(m.id)}
            className={`rounded-xl border px-2 py-2 text-center transition-colors disabled:opacity-60 ${
              mode === m.id
                ? 'border-run bg-run/15 text-white'
                : 'border-line bg-card text-white/60 hover:border-run-dim'
            }`}
          >
            <div className="text-xl">{m.icon}</div>
            <div className="mt-0.5 text-xs font-semibold">{m.label}</div>
          </button>
        ))}
      </div>
      <p className="mt-2 text-xs text-white/35">
        点击模式 = 跳到该区间；区间外（&lt;100 或 &gt;185）请在右侧输入框手动输入
      </p>
    </section>
  )
}
