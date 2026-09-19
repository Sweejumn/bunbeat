# Bunbeat 原生版（Kotlin + Jetpack Compose）

这是 `android_app/`（Flutter 版）的**原生 Kotlin 复刻**：同样的功能、同样的界面语义、
同样的算法，但不依赖 Flutter 运行时。

## 为什么要有它

- Flutter 版把 ffmpeg-kit、just_audio 等一整套原生库打进 APK，体积 100 MB 左右；
  原生版直接用平台 API（MediaStore / MediaCodec / ExoPlayer），APK 小一个数量级。
- BPM 引擎已经是 Kotlin（`kotlin_bpm/`，与 Dart 版做过逐位一致性验证），
  原生版直接把同一份源码编进 APK，不存在「两个实现漂移」的问题。

## 结构

```
native_app/
  app/src/main/java/com/bunbeat/nativeapp/
    MainActivity.kt        # 应用外壳：导航、SAF 目录选择、Snackbar、更新弹窗
    AppState.kt            # 全局状态（对应 Flutter 版的 MultiProvider）
    bpm/                   # BPM 引擎（直接来自 kotlin_bpm/，逐位一致性已验证）
    model/Song.kt          # Song / BeatMode / Recommendation
    core/Prefs.kt          # SharedPreferences 封装
    audio/                 # 文件夹扫描、解码、分析缓存、NCM 解密、分析管线
    store/                 # LibraryStore / QueueStore / SettingsStore
    player/                # ExoPlayer 封装、节拍器、节拍尺
    ui/                    # Compose 页面与公共组件
```

## 与 Flutter 版的对应关系（移植映射）

| Flutter 依赖 | 原生替代 |
| --- | --- |
| `just_audio` | `androidx.media3.exoplayer`（`PlaybackParameters` 做保持音高的变速） |
| `ffmpeg_kit_flutter_audio` | `MediaExtractor` + `MediaCodec`（解码为 22050 Hz 单声道 PCM） |
| `on_audio_query` | `MediaStore.Audio` 查询 |
| `file_picker`（目录选择） | `ACTION_OPEN_DOCUMENT_TREE` + `DocumentFile` |
| `shared_preferences` | `SharedPreferences` |
| `dio` | `HttpURLConnection` |
| `package_info_plus` | `PackageManager.getPackageInfo` |
| `pointycastle`（NCM AES） | `javax.crypto`（AES-128-ECB） |
| `provider` | `AppState` + Compose `mutableStateOf` |

## 更新通道

原生版的 applicationId 是 `com.bunbeat.nativeapp`，与 Flutter 版并存安装互不影响。

- 原生版更新检查**只认 tag 以 `native-` 开头**的 Release（例如 `native-v0.2.0+3`），
  所以绝不会把 Flutter 版的 APK 当成自己的更新。
- 原生版的 Release **必须发布为 pre-release**：Flutter 版只查 `releases/latest`，
  如果原生版成为「最新正式发布」，Flutter 版就会把原生版 APK 提示成更新、
  装出第二个 App。发成 pre-release 可以让 `releases/latest` 始终指向 Flutter 版
  （Kotlin 引擎版 `kotlin-bpm-v1.0.0` 出于同样理由也是 pre-release）。

## 构建

```powershell
cd native_app
.\gradlew.bat :app:assembleDebug     # 调试包
.\gradlew.bat :app:assembleRelease   # 发布包（与 Flutter 版一致用 debug 签名，便于直装验证）
```

`app/build.gradle.kts` 里 compileSdk 37 / buildToolsVersion 36.0.0 是按本机 SDK 现状钉住的，
换机器时按需调整。

## 实机验证（与 Flutter 版逐项对比）

验证方式：同一台模拟器（`emulator-5554`），同一个音乐目录 `/sdcard/Music/RunBpmTest`
（11 首真实 MP3），先装 Flutter 版（debug 包，便于 `run-as` 取数据）跑完整分析，
再装原生版跑一遍，然后逐字段 diff 两边写出的分析缓存 JSON。

- **缓存 id 完全一致**：`stableId`（路径 hash，`h*31+code & 0x7fffffff` 转 8 位十六进制）
  在两边的产物文件名逐一对得上，说明「同一首歌两边认成同一个 id」。
- **BPM 结果**：11 首里 10 首偏差 ≤ 0.036 BPM（相对 ≤ 0.024%），
  1 首（`1391ff1e`）偏差 0.72 BPM；时长、拍点数量、`confidence` 基本一致。
  残余偏差来自解码器不同（Flutter 版用 ffmpeg-kit，原生版用 `MediaCodec`），
  同一份 PCM 输入下 BPM 引擎本身是与 Dart 版逐位一致的（见 `kotlin_bpm/` 的一致性验证）。
- **UI**：曲库（扫描/分析进度、每首歌的「BPM · 可信度 · 算法N」）、推荐（模式区间、
  分级符号 `=`/`↑`/`↓`/`✕` 与百分比）、设置（主题/主题色/BPM 显示）、关于（版本、
  公告、检查更新、GitHub 入口）均已在模拟器上实际打开核对。

对比数据留在 `research_tmp/native_parity/`（`dart_cache_v5/`、`native_cache/`、`compare_mp3.json`）。

## 移植契约

并行移植期间的接口约定保留在 `PORT_CONTRACT.md`，便于后续扩展时保持模块边界一致；
其中的「编译约定」一节记录了移植中真实踩过的几个 Kotlin/Compose 坑，改代码前值得先读。
