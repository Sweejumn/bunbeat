import 'dart:io';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/song.dart';
import '../services/audio_reader.dart';
import '../services/library_service.dart';
import '../services/bpm_display_controller.dart';
import 'archive_page.dart';
import 'marquee_text.dart';
import 'settings_page.dart';

/// 一个预设音源：常见音乐 App 的下载目录。
class _MusicSource {
  final String name;
  final String path;
  const _MusicSource(this.name, this.path);
}

/// 常见音源的默认下载目录（可再通过「自定义文件夹」手动选其它目录）。
const List<_MusicSource> _kSources = [
  _MusicSource('网易云音乐', '/storage/emulated/0/Download/netease/cloudmusic/Music'),
  _MusicSource('QQ音乐', '/storage/emulated/0/Music/qqmusic/song'),
  _MusicSource('酷狗音乐', '/storage/emulated/0/Download/kgmusic/download'),
];

/// 把文件夹路径映射成一个简短、可读的来源名（不显示完整路径）。
String sourceNameForPath(String? path) {
  if (path == null || path.isEmpty) return '曲库';
  for (final s in _kSources) {
    if (path.toLowerCase() == s.path.toLowerCase()) return s.name;
  }
  return '本地文件夹';
}

/// 曲库歌曲排序方式。
enum _SortMode { scan, titleAZ, titleZA, bpmAsc, bpmDesc, duration }

extension on _SortMode {
  String get label => switch (this) {
        _SortMode.scan => '默认顺序',
        _SortMode.titleAZ => '标题 A→Z',
        _SortMode.titleZA => '标题 Z→A',
        _SortMode.bpmAsc => 'BPM 从低到高',
        _SortMode.bpmDesc => 'BPM 从高到低',
        _SortMode.duration => '时长从长到短',
      };
}

class LibraryPage extends StatefulWidget {
  const LibraryPage({super.key});

  @override
  State<LibraryPage> createState() => _LibraryPageState();
}

class _LibraryPageState extends State<LibraryPage> {
  _SortMode _sortMode = _SortMode.scan;
  final TextEditingController _searchCtrl = TextEditingController();
  bool _searching = false;

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  /// 顶部「添加音乐」：弹底部面板，可选预设音源或自定义文件夹。
  void _addMusic(BuildContext context, LibraryService lib) {
    showModalBottomSheet<void>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const ListTile(
              leading: Icon(Icons.add),
              title: Text('添加音乐'),
              subtitle: Text('选择一个音源或文件夹，会自动扫描导入',
                  style: TextStyle(fontSize: 12)),
            ),
            const Divider(height: 1),
            for (final s in _kSources)
              ListTile(
                leading: const Icon(Icons.library_music),
                title: Text(s.name),
                subtitle: Text(s.path,
                    maxLines: 1, overflow: TextOverflow.ellipsis),
                onTap: () {
                  Navigator.pop(ctx);
                  _pickSource(context, lib, s.path);
                },
              ),
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.folder_open),
              title: const Text('自定义文件夹'),
              subtitle: const Text('自己挑选一个音乐文件夹'),
              onTap: () {
                Navigator.pop(ctx);
                _pickFolder(context, lib);
              },
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _pickFolder(BuildContext context, LibraryService lib) async {
    final reader = AudioReader();
    final pick = await reader.pickFolder(withSubfolders: true);
    if (pick.cancelled) return;
    if (context.mounted) await lib.loadFolder(pick);
  }

  /// 直接按预设音源路径扫描载入（不弹系统文件夹选择器）。
  Future<void> _pickSource(
      BuildContext context, LibraryService lib, String path) async {
    final reader = AudioReader();
    final pick = await reader.scanFolder(path);
    if (!context.mounted) return;
    if (pick.audioFiles.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text('未在该音源目录找到音乐，可能是路径不存在或还没有下载歌曲')),
      );
      return;
    }
    await lib.loadFolder(pick);
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('已导入，正在分析 BPM…')),
      );
    }
  }

  void _openArchive(BuildContext context) {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => const ArchivePage()),
    );
  }

  @override
  Widget build(BuildContext context) {
    final lib = context.watch<LibraryService>();
    return Scaffold(
      appBar: AppBar(
        title: const Text('Bunbeat · 曲库'),
        actions: [
          IconButton(
            icon: const Icon(Icons.inventory_2_outlined),
            tooltip: '归档',
            onPressed: () => _openArchive(context),
          ),
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            tooltip: '设置',
            onPressed: () => Navigator.push(
              context,
              MaterialPageRoute(builder: (_) => const SettingsPage()),
            ),
          ),
        ],
      ),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // 顶部操作条：添加音乐（常驻）+ 排序 + 搜索。
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
            child: Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: () => _addMusic(context, lib),
                    icon: const Icon(Icons.add),
                    label: const Text('添加音乐'),
                  ),
                ),
                const SizedBox(width: 4),
                PopupMenuButton<_SortMode>(
                  tooltip: '排序',
                  initialValue: _sortMode,
                  icon: const Icon(Icons.sort),
                  onSelected: (v) => setState(() => _sortMode = v),
                  itemBuilder: (_) => [
                    for (final m in _SortMode.values)
                      PopupMenuItem(value: m, child: Text(m.label)),
                  ],
                ),
                IconButton(
                  icon: Icon(_searching ? Icons.close : Icons.search),
                  tooltip: _searching ? '关闭搜索' : '搜索',
                  onPressed: () => setState(() {
                    _searching = !_searching;
                    if (!_searching) _searchCtrl.clear();
                  }),
                ),
              ],
            ),
          ),
          if (_searching)
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 4),
              child: TextField(
                controller: _searchCtrl,
                autofocus: true,
                onChanged: (_) => setState(() {}),
                decoration: const InputDecoration(
                  prefixIcon: Icon(Icons.search),
                  hintText: '搜索歌名 / 歌手',
                  isDense: true,
                  border: OutlineInputBorder(),
                ),
              ),
            ),
          if (lib.isAnalyzing)
            Padding(
              padding: const EdgeInsets.all(8),
              child: LinearProgressIndicator(
                value: lib.analyzingTotal == 0
                    ? null
                    : lib.analyzingDone / lib.analyzingTotal,
              ),
            ),
          Expanded(child: _buildBody(context, lib)),
        ],
      ),
    );
  }

  Widget _buildBody(BuildContext context, LibraryService lib) {
    if (lib.folderPath == null) {
      return const Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.music_note, size: 80, color: Colors.grey),
            SizedBox(height: 16),
            Text('还没有音乐\n点上方「添加音乐」选择音源导入'),
            SizedBox(height: 16),
            Text('无需上传，全程离线', style: TextStyle(fontSize: 12)),
          ],
        ),
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _FolderHeader(lib: lib, onPick: () => _pickFolder(context, lib)),
        Expanded(
          child: _SongList(
            songs: _visibleSongs(lib),
            onLongPress: (song) => _showActions(context, lib, song),
          ),
        ),
      ],
    );
  }

  /// 已排序 + 搜索过滤后的可见歌曲（已归档的不在 lib.songs 里，自然排除）。
  List<Song> _visibleSongs(LibraryService lib) {
    var list = lib.songs.toList();
    final q = _searchCtrl.text.trim().toLowerCase();
    if (q.isNotEmpty) {
      list = list
          .where((s) =>
              s.title.toLowerCase().contains(q) ||
              s.filename.toLowerCase().contains(q) ||
              s.artist.toLowerCase().contains(q))
          .toList();
    }
    switch (_sortMode) {
      case _SortMode.scan:
        break;
      case _SortMode.titleAZ:
        list.sort((a, b) =>
            a.title.toLowerCase().compareTo(b.title.toLowerCase()));
        break;
      case _SortMode.titleZA:
        list.sort((a, b) =>
            b.title.toLowerCase().compareTo(a.title.toLowerCase()));
        break;
      case _SortMode.bpmAsc:
        list.sort((a, b) => (a.originalBpm ?? 0)
            .compareTo(b.originalBpm ?? 0));
        break;
      case _SortMode.bpmDesc:
        list.sort((a, b) => (b.originalBpm ?? 0)
            .compareTo(a.originalBpm ?? 0));
        break;
      case _SortMode.duration:
        list.sort((a, b) =>
            (b.duration ?? 0).compareTo(a.duration ?? 0));
        break;
    }
    return list;
  }

  /// 长按弹出操作菜单（归档 / 重新检测 / BPM 修改）。
  Future<void> _showActions(
      BuildContext context, LibraryService lib, Song song) async {
    final action = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.music_note),
              title: MarqueeText(song.title),
              subtitle: Text(_statusText(song)),
            ),
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.inventory_2_outlined),
              title: const Text('归档'),
              subtitle: const Text('从曲库与推荐中隐藏，可在归档页放回'),
              onTap: () => Navigator.pop(ctx, 'archive'),
            ),
            ListTile(
              leading: const Icon(Icons.refresh),
              title: const Text('重新检测'),
              subtitle: const Text('重新分析这首歌曲的 BPM'),
              onTap: () => Navigator.pop(ctx, 'retry'),
            ),
            if (song.hasBpm) ...[
              ListTile(
                leading: const Icon(Icons.double_arrow),
                title: const Text('BPM ×2'),
                subtitle: const Text('把 BPM 乘以二（常用于半拍/休止误判）'),
                onTap: () => Navigator.pop(ctx, 'x2'),
              ),
              ListTile(
                leading: const Icon(Icons.horizontal_rule),
                title: const Text('BPM ÷2'),
                subtitle: const Text('把 BPM 除以二'),
                onTap: () => Navigator.pop(ctx, 'div2'),
              ),
              ListTile(
                leading: const Icon(Icons.edit),
                title: const Text('手动修改'),
                subtitle: const Text('直接输入一个数值作为 BPM'),
                onTap: () => Navigator.pop(ctx, 'manual'),
              ),
            ],
          ],
        ),
      ),
    );

    if (action == null || !mounted) return;
    switch (action) {
      case 'archive':
        await lib.archive(song);
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('已归档，可在曲库右上角归档入口查看')),
          );
        }
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

  String _statusText(Song song) {
    switch (song.bpmStatus) {
      case BpmStatus.pending:
        return '等待分析';
      case BpmStatus.analyzing:
        return '分析中…';
      case BpmStatus.failed:
        return song.bpmError ?? '分析失败';
      case BpmStatus.done:
        final conf = (song.bpmConfidence ?? 0) * 100;
        return '${context.read<BpmDisplayController>().format(song.originalBpm)} BPM · 可信度 ${conf.round()}%';
    }
  }

  Future<void> _editBpm(
      BuildContext context, LibraryService lib, Song song) async {
    final controller = TextEditingController(
      text: song.originalBpm?.round().toString() ?? '',
    );
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
    if (value != null) {
      lib.setManualBpm(song, value);
    }
  }
}

class _FolderHeader extends StatelessWidget {
  final LibraryService lib;
  final VoidCallback onPick;
  const _FolderHeader({required this.lib, required this.onPick});

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: const Icon(Icons.folder),
      // 不显示完整路径，只显示简短的可读来源名。
      title: Text(sourceNameForPath(lib.folderPath)),
      subtitle: Text('${lib.songs.length} 首音乐'),
      trailing: TextButton(onPressed: onPick, child: const Text('更换')),
    );
  }
}

class _SongList extends StatelessWidget {
  final List<Song> songs;
  final void Function(Song) onLongPress;
  const _SongList({required this.songs, required this.onLongPress});

  @override
  Widget build(BuildContext context) {
    if (songs.isEmpty) {
      return const Center(child: Text('没有匹配的音乐'));
    }
    return ListView.builder(
      itemCount: songs.length,
      itemBuilder: (context, i) {
        final s = songs[i];
        return _SongTile(song: s, onLongPress: () => onLongPress(s));
      },
    );
  }
}

class _SongTile extends StatelessWidget {
  final Song song;
  final VoidCallback onLongPress;
  const _SongTile({required this.song, required this.onLongPress});

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

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    // 浅色模式用偏深的颜色保证在白底上可读；深色模式用高亮色。
    final dark = theme.brightness == Brightness.dark;
    final color = switch (song.bpmStatus) {
      BpmStatus.done => dark ? Colors.greenAccent : Colors.green.shade700,
      BpmStatus.failed => dark ? Colors.redAccent : Colors.red.shade700,
      _ => dark ? Colors.orangeAccent : Colors.orange.shade800,
    };
    return ListTile(
      // 长按弹出操作菜单（归档 / 重新检测 / BPM 修改）。
      onLongPress: onLongPress,
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
      subtitle: Text(_statusText(context), style: TextStyle(color: color)),
    );
  }
}
