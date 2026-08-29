import type { ModeId } from '../types'
import { MODES } from '../types'

interface Props {
  mode: ModeId
  targetBpm: number
  onMode: (m: ModeId) => void
  onTarget: (bpm: number) => void
  disabled?: boolean
}

const MIN = 60
const MAX = 220

// Zone colors (in slider-value order): custom(gray) / walk / jog / run / sprint / custom(gray)
const ZONES: { from: number; to: number; color: string; label: string; icon: string }[] = [
  { from: 100, to: 120, color: '#38bdf8', label: '走路', icon: '🚶' },
  { from: 120, to: 145, color: '#34d399', label: '慢跑', icon: '🏃' },
  { from: 145, to: 165, color: '#fbbf24', label: '跑步', icon: '🏃‍♂️' },
  { from: 165, to: 185, color: '#f87171', label: '快跑', icon: '⚡' },
]

const GRAY = '#3d4a57'

function modeFromBpm(bpm: number): ModeId {
  if (bpm >= 100 && bpm < 120) return 'walk'
  if (bpm >= 120 && bpm < 145) return 'jog'
  if (bpm >= 145 && bpm < 165) return 'run'
  if (bpm >= 165 && bpm <= 185) return 'sprint'
  return 'custom'
}

const pct = (v: number) => ((v - MIN) / (MAX - MIN)) * 100

export function ModePicker({ mode, targetBpm, onMode, onTarget, disabled }: Props) {
  const active = MODES.find((m) => m.id === mode) ?? MODES[0]

  // Colored track: gray outside the four zones, zone colors inside.
  const stops: string[] = [`${GRAY} 0%`, `${GRAY} ${pct(100)}%`]
  for (const z of ZONES) stops.push(`${z.color} ${pct(z.from)}% ${pct(z.to)}%`)
  stops.push(`${GRAY} ${pct(185)}%`, `${GRAY} 100%`)
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

  return (
    <section>
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-lg font-bold text-white">今天想怎么跑？</h2>
        <span className="font-mono text-2xl font-bold text-run">
          {targetBpm}
          <span className="ml-1 text-sm text-white/40">BPM</span>
        </span>
      </div>

      {/* one long slider covering 60-220; zones = modes */}
      <input
        type="range"
        className="bpm-slider w-full"
        min={MIN}
        max={MAX}
        step={1}
        value={targetBpm}
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
          {mode === 'custom' ? ' · 区间外自定义' : ` · ${active.range[0]}–${active.range[1]} BPM`}
        </span>
        <span className="text-xs text-white/35">拖动滑块跨区间即切换模式</span>
      </div>

      {/* quick-jump chips */}
      <div className="mt-2 grid grid-cols-5 gap-2">
        {MODES.map((m) => (
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
      <p className="mt-2 text-xs text-white/35">点击模式 = 跳到该区间；划到区间外即自定义</p>
    </section>
  )
}
