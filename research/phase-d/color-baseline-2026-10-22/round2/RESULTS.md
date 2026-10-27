# Colour A/B round 2 (2026-10-22 13:14-13:16, automated)

Setup: owner's phone on a stand, Pro mode with AFL/AEL/WBL (1/26 s, ISO 1200, WB 5125 K), shutter over adb
(`work/design/color-baseline/round.sh`); tablet in Regal 180, Custom 50/50/50, front light off, card in Gallery.
Analysis: `analyze_round.py` (registration 0.88-0.92 on all 17). Numbers: `metrics.txt` (CIELAB of the camera's sRGB).
Drift check A0 -> A0end: < 1 L* on every patch, < 0.2 C*: the light was stable; differences below ~1 are noise.

| Knob | What the photos show (vs A0) | Verdict |
|---|---|---|
| level_white 20/40/60 | paper white and cream unchanged (the panel's white is the limit: white 75.7 vs cream 74.5 already); light-mids lighter (gray 204 +1.3/+2.2/+3.3, light skin +0.2/+1.4/+2.8); pastels slightly washed (-0.4..-0.7 C) | little to gain: off |
| level_black 20/40 | panel black unchanged (16.8, floor); grays 0 and 26 merge (step -7.3/-5.9); mid-darks darker (gray 76 and 128 about -3.5 to -3.9): heavier text edges, less shadow detail | 20 for reading profiles only (owner liked it) |
| ok_sat 30/60/100 | chroma up, greys stay neutral: pastels +13/+20/+33 %, skin +10/+33/+48 %, ColorChecker +2/+6/+10 %; skin darker (-1.4/-2.6/-4.3 L) | 60 = default candidate |
| tone 20/40 | light tones lighter (gray 204 +2.3/+4.0), shadows crushed (0/26 step -5.7), slightly less chroma | off (overlaps level_black) |
| vivid 50 vs ok_sat | without vivid: ColorChecker chroma -2.0 and light tones darker (gray 204 -3.0); ok_sat alone at 100 still below vivid on strong colours, above it on pastels/skin | keep vivid 50 AND add ok_sat |
| lut | lut 0 (no stock LUT): chroma -1.7, light tones darker; lut 1 = A0 (the LUT is already on with vivid) | keep stock (-1) |

Best measured combination: vivid 50 + ok_sat 60 (ColorChecker 9.2, pastels 9.0, skin 7.7 vs A0 8.7 / 7.5 / 5.8).
All photos with the front light OFF: the owner's reading condition (light on) still needs one look by eye.
