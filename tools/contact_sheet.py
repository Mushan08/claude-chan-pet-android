#!/usr/bin/env python3
"""Build a checkerboard contact sheet from the Chrome self-test screenshots."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1] / "out"
NAMES = ["idle-bubble", "face", "hug", "wave", "love", "read", "think"]


def checker(size: tuple[int, int]) -> Image.Image:
    w, h = size
    bg = Image.new("RGBA", size, (255, 255, 255, 255))
    d = ImageDraw.Draw(bg)
    cell = 16
    for y in range(0, h, cell):
        for x in range(0, w, cell):
            if (x // cell + y // cell) % 2:
                d.rectangle((x, y, x + cell - 1, y + cell - 1), fill=(226, 222, 216, 255))
    return bg


def main() -> int:
    tile_w, tile_h, label_h = 240, 240, 22
    cols, rows = 3, 3
    sheet = Image.new("RGB", (tile_w * cols, (tile_h + label_h) * rows), (250, 248, 244))
    draw = ImageDraw.Draw(sheet)
    for i, name in enumerate(NAMES):
        im = Image.open(ROOT / f"shot-{name}.png").convert("RGBA")
        crop = im.crop((130, 90, 370, 330))
        bg = checker((tile_w, tile_h))
        bg.alpha_composite(crop)
        col, row = i % cols, i // cols
        x = col * tile_w
        y = row * (tile_h + label_h)
        draw.rectangle((x, y, x + tile_w - 1, y + label_h - 1), fill=(246, 243, 237))
        draw.text((x + 8, y + 5), name, fill=(43, 39, 36))
        sheet.paste(bg.convert("RGB"), (x, y + label_h))
    out = ROOT / "contact-sheet.png"
    sheet.save(out)
    print(out, sheet.size)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
