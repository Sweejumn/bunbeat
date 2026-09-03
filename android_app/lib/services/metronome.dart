/// 节拍器：一个前瞻调度器，把点击安排在音乐的时间轴上，逐拍与目标 BPM 对齐。
///
/// 由于 Flutter 端没有 Web Audio 那种与播放器共享的 AudioContext 时钟，
/// 这里用一个高精度 Stopwatch 把「媒体时间 ↔ 墙钟时间」锚定：
///   墙钟时刻的媒体时间 = 锚点媒体位置 + (Stopwatch 已流逝)
/// 调度器每个 tick 检查是否跨过了某个拍边界（phase + k*period），
/// 若跨过就用节拍器声音池播出一声响。
library;

import 'dart:async';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:just_audio/just_audio.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

class Metronome {
  final List<AudioPlayer> _pool = [];

  /// 播放器池满后不再新建，改用最久空闲复用（避免无限增长）。
  final int _poolSize = 4;

  /// 每个池内播放器上一次开始播放的墙钟时间（选中“最久空闲”的播放器，
  /// 从而即使在高速 BPM 下也几乎不会重叠或丢失点击）。
  final List<int> _poolLastUsedMs = [];

  Timer? _timer;
  Stopwatch? _watch;
  Duration _anchorPosition = Duration.zero;

  double _bpm = 120;
  double _phase = 0.0; // 第一个拍的媒体时间（秒）
  bool _phaseKnown = false;
  List<double>? _beatTimes; // 媒体时间轴真实拍点（秒）
  int _nextBeatIndex = 0;

  /// 相位微调（秒）：正 = 点击整体滞后，负 = 提前。由「偏差」滑杆驱动。
  double _phaseOffset = 0.0;

  bool _enabled = false;
  double _volume = 0.5;
  String? _clickPath;

  /// 每次计划点击时回调（用于 UI 上的拍点指示器）。
  void Function(double mediaSeconds)? onClick;

  Future<void> ensureInitialized() async {
    if (_clickPath != null) return;
    final dir = await getTemporaryDirectory();
    _clickPath = p.join(dir.path, 'runbpm_tick.wav');
    if (!await File(_clickPath!).exists()) {
      await _writeClickWav(_clickPath!);
    }
    await _initPool();
  }

  /// 预先创建并加载节拍器声音池：每个播放器都在初始化时设好文件路径与音量，
  /// 之后每次点击只 `play()`，不再有按次加载文件的延迟 —— 这是“时有时无”
  /// 的根本修复（旧版每次点击都要 setFilePath，延迟未达标就会丢拍）。
  Future<void> _initPool() async {
    if (_pool.isNotEmpty || _clickPath == null) return;
    for (var i = 0; i < _poolSize; i++) {
      try {
        final pl = AudioPlayer();
        await pl.setFilePath(_clickPath!);
        await pl.setVolume(_volume);
        await pl.setLoopMode(LoopMode.off);
        // seek 到结尾，避免刚创建时 playing 流状态干扰
        _pool.add(pl);
        _poolLastUsedMs.add(0);
      } catch (_) {
        // 若某个播放器初始化失败，跳过（其余仍可工作）
      }
    }
  }

  void setBpm(double bpm) {
    if (bpm == _bpm) return;
    _bpm = bpm;
    if (_enabled) _restart();
  }

  /// 设置真实拍点（媒体时间轴秒），null 则回退到等距网格。
  void setBeatMap(List<double>? beatTimes) {
    if (beatTimes != null && beatTimes.length >= 2) {
      _beatTimes = List.of(beatTimes)..sort();
    } else {
      _beatTimes = null;
    }
    _nextBeatIndex = 0;
    if (_enabled) _restart();
  }

  /// 设置等距网格相位锚点（媒体时间秒）。
  void setPhase(double? phase) {
    _phaseKnown = phase != null && phase >= 0;
    _phase = _phaseKnown ? (phase ?? 0.0) : 0.0;
    if (_enabled) _restart();
  }

  /// 相位微调（秒）：正 = 点击整体滞后，负 = 提前。调用方应钳制在 ±半拍。
  void setPhaseOffset(double offsetSeconds) {
    if (offsetSeconds == _phaseOffset) return;
    _phaseOffset = offsetSeconds;
    if (_enabled) _restart();
  }

  double get phaseOffset => _phaseOffset;

  void setVolume(double v) {
    _volume = v.clamp(0.0, 1.0);
    for (final pl in _pool) {
      pl.setVolume(_volume);
    }
  }

  double get volume => _volume;

  void setEnabled(bool on) {
    if (on == _enabled) return;
    _enabled = on;
    if (on) {
      _restart();
    } else {
      _stopTimer();
    }
  }

  bool get isEnabled => _enabled;

  /// 播放/暂停/seek 时调用，Re-锚定时钟。
  void reAnchor(Duration position) {
    _anchorPosition = position;
    _watch = Stopwatch()..start();
    // 重置拍点游标，确保从当前位置重新对齐（即使此前已推进过）
    _nextBeatIndex = 0;
    if (_enabled) _restart();
  }

  void dispose() {
    _stopTimer();
    for (final pl in _pool) {
      pl.dispose();
    }
    _pool.clear();
  }

  void _restart() {
    _stopTimer();
    if (!_enabled) return;
    _timer = Timer.periodic(const Duration(milliseconds: 16), _tick);
  }

  void _stopTimer() {
    _timer?.cancel();
    _timer = null;
  }

  double get _mediaNow {
    final w = _watch;
    if (w == null) return _anchorPosition.inMilliseconds / 1000.0;
    return _anchorPosition.inMilliseconds / 1000.0 + w.elapsedMilliseconds / 1000.0;
  }

  void _tick(Timer t) {
    final now = _mediaNow;
    if (_beatTimes != null) {
      // 逐真实拍点（受相位微调整体平移：拍点 + offset 越过 now 才触发）
      while (_nextBeatIndex < _beatTimes!.length &&
          _beatTimes![_nextBeatIndex] + _phaseOffset <= now + 0.03) {
        _triggerClick(_beatTimes![_nextBeatIndex] + _phaseOffset);
        _nextBeatIndex++;
      }
    } else if (_phaseKnown) {
      // 等距网格
      final period = 60.0 / _bpm;
      final shifted = _phase + _phaseOffset;
      final k = ((now - shifted) / period).floor();
      final beat = shifted + k * period;
      if (beat > 0 && (beat - _lastGridBeat).abs() > 1e-3 && beat <= now + 0.03) {
        _triggerClick(beat);
        _lastGridBeat = beat;
      }
    } else {
      // 无相位：按时间自由点击
    }
  }

  double _lastGridBeat = -1e9;

  void _triggerClick(double mediaSeconds) {
    onClick?.call(mediaSeconds);
    if (_pool.isEmpty || _clickPath == null) return;
    // 选取“最久未用”（空闲最久）的播放器，降低重叠/丢拍概率。
    final now = DateTime.now().millisecondsSinceEpoch;
    var best = 0;
    for (var i = 1; i < _pool.length; i++) {
      if (_poolLastUsedMs[i] < _poolLastUsedMs[best]) best = i;
    }
    final pl = _pool[best];
    _poolLastUsedMs[best] = now;
    try {
      pl.seek(Duration.zero);
      pl.play();
    } catch (_) {}
  }

  /// 生成一个 60ms 的短促「嗒」声写为单声道 16-bit WAV。
  Future<void> _writeClickWav(String path) async {
    final sr = 44100;
    final dur = 0.06;
    final n = (sr * dur).round();
    final bytes = ByteData(n * 2);
    for (int i = 0; i < n; i++) {
      final t = i / sr;
      // 指数衰减的正弦（2kHz），听起来像短促的嗒声
      final env = math.exp(-18 * t);
      final v = math.sin(2 * math.pi * 2000 * t) * env;
      final s16 = (v * 0.8 * 32767).round().clamp(-32768, 32767);
      bytes.setInt16(i * 2, s16, Endian.little);
    }
    // 44-byte WAV header
    final header = ByteData(44);
    void ascii(int off, String s) {
      for (int i = 0; i < s.length; i++) {
        header.setUint8(off + i, s.codeUnitAt(i));
      }
    }

    ascii(0, 'RIFF');
    header.setUint32(4, 36 + n * 2, Endian.little);
    ascii(8, 'WAVE');
    ascii(12, 'fmt ');
    header.setUint32(16, 16, Endian.little);
    header.setUint16(20, 1, Endian.little); // PCM
    header.setUint16(22, 1, Endian.little); // mono
    header.setUint32(24, sr, Endian.little);
    header.setUint32(28, sr * 2, Endian.little); // byte rate
    header.setUint16(32, 2, Endian.little); // block align
    header.setUint16(34, 16, Endian.little); // bits
    ascii(36, 'data');
    header.setUint32(40, n * 2, Endian.little);

    final wav = Uint8List(44 + n * 2);
    wav.setRange(0, 44, header.buffer.asUint8List());
    wav.setRange(44, 44 + n * 2, bytes.buffer.asUint8List());
    await File(path).writeAsBytes(wav, flush: true);
  }
}
