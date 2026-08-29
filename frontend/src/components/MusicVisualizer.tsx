import { useEffect, useRef } from 'react'

/**
 * Real music visualization: scrolling WAVEFORM (the actual audio signal),
 * a thin frequency SPECTRUM strip, the active BEAT grid (green lines) and
 * live metronome CLICKS (amber dots) overlaid, plus a center playhead.
 *
 * Alignment is instantly readable: waveform peaks, green beat lines and
 * amber click dots should all cross the center line together.
 *
 * Resource safety: one rAF loop (transform/draw only), ring buffer of fixed
 * size, ResizeObserver cleaned up on unmount, component keyed per song.
 */
interface Props {
  beats: number[] // active beat map (processed-timeline seconds)
  clicksRef: React.MutableRefObject<number[]>
  getCurrentTime: () => number
  analyser: AnalyserNode | null
}

const PX = 70 // pixels per second (scroll speed)
const HISTORY_SEC = 8 // seconds of waveform history kept

export function MusicVisualizer({ beats, clicksRef, getCurrentTime, analyser }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const bufferRef = useRef<{ data: Float32Array; write: number } | null>(null)
  const timeScratch = useRef<Float32Array<ArrayBuffer> | null>(null)
  const freqScratch = useRef<Uint8Array<ArrayBuffer> | null>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    let raf = 0
    let width = 100
    let height = 96
    const resize = () => {
      const rect = canvas.getBoundingClientRect()
      const dpr = window.devicePixelRatio || 1
      width = Math.max(100, rect.width)
      height = Math.max(48, rect.height)
      canvas.width = Math.round(width * dpr)
      canvas.height = Math.round(height * dpr)
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    }
    resize()
    const ro = new ResizeObserver(resize)
    ro.observe(canvas)

    const sampleRate = analyser ? analyser.context.sampleRate : 44100

    const loop = () => {
      const now = getCurrentTime()
      const cx = width / 2

      // --- push the current audio frame into the waveform history ---
      if (analyser) {
        if (!bufferRef.current) {
          bufferRef.current = { data: new Float32Array(Math.ceil(HISTORY_SEC * sampleRate)), write: 0 }
        }
        if (!timeScratch.current) timeScratch.current = new Float32Array(analyser.fftSize)
        analyser.getFloatTimeDomainData(timeScratch.current)
        const buf = bufferRef.current
        const chunk = timeScratch.current
        for (let i = 0; i < chunk.length; i++) {
          buf.data[buf.write] = chunk[i]
          buf.write = (buf.write + 1) % buf.data.length
        }
        if (!freqScratch.current) freqScratch.current = new Uint8Array(analyser.frequencyBinCount)
        analyser.getByteFrequencyData(freqScratch.current)
      }

      // --- draw background ---
      ctx.fillStyle = 'rgba(10,15,13,0.9)'
      ctx.fillRect(0, 0, width, height)

      const specH = Math.min(16, height * 0.18)
      const waveTop = specH + 6
      const waveBottom = height - 4
      const waveH = waveBottom - waveTop

      // --- spectrum strip (frequency energy: "音调") ---
      if (analyser && freqScratch.current) {
        const bins = 64
        const f = freqScratch.current
        for (let i = 0; i < bins; i++) {
          // log-ish mapping over the bins
          const bi = Math.min(f.length - 1, Math.floor(Math.pow(i / bins, 1.6) * f.length))
          const v = f[bi] / 255
          ctx.fillStyle = `rgba(52,211,153,${0.25 + v * 0.75})`
          const bw = width / bins
          ctx.fillRect(i * bw, specH * (1 - v), bw - 1, specH * v)
        }
      }

      // --- waveform history (scrolling, min/max envelope) ---
      const buf = bufferRef.current
      if (buf && buf.data.length > sampleRate) {
        ctx.strokeStyle = 'rgba(231,239,233,0.9)'
        ctx.lineWidth = 1
        ctx.beginPath()
        const samplesPerPx = sampleRate / PX
        for (let x = 0; x < width; x += 1) {
          const t = now + (x - cx) / PX // seconds
          const age = now - t
          if (age < 0 || age > HISTORY_SEC) {
            ctx.moveTo(x, waveTop)
            ctx.lineTo(x, waveBottom)
            continue
          }
          const centerIdx = (buf.write - Math.round(age * sampleRate) + buf.data.length * 2) % buf.data.length
          const span = Math.max(2, Math.floor(samplesPerPx))
          let mn = 1
          let mx = -1
          for (let s = 0; s < span; s++) {
            const idx = (centerIdx + buf.data.length - Math.floor(span / 2) + s + buf.data.length) % buf.data.length
            const v = buf.data[idx]
            if (v < mn) mn = v
            if (v > mx) mx = v
          }
          const y1 = waveTop + (0.5 - mx) * waveH * 0.9
          const y2 = waveTop + (0.5 - mn) * waveH * 0.9
          ctx.moveTo(x, y1)
          ctx.lineTo(x, y2)
        }
        ctx.stroke()
      } else {
        ctx.fillStyle = 'rgba(255,255,255,0.15)'
        ctx.font = '11px sans-serif'
        ctx.fillText('音乐波形（打开节拍器后显示）', 12, height / 2 + 4)
      }

      // --- beat grid lines (green) within the visible window ---
      ctx.fillStyle = 'rgba(52,211,153,0.75)'
      for (const b of beats) {
        const x = Math.round(cx + (b - now) * PX)
        if (x >= 0 && x < width) ctx.fillRect(x, waveTop, 2, waveH)
      }

      // --- live clicks (amber dots) ---
      ctx.fillStyle = '#fbbf24'
      for (const t of clicksRef.current) {
        const x = Math.round(cx + (t - now) * PX)
        if (x >= -4 && x < width + 4) {
          ctx.beginPath()
          ctx.arc(x, waveBottom - 5, 3, 0, Math.PI * 2)
          ctx.fill()
        }
      }

      // --- playhead ---
      ctx.fillStyle = 'rgba(255,255,255,0.9)'
      ctx.fillRect(cx, 0, 1.5, height)

      // --- time ruler ---
      ctx.fillStyle = 'rgba(255,255,255,0.35)'
      ctx.font = '10px monospace'
      for (let s = -4; s <= 4; s++) {
        const x = Math.round(cx + s * PX)
        if (x >= 0 && x < width) {
          ctx.fillText(`${s > 0 ? '+' : ''}${s}s`, x + 3, height - 2)
        }
      }

      raf = requestAnimationFrame(loop)
    }
    raf = requestAnimationFrame(loop)
    return () => {
      cancelAnimationFrame(raf)
      ro.disconnect()
    }
  }, [analyser, getCurrentTime, beats])

  return <canvas ref={canvasRef} className="h-24 w-full rounded-lg" />
}
