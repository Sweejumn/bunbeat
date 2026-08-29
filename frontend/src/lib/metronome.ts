/**
 * Browser-side metronome using the Web Audio API.
 *
 * Clicks are scheduled ahead of time (lookahead scheduler) and aligned to
 * the <audio> element's currentTime, so the metronome stays in sync with
 * the music. Every 4th beat is an accented "strong" click.
 */
export class Metronome {
  private ctx: AudioContext | null = null
  private timer: number | null = null
  private nextTime = 0
  private beat = 0
  private bpm = 120
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
    // Align the next beat shortly after the current playback position.
    this.nextTime = this.audio.currentTime + 0.08
    this.beat = Math.floor(this.nextTime / (60 / this.bpm))
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
      this.scheduleClick(this.nextTime, this.beat)
      this.nextTime += interval
      this.beat += 1
    }
  }

  private scheduleClick(time: number, beat: number): void {
    if (!this.ctx) return
    const osc = this.ctx.createOscillator()
    const gain = this.ctx.createGain()
    const strong = beat % 4 === 0
    osc.type = 'square'
    osc.frequency.value = strong ? 1760 : 1174 // A6 / D6
    const peak = this.vol * (strong ? 0.55 : 0.32)
    gain.gain.setValueAtTime(peak, time)
    gain.gain.exponentialRampToValueAtTime(0.0008, time + (strong ? 0.09 : 0.06))
    osc.connect(gain)
    gain.connect(this.ctx.destination)
    osc.start(time)
    osc.stop(time + 0.12)
  }
}
