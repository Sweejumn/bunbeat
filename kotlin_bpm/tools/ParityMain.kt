// Parity harness runner (not part of the ported library):
// reads WAV paths from argv, runs all 6 engines, writes a JSON mirror of the
// Dart golden file so the two can be diffed numerically.
//
// Usage: ParityMain <out.json> <file1.wav> [file2.wav ...]
package com.bunbeat.bpm

import java.io.File

private fun jnum(d: Double?): String {
    if (d == null) return "null"
    if (d.isNaN() || d.isInfinite()) return "null"
    return d.toString()
}

/** 数值指纹：与 Dart 侧 fp() 完全同构（n/sum/sq/wsum/min/max/head）。 */
private fun fp(xs: List<Double>?): String {
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
    val head = xs.take(4).joinToString(", ") { jnum(it) }
    return "{\"n\": ${xs.size}, \"sum\": ${jnum(sum)}, \"sq\": ${jnum(sq)}, " +
        "\"wsum\": ${jnum(wsum)}, \"min\": ${jnum(mn)}, \"max\": ${jnum(mx)}, \"head\": [$head]}"
}

private fun jstr(s: String?): String {
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

private fun dump(r: BpmResult): String {
    val keys = r.beatMaps?.keys?.joinToString(", ") { jstr(it) } ?: ""
    return buildString {
        append("{")
        append("\"bpm\": ${jnum(r.bpm)}, ")
        append("\"confidence\": ${jnum(r.confidence)}, ")
        append("\"duration\": ${jnum(r.duration)}, ")
        append("\"error\": ${jstr(r.error)}, ")
        append("\"beatOffset\": ${jnum(r.beatOffset)}, ")
        append("\"phaseReliability\": ${jnum(r.phaseReliability)}, ")
        append("\"beats\": ${fp(r.beatTimes)}, ")
        append("\"grid\": ${fp(r.beatMaps?.get("grid"))}, ")
        append("\"snap\": ${fp(r.beatMaps?.get("snap"))}, ")
        append("\"mapKeys\": [$keys]")
        append("}")
    }
}

fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: ParityMain <out.json> <file.wav>...")
        kotlin.system.exitProcess(2)
    }
    val outPath = args[0]
    val engines: List<Pair<String, (DoubleArray, Int) -> BpmResult>> = listOf(
        "1_librosa" to { s, sr -> BpmAnalyzer.analyzeLibrosaPcm(s, sr) },
        "2_tempogram" to { s, sr -> BpmAnalyzer.analyzeTempogramPcm(s, sr) },
        "3_peakcluster" to { s, sr -> BpmAnalyzer.analyzePeakClusterPcm(s, sr) },
        "4_basskick" to { s, sr -> BpmAnalyzer.analyzeBassKickPcm(s, sr) },
        "5_dpbeats" to { s, sr -> BpmAnalyzer.analyzeDpBeatsPcm(s, sr) },
        "6_combfold" to { s, sr -> BpmAnalyzer.analyzeCombFoldPcm(s, sr) },
    )
    val sb = StringBuilder()
    sb.append("{\n \"sampleRate\": ${BpmAnalyzer.kSampleRate},\n \"files\": {\n")
    var firstFile = true
    for (p in args.drop(1)) {
        val f = File(p)
        val name = f.name
        val samples = BpmAnalyzer.decodeWavPcm16(f.readBytes())
        if (!firstFile) sb.append(",\n")
        firstFile = false
        sb.append("  ${jstr(name)}: {\n")
        sb.append("   \"samples\": ${samples?.size ?: -1}")
        for ((key, fn) in engines) {
            sb.append(",\n   ${jstr(key)}: ")
            if (samples == null) {
                sb.append("{\"exception\": \"decode failed\"}")
                continue
            }
            sb.append(
                try {
                    dump(fn(samples, BpmAnalyzer.kSampleRate))
                } catch (e: Throwable) {
                    "{\"exception\": ${jstr(e.toString())}}"
                }
            )
        }
        sb.append("\n  }")
        System.err.println("DONE\t$name\t${samples?.size ?: -1}")
    }
    sb.append("\n }\n}\n")
    File(outPath).writeText(sb.toString())
    System.err.println("WROTE $outPath")
}
