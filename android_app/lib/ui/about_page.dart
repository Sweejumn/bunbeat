import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../services/update_service.dart';
import 'update_flow.dart';

/// 关于页：应用图标/名称、版本、简介、最新版本公告、致谢，
/// 以及「检查更新」与「查看 GitHub 源代码」入口。
class AboutPage extends StatefulWidget {
  const AboutPage({super.key});

  @override
  State<AboutPage> createState() => _AboutPageState();
}

class _AboutPageState extends State<AboutPage> {
  String? _version;
  LatestRelease? _latest;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadVersion();
    _loadLatest();
  }

  Future<void> _loadVersion() async {
    final info = await PackageInfo.fromPlatform();
    if (mounted) {
      setState(() => _version = '${info.version}+${info.buildNumber}');
    }
  }

  Future<void> _loadLatest() async {
    final latest = await UpdateService().fetchLatestRelease();
    if (mounted) {
      setState(() {
        _latest = latest;
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('关于')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          // 图标与名称（图标与桌面启动图标一致）
          Center(
            child: Column(
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(20),
                  child: Image.asset(
                    'assets/ic_launcher.png',
                    width: 88,
                    height: 88,
                    fit: BoxFit.cover,
                  ),
                ),
                const SizedBox(height: 12),
                Text('Bunbeat',
                    style: theme.textTheme.headlineSmall
                        ?.copyWith(fontWeight: FontWeight.bold)),
                const SizedBox(height: 4),
                Text(
                  _version != null ? '版本 $_version' : '版本 …',
                  style: theme.textTheme.bodySmall,
                ),
              ],
            ),
          ),
          const SizedBox(height: 24),

          // 简介
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('简介', style: theme.textTheme.titleSmall),
                  const SizedBox(height: 8),
                  Text(
                    'Bunbeat 是一款跑步音乐播放器：选择本地文件夹直接读取音乐，自动识别 BPM，'
                    '按你选的节奏变速（保持音高）连续播放，并配合节拍器帮你踩点跑。'
                    '完全离线运行，音乐文件不离开你的设备。',
                    style: theme.textTheme.bodyMedium,
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),

          // 最新版本公告
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('最新版本公告', style: theme.textTheme.titleSmall),
                  const SizedBox(height: 8),
                  if (_loading)
                    Text(
                      '正在检查最新版本…',
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: theme.colorScheme.outline,
                      ),
                    )
                  else if (_latest == null)
                    Text(
                      '获取最新版本信息失败，请确认网络后再试。',
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: theme.colorScheme.outline,
                      ),
                    )
                  else ...[
                    Text(
                      'v${_latest!.latestVersion}',
                      style: theme.textTheme.titleMedium
                          ?.copyWith(fontWeight: FontWeight.bold),
                    ),
                    if (_latest!.publishedAt != null) ...[
                      const SizedBox(height: 4),
                      Text(
                        '更新时间：${_formatTime(_latest!.publishedAt!)}',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.outline,
                        ),
                      ),
                    ],
                    if (_latest!.releaseNotes != null) ...[
                      const SizedBox(height: 8),
                      Text(
                        _latest!.releaseNotes!,
                        style: theme.textTheme.bodyMedium,
                      ),
                    ],
                  ],
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),

          // 致谢与开源
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('致谢与开源', style: theme.textTheme.titleSmall),
                  const SizedBox(height: 8),
                  Text(
                    '基于 Flutter 构建，播放与变速使用 just_audio / ExoPlayer，'
                    'BPM 分析使用本地频谱（FFT）实现。界面与交互对齐 Web 版 RUN BPM。',
                    style: theme.textTheme.bodyMedium,
                  ),
                ],
              ),
            ),
          ),

          // 检查更新 / 查看源代码
          const SizedBox(height: 16),
          Card(
            child: Column(
              children: [
                ListTile(
                  leading: const Icon(Icons.system_update_alt),
                  title: const Text('检查更新'),
                  subtitle: const Text('从 GitHub Releases 检查并安装新版本'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => UpdateFlow.checkAndPrompt(context),
                ),
                const Divider(height: 1),
                ListTile(
                  leading: const Icon(Icons.code),
                  title: const Text('查看 GitHub 源代码'),
                  subtitle: const Text('在浏览器中查看项目源码与更新记录'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => _openRepo(context),
                ),
              ],
            ),
          ),

          const SizedBox(height: 24),
          Center(
            child: Text(
              '仅供学习与个人使用\n本 App 不收集任何用户数据',
              textAlign: TextAlign.center,
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.outline),
            ),
          ),
        ],
      ),
    );
  }

  /// 打开 GitHub 项目主页（系统浏览器）。
  Future<void> _openRepo(BuildContext context) async {
    final uri = Uri.parse('https://github.com/Sweejumn/muzrun');
    try {
      final ok = await launchUrl(uri, mode: LaunchMode.externalApplication);
      if (!ok && context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('无法打开浏览器，请稍后再试')),
        );
      }
    } catch (_) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('无法打开链接：$uri')),
        );
      }
    }
  }

  /// 格式化发布时间为本地日期时间串。
  String _formatTime(DateTime t) {
    String two(int n) => n.toString().padLeft(2, '0');
    return '${t.year}-${two(t.month)}-${two(t.day)} '
        '${two(t.hour)}:${two(t.minute)}';
  }
}
