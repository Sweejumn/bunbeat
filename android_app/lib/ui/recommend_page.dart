import 'dart:io';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/modes.dart';
import '../models/song.dart';
import '../services/audio_player_service.dart';
import '../services/library_service.dart';
import '../services/queue_service.dart';

class RecommendPage extends StatefulWidget {
  const RecommendPage({super.key});

  @override
  State<RecommendPage> createState() => _RecommendPageState();
}

class _RecommendPageState extends State<RecommendPage> {
  ModeId _mode = ModeId.run;
  final Set<String> _selected = {};

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryService>();
    final recs = lib.recommend(target: lib.targetBpm)
      ..sort((a, b) => a.distance.compareTo(b.distance));
    final eligible = recs.where((r) => r.song.hasBpm).length;

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
          Text('选择运动模式', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: kModes.map((m) {
              final selected = _mode == m.id;
              return ChoiceChip(
                avatar: Text(m.icon),
                label: Text(
                  '${m.label}\n${m.rangeLow}–${m.rangeHigh} BPM',
                  textAlign: TextAlign.center,
                ),
                selected: selected,
                onSelected: (_) {
                  setState(() {
                    _mode = m.id;
                    lib.targetBpm = m.defaultBpm;
                  });
                },
              );
            }).toList(),
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              const Text('目标 BPM'),
              Expanded(
                child: Slider(
                  min: 60,
                  max: 220,
                  value: lib.targetBpm.clamp(60, 220).toDouble(),
                  label: lib.targetBpm.round().toString(),
                  onChanged: (v) => setState(() => lib.targetBpm = v),
                ),
              ),
              SizedBox(
                width: 56,
                child: Text(
                  '${lib.targetBpm.round()}',
                  textAlign: TextAlign.end,
                  style: Theme.of(context).textTheme.titleLarge,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text('已识别 ${eligible} 首可用于变速的歌曲', style: Theme.of(context).textTheme.bodySmall),
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
  final bool selected;
  final VoidCallback onToggle;
  const _RecTile({required this.r, required this.selected, required this.onToggle});

  @override
  Widget build(BuildContext context) {
    final s = r.song;
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
      title: Text(
        s.title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Row(
        children: [
          _Stars(score: r.score),
          const SizedBox(width: 6),
          Expanded(
            child: Text(
              '${s.originalBpm!.round()} BPM · 距离目标 ${r.distance.toStringAsFixed(1)}',
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

class _Stars extends StatelessWidget {
  final int score;
  const _Stars({required this.score});

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: List.generate(5, (i) {
        return Icon(
          i < score ? Icons.star : Icons.star_border,
          size: 16,
          color: Colors.amber,
        );
      }),
    );
  }
}
