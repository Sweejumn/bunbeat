export function formatDuration(seconds: number | null | undefined): string {
  if (seconds == null || !Number.isFinite(seconds)) return '--:--'
  const s = Math.max(0, Math.floor(seconds))
  const m = Math.floor(s / 60)
  const r = s % 60
  return `${m}:${r.toString().padStart(2, '0')}`
}

export function formatBpm(bpm: number | null | undefined): string {
  if (bpm == null || !Number.isFinite(bpm)) return '—'
  return `${Math.round(bpm)}`
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export function confidenceLabel(conf: number | null | undefined): string {
  if (conf == null) return ''
  if (conf >= 0.8) return '高'
  if (conf >= 0.6) return '中'
  return '低'
}
