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
  });
}
