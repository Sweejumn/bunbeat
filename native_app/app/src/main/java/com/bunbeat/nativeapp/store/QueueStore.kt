package com.bunbeat.nativeapp.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bunbeat.nativeapp.core.Prefs
import com.bunbeat.nativeapp.model.BpmStatus
import com.bunbeat.nativeapp.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** 播放循环模式（对应 Dart `LoopMode`：off / all / one）。 */
enum class LoopMode { OFF, ALL, ONE }

/**
 * 当前播放队列（对应 Dart `QueueService`）。
 *
 * 与 Dart 的差别只有「承载类型」：Dart 的队列元素是轻量的 `PlaylistItem`
 * （filePath + originalBpm + targetBpm），原生版直接用 [Song]，
 * 因为播放页/节拍器还要读封面、拍点等信息，而 targetBpm 在原生侧是
 * [LibraryStore] 的全局设置（播放时现算速率），不再逐项存储。
 */
class QueueStore(private val prefs: Prefs, private val scope: CoroutineScope) {

    companion object {
        // 持久化键与 Dart 完全一致，便于两版对照排查。
        private const val PREF_QUEUE = "runbpm.queue"
        private const val PREF_QUEUE_INDEX = "runbpm.queueIndex"
        private const val PREF_LOOP_MODE = "runbpm.loopMode"
        private const val PREF_SHUFFLE = "runbpm.shuffle"

        // Dart 用 `LoopMode.name` 存盘（小写），Kotlin 枚举常量是大写，这里显式映射。
        private fun modeKey(m: LoopMode): String = when (m) {
            LoopMode.OFF -> "off"
            LoopMode.ALL -> "all"
            LoopMode.ONE -> "one"
        }

        private fun modeFromKey(key: String?): LoopMode =
            LoopMode.entries.firstOrNull { modeKey(it) == key } ?: LoopMode.ALL

        /**
         * 与 Dart `_stableId` 完全一致的 id（路径 hash）：
         * `h = (h * 31 + code) & 0x7fffffff`，UTF-16 码元，8 位小写十六进制。
         * 这里自带一份私有实现，保证「从 prefs 恢复的 Song」与媒体库扫描出的
         * Song 拥有同一个 id（[syncSong] 才能按 id 对上）。
         */
        private fun stableId(path: String): String {
            var h = 0
            for (ch in path) {
                h = (h * 31 + ch.code) and 0x7fffffff
            }
            return h.toString(16).padStart(8, '0')
        }
    }

    // 队列本身用快照列表，播放页/播放列表直接读它就能重组。
    private val _items = mutableStateListOf<Song>()

    /** 队列内容（只读视图；外部不要试图改，change 一律走本类的方法）。 */
    val items: List<Song> get() = _items

    /** 当前曲在队列中的下标；-1 表示「无当前曲」（队列空）。 */
    var index by mutableStateOf(-1)
        private set

    /** 循环模式，默认 `all`（与 Dart 一致）。 */
    var loopMode by mutableStateOf(LoopMode.ALL)
        private set

    /** 随机播放开关，默认关。 */
    var shuffle by mutableStateOf(false)
        private set

    /** 当前曲；越界或队列为空时为 null（对应 Dart `current`）。 */
    val current: Song?
        get() = if (index >= 0 && index < _items.size) _items[index] else null

    /**
     * 随机播放的历史（只做「避免立刻重复」用，不参与 UI 重组）。
     * Dart 里就是普通 `List<int>`，这里保持非 Compose 状态。
     */
    private val shuffleHistory = mutableListOf<Int>()

    /** 串行化写盘，避免连续操作时后台写入交错、后写的被先写的覆盖。 */
    private val writeLock = Mutex()

    // ---------------------------------------------------------------- 持久化

    /**
     * 启动时从持久化恢复上次的播放队列 + 当前曲 + 循环/随机模式。
     *
     * Dart 存的是 `[{f: filePath, t: targetBpm, o: originalBpm}, ...]`（**不是** id 列表），
     * 恢复时不查媒体库、只认 filePath；这里照做，并额外带上 title/artist/封面等展示字段，
     * 让「媒体库还没扫完」时播放页也能正常显示。等 [LibraryStore] 扫完再用 [syncSong]
     * 回写完整信息。
     *
     * 对应 Dart 的规则：`f` 缺失或为空串的条目直接跳过；整段 JSON 解析失败则保持空队列。
     */
    suspend fun loadFromPrefs() {
        val raw = prefs.getString(PREF_QUEUE)
        if (raw.isNullOrEmpty()) return
        try {
            val decoded = JSONArray(raw)
            val restored = ArrayList<Song>(decoded.length())
            for (i in 0 until decoded.length()) {
                // Dart: `if (e is! Map) continue;`
                val e = decoded.optJSONObject(i) ?: continue
                // Dart: `if (fp is! String || fp.isEmpty) continue;` —— 非字符串也一律跳过。
                val filePath = e.opt("f") as? String ?: continue
                if (filePath.isEmpty()) continue
                restored.add(songFromJson(e, filePath))
            }
            if (restored.isEmpty()) return
            _items.clear()
            _items.addAll(restored)
            // Dart: `(prefs.getInt(...) ?? 0).clamp(0, _items.length - 1)`
            index = prefs.getInt(PREF_QUEUE_INDEX, 0).coerceIn(0, _items.size - 1)
            loopMode = modeFromKey(prefs.getString(PREF_LOOP_MODE))
            shuffle = prefs.getBool(PREF_SHUFFLE, false)
            shuffleHistory.clear()
        } catch (_: Exception) {
            // 反序列化失败则忽略，保持空队列（与 Dart 的 catch 行为一致）。
        }
    }

    /** 由一条持久化记录还原 [Song]；字段缺失时退回「仅凭路径」的最小可用对象。 */
    private fun songFromJson(e: JSONObject, filePath: String): Song {
        // 注意：Dart 在 `o` 缺失时会回退成 targetBpm（默认 120.0），原生侧不伪造 BPM——
        // originalBpm 在这里是「已分析」的判据，凭空造一个 120 会误判成已分析。
        val originalBpm = if (e.has("o") && !e.isNull("o")) {
            e.optDouble("o", Double.NaN).takeIf { !it.isNaN() }
        } else {
            null
        }
        val duration = if (e.has("duration") && !e.isNull("duration")) {
            e.optDouble("duration", Double.NaN).takeIf { !it.isNaN() }
        } else {
            null
        }
        val savedArt = e.optString("art", "")
        return Song(
            id = e.optString("id", "").ifEmpty { stableId(filePath) },
            filePath = filePath,
            filename = e.optString("filename", "").ifEmpty { fileNameOf(filePath) },
            title = e.optString("title", "").ifEmpty { titleOf(filePath) },
            artist = e.optString("artist", ""),
            duration = duration,
            originalBpm = originalBpm,
            // 队列里的歌基本都是「已分析」才入队的（Dart 侧 buildPlaylist 只收 hasBpm 的歌），
            // 所以恢复到 originalBpm 时标记 DONE，让变速与 BPM 展示立即可用。
            bpmStatus = if (originalBpm != null) BpmStatus.DONE else BpmStatus.PENDING,
            artworkPath = savedArt.ifEmpty { null },
        )
    }

    /** 把当前队列/当前曲/循环/随机模式写盘（退出后可恢复）。 */
    private fun persist() {
        // 先在调用线程拍快照：保证落盘的是一致状态，且后台线程不碰 Compose 快照。
        val payload = JSONArray().apply {
            for (s in _items) {
                val o = JSONObject()
                o.put("f", s.filePath)
                val ob = s.originalBpm
                if (ob != null) o.put("o", ob)
                val d = s.duration
                if (d != null) o.put("duration", d)
                o.put("id", s.id)
                o.put("filename", s.filename)
                o.put("title", s.title)
                o.put("artist", s.artist)
                val art = s.artworkPath
                if (art != null) o.put("art", art)
                put(o)
            }
        }.toString()
        val idx = index
        val mode = modeKey(loopMode)
        val sh = shuffle
        scope.launch(Dispatchers.IO) {
            writeLock.withLock {
                prefs.putString(PREF_QUEUE, payload)
                prefs.putInt(PREF_QUEUE_INDEX, idx)
                prefs.putString(PREF_LOOP_MODE, mode)
                prefs.putBool(PREF_SHUFFLE, sh)
            }
        }
    }

    private fun fileNameOf(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private fun titleOf(path: String): String {
        val name = fileNameOf(path)
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    // ------------------------------------------------------------ 队列的增删改

    /** 用选定歌曲建立播放列表，并从 [startIndex] 开始（对应 Dart `start`）。 */
    fun start(songs: List<Song>, startIndex: Int) {
        _items.clear()
        _items.addAll(songs)
        index = if (_items.isEmpty()) -1 else startIndex.coerceIn(0, _items.size - 1)
        shuffleHistory.clear()
        persist()
    }

    /**
     * 把还没在队列里的歌追加到队尾，返回新增数量。
     * 对应 Dart `toggleAddRemove` 的「补齐」分支：按 filePath 判重，只补缺失的那些。
     */
    fun append(songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        val present = HashSet<String>(_items.size * 2)
        for (s in _items) present.add(s.filePath)
        var added = 0
        for (s in songs) {
            if (present.add(s.filePath)) {
                _items.add(s)
                added++
            }
        }
        if (added == 0) return 0
        // Dart 保持 -1（队列空时补入不设当前曲）会导致播放键无处可放，这里落到第一首。
        if (index < 0) index = 0
        shuffleHistory.clear()
        persist()
        return added
    }

    /** 插到当前曲之后（「下一首播放」）；队列为空时作为唯一一首。 */
    fun appendNext(song: Song) {
        if (_items.isEmpty()) {
            _items.add(song)
            index = 0
        } else if (index < 0) {
            _items.add(0, song)
            index = 0
        } else {
            _items.add((index + 1).coerceAtMost(_items.size), song)
        }
        shuffleHistory.clear()
        persist()
    }

    /** 清空队列（对应 Dart `clear`；播放状态的停止由 PlayerController 负责）。 */
    fun clear() {
        _items.clear()
        index = -1
        shuffleHistory.clear()
        persist()
    }

    /**
     * 按条件移除队列中的歌（对应 Dart `toggleAddRemove` 的「全部移出」分支）：
     * 尽量让当前曲保持原样——仍在队列就指向它，否则落到队首。
     */
    fun removeWhere(pred: (Song) -> Boolean) {
        if (_items.isEmpty()) return
        val currentPath = current?.filePath
        val kept = _items.filterNot(pred)
        if (kept.size == _items.size) return
        _items.clear()
        _items.addAll(kept)
        if (_items.isEmpty()) {
            index = -1
        } else if (currentPath != null) {
            val i = _items.indexOfFirst { it.filePath == currentPath }
            index = if (i < 0) 0 else i
        } else {
            index = index.coerceIn(0, _items.size - 1)
        }
        shuffleHistory.clear()
        persist()
    }

    /**
     * 按索引移除一首（对应 Dart `removeAt`，播放列表里的删除按钮）：
     * 删的是当前曲之前 → 当前曲下标前移一位；删的是当前曲本身 → 下标顺延到
     * 下一首（越界时回退到末尾）；删空队列则清掉当前曲。
     */
    fun removeAt(i: Int) {
        if (i < 0 || i >= _items.size) return
        _items.removeAt(i)
        if (_items.isEmpty()) {
            index = -1
        } else if (i < index) {
            index = index - 1 // 删的是当前曲之前
        } else if (i == index) {
            index = index.coerceIn(0, _items.size - 1) // 删的是当前曲
        }
        shuffleHistory.clear()
        persist()
    }

    /**
     * 把队列里 [from] 位置的歌移动到 [to]（长按拖动排序，对应 Dart `moveItem`）。
     * 移动后修正当前曲下标，保持「正在播放的那首」不变。
     */
    fun moveItem(from: Int, to: Int) {
        if (from < 0 || from >= _items.size) return
        if (to < 0 || to >= _items.size) return
        if (from == to) return
        val item = _items.removeAt(from)
        _items.add(to, item)
        // 修正当前曲索引：Dart 的三条分支，逐条照搬。
        if (from == index) {
            index = to
        } else if (from < index && to >= index) {
            index = index - 1
        } else if (from > index && to <= index) {
            index = index + 1
        }
        shuffleHistory.clear()
        persist()
    }

    /**
     * 用新的 [Song]（分析完成后的回写）替换队列里的同一首。
     * 先按 id 匹配；从 prefs 恢复出来的对象 id 可能来自旧数据，因此再按 filePath 兜底。
     */
    fun syncSong(song: Song) {
        val i = _items.indexOfFirst { it.id == song.id || it.filePath == song.filePath }
        if (i < 0) return
        if (_items[i] == song) return
        _items[i] = song
        persist()
    }

    /** 跳到队列中指定下标（对应 Dart `jumpTo`：越界直接忽略）。 */
    @kotlin.jvm.JvmName("selectIndex")
    fun setIndex(i: Int) {
        if (i < 0 || i >= _items.size) return
        index = i
        shuffleHistory.clear()
        persist()
    }

    /**
     * [setIndex] 的别名：Dart 页面写的是 `q.jumpTo(index)`，
     * 这里保留同名入口，页面移植时可以逐字对应（行为完全等同）。
     */
    fun jumpTo(i: Int) = setIndex(i)

    @kotlin.jvm.JvmName("applyLoopMode")
    fun setLoopMode(m: LoopMode) {
        if (loopMode == m) return
        loopMode = m
        // Dart 切循环模式不动随机历史。
        persist()
    }

    @kotlin.jvm.JvmName("applyShuffle")
    fun setShuffle(on: Boolean) {
        if (shuffle == on) return
        shuffle = on
        shuffleHistory.clear()
        persist()
    }

    // ---------------------------------------------------------------- 切歌

    /**
     * 手动切下一首（用户点 next 时）。单曲循环下点 next 仍前进；
     * 只有自然播完才遵循单曲循环，见 [onEnded]。
     */
    fun next(): Song? {
        if (_items.isEmpty()) return null
        if (_items.size == 1) {
            index = 0
            persist()
            return current
        }
        index = if (shuffle) randomNext() else (index + 1) % _items.size
        persist()
        return current
    }

    /** 手动切上一首（对应 Dart `prev`；随机模式下同样走随机抽取）。 */
    fun previous(): Song? {
        if (_items.isEmpty()) return null
        if (_items.size == 1) {
            index = 0
            persist()
            return current
        }
        index = if (shuffle) randomNext() else (index - 1 + _items.size) % _items.size
        persist()
        return current
    }

    /**
     * 一首自然播放结束后的行为（对应 Dart `onEnded`，播放器 ended 事件调用）。
     *
     * 返回值是「接下来该播放的歌」：
     * - `one`：索引不变，返回当前曲（调用方重新起播，即单曲循环）；
     * - 只有一首且 `off`：返回 null，自然停止；
     * - `off` 且列表播完：停在末尾并返回 null；
     * - 其余情况前进一首（随机模式走随机抽取）。
     */
    fun onEnded(): Song? {
        if (_items.isEmpty()) return null
        if (loopMode == LoopMode.ONE) {
            // 单曲循环：停在原曲（index 不变），不写盘（Dart 亦如此）。
            return current
        }
        if (_items.size == 1) {
            // 只有一首：off 模式自然停，all/one 循环。
            return if (loopMode == LoopMode.OFF) {
                persist()
                null
            } else {
                current
            }
        }
        if (shuffle) {
            index = randomNext()
        } else {
            index = (index + 1) % _items.size
            if (index == 0 && loopMode == LoopMode.OFF) {
                // 列表播完且非循环：停在末尾，停止播放。
                index = _items.size - 1
                persist()
                return null
            }
        }
        persist()
        return current
    }

    /**
     * 随机抽取下一首，严格复刻 Dart `_randomNext`：
     * 不与当前曲重复，且当历史已覆盖到「只剩一首没放过」时向后顺延，
     * 尽量避免连续重复（Dart 用每次新建的 `math.Random()`，这里用 [Random.Default]，
     * 分布等价但不是同一串数）。
     */
    private fun randomNext(): Int {
        if (_items.size <= 1) return 0
        var pick = Random.nextInt(_items.size)
        // 避免与当前曲重复；若队列只有 1 首则必然重复（已在外面处理）。
        if (pick == index) {
            pick = (pick + 1) % _items.size
        }
        // 简单去重：最近两首不重复（若队列极小可放宽）。
        if (_items.size > 2) {
            var guard = 0
            while (guard < _items.size &&
                shuffleHistory.contains(pick) &&
                shuffleHistory.size >= _items.size - 1
            ) {
                pick = (pick + 1) % _items.size
                guard++
            }
        }
        shuffleHistory.add(pick)
        if (shuffleHistory.size > _items.size) {
            shuffleHistory.removeAt(0)
        }
        return pick
    }
}
