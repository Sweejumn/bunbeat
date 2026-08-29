import { useEffect, useRef } from 'react'

/**
 * Music visualizer v3 — everything on ONE time axis, cheap, pause-aware.
 *
 *   - scrolling WAVEFORM history (block min/max envelope — 1 read per pixel)
 *   - LIVE frequency meter fixed at the center playhead (16 bands, "现在"频谱)
 *   - beat grid (green lines) and metronome clicks (amber dots) overlaid,
 *     scrolling at the same speed as the waveform
 *   - FREEZES entirely while the audio is paused (no phantom motion)
 *   - capped at ~30 fps; no per-pixel sample loops
 */
interface Props {
  beats: number[] // active beat map (processed-timeline seconds)
  clicksRef: React.MutableRefObject<number[]>
  getCurrentTime: () => number
  getPaused: () => boolean
  analyser: AnalyserNode | null
}

const PX = 70 // pixels per second (scroll speed)
const HISTORY_SEC = 8
const BAND_COUNT = 16
const FPS_CAP = 33 // ms per draw (>=30fps)

export function MusicVisualizer({ beats, clicksRef, getCurrentTime, getPaused, analyser }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  // block envelope ring buffer: one {min,max} pair per analyser fftSize chunk
  const envRef = useRef<{ min: Float32Array; max: Float32Array; write: number; n: number } | null>(null)
  const timeScratch = useRef<Float32Array<ArrayBuffer> | null>(null)
  const freqScratch = useRef<Uint8Array<ArrayBuffer> | null>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    let raf = 0
    let lastDraw = 0
    let width = 100
    let height = 96
    // NOTE: only the ResizeObserver resizes the canvas; resizing resets
    // canvas.width which CLEARS the frame — doing that on every effect run
    // was the source of the flicker.
    const resize = () => {
      const rect = canvas.getBoundingClientRect()
      const dpr = window.devicePixelRatio || 1
      width = Math.max(100, rect.width)
      height = Math.max(48, rect.height)
      canvas.width = Math.round(width * dpr)
      canvas.height = Math.round(height * dpr)
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    }
    const ro = new ResizeObserver(resize)
    ro.observe(canvas)
    resize()

    const loop = (t: number) => {
      raf = requestAnimationFrame(loop)
      if (document.hidden) return
      if (t - lastDraw < FPS_CAP) return
      lastDraw = t

      const now = getCurrentTime()
      const cx = width / 2
      const paused = getPaused()

      // Trim click history so the list cannot grow without bound.
      const clicks = clicksRef.current
      if (clicks.length > 200) {
        clicksRef.current = clicks.filter((c) => c > now - 15)
      }

      // --- collect one analyser chunk -> one envelope block (only while
      // playing; paused audio feeds silence anyway) ---
      if (analyser && !paused) {
        if (!envRef.current) {
          const n = Math.ceil((HISTORY_SEC * analyser.context.sampleRate) / analyser.fftSize)
          envRef.current = { min: new Float32Array(n), max: new Float32Array(n), write: 0, n }
        }
        if (!timeScratch.current) timeScratch.current = new Float32Array(analyser.fftSize)
        analyser.getFloatTimeDomainData(timeScratch.current)
        const chunk = timeScratch.current
        let mn = 1
        let mx = -1
        for (let i = 0; i < chunk.length; i++) {
          if (chunk[i] < mn) mn = chunk[i]
          if (chunk[i] > mx) mx = chunk[i]
        }
        const env = envRef.current
        env.min[env.write] = mn
        env.max[env.write] = mx
        env.write = (env.write + 1) % env.n
        if (!freqScratch.current) freqScratch.current = new Uint8Array(analyser.frequencyBinCount)
        analyser.getByteFrequencyData(freqScratch.current)
      }

      // --- background ---
      ctx.fillStyle = 'rgba(10,15,13,0.92)'
      ctx.fillRect(0, 0, width, height)

      const meterH = Math.min(14, height * 0.15)
      const waveTop = meterH + 6
      const waveBottom = height - 14
      const waveH = waveBottom - waveTop

      // --- waveform envelope (1 read per pixel) ---
      const env = envRef.current
      if (env && analyser && !paused) {
        const blockDur = analyser.fftSize / analyser.context.sampleRate
        ctx.strokeStyle = 'rgba(231,239,233,0.85)'
        ctx.lineWidth = 1
        ctx.beginPath()
        for (let x = 0; x < width; x += 1) {
          const t = now + (x - cx) / PX
          const age = now - t
          if (age < 0 || age > HISTORY_SEC) {
            ctx.moveTo(x, waveTop)
            ctx.lineTo(x, waveBottom)
            continue
          }
          const bi = (env.write - 1 - Math.floor(age / blockDur) + env.n * 2) % env.n
          const y1 = waveTop + (0.5 - Math.max(env.max[bi], 0)) * waveH * 0.92
          const y2 = waveTop + (0.5 - Math.min(env.min[bi], 0)) * waveH * 0.92
          ctx.moveTo(x, y1)
          ctx.lineTo(x, y2)
        }
        ctx.stroke()
      } else {
        ctx.fillStyle = 'rgba(255,255,255,0.15)'
        ctx.font = '11px sans-serif'
        ctx.fillText(paused ? '已暂停' : '音乐波形', 12, height / 2 + 4)
      }

      // --- live frequency meter at the playhead (16 bands) ---
      if (analyser && freqScratch.current && !paused) {
        const f = freqScratch.current
        const bw = Math.min(26, width * 0.03)
        const startX = cx - (BAND_COUNT * bw) / 2
        for (let b = 0; b < BAND_COUNT; b++) {
          const bi = Math.min(f.length - 1, Math.floor(Math.pow(b / BAND_COUNT, 1.5) * f.length))
          const v = f[bi] / 255
          ctx.fillStyle = `rgba(52,211,153,${0.2 + v * 0.8})`
          ctx.fillRect(startX + b * bw, waveTop - meterH * v, bw - 1.5, Math.max(1, meterH * v))
        }
      }

      // --- beat grid lines (green), visible window only ---
      ctx.fillStyle = 'rgba(52,211,153,0.7)'
      for (const b of beats) {
        const x = Math.round(cx + (b - now) * PX)
        if (x >= 0 && x < width) ctx.fillRect(x, waveTop, 2, waveH)
      }

      // --- live clicks (amber dots), visible window only ---
      ctx.fillStyle = '#fbbf24'
      for (const ct of clicks) {
        const x = Math.round(cx + (ct - now) * PX)
        if (x >= -4 && x < width + 4) {
          ctx.beginPath()
          ctx.arc(x, waveBottom - 4, 3, 0, Math.PI * 2)
          ctx.fill()
        }
      }

      // --- playhead + ruler ---
      ctx.fillStyle = 'rgba(255,255,255,0.9)'
      ctx.fillRect(cx, 0, 1.5, height)
      ctx.fillStyle = 'rgba(255,255,255,0.35)'
      ctx.font = '10px monospace'
      for (let s = -4; s <= 4; s++) {
        const x = Math.round(cx + s * PX)
        if (x >= 0 && x < width) ctx.fillText(`${s > 0 ? '+' : ''}${s}s`, x + 3, height - 3)
      }
    }
    raf = requestAnimationFrame(loop)
    return () => {
      cancelAnimationFrame(raf)
      ro.disconnect()
    }
  }, [analyser, getCurrentTime, getPaused, beats])

  return <canvas ref={canvasRef} className="h-24 w-full rounded-lg" />
}
