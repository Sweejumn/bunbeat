/// 小型纯 Dart 复数 FFT（radix-2 迭代实现）的 Kotlin 移植版。
///
/// 与 `lib/services/fft.dart` 逐运算对应：位反转排列 + 迭代蝶形，
/// 每一步浮点运算的顺序、结合方式与 Dart 版完全一致。
package com.bunbeat.bpm

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 复数：与 Dart `Complex` 一致（re/im 可变，magnitude = sqrt(re*re + im*im)）。 */
class Complex(var re: Double, var im: Double) {
    val magnitude: Double
        get() = sqrt(re * re + im * im)
}

/** 返回大于等于 [n] 的 2 的幂。 */
fun nextPow2(n: Int): Int {
    var k = 1
    while (k < n) {
        k = k shl 1
    }
    return k
}

/**
 * 就地计算 [input] 的一维 FFT（长度自动补零到 2 的幂并返回新数组）。
 * 与 Dart 版同构：`nextPow2` 补零 → 新数组 → 正向变换。
 */
fun fft(input: DoubleArray): Array<Complex> {
    val n = nextPow2(input.size)
    val re = DoubleArray(n)
    val im = DoubleArray(n)
    for (i in input.indices) {
        re[i] = input[i]
    }
    transform(re, im, invert = false)
    return Array(n) { Complex(re[it], im[it]) }
}

fun fft(input: List<Double>): Array<Complex> = fft(input.toDoubleArray())

/** 就地计算 [a] 的逆 FFT（结果除以 n）。 */
fun ifft(a: Array<Complex>) {
    val n = a.size
    val re = DoubleArray(n)
    val im = DoubleArray(n)
    for (i in 0 until n) {
        re[i] = a[i].re
        im[i] = a[i].im
    }
    transform(re, im, invert = true)
    for (i in 0 until n) {
        a[i].re = re[i] / n
        a[i].im = im[i] / n
    }
}

// ---------- 数组形式内部接口（热路径用，避免每帧分配对象；运算序列与上面完全一致） ----------

internal fun fftInPlace(re: DoubleArray, im: DoubleArray) {
    transform(re, im, invert = false)
}

internal fun ifftInPlace(re: DoubleArray, im: DoubleArray) {
    transform(re, im, invert = true)
    val n = re.size
    for (i in 0 until n) {
        re[i] /= n
        im[i] /= n
    }
}

/**
 * 与 Dart `_transform` 逐行对应。
 *
 * 注意结合顺序：`2 * math.pi / len * (invert ? -1 : 1)` 在 Dart 中为
 * `((2*pi)/len) * (±1)`，此处保持一致。
 */
private fun transform(re: DoubleArray, im: DoubleArray, invert: Boolean) {
    val n = re.size
    // 位反转排列
    var j = 0
    var i = 1
    while (i < n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j xor bit
        if (i < j) {
            val tr = re[i]
            re[i] = re[j]
            re[j] = tr
            val ti = im[i]
            im[i] = im[j]
            im[j] = ti
        }
        i++
    }
    // 蝶形运算
    var len = 2
    while (len <= n) {
        val ang = 2 * PI / len * (if (invert) -1 else 1)
        val wlenRe = cos(ang)
        val wlenIm = sin(ang)
        val halfLen = len / 2
        var i2 = 0
        while (i2 < n) {
            var wRe = 1.0
            var wIm = 0.0
            for (jj in 0 until halfLen) {
                val uRe = re[i2 + jj]
                val uIm = im[i2 + jj]
                val vRe0 = re[i2 + jj + halfLen]
                val vIm0 = im[i2 + jj + halfLen]
                val vRe = vRe0 * wRe - vIm0 * wIm
                val vIm = vRe0 * wIm + vIm0 * wRe
                re[i2 + jj] = uRe + vRe
                im[i2 + jj] = uIm + vIm
                re[i2 + jj + halfLen] = uRe - vRe
                im[i2 + jj + halfLen] = uIm - vIm
                val nwRe = wRe * wlenRe - wIm * wlenIm
                val nwIm = wRe * wlenIm + wIm * wlenRe
                wRe = nwRe
                wIm = nwIm
            }
            i2 += len
        }
        len = len shl 1
    }
}
