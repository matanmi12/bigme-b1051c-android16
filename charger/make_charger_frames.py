#!/usr/bin/env python3
"""Off-mode charging screen (device powered off + charger) for the AOSP charger of the GSI (needs Pillow + numpy).

Stock drew it with MTK's kpoc_charger + libshowlogo (logo partition images); the GSI only has the AOSP charger,
which showed a black screen with the GSI's 463-byte placeholder battery. Owner 2026-10-19: the black-and-white
InKognito drawing with a battery icon centred below the badge, filled in 20 % steps.

Output (OUT_DIR):
  battery_scale.png  5 frames for minui's res_create_multi_display_surface: rows interleaved (row y of frame f is
                     output row y*5+f), tEXt "Frames"="5"; frame k = (k+1) battery segments, 1860x2480 portrait
                     (the charger runs with ro.minui.default_rotation, see b1051c-charger.rc)
  animation.txt      one static frame per 20 % (shown for 10 min, redrawn on level changes, never blanked)
  b1051c-charger.rc  injected through Magisk overlay.d: in charger mode only, bind our files over the GSI's ones
  preview.png        the five frames side by side
Usage: make_charger_frames.py BW_STEP0_PNG OUT_DIR
"""
import os, sys
import numpy as np
from PIL import Image, ImageDraw, PngImagePlugin

INK = (0, 0, 0)
PAPER = (255, 255, 255)
FRAMES = 5


def battery(draw, cx, cy, segments):
    """Battery lying flat, centred at (cx, cy), with a charging bolt on its left; heavy ink like board 05."""
    bw, bh, stroke, r = 440, 200, 16, 34
    nub_w, nub_h = 34, 84
    bolt_w, gap = 110, 44
    total = bolt_w + gap + bw + nub_w
    x0 = cx - total // 2
    # bolt
    bx, by = x0, cy - 85
    pts = [(bx + 70, by), (bx + 8, by + 100), (bx + 52, by + 100), (bx + 34, by + 170), (bx + 104, by + 62),
           (bx + 60, by + 62), (bx + 82, by)]
    draw.polygon(pts, fill=INK)
    # body + nub
    left = x0 + bolt_w + gap
    top = cy - bh // 2
    draw.rounded_rectangle((left, top, left + bw, top + bh), radius=r, outline=INK, width=stroke, fill=PAPER)
    draw.rounded_rectangle((left + bw - 2, cy - nub_h // 2, left + bw + nub_w, cy + nub_h // 2), radius=10, fill=INK)
    # segments
    pad, sgap = stroke + 14, 14
    inner_w = bw - 2 * pad
    seg_w = (inner_w - (FRAMES - 1) * sgap) / FRAMES
    for k in range(FRAMES):
        sx = left + pad + k * (seg_w + sgap)
        box = (round(sx), top + pad, round(sx + seg_w), top + bh - pad)
        if k < segments:
            draw.rounded_rectangle(box, radius=8, fill=INK)


def main():
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    base = Image.open(src).convert('RGB')
    assert base.size == (1860, 2480), base.size
    frames = []
    for k in range(FRAMES):
        f = base.copy()
        battery(ImageDraw.Draw(f), 930, 2205, k + 1)
        frames.append(np.asarray(f))
    h, w, _ = frames[0].shape
    stack = np.empty((h * FRAMES, w, 3), np.uint8)
    for k, f in enumerate(frames):
        stack[k::FRAMES] = f                          # row y of frame k -> y*FRAMES + k
    info = PngImagePlugin.PngInfo()
    info.add_text('Frames', str(FRAMES))
    Image.fromarray(stack).save(os.path.join(out, 'battery_scale.png'), pnginfo=info, optimize=True)

    with open(os.path.join(out, 'animation.txt'), 'w') as a:
        a.write('# InKognito off-mode charging (Bigme B1051C): black-pen badge + battery, one frame per 20 %.\n'
                '# Static frames, shown 10 min each and redrawn when the level changes; no percent text.\n'
                '# animation: num_cycles, first_frame_repeats, animation_file\n'
                'animation: 1000 1 charger/battery_scale\n\n'
                'fail: charger/battery_fail\n\n'
                '# frame: disp_time min_level max_level\n')
        for k in range(FRAMES):
            lo = 0 if k == 0 else k * 20 + 1
            a.write(f'frame: 600000 {lo} {(k + 1) * 20}\n')

    with open(os.path.join(out, 'b1051c-charger.rc'), 'w') as rc:
        rc.write('# Bigme B1051C (InKognito): off-mode charging screen. Injected by Magisk overlay.d (works in charger\n'
                 '# mode, where Magisk modules are not mounted). Only in charger mode: our animation + frames over the\n'
                 '# GSI defaults, and the panel rotation (ro.surface_flinger.primary_display_orientation = ORIENTATION_270).\n'
                 '# The charger reads the images from /product/etc/res/images when animation.txt comes from /product\n'
                 '# (/system/etc/res/images is only the backup root), so both are covered.\n'
                 'on init && property:ro.bootmode=charger\n'
                 '    setprop ro.minui.default_rotation ROTATION_LEFT\n'
                 '    mount none ${MAGISKTMP}/b1051c_animation.txt /product/etc/res/values/charger/animation.txt bind\n'
                 '    mount none ${MAGISKTMP}/b1051c_battery_scale.png /product/etc/res/images/charger/battery_scale.png bind\n'
                 '    mount none ${MAGISKTMP}/b1051c_battery_scale.png /system/etc/res/images/charger/battery_scale.png bind\n'
                 '\n'
                 '# Diagnostics for the next normal boot: mounts as init sees them, and the charger kernel log a little later.\n'
                 'on charger\n'
                 '    mkdir /metadata/b1051c 0770 root system\n'
                 '    copy /proc/self/mounts /metadata/b1051c/charger-mounts.txt\n'
                 '    exec_background u:r:magisk:s0 root root -- /system/bin/sh ${MAGISKTMP}/b1051c_charger_diag.sh\n')

    with open(os.path.join(out, 'b1051c_charger_diag.sh'), 'w') as sh:
        sh.write('#!/system/bin/sh\n'
                 '# Charger mode only (b1051c-charger.rc): what the next normal boot needs to debug the charging screen.\n'
                 'sleep 12\n'
                 'out=/metadata/b1051c/charger-diag.txt\n'
                 '{\n'
                 '  date; getprop ro.bootmode; getprop ro.minui.default_rotation\n'
                 '  grep -E "charger|res/" /proc/mounts\n'
                 '  ls -laZ /product/etc/res/values/charger /product/etc/res/images/charger /system/etc/res/images/charger\n'
                 '  dmesg | grep -i -E "charger|init: .*(b1051c|mount|charger)|avc: .*(charger|b1051c)" | tail -150\n'
                 '} > $out 2>&1\n')

    # LK shows logo image 2 when charging starts (and for a low battery): same drawing, empty battery + bolt
    lk = base.copy()
    battery(ImageDraw.Draw(lk), 930, 2205, 0)
    lk.save(os.path.join(out, 'lk-image2.png'), optimize=True)

    prev = Image.new('RGB', (FRAMES * 380 + 10, 520), (200, 200, 200))
    for k, f in enumerate(frames):
        prev.paste(Image.fromarray(f).resize((370, 493), Image.LANCZOS), (10 + k * 380, 13))
    prev.save(os.path.join(out, 'preview.png'))
    print('battery_scale.png', os.path.getsize(os.path.join(out, 'battery_scale.png')), 'B')


if __name__ == '__main__':
    main()
