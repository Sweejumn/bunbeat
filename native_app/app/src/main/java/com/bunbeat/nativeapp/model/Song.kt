package com.bunbeat.nativeapp.model

/** BPM 分析状态（对应 Dart `BpmStatus`）。 */
enum class BpmStatus { PENDING, ANALYZING, DONE, FAILED }

/**
 * 节拍模式（对应 Dart `BeatMode` + `kBeatModes`）。
 * [key] 是与缓存 / beatMaps 里使用的字符串键（grid/snap）。
 */
enum class BeatMode(val key: String, val label: String, val desc: String) {
    GRID("grid", "固定拍子", "完全等距 · 默认推荐"),
    SNAP("snap", "跟随起音", "±12% 吸附打击点");

    companion object {
        val all: List<BeatMode> = listOf(GRID, SNAP)
        fun fromKey(key: String?): BeatMode =
            all.firstOrNull { it.key == key } ?: GRID
    }
}

/**
 * 一首从所选文件夹中读取的音乐（对应 Dart `Song`）。
 *
 * 与 Dart 版不同：这里做成不可变 data class，更新走 [copy]，
 * 便于 Compose 做快照与重组。
 */
data class Song(
    /** 唯一 id（用文件路径 hash），与 Dart 版保持一致以便复用同一套缓存语义。 */
    val id: String,
    /** 可播放文件路径。NCM 源会被重定向到解密后的真实音频文件。 */
    val filePath: String,
    val filename: String,
    val title: String,
    val artist: String,
    val duration: Double? = null,
    val originalBpm: Double? = null,
    val bpmConfidence: Double? = null,
    val bpmStatus: BpmStatus = BpmStatus.PENDING,
    val bpmError: String? = null,
    /** 第一拍在原始时间轴上的秒数（节拍相位锚点）。 */
    val beatOffset: Double? = null,
    /** 固定拍子时间轴（秒）。 */
    val beatTimes: List<Double>? = null,
    /** 各节拍模式的时间轴，键为 [BeatMode.key]；grid 与 [beatTimes] 等价。 */
    val beatMaps: Map<String, List<Double>>? = null,
    /** 0..1：两个独立相位信号是否一致。null 表示未评估。 */
    val phaseReliability: Double? = null,
    /** 产生该结果的算法编号；null 表示未知/旧缓存。 */
    val algorithm: Int? = null,
    /** 各算法最近一次测得的 BPM（键 = 算法编号字符串）。 */
    val byAlgorithm: Map<String, Double> = emptyMap(),
    /** 内嵌封面图的缓存文件路径。 */
    val artworkPath: String? = null,
) {
    val hasBpm: Boolean get() = originalBpm != null && bpmStatus == BpmStatus.DONE

    /** 是否标记为归档（由 LibraryStore 维护，不在 Song 内部存储）。 */
    fun displayTitle(): String = title.ifBlank { filename }
}

/** 推荐结果：distance = |原BPM − 目标BPM|，score 为 1..5 星（对应 Dart `Recommendation`）。 */
data class Recommendation(
    val song: Song,
    val distance: Double,
    val score: Int,
)
