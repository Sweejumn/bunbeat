"""Generate synthetic MP3 test tracks with a known BPM.

Each track is a decaying "click + thump" pattern at exactly BPM beats per
minute, so the pipeline (BPM detection -> time-stretch -> re-detection)
can be verified against ground truth.

Usage:
    python scripts/gen_test_audio.py [--out data/test_audio] [--bpm 120 128 ...]
"""
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 44100
DURATION = 45.0


def make_click_track(bpm: float, duration: float = DURATION) -> np.ndarray:
    """Click + thump track with an accented downbeat every 4 beats —
    mimics real music where the full tempo is usually perceivable."""
    period = 60.0 / bpm
    t = np.arange(int(SR * duration)) / SR
    beat = t % period
    bar = (t // period) % 4  # 0 = downbeat

    click = 0.5 * np.sin(2 * np.pi * 880 * t) * np.exp(-90 * beat)
    thump = 0.4 * np.sin(2 * np.pi * 220 * t) * np.exp(-10 * beat)
    bass = 0.35 * np.sin(2 * np.pi * 55 * t) * np.exp(-6 * beat)

    # Accent on the downbeat: stronger thump + a lower, louder click.
    accent_env = 0.9 * (bar == 0) + 0.3 * (bar != 0)
    click = click * (0.5 + 0.5 * (bar == 0))
    thump = thump * accent_env
    bass = bass * accent_env

    return 0.9 * (click + thump + bass)


def get_ffmpeg() -> str:
    import imageio_ffmpeg

    return imageio_ffmpeg.get_ffmpeg_exe()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="data/test_audio", type=Path)
    parser.add_argument("--bpm", nargs="+", type=int, default=[120, 128, 150, 165, 180])
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)
    ffmpeg = get_ffmpeg()

    for bpm in args.bpm:
        wav = args.out / f"_tmp_{bpm}.wav"
        mp3 = args.out / f"test_{bpm}.mp3"
        sf.write(wav, make_click_track(float(bpm)), SR)
        subprocess.run(
            [ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", str(wav),
             "-c:a", "libmp3lame", "-q:a", "4", str(mp3)],
            check=True,
        )
        wav.unlink(missing_ok=True)
        print(f"generated {mp3.name} @ {bpm} BPM")


if __name__ == "__main__":
    main()
