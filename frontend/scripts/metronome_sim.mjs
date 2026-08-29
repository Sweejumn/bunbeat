// Deterministic simulation of the metronome scheduling algorithm
// (mirrors frontend/src/lib/metronome.ts). Run: node __metronome_sim.mjs
class MockCtx {
  constructor() {
    this.currentTime = 0
  }
  createOscillator() {
    return { onended: null, start() {}, stop() {}, connect() {}, disconnect() {} }
  }
  createGain() {
    return { connect() {}, disconnect() {}, gain: { setValueAtTime() {}, exponentialRampToValueAtTime() {} } }
  }
}

class SimMetronome {
  constructor(audio) {
    this.audio = audio
    this.ctx = new MockCtx()
    this.bpm = 165
    this.phase = 0
    this.phaseKnown = false
    this.phaseOffset = 0
    this.beatMap = null
    this.mapIdx = 0
    this.extrapInterval = 60 / 165
    this.enabled = false
    this.nextTime = 0
    this.scheduled = [] // {mediaTime, ctxTime}
    this.sources = new Set()
  }
  setPhase(p) {
    this.phase = p
    this.phaseKnown = p != null
    if (this.enabled) this.restart()
  }
  setBeatMap(times) {
    const map = times && times.length >= 2 ? [...times].sort((a, b) => a - b) : null
    if (map && map.length >= 2) {
      this.extrapInterval = Math.max(0.05, map[map.length - 1] - map[map.length - 2])
    }
    this.beatMap = map
    if (this.enabled) this.restart()
  }
  setPhaseOffset(o) {
    this.phaseOffset = o
    if (this.enabled) this.restart()
  }
  setEnabled(on) {
    this.enabled = on
    if (on) this.restart()
  }
  restart() {
    const now = this.audio.currentTime + 0.03
    if (this.beatMap) {
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
      const interval = 60 / this.bpm
      const anchor = this.phase + this.phaseOffset
      const k = Math.max(0, Math.ceil((now - anchor) / interval - 1e-9))
      this.nextTime = anchor + k * interval
      while (this.nextTime < now) this.nextTime += interval
    } else {
      this.nextTime = now
    }
  }
  tick() {
    if (this.audio.paused) return
    const lookahead = 0.25
    const horizon = this.audio.currentTime + lookahead
    while (this.nextTime < horizon) {
      const mediaTime = this.nextTime
      const ctxTime = this.ctx.currentTime + (mediaTime - this.audio.currentTime)
      if (ctxTime > this.ctx.currentTime + 0.002) {
        this.scheduled.push({ mediaTime, ctxTime })
      }
      if (this.beatMap) {
        if (this.mapIdx < this.beatMap.length - 1) {
          this.mapIdx++
          this.nextTime = this.beatMap[this.mapIdx] + this.phaseOffset
        } else if (this.mapIdx === this.beatMap.length - 1) {
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
  disable() {
    this.enabled = false
    this.sources.clear()
  }
  // run simulated playback: step both clocks forward
  play(seconds) {
    this.audio.paused = false
    const steps = Math.round(seconds / 0.025)
    for (let i = 0; i < steps; i++) {
      this.audio.currentTime += 0.025
      this.ctx.currentTime += 0.025
      this.tick()
    }
  }
}

let failures = 0
function assert(cond, msg) {
  if (!cond) {
    console.error("FAIL:", msg)
    failures++
  } else {
    console.log("ok:", msg)
  }
}

const interval = 60 / 165 // 0.363636
const audio = { currentTime: 0, paused: true, duration: 300 }
const m = new SimMetronome(audio)

// --- scenario 1: enable mid-song at 42.3s, grid phase from analysis
audio.currentTime = 42.3
m.setPhase(0.1417) // e.g. 別れ2012 -> 165 BPM
m.setEnabled(true)
m.play(0.6) // play 600ms, ticking every 25ms
assert(m.scheduled.length > 0, "clicks scheduled after enabling")
assert(
  m.scheduled[0].mediaTime - 42.3 <= interval + 0.06,
  `first click within one interval of enable (${(m.scheduled[0].mediaTime - 42.3).toFixed(3)}s)`,
)
for (const s of m.scheduled) {
  const off = (s.mediaTime - 0.1417) % interval
  const gridErr = Math.min(Math.abs(off), interval - Math.abs(off))
  assert(gridErr < 1e-6, `click ${s.mediaTime.toFixed(4)} on grid (err ${gridErr.toExponential(2)})`)
  const expectedCtx = 0 + (s.mediaTime - 42.3)
  assert(
    Math.abs(s.ctxTime - expectedCtx) < 1e-6,
    `ctx conversion (${s.ctxTime.toFixed(4)} vs expected ${expectedCtx.toFixed(4)})`,
  )
}

// --- scenario 2: context created long ago (ctx.currentTime = 500), audio at 12s
m.scheduled.length = 0
m.ctx.currentTime = 500
audio.currentTime = 12.0
m.setEnabled(true)
m.play(0.6)
for (const s of m.scheduled) {
  const expectedCtx = 500 + (s.mediaTime - 12.0)
  assert(
    Math.abs(s.ctxTime - expectedCtx) < 1e-6,
    `ctx offset handled (${s.ctxTime.toFixed(3)} vs ${expectedCtx.toFixed(3)})`,
  )
}

// --- scenario 3: seek backward while enabled -> grid continues, no past clicks
audio.currentTime = 120
m.setEnabled(true)
m.play(0.3)
m.scheduled.length = 0
audio.currentTime = 30 // seeked back to 30s
m.restart()
m.play(0.6)
assert(m.scheduled[0].mediaTime >= 30, `post-seek first click not in past (${m.scheduled[0].mediaTime.toFixed(3)})`)
const off = (m.scheduled[0].mediaTime - 0.1417) % interval
assert(
  Math.min(Math.abs(off), interval - Math.abs(off)) < 1e-6,
  "grid phase preserved after seek",
)

// --- scenario 4: disable clears pending sources
m.disable()
assert(m.sources.size === 0, "disable clears pending sources")

// --- scenario 5: no clicks while paused
m.scheduled.length = 0
audio.paused = true
audio.currentTime = 50
m.setEnabled(true)
m.tick()
assert(m.scheduled.length === 0, "no clicks scheduled while paused")

// --- scenario 6: no phase (free-run) still ticks
m.scheduled.length = 0
audio.paused = false
audio.currentTime = 5
m.setPhase(null)
m.setEnabled(true)
m.play(0.5)
assert(m.scheduled.length > 0, "free-run mode schedules clicks")

// --- scenario 7: manual phase nudge shifts the grid (positive = later)
m.scheduled.length = 0
audio.currentTime = 42.3
m.setPhase(0.1417)
m.setPhaseOffset(0.1818) // +50% of a beat at 165 BPM
m.setEnabled(true)
m.play(0.6)
for (const s of m.scheduled) {
  const off = (s.mediaTime - (0.1417 + 0.1818)) % interval
  const gridErr = Math.min(Math.abs(off), interval - Math.abs(off))
  assert(gridErr < 1e-6, `nudged click on (phase+offset) grid (err ${gridErr.toExponential(2)})`)
}
// negative nudge shifts earlier
m.scheduled.length = 0
m.setPhaseOffset(-0.09)
m.play(0.6)
for (const s of m.scheduled) {
  const off = (s.mediaTime - (0.1417 - 0.09)) % interval
  const gridErr = Math.min(Math.abs(off), interval - Math.abs(off))
  assert(gridErr < 1e-6, `negative nudge grid (err ${gridErr.toExponential(2)})`)
}

// --- scenario 8: beat-map mode — clicks land exactly on map times
const map = []
for (let k = 0; k < 20; k++) map.push(2.0 + k * 0.4) // beats at 2.0, 2.4, ..., 9.6 (150 BPM)
m.setPhaseOffset(0) // reset nudge from previous scenario
m.setPhase(null) // ensure grid fallback is off
m.scheduled.length = 0
audio.currentTime = 2.5
m.setBeatMap(map)
m.setEnabled(true)
m.play(1.0)
assert(m.scheduled.length >= 2, "map mode schedules clicks")
for (const s of m.scheduled) {
  const inMap = map.some((t) => Math.abs(s.mediaTime - t) < 1e-6)
  assert(inMap, `map click ${s.mediaTime.toFixed(4)} is exactly a map beat`)
}
// first click is the first map beat >= enable position (+0.03 lead)
assert(Math.abs(m.scheduled[0].mediaTime - 2.8) < 1e-6, `first map click at 2.8 (got ${m.scheduled[0].mediaTime})`)

// --- scenario 9: seek backward inside the map re-anchors
m.scheduled.length = 0
audio.currentTime = 6.0
m.restart()
m.play(0.6)
assert(Math.abs(m.scheduled[0].mediaTime - 6.4) < 1e-6, `post-seek first click on next map beat 6.4 (got ${m.scheduled[0].mediaTime})`)

// --- scenario 10: past the end of the map -> extrapolate with last interval
m.scheduled.length = 0
audio.currentTime = 9.7 // after last map beat 9.6
m.restart()
m.play(1.0)
assert(m.scheduled.length >= 2, "extrapolation schedules clicks")
for (const s of m.scheduled) {
  const off = (s.mediaTime - 9.6) % 0.4
  const err = Math.min(Math.abs(off), 0.4 - Math.abs(off))
  assert(err < 1e-6, `extrapolated click at ${s.mediaTime.toFixed(3)} continues 0.4s grid (err ${err.toExponential(2)})`)
}

// --- scenario 11: nudge shifts the whole beat map
m.scheduled.length = 0
m.setPhaseOffset(0.2) // +50% of the 0.4s beat
audio.currentTime = 2.5
m.restart()
m.play(0.6)
for (const s of m.scheduled) {
  const inMap = map.some((t) => Math.abs(s.mediaTime - (t + 0.2)) < 1e-6)
  assert(inMap, `nudged map click at ${s.mediaTime.toFixed(4)} (beat+0.2)`)
}
m.setPhaseOffset(0) // reset

console.log(failures === 0 ? "\nRESULT: PASS" : `\nRESULT: FAIL (${failures})`)
process.exit(failures === 0 ? 0 : 1)
