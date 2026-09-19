// WAV entry-point parity probe (harness code, not part of the ported library).
// Mirrors Dart's `BpmAnalyzer.analyzeWavFile`:
//   decode -> (null || size < kSampleRate * 2) ? "too short" result : analyzePcm
//
// Usage: WavProbeMain <out.json> <file1.wav> [file2.wav ...]
package com.bunbeat.bpm

import java.io.File

private fun jnumP(d: Double?): String {
    if (d == null) return "null"
    if (d.isNaN() || d.isInfinite()) return "null"
    return d.toString()
}

private fun fpP(xs: List<Double>?): String {
    if (xs == null) return "{\"n\": -1}"
    if (xs.isEmpty()) return "{\"n\": 0}"
    var sum = 0.0
    var sq = 0.0
    var wsum = 0.0
    var mn = xs[0]
    var mx = xs[0]
    for (i in xs.indices) {
        val v = xs[i]
        sum += v
        sq += v * v
        wsum += v * (i + 1)
        if (v < mn) mn = v
        if (v > mx) mx = v
    }
    val head = xs.take(4).joinToString(", ") { jnumP(it) }
    return "{\"n\": ${xs.size}, \"sum\": ${jnumP(sum)}, \"sq\": ${jnumP(sq)}, " +
        "\"wsum\": ${jnumP(wsum)}, \"min\": ${jnumP(mn)}, \"max\": ${jnumP(mx)}, \"head\": [$head]}"
}

private fun jstrP(s: String?): String {
    if (s == null) return "null"
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append("\"")
    return sb.toString()
}

private fun dumpP(r: BpmResult): String {
    val keys = r.beatMaps?.keys?.joinToString(", ") { jstrP(it) } ?: ""
    return buildString {
        append("{")
        append("\"bpm\": ${jnumP(r.bpm)}, ")
        append("\"confidence\": ${jnumP(r.confidence)}, ")
        append("\"duration\": ${jnumP(r.duration)}, ")
        append("\"error\": ${jstrP(r.error)}, ")
        append("\"beatOffset\": ${jnumP(r.beatOffset)}, ")
        append("\"phaseReliability\": ${jnumP(r.phaseReliability)}, ")
        append("\"beats\": ${fpP(r.beatTimes)}, ")
        append("\"grid\": ${fpP(r.beatMaps?.get("grid"))}, ")
        append("\"snap\": ${fpP(r.beatMaps?.get("snap"))}, ")
        append("\"mapKeys\": [$keys]")
        append("}")
    }
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: WavProbeMain <out.json> <file.wav>...")
        kotlin.system.exitProcess(2)
    }
    val outPath = args[0]
    val sb = StringBuilder()
    sb.append("{\n \"sampleRate\": ${BpmAnalyzer.kSampleRate},\n \"files\": {\n")
    var first = true
    for (p in args.drop(1)) {
        val f = File(p)
        // Call the real public entry point, exactly like the Dart probe does.
        val r: BpmResult = BpmAnalyzer.analyzeWavFile(p)
        if (!first) sb.append(",\n")
        first = false
        sb.append("  ${jstrP(f.name)}: {\n   \"0_wavfile\": ${dumpP(r)}\n  }")
        System.err.println("PROBED\t${f.name}\t${r.bpm}")
    }
    sb.append("\n }\n}\n")
    File(outPath).writeText(sb.toString())
    System.err.println("WROTE $outPath")
}
