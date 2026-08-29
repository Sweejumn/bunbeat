import { useEffect, useRef, useState } from 'react'

/**
 * Scrolling beat ruler for debugging metronome alignment.
 *
 * Three rows scroll past a fixed center playhead:
 *   预期   the ORIGINAL song's beats mapped onto the stretched timeline
 *          (dim) — where the old fixed-grid approach clicked
 *   实际   the stretched file's own detected beats (green) — what the
 *          metronome clicks on
 *   点击   live metronome clicks (amber dots), bounded to the visible window
 *
 * Resource-safety notes (a previous version froze the whole system):
 *  - click dots are React-rendered and filtered to the visible window only;
 *    older clicks are dropped (and trimmed from the source array), so the
 *    DOM and the composited layer stay bounded;
 *  - the per-frame rAF loop only updates CSS transforms (no node creation);
 *  - the component is remounted per song via `key`, so its timers and
 *    listeners always have a cleanup path.
 */
interface Props {
  predicted: number[] // processed-timeline seconds
  actual: number[] // processed-timeline seconds
  getCurrentTime: () => number
  clicksRef: React.MutableRefObject<number[]>
}

const PX = 70 // pixels per second
const CENTER = 180
const WINDOW = 5 // seconds each side of the playhead
const CLICK_LOOKAHEAD = 1.5 // also show clicks slightly ahead of the playhead

function TickRow({ times, color, label, refCb }: {
  times: number[]
  color: string
  label: string
  refCb: (el: HTMLDivElement | null) => void
}) {
  return (
    <div className="relative h-6 border-b border-line/40 last:border-b-0">
      <span className="absolute left-1 top-0 z-10 text-[10px] leading-6 text-white/35">{label}</span>
      <div ref={refCb} className="absolute inset-y-0 will-change-transform">
        {times.map((t) => (
          <div
            key={t}
            className={`absolute top-0 h-full w-[2px] ${color}`}
            style={{ left: t * PX - 1 }}
          />
        ))}
      </div>
    </div>
  )
}

export function BeatVisualizer({ predicted, actual, getCurrentTime, clicksRef }: Props) {
  const predRef = useRef<HTMLDivElement | null>(null)
  const actRef = useRef<HTMLDivElement | null>(null)
  const clickRowRef = useRef<HTMLDivElement | null>(null)
  const [visibleClicks, setVisibleClicks] = useState<number[]>([])

  // Scroll: per-frame transform updates only — no DOM creation.
  useEffect(() => {
    let raf = 0
    const loop = () => {
      const off = CENTER - getCurrentTime() * PX
      if (predRef.current) predRef.current.style.transform = `translateX(${off}px)`
      if (actRef.current) actRef.current.style.transform = `translateX(${off}px)`
      if (clickRowRef.current) clickRowRef.current.style.transform = `translateX(${off}px)`
      raf = requestAnimationFrame(loop)
    }
    raf = requestAnimationFrame(loop)
    return () => cancelAnimationFrame(raf)
  }, [getCurrentTime])

  // Click dots: bounded to the visible window; source array trimmed so it
  // cannot grow without bound during long sessions.
  useEffect(() => {
    const iv = window.setInterval(() => {
      const now = getCurrentTime()
      const min = now - WINDOW
      const max = now + CLICK_LOOKAHEAD
      const clicks = clicksRef.current
      setVisibleClicks(clicks.filter((t) => t >= min && t <= max))
      // Drop clicks that scrolled out long ago (never needed again).
      if (clicks.length > 100 && clicks[0] < min - 5) {
        clicksRef.current = clicks.filter((t) => t >= min - 5)
      }
    }, 250)
    return () => window.clearInterval(iv)
  }, [getCurrentTime, clicksRef])

  return (
    <div className="mt-2 overflow-hidden rounded-lg border border-line bg-ink/60">
      <div className="relative" style={{ width: CENTER * 2 }}>
        <TickRow times={predicted} color="bg-slate-500/70" label="预期" refCb={(el) => (predRef.current = el)} />
        <TickRow times={actual} color="bg-run" label="实际" refCb={(el) => (actRef.current = el)} />
        <div className="relative h-6">
          <span className="absolute left-1 top-0 z-10 text-[10px] leading-6 text-white/35">点击</span>
          <div ref={clickRowRef} className="absolute inset-y-0 will-change-transform">
            {visibleClicks.map((t) => (
              <div
                key={t}
                className="absolute top-0 h-full w-[5px] rounded-full bg-accent"
                style={{ left: t * PX - 2.5 }}
              />
            ))}
          </div>
        </div>
        {/* playhead */}
        <div
          className="pointer-events-none absolute inset-y-0 z-20 w-px bg-white/80"
          style={{ left: CENTER }}
        />
        {/* window fade */}
        <div className="pointer-events-none absolute inset-y-0 w-8 bg-gradient-to-r from-ink to-transparent" style={{ left: 0 }} />
        <div className="pointer-events-none absolute inset-y-0 w-8 bg-gradient-to-l from-ink to-transparent" style={{ right: 0 }} />
      </div>
      <div className="flex justify-between border-t border-line/40 px-2 py-0.5 text-[10px] text-white/25">
        <span>← {WINDOW}s</span>
        <span>滚动显示 {WINDOW * 2}s 窗口，中间线 = 当前播放位置</span>
        <span>{WINDOW}s →</span>
      </div>
    </div>
  )
}
