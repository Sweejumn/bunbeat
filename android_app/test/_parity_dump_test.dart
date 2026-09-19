// 临时 parity dump harness（不进 git）：对 manifest 里的 WAV 跑全部 6 个引擎，
// 把结果以全精度写入 expected_dart.json，供 Kotlin 移植版做逐位复刻校验。
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';

import '../lib/services/bpm_analyzer.dart';

// 路径可用环境变量覆盖（同一份 harness 复用于 dev 集与 holdout 集）：
//   PARITY_MANIFEST / PARITY_OUT
const _defaultDir = r'C:\Users\123\Desktop\muzrun\research_tmp\parity';
final _manifest = Platform.environment['PARITY_MANIFEST'] ??
    '$_defaultDir\\manifest.json';
final _out =
    Platform.environment['PARITY_OUT'] ?? '$_defaultDir\\expected_dart.json';

List<double> decodeWavMono(Uint8List bytes) {
  var i = 12;
  int? dataOff;
  int? dataLen;
  var bitDepth = 16;
  var channels = 1;
  while (i + 8 <= bytes.length) {
    final id = String.fromCharCodes(bytes.sublist(i, i + 4));
    final sz = bytes.buffer.asByteData().getUint32(i + 4, Endian.little);
    if (id == 'fmt ') {
      final bd = bytes.buffer.asByteData();
      channels = bd.getUint16(i + 10, Endian.little);
      bitDepth = bd.getUint16(i + 22, Endian.little);
    } else if (id == 'data') {
      dataOff = i + 8;
      dataLen = sz;
    }
    i += 8 + (sz + (sz.isOdd ? 1 : 0));
  }
  if (dataOff == null || dataLen == null) throw StateError('no data');
  final out = <double>[];
  final bd = bytes.buffer.asByteData(bytes.offsetInBytes + dataOff);
  final n = dataLen ~/ (bitDepth ~/ 8);
  for (var k = 0; k < n;) {
    if (channels > 1) {
      var s = 0.0;
      for (var c = 0; c < channels; c++) {
        s += bd.getInt16((k + c) * 2, Endian.little) / 32768.0;
      }
      out.add(s / channels);
      k += channels;
    } else if (bitDepth == 16) {
      out.add(bd.getInt16(k * 2, Endian.little) / 32768.0);
      k++;
    } else if (bitDepth == 24) {
      final b0 = bd.getUint8(k * 3), b1 = bd.getUint8(k * 3 + 1);
      final b2 = bd.getInt8(k * 3 + 2);
      out.add((b0 | (b1 << 8) | (b2 << 16)) / 8388608.0);
      k++;
    } else {
      out.add(bd.getInt32(k * 4, Endian.little) / 2147483648.0);
      k++;
    }
  }
  return out;
}

/// 数值指纹：完整列表的 长度/和/平方和/位置加权和/min/max/前4项。
/// `sum`/`sq` 是聚合量，`wsum = Σ x[i]*(i+1)` 让任何单个元素的 1 ulp 差异
/// 都必然体现在指纹里（除非精确抵消）。
Map<String, dynamic> fp(List<double>? xs) {
  if (xs == null) return {'n': -1};
  if (xs.isEmpty) return {'n': 0};
  var sum = 0.0, sq = 0.0, wsum = 0.0, mn = xs.first, mx = xs.first;
  for (var i = 0; i < xs.length; i++) {
    final v = xs[i];
    sum += v;
    sq += v * v;
    wsum += v * (i + 1);
    if (v < mn) mn = v;
    if (v > mx) mx = v;
  }
  return {
    'n': xs.length,
    'sum': sum,
    'sq': sq,
    'wsum': wsum,
    'min': mn,
    'max': mx,
    'head': xs.take(4).toList(),
  };
}

Map<String, dynamic> dump(BpmResult r) => {
      'bpm': r.bpm,
      'confidence': r.confidence,
      'duration': r.duration,
      'error': r.error,
      'beatOffset': r.beatOffset,
      'phaseReliability': r.phaseReliability,
      'beats': fp(r.beatTimes),
      'grid': fp(r.beatMaps?['grid']),
      'snap': fp(r.beatMaps?['snap']),
      'mapKeys': r.beatMaps?.keys.toList(),
    };

typedef Engine = BpmResult Function(List<double> s, {required int sampleRate});

void main() {
  test('parity dump all engines', () {
    final man = jsonDecode(File(_manifest).readAsStringSync()) as Map;
    final paths = (man['paths'] as List).cast<String>();
    final engines = <String, Engine>{
      '1_librosa': BpmAnalyzer.analyzeLibrosaPcm,
      '2_tempogram': BpmAnalyzer.analyzeTempogramPcm,
      '3_peakcluster': BpmAnalyzer.analyzePeakClusterPcm,
      '4_basskick': BpmAnalyzer.analyzeBassKickPcm,
      '5_dpbeats': BpmAnalyzer.analyzeDpBeatsPcm,
      '6_combfold': BpmAnalyzer.analyzeCombFoldPcm,
    };
    final result = <String, dynamic>{'sampleRate': kSampleRate, 'files': {}};
    for (final p in paths) {
      final name = p.split(RegExp(r'[\\/]')).last;
      final samples = decodeWavMono(File(p).readAsBytesSync());
      final per = <String, dynamic>{'samples': samples.length};
      for (final e in engines.entries) {
        try {
          per[e.key] = dump(e.value(samples, sampleRate: kSampleRate));
        } catch (err) {
          per[e.key] = {'exception': '$err'};
        }
      }
      (result['files'] as Map)[name] = per;
      print('DUMPED\t$name\t${samples.length}');
    }
    File(_out).writeAsStringSync(const JsonEncoder.withIndent(' ').convert(result));
    print('WROTE $_out');
  },
      timeout: const Timeout(Duration(minutes: 20)),
      // 干净检出（没有本地音频/清单）时自动跳过，不影响 `flutter test`。
      skip: !File(_manifest).existsSync()
          ? 'parity manifest not found: $_manifest'
          : false);
}
