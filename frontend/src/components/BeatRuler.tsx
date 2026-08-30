import { useEffect, useRef } from 'react'

/**
 * Minimal beat ruler: green tick marks at the beat positions scroll past a
 * fixed center playhead. No canvas, no audio capture, no waveform/spectrum —
 * one CSS transform per frame, so it cannot flicker or lag.
 *
 * Alignment reading: a green line crossing the center line = a beat is now.
 * Optional amber marks show manual tap-tempo taps (for calibration).
 */
interface Props {
  beats: number[] // beat positions (seconds in the processed timeline)
  getCurrentTime: () => number
  taps?: number[] // tap-tempo tap positions (seconds), shown as amber marks
}

const PX = 80 // pixels per second

export function BeatRuler({ beats, getCurrentTime, taps = [] }: Props) {
  const rowRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    let raf = 0
    const loop = () => {
      // One composited transform update per frame; time is frozen while
      // paused, so the ruler freezes too.
      const now = getCurrentTime()
      if (rowRef.current) {
        rowRef.current.style.transform = `translateX(calc(50% - ${(now * PX).toFixed(2)}px))`
      }
      raf = requestAnimationFrame(loop)
    }
    raf = requestAnimationFrame(loop)
    return () => cancelAnimationFrame(raf)
  }, [getCurrentTime])

  return (
    <div className="mt-2 overflow-hidden rounded-lg border border-line bg-ink/70">
      <div className="relative h-14">
        {/* inset-0 (not inset-y-0): the row must span the full container
            width so the 50% inside the transform means the container center
            (the playhead). With inset-y-0 the row collapses to 0px wide and
            every tick lands at the left edge. */}
        <div ref={rowRef} className="absolute inset-0 will-change-transform">
          {beats.map((t, i) => (
            <div
              key={i}
              className="absolute inset-y-1 w-[2px] rounded bg-run/70"
              style={{ left: t * PX - 1 }}
            />
          ))}
          {taps.map((t, i) => (
            <div
              key={`tap-${i}`}
              className="absolute inset-y-1 w-[3px] rounded bg-accent"
              style={{ left: t * PX - 1.5 }}
              title={`打拍 ${i + 1}: ${t.toFixed(3)}s`}
            />
          ))}
        </div>
        {/* playhead */}
        <div className="absolute inset-y-0 left-1/2 w-px bg-white/85" />
        <div className="absolute left-1/2 top-0 h-2 w-[3px] -translate-x-1/2 rounded-b bg-white/85" />
      </div>
      <div className="flex justify-between border-t border-line/40 px-2 py-0.5 text-[10px] text-white/25">
        <span>← 4s</span>
        <span>4s →</span>
      </div>
    </div>
  )
}
