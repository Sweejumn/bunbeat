import type { Health, ProcessTask, Recommendation, Song } from './types'

const BASE = '/api'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, init)
  if (!res.ok) {
    let detail = `HTTP ${res.status}`
    try {
      const body = await res.json()
      if (body && typeof body.detail === 'string') detail = body.detail
    } catch {
      /* non-JSON error body */
    }
    throw new Error(detail)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

function jsonInit(method: string, body: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }
}

export const api = {
  health: () => request<Health>('/health'),

  listSongs: () => request<Song[]>('/songs'),

  upload(files: File[]): Promise<Song[]> {
    const form = new FormData()
    for (const f of files) form.append('files', f)
    return request<Song[]>('/songs/upload', { method: 'POST', body: form })
  },

  updateSong: (id: string, patch: { original_bpm?: number; title?: string; artist?: string }) =>
    request<Song>(`/songs/${id}`, jsonInit('PATCH', patch)),

  analyzeSong: (id: string) => request<Song>(`/songs/${id}/analyze`, { method: 'POST' }),

  deleteSong: (id: string) => request<void>(`/songs/${id}`, { method: 'DELETE' }),

  recommend: (targetBpm: number, songIds?: string[]) =>
    request<Recommendation[]>('/recommend', jsonInit('POST', { target_bpm: targetBpm, song_ids: songIds })),

  processBatch: (songIds: string[], targetBpm: number) =>
    request<{ tasks: ProcessTask[] }>('/process/batch', jsonInit('POST', { song_ids: songIds, target_bpm: targetBpm })),

  getTask: (taskId: string) => request<ProcessTask>(`/process/tasks/${taskId}`),

  originalUrl: (songId: string) => `${BASE}/audio/${songId}`,
  processedUrl: (songId: string, targetBpm: number) =>
    `${BASE}/audio/${songId}/processed?target_bpm=${Math.round(targetBpm * 10) / 10}`,
}
