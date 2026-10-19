#!/usr/bin/env python3
"""Take the red/brown cast out of near-black and dark brown tones, for the Kaleido panel (needs Pillow + numpy).

Why: the logo's ink and the cat's fur are not neutral (e.g. #1C0B0A, #080403, fur #3B312E). The Kaleido colour
filter, and the vivid boost of the colour pipeline, turn that small warm tint into visible red (owner 2026-10-19).
What: per pixel, blend towards its own gray (same luma) by a weight w:
  - very dark (HSL L < ~0.14): fully neutral, whatever the hue (ink lines, the darkest fur);
  - dark to mid (L < ~0.45), low saturation (S < ~0.38) AND warm hue (330-50 deg): neutral (fur, browns);
then darken those tones a little (up to 15 %) so the black cat reads black, not dark gray.
Saturated colours (bordeaux, gold, teal, steel, green eyes, the bordeaux paw prints) are untouched; so is the paper.
Usage: neutralize_darks.py IN OUT [--report]
"""
import sys
import numpy as np
from PIL import Image


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def neutralize(rgb):
    a = rgb.astype(np.float32) / 255
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    mx, mn = a.max(2), a.min(2)
    L = (mx + mn) / 2
    d = mx - mn
    den = np.where(L < .5, mx + mn, 2 - mx - mn)
    S = np.divide(d, den, out=np.zeros_like(d), where=den > 1e-6)
    h = np.zeros_like(L)
    nz = d > 1e-6
    rc = np.divide(mx - r, d, out=np.zeros_like(d), where=nz)
    gc = np.divide(mx - g, d, out=np.zeros_like(d), where=nz)
    bc = np.divide(mx - b, d, out=np.zeros_like(d), where=nz)
    h = np.where(r == mx, bc - gc, np.where(g == mx, 2 + rc - bc, 4 + gc - rc))
    hue = (h / 6 % 1) * 360
    warm = ((hue >= 330) | (hue <= 50)).astype(np.float32)
    w1 = 1 - smoothstep(0.08, 0.14, L)
    # fur mid-tones reach L ~0.45 / S ~0.4; the gold coat (S >= 0.55), bordeaux (S ~0.54) and the nose (L ~0.5) stay
    w2 = (1 - smoothstep(0.40, 0.50, L)) * (1 - smoothstep(0.32, 0.45, S)) * warm
    w = np.maximum(w1, w2)[..., None]
    y = (0.299 * r + 0.587 * g + 0.114 * b)[..., None]
    out = y + (a - y) * (1 - w)
    out = out * (1 - 0.15 * w)
    return (np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8), w[..., 0]


def main():
    src, dst = sys.argv[1], sys.argv[2]
    im = Image.open(src).convert('RGB')
    out, w = neutralize(np.asarray(im))
    Image.fromarray(out).save(dst, **({'quality': 95} if dst.lower().endswith(('.jpg', '.jpeg')) else {'optimize': True}))
    if '--report' in sys.argv:
        a = np.asarray(im).astype(np.int16)
        o = out.astype(np.int16)
        changed = w > 0.05
        print(f'{src}: {100 * changed.mean():.1f}% of pixels neutralized (w>0.05); '
              f'mean RGB there before {a[changed].mean(0).round(1)} after {o[changed].mean(0).round(1)}')


if __name__ == '__main__':
    main()
