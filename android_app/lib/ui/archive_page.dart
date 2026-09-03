import 'dart:io';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/song.dart';
import '../services/library_service.dart';
import '../services/bpm_display_controller.dart';
import 'marquee_text.dart';

/// 归档页：展示已归档（从曲库/推荐中隐藏）的歌曲，可放回曲库或继续操作 BPM。
class ArchivePage extends StatelessWidget {
  const ArchivePage({super.key});

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryService>();
    return Scaffold(
      appBar: AppBar(title: const Text('归档')),
      body: lib.archived.isEmpty
          ? const Center(
              child: Text('暂无归档歌曲\n在曲库长按歌曲 → 归档'),
            )
          : ListView.builder(
              itemCount: lib.archived.length,
              itemBuilder: (context, i) {
                final s = lib.archived[i];
                return _ArchivedTile(song: s);
              },
            ),
    );
  }
}

class _ArchivedTile extends StatelessWidget {
  final Song song;
  const _ArchivedTile({required this.song});

  String _statusText(BuildContext outer) {
    switch (song.bpmStatus) {
      case BpmStatus.pending:
        return '等待分析';
      case BpmStatus.analyzing:
        return '分析中…';
      case BpmStatus.failed:
        return song.bpmError ?? '分析失败';
      case BpmStatus.done:
        final conf = (song.bpmConfidence ?? 0) * 100;
        return '${outer.read<BpmDisplayController>().format(song.originalBpm)} BPM · 可信度 ${conf.round()}%';
    }
  }

  Future<void> _showActions(BuildContext context, LibraryService lib) async {
    final action = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.music_note),
              title: MarqueeText(song.title),
              subtitle: Text(_statusText(context)),
            ),
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.unarchive),
              title: const Text('放回曲库'),
              onTap: () => Navigator.pop(ctx, 'unarchive'),
            ),
            ListTile(
              leading: const Icon(Icons.refresh),
              title: const Text('重新检测'),
              onTap: () => Navigator.pop(ctx, 'retry'),
            ),
            if (song.hasBpm) ...[
              ListTile(
                leading: const Icon(Icons.double_arrow),
                title: const Text('BPM ×2'),
                onTap: () => Navigator.pop(ctx, 'x2'),
              ),
              ListTile(
                leading: const Icon(Icons.horizontal_rule),
                title: const Text('BPM ÷2'),
                onTap: () => Navigator.pop(ctx, 'div2'),
              ),
              ListTile(
                leading: const Icon(Icons.edit),
                title: const Text('手动修改'),
                onTap: () => Navigator.pop(ctx, 'manual'),
              ),
            ],
          ],
        ),
      ),
    );

    if (action == null || !context.mounted) return;
    switch (action) {
      case 'unarchive':
        await lib.unarchive(song);
        break;
      case 'retry':
        lib.retryAnalyze(song);
        break;
      case 'x2':
        lib.setManualBpm(song, ((song.originalBpm ?? 0) * 2).clamp(20, 400));
        break;
      case 'div2':
        lib.setManualBpm(song, ((song.originalBpm ?? 0) / 2).clamp(20, 400));
        break;
      case 'manual':
        await _editBpm(context, lib, song);
        break;
    }
  }

  Future<void> _editBpm(
      BuildContext context, LibraryService lib, Song cur) async {
    final controller =
        TextEditingController(text: cur.originalBpm?.round().toString() ?? '');
    final value = await showDialog<double>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('修改 BPM'),
        content: TextField(
          controller: controller,
          keyboardType: TextInputType.number,
          decoration: const InputDecoration(labelText: 'BPM (20–400)'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
            onPressed: () {
              final v = double.tryParse(controller.text);
              if (v != null && v >= 20 && v <= 400) Navigator.pop(ctx, v);
            },
            child: const Text('确定'),
          ),
        ],
      ),
    );
    if (value != null) lib.setManualBpm(cur, value);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final lib = context.read<LibraryService>();
    return ListTile(
      onTap: () => _showActions(context, lib),
      onLongPress: () => _showActions(context, lib),
      leading: ClipRRect(
        borderRadius: BorderRadius.circular(4),
        child: SizedBox(
          width: 48,
          height: 48,
          child: song.artworkPath != null
              ? Image.file(
                  File(song.artworkPath!),
                  fit: BoxFit.cover,
                  errorBuilder: (_, __, ___) =>
                      const Icon(Icons.music_note, color: Colors.grey),
                )
              : const Icon(Icons.music_note, color: Colors.grey),
        ),
      ),
      title: MarqueeText(song.title),
      subtitle: Text(_statusText(context)),
      trailing: TextButton(
        onPressed: () => lib.unarchive(song),
        child: const Text('放回'),
      ),
      // 归档行淡显，示意已隐藏。
      tileColor: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.3),
    );
  }
}
