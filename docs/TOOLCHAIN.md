# Toolchain (macOS, Apple Silicon) — set up 2026-10-17

| Tool | Where | Notes |
|---|---|---|
| adb / fastboot 37.0.0 | `/opt/homebrew/share/android-commandlinetools/platform-tools` | already present |
| Python 3.12 / 3.14 | `/opt/homebrew/bin/python3.12` | needed for lpunpack-style scripts (3.9 system python lacks f-string features) |
| e2fsprogs (debugfs, e2fsck, resize2fs) | `/opt/homebrew/opt/e2fsprogs/sbin/` | read ext4 images without mounting: `debugfs -R "ls -l /lib64/hw" vendor_b.img` |
| xz, zstd, lz4, libusb | brew | |
| Android NDK 26.3.11579264 | `/opt/homebrew/share/android-commandlinetools/ndk/26.3.11579264` | builds the HWC shim: `ANDROID_NDK_HOME=... bash work/shim/build.sh` |
| mtkclient (git, 2026-10) | `tools/venv` + `work/ref/mtkclient` | run: `tools/venv/bin/python work/ref/mtkclient/mtk.py <cmd>`; use `--preloader BIGME_B1051C_system_dump/preloader_boot1.bin` for BROM mode on this UFS board; **do not** install `mfusepy` (breaks on macOS) |
| avbtool.py (AOSP main) | scratchpad copy; re-fetch: `curl -sSL "https://android.googlesource.com/platform/external/avb/+/refs/heads/main/avbtool.py?format=TEXT" \| base64 -d > avbtool.py` | `python3 -I avbtool.py info_image --image vbmeta_b.bin` |
| lpextract_slot.py | `tools/scripts/` | slot-aware extraction of logical partitions from super.bin (public lpunpack.py cannot read slot B) |

Reference repos (read-only clones) in `work/ref/`: awong-dev/hibreak-pro-color (HWC shim + notes), vbbot/HibreakProPatchingService + hibreak_pro_patches, imedwei/inksdk, dbenjaminb/BigmeB1051CProPatcher, joemk88/hibreak-android-auto-fix, micheal65536/hibreak-eink-manager, bkerler/mtkclient.
