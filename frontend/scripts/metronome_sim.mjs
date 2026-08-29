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
  setEnabled(on) {
    this.enabled = on
    if (on) this.restart()
  }
  restart() {
    const interval = 60 / this.bpm
    const now = this.audio.currentTime + 0.03
    if (this.phaseKnown) {
      const k = Math.max(0, Math.ceil((now - this.phase) / interval - 1e-9))
      this.nextTime = this.phase + k * interval
      while (this.nextTime < now) this.nextTime += interval
    } else {
      this.nextTime = now
    }
  }
  tick() {
    if (this.audio.paused) return
    const lookahead = 0.25
    const interval = 60 / this.bpm
    const horizon = this.audio.currentTime + lookahead
    while (this.nextTime < horizon) {
      const mediaTime = this.nextTime
      const ctxTime = this.ctx.currentTime + (mediaTime - this.audio.currentTime)
      if (ctxTime > this.ctx.currentTime + 0.002) {
        this.scheduled.push({ mediaTime, ctxTime })
      }
      this.nextTime += interval
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

console.log(failures === 0 ? "\nRESULT: PASS" : `\nRESULT: FAIL (${failures})`)
process.exit(failures === 0 ? 0 : 1)
