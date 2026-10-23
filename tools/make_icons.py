#!/usr/bin/env python3
"""Generate launcher icons and the monochrome notification icon."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app" / "src" / "main" / "assets" / "pet" / "front.png"
RES = ROOT / "app" / "src" / "main" / "res"

PAPER = (246, 243, 237, 255)
COPPER = (200, 112, 60, 255)


def rounded_icon(size: int) -> Image.Image:
    scale = 4  # draw big, then downsample for clean edges
    canvas = Image.new("RGBA", (size * scale, size * scale), (0, 0, 0, 0))
    d = ImageDraw.Draw(canvas)
    s = size * scale
    margin = round(s * 0.045)
    radius = round(s * 0.23)
    d.rounded_rectangle(
        (margin, margin, s - margin, s - margin),
        radius=radius,
        fill=PAPER,
        outline=COPPER,
        width=max(2, round(s * 0.018)),
    )

    pet = Image.open(SRC).convert("RGBA")
    target_h = round(s * 0.72)
    target_w = round(pet.width * target_h / pet.height)
    pet = pet.resize((target_w, target_h), Image.LANCZOS)
    x = (s - target_w) // 2
    y = s - margin - round(s * 0.10) - target_h
    canvas.alpha_composite(pet, (x, y))

    return canvas.resize((size, size), Image.LANCZOS)


def notification_icon() -> Image.Image:
    pet = Image.open(SRC).convert("RGBA")
    alpha = pet.getchannel("A")
    # Android tints the small icon; only the alpha silhouette matters.
    white = Image.new("RGBA", pet.size, (255, 255, 255, 0))
    white.putalpha(alpha)
    box = alpha.getbbox()
    white = white.crop(box)
    side = 96
    target_h = round(side * 0.86)
    target_w = round(white.width * target_h / white.height)
    white = white.resize((target_w, target_h), Image.LANCZOS)
    out = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    out.alpha_composite(white, ((side - target_w) // 2, (side - target_h) // 2))
    return out


def main() -> int:
    for density, size in {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }.items():
        d = RES / f"mipmap-{density}"
        d.mkdir(parents=True, exist_ok=True)
        rounded_icon(size).save(d / "ic_launcher.png")
    drawable = RES / "drawable-nodpi"
    drawable.mkdir(parents=True, exist_ok=True)
    notification_icon().save(drawable / "ic_stat_pet.png")
    rounded_icon(512).save(ROOT / "docs" / "icon-512.png")
    print("icons written")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
