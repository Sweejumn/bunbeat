import type { ModeId } from '../types'
import { MODES } from '../types'

interface Props {
  mode: ModeId
  targetBpm: number
  onMode: (m: ModeId) => void
  onTarget: (bpm: number) => void
  disabled?: boolean
}

export function ModePicker({ mode, targetBpm, onMode, onTarget, disabled }: Props) {
  const active = MODES.find((m) => m.id === mode) ?? MODES[0]
  return (
    <section>
      <h2 className="mb-3 text-lg font-bold text-white">今天想怎么跑？</h2>
      <div className="grid grid-cols-5 gap-2">
        {MODES.map((m) => (
          <button
            key={m.id}
            disabled={disabled}
            onClick={() => onMode(m.id)}
            className={`rounded-xl border px-2 py-3 text-center transition-colors disabled:opacity-60 ${
              mode === m.id
                ? 'border-run bg-run/15 text-white'
                : 'border-line bg-card text-white/60 hover:border-run-dim'
            }`}
          >
            <div className="text-2xl">{m.icon}</div>
            <div className="mt-1 text-sm font-semibold">{m.label}</div>
            <div className="text-[11px] text-white/40">
              {m.range[0]}–{m.range[1]} BPM
            </div>
          </button>
        ))}
      </div>

      <div className="mt-4 rounded-xl border border-line bg-panel p-4">
        <div className="flex items-center justify-between">
          <span className="text-sm text-white/60">
            {mode === 'custom' ? '自定义目标 BPM' : `${active.label}目标 BPM`}
          </span>
          <span className="font-mono text-2xl font-bold text-run">
            {targetBpm}
            <span className="ml-1 text-sm text-white/40">BPM</span>
          </span>
        </div>
        <input
          type="range"
          min={active.range[0]}
          max={active.range[1]}
          step={1}
          value={targetBpm}
          disabled={disabled}
          onChange={(e) => onTarget(Number(e.target.value))}
          className="mt-3 w-full"
        />
        <p className="mt-2 text-xs text-white/35">滑动微调目标节奏，系统将按「与目标 BPM 的差距」推荐歌曲</p>
      </div>
    </section>
  )
}
