# Stock kernel e-ink pen path (B1051C Pro, kernel 4.19.191, eink driver v5.03_20260301)

> **Status 2026-10-20 22:55:** the author (kernel RE agent) re-checked Q2–Q5 against the disassembly and reported **several errors** in them; only **Q1** (incl. the Hyper panel-size gate) is confirmed. Treat Q2–Q5 as unverified notes until each claim is re-checked in `kernel/dis/`. Sections Q6 / implications were never written.
>
> **Update 2026-10-21:** **Q2 re-checked and corrected** (VCOM flag, verify on every update, the four `is_regal_support` conditions, the remap promotion and the 3 s demotion, panel identity). Q3–Q5 are still unverified.


Offline analysis of the stock kernel, plus read-only checks on the device (2026-10-20).
Each claim is tagged **[V]** (verified: function + Image offset / disassembly) or **[I]** (inferred).

## Kernel extraction
`BIGME_B1051C_system_dump/boot_b.bin` is an Android boot image v2, page size 0x800. The kernel at offset 0x800 is
**gzip-compressed** (magic `1f 8b`), not raw. Decompressed it is a raw arm64 `Image`: "ARMd" at 0x38, text_offset
0x80000, image_size 0x337f000 (= `_end − _text`). Banner: `Linux version 4.19.191 ... #1 SMP PREEMPT Wed Mar 18
2026`. Image file offset = runtime addr − `_text` (0xffffffa6ea080000). Pointers stored in .data/.rodata are unslid at
link base 0xffffff8008080000.

Artefacts (all proprietary, keep local, never upload):
- `pen/kernel/bin/Image` — decompressed kernel.
- `pen/kernel/dis/*.s` — annotated disassembly (eink_core.s = full driver; drm_eink_ioctls.s; one file per function).
- `pen/kernel/scripts/` — `kdis.py` (annotating disassembler), `xref.py` (whole-kernel BL/ADRP xref), `jtab.py`
  (byte-jump-table decoder), `d.sh`/`x.sh` wrappers.

## Device facts confirmed read-only
- `/proc/device-tree/eink`: `eink_panel_type=0x21`, `eink_width=0x9c0 (2496)`, `eink_height=0x798 (1944)`,
  `eink_fb_width=0x9b0 (2480)`, `eink_fb_height=0x744 (1860)`, `eink_bit_num=5`, `eink_max_fps=0x0f`,
  `eink_dynamic_fps=1`, `eink_manufacturer=0`. [V]
- `/dev/dri/card0` is `crw-rw---- system system` (SELinux `dri_device`). [V]
- `eink_debug/version = v5.03_20260301`, `panel_type=0x21`, `global_mode=0x0`, `temperature=30`, `clean_a2=0`,
  `print_level=0`. The loaded waveform (`eink_debug/waveform`) is `570_VSK042_HV1501_EC103KH3C6_SC1452-KCE_TC.awf`
  (the LK/boot waveform), **not** the one named inside `/system/eink_key` (`470_...EC103KH2C1...`). [V]

## Q1. is_handwritting_mode and which modes are "handwriting"

`is_handwritting_mode` (off 0x7ea294) is 5 instructions: `return ((m & 0xff) - 5) < 2`, i.e. **(m & 0xff) ∈ {5, 6}**,
upper/flag bits ignored. [V] It has **no callers** in the kernel (dead symbol); the same `(x-5)<2` test is inlined in
the hot paths instead (`__queue_image`, `decode_one_frame`, `__active_soft_pipe`, `get_default_dither_type`,
`is_clean_mode`). [V]

Mode 1029 = 0x405, 1030 = 0x406. `& 0xff` gives 5 and 6, so **both are "handwriting"**. [V] In `remap_eink_mode`
(off 0x7cdc20, jump table at ea84dcec) **mode low-byte 5 and 6 both map to waveform index 5**, and index 5 = the **DU**
waveform (A2_IN/A2_OUT are empty on this unit, so DU is the local-update waveform). [V]

0xb7 (Hyper) is **not** handwriting: 0xb7 & 0xff = 0xb7, not in {5,6}. [V]

Why 0xb7 fails on this device (independent of eink_key): `check_valid_waveform_mode` (off 0x7bb438) runs a 0xb8-entry
jump table; mode 0xb7 is the one entry routed to `is_support_hyper_mode` (off 0x7d9bc8), which returns
`panel_width * panel_height < 0x206701` (2,123,521 ≈ 1680×1264). This panel is 2496×1944 = 4,852,224, so
`is_support_hyper_mode()==0` and 0xb7 is rejected with "This mode(0xb7) is unsupport". **Hyper cannot work on this
panel at all, with or without eink_key.** The "eink handwrite pre-process fail, ret=-1" line is just the generic failure
message of `drm_eink_update_ioctl` (it prints it whenever `eink_pre_process` returns < 0). [V]

trace_handwritting_buffer (off 0x7cb408) is a debug dump-to-file helper; not on the latency path. [I]

## Q2. eink_pre_process control flow and what the "verify" checks

**Re-checked 2026-10-21** against the stock kernel extracted for the review (`backups/review-2026-10-21/kernel.img`,
same build) with capstone and a whole-kernel BL/ADRP xref. Offsets below are Image file offsets. The earlier version is in
`backups/review-2026-10-21/KERNEL-PEN-PATH.md.pre-q2fix`.

`eink_pre_process` (off 0x7eb1ec) runs once per `DRM_IOCTL_EINK_UPDATE`:
1. `ktime_get_real_ts64`; null/manager/buffer/pipeline checks (returns -1..-4). [V 20.10, not re-checked]
2. Wait up to 0xc7 × 10 ms for resume if `manager+0x458` is set (-5 on timeout). [V 20.10, not re-checked]
3. `load_waveform_from_file("/data/waveform.bin")`: an **optional OTA** path. It is absent on this unit, so the driver logs
   "no OTA waveform update" and continues. [V 20.10, not re-checked]
4. **One-time setup (corrected):** `manager+0x2d9` is the **VCOM-set-done** flag, not a "verified" flag. While it is 0,
   the function calls `tps65185_vcom_set()` and sets it to 1 (0x7eb31c → 0x7eb5d0..0x7eb614). A global byte
   (`0x2c98df8`) guards a one-time `__init_wavedata_buffer` (0x7eb328 → 0x7eb540..0x7eb5ac). [V]
5. **`eink_regal_verify("/system/eink_key")` runs on every update** (0x7eb334, the string is at 0x17b6b65). It returns
   at once when the verified byte is set. If it returns non-zero, the function prints "eink verify fail" and **jumps
   back to the same continuation (0x7eb338 → 0x7eb5b8 → 0x7eb5cc → 0x7eb33c). A failed verify never aborts the
   update.** [V]
   - Without the key file, this means a `filp_open` of a missing file plus two log lines on every update. The 20.10
     pen session logged 550 "fail to open waveform file(/system/eink_key)" + 550 "eink verify fail" lines. The cost
     is small but real. [V]
6. Apply the kernel global overrides into the update config when they are non-zero: `g_eink_global_mode` → cfg.mode,
   `g_contrast` → cfg+0x20, `g_saturation` → cfg+0x1c, `g_brightness` → cfg+0x24 (starts at 0x7eb33c). [V 20.10, not re-checked]
7. If cfg.mode == 0xb5, run an fps gate (at least 251 ms between 0xb5 updates). [V 20.10, not re-checked]
8. Call the real worker through the CFI-checked pointer `manager->update_fn (+0xd0)` → **`__queue_image`**.
   [V 20.10, not re-checked]

### eink_regal_verify / EInk_Init: what "verify" is
`eink_regal_verify` (off 0x7bb810; its only caller is `eink_pre_process`). The state is `g_waveform_file` (0x2c97c20):
byte +0 = "load", byte +1 = **verified**, mutex at +0x20. [V]
- Verified byte == 1 → return 0.
- `is_oed_gray_panel()` → set verified, log "verify oed mask ok", return 0 (no file needed). **Not this panel** (see
  below).
- Otherwise: path NULL → -1; `filp_open` fails → **-2** ("fail to open waveform file(%s)", which is what we see);
  `vmalloc` fails → -3; short read → -4. Then **`EInk_Init(buf)`**: non-zero → -5 ("verify eink regal fail");
  zero → set verified = 1, log "verify eink regal ok". It always logs "regal=%d, load=%d" on the way out. [V]

`EInk_Init` (off 0xbe30) has exactly one caller (`eink_regal_verify`) and is the only writer of the global `FLAG`
(0xffffffa6ebe4b480). `FLAG` is read by `eink_process`, `eink_process_index` and `test_flag`. `test_flag` is called by
`eink_process_neon` and `eink_process_color_neon`, and those two are called only by `xrz_eink_regal_process` (the
REGAL pixel pre-process, run from `eink_pixel_process_thread`). So the file gates the REGAL pixel path. [V] The genuine
stock file from this unit (`work/einkkey/eink_key`) is the only input we use; the check internals are deliberately not
documented here. Whether any waveform data is kept from the file is [I].

**Panel identity on this unit:** `__eink_get_sys_config_from_dts` reads `eink_panel_type`, `eink_manufacturer` and
`eink_mirror` into consecutive u32s at `eink_manager+0x68/+0x6c/+0x70` (0x7da1b4..0x7da234;
`disp_get_eink_mirror_flag` reads +0x70). The live DT has panel_type 0x21 and manufacturer 0. [V] Hence:
- `is_oed_panel` = (+0x69 bit 0) or manufacturer == 1 → **false**;
- `is_oed_color_panel` = (panel_type bit 8) or (manufacturer == 1 and panel_type != 0) → **false**;
- `is_oed_gray_panel` = manufacturer == 1 and panel_type == 0 → **false**. [V]

### Which modes depend on the verify (corrected)
`is_regal_support` (off 0x7bb6f4; called by `remap_eink_mode`, `eink_new_process` and the two
`new_process_calc_index_for_overlap_part_for_du_{4,5}bit` functions) returns true only if **all four** hold. The old
text listed only the last one. [V]
1. The waveform state word (`0x2c97c38`) == 5, i.e. a 5-bit waveform; otherwise "not 5bit waveform". DT
   `eink_bit_num=5`; the runtime value was not read. [V code / I value]
2. Not an OED color panel; otherwise "OED panel without REGAL mode data". This holds on this unit, see above. [V]
3. The loaded waveform has REGAL data: a pointer compare (`[0x2c97c30]->+0x18` vs `0x2c97c30+0x120`), otherwise
   "waveform without REGAL mode data". **Unknown for the LK waveform `570_..._EC103KH3C6_...`.** [V code / I value]
4. The verified byte == 1, otherwise "eink verify key fail". [V]

These messages print only at `print_level >= 2` (ours is 0), which is why they never show up in our logs.

`remap_eink_mode` (off 0x7cdc20), tail at 0x7cdf3c: [V]
- A byte (`eink+0x2a8`) is set by every handwriting (5/6), A2 (0x10) or 0x89 update and is cleared once 3001 ms pass
  without one. **While it is set, REGAL-class modes (0x21/0x40/0x80/0xb4) become 0x484 (GU16 + flag 0x400)
  regardless of the key.**
- Otherwise, when `disp_get_eink_panel_type() != 0` (ours is 0x21) or `is_x6_board()`, **a GU16 request (0x84) is
  promoted to REGAL 0x40**.
- Then, if `!is_regal_support()` and the mode is 0x40/0x80/0xb4 → print "this machine not support regal mode" and use
  **0x84 (GU16)**.

**Every non-REGAL mode (DU 2, GC16 4, A2 0x10, Fast 0xb2, Extreme 0xb3, handwrite 5/6, GL16, …) does not need eink_key.**
The DU/GC16 waveform offsets exist in the loaded LK waveform (`eink_debug/waveform`: GC16 @0x10c998, DU @0x20bb08, A2
@0x260bc0). [V 20.10, not re-checked]

**Net effect of a missing `/system/eink_key`:** the update **proceeds normally** in every mode; Regal (and, on this panel,
GU16) requests end up as 0x84 GU16. This matches the device: "verify fail" appears in all modes, yet
Fast/Extreme/DU/GC16 updates draw. **eink_key is not needed for a pen fast path, and the Hyper failure is the separate
panel-size gate (Q1).** [V]

**What this means for the eink_key experiment (wave 3):** a genuine key can satisfy only condition 4. True REGAL also needs
condition 3, a REGAL table in the LK-loaded waveform, which we don't know about. Note also that `/system/eink_key` names a
different waveform (`470_..._EC103KH2C1_...`) than the one loaded (`570_..._EC103KH3C6_...`). Plan the experiment as:
1. Put the key at `/system/eink_key` (system partition write, so it needs the owner's approval; or a Magisk overlay).
2. Look for "verify eink regal ok" and "regal=1".
3. With `print_level` 2, check whether "waveform without REGAL mode data" still appears.
4. Only then compare Regal with GU16 on the panel.

Even with a valid key, Regal falls back to GU16 for 3 s after any pen or A2 update. [I for the outcome]

## Q3. What is different about the handwriting path vs a normal update

There is **no separate kernel entry point** for handwriting. Every update enters through `DRM_IOCTL_EINK_UPDATE` →
`eink_pre_process` → `__queue_image` → the soft-pipe pipeline. "Handwriting" is just a **mode value and a layout**, and
the differences are data-driven inside `__queue_image` (off 0x7ce198) and `eink_new_process` (off 0x7cb47c): [V]

- **Waveform:** modes 5/6 map to index 5 = **DU** (1-bit, 2-level, fastest). A "normal" page uses GC16 (index 4,
  ~16-level) or GU16/REGAL. DU has far fewer frames: on this unit the kernel dumps DU ≈ tens of frames vs GC16 larger.
  Confirmed shape from `dump_any_mode_data` ("total_frame", "real_total"). [V]
- **"switch to DU":** in `__queue_image`, when mode low-byte == 5 and the panel is kaleido/color, it logs
  "[Handwrite] switch to DU" and sets a per-pipe flag (`+0x68`) so the pipe uses the DU waveform and the color path is
  bypassed; for mode 1 (INIT) it resets that flag. [V]
- **Color conversion skipped/cheaper:** DU/handwrite feeds `convert_rgb2gray_for_kaleido3_A2` /
  `convert_rgb2gray_for_gray_panel` (1-bit output) instead of the 16-level `convert_rgb2gray_for_color_panel` + color
  LUT. The NEON kernels `eink_convert_rgba_to_rgb16_neon` (ld4/st3, strips alpha) and `dithering_Y8_to_Y1` do the work.
  [V]
- **Dithering:** `get_default_dither_type` (off 0x7ce074) returns a cheaper dither for the fast modes; `do_effect_
  process` only re-dithers when the rect's `need_dither` flag is set, and caches the last dither type. [V]
- **Handwriting has a dedicated collision policy** (see Q4): "handwritting collision with system update",
  "handwritting path not support this mode", "handwritting not support no-overlap area update", "[Handwrite] wakeup
  pixel process", "handwritting active pipe fail for a long time, force reset". These strings are only reached when the
  incoming mode is handwriting; the handwriting rect is scheduled with higher urgency (`eink_triger_boost_cpu(2000ms)`
  for mode 0x89/handwrite in `remap_eink_mode`). [V]
- **No separate overlay plane.** `eink_update_plane`/`eink_update_crtc` (the DRM atomic path, off 0x7eb05c / 0x7ba0d4)
  is one plane; the composer's HANDWRITTEN/HANDWRITTEN_BG layer flags are an *SF/HWC* concept (desc 71), they never
  reach this kernel — the kernel only ever sees the final mode+rect+fd in the ioctl. [V]

**Takeaway:** the kernel-side "speed" of the pen path is entirely explained by (a) DU 1-bit waveform (few frames),
(b) skipped color/16-level conversion, and (c) a small rect. All three are selectable through the ordinary ioctl by
choosing mode 5 (or A2 0x10 / DU 2) and a small area. There is no secret kernel stroke renderer. [V/I]

## Q4. The update pipeline — collisions, concurrency, queue depth, cost vs area

Pipeline objects are built in `pipeline_manager_init` (off 0x7d4e78) and `ring_buffer_manager_init` (off 0x7cf7a8): [V]
- **0x5a = 90 "soft pipes"** pre-allocated (loop `cmp x25,#0x5a`), each 0x120 bytes, on a free list. A soft pipe is one
  in-flight update region. [V]
- **10 image nodes** in the ring buffer (`mov w8,#0xa` in ring_buffer_manager_init) — the queue depth for pending
  update images. [V]
- Three decode worker threads `calc_wavedata_thread0..2` (named "decode_thread0..2", SCHED_FIFO prio 2) plus the single
  `fresh_frame_thread` ("fresh irq thread", prio 0x63) and `eink_pixel_process_thread` ("eink pixel proc", prio 0x62),
  all bound and RT-scheduled. Wavedata for a frame is split across the 3 decode threads via `calc_wavedata_single`. [V]

**Collision / merge (the key question for small rects):**
- `__queue_image` builds an image node, then walks the **collision list** and **used list** (`rect_is_overlap`,
  off 0x7b9614). Two regions "collide" only if their rectangles overlap *and* they are different update instances. [V]
- `soft_pipe_get_collision_relationship` (off 0x7d6c3c): for the incoming rect it ORs together a 90-bit mask of every
  active soft pipe whose rect overlaps it, under a spinlock. If the overlap is small it allocates a per-pixel mask and
  does a **partial** update of just the overlap; if the overlap area ≥ the new area (or the modes are incompatible,
  e.g. a2-vs-other) it treats it as a whole collision. Logs "overlap area is small, partial update" vs "overlap area is
  too large". [V]
- **Non-overlapping regions run concurrently.** Each gets its own soft pipe; `active_all_valid_soft_pipe` →
  `__active_soft_pipe` → `triger_vsync` activates all ready pipes and the fresh-frame thread clocks them out together.
  Overlapping regions are serialized (the new one waits, `msleep(2)` poll loop up to ~0x12b iterations, or merges). [V]
- **Merge:** `try_to_merge_image` / `merge_mode` / `rect_merge_check` can coalesce a new small rect into a pending one
  when modes match ("handwriting not to merge" shows handwriting deliberately refuses merge to stay low-latency). [V]

**Per-update cost vs area [V/I]:**
- Cost ≈ (number of waveform frames for the mode) × (pixels in rect) processed by the decode threads, then streamed by
  the fresh-frame thread at the panel frame rate (`eink_max_fps=15`, `eink_fresh_hz=0x55`). [I from code shape]
- DU/A2 have the fewest frames; a small rect has the fewest pixels. **A small-rect A2/DU update is dramatically cheaper
  than a full-screen GC16/Extreme update** — both because of fewer frames and fewer pixels, and because several small
  non-overlapping rects can be in flight at once. Our current path submits **full-screen** (0,0..2479,1859) Extreme
  frames every time, which is the worst case on both axes. [V for our frames = full-screen; see keep-drawing log]
- `__get_remain_usable_pixel_cap` restricts *partial* (non-full) updates to AUTO mode (0x89/0xff): "only AUTO mode
  support partial update". So to get true partial-area scheduling the mode must be AUTO or the per-rect dirty area must
  be passed as the update rect (which the DU/A2 path does honor — the rect in the ioctl is the update area). [V]

## Q5. DRM_IOCTL_EINK_UPDATE — struct, flags, and who may call it

**ioctl number** `0xC04464CF` = `DRM_IOWR('d', 0xCF, <0x44-byte struct>)`. In `mtk_ioctls`/`drm_ioctls` index 0xCF,
flags = **0x30**. [V]

**Permission flags** are decoded by `drm_ioctl_permit` (off 0x705ae8): bit0 = DRM_AUTH, bit1 = DRM_MASTER, bit2 =
DRM_ROOT_ONLY, bit5 = DRM_RENDER_ALLOW. **0x30 = bit4 | bit5**. Bit4 is DRM_UNLOCKED (no permission meaning); bit5 =
DRM_RENDER_ALLOW. There is **no DRM_AUTH, no DRM_MASTER, no DRM_ROOT_ONLY**. [V]
→ **Any process that can open `/dev/dri/card0` (or a render node) may issue EINK_UPDATE; it does not need to be the DRM
master/composer.** This is exactly how the stock standalone `handwrittenservice` (a non-composer process) issued it.
The only gate on our GSI is the file's SELinux/unix perms (`crw-rw---- system system`, `dri_device`): a process in group
`system` / with the right sepolicy can open it. [V]

**Argument struct (68 bytes).** Confirmed three ways — the kernel copies fields [x1+0]..[x1+0x40] in
`drm_eink_update_ioctl` (off 0x71d978); the shim logs them (`work/shim/hwcshim.cpp`); the stock `handwrittenservice`
`mtk_commit` builds the same struct before `ioctl(fd, 0xC04464CF, &u)` (off 0x18320 in
`pen/userspace/disasm-20261020/handwrittenservice.dis.txt`). [V]

| off | field | meaning |
|----|-------|---------|
| 0x00 | `mode` | refresh mode + flag bits (low 16 = mode; dither in bits 29–31 via AUTO_DITHER) |
| 0x04 | `left` | update rect, inclusive |
| 0x08 | `top` | |
| 0x0c | `right` | |
| 0x10 | `bottom` | |
| 0x14 | `dither` | dither type (0 = use default; observed 3 for Fast/Extreme) |
| 0x18 | `rsvd0[0]` | overwritten from kernel `g_eink_global_mode` when non-zero (see pre_process step 5) |
| 0x1c | `rsvd0[1]` | overwritten from `g_saturation` when non-zero |
| 0x20 | `rsvd0[2]` | overwritten from `g_contrast` when non-zero |
| 0x24 | `rsvd0[3]` | overwritten from `g_brightness` when non-zero |
| 0x28 | `fd` | **dma-buf fd** of the source image |
| 0x2c | `format` | 0 = buffer already gray8 (HWC did CPU convert); **2 = RGBA**, kernel converts (see below) |
| 0x30 | `rsvd1[0]` | x offset (handwrittenservice packs layout x here) |
| 0x34 | `rsvd1[1]` | y offset |
| 0x38 | `stride` | pixels |
| 0x3c | `height` | pixels |
| 0x40 | `rsvd2` | handwrittenservice packs the layout h/extra here; kernel copies it but the DU path ignores it |

Note the `w19` at [x1+0x28] in the kernel is the **fd**, used immediately: `dma_buf_get(fd)` →
`dma_buf_begin_cpu_access(DMA_FROM_DEVICE=2)` → `dma_buf_kmap` → `eink_pre_process(mgr, crtc?, &localcopy, ...)` →
`dma_buf_end/kunmap/put`. If fd==0 the kernel skips the dma-buf and uses the plane path. [V]

**format=2 meaning:** the field at +0x2c selects the CPU conversion. The HWC/shim fills it from
`ro.vendor.xrz.screen_type>0 ? 2 : 0`. format!=0 ⇒ kernel reads the dma-buf as **RGBA_8888** and runs
`eink_convert_rgba_to_rgb16_neon` + the gray/color conversion itself; format==0 ⇒ the buffer is already gray8 in place.
Our shim already logs `format=2 stride=2480 height=1860` for the full-screen client target. [V]

**Buffer requirements:** the fd must be a **dma-buf** (ION) that `dma_buf_get` accepts, large enough for
`stride*height*bpp` (bpp 4 when format=2). The stock handwrittenservice allocates an ION buffer that the app draws into
(`HandwrittenClient.getCanvas()`), and passes its fd. On our GSI the shim passes the **SF client-target dma-buf fd**.
Either works; the kernel does not care about the producer, only that the fd is a mappable dma-buf of the declared
geometry. [V]

Companion ioctls: `DRM_IOCTL_GET_EINK_TYPE 0xC00464D0` (flags 0x10 = DRM_AUTH; returns panel type), `DRM_IOCTL_RELOAD_
WAVEFORM 0xC00464D1` (0x10, triggers `g_waveform_file` reload — **do not call, it re-reads waveform**),
`DRM_IOCTL_EINK_WAIT_VSYNC 0xC00464D2`/`0xC00464D1` naming varies; `drm_eink_wait_vsync_ioctl` (off 0x71dcf8) blocks on
one of 4 per-"monitor-id" waitqueues until the fresh-frame IRQ fires (`triger_vsync`, off 0x71dbcc). Flags 0x30 =
RENDER_ALLOW, so a non-master can wait for the panel-done fence. [V]
