#!/usr/bin/env python3
"""InKognito boot/shutdown screens from the owner's square logo (needs Pillow).

Output (1860x2480 portrait, the panel's native size for the logo partition, the boot animation and the shutdown
image): inkognito-1860x2480-cream.png, inkognito-1860x2480-white.png, and compare-cream-white.png (left half cream,
right half white) to judge the background on the Kaleido panel. The background around the badge is flood-filled
from the corners, so it becomes one flat colour (the original has paper grain, which e-ink dithers into noise and
which compresses badly in the logo partition). Keeps the top-right corner free for LK's low-battery icon (image 2).

Usage: make_bootlogo.py SRC_SQUARE_LOGO OUT_DIR
"""
import os, sys
from PIL import Image, ImageDraw, ImageFilter

W, H = 1860, 2480
LOGO_W = 1600                  # 130 px side margins
CENTER_Y = 1160                # slightly above the middle
CREAM = (0xF7, 0xF4, 0xE7)     # the logo's own paper colour (sampled)
WHITE = (255, 255, 255)
SENTINEL = (255, 0, 255)


def background_mask(img):
    """L mask, 255 where the pixel belongs to the paper around the badge (connected to the borders)."""
    probe = img.copy()
    for x, y in ((0, 0), (img.width - 1, 0), (0, img.height - 1), (img.width - 1, img.height - 1),
                 (img.width // 2, 0), (img.width // 2, img.height - 1), (0, img.height // 2), (img.width - 1, img.height // 2)):
        if probe.getpixel((x, y)) != SENTINEL:
            ImageDraw.floodfill(probe, (x, y), SENTINEL, thresh=40)
    mask = Image.new("L", img.size, 0)
    mask.putdata([255 if p == SENTINEL else 0 for p in probe.getdata()])
    return mask.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.GaussianBlur(1.2))


def compose(logo, mask, bg):
    canvas = Image.new("RGB", (W, H), bg)
    flat = Image.composite(Image.new("RGB", logo.size, bg), logo, mask)
    canvas.paste(flat, ((W - logo.width) // 2, CENTER_Y - logo.height // 2))
    return canvas


def main():
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    logo = Image.open(src).convert("RGB")
    logo = logo.resize((LOGO_W, round(logo.height * LOGO_W / logo.width)), Image.LANCZOS)
    mask = background_mask(logo)
    cream, white = compose(logo, mask, CREAM), compose(logo, mask, WHITE)
    cream.save(os.path.join(out, "inkognito-1860x2480-cream.png"), optimize=True)
    white.save(os.path.join(out, "inkognito-1860x2480-white.png"), optimize=True)
    # Step 2 of the boot "animation": LK shows image 0, the kernel later shows image 38 -> same screen + three
    # brand dots under the badge (bordeaux, gold, teal, ink outlines), so the boot visibly moves on.
    top = CENTER_Y - logo.height // 2
    for name, base in (("cream", cream), ("white", white)):
        step2 = base.copy()
        d = ImageDraw.Draw(step2)
        cy, r = top + logo.height + 170, 30
        for k, fill in enumerate(((0x93, 0x28, 0x45), (0xCD, 0x8E, 0x2C), (0x19, 0x4D, 0x50))):
            cx = W // 2 + (k - 1) * 110
            d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=fill, outline=(0, 0, 0), width=6)
        step2.save(os.path.join(out, f"inkognito-1860x2480-{name}-step2.png"), optimize=True)
    cmp_img = cream.copy()
    cmp_img.paste(white.crop((W // 2, 0, W, H)), (W // 2, 0))
    ImageDraw.Draw(cmp_img).line([(W // 2, 0), (W // 2, 120)], fill=(0, 0, 0), width=4)
    cmp_img.save(os.path.join(out, "compare-cream-white.png"), optimize=True)
    print("paper pixels flattened:", sum(1 for v in mask.getdata() if v > 128), "of", logo.width * logo.height)


if __name__ == "__main__":
    main()
