/**
 * Browser-side metronome using the Web Audio API.
 *
 * Clicks are scheduled ahead of time (lookahead scheduler) and aligned to
 * the <audio> element's currentTime. A beat phase can be supplied so clicks
 * land ON the music's actual beats (see `setPhase`): with a known phase the
 * scheduler snaps to the grid  phase + k * (60/bpm), otherwise it free-runs
 * from the current playback position.
 */
export class Metronome {
  private ctx: AudioContext | null = null
  private timer: number | null = null
  private nextTime = 0
  private bpm = 120
  private phase = 0 // seconds at which a beat occurs (grid anchor)
  private phaseKnown = false
  private enabled = false
  private vol = 0.5
  private audio: HTMLAudioElement

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
    const now = this.audio.currentTime + 0.05
    if (this.phaseKnown) {
      // Next grid beat at or after `now`: phase + k*interval.
      const k = Math.max(0, Math.ceil((now - this.phase) / interval - 1e-9))
      this.nextTime = this.phase + k * interval
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
  }

  private tick = (): void => {
    if (!this.ctx) return
    // While the music is paused, currentTime is frozen: don't keep
    // scheduling clicks at the same timestamp.
    if (this.audio.paused) return
    const lookahead = 0.15
    const interval = 60 / this.bpm
    while (this.nextTime < this.audio.currentTime + lookahead) {
      this.scheduleClick(this.nextTime)
      this.nextTime += interval
    }
  }

  private scheduleClick(time: number): void {
    if (!this.ctx) return
    const osc = this.ctx.createOscillator()
    const gain = this.ctx.createGain()
    osc.type = 'square'
    osc.frequency.value = 1174 // D6
    gain.gain.setValueAtTime(this.vol * 0.32, time)
    gain.gain.exponentialRampToValueAtTime(0.0008, time + 0.06)
    osc.connect(gain)
    gain.connect(this.ctx.destination)
    osc.start(time)
    osc.stop(time + 0.08)
  }
}
