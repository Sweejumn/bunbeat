export type BpmStatus = 'pending' | 'analyzing' | 'done' | 'failed'

export interface Song {
  id: string
  filename: string
  title: string
  artist: string
  duration: number | null
  original_bpm: number | null
  bpm_confidence: number | null
  bpm_status: BpmStatus
  bpm_error: string | null
  /** seconds of the first detected beat in the original audio (phase anchor) */
  beat_offset: number | null
  /** default beat map (fixed grid), seconds in the original timeline */
  beat_times: number[] | null
  /** alternate beat maps per mode (light / snap), original timeline */
  beat_maps: Record<BeatMode, number[]> | null
  mime_type: string | null
  size: number
  created_at: string
}

export interface Recommendation {
  song: Song
  distance: number
  score: number // 1..5 stars
}

export interface ProcessTask {
  id: string
  song_id: string
  target_bpm: number
  status: 'pending' | 'processing' | 'done' | 'failed'
  error: string | null
  created_at: string
  updated_at: string
  processed_url: string | null
  processed_bpm: number | null
  /** default beat map (fixed grid) of the stretched file */
  processed_beat_times: number[] | null
  /** alternate beat maps per mode of the stretched file */
  processed_beat_maps: Record<BeatMode, number[]> | null
}

/** selectable beat-map modes (backend computes all three at analysis time) */
export type BeatMode = 'grid' | 'light' | 'snap'

export const BEAT_MODES: { id: BeatMode; label: string; desc: string }[] = [
  { id: 'grid', label: '固定拍子', desc: '完全等距 · 默认推荐' },
  { id: 'light', label: '轻跟随', desc: '±5% 跟随起音' },
  { id: 'snap', label: '跟随起音', desc: '±12% 吸附打击点' },
]

export interface Health {
  status: string
  version: string
  ffmpeg: string
  data_dir: string
  songs: number
}

export type ModeId = 'walk' | 'jog' | 'run' | 'sprint' | 'custom'

export interface ModeDef {
  id: ModeId
  label: string
  icon: string
  range: [number, number]
  defaultBpm: number
}

export const MODES: ModeDef[] = [
  { id: 'walk', label: '走路', icon: '🚶', range: [100, 120], defaultBpm: 110 },
  { id: 'jog', label: '慢跑', icon: '🏃', range: [120, 145], defaultBpm: 132 },
  { id: 'run', label: '跑步', icon: '🏃‍♂️', range: [145, 165], defaultBpm: 155 },
  { id: 'sprint', label: '快跑', icon: '⚡', range: [165, 185], defaultBpm: 175 },
  { id: 'custom', label: '自定义', icon: '🎯', range: [60, 220], defaultBpm: 150 },
]

export interface PlaylistItem {
  song: Song
  url: string
  targetBpm: number
  /** ground-truth beats of the stretched file (fixed grid), if available */
  processedBeatTimes: number[] | null
  /** alternate beat maps of the stretched file, per mode */
  processedBeatMaps: Record<BeatMode, number[]> | null
}
