/**
 * Browser-side metronome using the Web Audio API.
 *
 * Alignment model:
 *  - Beats live on the MEDIA timeline: a beat occurs at `phase`, then every
 *    60/bpm seconds (phase is the first-beat position of the time-stretched
 *    audio, derived from the BPM analysis' beat offset).
 *  - A lookahead scheduler (25ms tick, 250ms horizon) converts media time to
 *    the AudioContext clock at scheduling time:
 *        ctxTime = ctx.currentTime + (mediaTime - audio.currentTime)
 *    so clicks land on the music's beats regardless of when the context was
 *    created or where playback currently is (seek, song change, pause).
 *  - Every scheduled click is tracked so disabling/pausing cancels pending
 *    clicks instantly (no "ghost" clicks).
 */
export class Metronome {
  private ctx: AudioContext | null = null
  private timer: number | null = null
  private nextTime = 0
  private bpm = 120
  private phase = 0 // media-time seconds at which a beat occurs (grid anchor)
  private phaseKnown = false
  private phaseOffset = 0 // seconds; positive shifts clicks later (滞后)
  private enabled = false
  private vol = 0.5
  private audio: HTMLAudioElement
  private sources = new Set<{ osc: OscillatorNode; gain: GainNode }>()

  constructor(audio: HTMLAudioElement) {
    this.audio = audio
  }

  setBpm(bpm: number): void {
    if (bpm === this.bpm) return
    this.bpm = bpm
    if (this.enabled) this.restart()
  }

  /** Anchor the beat grid: a beat occurs at `phaseSeconds`, then every 60/bpm. */
  setPhase(phaseSeconds: number | null | undefined): void {
    const known = phaseSeconds != null && Number.isFinite(phaseSeconds) && phaseSeconds >= 0
    this.phase = known ? phaseSeconds : 0
    this.phaseKnown = known
    if (this.enabled) this.restart()
  }

  /**
   * Manual fine-tune of the grid position (seconds). Positive = clicks later.
   * Callers should clamp to +/- half a beat. No-op while free-running (no grid).
   */
  setPhaseOffset(offsetSeconds: number): void {
    if (Math.abs(offsetSeconds - this.phaseOffset) < 1e-9) return
    this.phaseOffset = offsetSeconds
    if (this.enabled) this.restart()
  }

  setVolume(v: number): void {
    this.vol = Math.min(1, Math.max(0, v))
  }

  setEnabled(on: boolean): void {
    if (on === this.enabled) return
    this.enabled = on
    if (on) {
      this.ensureCtx()
      void this.ctx?.resume()
      this.restart()
    } else {
      this.stopScheduler()
    }
  }

  get isEnabled(): boolean {
    return this.enabled
  }

  onPlay(): void {
    if (this.enabled) this.restart()
  }

  onPause(): void {
    this.stopScheduler()
  }

  onSeek(): void {
    if (this.enabled) this.restart()
  }

  dispose(): void {
    this.stopScheduler()
    void this.ctx?.close()
    this.ctx = null
  }

  private ensureCtx(): void {
    if (!this.ctx) {
      const Ctor =
        window.AudioContext ??
        (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext
      if (Ctor) this.ctx = new Ctor()
    }
  }

  private restart(): void {
    this.stopScheduler()
    if (!this.enabled || !this.ctx) return
    const interval = 60 / this.bpm
    const now = this.audio.currentTime + 0.03 // small lead against clock read
    if (this.phaseKnown) {
      // Next grid beat at or after `now`: (phase + offset) + k*interval.
      const anchor = this.phase + this.phaseOffset
      const k = Math.max(0, Math.ceil((now - anchor) / interval - 1e-9))
      this.nextTime = anchor + k * interval
      while (this.nextTime < now) this.nextTime += interval
    } else {
      this.nextTime = now
    }
    this.timer = window.setInterval(this.tick, 25)
  }

  private stopScheduler(): void {
    if (this.timer != null) {
      window.clearInterval(this.timer)
      this.timer = null
    }
    // Cancel pending clicks so turning off / pausing is instant.
    for (const { osc, gain } of this.sources) {
      try {
        osc.stop()
      } catch {
        /* already stopped */
      }
      try {
        gain.disconnect()
      } catch {
        /* already disconnected */
      }
    }
    this.sources.clear()
  }

  private tick = (): void => {
    if (!this.ctx) return
    // While the music is paused, currentTime is frozen: don't keep
    // scheduling clicks at the same timestamp.
    if (this.audio.paused) return
    const lookahead = 0.25
    const interval = 60 / this.bpm
    const horizon = this.audio.currentTime + lookahead
    while (this.nextTime < horizon) {
      this.scheduleClick(this.nextTime)
      this.nextTime += interval
    }
  }

  private scheduleClick(mediaTime: number): void {
    if (!this.ctx) return
    // Convert media-timeline time to the AudioContext clock, re-anchored at
    // scheduling time so it stays correct across seeks and song changes.
    const when = this.ctx.currentTime + (mediaTime - this.audio.currentTime)
    if (when <= this.ctx.currentTime + 0.002) return // skip past/present clicks

    const osc = this.ctx.createOscillator()
    const gain = this.ctx.createGain()
    osc.type = 'square'
    osc.frequency.value = 1174 // D6
    gain.gain.setValueAtTime(this.vol * 0.32, when)
    gain.gain.exponentialRampToValueAtTime(0.0008, when + 0.06)
    osc.connect(gain)
    gain.connect(this.ctx.destination)
    const entry = { osc, gain }
    this.sources.add(entry)
    osc.onended = () => {
      this.sources.delete(entry)
      try {
        gain.disconnect()
      } catch {
        /* noop */
      }
    }
    osc.start(when)
    osc.stop(when + 0.08)
  }
}
