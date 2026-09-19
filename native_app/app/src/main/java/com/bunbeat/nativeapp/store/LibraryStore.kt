package com.bunbeat.nativeapp.store

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bunbeat.bpm.BpmResult
import com.bunbeat.nativeapp.audio.AnalysisCache
import com.bunbeat.nativeapp.audio.Analyzer
import com.bunbeat.nativeapp.audio.AudioReader
import com.bunbeat.nativeapp.audio.FolderPick
import com.bunbeat.nativeapp.core.Prefs
import com.bunbeat.nativeapp.model.BpmStatus
import com.bunbeat.nativeapp.model.Recommendation
import com.bunbeat.nativeapp.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 曲库状态（等价 Flutter 版 `LibraryService`）。
 *
 * 与 Dart 版的差别只在「表示的细节」上，行为尽量逐条对齐：
 * - Dart 用 `_songs` + `_archived` 两个列表；原生侧只保留**一个** [songs] 列表（含归档），
 *   归档与否由 [archivedIds] 决定。这样 Song 不需要在列表间搬家，`copy()` 回写也不会丢位置。
 * - Song 在原生侧是不可变 data class，所有更新都走 `copy()` + [updateSong]。
 * - 过去的「notifyListeners()」在这里就是给 Compose 状态赋值，页面自动重组。
 *
 * 持久化键名与 Dart 完全一致（`last_folder` / `target_bpm` / `runbpm.archived`），
 * 便于两版对照排查。
 */
class LibraryStore(
    private val context: Context,
    private val prefs: Prefs,
    private val cache: AnalysisCache,
    private val scope: CoroutineScope,
) {
    private companion object {
        /** 上次选择的文件夹（文件系统路径或 SAF tree uri 字符串）。 */
        const val kLastFolder = "last_folder"

        /** 目标 BPM。 */
        const val kTargetBpm = "target_bpm"

        /** 归档歌曲的稳定 id 集合。 */
        const val kArchived = "runbpm.archived"

        /** 目标 BPM 默认值（与 Dart `_targetBpm = 155` 一致）。 */
        const val kDefaultTargetBpm = 155.0

        const val kContentScheme = "content://"
    }

    // ---------------- 可观察状态（全部 private set，页面只读） ----------------

    /** 全部歌曲（含归档），顺序 = 扫描顺序（对齐 Dart 的「默认顺序」）。 */
    var songs: List<Song> by mutableStateOf<List<Song>>(emptyList())
        private set

    /** 归档歌曲的稳定 id 集合（= 源文件路径 hash，重启后仍然稳定）。 */
    var archivedIds: Set<String> by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 当前文件夹（SAF 场景下是 tree uri 字符串）；null 表示从未选择过。 */
    var folderPath: String? by mutableStateOf<String?>(null)
        private set

    /** 当前目标 BPM（推荐与入队用）。修改即持久化，退出后下次启动恢复。 */
    var targetBpm: Double by mutableStateOf(kDefaultTargetBpm)
        private set

    /** 是否正在扫描文件夹。 */
    var scanning: Boolean by mutableStateOf(false)
        private set

    /** 是否还有分析任务在队列里（串行执行，同一时刻只跑一首）。 */
    var analyzing: Boolean by mutableStateOf(false)
        private set

    /** 状态文案（扫描 / 分析进度）；空闲时为 null。 */
    var statusText: String? by mutableStateOf<String?>(null)
        private set

    /** 最近一次扫描 / 分析过程中收集到的错误，仅提示用，不阻断流程。 */
    var errors: List<String> by mutableStateOf<List<String>>(emptyList())
        private set

    /** 当前文件夹的显示名（没有文件夹时为空串），供页面标题使用。 */
    var currentFolderName: String by mutableStateOf("")
        private set

    // ---------------- 分析串行调度（内部） ----------------

    /** 一个待分析任务：只存 id，避免队列持有已经被替换掉的 Song 旧实例。 */
    private data class AnalyzeJob(val id: String, val force: Boolean)

    private val queueLock = Any()
    private val analyzeQueue = ArrayDeque<AnalyzeJob>()
    private var analyzeWorker: Job? = null

    /** 本轮排队分析的进度（用于状态文案）。 */
    private var passTotal = 0
    private var passDone = 0

    // ---------------- 启动恢复 / 持久化 ----------------

    /** 启动时恢复上次保存的目标 BPM（未保存过则保持默认 155）。 */
    suspend fun loadTargetBpm() {
        // 与 Dart 一致：只有 > 0 的值才覆盖默认值。
        val v = prefs.getDouble(kTargetBpm, kDefaultTargetBpm)
        if (v > 0) targetBpm = v
    }

    /** 读取持久化的归档 id 集合（启动时调用，保持归档状态跨启动）。 */
    suspend fun loadArchived() {
        archivedIds = prefs.getStringList(kArchived).toSet()
    }

    /**
     * 启动时恢复上次文件夹并载入（命中缓存即秒开，不重新解析）。
     * 与 Dart 一致：扫描不到任何音频（或从未选过文件夹）时保持现状，不覆盖曲库。
     */
    suspend fun restoreLastFolder() {
        val folder = prefs.getString(kLastFolder)?.trim().orEmpty()
        if (folder.isEmpty()) return
        val pick = scanFolderPath(folder) ?: return
        if (pick.audioFiles.isEmpty()) return
        applyPick(pick)
    }

    // ---------------- 选择 / 扫描 ----------------

    /** SAF 选中目录后调用：扫描该目录并用结果替换曲库。 */
    suspend fun openFolder(treeUri: Uri) {
        scanning = true
        errors = emptyList()
        if (!analyzing) statusText = "正在扫描文件夹…"
        try {
            val pick = AudioReader.scanTree(context, treeUri, withSubfolders = true)
            // SAF 场景下 A 侧可能给不出文件系统路径，这里退化为 tree uri 字符串，
            // 保证 last_folder 记的是「能重新扫描的东西」而不是 null。
            applyPick(
                if (pick.path == null) {
                    FolderPick(path = treeUri.toString(), audioFiles = pick.audioFiles, errors = pick.errors)
                } else {
                    pick
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addError("无法读取文件夹: ${e.message ?: e.javaClass.simpleName}")
            statusText = null
        } finally {
            scanning = false
        }
    }

    /**
     * 重新扫描当前文件夹并对齐曲库：
     * 保留已有分析结果（缓存命中即秒开）、剔除已消失的文件、加入新文件，
     * 旧算法测得的缓存结果排队在后台无感重测。
     */
    suspend fun refresh() {
        val folder = folderPath ?: return
        scanning = true
        errors = emptyList()
        if (!analyzing) statusText = "正在扫描文件夹…"
        try {
            val pick = scanFolderPath(folder) ?: return
            addErrors(pick.errors)
            reconcile(pick.audioFiles)
        } finally {
            scanning = false
            if (!analyzing) statusText = null
        }
    }

    /** 按当前文件夹形态选择扫描方式：SAF tree uri 走 scanTree，其余按文件系统路径扫描。 */
    private suspend fun scanFolderPath(folder: String): FolderPick? {
        return try {
            if (folder.startsWith(kContentScheme, ignoreCase = true)) {
                AudioReader.scanTree(context, Uri.parse(folder), withSubfolders = true)
            } else {
                AudioReader.scanFolder(context, folder, withSubfolders = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            addError("无法读取文件夹: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    /** 记下文件夹、持久化、然后把曲库对齐到扫描结果（对应 Dart `loadFolder`）。 */
    private suspend fun applyPick(pick: FolderPick) {
        val path = pick.path
        folderPath = path
        currentFolderName = folderDisplayName(path)
        if (path == null) prefs.remove(kLastFolder) else prefs.putString(kLastFolder, path)
        addErrors(pick.errors)
        reconcile(pick.audioFiles)
    }

    /**
     * 用扫描到的文件路径列表对齐 [songs]：
     * - 已消失的文件被剔除；
     * - 仍存在的文件复用原 Song 实例（分析结果 / NCM 解密后的路径 / 封面都不丢）；
     * - 新文件以 pending 加入；
     * - 逐个查缓存：命中则秒开写回结果，未命中排队分析，旧算法结果排队后台重测。
     */
    private suspend fun reconcile(paths: List<String>) {
        val previous = songs.associateBy { it.id }
        val next = ArrayList<Song>(paths.size)
        for (p in paths) {
            val id = stableId(p)
            next.add(
                previous[id] ?: Song(
                    id = id,
                    filePath = p,
                    filename = fileName(p),
                    title = fileStem(p),
                    artist = "未知",
                    bpmStatus = BpmStatus.PENDING,
                )
            )
        }
        // 先落地列表：新文件立刻出现（pending），消失的文件立刻消失。
        songs = next

        val toAnalyze = ArrayList<String>()
        val staleIds = ArrayList<String>()
        for (i in next.indices) {
            val song = next[i]
            val scannedPath = paths[i]
            // 正在分析中的不要打断（例如刷新时刚好在跑）。
            if (song.bpmStatus == BpmStatus.ANALYZING) continue

            val cached = try {
                cache.load(song)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

            if (cached == null) {
                // 缓存未命中：新文件 / 源文件已变更 / 缓存被清。
                // 若这首歌此前因为 NCM 解密被改写过路径，这里退回原始扫描路径，
                // 让 Analyzer 从源文件重新解析（重新解密）。
                val target = if (song.filePath == scannedPath) {
                    song
                } else {
                    song.copy(filePath = scannedPath, bpmStatus = BpmStatus.PENDING, bpmError = null)
                }
                if (target !== song) updateSong(target)
                toAnalyze.add(target.id)
                continue
            }

            val restored = withCached(song, cached)
            updateSong(restored)
            // 旧算法结果：不打断用户，排队后台用当前算法重测（手动 BPM 与归档歌曲不重测）。
            if (cached.stale && !cached.manual && !isArchived(restored.id)) staleIds.add(restored.id)
        }

        enqueueAnalyze(toAnalyze, force = false)
        enqueueAnalyze(staleIds, force = true)
    }

    /** 把缓存结果写回 Song（对应 Dart `_loadSong` 的缓存命中分支）。 */
    private fun withCached(song: Song, c: AnalysisCache.Cached): Song = song.copy(
        bpmStatus = BpmStatus.DONE,
        originalBpm = c.bpm,
        bpmConfidence = c.confidence,
        duration = c.duration,
        beatOffset = c.beatOffset,
        beatTimes = c.beatTimes,
        beatMaps = c.beatMaps,
        phaseReliability = c.phaseReliability,
        algorithm = c.algorithm,
        byAlgorithm = c.byAlgorithm,
        // 仅当缓存里有持久化封面才覆盖；否则保留 NCM 解密出的内嵌封面。
        artworkPath = c.artworkFile ?: song.artworkPath,
        bpmError = null,
    )

    // ---------------- 目标 BPM ----------------

    /** 设置目标 BPM 并持久化（与 Dart setter 一致：值没变就不写盘）。 */
    @kotlin.jvm.JvmName("applyTargetBpm")
    fun setTargetBpm(v: Double) {
        if (targetBpm == v) return
        targetBpm = v
        prefs.putDouble(kTargetBpm, v)
    }

    // ---------------- 归档 ----------------

    /** 该 id 是否处于归档状态。 */
    fun isArchived(id: String): Boolean = archivedIds.contains(id)

    /** 归档一首歌：从曲库（与推荐）隐藏，可在归档页放回；立即持久化。 */
    fun archive(id: String) {
        if (archivedIds.contains(id)) return
        archivedIds = archivedIds + id
        persistArchived()
    }

    /** 把一首归档歌曲放回曲库，并持久化。 */
    fun unarchive(id: String) {
        if (!archivedIds.contains(id)) return
        archivedIds = archivedIds - id
        persistArchived()
    }

    private fun persistArchived() {
        prefs.putStringList(kArchived, archivedIds.toList())
    }

    /** 未归档歌曲（曲库页 / 推荐页只应使用这些）。 */
    fun activeSongs(): List<Song> = songs.filter { !archivedIds.contains(it.id) }

    /** 已归档歌曲（归档页）。 */
    fun archivedSongs(): List<Song> = songs.filter { archivedIds.contains(it.id) }

    // ---------------- 列表 / 查询 ----------------

    /**
     * 搜索 + 归档过滤后的可见列表（对应 Dart 曲库页 `_visibleSongs` 的搜索部分）。
     *
     * [showArchived] = false 只看未归档（曲库页）；true 则把归档歌曲一并显示
     * （归档页 / 「显示归档」开关用）。顺序保持扫描顺序，即 Dart 的「默认顺序」。
     */
    fun visibleSongs(showArchived: Boolean, query: String): List<Song> {
        val base = if (showArchived) songs else activeSongs()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return base
        return base.filter {
            it.title.lowercase().contains(q) ||
                it.filename.lowercase().contains(q) ||
                it.artist.lowercase().contains(q)
        }
    }

    /**
     * 规则推荐：按 |原BPM − 目标BPM| 升序，低置信度（< 0.4）距离 +4 靠后，并给 1..5 星。
     * 排序规则与 Dart 一致：距离相同时置信度高的在前。
     */
    fun recommendations(): List<Recommendation> {
        val t = targetBpm
        val scored = activeSongs()
            .filter { it.hasBpm }
            .map { s ->
                var dist = abs((s.originalBpm ?: 0.0) - t)
                if ((s.bpmConfidence ?: 0.0) < 0.4) dist += 4.0
                Recommendation(song = s, distance = dist, score = stars(dist))
            }
        return scored.sortedWith(
            compareBy<Recommendation> { it.distance }
                .thenByDescending { it.song.bpmConfidence ?: 0.0 }
        )
    }

    /** 星级：距离越小星越多（与 Dart `_stars` 完全一致，注意用的是加过惩罚的距离）。 */
    private fun stars(d: Double): Int = when {
        d <= 3 -> 5
        d <= 8 -> 4
        d <= 16 -> 3
        d <= 28 -> 2
        else -> 1
    }

    fun byId(id: String): Song? = songs.firstOrNull { it.id == id }

    /** 按 id 替换（分析完成 / 手动改 BPM 后回写），保持原有位置。 */
    fun updateSong(song: Song) {
        val list = songs
        val i = list.indexOfFirst { it.id == song.id }
        if (i < 0) return
        val updated = list.toMutableList()
        updated[i] = song
        songs = updated
    }

    /** 从曲库移除一首歌（归档 id 保留：id 是路径 hash，文件回来时归档状态仍然有效）。 */
    fun removeSong(id: String) {
        songs = songs.filterNot { it.id == id }
    }

    // ---------------- 分析 ----------------

    /**
     * 手动修改某首歌的 BPM（持久化，重启后仍保留）。
     * 与 Dart 一致：保留已缓存的拍点 / 封面文件名，并写 manual=true，
     * 下次走缓存时不会用自动分析结果覆盖用户手填的值。
     */
    fun setManualBpm(song: Song, bpm: Double) {
        val updated = song.copy(
            originalBpm = bpm,
            bpmConfidence = 1.0,
            bpmStatus = BpmStatus.DONE,
            bpmError = null,
        )
        updateSong(updated)
        scope.launch {
            try {
                val cached = cache.load(updated)
                cache.save(
                    updated,
                    BpmResult(
                        bpm = bpm,
                        confidence = 1.0,
                        duration = updated.duration,
                        beatOffset = updated.beatOffset,
                        beatTimes = updated.beatTimes,
                        beatMaps = cached?.beatMaps ?: updated.beatMaps,
                        phaseReliability = cached?.phaseReliability ?: updated.phaseReliability,
                    ),
                    manual = true,
                    artworkFile = cached?.artworkFile,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addError("写入缓存失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** 重新分析一首歌（对应 Dart `retryAnalyze`：先重置为 pending 再排队重测）。 */
    fun analyzeOne(song: Song) {
        updateSong(song.copy(bpmStatus = BpmStatus.PENDING, bpmError = null))
        enqueueAnalyze(listOf(song.id), force = true)
    }

    /** 分析全部未归档歌曲；[force] = true 表示忽略缓存重新测量。 */
    fun analyzeAll(force: Boolean = false) {
        enqueueAnalyze(activeSongs().map { it.id }, force)
    }

    /** 排队分析；同一首歌在队列里只保留一次。队列由单个 worker 串行消费。 */
    private fun enqueueAnalyze(ids: List<String>, force: Boolean) {
        if (ids.isEmpty()) return
        var startWorker = false
        synchronized(queueLock) {
            for (id in ids) {
                if (analyzeQueue.any { it.id == id }) continue
                analyzeQueue.addLast(AnalyzeJob(id, force))
                passTotal++
            }
            if (analyzeQueue.isNotEmpty() && analyzeWorker?.isActive != true) startWorker = true
        }
        if (startWorker) {
            analyzing = true
            updateAnalyzeStatus()
            val job = scope.launch { drainAnalyzeQueue() }
            synchronized(queueLock) { analyzeWorker = job }
        }
    }

    /** 串行消费分析队列：一次只分析一首，避免多首同时解码把 CPU 抢满。 */
    private suspend fun drainAnalyzeQueue() {
        analyzing = true
        try {
            while (true) {
                val item = synchronized(queueLock) { analyzeQueue.removeFirstOrNull() } ?: break
                // 每首歌重新取一次最新实例：排队期间它可能已被更新或移除。
                val song = byId(item.id)
                if (song != null) runAnalyze(song, item.force)
                synchronized(queueLock) { passDone++ }
                updateAnalyzeStatus()
            }
        } finally {
            // 收尾瞬间可能又排进了新任务：队列非空就让新的 worker 接着消费，避免丢任务。
            val leftover = synchronized(queueLock) {
                if (analyzeQueue.isEmpty()) {
                    passTotal = 0
                    passDone = 0
                }
                analyzeQueue.isNotEmpty()
            }
            if (leftover) {
                val job = scope.launch { drainAnalyzeQueue() }
                synchronized(queueLock) { analyzeWorker = job }
            } else {
                analyzing = false
                statusText = null
            }
        }
    }

    private fun updateAnalyzeStatus() {
        val progress = synchronized(queueLock) { passDone to passTotal }
        statusText = if (progress.second <= 0) null else "正在分析 BPM（${progress.first}/${progress.second}）…"
    }

    /**
     * 实际执行一首歌的分析（对应 Dart `_analyze`）。
     * 解码 / 引擎 / 写缓存 / 封面落地都由 [Analyzer] 负责，这里只管状态与串行调度。
     */
    private suspend fun runAnalyze(song: Song, force: Boolean) {
        // 先翻成 analyzing，让 UI 立刻有反馈（Dart 在 _analyze 开头做同样的事）。
        updateSong(song.copy(bpmStatus = BpmStatus.ANALYZING, bpmError = null))
        val current = byId(song.id) ?: return
        val result = try {
            Analyzer.analyze(context, cache, current, force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "分析失败: ${e.message ?: e.javaClass.simpleName}"
            addError(msg)
            current.copy(bpmStatus = BpmStatus.FAILED, bpmError = msg)
        }
        updateSong(result)
    }

    // ---------------- 工具 ----------------

    /** 稳定的 id（路径 hash），与 Dart `_stableId` 逐位一致（取低 31 位后转 16 进制补 8 位）。 */
    private fun stableId(s: String): String {
        var h = 0L
        for (ch in s) {
            // Kotlin 的 Char 就是 UTF-16 code unit，和 Dart 的 String.codeUnits 一致。
            h = (h * 31 + ch.code) and 0x7fffffffL
        }
        return h.toString(16).padStart(8, '0')
    }

    private fun fileName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    /** 去扩展名的文件名（Dart `p.basenameWithoutExtension`）。 */
    private fun fileStem(path: String): String {
        val name = fileName(path)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** 文件夹显示名：SAF tree uri 先交给 AudioReader 归一化成真实路径再取末段。 */
    private fun folderDisplayName(path: String?): String {
        if (path.isNullOrBlank()) return ""
        val normalized = try {
            AudioReader.normalizeFolderPath(path)
        } catch (e: Exception) {
            path
        }
        val trimmed = normalized.trimEnd('/', '\\')
        val name = trimmed.substringAfterLast('/').substringAfterLast('\\')
        return name.ifBlank { trimmed }
    }

    private fun addErrors(list: List<String>) {
        if (list.isEmpty()) return
        errors = (errors + list).distinct()
    }

    private fun addError(msg: String) = addErrors(listOf(msg))
}
