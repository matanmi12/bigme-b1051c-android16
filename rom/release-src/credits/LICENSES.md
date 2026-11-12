# InKognito beta 1: licenses and sources

Release: `InKognito-B1051CPro-beta1-20261112`. Checked against the upstream repositories on 2026-10-21; the
keyboard rows (HeliBoard, its dictionary, the swipe library) were added on 2026-11-11.

This release combines our own code with third-party software, fonts and data. The table lists each part, its
license and where its source is. The license texts are in [licenses/](licenses/). Where a part has no license
statement, or its terms are unclear, we say so instead of guessing. Nothing here is legal advice.

## Components

| Component | Version in this release | Where it is | License | Source | Notes |
|---|---|---|---|---|---|
| **InKognito layer** (E-Ink Control, E-Ink SystemUI plugin, Lock & Sleep, einkd, Magisk modules, the keyboard look overlay, first-boot scripts, KOReader patches, charging-mode rc, installer) | beta 1 | `system.img` / `system-nogapps.img` (`/system/etc/inkognito`, `/system/etc/init/inkognito.rc`), `kit/boot` (charging-mode rc and script, put into `overlay.d` of the boot image the installer builds), `inkognito.py` and its front ends | MIT | https://github.com/matanmi12/bigme-b1051c-android16 | `licenses/MIT-InKognito.txt`. Copyright "The bigme-b1051c-android16 contributors". The brand art (badge, boot logo, wallpapers, nav buttons) is listed separately below. |
| **HWC display shim** (`hwcomposer.mtk_common.so`) | in module `b1051c-eink-compat` 0.1.55 | `system.img` payload | Our changes: MIT. The original project has **no license file**. | Ours: `shim/` in our repo. Original: https://github.com/awong-dev/hibreak-pro-color (`tools/hwcshim`, commit `4a65ee9`) | Started as a port of awong-dev's shim. The original design and code are its author's; we credit it and offer only our changes under MIT. It also contains Bigme-derived data and logic (see "Bigme components"). |
| **TrebleDroid GSI** | `ci-20250617`, `system-td-arm64-vanilla` (`treble_arm64_bvS`, userdebug, test-keys) | `system.img` (base image) | AOSP licenses (mostly Apache-2.0, plus GPL, LGPL and others). TrebleDroid's build scripts: Apache-2.0. `device_phh_treble`, `vendor_hardware_overlay` and `treble_app` have no license file on GitHub. | https://github.com/TrebleDroid/treble_experimentations/releases/tag/ci-20250617 ; https://github.com/TrebleDroid/treble_manifest (branch `android-16.0`) and the repos it lists | Download `system-td-arm64-vanilla.img.xz` sha256 `d06c66e4…ae55ad6` = the GitHub release asset. InKognito adds its payload and one init file to this image. The GSI's full license notices: `/system/etc/NOTICE.xml.gz` (Settings > About tablet > Legal information). |
| **AOSP** (inside the GSI) | Android 16, build ID `BP2A.250605.031.A3` | `system.img` | Mostly Apache-2.0, plus others | https://android.googlesource.com/platform/manifest | Google's build-number table maps this build ID to tag `android-16.0.0_r2`. The TrebleDroid release title says `android_16.0.0_r1`. The image includes GPL and LGPL programs, for example e2fsprogs, f2fs-tools, iproute2, iptables, dnsmasq, gptfdisk and exfatprogs (listed in its NOTICE file). |
| **Launcher3QuickStep, patched** | from the GSI above | Magisk module `b1051c-launcher3` | Apache-2.0 | AOSP/TrebleDroid source of the GSI; our patch: `launcher3patch/` in our repo | A modified build of the GSI's own Launcher3 (hooks plus added classes for the transparent nav row on the home screen and the "Clear all" button in Recents), re-signed with the public AOSP test key. |
| **Magisk** | v30.7 | `kit/boot` (`magiskinit` and the xz-compressed `magisk`, `stub` and `init-ld` binaries that go into the boot ramdisk the installer builds) and the Magisk app in the payload | GPL-3.0-or-later | https://github.com/topjohnwu/Magisk/tree/v30.7 (with submodules) | `Magisk-v30.7.apk` sha256 `e0d32d21…afae9ebd5` = the GitHub release asset. It contains BusyBox 1.36.1 (`libbusybox.so`, GPL-2.0), source: https://github.com/topjohnwu/ndk-busybox. Texts: `licenses/GPL-3.0.txt`, `licenses/GPL-2.0.txt`. |
| **Lawnchair** | 15 Beta 3 (`v15.0.0-beta3.0`) | APK in the payload | **Apache-2.0** | https://github.com/LawnchairLauncher/lawnchair/tree/v15.0.0-beta3.0 | Unmodified. APK sha256 `d4200d09…4c7623f86059` = the GitHub release asset. `LICENSE.txt` at this tag is Apache-2.0 (Lawnchair 1.x was GPL-3.0). Text: `licenses/Apache-2.0.txt`. |
| **KOReader** | v2026.07.1 (arm64) | APK in the payload | AGPL-3.0 | https://github.com/koreader/koreader/tree/v2026.07.1 ; https://github.com/koreader/koreader-base ; https://github.com/koreader/android-luajit-launcher (MIT) | Unmodified official APK: sha256 `89cecbe0…6b515ed8b00d152412` = `koreader-android-arm64-v2026.07.1.apk`. It bundles third-party libraries under their own licenses (notices inside the APK). Text: `licenses/AGPL-3.0.txt`. |
| **HeliBoard** | 4.2 | Magisk module `b1051c-heliboard` in the payload (installed as a system app, `/system/product/app/HeliBoard`) | **GPL-3.0** (parts from the AOSP keyboard: Apache-2.0) | https://github.com/Helium314/HeliBoard/tree/v4.2 ; the corresponding source archive of tag `v4.2` is attached to the InKognito release | Unmodified official APK, signed by its developer: sha256 `69b2b2fb349934371b1ed2d3569541cc0bda987f8aaf5eb5f5b157961f76de86` = the GitHub release asset of v4.2. It has no internet permission. Its dictionaries and other bundled parts keep their own licenses (notices inside the app). Texts: `licenses/GPL-3.0.txt`, `licenses/Apache-2.0.txt`. |
| **HeliBoard key look** (overlay `org.b1051c.overlay.heliboard`) | 0.1.1 | Magisk module `b1051c-heliboard` | MIT (ours) | `apps/heliboardoverlay/` in our repo | A resource overlay with our own drawings (outlined keys, light and dark). It changes how HeliBoard looks at run time and contains no HeliBoard code and no Bigme files. |
| **Hebrew dictionary for HeliBoard** (`main_he`, experimental) | file dated 2025-11-11, about 505,000 words | In the payload; the first-boot setup copies it into HeliBoard's data as `dicts/he/main_user.dict` | **CC BY-SA 4.0** | https://codeberg.org/Helium314/aosp-dictionaries (experimental dictionaries, `main_he`) | Unmodified; only the file name differs. Attribution: the aosp-dictionaries contributors; per that repository it is built from the Leipzig Corpora Collection and FrequencyWords. Text: `licenses/CC-BY-SA-4.0.txt`. |
| **Google swipe typing library** (`libjni_latinimegoogle.so`) | from MTG `16.0.0-arm64-20260915_222150` | Magisk module `b1051c-gapps` (`/system/product/lib64`), **only in the download with Google** | **Proprietary, Google LLC** | MindTheGapps (same release as the Google apps row below) | Unmodified. HeliBoard loads it for swipe typing. Not in `system-nogapps.img`. See "Google apps". |
| **ZenOS** (`zenos.koplugin`) | 3.0.0 | KOReader plugins folder (installed at first boot) | GPL-3.0 | https://github.com/xZenLabs/zen-os/tree/v3.0.0 (formerly `AnthonyGress/zen_ui.koplugin`) | Unmodified (checked `main.lua`, `_meta.lua`, `LICENSE.md` against the tag). Bundled fonts: see the font rows. |
| **Bookshelf** (`bookshelf.koplugin`), if included | 4.2.2 | KOReader plugins folder | AGPL-3.0 | https://github.com/AndyHazz/bookshelf.koplugin/tree/v4.2.2 | Unmodified (checked `main.lua`, `_meta.lua`, `LICENSE`). It carries its own font licenses (Caveat and Inter: OFL-1.1, Roboto Condensed: Apache-2.0). |
| **derainbowify** (`derainbowify.koplugin`) | 0.0.12 (commit `f2892a2`, same code as tag `v0.0.12` plus README changes) | KOReader plugins folder | GPL-3.0 | https://github.com/Euphoriyy/derainbowify.koplugin/tree/f2892a219da65bd765d34ae2140a5dd706dfc42b | Lua files unmodified. The two Android arm64 libraries (`libs/*.so`) were **built by us** from that commit's `ffi/` sources with NDK r26d; they are not the binaries from the upstream release zip. The moire filter uses PFFFT (FFTPACK license): `licenses/BSD-PFFFT-FFTPACK.txt`. |
| **KOReader patches** `1-bigme-eink.lua`, `2-bigme-light-dialog.lua` | alpha 2 | KOReader `patches/` folder | MIT (ours) | `koreader/patches/` in our repo | Loaded by KOReader at run time. |
| **MindTheGapps** subset and **Google apps**: Play services 25.15.32, Play Store 45.7.17, Google Services Framework 16-13343139, GmsOverlay, Setup Wizard, Google Restore | MTG `16.0.0-arm64-20260915_222150` | Magisk module `b1051c-gapps` in the payload | Google apps: **proprietary, Google LLC**. MTG's own files: GPL-2.0 per MTG's `LICENSE` (its `proprietary/` files excluded). | https://github.com/MindTheGapps/16.0.0-arm64/releases/tag/MindTheGapps-16.0.0-arm64-20260915_222150 ; https://github.com/MindTheGapps/vendor_gapps (branch `baklava`) | MTG zip sha256 `d60f550e…999bc6a3` = the published checksum. APKs unmodified. The permission and sysconfig XMLs are subsets of MTG's files. See "Google apps" below. |
| **Rubik** | 2.300 | System font (`/system/fonts/Roboto-Regular.ttf`, via module `b1051c-fonts`); E-Ink Control assets | OFL-1.1 (no Reserved Font Name) | https://github.com/googlefonts/rubik ; https://github.com/google/fonts/tree/main/ofl/rubik | The system font file is `Rubik[wght].ttf` from google/fonts, byte for byte, saved under the file name `Roboto-Regular.ttf`. Its internal font names are unchanged. E-Ink Control carries static instances (weights 400, 500, 600) generated from the same variable font. Text: `licenses/OFL-1.1-Rubik.txt` (also inside the module and the app). |
| **Lilita One** | 1.002 | E-Ink Control assets | OFL-1.1, Reserved Font Name "Lilita" | https://github.com/google/fonts/tree/main/ofl/lilitaone | Unmodified (byte for byte the google/fonts file). Text: `licenses/OFL-1.1-LilitaOne.txt` (also inside the app). |
| **Readerly R** and **Libron R** (inside ZenOS) | Readerly 1.11, Libron 0.24 | ZenOS `fonts/` | OFL-1.1 (Libron: Reserved Font Name "Libron") | https://github.com/nicoverbruggen/readerly ; https://github.com/nicoverbruggen/libron | Based on Newsreader by the Newsreader Project Authors. ZenOS does not ship these license texts, so we add them: `licenses/OFL-1.1-Readerly.txt`, `licenses/OFL-1.1-Libron.txt`. |
| **Hyperreadable** (inside ZenOS) | 2.000 | ZenOS `fonts/` | OFL-1.1 | https://github.com/MadSimple/hyperreadable | ZenOS does not ship the license text, so we add it: `licenses/OFL-1.1-Hyperreadable.txt`. |
| **Symbols Nerd Font** (inside ZenOS) | Nerd Fonts 3.4.0 | ZenOS `fonts/` | Nerd Fonts: patched fonts OFL-1.1, code MIT, glyphs from icon sets under their own licenses | https://github.com/ryanoasis/nerd-fonts (see its license audit) | ZenOS ships the MIT text (`fonts/NERD_FONTS_LICENSE`). |
| **Material Design icons** | n/a | E-Ink Control Quick Settings tile glyphs | Apache-2.0 | https://github.com/google/material-design-icons | Each drawable names the icon it uses. |
| **SystemUI plugin API** (interface stubs) | n/a | E-Ink SystemUI plugin (build only) | Apache-2.0 | AOSP `frameworks/base/packages/SystemUI/plugin` | The plugin is signed with the public AOSP test platform key, the same key as the GSI. |
| **Bigme components** | firmware `B1051C_R_4.9.0` | shim, icons; boot, vbmeta and logo are built on the user's computer from the user's own tablet | See "Bigme components" | See below | Stated as facts, without legal conclusions. |

## Bigme components

These parts come from, or are derived from, Bigme's stock firmware `B1051C_R_4.9.0` for the B1051C Pro. We did not
find a license statement from Bigme for any of them. They are listed so that you know exactly what is in the
release.

**Since alpha 3 the release holds no boot, vbmeta or logo image.** `inkognito.py` builds them on the user's
computer from the backup it makes of the user's own tablet; the release ships only InKognito's own parts for them
(`kit/`). What ends up on the tablet:

| Item | Where | What it is |
|---|---|---|
| Linux kernel 4.19.191 (Bigme build of 2026-03-18) | `boot_<slot>`, from the user's own tablet | Bigme's stock kernel binary, unchanged; not in the release. Linux is GPL-2.0, see "Kernel source". |
| Device tree blob | `boot_<slot>`, from the user's own tablet | Bigme's stock DTB with one change: the microSD clock is capped at 50 MHz. |
| Boot ramdisk | `boot_<slot>`, from the user's own tablet | Bigme's stock ramdisk with Magisk 30.7 and our `overlay.d` files added from `kit/boot`. |
| vbmeta | `vbmeta_<slot>`, from the user's own tablet | Bigme's stock vbmeta with the flags field set to 1 (hashtree verification disabled). |
| Boot logo partition | `logo`, from the user's own tablet (optional) | Images 0, 2 and 38 are replaced with InKognito art from `kit/logo`; the rest stays as on the tablet. Image 2 (the low-battery start screen) keeps a small area of Bigme's stock low-battery icon in the top right corner. |
| Color lookup tables | compiled into the HWC shim | Two 512x512 3D LUTs generated from the stock files `/system/etc/g_c001` and `/system/etc/g_c002`. They are not in the public repo; it has the generator script instead. |
| Color processing logic | HWC shim | A CPU version of the color shader found in Bigme's stock `/system/bin/surfaceflinger` (contrast and brightness curves, "vivid" saturation, LUT lookup). The shim also uses struct offsets in Bigme's vendor HWC that were found by disassembly. |
| Stock app icons | Lawnchair icon pack `org.b1051c.iconpack` and Magisk module `b1051c-icons` (icon overlays) | Bigme/XRZ stock launcher icons (the xLauncher3 color and mono sets and the XRZ e-book launcher set) extracted from the stock system image, mixed with our own drawings. Many of them show third-party app logos (for example Google, Amazon and other apps). E-Ink Control also uses the shape of Bigme's stock squircle icon mask. |
| Touch and pen configuration | module `b1051c-eink-compat` (`/system/usr/idc/*.idc`) | Input configuration files for the stock touch panel and Wacom digitizer. They carry the AOSP Apache-2.0 header. |

Not included: Bigme's vendor partition (the vendor HWC, the e-ink driver, firmware blobs) stays on the device and is
not redistributed. Nothing else from the stock system image is shipped.

### Kernel source (GPL-2.0)

Draft text for the release:

> InKognito does not distribute a kernel. The installer keeps the kernel that is already on the user's tablet,
> Bigme's own binary from firmware B1051C_R_4.9.0 (Linux 4.19.191), unchanged.
> Linux is licensed under the GNU General Public License, version 2 (`licenses/GPL-2.0.txt`).
> As of 2026-10-21 we could not find a public kernel source release from Bigme for the B1051C Pro or its MT6877
> platform. We searched GitHub, Bigme's web presence and the HiBreak community resources. We do not have Bigme's
> kernel source and cannot provide it. Bigme distributes this kernel on every B1051C Pro, so requests for its
> source should go to Bigme. If Bigme publishes it, we will link it here.

## Google apps

Google Play services, Google Play Store, Google Services Framework, Google Setup Wizard, Google Restore and the
swipe typing library `libjni_latinimegoogle.so` are proprietary software of Google LLC. They come unmodified from MindTheGapps 16, which repackages
them from Google's images. MTG's `LICENSE` says it does not own these files and refers to their authors for
licensing. Neither InKognito nor MindTheGapps grants any license to them. Their use is subject to Google's terms:
https://policies.google.com/terms and https://play.google.com/about/play-terms/. Google licenses its apps to device
makers through separate agreements. This project has no such agreement, and the device is not Play Protect certified.

## Brand art

The InKognito name, badge, boot logo and boot animation, wallpapers, shutdown animation and spray-paint nav buttons
were provided by matanmi12 for this project. They are not covered by the MIT license of the code unless
matanmi12 says so.

## Files in `licenses/`

| File | For |
|---|---|
| `MIT-InKognito.txt` | Our code |
| `Apache-2.0.txt` | Lawnchair, AOSP parts (patched Launcher3, plugin API, the AOSP keyboard code inside HeliBoard), Material Design icons, Roboto Condensed (Bookshelf) |
| `GPL-2.0.txt` | Linux kernel (Bigme binary), BusyBox in Magisk, MindTheGapps' own files |
| `GPL-3.0.txt` | Magisk, HeliBoard, ZenOS, derainbowify |
| `AGPL-3.0.txt` | KOReader, Bookshelf |
| `CC-BY-SA-4.0.txt` | Hebrew dictionary for HeliBoard |
| `OFL-1.1-Rubik.txt` | Rubik |
| `OFL-1.1-LilitaOne.txt` | Lilita One |
| `OFL-1.1-Readerly.txt`, `OFL-1.1-Libron.txt`, `OFL-1.1-Hyperreadable.txt` | Fonts bundled in ZenOS |
| `BSD-PFFFT-FFTPACK.txt` | PFFFT, inside derainbowify's Android library |

The GSI's own notices for AOSP and TrebleDroid components are in the image at `/system/etc/NOTICE.xml.gz`.
