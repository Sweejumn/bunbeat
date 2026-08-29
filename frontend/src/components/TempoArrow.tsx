/**
 * Tempo-adjustment indicator.
 *
 * Shows how a song must be tempo-adjusted to reach the target BPM:
 *   ↑  speeding up needed   ↓  slowing down needed
 * More arrows = bigger difference (1/2/3), ✕ = outside the
 * processable range (matches the backend's 1/3x..3x limit).
 */
interface Props {
  originalBpm: number | null | undefined
  targetBpm: number
  className?: string
}

const MAX_RATIO = 3.0

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
  if (pct < 0.5) {
    return (
      <span className={`text-white/30 ${className ?? ''}`} title="与目标 BPM 一致，无需变速">
        =
      </span>
    )
  }

  const count = pct <= 5 ? 1 : pct <= 15 ? 2 : 3
  const speedUp = ratio > 1
  const arrow = speedUp ? '↑' : '↓'
  const color = speedUp ? 'text-sky-400' : 'text-orange-400'
  const verb = speedUp ? '加快' : '减慢'

  return (
    <span
      className={`font-bold ${color} ${className ?? ''}`}
      title={`需${verb}约 ${pct.toFixed(0)}%（目标 ${targetBpm} BPM）`}
    >
      {arrow.repeat(count)}
    </span>
  )
}
