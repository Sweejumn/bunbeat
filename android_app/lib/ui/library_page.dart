import 'dart:io';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/song.dart';
import '../services/audio_reader.dart';
import '../services/library_service.dart';
import '../services/bpm_display_controller.dart';
import '../services/queue_service.dart';
import 'archive_page.dart';
import 'help_dialog.dart';
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

  /// 单击选中的歌曲 id 集合（用主题色高亮），供「加入播放列表」批量处理。
  final Set<String> _selected = {};

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  /// 顶部「添加音乐」：根据是否已选过文件夹决定行为。
  /// - 没选过（尚未导入）：直接弹系统文件夹选择器，让用户尽快用起来；
  /// - 已选过：弹出底部面板，可选预设音源或自定义文件夹（便于更换来源）。
  void _addMusic(BuildContext context, LibraryService lib) {
    if (lib.folderPath == null) {
      _pickFolder(context, lib);
    } else {
      _showSourceSheet(context, lib);
    }
  }

  /// 「添加音乐」底部面板：可选预设音源或自定义文件夹。
  void _showSourceSheet(BuildContext context, LibraryService lib) {
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
            icon: const Icon(Icons.help_outline),
            tooltip: '使用说明',
            onPressed: () =>
                HelpDialog.show(context, section: HelpSection.library),
          ),
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
          // 顶部操作条：音源按钮（首次进入为「添加音乐」，选过文件夹后显示「音源」，点击功能相同）+ 排序 + 搜索。
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
            child: Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: () => _addMusic(context, lib),
                    icon: const Icon(Icons.add),
                    label: Text(lib.folderPath == null ? '添加音乐' : '音源'),
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
            Text('还没有音乐\n点上方「添加音乐」选择一个文件夹导入'),
            SizedBox(height: 16),
            Text('无需上传，全程离线', style: TextStyle(fontSize: 12)),
          ],
        ),
      );
    }
    return _SongList(
      songs: _visibleSongs(lib),
      selected: _selected,
      onToggle: (id) => setState(() {
        if (!_selected.remove(id)) _selected.add(id);
      }),
      onLongPress: (song) => _showActions(context, lib, song),
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

  /// 按用户点击顺序返回选中的歌曲。
  /// `_selected` 是插入序的 LinkedHashSet，其迭代顺序即点击先后，
  /// 因此这里遍历 `_selected`（而不是反查 `lib.songs`）以保证加入播放列表的顺序与点击一致。
  List<Song> _selectedSongs(LibraryService lib) {
    final byId = {for (final s in lib.songs) s.id: s};
    return [for (final id in _selected) if (byId[id] != null) byId[id]!];
  }

  /// 长按弹出操作菜单（加入播放列表 / 归档 / 重新检测 / BPM 修改）。
  Future<void> _showActions(
      BuildContext context, LibraryService lib, Song song) async {
    // 判断当前选中（或长按这一首）是否已全部在播放列表里，据此显示「删除」还是「加入」。
    final queue = context.read<QueueService>();
    final targets = _selected.isNotEmpty ? _selectedSongs(lib) : <Song>[song];
    final targetPaths = {
      for (final s in targets)
        if (s.hasBpm) s.filePath,
    };
    final allInQueue = targetPaths.isNotEmpty &&
        targetPaths.every(queue.items.map((e) => e.filePath).toSet().contains);

    final action = await showModalBottomSheet<String>(
      context: context,
      // 面板高度不足时内容可滚动，避免底部选项（如「手动修改」）被遮挡无法点选。
      builder: (ctx) => SafeArea(
        child: SingleChildScrollView(
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
              leading: const Icon(Icons.playlist_add),
              title: const Text('加入播放列表'),
              subtitle: Text(allInQueue
                  ? '全部已在播放列表中，点击从列表删除'
                  : '点击加入播放列表'),
              onTap: () => Navigator.pop(ctx, 'playlist'),
            ),
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
                leading: const Icon(Icons.edit),
                title: const Text('手动修改'),
                subtitle: const Text('直接输入一个数值作为 BPM'),
                onTap: () => Navigator.pop(ctx, 'manual'),
              ),
            ],
          ],
        ),
      ),
    ),
    );

    if (action == null || !mounted) return;
    switch (action) {
      case 'playlist':
        await _togglePlaylist(context, lib, song);
        break;
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
      case 'manual':
        await _editBpm(context, lib, song);
        break;
    }
  }

  /// 「加入播放列表」：对当前选中集合切换与播放队列的关系。
  /// 全部已在队列 → 全部移出；部分在 → 补入剩余；都不在 → 全部加入。
  /// 若未选中任何歌，则只处理长按的这一首。
  Future<void> _togglePlaylist(
      BuildContext context, LibraryService lib, Song longPressed) async {
    final List<Song> target;
    if (_selected.isNotEmpty) {
      target = _selectedSongs(lib); // 按点击顺序
    } else {
      target = [longPressed];
    }
    final playlist = lib.buildPlaylist(target); // 只含已有 BPM 的歌曲
    if (playlist.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('这些歌曲还没有 BPM，无法加入播放列表')),
      );
      return;
    }
    final queue = context.read<QueueService>();
    final (added, removed) = queue.toggleAddRemove(playlist);
    if (_selected.isNotEmpty) setState(_selected.clear);
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(removed > 0
            ? '已从播放列表移出 $removed 首'
            : '已把 $added 首加入播放列表'),
      ),
    );
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

class _SongList extends StatelessWidget {
  final List<Song> songs;
  final Set<String> selected;
  final ValueChanged<String> onToggle;
  final void Function(Song) onLongPress;
  const _SongList({
    required this.songs,
    required this.selected,
    required this.onToggle,
    required this.onLongPress,
  });

  @override
  Widget build(BuildContext context) {
    if (songs.isEmpty) {
      return const Center(child: Text('没有匹配的音乐'));
    }
    return ListView.builder(
      itemCount: songs.length,
      itemBuilder: (context, i) {
        final s = songs[i];
        return _SongTile(
          song: s,
          selected: selected.contains(s.id),
          onToggle: () => onToggle(s.id),
          onLongPress: () => onLongPress(s),
        );
      },
    );
  }
}

class _SongTile extends StatelessWidget {
  final Song song;
  final bool selected;
  final VoidCallback onToggle;
  final VoidCallback onLongPress;

  /// 长按呼出菜单的触发时长：比系统默认（约 500ms）更短，方便快速呼出。
  static const Duration _longPressDuration = Duration(milliseconds: 250);

  const _SongTile({
    required this.song,
    required this.selected,
    required this.onToggle,
    required this.onLongPress,
  });

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
    // 用自定义长按识别器以缩短长按时长（ListTile.onLongPress 用的是系统默认约 500ms）。
    // 快速点击仍走 ListTile.onTap 选中；按住 _longPressDuration 不动则呼出菜单。
    return RawGestureDetector(
      gestures: {
        LongPressGestureRecognizer:
            GestureRecognizerFactoryWithHandlers<LongPressGestureRecognizer>(
          () => LongPressGestureRecognizer(
              duration: _longPressDuration, supportedDevices: null),
          (recognizer) => recognizer.onLongPress = onLongPress,
        ),
      },
      child: ListTile(
        onTap: onToggle,
        selected: selected,
        selectedTileColor: theme.colorScheme.primary.withValues(alpha: 0.15),
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
      ),
    );
  }
}
