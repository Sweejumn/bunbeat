import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';

/// 关于页：应用名、版本、简介、免责声明、致谢与使用说明入口。
class AboutPage extends StatefulWidget {
  const AboutPage({super.key});

  @override
  State<AboutPage> createState() => _AboutPageState();
}

class _AboutPageState extends State<AboutPage> {
  String? _version;

  @override
  void initState() {
    super.initState();
    _loadVersion();
  }

  Future<void> _loadVersion() async {
    final info = await PackageInfo.fromPlatform();
    if (mounted) {
      setState(() => _version = '${info.version}+${info.buildNumber}');
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
          // 图标与名称
          Center(
            child: Column(
              children: [
                Container(
                  width: 88,
                  height: 88,
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(
                      colors: [Color(0xFF34D399), Color(0xFF38BDF8)],
                    ),
                    borderRadius: BorderRadius.circular(22),
                  ),
                  child: const Icon(Icons.directions_run,
                      size: 52, color: Colors.white),
                ),
                const SizedBox(height: 12),
                Text('RUN BPM',
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
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('简介', style: theme.textTheme.titleSmall),
                  const SizedBox(height: 8),
                  Text(
                    'RUN BPM 是一款跑步音乐播放器：选择本地文件夹直接读取音乐，自动识别 BPM，'
                    '按你选的节奏变速（保持音高）连续播放，并配合节拍器帮你踩点跑。'
                    '完全离线运行，音乐文件不离开你的设备。',
                    style: theme.textTheme.bodyMedium,
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('跑步安全提示', style: theme.textTheme.titleSmall),
                  const SizedBox(height: 8),
                  Text(
                    '跑步时请留意周围环境与交通，避免在危险路段使用耳机听音。'
                    '本应用仅提供节奏陪伴与音乐播放，不构成任何运动或健康建议。'
                    '训练强度请量力而行，如有不适请及时停止。',
                    style: theme.textTheme.bodyMedium,
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 12),
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
}
