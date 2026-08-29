import { useEffect, useRef } from 'react'

/**
 * Scrolling beat ruler for debugging metronome alignment.
 *
 * Three rows scroll past a fixed center playhead:
 *   预期   the ORIGINAL song's beats mapped onto the stretched timeline
 *          (dim) — where the old fixed-grid approach clicked
 *   实际   the stretched file's own detected beats (green) — what the
 *          metronome now clicks on
 *   点击   live metronome clicks recorded via `clicksRef` (amber dots)
 * Any drift/jitter/wobble between the rows is exactly what the user hears.
 */
interface Props {
  predicted: number[] // processed-timeline seconds
  actual: number[] // processed-timeline seconds
  getCurrentTime: () => number
  clicksRef: React.MutableRefObject<number[]>
}

const PX = 70 // pixels per second
const CENTER = 180
const VISIBLE = 5 // seconds either side of the playhead

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
  const renderedClicks = useRef(0)

  useEffect(() => {
    let raf = 0
    const loop = () => {
      const now = getCurrentTime()
      const off = CENTER - now * PX
      if (predRef.current) predRef.current.style.transform = `translateX(${off}px)`
      if (actRef.current) actRef.current.style.transform = `translateX(${off}px)`
      const clickRow = clickRowRef.current
      if (clickRow) {
        clickRow.style.transform = `translateX(${off}px)`
        // append dots for newly scheduled clicks (stable DOM, no re-render)
        const clicks = clicksRef.current
        for (let i = renderedClicks.current; i < clicks.length; i++) {
          const dot = document.createElement('div')
          dot.className = 'absolute top-0 h-full w-[5px] rounded-full bg-accent'
          dot.style.left = `${clicks[i] * PX - 2.5}px`
          clickRow.appendChild(dot)
        }
        renderedClicks.current = clicks.length
      }
      raf = requestAnimationFrame(loop)
    }
    raf = requestAnimationFrame(loop)
    return () => cancelAnimationFrame(raf)
  }, [getCurrentTime, clicksRef])

  // reset click markers when the song changes (component key remounts anyway)
  useEffect(() => {
    renderedClicks.current = 0
    const row = clickRowRef.current
    if (row) row.replaceChildren()
  }, [predicted, actual])

  return (
    <div className="mt-2 overflow-hidden rounded-lg border border-line bg-ink/60">
      <div className="relative" style={{ width: CENTER * 2 }}>
        <TickRow times={predicted} color="bg-slate-500/70" label="预期" refCb={(el) => (predRef.current = el)} />
        <TickRow times={actual} color="bg-run" label="实际" refCb={(el) => (actRef.current = el)} />
        <div className="relative h-6">
          <span className="absolute left-1 top-0 z-10 text-[10px] leading-6 text-white/35">点击</span>
          <div ref={clickRowRef} className="absolute inset-y-0 will-change-transform" />
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
        <span>← {VISIBLE}s</span>
        <span>滚动显示 {VISIBLE * 2}s 窗口，中间线 = 当前播放位置</span>
        <span>{VISIBLE}s →</span>
      </div>
    </div>
  )
}
