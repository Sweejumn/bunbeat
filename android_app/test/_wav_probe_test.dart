// 临时 WAV 入口 parity 探针（不进 git）：走 App 真正使用的入口
// `BpmAnalyzer.analyzeWavFile`（= _decodeWavPcm16 + 过短判定 + analyzePcm），
// 覆盖立体声 / 24bit / 缺 data 块 / 过短 等边界容器。
//
//   PROBE_MANIFEST / PROBE_OUT 可覆盖默认路径。
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import '../lib/services/bpm_analyzer.dart';

const _defaultDir = r'C:\Users\123\Desktop\muzrun\research_tmp\parity';
final _manifest =
    Platform.environment['PROBE_MANIFEST'] ?? '$_defaultDir\\probe.json';
final _out =
    Platform.environment['PROBE_OUT'] ?? '$_defaultDir\\expected_dart_probe.json';

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

void main() {
  test('wav entry-point probe', () async {
    final man = jsonDecode(File(_manifest).readAsStringSync()) as Map;
    final paths = (man['paths'] as List).cast<String>();
    final result = <String, dynamic>{'sampleRate': kSampleRate, 'files': {}};
    for (final p in paths) {
      final name = p.split(RegExp(r'[\\/]')).last;
      dynamic per;
      try {
        final r = await BpmAnalyzer.analyzeWavFile(p);
        per = <String, dynamic>{'0_wavfile': dump(r)};
      } catch (err) {
        per = <String, dynamic>{
          '0_wavfile': {'exception': '$err'}
        };
      }
      (result['files'] as Map)[name] = per;
      print('PROBED\t$name\t${per['0_wavfile'] is Map ? (per['0_wavfile'] as Map)['bpm'] : '?'}');
    }
    File(_out).writeAsStringSync(const JsonEncoder.withIndent(' ').convert(result));
    print('WROTE $_out');
  },
      timeout: const Timeout(Duration(minutes: 20)),
      // 干净检出（没有本地音频/清单）时自动跳过，不影响 `flutter test`。
      skip: !File(_manifest).existsSync()
          ? 'probe manifest not found: $_manifest'
          : false);
}
