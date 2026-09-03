/// 小型纯 Dart 复数 FFT（radix-2 迭代实现），用于本地 BPM 分析。
///
/// 用法：把样本填进 [re]，[im] 填 0；调用 [fft] 后原地得到频谱。
library;

import 'dart:math' as math;

class Complex {
  double re;
  double im;
  Complex(this.re, this.im);

  double get magnitude => math.sqrt(re * re + im * im);
}

/// 返回大于等于 [n] 的 2 的幂。
int nextPow2(int n) {
  int k = 1;
  while (k < n) {
    k <<= 1;
  }
  return k;
}

/// 就地计算 [input] 的一维 FFT（长度自动补零到 2 的幂并返回新数组）。
List<Complex> fft(List<double> input) {
  final n = nextPow2(input.length);
  final a = List<Complex>.generate(n, (i) => i < input.length ? Complex(input[i], 0.0) : Complex(0.0, 0.0));
  _transform(a, invert: false);
  return a;
}

/// 就地计算 [input] 的逆 FFT（结果除以 n）。
void ifft(List<Complex> a) {
  _transform(a, invert: true);
  final n = a.length;
  for (var i = 0; i < n; i++) {
    a[i].re /= n;
    a[i].im /= n;
  }
}

void _transform(List<Complex> a, {required bool invert}) {
  final n = a.length;
  // 位反转排列
  for (int i = 1, j = 0; i < n; i++) {
    int bit = n >> 1;
    for (; j & bit != 0; bit >>= 1) {
      j ^= bit;
    }
    j ^= bit;
    if (i < j) {
      final tmp = a[i];
      a[i] = a[j];
      a[j] = tmp;
    }
  }
  // 蝶形运算
  for (int len = 2; len <= n; len <<= 1) {
    final ang = 2 * math.pi / len * (invert ? -1 : 1);
    final wlenRe = math.cos(ang);
    final wlenIm = math.sin(ang);
    for (int i = 0; i < n; i += len) {
      double wRe = 1, wIm = 0;
      for (int j = 0; j < len ~/ 2; j++) {
        final u = a[i + j];
        final v = a[i + j + len ~/ 2];
        final vRe = v.re * wRe - v.im * wIm;
        final vIm = v.re * wIm + v.im * wRe;
        a[i + j] = Complex(u.re + vRe, u.im + vIm);
        a[i + j + len ~/ 2] = Complex(u.re - vRe, u.im - vIm);
        final nwRe = wRe * wlenRe - wIm * wlenIm;
        final nwIm = wRe * wlenIm + wIm * wlenRe;
        wRe = nwRe;
        wIm = nwIm;
      }
    }
  }
}
