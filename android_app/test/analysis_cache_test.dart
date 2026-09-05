import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:run_bpm_android/services/analysis_cache.dart';

/// 构造一个注入固定基目录的 AnalysisCache（不触平台通道）。
AnalysisCache makeCache(Directory base) {
  // AnalysisCache 构造为私有 _({baseDir})；为可测性暴露一个工厂。
  return AnalysisCache.forTesting(baseDir: base);
}

void main() {
  late Directory base;
  late Directory srcDir;
  late File src;

  setUp(() async {
    base = await Directory.systemTemp.createTemp('runbpm_cache_test_');
    srcDir = await Directory.systemTemp.createTemp('runbpm_src_test_');
    src = File('${srcDir.path}/song.mp3');
    await src.writeAsBytes(List<int>.filled(1000, 7));
  });

  tearDown(() async {
    try {
      await base.delete(recursive: true);
    } catch (_) {}
    try {
      await srcDir.delete(recursive: true);
    } catch (_) {}
  });

  group('AnalysisCache', () {
    test('写后读回一致（含 manual/裸 beatTimes）', () async {
      final c = makeCache(base);
      await c.write(
        'abc123',
        src.path,
        bpm: 123.4,
        confidence: 0.87,
        duration: 210.5,
        beatOffset: 1.25,
        beatTimes: [0.5, 1.0, 1.5],
        manual: true,
        artworkFile: 'abc123_art.jpg',
      );
      final r = await c.read('abc123', src.path);
      expect(r, isNotNull);
      expect(r!.bpm, 123.4);
      expect(r.confidence, 0.87);
      expect(r.duration, 210.5);
      expect(r.beatOffset, 1.25);
      expect(r.beatTimes, [0.5, 1.0, 1.5]);
      expect(r.manual, isTrue);
      expect(r.artworkFile, 'abc123_art.jpg');
    });

    test('源文件未变 -> 命中缓存', () async {
      final c = makeCache(base);
      await c.write('id1', src.path, bpm: 100.0, manual: false);
      final r = await c.read('id1', src.path);
      expect(r, isNotNull);
    });

    test('源文件变大（更换歌曲）-> 缓存失效', () async {
      final c = makeCache(base);
      await c.write('id1', src.path, bpm: 100.0);
      // 修改源文件尺寸 = 文件被替换
      await src.writeAsBytes(List<int>.filled(5000, 7));
      final r = await c.read('id1', src.path);
      expect(r, isNull);
    });

    test('无缓存记录 -> 返回 null', () async {
      final c = makeCache(base);
      final r = await c.read('nope', src.path);
      expect(r, isNull);
    });

    test('persistArtwork 拷贝文件并可由 artworkPath 解析', () async {
      final c = makeCache(base);
      final tmp = File('${srcDir.path}/cover.jpg');
      await tmp.writeAsBytes(List<int>.filled(200, 1));
      final name = await c.persistArtwork('art1', tmp.path);
      expect(name, 'art1_art.jpg');
      final path = await c.artworkPath('art1', name);
      expect(path, isNotNull);
      expect(await File(path!).length(), 200);
    });

    test('artworkFile 为 null 时 artworkPath 返回 null', () async {
      final c = makeCache(base);
      expect(await c.artworkPath('x', null), isNull);
    });

    test('两次写同一 id 覆盖旧值（手动 BPM 更新）', () async {
      final c = makeCache(base);
      await c.write('id1', src.path, bpm: 90.0, manual: false);
      await c.write('id1', src.path, bpm: 150.0, manual: true);
      final r = await c.read('id1', src.path);
      expect(r!.bpm, 150.0);
      expect(r.manual, isTrue);
    });

    test('空源文件也能写读（size=0 不当作失效）', () async {
      final empty = File('${srcDir.path}/empty.mp3');
      await empty.writeAsBytes(const []);
      final c = makeCache(base);
      await c.write('e1', empty.path, bpm: 60.0);
      final r = await c.read('e1', empty.path);
      expect(r, isNotNull);
      expect(r!.bpm, 60.0);
    });

    test('算法升级：旧算法结果 stale=true 且不失效（秒开 + 后台重测用）', () async {
      final c = makeCache(base);
      // 旧算法（算法4）写入
      await c.write('s1', src.path, bpm: 121.0, algorithm: 4);
      // 当前算法为 5：旧结果保留但标记 stale
      final r = await c.read('s1', src.path, currentAlgorithm: 5);
      expect(r, isNotNull);
      expect(r!.bpm, 121.0);
      expect(r.algorithm, 4);
      expect(r.stale, isTrue);
    });

    test('算法一致：结果非 stale', () async {
      final c = makeCache(base);
      await c.write('s2', src.path, bpm: 150.0, algorithm: 5);
      final r = await c.read('s2', src.path, currentAlgorithm: 5);
      expect(r!.stale, isFalse);
      expect(r.algorithm, 5);
    });

    test('各算法 BPM 历史保留（byAlgorithm 合并不覆盖）', () async {
      final c = makeCache(base);
      await c.write('h1', src.path, bpm: 120.0, algorithm: 4);
      await c.write('h1', src.path, bpm: 125.0, algorithm: 5);
      final r = await c.read('h1', src.path, currentAlgorithm: 5);
      expect(r!.byAlgorithm['4'], 120.0);
      expect(r.byAlgorithm['5'], 125.0);
      expect(r.bpm, 125.0);
      expect(r.stale, isFalse);
    });

    test('旧格式缓存（无 algorithm 字段）视为旧算法结果 stale', () async {
      final c = makeCache(base);
      // 手工构造 v4 格式 JSON（无 algorithm）
      final dir = Directory('${base.path}/analysis');
      await dir.create(recursive: true);
      final stat = await src.stat();
      await File('${dir.path}/legacy.json').writeAsString(
          '{"schemaVersion":4,"bpm":99.0,"manual":false,'
          '"fileSize":${stat.size},"fileMtime":${stat.modified.millisecondsSinceEpoch}}');
      final r = await c.read('legacy', src.path, currentAlgorithm: 5);
      expect(r, isNotNull);
      expect(r!.bpm, 99.0);
      expect(r.stale, isTrue);
      expect(r.algorithm, isNull);
    });
  });
}
