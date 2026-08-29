import { useEffect, useRef } from 'react'

/**
 * Minimal beat ruler: green tick marks at the beat positions scroll past a
 * fixed center playhead. No canvas, no audio capture, no waveform/spectrum —
 * one CSS transform per frame, so it cannot flicker or lag.
 *
 * Alignment reading: a green line crossing the center line = a beat is now.
 */
interface Props {
  beats: number[] // beat positions (seconds in the processed timeline)
  getCurrentTime: () => number
}

const PX = 80 // pixels per second

export function BeatRuler({ beats, getCurrentTime }: Props) {
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
        <div ref={rowRef} className="absolute inset-y-0 will-change-transform">
          {beats.map((t) => (
            <div
              key={t}
              className="absolute inset-y-1 w-[2px] rounded bg-run/70"
              style={{ left: t * PX - 1 }}
            />
          ))}
        </div>
        {/* playhead */}
        <div className="absolute inset-y-0 left-1/2 w-px bg-white/85" />
        <div className="absolute left-1/2 top-0 h-2 w-[3px] -translate-x-1/2 rounded-b bg-white/85" />
      </div>
      <div className="flex justify-between border-t border-line/40 px-2 py-0.5 text-[10px] text-white/25">
        <span>← 4s</span>
        <span>绿线 = 拍点 · 穿过中心线即当前拍 · 暂停时静止</span>
        <span>4s →</span>
      </div>
    </div>
  )
}
