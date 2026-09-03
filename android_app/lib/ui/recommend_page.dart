import 'dart:io';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/song.dart';
import '../services/audio_player_service.dart';
import '../services/library_service.dart';
import '../services/queue_service.dart';
import 'marquee_text.dart';
import 'mode_picker.dart';
import 'tempo_grade.dart';

class RecommendPage extends StatefulWidget {
  const RecommendPage({super.key});

  @override
  State<RecommendPage> createState() => _RecommendPageState();
}

class _RecommendPageState extends State<RecommendPage> {
  final Set<String> _selected = {};
  double _lastAutoTarget = double.negativeInfinity;

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryService>();
    final recs = lib.recommend(target: lib.targetBpm)
      ..sort((a, b) => a.distance.compareTo(b.distance));
    final eligible = recs.where((r) => r.song.hasBpm).length;
    final target = lib.targetBpm;

    // 对齐 Web：每次目标 BPM 变化（含首次载入）自动勾选所有可变速歌曲，
    // 红色 ✕（>12%）的歌曲不自动选，仍保留用户手动选择。
    if (_lastAutoTarget != target) {
      _lastAutoTarget = target;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        setState(() {
          _selected.clear();
          for (final r in lib.recommend(target: target)) {
            if (_processable(r, target)) _selected.add(r.song.id);
          }
        });
      });
    }

    return Scaffold(
      appBar: AppBar(title: const Text('运动模式与推荐')),
      // 底部常驻播放条：无论列表多长，按钮始终可见，无需滚动到底。
      bottomNavigationBar: _selected.isEmpty
          ? null
          : SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                child: FilledButton.icon(
                  onPressed: () => _play(context, lib),
                  icon: const Icon(Icons.play_arrow),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(52),
                    textStyle: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  label: Text('变速并播放（${_selected.length} 首）'),
                ),
              ),
            ),
      body: ListView(
        padding: EdgeInsets.fromLTRB(16, 16, 16, _selected.isEmpty ? 16 : 96),
        children: [
          // 运动模式选择（与 Web 版 ModePicker 对齐：彩色滑块 + 快捷芯片 + 手动输入）。
          ModePicker(
            bpm: lib.targetBpm,
            onChanged: (v) {
              setState(() {
                lib.targetBpm = v;
              });
            },
          ),
          const SizedBox(height: 16),
          const SizedBox(height: 8),
          if (eligible > 0)
            Row(
              children: [
                Text('选择', style: Theme.of(context).textTheme.bodyMedium),
                const Spacer(),
                TextButton(
                  onPressed: () {
                    setState(() {
                      _selected.clear();
                      for (final r in recs) {
                        if (_processable(r, lib.targetBpm)) {
                          _selected.add(r.song.id);
                        }
                      }
                    });
                  },
                  child: const Text('自动勾选可变速'),
                ),
                TextButton(
                  onPressed: () {
                    setState(() {
                      _selected.clear();
                      for (final r in recs) {
                        _selected.add(r.song.id);
                      }
                    });
                  },
                  child: const Text('全选'),
                ),
                TextButton(
                  onPressed: () => setState(() => _selected.clear()),
                  child: const Text('全不选'),
                ),
              ],
            ),
          const SizedBox(height: 4),
          ...recs.map((r) => _RecTile(
                r: r,
                targetBpm: target,
                selected: _selected.contains(r.song.id),
                onToggle: () => setState(() {
                  if (!_selected.remove(r.song.id)) {
                    _selected.add(r.song.id);
                  }
                }),
              )),
          if (recs.isEmpty)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Center(child: Text('请先在「曲库」选择文件夹并等待 BPM 分析')),
            ),
        ],
      ),
    );
  }

  /// 是否可用于变速：与原 BPM 的偏移 ≤12%（对应 Web TempoArrow 的可变速判定）。
  bool _processable(Recommendation r, double target) {
    final orig = r.song.originalBpm;
    if (orig == null || orig <= 0) return false;
    return (r.song.originalBpm! - target).abs() / orig <= 0.12;
  }

  void _play(BuildContext context, LibraryService lib) {    final queue = context.read<QueueService>();
    final selected = lib.songs.where((s) => _selected.contains(s.id)).toList();
    final playlist = lib.buildPlaylist(selected, target: lib.targetBpm);
    queue.start(playlist);
    // 真正开始播放第一首（与「变速并播放」文案一致），
    // 由 AudioPlayerService 载入并变速；失败也不阻塞入队。
    final svc = context.read<AudioPlayerService>();
    if (playlist.isNotEmpty) {
      svc.tryPlay(playlist.first);
    }
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('已入队并开始播放第一首')),
    );
  }
}

class _RecTile extends StatelessWidget {
  final Recommendation r;
  final double targetBpm;
  final bool selected;
  final VoidCallback onToggle;
  const _RecTile({
    required this.r,
    required this.targetBpm,
    required this.selected,
    required this.onToggle,
  });

  @override
  Widget build(BuildContext context) {
    final s = r.song;
    final orig = s.originalBpm;
    final grade = gradeTempo(orig, targetBpm);
    return ListTile(
      onTap: onToggle,
      leading: ClipRRect(
        borderRadius: BorderRadius.circular(4),
        child: SizedBox(
          width: 48,
          height: 48,
          child: s.artworkPath != null
              ? Image.file(
                  File(s.artworkPath!),
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) =>
                      const Icon(Icons.music_note, color: Colors.grey),
                )
              : const Icon(Icons.music_note, color: Colors.grey),
        ),
      ),
      title: MarqueeText(s.title),
      subtitle: Row(
        children: [
          // 分级箭头/符号（绿= · 绿/琥珀/红↑↓ · 红✕），颜色与 Web 图例一致。
          Text(
            grade.symbol,
            style: TextStyle(
              color: grade.color,
              fontWeight: FontWeight.bold,
              fontSize: 16,
            ),
          ),
          const SizedBox(width: 6),
          Expanded(
            child: Text(
              orig != null
                  ? '${orig.round()} BPM · ${grade.pctLabel} · ${r.distance.toStringAsFixed(1)}'
                  : '未知 BPM',
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
      // 整行可点选，勾选框仅作选中态显示（避免点击二次触发）。
      trailing: Checkbox(
        value: selected,
        onChanged: null,
      ),
      dense: true,
    );
  }
}

