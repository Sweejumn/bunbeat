/// 设备端 BPM / 拍点分析（`lib/services/bpm_analyzer.dart` 的忠实 Kotlin 移植）。
///
/// 逐运算对应 Dart 原实现：浮点运算的顺序与结合方式、Dart `%` 的欧几里得语义、
/// `toStringAsFixed` 的十进制量化、整型除法 `~/`、记录 `(a, b)`、可空返回与
/// 异常转 `BpmResult(error=...)` 的路径全部保持一致。
package com.bunbeat.bpm

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val kHop: Int = 512
const val kWin: Int = 1024

/// mel 谱频带数（librosa 默认 128，设备端取 32 已足够，速度快一个量级）。
const val kMelBands: Int = 32

/// Dart `math.ln2` / `math.ln10` 的精确双精度字面量。
private const val LN2: Double = 0.6931471805599453
private const val LN10: Double = 2.302585092994046

class BpmResult(
    val bpm: Double? = null,
    val confidence: Double = 0.0,
    val duration: Double? = null,
    val error: String? = null,
    val beatOffset: Double? = null,
    val beatTimes: List<Double>? = null,
    /// 各节拍模式的时间轴（秒），键为 BeatMode 名称（grid/snap）。
    val beatMaps: Map<String, List<Double>>? = null,
    /// 0..1：两个独立相位信号是否一致（低 = 建议手动校准）。
    val phaseReliability: Double? = null,
)

// ---------- Dart 语义辅助函数 ----------

/** Dart 的 double `%`：欧几里得取模，正除数时结果非负。 */
private fun dmod(a: Double, b: Double): Double {
    val r = a % b
    if (r == 0.0) return 0.0
    if (r < 0.0 && b > 0.0) return r + b
    if (r > 0.0 && b < 0.0) return r + b
    return r
}

/** Dart 的 double `.round()`：就近取整，半数远离 0。 */
private fun dartRound(x: Double): Int {
    return if (x >= 0.0) floor(x + 0.5).toInt() else -floor(-x + 0.5).toInt()
}

/**
 * `double.parse(x.toStringAsFixed(f))`：把 [v] 按 [f] 位小数量化后重新解析回 double。
 *
 * Dart 的 `toStringAsFixed` 走 C 的 `%.*f`；这里用 BigDecimal 对 double 的**精确**
 * 十进制展开做定标（与 printf 的“对精确值定标”一致），半数情况取远离 0（正值）
 * ——即 ECMAScript/MSVC 的平局规则。
 */
private fun dartRoundFixed(v: Double, f: Int): Double {
    if (v.isNaN() || v.isInfinite()) return v
    val scaled = BigDecimal(v).movePointRight(f)
    val rounded = if (scaled.signum() < 0) {
        scaled.setScale(0, RoundingMode.HALF_DOWN)
    } else {
        scaled.setScale(0, RoundingMode.HALF_UP)
    }
    return rounded.movePointLeft(f).toDouble()
}

/// 帧率（sr / kHop，Dart 的 `/` 为 double 除法）。
private fun frameRateOf(sr: Int): Double = sr / kHop.toDouble()

object BpmAnalyzer {

    const val kSampleRate: Int = 22050

    /// 与 Dart `const frameSec = kHop / kSampleRate;` 相同（int/int → double 除法）。
    private val FRAME_SEC: Double = kHop / kSampleRate.toDouble()

    /// `(startFrame * kHop) / kSampleRate`，Dart 中 int*int 后为 double 除法。
    private fun frameToSeconds(frame: Int): Double = (frame * kHop).toDouble() / kSampleRate

    /// 当前版本采用的 BPM 引擎编号。5 = 稳健 BPM + Ellis 动态规划整曲拍点。
    const val kActiveAlgorithm: Int = 5

        /// 从（ffmpeg 解码得到的单声道 16-bit）WAV 文件读取 PCM 并分析。
        ///
        /// 与 Dart `analyzeWavFile` 等价；Dart 侧用 `compute()` 把它丢到后台
        /// isolate，Kotlin 侧保持同步，调用方自行放到工作线程即可。
        fun analyzeWavFile(wavPath: String): BpmResult {
            return try {
                val bytes = File(wavPath).readBytes()
                val samples = decodeWavPcm16(bytes)
                if (samples == null || samples.size < kSampleRate * 2) {
                    BpmResult(
                        bpm = null,
                        confidence = 0.0,
                        error = "音频过短或格式无法解析，无法可靠检测 BPM",
                    )
                } else {
                    analyzePcm(samples, kSampleRate)
                }
            } catch (e: Throwable) {
                BpmResult(bpm = null, confidence = 0.0, error = "解析音频失败: $e")
            }
        }

        /// 对外统一入口：按当前版本选中的算法分析。
        fun analyzePcm(samples: DoubleArray, sampleRate: Int): BpmResult {
            return when (kActiveAlgorithm) {
                1 -> analyzeLibrosaPcm(samples, sampleRate)
                2 -> analyzeTempogramPcm(samples, sampleRate)
                3 -> analyzePeakClusterPcm(samples, sampleRate)
                4 -> analyzeBassKickPcm(samples, sampleRate)
                5 -> analyzeDpBeatsPcm(samples, sampleRate)
                else -> analyzeCombFoldPcm(samples, sampleRate)
            }
        }

        // ================= 算法 1：librosa 蓝本（mel 谱通量 + Ellis DP） =================

        /// 分析一段单声道 PCM（float -1..1 或 int16 范围内的样本）。
        fun analyzeLibrosaPcm(samples: DoubleArray, sampleRate: Int): BpmResult {
            if (samples.size < sampleRate * 2) {
                return BpmResult(bpm = null, confidence = 0.0, error = "音频过短，无法可靠检测 BPM")
            }
            // 仅分析前 60 秒（加快速度）；但拍子网格会按整首歌时长铺满，见下面。
            val maxLen = sampleRate * 60
            val data = if (samples.size > maxLen) samples.copyOfRange(0, maxLen) else samples
            // 整首歌的时长（秒）
            val fullDuration = samples.size / sampleRate.toDouble()

            try {
                // 1) mel 对数谱起音强度包络
                val onset = melOnsetStrength(data, sampleRate)
                if (onset.size < 8) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "音频有效起音过少，无法可靠检测 BPM")
                }
                if (!anyPositive(onset)) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "未检测到有效起音")
                }

                // 2) 自相关 + 对数正态先验 → 扫描 40–320 BPM
                val ac = autocorrelate(onset)
                val coarse = tempoFromAC(ac, sampleRate)
                if (coarse <= 0) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法可靠检测 BPM")
                }

                // 3) 抛物线细化 + 4) 八度/脉冲修正
                val refined = refineTempo(ac, coarse)
                val bpm = octaveCorrect(ac, refined).first
                if (bpm <= 0) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法可靠检测 BPM")
                }

                // 5) Ellis 动态规划拍点跟踪
                val beats = beatTrackDP(onset, bpm, sampleRate)
                if (beats.isEmpty()) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法跟踪整曲拍点")
                }

                // 6) 下拍对齐
                val startFrame = downbeatAlign(onset, beats)

                // 7) grid + snap
                val beatMaps = buildBeatMaps(onset, bpm, startFrame, fullDuration)
                val grid = beatMaps["grid"] ?: emptyList()
                val confidence = confidenceOf(onset, bpm, grid)
                val reliability = phaseReliability(onset, bpm, if (grid.isNotEmpty()) grid[0] else 0.0)

                return BpmResult(
                    bpm = bpm,
                    confidence = confidence,
                    duration = fullDuration,
                    beatOffset = if (grid.isNotEmpty()) grid[0] else null,
                    beatTimes = grid,
                    beatMaps = beatMaps,
                    phaseReliability = reliability,
                )
            } catch (e: Throwable) {
                return BpmResult(bpm = null, confidence = 0.0, error = "节拍检测失败: $e")
            }
        }

        // ---------- 起音强度包络（mel 对数谱谱通量） ----------

        /// 赫兹→mel（librosa 公式，ln 形式：2595*log10 = 1127.01048*ln）。
        private fun hzToMel(hz: Double): Double = 1127.01048 * ln(1.0 + hz / 700.0)

        /// mel→赫兹。
        private fun melToHz(mel: Double): Double = 700.0 * (exp(mel / 1127.01048) - 1.0)

        /// 生成 mel 滤波器组权重（nMel x nBins，nBins = nFft/2）。
        private fun melFilterbank(nMel: Int, sr: Int, nFft: Int, fmin: Double = 20.0, fmax: Double? = null): Array<DoubleArray> {
            val fTop = fmax ?: sr / 2.0
            val nBins = nFft / 2
            val melMin = hzToMel(fmin)
            val melMax = hzToMel(fTop)
            val hzPts = DoubleArray(nMel + 2) { i ->
                melToHz(melMin + (melMax - melMin) * i / (nMel + 1))
            }
            val filters = Array(nMel) { DoubleArray(nBins) }
            for (m in 0 until nMel) {
                val hL = hzPts[m]
                val hC = hzPts[m + 1]
                val hR = hzPts[m + 2]
                val wm = filters[m]
                for (b in 0 until nBins) {
                    val f = (b * sr).toDouble() / nFft
                    if (f >= hL && f < hC) {
                        wm[b] = (f - hL) / (hC - hL)
                    } else if (f >= hC && f <= hR) {
                        wm[b] = (hR - f) / (hR - hC)
                    }
                }
            }
            return filters
        }

        /// 中位数。
        private fun median(xs: DoubleArray): Double {
            if (xs.isEmpty()) return 0.0
            val s = xs.copyOf()
            s.sort()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }

        /// 标准差（ddof=1）。
        private fun std(x: DoubleArray): Double {
            if (x.size < 2) return 0.0
            var mean = 0.0
            for (v in x) {
                mean += v
            }
            mean /= x.size
            var sq = 0.0
            for (v in x) {
                val d = v - mean
                sq += d * d
            }
            return sqrt(sq / (x.size - 1))
        }

        private fun anyPositive(xs: DoubleArray): Boolean {
            for (v in xs) {
                if (v > 0) return true
            }
            return false
        }

        /// mel 对数谱谱通量起音强度（逐运算对应 Dart `_melOnsetStrength`）。
        private fun melOnsetStrength(data: DoubleArray, sr: Int): DoubleArray {
            val nFrames = (data.size - kWin) / kHop
            if (nFrames < 4) return DoubleArray(0)
            val filters = melFilterbank(kMelBands, sr, kWin)
            val window = DoubleArray(kWin) { i ->
                0.5 * (1 - cos(2 * PI * i / (kWin - 1)))
            }
            val half = kWin / 2
            val frame = DoubleArray(kWin)
            val prev = DoubleArray(kMelBands)
            val diffs = DoubleArray(kMelBands)
            val onset = DoubleArray(nFrames)
            val re = DoubleArray(kWin)
            val im = DoubleArray(kWin)
            for (f in 0 until nFrames) {
                val start = f * kHop
                for (i in 0 until kWin) {
                    frame[i] = data[start + i] * window[i]
                }
                for (i in 0 until kWin) {
                    re[i] = frame[i]
                    im[i] = 0.0
                }
                fftInPlace(re, im)
                for (m in 0 until kMelBands) {
                    var e = 0.0
                    val wm = filters[m]
                    // mel 权重矩阵很多元素为 0，跳过以提速
                    for (b in 0 until half) {
                        if (wm[b] != 0.0) {
                            val mag = sqrt(re[b] * re[b] + im[b] * im[b])
                            e += mag * mag * wm[b]
                        }
                    }
                    val db = 10.0 * (ln(e + 1e-10) / LN10)
                    var d = db - prev[m]
                    prev[m] = db
                    if (d < 0) d = 0.0
                    diffs[m] = d
                }
                onset[f] = median(diffs)
            }
            return onset
        }

        // ---------- BPM 估计（librosa tempo + 自相关） ----------

        private fun autocorrelate(x: DoubleArray): DoubleArray {
            val n = x.size
            var sum = 0.0
            for (v in x) {
                sum += v
            }
            val mean = sum / n
            val centered = DoubleArray(n) { x[it] - mean }
            // 用 FFT 实现自相关：IFFT(|FFT(x)|^2)
            val len = nextPow2(n * 2)
            val re = DoubleArray(len)
            val im = DoubleArray(len)
            for (i in 0 until n) {
                re[i] = centered[i]
            }
            fftInPlace(re, im)
            for (i in 0 until len) {
                val m = sqrt(re[i] * re[i] + im[i] * im[i])
                re[i] = m * m
                im[i] = 0.0
            }
            ifftInPlace(re, im)
            val out = DoubleArray(n)
            for (i in 0 until n) {
                out[i] = re[i]
            }
            return out
        }

        /// 在 lag 域均匀扫描自相关，用对数正态先验加权。返回粗估 BPM。
        private fun tempoFromAC(ac: DoubleArray, sr: Int): Double {
            val frameRate = frameRateOf(sr)
            val startBpm = 120.0
            val stdBpm = 1.0
            val minBpm = 40.0
            val maxBpm = 320.0
            var bestBpm = -1.0
            var bestScore = -1e300
            for (lag in 1 until ac.size) {
                val bpm = 60.0 * frameRate / lag
                if (bpm < minBpm || bpm > maxBpm) continue
                // 对数正态先验：中心 120 BPM，std_bpm=1.0（log2 域）
                val t = ln(bpm / startBpm) / LN2 / stdBpm
                val logPrior = -0.5 * (t * t)
                val v = max(0.0, ac[lag])
                val score = ln(1.0 + 1e6 * v) + logPrior
                if (score > bestScore) {
                    bestScore = score
                    bestBpm = bpm
                }
            }
            return bestBpm
        }

        private fun strengthAtLag(ac: DoubleArray, bpm: Double): Double {
            if (bpm <= 0) return 0.0
            // 将该 BPM 换算到 22050Hz/512hop 帧网格的滞后量
            val lag = (60.0 / bpm) * kSampleRate / kHop
            if (lag < 2 || lag >= ac.size - 2) return 0.0
            val lo = max(1, floor(lag * 0.9).toInt())
            val hi = min(ac.size - 1, ceil(lag * 1.1).toInt())
            if (hi <= lo) return 0.0
            var best = -1e18
            var i = lo
            while (i <= hi && i < ac.size) {
                if (ac[i] > best) best = ac[i]
                i++
            }
            return best
        }

        private fun refineTempo(ac: DoubleArray, baseBpm: Double): Double {
            val lag0 = (60.0 / baseBpm) * kSampleRate / kHop
            val lo = max(1, floor(lag0 * 0.85).toInt())
            val hi = min(ac.size - 1, ceil(lag0 * 1.15).toInt())
            if (hi <= lo) return baseBpm
            var bestIdx = lo
            var bestVal = -1e18
            for (i in lo..hi) {
                if (ac[i] > bestVal) {
                    bestVal = ac[i]
                    bestIdx = i
                }
            }
            // 抛物线插值
            var idx = bestIdx.toDouble()
            if (bestIdx > 0 && bestIdx < ac.size - 1) {
                val y0 = ac[bestIdx - 1]
                val y1 = ac[bestIdx]
                val y2 = ac[bestIdx + 1]
                val denom = y0 - 2 * y1 + y2
                if (abs(denom) > 1e-12) {
                    val delta = 0.5 * (y0 - y2) / denom
                    idx += delta.coerceIn(-1.0, 1.0)
                }
            }
            return 60.0 / (max(idx, 1e-6) * kHop / kSampleRate)
        }

        private fun octaveCorrect(ac: DoubleArray, tempo: Double): Pair<Double, Boolean> {
            var best = tempo
            var corrected = false
            val sCur = strengthAtLag(ac, tempo)
            val sDouble = strengthAtLag(ac, tempo * 2.0)
            val s15 = strengthAtLag(ac, tempo * 1.5)
            val sHalf = strengthAtLag(ac, tempo / 2.0)
            val s23 = strengthAtLag(ac, tempo / 1.5)

            if (tempo < 100 && 100 <= tempo * 2.0 && tempo * 2.0 <= 210 && sDouble > sCur * 0.8) {
                best = tempo * 2.0
                corrected = true
            } else if (tempo * 1.5 >= 100 && tempo * 1.5 <= 210 && s15 > sCur * 0.8 && tempo * 1.5 > tempo) {
                best = tempo * 1.5
                corrected = true
            } else if (s23 > sCur * 1.3 && tempo / 1.5 >= 50) {
                best = tempo / 1.5
                corrected = true
            } else if (sHalf > sCur * 1.3 && tempo / 2.0 >= 50) {
                best = tempo / 2.0
                corrected = true
            }

            while (best < 60 && best * 2 <= 300) {
                best *= 2.0
                corrected = true
            }
            while (best > 200) {
                best /= 2.0
                corrected = true
            }
            // 仅去除浮点尾部噪声到 6 位。
            return Pair(dartRoundFixed(best, 6), corrected)
        }

        // ---------- 拍点跟踪（Ellis 2007 动态规划） ----------

        private fun beatLocalScore(onset: DoubleArray, period: Int): DoubleArray {
            val n = onset.size
            val norm = std(onset)
            if (norm <= 0) return DoubleArray(0)
            val winLen = 2 * period + 1
            val w = DoubleArray(winLen)
            for (k in -period..period) {
                val x = k * 32.0 / period
                w[k + period] = exp(-0.5 * x * x)
            }
            val out = DoubleArray(n)
            for (i in 0 until n) {
                var s = 0.0
                for (j in 0 until winLen) {
                    val idx = i - period + j
                    if (idx >= 0 && idx < n) {
                        s += (onset[idx] / norm) * w[j]
                    }
                }
                out[i] = s
            }
            return out
        }

        /// 核心动态规划（librosa `__beat_track_dp`）。
        private fun beatTrackDp(localscore: DoubleArray, period: Int, tightness: Double): Pair<IntArray, DoubleArray> {
            val n = localscore.size
            val backlink = IntArray(n) { -1 }
            val cum = DoubleArray(n)
            val minWin = max(1, dartRound(period / 2.0)) // -round(p/2)
            val maxWin = 2 * period // -2p
            var maxLs = 0.0
            for (v in localscore) {
                if (v > maxLs) maxLs = v
            }
            if (maxLs <= 0) return Pair(backlink, cum)
            var firstBeat = true
            for (i in 0 until n) {
                var best = Double.NEGATIVE_INFINITY
                var bestW = -minWin - 1 // 哨兵：表示未找到有效前驱
                for (w in -maxWin..-minWin) {
                    val j = i + w
                    val x = -w.toDouble() / period
                    if (x <= 0) continue
                    val lx = ln(x)
                    val tw = -tightness * lx * lx
                    // 有有效前驱（j>=0）才加其累计分；否则该候选只有转移罚项
                    val cand = if (j >= 0) tw + cum[j] else tw
                    if (cand > best) {
                        best = cand
                        bestW = w
                    }
                }
                cum[i] = localscore[i] + (if (best.isFinite()) best else 0.0)
                if (firstBeat && localscore[i] < 0.01 * maxLs) {
                    backlink[i] = -1
                } else {
                    backlink[i] = i + bestW
                    firstBeat = false
                }
            }
            return Pair(backlink, cum)
        }

        /// 计算局部最大点处累积得分的（下侧）中位数之上的最后一个拍。
        private fun lastBeat(cum: DoubleArray): Int {
            val n = cum.size
            val maxes = ArrayList<Int>()
            for (i in 1 until n - 1) {
                if (cum[i] > cum[i - 1] && cum[i] > cum[i + 1]) maxes.add(i)
            }
            if (maxes.isEmpty()) {
                var mi = 0
                for (i in 1 until n) {
                    if (cum[i] > cum[mi]) mi = i
                }
                return mi
            }
            val vals = DoubleArray(maxes.size) { cum[maxes[it]] }
            vals.sort()
            val med = vals[vals.size / 2]
            var best = -1
            for (i in maxes) {
                if (cum[i] * 2 > med) best = i // 取最后一个满足条件的局部最大
            }
            if (best < 0) best = maxes[maxes.size - 1]
            return best
        }

        /// 丢弃首尾起音较弱的拍（hann5 平滑 + RMS 阈值）。
        private fun trimBeats(localscore: DoubleArray, beats: List<Int>): List<Int> {
            if (beats.size < 4) return beats
            val hann5 = doubleArrayOf(0.0, 0.5, 1.0, 0.5, 0.0)
            val m = beats.size
            val smooth = DoubleArray(m)
            for (i in 0 until m) {
                var s = 0.0
                var wsum = 0.0
                for (k in 0 until 5) {
                    val bi = i + (k - 2)
                    if (bi >= 0 && bi < m) {
                        s += localscore[beats[bi]] * hann5[k]
                        wsum += hann5[k]
                    }
                }
                smooth[i] = if (wsum > 0) s / wsum else 0.0
            }
            var sq = 0.0
            for (v in smooth) {
                sq += v * v
            }
            val thr = 0.5 * sqrt(sq / m)
            var lo = -1
            var hi = -1
            for (i in 0 until m) {
                if (smooth[i] > thr) {
                    if (lo < 0) lo = i
                    hi = i
                }
            }
            if (lo < 0 || hi < 0 || lo >= hi) return beats
            return beats.subList(lo, hi + 1).toList()
        }

        /// 完整 Ellis 拍点跟踪，返回拍点所在帧索引（升序）。
        private fun beatTrackDP(onset: DoubleArray, bpm: Double, sr: Int, tightness: Double = 100.0): List<Int> {
            val n = onset.size
            if (n < 4 || bpm <= 0) return emptyList()
            val frameRate = frameRateOf(sr)
            var period = dartRound(60.0 * frameRate / bpm)
            if (period < 4) period = 4
            if (period > n / 2) period = n / 2
            if (period < 4) return emptyList()

            val localscore = beatLocalScore(onset, period)
            if (localscore.size != n) return emptyList()

            val (backlink, cum) = beatTrackDp(localscore, period, tightness)

            val last = lastBeat(cum)
            if (last < 0) return emptyList()

            val rev = ArrayList<Int>()
            rev.add(last)
            while (backlink[rev[rev.size - 1]] >= 0) {
                val p = backlink[rev[rev.size - 1]]
                if (p >= rev[rev.size - 1]) break // 防御环路
                rev.add(p)
            }
            val beats = rev.reversed().toList()
            return trimBeats(localscore, beats)
        }

        /// 下拍（重拍）对齐：按小节位置（模 4）折叠各拍处的起音能量。
        private fun downbeatAlign(onset: DoubleArray, beats: List<Int>): Int {
            if (beats.size < 4) return beats[0]
            val w = 4
            val sums = DoubleArray(w)
            val cnts = IntArray(w)
            for (i in beats.indices) {
                val f = beats[i]
                var best = -1e300
                for (d in -1..1) {
                    val idx = f + d
                    if (idx >= 0 && idx < onset.size && onset[idx] > best) best = onset[idx]
                }
                val e = if (best < 0) 0.0 else best
                sums[i % w] += e
                cnts[i % w]++
            }
            var bestMode = 0
            var bestScore = -1.0
            for (m in 0 until w) {
                if (cnts[m] == 0) continue
                val s = sums[m] / cnts[m]
                if (s > bestScore) {
                    bestScore = s
                    bestMode = m
                }
            }
            if (bestMode == 0) return beats[0]
            for (i in beats.indices) {
                if (i % w == bestMode) return beats[i]
            }
            return beats[0]
        }

        // ---------- 拍点时间轴（grid / snap） ----------

        /// 以 [startFrame] 为相位，等距铺满到 [total]。
        private fun buildGrid(startFrame: Int, bpm: Double, total: Double): MutableList<Double> {
            if (bpm <= 0) return ArrayList()
            val period = 60.0 / bpm
            val start = frameToSeconds(startFrame)
            val times = ArrayList<Double>()
            var t = start
            while (t < total) {
                times.add(dartRoundFixed(t, 3))
                t += period
            }
            return times
        }

        /// 生成节拍模式的时间轴：grid（等距）/ snap（±12% 吸附）。
        private fun buildBeatMaps(onset: DoubleArray, bpm: Double, startFrame: Int, total: Double): Map<String, List<Double>> {
            if (bpm <= 0) return LinkedHashMap()
            val period = 60.0 / bpm
            val grid = buildGrid(startFrame, bpm, total)
            if (grid.isEmpty()) {
                val only = LinkedHashMap<String, List<Double>>()
                only["grid"] = grid
                return only
            }
            val snap = followOnsets(onset, grid, period, 0.12)
            val out = LinkedHashMap<String, List<Double>>()
            out["grid"] = grid
            out["snap"] = snap
            return out
        }

        /// 把 grid 里每个拍的时间，在 ±fraction*period 窗口内吸附到最近的局部起音峰。
        private fun followOnsets(onset: DoubleArray, grid: List<Double>, period: Double, fraction: Double): MutableList<Double> {
            val win = period * fraction // 秒
            val result = ArrayList<Double>()
            for (t in grid) {
                val lo = (t - win).coerceIn(0.0, Double.POSITIVE_INFINITY)
                val hi = t + win
                val loIdx = max(0, floor(lo / FRAME_SEC).toInt())
                val hiIdx = min(onset.size - 1, ceil(hi / FRAME_SEC).toInt())
                var bestIdx = -1
                var bestE = -1e18
                for (i in loIdx..hiIdx) {
                    val isPeak = i > 0 && i < onset.size - 1 &&
                        onset[i] >= onset[i - 1] && onset[i] > onset[i + 1]
                    if (isPeak && onset[i] > bestE) {
                        bestE = onset[i]
                        bestIdx = i
                    }
                }
                if (bestIdx >= 0) {
                    result.add(dartRoundFixed(bestIdx * FRAME_SEC, 3))
                } else {
                    result.add(t)
                }
            }
            return result
        }

        // ---------- 置信度 / 相位可靠性 ----------

        private fun circDist(a: Int, b: Int, n: Int): Int {
            val d = abs(a - b) % n
            return min(d, n - d)
        }

        private fun confidenceOf(onset: DoubleArray, bpm: Double, grid: List<Double>): Double {
            if (onset.size < 4 || grid.size < 2) return 0.0
            val period = 60.0 / bpm
            if (period <= 0) return 0.0

            // 1) 脉冲清晰度：按周期折叠，量「主峰相窗」内的能量占比（扣除均匀基线）。
            val bins = 48
            val hist = DoubleArray(bins)
            for (i in onset.indices) {
                val e = onset[i]
                if (e <= 0) continue
                val t = i * FRAME_SEC
                val ph = dmod(t, period)
                var b = floor((ph / period) * bins).toInt()
                if (b < 0) b = 0
                if (b >= bins) b = bins - 1
                hist[b] += e
            }
            var peakBin = 0
            for (b in 1 until bins) {
                if (hist[b] > hist[peakBin]) peakBin = b
            }
            val win = dartRound(bins * 0.10).coerceIn(1, bins / 4)
            var peakE = 0.0
            var totE = 0.0
            for (b in 0 until bins) {
                if (circDist(b, peakBin, bins) <= win) peakE += hist[b]
                totE += hist[b]
            }
            // 均匀情况下该窗的期望占比
            val base = (2 * win + 1) / bins.toDouble()
            val clarity = if (totE > 0 && base < 1.0) {
                max(0.0, ((peakE / totE) - base) / (1.0 - base))
            } else {
                0.0
            }

            // 2) 周期强度：归一化自相关峰突出度
            var periodicStrength = 0.0
            val ac = autocorrelate(onset)
            val ac0 = if (ac.isNotEmpty()) ac[0] else 0.0
            if (ac0 > 0) {
                val lag = dartRound(period / FRAME_SEC)
                val frameRate = kSampleRate / kHop.toDouble()
                val norms = ArrayList<Double>()
                var peakIndex: Int? = null
                for (l in 1 until ac.size) {
                    val b = 60.0 * frameRate / l
                    if (b < 40 || b > 320) continue
                    val v = max(0.0, ac[l] / ac0)
                    norms.add(v)
                    if (l == lag) peakIndex = l
                }
                if (norms.isNotEmpty()) {
                    norms.sort()
                    val med = norms[norms.size / 2]
                    val peak = if (peakIndex != null) max(0.0, ac[peakIndex] / ac0) else 0.0
                    periodicStrength = if (med >= 1.0) {
                        0.0
                    } else {
                        ((peak - med) / (1.0 - med + 1e-9)).coerceIn(0.0, 1.0)
                    }
                }
            }

            // 3) 落拍命中率
            val window = period * 0.12
            val peaks = ArrayList<Int>()
            for (i in 1 until onset.size - 1) {
                if (onset[i] > onset[i - 1] && onset[i] >= onset[i + 1]) peaks.add(i)
            }
            var hitRate = 0.0
            if (peaks.size >= 4) {
                val energies = DoubleArray(peaks.size) { onset[peaks[it]] }
                energies.sort()
                val thr = energies[energies.size / 2] * 0.5
                var aligned = 0.0
                var weight = 0.0
                var gi = 0
                for (i in peaks) {
                    val e = onset[i]
                    if (e < thr) continue
                    val t = i * FRAME_SEC
                    while (gi + 1 < grid.size && grid[gi + 1] < t) {
                        gi++
                    }
                    val d = abs(t - grid[gi])
                    val dNext = if (gi + 1 < grid.size) abs(t - grid[gi + 1]) else Double.POSITIVE_INFINITY
                    val bestD = if (d < dNext) d else dNext
                    if (bestD <= window) aligned += e
                    weight += e
                }
                if (weight > 0) hitRate = (aligned / weight).coerceIn(0.0, 1.0)
            }

            // 加权合成：清晰度为主，周期强度次之，命中为辅
            val conf = 0.45 * clarity + 0.35 * periodicStrength + 0.20 * hitRate
            return conf.coerceIn(0.0, 1.0)
        }

        /// 相位可靠性（0..1）：折叠相位直方图。
        private fun phaseReliability(onset: DoubleArray, bpm: Double, phaseA: Double): Double {
            if (bpm <= 0 || onset.size < 8 || phaseA.isNaN()) return 0.5
            val period = 60.0 / bpm
            if (period <= 0) return 0.5
            val bins = 64
            val hist = DoubleArray(bins)
            var total = 0.0
            for (i in onset.indices) {
                val e = onset[i]
                if (e <= 0) continue
                val t = i * FRAME_SEC
                var ph = dmod(t - phaseA, period)
                if (ph < 0) ph += period
                var b = floor((ph / period) * bins).toInt()
                if (b < 0) b = 0
                if (b >= bins) b = bins - 1
                hist[b] += e
                total += e
            }
            if (total <= 0) return 0.0
            var peakBin = 0
            for (b in 1 until bins) {
                if (hist[b] > hist[peakBin]) peakBin = b
            }
            val alignFrac = circDist(peakBin, 0, bins) / bins.toDouble() // 0..0.5
            val align = (1.0 - alignFrac / 0.25).coerceIn(0.0, 1.0)
            val win = dartRound(bins * 0.12).coerceIn(1, bins / 4)
            val off = (peakBin + bins / 2) % bins
            var onE = 0.0
            var offE = 0.0
            for (b in 0 until bins) {
                if (circDist(b, peakBin, bins) <= win) {
                    onE += hist[b]
                } else if (circDist(b, off, bins) <= win) {
                    offE += hist[b]
                }
            }
            val clarity = if ((onE + offE) > 0) max(0.0, (onE - offE) / (onE + offE)) else 0.0
            return (0.6 * align + 0.4 * clarity).coerceIn(0.0, 1.0)
        }

        // ---------- 算法 2：FourierTempogram + PLP ----------

        /// 全频段谱通量起音强度。
        private fun fluxOnset(data: DoubleArray, sr: Int, hop: Int = kHop): DoubleArray {
            val nFrames = (data.size - kWin) / hop
            if (nFrames < 4) return DoubleArray(0)
            val window = DoubleArray(kWin) { i ->
                0.5 * (1 - cos(2 * PI * i / (kWin - 1)))
            }
            val half = kWin / 2
            val frame = DoubleArray(kWin)
            val prevMag = DoubleArray(half)
            val onset = DoubleArray(nFrames)
            val re = DoubleArray(kWin)
            val im = DoubleArray(kWin)
            for (f in 0 until nFrames) {
                val start = f * hop
                for (i in 0 until kWin) {
                    frame[i] = data[start + i] * window[i]
                }
                for (i in 0 until kWin) {
                    re[i] = frame[i]
                    im[i] = 0.0
                }
                fftInPlace(re, im)
                var flux = 0.0
                var prevSum = 0.0
                for (b in 0 until half) {
                    val mag = sqrt(re[b] * re[b] + im[b] * im[b])
                    val d = mag - prevMag[b]
                    prevMag[b] = mag
                    prevSum += mag
                    if (d > 0) flux += d
                }
                onset[f] = if (prevSum > 1e-9) flux / prevSum else 0.0
            }
            return onset
        }

        /// 频带受限谱通量起音强度。返回 (onset, lowFracMean)。
        private fun bandFluxOnset(data: DoubleArray, sr: Int, hop: Int = kHop, cutHz: Double = 160.0): Pair<DoubleArray, Double> {
            val nFrames = (data.size - kWin) / hop
            if (nFrames < 4) return Pair(DoubleArray(0), 0.0)
            val window = DoubleArray(kWin) { i ->
                0.5 * (1 - cos(2 * PI * i / (kWin - 1)))
            }
            val half = kWin / 2
            // 频率 < cutHz 的最高 bin 索引（bin 频率 = b * sr / kWin）
            val maxBin = max(1, min(half, ceil(cutHz * kWin / sr).toInt()))
            val frame = DoubleArray(kWin)
            val prevMag = DoubleArray(half)
            val onset = DoubleArray(nFrames)
            val re = DoubleArray(kWin)
            val im = DoubleArray(kWin)
            var fracSum = 0.0
            for (f in 0 until nFrames) {
                val start = f * hop
                for (i in 0 until kWin) {
                    frame[i] = data[start + i] * window[i]
                }
                for (i in 0 until kWin) {
                    re[i] = frame[i]
                    im[i] = 0.0
                }
                fftInPlace(re, im)
                var flux = 0.0
                var bandSum = 0.0
                var totalSum = 0.0
                for (b in 0 until half) {
                    val mag = sqrt(re[b] * re[b] + im[b] * im[b])
                    totalSum += mag
                    if (b < maxBin) {
                        val d = mag - prevMag[b]
                        prevMag[b] = mag
                        bandSum += mag
                        if (d > 0) flux += d
                    }
                }
                onset[f] = if (bandSum > 1e-9) flux / bandSum else 0.0
                fracSum += if (totalSum > 1e-9) bandSum / totalSum else 0.0
            }
            return Pair(onset, fracSum / nFrames)
        }

        private const val kTempoWin = 256
        private const val kTempoHop = 32
        private const val kTempoNfft = 512

        /// 对起音包络做滑动窗 FFT，返回时间平均后的 tempogram 幅度谱。
        private fun meanTempogram(onset: DoubleArray, sr: Int): DoubleArray {
            val n = onset.size
            val tg = DoubleArray(kTempoNfft / 2 + 1)
            if (n < kTempoWin) return tg
            // Hann 窗
            val hann = DoubleArray(kTempoWin) { i ->
                0.5 * (1 - cos(2 * PI * i / (kTempoWin - 1)))
            }
            val buf = DoubleArray(kTempoNfft)
            val block = DoubleArray(kTempoWin)
            val re = DoubleArray(kTempoNfft)
            val im = DoubleArray(kTempoNfft)
            var count = 0
            var start = 0
            while (start + kTempoWin <= n) {
                for (i in 0 until kTempoWin) {
                    block[i] = onset[start + i] * hann[i]
                }
                // 前 kTempoWin 放数据，其余为 0（零填充加密频域采样）
                System.arraycopy(block, 0, buf, 0, kTempoWin)
                for (i in kTempoWin until kTempoNfft) {
                    buf[i] = 0.0
                }
                for (i in 0 until kTempoNfft) {
                    re[i] = buf[i]
                    im[i] = 0.0
                }
                fftInPlace(re, im)
                for (l in tg.indices) {
                    tg[l] += sqrt(re[l] * re[l] + im[l] * im[l])
                }
                count++
                start += kTempoHop
            }
            if (count > 0) {
                for (l in tg.indices) {
                    tg[l] /= count
                }
            }
            return tg
        }

        /// 从平均 tempogram 选最佳拍频 bin。返回 (bpm, l)；无有效峰返回 null。
        private fun tempoFromTempogram(tg: DoubleArray, sr: Int): Pair<Double, Int>? {
            val frameRate = frameRateOf(sr)
            // 目标 40–300 BPM → bin 范围
            val bpmPerBin = 60.0 * frameRate / kTempoNfft // ~5.05
            val l0 = max(1, ceil(40.0 / bpmPerBin).toInt())
            val l1 = min(tg.size - 1, floor(300.0 / bpmPerBin).toInt())
            if (l1 < l0) return null
            // 轻平滑（3 点三角核）
            val smooth = DoubleArray(tg.size)
            for (l in l0..l1) {
                val v = tg[l] * 0.5 +
                    (if (l > l0) tg[l - 1] * 0.25 else 0.0) +
                    (if (l < l1) tg[l + 1] * 0.25 else 0.0)
                smooth[l] = v
            }
            var bestL = -1
            var best = -1.0
            for (l in l0..l1) {
                val bpm = l * bpmPerBin
                val q = ln(bpm / 120.0) / LN2
                val prior = exp(-0.5 * (q * q))
                val s = smooth[l] * prior
                if (s > best) {
                    best = s
                    bestL = l
                }
            }
            if (bestL <= 0) return null
            var idx = bestL.toDouble()
            if (bestL > 0 && bestL < tg.size - 1 && smooth[bestL - 1] + smooth[bestL + 1] > 0) {
                val y0 = smooth[bestL - 1]
                val y1 = smooth[bestL]
                val y2 = smooth[bestL + 1]
                val denom = y0 - 2 * y1 + y2
                if (abs(denom) > 1e-12) {
                    idx += (0.5 * (y0 - y2) / denom).coerceIn(-1.0, 1.0)
                }
            }
            val bpm = dartRoundFixed(idx * bpmPerBin, 6)
            return Pair(bpm, bestL)
        }

        /// 相位锁定验证：返回该速度下最优相位与 q*。
        private fun pulseQuality(onset: DoubleArray, bpm: Double, sr: Int): Pair<Int, Double> {
            val frameRate = frameRateOf(sr)
            val n = onset.size
            var period = dartRound(60.0 * frameRate / bpm)
            if (period < 4) return Pair(0, -1.0)
            if (period >= n) period = n - 1
            val midOff = (period + 1) / 2
            var bestS = 0
            var bestQ = -1.0
            for (s in 0 until period) {
                var on = 0.0
                var off = 0.0
                var t = s
                while (t < n) {
                    on += onset[t]
                    val mid = t + midOff
                    if (mid < n) off += onset[mid]
                    t += period
                }
                val denom = on + off
                val q = if (denom <= 1e-12) 0.0 else on * on / denom
                if (q > bestQ) {
                    bestQ = q
                    bestS = s
                }
            }
            return Pair(bestS, bestQ)
        }

        /// 以 [phase0] 为基准等距排布拍点，再 ±12% 周期内吸附到最近的局部起音峰。
        private fun snappedBeats(onset: DoubleArray, bpm: Double, sr: Int, phase0: Int = -1): List<Int> {
            val n = onset.size
            val frameRate = frameRateOf(sr)
            val period = dartRound(60.0 * frameRate / bpm)
            if (period < 4) return emptyList()
            var ph0 = phase0
            if (ph0 < 0 || ph0 >= n) {
                ph0 = period / 2
            }
            var s0 = ph0
            while (s0 - period >= 0) {
                s0 -= period
            }
            val raw = ArrayList<Int>()
            var t = s0
            while (t < n) {
                raw.add(t)
                t += period
            }
            val win = max(1, dartRound(period * 0.12))
            val beats = ArrayList<Int>()
            for (b in raw) {
                var lo = b - win
                var hi = b + win
                if (lo < 0) lo = 0
                if (hi >= n) hi = n - 1
                var bestIdx = b
                var bestV = Double.NEGATIVE_INFINITY
                for (i in lo..hi) {
                    if (onset[i] > bestV) {
                        bestV = onset[i]
                        bestIdx = i
                    }
                }
                beats.add(bestIdx)
            }
            val out = ArrayList<Int>()
            for (b in beats) {
                if (out.isEmpty() || b > out[out.size - 1]) out.add(b)
            }
            return out
        }

        /// FourierTempogram 完整管线入口。
        fun analyzeTempogramPcm(samples: DoubleArray, sampleRate: Int): BpmResult {
            if (samples.size < sampleRate * 2) {
                return BpmResult(bpm = null, confidence = 0.0, error = "音频过短，无法可靠检测 BPM")
            }
            val maxLen = sampleRate * 60
            val data = if (samples.size > maxLen) samples.copyOfRange(0, maxLen) else samples
            val fullDuration = samples.size / sampleRate.toDouble()
            try {
                // 1) 全频段谱通量起音
                val onset = fluxOnset(data, sampleRate)
                if (onset.size < 8) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "音频有效起音过少，无法可靠检测 BPM")
                }
                if (!anyPositive(onset)) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "未检测到有效起音")
                }

                // 2) tempogram 频域估 BPM
                val tg = meanTempogram(onset, sampleRate)
                val coarse = tempoFromTempogram(tg, sampleRate)
                if (coarse == null) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法可靠检测 BPM")
                }

                // 3) 八度/倍频消歧
                val ac = autocorrelate(onset)
                val refined = refineTempo(ac, coarse.first)
                val bpm = octaveCorrect(ac, refined).first
                if (bpm <= 0) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法可靠检测 BPM")
                }

                // 4) 相位（锁定 q 的最优相位）+ 局部峰吸附 → 拍点
                val phase = pulseQuality(onset, bpm, sampleRate).first
                val beats = snappedBeats(onset, bpm, sampleRate, phase)
                if (beats.isEmpty()) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法跟踪整曲拍点")
                }

                // 5) 下拍对齐 + 6) grid/snap 时间轴
                val startFrame = downbeatAlign(onset, beats)
                val beatMaps = buildBeatMaps(onset, bpm, startFrame, fullDuration)
                val grid = beatMaps["grid"] ?: emptyList()
                val confidence = confidenceOf(onset, bpm, grid)
                val reliability = phaseReliability(onset, bpm, if (grid.isNotEmpty()) grid[0] else 0.0)

                return BpmResult(
                    bpm = bpm,
                    confidence = confidence,
                    duration = fullDuration,
                    beatOffset = if (grid.isNotEmpty()) grid[0] else null,
                    beatTimes = grid,
                    beatMaps = beatMaps,
                    phaseReliability = reliability,
                )
            } catch (e: Throwable) {
                return BpmResult(bpm = null, confidence = 0.0, error = "节拍检测失败: $e")
            }
        }

        // ---------- 算法 3：自相关估拍 + 起音峰圆周直方图定相位 ----------

        /// 能量和最大化相位（回退用）。
        private fun energyPhase(onset: DoubleArray, period: Int): Int {
            val n = onset.size
            val w = max(1, period / 8)
            var bestS = 0
            var bestE = Double.NEGATIVE_INFINITY
            for (s in 0 until period) {
                var e = 0.0
                var t = s
                while (t < n) {
                    var lo = t - w
                    if (lo < 0) lo = 0
                    var hi = t + w
                    if (hi >= n) hi = n - 1
                    var m = Double.NEGATIVE_INFINITY
                    for (i in lo..hi) {
                        if (onset[i] > m) m = onset[i]
                    }
                    if (m.isFinite()) e += m
                    t += period
                }
                if (e > bestE) {
                    bestE = e
                    bestS = s
                }
            }
            return bestS
        }

        /// 起音峰圆周直方图定相位。峰过少时回退到能量和最大化。
        private fun peakClusterPhase(onset: DoubleArray, bpm: Double, sr: Int): Int {
            val frameRate = frameRateOf(sr)
            val n = onset.size
            var period = dartRound(60.0 * frameRate / bpm)
            if (period < 4) return 0
            if (period >= n) period = n - 1
            var sum = 0.0
            for (v in onset) {
                sum += v
            }
            val mean = sum / n
            val thr = mean * 0.6
            val hist = DoubleArray(period)
            var peakCount = 0
            for (i in 2 until n - 2) {
                if (onset[i] >= onset[i - 1] && onset[i] > onset[i + 1] && onset[i] > thr) {
                    hist[i % period] += onset[i]
                    peakCount++
                }
            }
            if (peakCount < 4) return energyPhase(onset, period)
            // 环形 3-点平滑（首尾相接）
            val hs = DoubleArray(period)
            for (b in 0 until period) {
                hs[b] = hist[b] * 0.5 +
                    hist[(b - 1 + period) % period] * 0.25 +
                    hist[(b + 1) % period] * 0.25
            }
            var best = 0
            for (b in 1 until period) {
                if (hs[b] > hs[best]) best = b
            }
            return best
        }

        /// 算法 3 完整管线入口。
        fun analyzePeakClusterPcm(samples: DoubleArray, sampleRate: Int): BpmResult {
            if (samples.size < sampleRate * 2) {
                return BpmResult(bpm = null, confidence = 0.0, error = "音频过短，无法可靠检测 BPM")
            }
            val maxLen = sampleRate * 60
            val data = if (samples.size > maxLen) samples.copyOfRange(0, maxLen) else samples
            val fullDuration = samples.size / sampleRate.toDouble()
            try {
                val onset = fluxOnset(data, sampleRate)
                if (onset.size < 8) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "音频有效起音过少，无法可靠检测 BPM")
                }
                if (!anyPositive(onset)) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "未检测到有效起音")
                }

                val ac = autocorrelate(onset)
                val coarse = tempoFromAC(ac, sampleRate)
                if (coarse <= 0) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法可靠检测 BPM")
                }

                val refined = refineTempo(ac, coarse)
                val bpm = octaveCorrect(ac, refined).first
                if (bpm <= 0) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法可靠检测 BPM")
                }

                val phase = peakClusterPhase(onset, bpm, sampleRate)
                val beats = snappedBeats(onset, bpm, sampleRate, phase)
                if (beats.isEmpty()) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法跟踪整曲拍点")
                }

                val startFrame = downbeatAlign(onset, beats)
                val beatMaps = buildBeatMaps(onset, bpm, startFrame, fullDuration)
                val grid = beatMaps["grid"] ?: emptyList()
                val confidence = confidenceOf(onset, bpm, grid)
                val reliability = phaseReliability(onset, bpm, if (grid.isNotEmpty()) grid[0] else 0.0)

                return BpmResult(
                    bpm = bpm,
                    confidence = confidence,
                    duration = fullDuration,
                    beatOffset = if (grid.isNotEmpty()) grid[0] else null,
                    beatTimes = grid,
                    beatMaps = beatMaps,
                    phaseReliability = reliability,
                )
            } catch (e: Throwable) {
                return BpmResult(bpm = null, confidence = 0.0, error = "节拍检测失败: $e")
            }
        }

        // ---------- 算法 4：自相关估拍 + 低频（底鼓）频带能量最大化定相位 ----------

        /// 算法 4 完整管线入口。
        fun analyzeBassKickPcm(samples: DoubleArray, sampleRate: Int): BpmResult {
            if (samples.size < sampleRate * 2) {
                return BpmResult(bpm = null, confidence = 0.0, error = "音频过短，无法可靠检测 BPM")
            }
            val maxLen = sampleRate * 60
            val data = if (samples.size > maxLen) samples.copyOfRange(0, maxLen) else samples
            val fullDuration = samples.size / sampleRate.toDouble()
            try {
                // 1) 全频段谱通量起音（估 BPM 用）
                val onset = fluxOnset(data, sampleRate)
                if (onset.size < 8) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "音频有效起音过少，无法可靠检测 BPM")
                }
                if (!anyPositive(onset)) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "未检测到有效起音")
                }

                // 2) 起音包络自相关 + 对数正态先验 → 粗估 BPM
                val ac = autocorrelate(onset)
                val coarse = tempoFromAC(ac, sampleRate)
                if (coarse <= 0) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法可靠检测 BPM")
                }

                // 3) 抛物线细化 + 自相关滞后强度八度消歧
                val refined = refineTempo(ac, coarse)
                val bpm = octaveCorrect(ac, refined).first
                if (bpm <= 0) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法可靠检测 BPM")
                }

                // 4) 低频（<160Hz 底鼓）频带起音 + 能量和最大化定相位 → 拍点
                val frameRate = frameRateOf(sampleRate)
                var period = dartRound(60.0 * frameRate / bpm)
                if (period < 4) period = 4
                val kickPair = bandFluxOnset(data, sampleRate, kHop, 160.0)
                val kick = kickPair.first
                val lowFrac = kickPair.second
                val phase = if (lowFrac > 0.08) {
                    energyPhase(kick, min(period, max(4, kick.size - 1)))
                } else {
                    peakClusterPhase(onset, bpm, sampleRate)
                }
                val beats = snappedBeats(onset, bpm, sampleRate, phase)
                if (beats.isEmpty()) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法跟踪整曲拍点")
                }

                // 5) 下拍对齐 + 6) grid/snap 时间轴
                val startFrame = downbeatAlign(onset, beats)
                val beatMaps = buildBeatMaps(onset, bpm, startFrame, fullDuration)
                val grid = beatMaps["grid"] ?: emptyList()
                val confidence = confidenceOf(onset, bpm, grid)
                val reliability = phaseReliability(onset, bpm, if (grid.isNotEmpty()) grid[0] else 0.0)

                return BpmResult(
                    bpm = bpm,
                    confidence = confidence,
                    duration = fullDuration,
                    beatOffset = if (grid.isNotEmpty()) grid[0] else null,
                    beatTimes = grid,
                    beatMaps = beatMaps,
                    phaseReliability = reliability,
                )
            } catch (e: Throwable) {
                return BpmResult(bpm = null, confidence = 0.0, error = "节拍检测失败: $e")
            }
        }

        // ---------- 算法 5：稳健 BPM + Ellis 动态规划拍点（DP 相位） ----------

        /// 算法 5 完整管线入口。
        fun analyzeDpBeatsPcm(samples: DoubleArray, sampleRate: Int): BpmResult {
            if (samples.size < sampleRate * 2) {
                return BpmResult(bpm = null, confidence = 0.0, error = "音频过短，无法可靠检测 BPM")
            }
            val maxLen = sampleRate * 60
            val data = if (samples.size > maxLen) samples.copyOfRange(0, maxLen) else samples
            val fullDuration = samples.size / sampleRate.toDouble()
            try {
                // 1) 全频段谱通量起音
                val onset = fluxOnset(data, sampleRate)
                if (onset.size < 8) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "音频有效起音过少，无法可靠检测 BPM")
                }
                if (!anyPositive(onset)) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "未检测到有效起音")
                }

                // 2) 自相关 + 对数正态先验 → 粗估 BPM
                val ac = autocorrelate(onset)
                val coarse = tempoFromAC(ac, sampleRate)
                if (coarse <= 0) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法可靠检测 BPM")
                }

                // 3) 抛物线细化 + 自相关滞后强度八度消歧
                val refined = refineTempo(ac, coarse)
                val bpm = octaveCorrect(ac, refined).first
                if (bpm <= 0) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法可靠检测 BPM")
                }

                // 4) Ellis 动态规划整曲拍点
                val beats = beatTrackDP(onset, bpm, sampleRate)
                if (beats.isEmpty()) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法跟踪整曲拍点")
                }

                // 5) 下拍对齐 + 6) grid/snap 时间轴
                val startFrame = downbeatAlign(onset, beats)
                val beatMaps = buildBeatMaps(onset, bpm, startFrame, fullDuration)
                val grid = beatMaps["grid"] ?: emptyList()
                val confidence = confidenceOf(onset, bpm, grid)
                val reliability = phaseReliability(onset, bpm, if (grid.isNotEmpty()) grid[0] else 0.0)

                return BpmResult(
                    bpm = bpm,
                    confidence = confidence,
                    duration = fullDuration,
                    beatOffset = if (grid.isNotEmpty()) grid[0] else null,
                    beatTimes = grid,
                    beatMaps = beatMaps,
                    phaseReliability = reliability,
                )
            } catch (e: Throwable) {
                return BpmResult(bpm = null, confidence = 0.0, error = "节拍检测失败: $e")
            }
        }

        // ---------- 算法 6：脉冲梳折叠（pulse-comb fold） ----------

        /// 把起音按 lag 折叠成单周期相位直方图，返回峰突出度。
        private fun combPhaseShare(onset: DoubleArray, lag: Int): Double {
            if (lag < 1 || onset.size < 4) return 0.0
            var total = 0.0
            for (v in onset) {
                total += v
            }
            if (total <= 1e-12) return 0.0
            val bin = DoubleArray(lag)
            for (i in onset.indices) {
                bin[i % lag] += onset[i]
            }
            val meanAvg = total / lag // 全域每相位平均能量
            if (meanAvg <= 1e-12) return 0.0
            var mx = bin[0]
            for (p in 1 until lag) {
                if (bin[p] > mx) mx = bin[p]
            }
            return (mx - meanAvg) / meanAvg
        }

        /// 抛物线细化：在 [lag-1, lag, lag+1] 三点共振强度上取顶点的亚帧 lag。
        private fun refineLag(res: Map<Int, Double>, lag: Int): Double {
            val y0 = res[lag - 1] ?: 0.0
            val y1 = res[lag] ?: 0.0
            val y2 = res[lag + 1] ?: 0.0
            val denom = y0 - 2 * y1 + y2
            if (abs(denom) < 1e-12) return lag.toDouble()
            val d = 0.5 * (y0 - y2) / denom
            return (lag + d).coerceIn((lag - 1).toDouble(), (lag + 1).toDouble())
        }

        /// 对数正态先验权重（中心 120 BPM，轻微）。
        private fun combPrior(bpm: Double): Double {
            val t = ln(bpm / 120.0) / LN2 / 1.6
            return exp(-0.5 * (t * t))
        }

        /// 40–320 BPM（lag 域）扫描相位窗占比，抛物线细化，再在 60–200 BPM 内择最优。
        private fun combFoldTempo(onset: DoubleArray, sr: Int): Pair<Double, Int> {
            val frameRate = frameRateOf(sr)
            val minBpm = 40.0
            val maxBpm = 320.0

            // 稀疏化：只保留局部起音峰
            val peak = DoubleArray(onset.size)
            for (i in 1 until onset.size - 1) {
                if (onset[i] >= onset[i - 1] && onset[i] > onset[i + 1]) {
                    peak[i] = onset[i]
                }
            }
            peak[0] = onset[0]
            peak[onset.size - 1] = onset[onset.size - 1]

            var bestShare = -1.0
            var bestLag = 1
            val resAt = LinkedHashMap<Int, Double>()
            for (lag in 1 until onset.size) {
                val bpm = 60.0 * frameRate / lag
                if (bpm < minBpm || bpm > maxBpm) continue
                val share = combPhaseShare(peak, lag)
                resAt[lag] = share
                val sc = share * combPrior(bpm)
                if (sc > bestShare) {
                    bestShare = sc
                    bestLag = lag
                }
            }
            if (bestShare <= 1e-9) return Pair(-1.0, 0)

            val refinedLag = refineLag(resAt, bestLag)
            var best = 60.0 * frameRate / refinedLag
            var bestScore = -1.0
            val cands = doubleArrayOf(
                best,
                best * 2.0,
                best / 2.0,
                best * 1.5,
                best / 1.5,
            )
            for (bpm in cands) {
                if (bpm < 60 || bpm > 200) continue
                val lag = max(1, dartRound(60.0 * frameRate / bpm))
                val s = combPhaseShare(peak, lag) * combPrior(bpm)
                if (s > bestScore) {
                    bestScore = s
                    best = bpm
                }
            }
            while (best < 60 && best * 2 <= 300) {
                best *= 2.0
            }
            while (best > 200) {
                best /= 2.0
            }
            best = dartRoundFixed(best, 6)
            val phase = combAnchorPhase(onset, best)
            return Pair(best, phase)
        }

        /// 相位：以「全曲最强局部起音峰」为锚点，±半周期内微调。
        private fun combAnchorPhase(onset: DoubleArray, bpm: Double): Int {
            if (onset.size < 4 || bpm <= 0) return 0
            val frameRate = kSampleRate / kHop.toDouble()
            val periodLag = max(1, dartRound(frameRate * 60.0 / bpm))
            if (periodLag < 2) return 0
            var anchor = 0
            var bestE = -1.0
            for (i in 1 until onset.size - 1) {
                if (onset[i] >= onset[i - 1] &&
                    onset[i] > onset[i + 1] &&
                    onset[i] > bestE
                ) {
                    bestE = onset[i]
                    anchor = i
                }
            }
            if (bestE <= 0) return 0
            val center = anchor % periodLag
            val halfWin = max(1, periodLag / 2)
            val winFrames = max(1, dartRound(0.12 * periodLag))
            var bestPhase = center
            var bestScore = -1.0
            for (d in -halfWin..halfWin) {
                val phase = ((center + d) % periodLag + periodLag) % periodLag
                var score = 0.0
                for (i in onset.indices) {
                    val hop = Math.floorMod(i - phase, periodLag)
                    if (min(hop, periodLag - hop) <= winFrames) {
                        score += onset[i]
                    }
                }
                if (score > bestScore) {
                    bestScore = score
                    bestPhase = phase
                }
            }
            return bestPhase
        }

        /// 算法 6 对外入口。
        fun analyzeCombFoldPcm(samples: DoubleArray, sampleRate: Int): BpmResult {
            if (samples.size < sampleRate * 2) {
                return BpmResult(bpm = null, confidence = 0.0, error = "音频过短，无法可靠检测 BPM")
            }
            val maxLen = sampleRate * 60
            val data = if (samples.size > maxLen) samples.copyOfRange(0, maxLen) else samples
            val fullDuration = samples.size / sampleRate.toDouble()
            try {
                val onset = fluxOnset(data, sampleRate)
                if (onset.size < 8) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "音频有效起音过少，无法可靠检测 BPM")
                }
                if (!anyPositive(onset)) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "未检测到有效起音")
                }

                val fold = combFoldTempo(onset, sampleRate)
                val bpm = fold.first
                val phase = fold.second
                if (bpm <= 0) {
                    return BpmResult(bpm = null, confidence = 0.0, error = "无法可靠检测 BPM")
                }

                val beatMaps = buildBeatMaps(onset, bpm, phase, fullDuration)
                val grid = beatMaps["grid"] ?: emptyList()
                val confidence = confidenceOf(onset, bpm, grid)
                val reliability = phaseReliability(onset, bpm, if (grid.isNotEmpty()) grid[0] else 0.0)

                return BpmResult(
                    bpm = bpm,
                    confidence = confidence,
                    duration = fullDuration,
                    beatOffset = if (grid.isNotEmpty()) grid[0] else null,
                    beatTimes = grid,
                    beatMaps = beatMaps,
                    phaseReliability = reliability,
                )
            } catch (e: Throwable) {
                return BpmResult(bpm = null, confidence = 0.0, error = "节拍检测失败: $e")
            }
        }

        // ---------- WAV 解码 ----------

        /// 仅支持 ffmpeg 生成的 PCM16 单声道 WAV。返回 float(-1..1) 样本。
        fun decodeWavPcm16(bytes: ByteArray): DoubleArray? {
            // 找 'data' 块
            var pos: Long = 12L
            while (pos + 8 <= bytes.size.toLong()) {
                val p = pos.toInt()
                val tag = String(bytes, p, 4, Charsets.ISO_8859_1)
                val size = (bytes[p + 4].toLong() and 0xFFL) or
                    ((bytes[p + 5].toLong() and 0xFFL) shl 8) or
                    ((bytes[p + 6].toLong() and 0xFFL) shl 16) or
                    ((bytes[p + 7].toLong() and 0xFFL) shl 24)
                if (tag == "data") {
                    val dataStart = pos + 8
                    val n = size
                    // 16-bit: 数据可能是奇数长度，但 PCM16 通常偶长
                    val sampleCount = (n / 2).toInt()
                    val out = DoubleArray(sampleCount)
                    for (i in 0 until sampleCount) {
                        val off = (dataStart + i * 2L).toInt()
                        val raw = ((bytes[off].toInt() and 0xFF) or (bytes[off + 1].toInt() shl 8)).toShort().toInt()
                        out[i] = raw / 32768.0
                    }
                    return out
                }
                pos += 8 + size + (if (size % 2 != 0L) 1L else 0L)
            }
            return null
        }
    }

