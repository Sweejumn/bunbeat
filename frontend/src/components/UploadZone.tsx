import { useCallback, useRef, useState } from 'react'

const ACCEPT = '.mp3,.wav,.m4a,.aac,.ogg,.flac,.opus'

interface Props {
  onFiles: (files: File[]) => void
  busy: boolean
}

export function UploadZone({ onFiles, busy }: Props) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [drag, setDrag] = useState(false)

  const handleFiles = useCallback(
    (list: FileList | null) => {
      if (!list || list.length === 0) return
      onFiles(Array.from(list))
    },
    [onFiles],
  )

  return (
    <div
      role="button"
      tabIndex={0}
      onClick={() => inputRef.current?.click()}
      onKeyDown={(e) => e.key === 'Enter' && inputRef.current?.click()}
      onDragOver={(e) => {
        e.preventDefault()
        setDrag(true)
      }}
      onDragLeave={() => setDrag(false)}
      onDrop={(e) => {
        e.preventDefault()
        setDrag(false)
        handleFiles(e.dataTransfer.files)
      }}
      className={`cursor-pointer rounded-2xl border-2 border-dashed p-8 text-center transition-colors ${
        drag ? 'border-run bg-run/10' : 'border-line bg-card hover:border-run-dim'
      }`}
    >
      <input
        ref={inputRef}
        type="file"
        accept={ACCEPT}
        multiple
        className="hidden"
        onChange={(e) => {
          handleFiles(e.target.files)
          e.target.value = ''
        }}
      />
      <div className="text-4xl">🎵</div>
      <p className="mt-2 text-lg font-semibold text-white">
        {busy ? '上传中…' : '点击或拖拽上传音乐'}
      </p>
      <p className="mt-1 text-sm text-white/50">
        支持 MP3 / WAV / M4A / AAC / OGG / FLAC，可一次选择多首，单文件上限 100 MB
      </p>
      <p className="mt-3 inline-block rounded-full bg-panel px-3 py-1 text-xs text-white/60">
        仅用于本地分析与播放，不会公开传播
      </p>
    </div>
  )
}
