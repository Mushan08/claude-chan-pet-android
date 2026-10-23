#!/usr/bin/env python3
"""Convert the desktop pet's alpha WebM clips into animated WebP.

Android WebView renders WebM through MediaCodec, which drops the VP9 alpha
plane. Animated WebP keeps the alpha channel and is decoded by Skia, so the
clips stay transparent on the phone.

Usage:
    python tools/convert_anims.py
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT.parent / "claude-chan-pet" / "assets" / "pet" / "anims"
OUT = ROOT / "app" / "src" / "main" / "assets" / "pet" / "anims"
HEIGHT = 480
FPS = 24
QUALITY = 76


def ffmpeg_exe() -> str:
    try:
        import imageio_ffmpeg

        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        exe = shutil.which("ffmpeg")
        if not exe:
            raise SystemExit("ffmpeg not found: pip install imageio-ffmpeg")
        return exe


def convert(name: str, src: Path, ffmpeg: str) -> dict:
    with tempfile.TemporaryDirectory(prefix=f"pet-{name}-") as tmp:
        tmp_path = Path(tmp)
        pattern = tmp_path / "f_%03d.png"
        cmd = [
            ffmpeg,
            "-y",
            "-loglevel",
            "error",
            "-c:v",
            "libvpx-vp9",
            "-i",
            str(src),
            "-vf",
            f"scale=-1:{HEIGHT}:flags=lanczos",
            "-pix_fmt",
            "rgba",
            str(pattern),
        ]
        subprocess.run(cmd, check=True)
        frames = sorted(tmp_path.glob("f_*.png"))
        if not frames:
            raise RuntimeError(f"{name}: no frames decoded")
        images = [Image.open(p).convert("RGBA") for p in frames]
        # The alpha plane can leave RGB noise under fully transparent pixels;
        # `exact=True` keeps it from bleeding into the edges after scaling.
        dst = OUT / f"{name}.webp"
        images[0].save(
            dst,
            save_all=True,
            append_images=images[1:],
            duration=round(1000 / FPS),
            loop=0,
            lossless=False,
            quality=QUALITY,
            method=4,
            exact=True,
        )
        probe = Image.open(dst)
        alpha = probe.convert("RGBA").getchannel("A").getextrema()
        return {
            "file": f"anims/{name}.webp",
            "w": probe.size[0],
            "h": probe.size[1],
            "frames": len(images),
            "fps": FPS,
            "seconds": round(len(images) / FPS, 2),
            "bytes": dst.stat().st_size,
            "alpha": list(alpha),
        }


def main() -> int:
    manifest_path = SOURCE / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    OUT.mkdir(parents=True, exist_ok=True)
    ffmpeg = ffmpeg_exe()
    result = {"source": "claude-chan-pet/assets/pet/anims", "clips": {}}
    for name in manifest["clips"]:
        src = SOURCE / f"{name}.webm"
        info = convert(name, src, ffmpeg)
        result["clips"][name] = info
        print(
            f"{name:6s} {info['w']}x{info['h']} {info['frames']}f "
            f"{info['bytes'] / 1024:.0f} KiB alpha={tuple(info['alpha'])}"
        )
    (OUT / "manifest.json").write_text(
        json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
