# Bigme B1051C Pro → Android 16 (TrebleDroid GSI) with a working colour e-ink layer

Android 16 on the **Bigme B1051C Pro** (MediaTek MT6877, 10.3" Kaleido 3 colour e-ink, stock firmware B1051C_R_4.9.0),
using the TrebleDroid A16 arm64 GSI on the stock vendor and kernel. The repo holds everything needed to make the
panel usable and to bring back the stock ROM's e-ink features:

| Piece | What it does |
|---|---|
| [`shim/`](shim) | **HWC2 interposer** (`hwcomposer.mtk_common.so`) between Android and Bigme's vendor composer: commits the GPU client target in RGBA so the colour panel shows a correct full-screen picture; Bigme's SurfaceFlinger colour shader ported to the CPU (vivid / contrast / colour brightness + the stock LUTs); auto full refresh (GC16) every N page turns; guard for a vendor crash in Regal mode. |
| [`module/`](module) | **Magisk module** `b1051c-eink-compat`: the shim, the vendor `vendor.xrz.*` props, density 300, stock touch/pen IDC files, SurfaceFlinger boot-wedge watchdog, HW-overlays-off guard, `einkd`, a framework RRO (keep the last image while asleep), and a stub for TrebleDroid's `phh-su` so Magisk works. |
| [`einkd/`](einkd) | Tiny root daemon: raises `vendor.xrz.check_commit` for 800 ms after each touch/pen/key, like the stock PowerManager, so only user page turns count for the auto full refresh. |
| [`apps/einkctl/`](apps/einkctl) | **E-Ink Control** app: refresh modes (stock HD 256 / Fast / Extreme / Regal / Hyper + A2), per-app refresh modes (accessibility service), colour profiles (stock Default/Magazine/Comic/Video + Custom), anti-shake, auto clean, full refresh every N, front light (two channels, night schedule, off in sleep), display density 100–500 dpi, Quick Settings tiles, a refresh button in the navigation-button row, dual-boot (DSU) switch. |
| [`apps/einkui/`](apps/einkui) | **SystemUI overlay plugin** (works on debuggable/test-keys GSIs): e-ink Quick Settings and notification shade in light and dark styles (outlined tiles, solid colours, no translucent grey shade), **two front-light sliders (cold / warm) in place of the brightness slider**, notifications in black and white. SystemUI itself is not modified. |
| [`apps/fwoverlay/`](apps/fwoverlay) | Static framework RRO: `config_displayColorFadeDisabled=true`. |
| [`gapps/`](gapps) | Scripts and notes to build an optional minimal Google apps Magisk module (Play Store + Play services + Services Framework) from a MindTheGapps 16 release. |
| [`tools/scripts/`](tools/scripts) | Host scripts used for the install (mtkclient wrappers, super/LP extent tools, Magisk patching, checks). |
| [`docs/`](docs), [`research/`](research) | Install runbook, status, and the reverse-engineering notes (stock display stack, refresh/colour/frontlight knobs, LK hidden dm-verity warning, SystemUI plugin route, logs). |

Status (2026-10-18): boots automatically on slot B; full-screen colour picture; touch, pen, Wi-Fi OK; refresh modes,
colour pipeline, front light, sleep image, QS styling and tiles verified on one device. Not yet tested: booting the
stock ROM as a DSU, Bluetooth/audio in depth. Notes in [`docs/STATUS.md`](docs/STATUS.md) and
[`research/phase-d/CONCLUSIONS.md`](research/phase-d/CONCLUSIONS.md). Ideas and open questions for later:
[`research/phase-d/FEATURES-NEXT.md`](research/phase-d/FEATURES-NEXT.md) (in Hebrew).

## ⚠️ Read before trying
This was done on one unit and **can brick yours**. Facts that matter on this device (details in the runbook):
- Virtual A/B; only **slot B** holds a bootable system after the stock OTAs. **Never `set_active a`**, and do not use
  **fastbootd** (it switched the device to slot A).
- All writes were done with **mtkclient** (preloader/DA), each with a pre-read and read-back: 1 byte in `vbmeta_b`
  (hashtree disabled), the GSI into the `system_b` extent of `super`, metadata/userdata wipe, a Magisk-patched
  `boot_b`, and a 4-byte `misc` fix that clears LK's hidden dm-verity warning (otherwise the device powers off).
- Keep full backups of every partition first. Never write calibration/security partitions (seccfg, nvram, nvdata,
  proinfo, waveform, vcom …).

Procedure: [`docs/PHASE-C-RUNBOOK.md`](docs/PHASE-C-RUNBOOK.md), [`research/hidden-verity-warning-2026-10-17.md`](research/hidden-verity-warning-2026-10-17.md),
and `tools/scripts/flash-gsi.sh` (prints the operations; review every step).

## Building
Host tools: Android SDK command-line tools (build-tools 36, platform android-35), NDK 26, OpenJDK 17, Python 3.
Paths default to Homebrew locations; override `ANDROID_NDK_HOME` / `ANDROID_SDK`.

```sh
# colour LUTs are Bigme's: extract them from your own device first (see the script header)
python3 -I shim/tools/make_lut_header.py g_c001.bmp g_c002.bmp > shim/lut_data.h
ANDROID_NDK_HOME=… shim/build.sh          # -> shim/out/hwcomposer.mtk_common.stripped.so
einkd/build.sh                            # -> einkd/einkd
apps/fwoverlay/build.sh                   # -> framework RRO
module/build.sh                           # -> module/b1051c-eink-compat-vX.zip  (magisk --install-module)
VERSION_CODE=14 VERSION_NAME=0.8.0 apps/einkctl/build.sh
tools/fetch-aosp-testkeys.sh              # public AOSP platform test key, only for the plugin
VERSION_CODE=14 VERSION_NAME=0.2.3 apps/einkui/build.sh
```
After installing E-Ink Control: enable its accessibility service, grant
`pm grant org.b1051c.einkctl android.permission.WRITE_SECURE_SETTINGS`, and allow it root in Magisk.
QS tile layout: `cmd statusbar set-tiles "<list>"` (on A16, `settings put secure sysui_qs_tiles` is reverted).

## Runtime knobs
| Knob | Meaning |
|---|---|
| `vendor.xrz.global_refresh_mode` | Global refresh mode read live by the vendor HWC (0 = per layer/default; 178 Fast, 179 Extreme, 180 Regal, 183 Hyper, −2147483471 HD 256). |
| `vendor.xrz.global_color_enhance` / `global_contrast` / `global_brightness_level` / `global_color_mode` | Colour pipeline in the shim (0–100; mode 2 = Comic LUT). |
| `vendor.xrz.clean_frequency` | Full refresh (GC16) every N page turns (shim + einkd). `vendor.debug.hwc.shim.auto_gc16=0` restores vendor behaviour. |
| `vendor.debug.hwc.shim.commit_mode=4` | One-shot full refresh (with a SurfaceFlinger 1008 toggle to force a frame). |
| `Settings.Secure b1051c_shade_theme` | Plugin style: 0 follow Dark theme, 1 light, 2 dark, −1 Android default. `b1051c_shade_stroke`: outline width in tenths of dp. |
| `Settings.Secure b1051c_fl_cold` / `b1051c_fl_warm` | Front-light values shared by the QS sliders and E-Ink Control (raw 0–222). |

## Not included, on purpose
- **Keys:** no private keys. The AOSP test platform key is public and fetched by `tools/fetch-aosp-testkeys.sh`.
- **Firmware and device data:** partition dumps, boot/system/super images, DSU images, `misc`/`metadata`/`expdb`
  dumps, encryption metadata. In the published logs, device identifiers are replaced with placeholders such as
  `<SERIAL>`, `<ME_ID>`, `<SOC_ID>`, `<STORAGE_CID>`, `<MAC>`, `<WIFI_SSID>`.
- **Third-party binaries and data:** Bigme's vendor libraries and colour LUTs, Google apps, Magisk, the GSI, and
  decompiled SystemUI sources. Build scripts and notes say where each comes from.
- Some docs mention local files that exist only in the original working folder (dumps, backups).

## Credits and licenses
- Original work in this repository: **MIT** (see [`LICENSE`](LICENSE)).
- `shim/hwcshim.cpp` started as a port of `tools/hwcshim` from
  [awong-dev/hibreak-pro-color](https://github.com/awong-dev/hibreak-pro-color) (HiBreak Pro Color). That project has
  no license file; the original design and code are its author's, and only the changes made here are offered under MIT.
- [TrebleDroid](https://github.com/TrebleDroid) / phh GSIs, [Magisk](https://github.com/topjohnwu/Magisk),
  [mtkclient](https://github.com/bkerler/mtkclient) (GPL-3.0, used as a tool, not included),
  [MindTheGapps](https://gitlab.com/MindTheGapps) (used to build the optional GApps module, not included).
- Studied for reference: BigmeB1051CProPatcher and HibreakProPatchingService (Apache-2.0), hibreak-eink-manager,
  the HiBreak community guides.
- Icons: Material Design icons (Apache-2.0). SystemUI plugin interface stubs follow AOSP's plugin API (Apache-2.0).
- Not affiliated with Bigme or Google. No warranty: you are responsible for your device.
