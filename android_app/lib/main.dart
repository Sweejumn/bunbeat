import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'services/audio_player_service.dart';
import 'services/library_service.dart';
import 'services/metronome.dart';
import 'services/queue_service.dart';
import 'services/theme_controller.dart';
import 'ui/home_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const RunBpmApp());
}

class RunBpmApp extends StatefulWidget {
  const RunBpmApp({super.key});

  @override
  State<RunBpmApp> createState() => _RunBpmAppState();
}

class _RunBpmAppState extends State<RunBpmApp> {
  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => LibraryService()),
        ChangeNotifierProvider(create: (_) => QueueService()),
        ChangeNotifierProvider(create: (_) => ThemeController()),
        Provider(create: (_) => AudioPlayerService()),
        Provider(create: (_) => Metronome()),
      ],
      child: Consumer<ThemeController>(
        builder: (context, themeCtrl, _) {
          return MaterialApp(
            title: 'RUN BPM',
            debugShowCheckedModeBanner: false,
            theme: ThemeData(
              colorScheme: ColorScheme.fromSeed(
                seedColor: themeCtrl.seed,
              ),
              useMaterial3: true,
            ),
            darkTheme: ThemeData(
              colorScheme: ColorScheme.fromSeed(
                seedColor: themeCtrl.seed,
                brightness: Brightness.dark,
              ),
              useMaterial3: true,
            ),
            themeMode: themeCtrl.mode,
            home: _StartupGate(child: const HomePage()),
          );
        },
      ),
    );
  }
}

/// 在首帧构建后触发一次「恢复上次文件夹」，避免阻塞画面。
class _StartupGate extends StatefulWidget {
  final Widget child;
  const _StartupGate({required this.child});

  @override
  State<_StartupGate> createState() => _StartupGateState();
}

class _StartupGateState extends State<_StartupGate> {
  bool _restored = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_restored) {
      _restored = true;
      // 等首帧画完再恢复，避免启动时白屏等待即时的缓存扫描。
      WidgetsBinding.instance.addPostFrameCallback((_) {
        context.read<LibraryService>().restoreLastFolder();
        context.read<ThemeController>().load();
      });
    }
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
