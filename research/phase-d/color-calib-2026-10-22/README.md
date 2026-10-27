# Panel colour work, 2026-10-22: A/B, panel calibration, light mix

Status at the end of the day: **paused after step 2 of 4; next = closed-loop correction** (owner, 22.10 14:3x:
"in the coming days we continue with the closed-loop correction"). Everything below can be resumed from the files in
this folder and the scripts in `work/design/color-baseline/`.

## What is live on the tablet (owner decisions)

| What | Value | Where it is set |
|---|---|---|
| Colour look | Custom preset: vivid / contrast / colour brightness 50, colour mode 4 | E-Ink Control (prefs `profile` 4) |
| OKLCh chroma stage | `vendor.debug.hwc.shim.ok_sat 60` for every preset | eink-compat `post-fs-data.sh` (boot) + E-Ink Control 0.15.12+ profile engine (setting `ok_sat`, default 60) |
| 3D stages in Fast too | `vendor.debug.hwc.shim.vivid_fast 1` | eink-compat `post-fs-data.sh` (fixes the grey look in Fast and the thin lines beside pen strokes) |
| Light presets | (26.10, E-Ink Control 0.15.15) Morning 212/175, Night 87/134, Bed 0/90, Color 196/202 (cold/warm), Custom; the 22.10 mixes were replaced (exponential raw scale, see settings-changes.log 26.10) | E-Ink Control (app list + QS tile "Light preset"); owner on Custom cold 222 / warm 80 |

Not done yet: the eink-compat **module zip** and the ROM payload still carry the old `post-fs-data.sh` (no vivid_fast /
ok_sat lines): rebuild before the next ROM. The calibrated table is **not** in the shim yet (all calibrated results so
far were made by pre-processing the card image on the Mac).

## 1. Knob A/B (round 2, `color-baseline-2026-10-22/round2/RESULTS.md`)

ok_sat 60 chosen (pastels +20 %, skin +33 %, greys neutral); vivid 50 and the stock LUT stay; level_white and tone do
nothing useful on this panel (its white is the limit); level_black 20 only for a future reading profile.

## 2. Panel calibration (this folder)

Measurement: every colour stage off (`calib_capture.py` OFF string), Regal 180 + one GC16 per image, the owner's
front light, phone camera locked. Cards from `make_calib.py`: 729 colours = 9 levels per channel, random order,
reference cells (white / black / gray 128) spread over the card.

| Set | Files | Notes |
|---|---|---|
| small cells (75 px), 2 cards | `cards/`, `shots/`, `panel_model.npz`, `model-report.txt` | **do not use**: camera flare lifted dark cells (black refs L* 33..51) |
| big cells (150 px), 4 cards | `cards-big/`, `shots-big/`, `panel_model_big.npz`, `model-report-big.txt` | good: black refs 22..29, shot-to-shot dE 0.5. Measured under the cold-heavy mix 50/208 |

Model = CIELAB per input on the 9^3 grid, relative to the paper white (flat-white shot, per channel, linear light).

Findings:
- The panel rotates hues: red -> pink-magenta (-40 deg), green -> teal (+46 to +62 deg), cyan +29, blue -18.
- Under a cold-heavy front light the darks and greys are strongly blue (black C* ~15-20 at h ~295, gray 128 C* ~10).
- Today's look (vivid 50 + ok_sat 60) renders skin pink (h 4-40 vs 52-70).

Calibrated table (`build_lut.py`, 33^3, target = source hue, panel-gray lightness, chroma mapped gamut to gamut with
cusp alignment, neutrals kept exactly so black text stays pure black; inversion in LCh with hue weight 3):

| Hue error (deg) on the baseline card | ColorChecker | hues S=1 | hues S=.5 | skin |
|---|---|---|---|---|
| no processing | 47.6 | 23.7 | 27.3 | 78.0 |
| today's look | 26.5 | 25.8 | 20.8 | 51.6 |
| calibrated (big model, gamma 0.5) | 18.5 | **6.0** | **7.0** | 64.8 (too yellow) |

(`eval-big-score.txt`; registration 0.904). The table fixes strong and pale colours but over-corrects warm mid/dark
tones (skin, browns, dark red) into yellow: it fights the blue darks, and the model is least accurate exactly there
(model vs card mean dE 6.7; darks worst, `eval-big/` check printed in the session). v2 of the table (honest gamut,
hue-weighted inversion, `calbig2-*.lut`) was built but not measured; on screen it pushes warm tones even more.

## 3. Front-light mix (this folder, `light-today/`, `light-balanced/`, `light-warm/`)

Same card, today's look, three mixes at about the same brightness (cold/warm):

| Mix | skin dh | ColorChecker dh | hues S=1 dh | grey tint C* | skin C* |
|---|---|---|---|---|---|
| cold-heavy 208/50 | 55.7 | 27.7 | 27.3 | 11-15 | 11.8 |
| balanced 130/130 | 36.9 | 25.6 | 17.0 | 5-8 | 12.2 |
| warm 50/208 | **13.7** | **19.8** | 17.3 | 6-10 | **20.1** |

The light mix matters more than any processing: amber light gives warm colours something to reflect. A calibration
is only valid for the mix it was measured under -> **measure again under the chosen preset** before building the
table that goes into the shim.

## The rig (how to run it again)

- Phone: owner's nubia NX769J, adb serial `FY24025100A7` (USB debugging, owner-connected); tablet `<SERIAL>`.
  With two devices every adb call needs `-s` (scripts set it; `ab.sh` needs `ANDROID_SERIAL`).
- Phone on a stand facing the tablet; camera app `com.android.camera` in **Pro** mode with the AFL / AEL / WBL
  buttons visible. `calib_capture.lock_camera()` reads the three buttons (red = locked) and taps only the open ones.
- Cards/images on the tablet under `/sdcard/Pictures/InKognito/calib/`; Gallery is started with `am start -S`
  (a running Gallery swallows the intent and keeps the old picture). The card is drawn at scale 0.929 from (66, 53).
- Session state on the tablet: `svc power stayon true` and Magisk toast off (`magisk --sqlite "UPDATE policies SET
  notification=0 WHERE uid=2000"`) during runs; set back to `stayon false` and `notification=1` afterwards.
- After a run: `calib_capture.py --restore` re-selects the owner's colour preset through `SET_PROFILE` (E-Ink Control
  writes only props that changed since its last apply, so an app switch does not undo the runtime props).

Commands (from `~/Desktop/Bigme`, python = `/opt/homebrew/bin/python3.14 -I`):
```
make_calib.py <dir>/cards-big --big 3                     # 4 cards + flat-white
calib_capture.py <dir>/shots-X base-pre=<card> flat=<flat>x2 big30=<card30>x2 ... base-post=<card>
SHOTS=shots-X CARDS_DIR=cards-big CARDS="30 31 32 33" PREFIX=big OUT=panel_model_X.npz calib_analyze.py <dir> "<corners>"
MODEL=panel_model_X.npz TAG=X build_lut.py <dir> --gamma 0.5 --apply color-baseline-card.png <out.png>
EVAL=eval-X eval_cal.py <dir> "<corners>" shoot NAME=/sdcard/...png:off|today|prop=v,...   (driver: lock first)
SHOTS=eval-X EVAL=eval-X eval_cal.py <dir> "<corners>" score off today cal...
```
`<corners>` = the screen's TL TR BR BL in that set's base photo (full resolution). The automatic first guess fails
when the tablet's light top bezel is in frame: draw a grid on a 1/4-size copy of the base photo, read the corners of
the lit panel area (black top band, gray side strips, the white nav bar at the bottom), multiply by 4; registration
then refines (score >= 0.85 = good; ~0.4 or lower = wrong corners, numbers meaningless).

Pitfalls met on 22.10 (each cost a run):
- The camera drops AFL/AEL/WBL after ~1.5-2 min idle, and the first shutter tap after that may only wake it:
  lock right before every run, keep runs short, every run takes its own base + flat shots; `check_exposure` stops a
  run when the exposure changes.
- The phone's framing and zoom change whenever it is moved or the camera re-locks focus: new corners per set.
- Never tap the phone blind: check `topResumedActivity` is the camera first (a tap once landed on the home screen,
  and a screenshot there showed the owner's personal calendar: deleted). Screenshots of the phone only while the
  camera is in front.
- Small calibration cells are ruined by flare from bright neighbours: use the 150 px cells.

## Next: closed-loop correction (planned, ~45-60 min, mostly automatic)

1. Owner picks the light preset to calibrate for (B / C / D). Select it (`SET_LIGHT_PRESET`), measure the 4 big cards
   again under it -> `panel_model_<preset>.npz`.
2. Build the table (start from gamma 0.5 and the v2 inversion).
3. Loop 2-3 times: a probe set (the baseline card + a probe card with skin, brown and dark-red ramps and the 24
   ColorChecker colours, big cells) shown through the table (pre-applied image) and measured; residual = measured -
   model prediction at the inputs the table chose; add those (input, measured) pairs to the model (scattered-data
   correction around the 9^3 grid) and invert again. Stop when skin / browns are within ~10 deg and nothing else got
   worse.
4. Shim stage: load a 33^3 RGB8 table from `/data/vendor/b1051c/color/cal.lut` (vendor_data_file, like the sleep
   frame), apply it at the end of `build_tables()` (baked into the 64^3 lattice, so the per-frame cost does not
   change); knob `vendor.debug.hwc.shim.cal` 0/1; colour_active() must treat it as a 3D stage. Module rebuild +
   one reboot (announce it). Then an A/B on the rig and by eye, with the light preset it was built for.
5. Later, if wanted: one table per light preset (the app selects the table with the preset).
