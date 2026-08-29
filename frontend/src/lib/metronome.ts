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
  private beatMap: number[] | null = null // media-time beat positions (processed timeline)
  private mapIdx = 0
  private extrapInterval = 60 / 120
  private enabled = false
  private vol = 0.5
  private audio: HTMLAudioElement
  private sources = new Set<{ osc: OscillatorNode; gain: GainNode }>()
  private clickListeners = new Set<(mediaTime: number) => void>()

  constructor(audio: HTMLAudioElement) {
    this.audio = audio
  }

  /** Subscribe to scheduled click times (media timeline). Returns unsubscribe. */
  onClick(listener: (mediaTime: number) => void): () => void {
    this.clickListeners.add(listener)
    return () => this.clickListeners.delete(listener)
  }

  setBpm(bpm: number): void {
    if (bpm === this.bpm) return
    this.bpm = bpm
    this.extrapInterval = 60 / bpm
    if (this.enabled) this.restart()
  }

  /**
   * Beat map (media-time seconds, ascending): clicks land exactly on these
   * positions, so they follow the music's real beats and never drift. When
   * the map runs out, clicks extrapolate with the map's last interval.
   */
  setBeatMap(beatTimes: number[] | null | undefined): void {
    const map = beatTimes && beatTimes.length >= 2 ? [...beatTimes].sort((a, b) => a - b) : null
    if (map && map.length >= 2) {
      this.extrapInterval = Math.max(0.05, map[map.length - 1] - map[map.length - 2])
    }
    this.beatMap = map
    if (this.enabled) this.restart()
  }

  /** Anchor the beat grid (fallback when no beat map): a beat at `phaseSeconds`, then every 60/bpm. */
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

  /** Number of clicks currently scheduled but not yet played (stall probe). */
  get pendingCount(): number {
    return this.sources.size
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
    const now = this.audio.currentTime + 0.03 // small lead against clock read
    if (this.beatMap) {
      // First map beat (nudged) at or after `now`; past the end, extrapolate.
      let i = 0
      while (i < this.beatMap.length && this.beatMap[i] + this.phaseOffset < now - 1e-9) i++
      this.mapIdx = i
      if (i < this.beatMap.length) {
        this.nextTime = this.beatMap[i] + this.phaseOffset
      } else {
        const last = this.beatMap[this.beatMap.length - 1] + this.phaseOffset
        const prev = this.beatMap[this.beatMap.length - 2] + this.phaseOffset
        this.extrapInterval = Math.max(0.05, last - prev)
        this.mapIdx = this.beatMap.length
        this.nextTime = last + this.extrapInterval
        while (this.nextTime < now) this.nextTime += this.extrapInterval
      }
    } else if (this.phaseKnown) {
      // Fixed-grid fallback: (phase + offset) + k*interval.
      const interval = 60 / this.bpm
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
    const horizon = this.audio.currentTime + lookahead
    while (this.nextTime < horizon) {
      this.scheduleClick(this.nextTime)
      if (this.beatMap) {
        if (this.mapIdx < this.beatMap.length - 1) {
          this.mapIdx++
          this.nextTime = this.beatMap[this.mapIdx] + this.phaseOffset
        } else if (this.mapIdx === this.beatMap.length - 1) {
          // last map beat scheduled -> extrapolate with the map's interval
          const last = this.beatMap[this.beatMap.length - 1] + this.phaseOffset
          const prev = this.beatMap[this.beatMap.length - 2] + this.phaseOffset
          this.extrapInterval = Math.max(0.05, last - prev)
          this.mapIdx = this.beatMap.length
          this.nextTime += this.extrapInterval
        } else {
          this.nextTime += this.extrapInterval
        }
      } else {
        this.nextTime += 60 / this.bpm
      }
    }
  }

  private scheduleClick(mediaTime: number): void {
    if (!this.ctx) return
    // Stall protection: if the audio element freezes (buffer underrun) without
    // a 'waiting' event, pending clicks would otherwise accumulate forever.
    if (this.sources.size > 300) return
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
    for (const fn of this.clickListeners) fn(mediaTime)
  }
}
