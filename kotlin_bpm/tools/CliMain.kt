// Standalone command-line front-end for the Kotlin BPM engine.
// Usage: java -jar bunbeat-bpm.jar [options] <file.wav> [file2.wav ...]
//
// Options:
//   -j, --json          print full JSON (one object per file)
//   -b, --beats         print beat timestamps (first 16)
//   -a, --all-beats     print every beat timestamp
//   -m, --maps          print grid/snap beat maps sizes + boundaries
//   -d, --dir <path>    scan a directory recursively for .wav files
//   -t, --top <n>       with --dir, print only the n loudest/fastest? (n/a) -> first n results
//   -q, --quiet         only print "path<TAB>bpm"
//   -h, --help          this help
//   -v, --version       engine version info
package com.bunbeat.bpm

import java.io.File
import kotlin.system.exitProcess

private const val ENGINE_VERSION = "1.0.0"

private fun jnum(d: Double?): String {
    if (d == null) return "null"
    if (d.isNaN() || d.isInfinite()) return "null"
    return d.toString()
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

private fun dlist(xs: List<Double>?): String {
    if (xs == null) return "null"
    return "[" + xs.joinToString(", ") { jnum(it) } + "]"
}

private fun dumpJson(path: String, r: BpmResult, withBeats: Boolean): String {
    val sb = StringBuilder()
    sb.append("{")
    sb.append("\"file\": ${jstr(path)}, ")
    sb.append("\"bpm\": ${jnum(r.bpm)}, ")
    sb.append("\"confidence\": ${jnum(r.confidence)}, ")
    sb.append("\"duration\": ${jnum(r.duration)}, ")
    sb.append("\"beatOffset\": ${jnum(r.beatOffset)}, ")
    sb.append("\"phaseReliability\": ${jnum(r.phaseReliability)}, ")
    sb.append("\"error\": ${jstr(r.error)}, ")
    sb.append("\"beatCount\": ${r.beatTimes?.size ?: 0}")
    r.beatMaps?.let { m ->
        val keys = m.keys.joinToString(", ") { jstr(it) }
        val counts = m.values.joinToString(", ") { (it?.size ?: 0).toString() }
        sb.append(", \"mapKeys\": [$keys], \"mapCounts\": [$counts]")
    }
    if (withBeats) sb.append(", \"beats\": ${dlist(r.beatTimes)}")
    sb.append("}")
    return sb.toString()
}

private fun usage() {
    println(
        """
        bunbeat-bpm $ENGINE_VERSION - Bunbeat BPM engine (Kotlin port of the Dart implementation)

        usage: java -jar bunbeat-bpm.jar [options] <file.wav> [file2.wav ...]

          -j, --json         output JSON instead of a text table
          -b, --beats        also print the first 16 beat timestamps
          -a, --all-beats    print every beat timestamp
          -m, --maps         print grid/snap beat-map sizes and boundaries
          -d, --dir <path>   scan a directory recursively for .wav files
          -q, --quiet        print only "path<TAB>bpm"
          -h, --help         show this help
          -v, --version      show engine version
        """.trimIndent()
    )
}

private fun collectWavs(dir: File): List<File> =
    (dir.listFiles() ?: emptyArray())
        .sortedBy { it.name }
        .flatMap { if (it.isDirectory) collectWavs(it) else listOf(it) }
        .filter { it.isFile && it.name.lowercase().endsWith(".wav") }

fun main(args: Array<String>) {
    var json = false
    var beats = false
    var allBeats = false
    var maps = false
    var quiet = false
    var dir: String? = null
    val files = ArrayList<String>()

    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "-h" || a == "--help" -> { usage(); return }
            a == "-v" || a == "--version" -> {
                println("bunbeat-bpm $ENGINE_VERSION (Kotlin port of android_app/lib/services/bpm_analyzer.dart)")
                println("sampleRate=${BpmAnalyzer.kSampleRate} activeAlgorithm=${BpmAnalyzer.kActiveAlgorithm}")
                return
            }
            a == "-j" || a == "--json" -> json = true
            a == "-b" || a == "--beats" -> beats = true
            a == "-a" || a == "--all-beats" -> { beats = true; allBeats = true }
            a == "-m" || a == "--maps" -> maps = true
            a == "-q" || a == "--quiet" -> quiet = true
            a == "-d" || a == "--dir" -> {
                i++
                if (i >= args.size) { System.err.println("missing value for $a"); exitProcess(2) }
                dir = args[i]
            }
            a.startsWith("-") && a.length > 1 -> { System.err.println("unknown option: $a"); usage(); exitProcess(2) }
            else -> files.add(a)
        }
        i++
    }

    dir?.let { d ->
        val f = File(d)
        if (!f.isDirectory) { System.err.println("not a directory: $d"); exitProcess(2) }
        collectWavs(f).forEach { files.add(it.absolutePath) }
    }

    if (files.isEmpty()) { usage(); exitProcess(2) }

    val t0 = System.currentTimeMillis()
    var jsonFirst = true
    if (json) println("[")
    for (p in files) {
        val f = File(p)
        // analyzeWavFile 自身已做 try/catch，失败时返回带 error 的结果。
        val r: BpmResult = BpmAnalyzer.analyzeWavFile(p)
        when {
            quiet -> println("$p\t${jnum(r.bpm)}")
            json -> {
                if (!jsonFirst) println(",")
                jsonFirst = false
                print("  " + dumpJson(p, r, beats))
            }
            else -> {
                println("== ${f.name}")
                if (r.error != null) {
                    println("   error: ${r.error}")
                } else {
                    println("   bpm=${jnum(r.bpm)}  confidence=${fmt4(r.confidence)}  offset=${fmt4(r.beatOffset)}s  phase=${fmt4(r.phaseReliability)}")
                    println("   duration=${fmt3(r.duration)}s  beats=${r.beatTimes?.size ?: 0}")
                    if (maps) {
                        r.beatMaps?.forEach { (k, v) ->
                            if (v == null || v.isEmpty()) {
                                println("   [$k] empty")
                            } else {
                                println("   [$k] n=${v.size} first=${fmt4(v.first())} last=${fmt4(v.last())}")
                            }
                        }
                    }
                    if (beats) {
                        val bt = r.beatTimes ?: emptyList()
                        val shown = if (allBeats) bt else bt.take(16)
                        println("   beatTimes: " + shown.joinToString(", ") { fmt4(it) } +
                            if (!allBeats && bt.size > shown.size) " ... (+${bt.size - shown.size})" else "")
                    }
                }
            }
        }
    }
    if (json) println("\n]")
    if (!quiet && !json) {
        val ms = System.currentTimeMillis() - t0
        System.err.println("analyzed ${files.size} file(s) in ${ms} ms")
    }
}

private fun fmt3(d: Double?): String = if (d == null || d.isNaN()) "-" else String.format("%.3f", d)
private fun fmt4(d: Double?): String = if (d == null || d.isNaN()) "-" else String.format("%.4f", d)
