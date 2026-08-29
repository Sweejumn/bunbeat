/**
 * Tempo-adjustment indicator.
 *
 * Shows how a song must be tempo-adjusted to reach the target BPM, graded
 * by the percent tempo change:
 *   green "="    <3%   — barely/not audible, essentially identical
 *   green "↑/↓"  3–5%  — small change, noticeable
 *   amber "↑/↓"  5–8%  — moderate change
 *   red   "↑/↓"  8–12% — clearly audible change
 *   red   "✕"    >12%  — too large a difference, cannot process well
 */
interface Props {
  originalBpm: number | null | undefined
  targetBpm: number
  className?: string
}

export function TempoArrow({ originalBpm, targetBpm, className }: Props) {
  if (originalBpm == null || originalBpm <= 0) return null

  const ratio = targetBpm / originalBpm
  const pct = Math.abs(ratio - 1) * 100

  if (pct > 12) {
    return (
      <span className={`text-red-400 ${className ?? ''}`} title={`差异过大（${pct.toFixed(1)}%），不适合变速`}>
        ✕
      </span>
    )
  }

  if (pct < 3) {
    return (
      <span
        className={`font-bold text-run ${className ?? ''}`}
        title={`与目标几乎一致（差 ${pct.toFixed(1)}%）`}
      >
        =
      </span>
    )
  }

  const speedUp = ratio > 1
  const verb = speedUp ? '加快' : '减慢'
  const color = pct < 5 ? 'text-run' : pct < 8 ? 'text-amber-400' : 'text-red-400'
  return (
    <span
      className={`font-bold ${color} ${className ?? ''}`}
      title={`需${verb}约 ${pct.toFixed(1)}%（目标 ${targetBpm} BPM）`}
    >
      {speedUp ? '↑' : '↓'}
    </span>
  )
}
