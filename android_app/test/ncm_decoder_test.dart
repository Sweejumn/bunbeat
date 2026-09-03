import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:pointycastle/export.dart';
import 'package:run_bpm_android/services/ncm_decoder.dart';

// 独立按 NCM 规范「反向编码」一段假 NCM 数据，用于验证解码器的正确性。
// 编码逻辑逐条对应权威参考（nowa277/OpenConverter 的 ncm.js）：
final Uint8List _coreKey = Uint8List.fromList([
  0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F, 0x35, 0x6B, 0x49, 0x6E, //
  0x62, 0x61, 0x78, 0x57,
]);
final Uint8List _metaKey = Uint8List.fromList([
  0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21, 0x5C, 0x5D, 0x26, 0x30, //
  0x55, 0x3C, 0x27, 0x28,
]);

Uint8List _aesEcbEncrypt(Uint8List data, Uint8List key) {
  final cipher = ECBBlockCipher(AESEngine())
    ..init(true, KeyParameter(Uint8List.fromList(key)));
  final out = Uint8List(data.length);
  var src = 0, dst = 0;
  while (src < data.length) {
    final n = cipher.processBlock(data, src, out, dst);
    src += n;
    dst += n;
  }
  return Uint8List.sublistView(out, 0, dst);
}

Uint8List _pkcs7Pad(Uint8List data, int block) {
  final pad = block - (data.length % block);
  final out = Uint8List(data.length + pad);
  out.setRange(0, data.length, data);
  for (var i = 0; i < pad; i++) {
    out[data.length + i] = pad;
  }
  return out;
}

Uint8List _xor(Uint8List d, int byte) =>
    Uint8List.fromList(d.map((b) => b ^ byte).toList());

/// 独立实现 box 密钥流生成（与解码器相同的规范算法）。
Uint8List _buildBox(Uint8List key) {
  final s = Uint8List(256);
  for (var i = 0; i < 256; i++) {
    s[i] = i;
  }
  var j = 0;
  for (var i = 0; i < 256; i++) {
    j = (j + s[i] + key[i % key.length]) & 0xff;
    final t = s[i];
    s[i] = s[j];
    s[j] = t;
  }
  final k = Uint8List(256);
  for (var i = 0; i < 256; i++) {
    k[i] = s[(s[i] + s[(i + s[i]) & 0xff]) & 0xff];
  }
  return k;
}

Uint8List _encryptAudio(Uint8List box, Uint8List data) {
  final out = Uint8List(data.length);
  for (var i = 0; i < data.length; i++) {
    out[i] = data[i] ^ box[(i + 1) % 256];
  }
  return out;
}

void _writeU32(BytesBuilder bb, int v) {
  bb.add([v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, (v >> 24) & 0xff]);
}

/// 编码一条完整的假 NCM 文件字节。
Uint8List encodeFakeNcm({
  required Uint8List rc4Key,
  required Uint8List audio,
  Map<String, dynamic>? meta,
  Uint8List? cover,
}) {
  final bb = BytesBuilder();

  // 魔数 + gap
  bb.add('CTENFDAM'.codeUnits);
  bb.add([0, 0]);

  // key 段
  final keyPlain = BytesBuilder()
    ..add('neteasecloudmusic'.codeUnits)
    ..add([0])
    ..add(rc4Key);
  final keyCipher = _aesEcbEncrypt(_pkcs7Pad(keyPlain.toBytes(), 16), _coreKey);
  final keyXored = _xor(keyCipher, 0x64);
  _writeU32(bb, keyXored.length);
  bb.add(keyXored);

  // meta 段
  if (meta != null) {
    final metaPlain = BytesBuilder()
      ..add('music:'.codeUnits)
      ..add(utf8.encode(jsonEncode(meta))); // 注意：JSON 按 UTF-8 编码（含中文歌名）
    final metaCipher = _aesEcbEncrypt(_pkcs7Pad(metaPlain.toBytes(), 16), _metaKey);
    final metaB64 = base64Encode(metaCipher);
    final full = BytesBuilder()
      ..add('163 key(Don\'t modify):'.codeUnits)
      ..add(metaB64.codeUnits);
    final metaXored = _xor(full.toBytes(), 0x63);
    _writeU32(bb, metaXored.length);
    bb.add(metaXored);
  } else {
    _writeU32(bb, 0);
  }

  // 5 字节 gap
  bb.add([0, 0, 0, 0, 0]);

  // 封面
  final imageSize = cover?.length ?? 0;
  final imageSpace = imageSize; // 无填充
  _writeU32(bb, imageSpace);
  _writeU32(bb, imageSize);
  if (cover != null) {
    bb.add(cover);
  }

  // 音频
  final box = _buildBox(rc4Key);
  bb.add(_encryptAudio(box, audio));

  return Uint8List.fromList(bb.toBytes());
}

void main() {
  group('NcmDecoder', () {
    test('box 密钥流与解码器内部实现对齐（确定性）', () {
      final key = Uint8List.fromList([1, 2, 3, 4, 5]);
      final a = _buildBox(key);
      final b = NcmDecoder.debugBuildBox(key);
      expect(a, b);
      // 确定性：两次调用一致
      final c = _buildBox(Uint8List.fromList([1, 2, 3, 4, 5]));
      expect(a, c);
    });

    test('往返：加密 → 解码得到原始音频/元数据/封面', () {
      final rc4Key = Uint8List.fromList('JustForTestRC4Key-123456'.codeUnits);
      final audio = Uint8List.fromList(
        List<int>.generate(1000, (i) => (i * 7 + 3) & 0xff),
      );
      final cover = Uint8List.fromList(List<int>.generate(64, (i) => (i + 1) & 0xff));
      const meta = <String, dynamic>{'format': 'mp3', 'songName': 'test'};

      final ncm = encodeFakeNcm(rc4Key: rc4Key, audio: audio, meta: meta, cover: cover);
      final decoded = NcmDecoder.decode(ncm);

      expect(decoded.format, 'mp3');
      expect(decoded.audio, audio);
      expect(decoded.meta, meta);
      expect(decoded.coverImage, cover);
    });

    test('往返：flac 格式 + 无封面', () {
      final rc4Key = Uint8List.fromList('flac-key-1234567890'.codeUnits);
      final audio = Uint8List.fromList(List<int>.generate(2560, (i) => i & 0xff));
      const meta = <String, dynamic>{'format': 'flac'};

      final ncm = encodeFakeNcm(rc4Key: rc4Key, audio: audio, meta: meta);
      final decoded = NcmDecoder.decode(ncm);

      expect(decoded.format, 'flac');
      expect(decoded.audio, audio);
      expect(decoded.coverImage, isNull);
    });

    test('往返：随机 meta（含 UTF-8 中文与 base64 封面字段）', () {
      final rc4Key = Uint8List.fromList('random-test-key'.codeUnits);
      final audio = Uint8List.fromList(List<int>.generate(700, (i) => (i * 3) & 0xff));
      final meta = <String, dynamic>{
        'format': 'm4a',
        'musicName': '歌曲',
        'musicPic': base64Encode(List<int>.generate(32, (i) => i)),
      };

      final ncm = encodeFakeNcm(rc4Key: rc4Key, audio: audio, meta: meta);
      final decoded = NcmDecoder.decode(ncm);

      expect(decoded.format, 'm4a');
      expect(decoded.audio, audio);
      expect(decoded.meta?['musicName'], '歌曲');
      expect(decoded.meta?['musicPic'], isA<String>());
    });

    test('非 NCM 文件（魔数错误）抛异常', () {
      final bad = Uint8List.fromList('This is not ncm!!'.codeUnits);
      expect(() => NcmDecoder.decode(bad), throwsA(isA<NcmFormatException>()));
    });

    test('损坏长度字段抛异常', () {
      final rc4Key = Uint8List.fromList('key'.codeUnits);
      final audio = Uint8List.fromList([1, 2, 3, 4]);
      final ncm = encodeFakeNcm(rc4Key: rc4Key, audio: audio);
      final corrupt = Uint8List.fromList(ncm);
      corrupt[10] = 0xff;
      corrupt[11] = 0xff;
      corrupt[12] = 0xff;
      corrupt[13] = 0x7f;
      expect(
        () => NcmDecoder.decode(corrupt),
        throwsA(isA<NcmFormatException>()),
      );
    });
  });
}
