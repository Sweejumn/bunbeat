# Bunbeat 原生版（Kotlin + Compose）接口契约

本文件是**并行移植的强制接口约定**。所有 Kotlin 代码写在：

```
native_app/app/src/main/java/com/bunbeat/nativeapp/
```

源真值（Source of Truth）永远是 Flutter 版：

```
android_app/lib/
```

## 0. 总原则

1. **行为对齐 Flutter 版**：所有 UI 文案、提示语、默认值、持久化键名、算法细节都必须与
   `android_app/lib/` 下的 Dart 代码一致。UI 中文文案**逐字照抄**，不要自己改写。
2. **技术栈固定**：Jetpack Compose + Material3（`androidx.compose.material3`）+ media3 ExoPlayer。
   **不要**新增任何 Gradle 依赖（`app/build.gradle.kts` 已定稿，改它会导致构建失败）。
   已可用：compose ui/foundation/material3/material-icons-extended、activity-compose、
   lifecycle-runtime-ktx、lifecycle-viewmodel-compose、documentfile、media3-exoplayer 1.4.1、
   kotlinx-coroutines-android、androidx.core-ktx。**没有** coil / glide / okhttp / gson /
   kotlinx-serialization —— 图片用 `BitmapFactory`，JSON 用 `org.json`（Android 自带），
   网络用 `HttpURLConnection`（`org.json` 与 `java.net` 都是平台自带，可直接用）。
3. **注释用中文**，风格对齐 Dart 版注释（说明「为什么」而不是复述代码）。
4. **只写自己负责的文件**，不要动别人的文件，不要动 `build.gradle.kts` / `AndroidManifest.xml` /
   `res/`。确实需要时，在最终报告里写清楚「需要谁改什么」。
5. 编译由主控统一做（各模块并行期间工程编译不过属正常）。因此代码必须**格外注意**
   导入是否齐全、类型是否精确匹配契约。不要写「大概能用」的东西。
6. Kotlin 版本 2.4.0 / JVM target 17 / minSdk 24 / compileSdk 37。可用 Java 17 语法。
   协程用 `kotlinx.coroutines`（`Dispatchers.IO` 做 IO / 解码，`Dispatchers.Default` 做 DSP）。
7. Compose 状态：所有可变状态用 `androidx.compose.runtime.mutableStateOf` + `by`，
   集合用 `mutableStateListOf`。类里的属性要 `import androidx.compose.runtime.getValue/setValue`。

## 1. 已存在的基础类型（不要重新定义，直接用）

### `model/Song.kt`（package `com.bunbeat.nativeapp.model`）

```kotlin
enum class BpmStatus { PENDING, ANALYZING, DONE, FAILED }

enum class BeatMode(val key: String, val label: String, val desc: String) {
    GRID("grid", "固定拍子", "完全等距 · 默认推荐"),
    SNAP("snap", "跟随起音", "±12% 吸附打击点");
    companion object { val all: List<BeatMode>; fun fromKey(key: String?): BeatMode }
}

data class Song(
    val id: String, val filePath: String, val filename: String, val title: String, val artist: String,
    val duration: Double? = null, val originalBpm: Double? = null, val bpmConfidence: Double? = null,
    val bpmStatus: BpmStatus = BpmStatus.PENDING, val bpmError: String? = null,
    val beatOffset: Double? = null, val beatTimes: List<Double>? = null,
    val beatMaps: Map<String, List<Double>>? = null, val phaseReliability: Double? = null,
    val algorithm: Int? = null, val byAlgorithm: Map<String, Double> = emptyMap(),
    val artworkPath: String? = null,
) { val hasBpm: Boolean; fun displayTitle(): String }

data class Recommendation(val song: Song, val distance: Double, val score: Int)
```

`Song` 是**不可变**的：更新要 `song.copy(...)`，然后通过 `LibraryStore.updateSong(newSong)` 回写。

### `core/Prefs.kt`（package `com.bunbeat.nativeapp.core`）

```kotlin
class Prefs(context: Context) {
    fun getString(key: String, def: String? = null): String?
    fun putString(key: String, value: String?)
    fun getDouble(key: String, def: Double): Double
    fun putDouble(key: String, value: Double)
    fun getInt(key: String, def: Int): Int
    fun putInt(key: String, value: Int)
    fun getBool(key: String, def: Boolean): Boolean
    fun putBool(key: String, value: Boolean)
    fun getStringList(key: String): List<String>
    fun putStringList(key: String, values: List<String>)
    fun remove(key: String)
    fun contains(key: String): Boolean
}
```

### `ui/theme/Theme.kt`（package `com.bunbeat.nativeapp.ui.theme`）

```kotlin
data class ThemeColorOption(val name: String, val color: Color)
val kThemeColors: List<ThemeColorOption>          // 与 Dart kThemeColors 一致（9 个）
object BunbeatColors { val bg, surface, surfaceAlt, textPrimary, textSecondary, divider, ok, warn, err: Color
                       val bgLight, surfaceLight, surfaceAltLight, textPrimaryLight, textSecondaryLight, dividerLight: Color }
fun seedColorScheme(seed: Color, dark: Boolean): ColorScheme
@Composable fun BunbeatTheme(seed: Color, darkMode: Boolean? = null, content: @Composable () -> Unit)
@Composable fun isDarkTheme(): Boolean
```

页面颜色**一律走 `MaterialTheme.colorScheme.*`**（primary/surface/onSurface/surfaceVariant/
onSurfaceVariant/error/outline），需要语义色（成功/警告）时用 `BunbeatColors.ok/warn`。

### `bpm/`（package `com.bunbeat.bpm`，已从 kotlin_bpm 移植并做过逐位一致性验证）

```kotlin
class BpmResult(val bpm: Double? = null, val confidence: Double = 0.0, val duration: Double? = null,
                val error: String? = null, val beatOffset: Double? = null, val beatTimes: List<Double>? = null,
                val beatMaps: Map<String, List<Double>>? = null, val phaseReliability: Double? = null)

object BpmAnalyzer {
    const val kSampleRate: Int = 22050
    const val kActiveAlgorithm: Int = 5
    fun analyzePcm(samples: DoubleArray, sampleRate: Int): BpmResult   // 同步、CPU 密集，调用方放到后台线程
    fun analyzeWavFile(wavPath: String): BpmResult
}
```

## 2. 各模块必须提供的 API（签名不可改）

### A. 音频层（owner: A）

```kotlin
// audio/AudioReader.kt  package com.bunbeat.nativeapp.audio
val kAudioExtensions: Set<String>   // 与 Dart kAudioExtensions 完全一致
data class FolderPick(val path: String?, val audioFiles: List<String>, val errors: List<String>) {
    val cancelled: Boolean
}
object AudioReader {
    fun normalizeFolderPath(raw: String): String          // 与 Dart normalizeFolderPath 行为一致
    suspend fun scanFolder(context: Context, rawFolder: String, withSubfolders: Boolean = true): FolderPick
    suspend fun scanTree(context: Context, treeUri: Uri, withSubfolders: Boolean = true): FolderPick
}

// audio/AudioDecoder.kt
object AudioDecoder {
    const val SAMPLE_RATE: Int = 22050
    suspend fun decodeToPcm(context: Context, path: String): DoubleArray?   // 22050Hz 单声道，归一 -1..1
    suspend fun probeDuration(context: Context, path: String): Double?      // 秒
    suspend fun extractArtwork(context: Context, path: String): String?     // 返回落地后的封面文件路径
}

// audio/AnalysisCache.kt
class AnalysisCache(context: Context) {
    data class Cached(
        val bpm: Double?, val confidence: Double, val duration: Double?, val beatOffset: Double?,
        val beatTimes: List<Double>?, val beatMaps: Map<String, List<Double>>?, val phaseReliability: Double?,
        val manual: Boolean, val artworkFile: String?, val algorithm: Int?, val stale: Boolean,
        val byAlgorithm: Map<String, Double>,
    )
    suspend fun load(song: Song): Cached?
    suspend fun save(song: Song, res: BpmResult, manual: Boolean, artworkFile: String?)
    suspend fun attachArtwork(song: Song, artworkFile: String)
    suspend fun setByAlgorithm(song: Song, algorithm: Int, bpm: Double)
}

// audio/NcmDecoder.kt
object NcmDecoder {
    fun isNcm(path: String): Boolean
    suspend fun decrypt(context: Context, srcPath: String): String?   // 返回解密后可播放文件路径
}

// audio/Analyzer.kt
object Analyzer {
    /** 等价 Dart decodeAndAnalyze：NCM→解密，解码→22050 单声道，引擎→BpmResult，写缓存。 */
    suspend fun analyze(context: Context, cache: AnalysisCache, song: Song, force: Boolean = false): Song
}
```

### B. 状态层（owner: B）

```kotlin
// store/LibraryStore.kt  package com.bunbeat.nativeapp.store
class LibraryStore(context: Context, prefs: Prefs, cache: AnalysisCache, scope: CoroutineScope) {
    val songs: List<Song>                 // 全部歌曲（含归档），已按 Dart 规则排序
    val archivedIds: Set<String>
    val folderPath: String?
    val targetBpm: Double                 // 默认 155
    val scanning: Boolean
    val analyzing: Boolean
    val statusText: String?
    val errors: List<String>
    val currentFolderName: String
    suspend fun loadTargetBpm()
    suspend fun loadArchived()
    suspend fun restoreLastFolder()
    suspend fun openFolder(treeUri: Uri)  // SAF 选中目录后调用
    suspend fun refresh()
    fun setTargetBpm(v: Double)
    fun isArchived(id: String): Boolean
    fun archive(id: String); fun unarchive(id: String)
    fun activeSongs(): List<Song>
    fun archivedSongs(): List<Song>
    fun visibleSongs(showArchived: Boolean, query: String): List<Song>
    fun recommendations(): List<Recommendation>
    fun byId(id: String): Song?
    fun updateSong(song: Song)            // 按 id 替换（分析完成后回写）
    fun removeSong(id: String)
    fun setManualBpm(song: Song, bpm: Double)
    fun analyzeOne(song: Song)
    fun analyzeAll(force: Boolean = false)
}

// store/QueueStore.kt
enum class LoopMode { OFF, ALL, ONE }
class QueueStore(prefs: Prefs, scope: CoroutineScope) {
    val items: List<Song>
    val index: Int
    val loopMode: LoopMode
    val shuffle: Boolean
    val current: Song?
    suspend fun loadFromPrefs()
    fun start(songs: List<Song>, startIndex: Int)
    fun append(songs: List<Song>): Int
    fun appendNext(song: Song)
    fun clear()
    fun removeWhere(pred: (Song) -> Boolean)
    fun syncSong(song: Song)
    fun setIndex(i: Int)
    fun setLoopMode(m: LoopMode)
    fun setShuffle(on: Boolean)
    fun next(): Song?
    fun previous(): Song?
}

// store/SettingsStore.kt
enum class ThemeModeSetting { SYSTEM, LIGHT, DARK }
class SettingsStore(prefs: Prefs) {
    val themeMode: ThemeModeSetting          // 默认 SYSTEM，键 runbpm.themeMode
    val seed: Color                          // 默认 kThemeColors[0].color，键 runbpm.themeColor（#RRGGBB）
    val bpmTwoDecimals: Boolean              // 默认 true，键 runbpm.bpmTwoDecimals
    fun load()
    fun setThemeMode(m: ThemeModeSetting)
    fun setSeed(c: Color)
    fun setTwoDecimals(on: Boolean)
    fun formatBpm(bpm: Double?): String      // 两位小数 或 四舍五入整数，null → "—"
}
```

### C. 播放层（owner: C）

```kotlin
// player/PlayerController.kt  package com.bunbeat.nativeapp.player
class PlayerController(context: Context, prefs: Prefs, cache: AnalysisCache) {
    val exo: ExoPlayer
    val currentSong: Song?
    val isPlaying: Boolean
    val positionSec: Double
    val durationSec: Double
    val speed: Float                     // 变速（pitch 保持不变）
    val volume: Float
    val ready: Boolean
    var onCompleted: (() -> Unit)?       // 播完自动下一首
    fun load(song: Song, autoPlay: Boolean = false)
    fun loadPaused(song: Song)
    fun play(); fun pause(); fun toggle()
    fun seekTo(sec: Double); fun seekBy(deltaSec: Double)
    fun setSpeed(v: Float); fun setVolume(v: Float)
    fun release()
}

// player/Metronome.kt
class Metronome(context: Context) {
    val enabled: Boolean
    val volume: Float
    fun start(bpm: Double, beatTimes: List<Double>?, fromSec: Double, isPlaying: () -> Boolean, positionSec: () -> Double)
    fun stop()
    fun updateBpm(bpm: Double, beatTimes: List<Double>?)
    fun setVolume(v: Float)
    fun release()
}

// player/BeatRuler.kt
@Composable fun BeatRuler(
    beatTimes: List<Double>?, durationSec: Double, positionSec: Double,
    modifier: Modifier = Modifier, onSeek: (Double) -> Unit = {},
)
```

### D/E. 页面（owner 见分工）

```kotlin
// ui/LibraryPage.kt
@Composable fun LibraryPage(app: AppState)
// ui/RecommendPage.kt
@Composable fun RecommendPage(app: AppState)
// ui/PlayerPage.kt
@Composable fun PlayerPage(app: AppState)
// ui/SettingsPage.kt
@Composable fun SettingsPage(app: AppState, onBack: () -> Unit)
// ui/AboutPage.kt
@Composable fun AboutPage(app: AppState, onBack: () -> Unit)
// ui/ArchivePage.kt
@Composable fun ArchivePage(app: AppState, onBack: () -> Unit)
// ui/Components.kt  （由 owner D 维护，其他人可调用）
@Composable fun MarqueeText(text: String, style: TextStyle, modifier: Modifier = Modifier, ...)
@Composable fun TempoGradeBadge(bpm: Double?, modifier: Modifier = Modifier)
@Composable fun SongRow(song: Song, subtitle: String?, trailing: @Composable () -> Unit, onClick: () -> Unit, ...)
@Composable fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)
```

## 3. AppState（主控负责，页面统一从这里取状态）

```kotlin
package com.bunbeat.nativeapp

/** 页面跳转动作：由 MainActivity 注入，页面里一律通过 app.nav 跳转，不要自己拿 Activity/Navigator。 */
class NavActions(
    val openArchive: () -> Unit,
    val openSettings: () -> Unit,
    val openAbout: () -> Unit,
    val back: () -> Unit,
)

class AppState(
    val context: Context,
    val prefs: Prefs,
    val cache: AnalysisCache,
    val library: LibraryStore,
    val queue: QueueStore,
    val settings: SettingsStore,
    val player: PlayerController,
    val metronome: Metronome,
    val update: UpdateController,
    val scope: CoroutineScope,
) {
    val activity: ComponentActivity?          // 需要 SAF 选择器等宿主能力时用
    lateinit var nav: NavActions              // 由 MainActivity 在创建后立即注入
    fun snackbar(msg: String)                 // 底部提示
    val snackbarMessage: String?              // 供 MainActivity 渲染
    fun requestFolderPick()                   // 触发 SAF 目录选择（主控实现）
}

val LocalApp = staticCompositionLocalOf<AppState> { error("AppState 未提供") }

// update/UpdateController.kt
class UpdateController(context: Context, scope: CoroutineScope) {
    val checking: Boolean
    val pending: ReleaseInfo?                 // 有新版时非空 → MainActivity 弹「发现新版本」
    val statusText: String?
    suspend fun check(manual: Boolean)
    fun dismiss()
    fun downloadAndInstall()
}
data class ReleaseInfo(val version: String, val notes: String, val apkUrl: String, val pageUrl: String, val sizeBytes: Long)
```

> 页面需要弹对话框/提示时，用 `app.snackbar("文案")`；不要自己拿 Activity。
> 页面需要「选文件夹」时调用 `app.requestFolderPick()`（主控实现，内部用 SAF 选择器）。

## 4. 交付要求

- 每个文件顶部写一行 `package`，导入按 IDE 习惯排序，**不要**用通配符导入。
- 不要写 TODO / 占位实现；每个函数都要有真实实现。
- 完成后自查：所有引用的类型/函数是否都在本契约或 Dart 源里存在；签名是否逐字一致。
- 报告里写：改了哪些文件、实现了什么、有哪些「需要主控注意」的偏差（例如某 Dart 依赖
  在原生侧没有等价物而做了替代）。

## 5. 编译约定（踩过的坑，务必遵守）

1. **不要用 `/*` 这种序列写注释**。Kotlin 的块注释是**可嵌套**的，KDoc 里写
   `` `audio/*` `` 会让注释永远闭合不了，报 `Syntax error: Unclosed comment`。
   需要写通配符时改写成「mime 以 `audio/` 开头」之类。
2. **属性 + 同名 setter 方法会撞 JVM 签名**。下面这种写法编译不过
   （`Platform declaration clash: setVolume(F)V`）：
   ```kotlin
   var volume by mutableStateOf(1f)
       private set
   fun setVolume(v: Float) { volume = v }   // ← 与属性的私有 setter 撞签名
   ```
   修法：给方法加 `@JvmName`（Kotlin 侧仍然叫 `setVolume`，只是改 JVM 名）：
   ```kotlin
   @kotlin.jvm.JvmName("applyVolume")
   fun setVolume(v: Float) { volume = v }
   ```
   已落盘的 `PlayerController` / `Metronome` / `QueueStore` / `LibraryStore` /
   `SettingsStore` 都按这个约定处理，新代码沿用。
3. `ScrollState.scrollTo` 收的是 **Int 像素**，传 Float 编译不过。
4. `Color.luminance()` 需要 `import androidx.compose.ui.graphics.luminance`。
5. 编译命令（主控统一执行，agent 不要自己跑）：
   `cd native_app; .\gradlew.bat :app:compileDebugKotlin --console=plain`

