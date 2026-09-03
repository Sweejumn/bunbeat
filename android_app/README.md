# RUN BPM · 安卓版（本地文件夹离线播放器）

这是原 Web 版「RUN BPM · 基于 BPM 的跑步音乐播放器」的 **安卓离线版**。
与 Web 版需要上传音乐到后端不同，本 App **直接读取你手机本地的音乐文件夹**，
全部处理（BPM 识别、变速、节拍器、播放）都在设备端完成，**无需联网、无需上传**。

## 功能

- 📁 **本地文件夹直读**：内置文件夹选择器（Android SAF）选一个文件夹后，通过 **MediaStore（on_audio_query）** 读取其中的 MP3 / WAV / M4A / FLAC / OGG 等音频。Android 10+ 的分区存储下 `dart:io` 无法直接枚举共享目录，故改用 MediaStore 查询 + 真实绝对路径（`SongModel.data`），直接读取并播放
- 🔍 **设备端 BPM 分析**：纯 Dart 实现的 FFT + 自相关分析（移植自后端的 librosa 逻辑），解析歌曲 BPM 与拍点，含八度 / 脉冲修正，也支持手动修改 BPM。**分析在后台 isolate 执行，不阻塞界面，避免卡顿**
- 🏃 **运动模式**：走路 / 慢跑 / 跑步 / 快跑 / 自定义，目标 BPM 可滑杆微调
- ⭐ **推荐**：按 `|原BPM − 目标BPM|` 距离排序并给出星级，规则透明可解释
- ⏩ **变速（保持音高）**：基于 ExoPlayer 的 `speed`（time-stretch），把 0.5–2.0 倍内的歌曲统一到目标 BPM，**音高不变**
- 🥁 **节拍器**：与当前歌曲真实拍点（或等距网格）逐拍对齐的前瞻调度器，带音量调节
- ▶️ **播放器**：播放 / 暂停 / 上一首 / 下一首、进度拖动、原始与目标 BPM 显示、连续播放

## 技术栈

| 层 | 技术 |
|---|---|
| 框架 | Flutter (Dart) |
| 播放 / 变速 | just_audio（ExoPlayer，变速默认保持音高） |
| 文件夹选择 | file_picker（Android SAF 目录选择器） |
| 扫描音频 | on_audio_query（MediaStore 查询，兼容分区存储） |
| 音频解码（用于 BPM） | ffmpeg_kit_flutter_audio → PCM 后本地分析 |
| BPM 分析 | 纯 Dart FFT + 自相关（后台 isolate，不卡 UI） |
| 状态管理 | Provider |

## 环境要求

- Flutter SDK ≥ 3.13（含 Android toolchain）
- Android SDK（建议 compileSdk 34 / minSdk 24 / targetSdk 34）
- JDK 17+

> 当前开发机没有装 Flutter / Android SDK，因此这里交付的是 **完整源码工程**，
> 你需要在自己电脑上安装 Flutter 后编译出 APK（见下）。

## 安装与构建（在你自己电脑上）

```bash
# 1. 进入本目录
cd android_app

# 2.（可选）用 Flutter 自动补齐/校正 Android 平台脚手架与图标
#    会保留已有代码与 pubspec，只补齐缺漏的 android/ 文件
flutter create --platforms=android --org com.example .

# 3. 拉取依赖
flutter pub get

# 4. 构建 Debug APK（用于调试）
flutter build apk --debug

# 5. 构建 Release APK
flutter build apk --release
```

生成的 APK 在 `build/app/outputs/flutter-apk/app-release.apk`，
用数据线连接手机（开启 USB 调试）后 `flutter install` 或直接复制 APK 安装。

> `ffmpeg_kit_flutter_audio` 依赖较大（内置 FFmpeg 二进制），首次构建会下载较多内容，请保持网络畅通。

> ⚠️ **FFmpeg 插件补丁（重要，共 3 处）**：原 `ffmpeg_kit_flutter_audio` 插件已无法在 2026 年的工具链下直接使用，
> 本工程已对其打补丁。若你在**新机器 / 清空过 Pub 缓存**后重建，需对
> `%LOCALAPPDATA%\Pub\Cache\hosted\pub.dev\ffmpeg_kit_flutter_audio-6.0.3\android\` 下的文件同样做以下修改：
>
> 1. **build.gradle — Maven 坐标**：`com.arthenica:ffmpeg-kit-audio:6.0-2` 已于 2025-04-01 被移出 Maven Central，
>    改为社区维护版 `implementation 'dev.ffmpegkit-maintained:ffmpeg-kit-audio:6.0.3'`（API 一致）。
> 2. **build.gradle — 补 smart-exception 依赖**：该维护版 AAR 的 POM 不会传递引入 `smart-exception-java`，
>    而 `FFmpegKitConfig.<clinit>` 启动时会引用 `com.arthenica.smartexception.java.Exceptions`，缺失会导致
>    安装后闪退（`NoClassDefFoundError` → SIGSEGV）。需显式加上
>    `implementation 'com.arthenica:smart-exception-java:0.2.1'`。
> 3. **build.gradle — compileSdk 升级到 36**：把 `compileSdkVersion 33` 改为 `36`（老值会触发 CheckAarMetadata 失败）。
> 4. **FFmpegKitFlutterPlugin.java — 移除 v1 embedding**：删除引用已移除类 `PluginRegistry.Registrar` 的
>    `registerWith(...)` 方法及 `init(...)` 的 Registrar 参数（新 Flutter 下编译会报「找不到符号 Registrar」）。
>
> 另：`android/app/build.gradle.kts` 已关闭 release 的 R8 minify/shrink（ffmpeg 原生库 + 反射会触发 R8 missing-class），
> 并固定 `compileSdk = 37`，且显式 `implementation("com.arthenica:smart-exception-java:0.2.1")` 打包保证启动不闪退。
> `file_picker` / `just_audio` / `audio_session` 的 compileSdk 也已补丁到 36。

> ⚠️ **on_audio_query_android 插件补丁（重要，在新机器重建时常做）**：新版工具链（AGP 9 + 内置 Kotlin）要求
> 插件带 `namespace` 且 JVM target 一致。若重建时报错，需对
> `%LOCALAPPDATA%\Pub\Cache\hosted\pub.dev\on_audio_query_android-1.1.0\android\build.gradle` 做：
> 1. 顶部加 `namespace 'com.lucasjosino.on_audio_query'`；
> 2. `compileSdkVersion` 升到 36；
> 3. 加 `compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }`
>    与 `kotlinOptions { jvmTarget = '11' }`（否则报「Inconsistent JVM Target」）。

## 使用方法

1. 打开 App，点「选择音乐文件夹」，通过系统目录选择器挑选你放音乐的文件夹
2. App 递归扫描并逐个分析每首歌的 BPM（显示进度「分析中…」）
3. 分析完成后每首歌显示 BPM 与可信度；不准确的可在右侧点 ✏️ 手动修改
4. 到「推荐」页选运动模式、微调目标 BPM，勾选想听的歌，点「变速并播放」
5. 到「播放」页开始连续播放；可在播放页开关节拍器并调节音量

## 项目结构

```
android_app/
├── pubspec.yaml               # 依赖
├── android/                   # Android 平台脚手架（gradle / manifest / 图标）
└── lib/
    ├── main.dart              # 入口 + Provider 装配
    ├── models/
    │   ├── modes.dart          # 运动模式与目标 BPM 预设
    │   └── song.dart           # Song / Recommendation
    ├── services/
    │   ├── audio_reader.dart   # 文件夹选择 + MediaStore 扫描音频
    │   ├── ffmpeg/audio_decode.dart  # ffmpeg 解码为 PCM WAV
    │   ├── bpm_analyzer.dart   # 设备端 BPM 分析（FFT + 自相关）
    │   ├── fft.dart            # 纯 Dart FFT
    │   ├── library_service.dart# 曲库状态 + 分析 + 推荐
    │   ├── audio_player_service.dart # just_audio 播放 + 变速
    │   ├── queue_service.dart  # 播放队列状态
    │   └── metronome.dart      # 节拍器（前瞻调度）
    └── ui/
        ├── home_page.dart      # 底部导航（曲库 / 推荐 / 播放）
        ├── library_page.dart   # 选文件夹 + 歌曲列表 + 手动改 BPM
        ├── recommend_page.dart # 运动模式 + 推荐 + 入队
        └── player_page.dart    # 播放器 + 节拍器
```

## 常见问题

- **打了音乐文件夹却显示 0 首**：Android 10+ 分区存储下无法用 `dart:io` 直接枚举共享目录。
  已改用 **on_audio_query（MediaStore）** 按所选文件夹前缀过滤扫描，需授予「音乐和音频」权限
  （`READ_MEDIA_AUDIO`，Android 13+）才会返回结果；授权后重新选一次文件夹即可。
- **解析音乐时卡顿**：BPM 分析使用后台 isolate（`compute`）执行重型 FFT，已不阻塞主线程。
  若仍感缓慢，大多是解码长音频耗时，属正常（分析前 60 秒加速）。
- **BPM 分析不准 / 报错**：设备端分析是 FFT 自相关简化实现，对很自由/很短的音频可能不准；
  可手动点击 ✏️ 输入 BPM。分析前会先用 ffmpeg 解码一小段，格式不支持时会明确报错。
- **八卦/变速要求过高**：`just_audio` 的 `speed` 在 0.5–2.0 外的效果会明显失真，
  代码已把播放速率限制在 0.5–2.0，请选择更接近原始 BPM 的目标。
- **节拍器没声音**：节拍器用生成的短暂嗒声文件混入，需在「播放」页打开开关并确认音量；首次打开会先生成嗒声文件。

## 与 Web 版的关系

- **Web 版**：上传音乐 → 后端 librosa 分析 → FFmpeg 变速 → 浏览器播放（需运行 FastAPI 后端）。
- **安卓版（本目录）**：本地读文件夹 → 设备端 FFT 分析 → ExoPlayer 变速 → 本地播放，全离线。

两者共享相同的运动模式、推荐规则（距离 + 星级）与界面流程，只是把能力全部移到设备端。
