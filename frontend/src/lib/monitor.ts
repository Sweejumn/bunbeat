/**
 * Lightweight runtime diagnostics for locating runaway resource usage
 * (CPU / GPU / memory) when something misbehaves in the field.
 *
 * Every 10s it logs to the console (and mirrors into window.__runbpmStats):
 *   - DOM node count          (unbounded DOM growth is a classic leak signal)
 *   - JS heap size            (when performance.memory is available)
 *   - measured rAF frame rate (UI thread liveliness)
 *   - metronome pending clicks (via a hook provided by the app)
 * Warnings are logged when thresholds are crossed so a reproduction is easy
 * to spot in the console.
 */

interface Stats {
  nodes: number
  heapMB: number | null
  fps: number
  pendingClicks: number
  date: string
}

declare global {
  interface Window {
    __runbpmStats?: Stats
    __runbpmPendingClicks?: () => number
  }
}

const NODE_WARN = 20000
const HEAP_WARN_MB = 400
const FPS_WARN = 25

let started = false

export function startDiagnostics(intervalMs = 10000): () => void {
  if (started) return () => undefined
  started = true

  // rAF frame-rate meter (updated continuously, cheap, cancellable).
  let frames = 0
  let last = performance.now()
  let fps = 60
  let meterRaf = 0
  const meter = () => {
    frames++
    const now = performance.now()
    if (now - last >= 1000) {
      fps = Math.round((frames * 1000) / (now - last))
      frames = 0
      last = now
    }
    meterRaf = requestAnimationFrame(meter)
  }
  meterRaf = requestAnimationFrame(meter)

  const iv = window.setInterval(() => {
    const nodes = document.getElementsByTagName('*').length
    const mem = (performance as unknown as { memory?: { usedJSHeapSize: number } }).memory
    const heapMB = mem ? mem.usedJSHeapSize / 1048576 : null
    const pendingClicks = window.__runbpmPendingClicks?.() ?? -1
    const stats: Stats = {
      nodes,
      heapMB: heapMB != null ? Math.round(heapMB * 10) / 10 : null,
      fps,
      pendingClicks,
      date: new Date().toISOString(),
    }
    window.__runbpmStats = stats
    const warns: string[] = []
    if (nodes > NODE_WARN) warns.push(`DOM 节点过多 (${nodes})`)
    if (heapMB != null && heapMB > HEAP_WARN_MB) warns.push(`JS 堆过大 (${heapMB.toFixed(0)}MB)`)
    if (fps < FPS_WARN) warns.push(`帧率过低 (${fps}fps)`)
    if (pendingClicks > 200) warns.push(`节拍器待调度点击过多 (${pendingClicks})`)
    console.info(
      `[runbpm] nodes=${nodes} heap=${heapMB != null ? heapMB.toFixed(1) + 'MB' : 'n/a'} ` +
        `fps=${fps} pendingClicks=${pendingClicks}` +
        (warns.length ? ' ⚠ ' + warns.join('；') : ''),
    )
  }, intervalMs)

  return () => {
    window.clearInterval(iv)
    cancelAnimationFrame(meterRaf)
    started = false
  }
}
