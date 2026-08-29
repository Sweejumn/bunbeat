/**
 * Tempo-adjustment indicator.
 *
 * Shows how a song must be tempo-adjusted to reach the target BPM:
 *   green "="  difference below the perceptual threshold (~3%) — the
 *              tempo change is barely/not audible
 *   red  "↑"   must be sped up by more than 3% — clearly audible
 *   red  "↓"   must be slowed down by more than 3% — clearly audible
 *   red  "✕"   outside the processable range (1/3x..3x) — cannot process
 */
interface Props {
  originalBpm: number | null | undefined
  targetBpm: number
  className?: string
}

const MAX_RATIO = 3.0
// Tempo-change just-noticeable-difference: beyond ~3% listeners clearly
// perceive the speed change.
const NOTICEABLE_PCT = 3.0

export function TempoArrow({ originalBpm, targetBpm, className }: Props) {
  if (originalBpm == null || originalBpm <= 0) return null

  const ratio = targetBpm / originalBpm

  if (ratio > MAX_RATIO || ratio < 1 / MAX_RATIO) {
    return (
      <span className={`text-red-400 ${className ?? ''}`} title="差异过大，无法变速处理">
        ✕
      </span>
    )
  }

  const pct = Math.abs(ratio - 1) * 100
  if (pct < NOTICEABLE_PCT) {
    return (
      <span
        className={`font-bold text-run ${className ?? ''}`}
        title={`与目标几乎一致（差 ${pct.toFixed(1)}%，听感无明显变化）`}
      >
        =
      </span>
    )
  }

  const speedUp = ratio > 1
  const verb = speedUp ? '加快' : '减慢'
  return (
    <span
      className={`font-bold text-red-400 ${className ?? ''}`}
      title={`需${verb}约 ${pct.toFixed(1)}%（目标 ${targetBpm} BPM）`}
    >
      {speedUp ? '↑' : '↓'}
    </span>
  )
}
