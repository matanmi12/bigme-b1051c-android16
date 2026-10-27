# Vendor HWC commit policy: why pen frames go out full-screen, and how to get small-rect pen commits

2026-10-20. Offline disassembly of `work/vendor_files/lib64/hw/hwcomposer.mt6877.so`, plus the Keep session log
`pen/keep-drawing-session.log` and read-only device checks.

- **Binary.** BuildID `bdef0c8d90fec30662441f364f4f06b7`, the same sha256 as the device's `/vendor/lib64/hw/hwcomposer.mt6877.so`.
  The device's `hwcomposer.mtk_common.so` is our own shim (it equals `work/shim/out/*.stripped.so`).
- **Labels.** **[V]** = verified at the cited address or in the log. **[I]** = inferred. Addresses are file virtual addresses.
- **Kernel facts** come from `KERNEL-PEN-PATH.md`. They are not repeated here.
- **Artefacts** (proprietary, keep local, never upload):
  - `pen/hwc/ann/`: annotated disassembly. `ALL_hwcomposer.ann` covers the whole `.text`; there is also one file per key function.
  - `pen/hwc/scripts/`: `ann.py` (capstone annotator: PLT, GOT, APS2 relocations, strings, float constants), `ann_all.py`, `rng.py`
    and `commit_timeline.py`, which parses the log.
  - `pen/hwc/keep-commit-timeline.txt`: timing statistics for the session.

## Summary

1. **Every normal commit covers the whole panel by construction.** `checkLayersAndSetCommitInfo` writes the commit rectangle
   exactly once, as (0,0,W,H) from `HWCDisplay::getWidth/getHeight` (stored @0xefbbc/0xefbc4). Per-layer damage only decides
   *whether* to commit (the "No dirty region" skip), the logged dirty ratio and the rect list. [V]
2. **Partial updates exist only as "rect commits".** These are up to 10 rects, one `EINK_UPDATE` per rect, with mode = layer
   mode|0x400. The HWC enables them only while a **handwriting layer** is present (desc-71 flags HANDWRITTEN, or
   SURFACEVIEW+RECT+HANDWRITTEN) and nothing forced a full refresh. On the GSI no layer carries these flags, so a rect commit
   never happens. [V]
3. **The Keep session:**
   - All 412 commits were normal, full-screen, mode 0xb3, every layer CLIENT. [V]
   - During the 9.6 s pen-down stroke, SF composed about 40 times a second, but the HWC skipped **470 of those frames** as
     "No dirty region". Only **13 commits** happened, and only when Keep's own window or the SurfaceView redrew. [V]
   - Why: Keep's ink layer (a front-buffered layer, same buffer handle throughout) delivered no surface damage. [I]
   - So the ink reached the panel in bursts, each as a full-screen Extreme commit of about 36 ms. [V]
4. **Commits are serialised on one OverlayEngine thread** (vsync wait, then CT fence, then memcmp and memcpy of 18 MB, then
   the ioctl). The HWC never waits for the panel. Per commit we measured:
   - policy to commit start: 15 ms (median);
   - commit start to "commit OK": 36 ms (median). Of that, **our shim's colour pipeline takes about 15–20 ms** and the ioctl
     8–16 ms. [V]
   - Back-to-back the pipeline manages about 28 commits a second. [V]
5. **Best route** (details in §6):
   - In `hook_mtk_commit`, for a "pen" profile, replace the vendor's full-screen ioctl with a small-bbox commit in mode 0x405
     (DU) and return 0.
   - The bbox comes from the vendor's own dirty rects (CommitInfo+0x20) or from an exact tile diff of the client target.
   - After the pen pauses, send one "settle" commit in a quality mode over the union of the pen rects.
   - Front-buffered apps such as Keep additionally need damage injection, because their frames never reach the commit stage.

## 1. How update_area is computed (Q1)

### 1.1 Data flow [V]
1. SurfaceFlinger calls `setLayerSurfaceDamage`, which goes to `HWCMediator::layerSetSurfaceDamage` @0x80a94 and then to
   `HWCLayer::setDamage` @0xd317c.
   - The damage is stored **persistently** in layer+0x48 (count) and +0x50 (rects). Nothing in the HWC clears it.
   - "0 rects" and "1 empty rect" compare as equal, so that change is a no-op.
2. `setClientTarget` damage is stored on the client-target pseudo-layer (@0x7d63c). That layer is skipped by the policy
   (layer+0x18 is_ct, @0xeed10). SF sends 0 rects there anyway, so **client-target damage is unused**.
3. Every `presentDisplay` (@0x7b0ac) runs `CommitPolicy::checkLayersAndSetCommitInfo` @0xeea10. For each visible layer sorted
   by Z (layer+0xdfc = desc-71 mode word, layer+0x98 = state-dirty bits):
   - **Skipped:** layers with an empty display frame or is_ct.
   - **SURFACEVIEW (0x10000):** the layer's *visible region* is used as its dirty area ("use visible region" @0xef0b8).
   - **HANDWRITTEN (0x40000) without SURFACEVIEW ("v2 hwl"):** `isWholeLayerDirty` @0xedfe8 tests whether the damage
     bounds equal the source crop.
     - If they do ("v2 hwl whole dirty"), the layer is treated normally and forces a full refresh.
     - If not, the layer is **ignored** ("ignore v2 hwl" @0xef3c8) and rect commits are armed.
   - **SURFACEVIEW+RECT(0x400)+HANDWRITTEN ("v3 hwl" @0xeee88):** the layer is ignored completely and rect commits are armed.
   - **Every other layer:** "use damage region" @0xef140. Each non-zero damage rect has the crop offset subtracted, goes
     through `getCoordinatesRect` @0xedb00 (transform to display coordinates) and is pushed as a `dirty_info_t`
     {Rect, mode|0x400, dither, ime} (@0xef2fc).
   - **0 stored rects means no damage** (@0xef158). This is why our shim's `full_damage` hook exists.
4. `prepareDirtyRegion` @0xee464:
   - merges overlapping rects into bounding boxes and keeps disjoint ones;
   - writes the list to CommitInfo+0x20 and the ratio Σarea/screen to +0x38 ("dirty ratio: %f" @0xefc10);
   - **does not touch the commit rect**.
5. `setCommitInfoForJob` @0xf0230 copies the CommitInfo into the job. `DrmDevice::triggerEinkCommit` @0xf5d38 then takes one
   of two paths:
   - **rect path:** CommitInfo+0x58 set and the vector non-empty. More than 10 rects ⇒ "dirty rect is too many" @0xf6248 and
     a fall-back to the normal path. Otherwise one `mtk_commit` per rect @0xf611c.
   - **normal path:** a single `mtk_commit` @0xf6374 with rect = CommitInfo+0x0c..0x18 minus 1, i.e. **(0,0,2479,1859)**, and
     mode = mode&~0x400, or 0x89 when an IME is present.

### 1.2 CommitInfo layout for this build [V]

| offset | field |
|---|---|
| +0x00 | valid; 0 = no commit |
| +0x04 | OR of all layer flags |
| +0x08 | mode |
| +0x0c..0x18 | rect (l,t,r,b, exclusive), always (0,0,W,H) |
| +0x20/+0x28 | `vector<dirty_info_t>`, 28-byte elements |
| +0x38 | dirty ratio (float) |
| +0x3c | dither |
| +0x54 | format |
| +0x58 | rect-commit enable |
| +0x5c/+0x60 | IME present / IME rect |
| +0x78 | wait-vsync |
| +0x7c | debounce in ms |
| +0x80 | clean: 1 = GC16 now, 2 = delayed GC16 |
| +0x84 | delay-clean ms; −1 means 400 |
| +0x88 | visible-layer-set changed |
| +0x8b | same-frame memcmp; default 1 |
| +0x8c | anti-flicker |
| +0x90 | CommitBuffer |
| +0x98 | handwriting layer present |

Layer state bits (+0x98), set by the HWCLayer setters and cleared in `afterPresent` @0xd2d78:

| bit | meaning |
|---|---|
| 0x1 | dataspace |
| 0x2 | blend |
| 0x4 | frame |
| 0x8 | crop |
| 0x10 | alpha |
| 0x20 | z |
| 0x40 | transform |
| 0x80 | visible region |
| 0x100 | colour |
| 0x400 | composition type |
| 0x800 | visible set |
| 0x4000 | frame |
| 0x1000 / 0x2000 / 0x8000 / 0x10000 | buffer handle |
| 0x20000 | desc-71 mode |

### 1.3 Conditions

| condition | effect | where |
|---|---|---|
| (none: the normal commit) | area = (0,0,W,H) whatever the damage | @0xefbbc/0xefbc4 |
| `vendor.xrz.disable_commit`==1, or no visible layers | "skip: system commit"; nothing committed | @0xeebd4 → @0xef618 |
| no layer has damage rects, and no forced refresh below | "skip: No dirty region"; CommitInfo+0 = 0 | @0xefb50–0xefbb8 |
| rect commit armed | needs a v3 hwl or an ignored v2 hwl ([sp+0x74]=1) **and** none of the next five rows | @0xef984–0xef99c |
| — `force_global_refresh_mode` ≥ 0 | forced full; value 0 = current mode, 4 = GC16; the prop is reset to −1 | @0xef8a4–0xef9b0 |
| — any layer's mode has FORCE_REFRESH 0x20000 | forced full | @0xef954 |
| — display "visible layer set changed" (display+0x1b4) | forced full | @0xeeaa8, @0xef958 |
| — any layer state & 0x4bdf (geometry, alpha, blend, crop, transform, dataspace, visible region or set), or a composition-type change | forced full | @0xef01c–0xef068 |
| — HANDWRITTEN_BG layer with state & 0x408c | forced full | @0xef024 |
| auto clean | `auto_clean_check_disable`≤0, mode 178/179, `auto_clean_enabled`≥1, not (handwriting and split screen), no IME ⇒ rect commit off | @0xef9b4–0xefa68 |
| auto global flush | `needCleanScreen` @0xee89c: rect commit off, plus GC16 | @0xefc2c–0xefcc4 |
| more than 10 merged rects | rect path falls back to full | @0xf5fe0, @0xf6230 |
| waveform reload (`vendor.xrz.update_waveform`) | full GC16 | @0xf5f40 |
| LOGO 0x4000000 / CURSOR / IME | mode 0xb1 / 0xb6 / 0x89 (0x89 = kernel AUTO); area unchanged | @0xefe48, @0xefde8, @0xf6294 |

**Do not affect the area [V]:**
- **dither and colour restoration:** only the dither field (@0xefe58, @0xf0318).
- **Kaleido colour panel:** format = 2 when screen_type > 0 (@0xf04a0).
- **anti-flicker:** non-rect commits only (@0xf0308). On the GSI its debugfs write fails on every commit ("error writing … anti_flicker", 412×). **Fixed 21.10** (module 0.1.28: node 0666 + composer→debugfs SELinux rule); 0 errors since.
- **MULTI_DAMAGE 0x200000:** no bit-21 test anywhere in the binary; layer+0xdfc is read only by `dumpLayer` and `checkLayers`.
- **isFocus (layer+0xe00):** no writer found [I]. The "unfocus" first-layer mode wins.
- **client composition:** the HWC always commits the client target.
- **Our shim:**
  - `full_damage` changes 0 rects into the whole crop. It affects only the skip decision and the ratio.
  - `commit_use_ct` swaps only the fd.
  - The colour pipeline processes whatever area the HWC sends.

**Mode-dependent extras [V]:**
- **Debounce.** For modes outside {178, 179, 183}, CommitInfo+0x7c = 120 ms (80 ms with a handwriting layer). The OverlayEngine
  sleeps that long ("skip,wait for next frame" @0xa864c–0xa865c). Pen profiles must therefore stay in 178/179.
- **Auto global flush.** `countFlushLimit` @0xee754 counts a frame when it covers ≥ 40 % of the screen (const 0.4 @0x46510),
  is ≥ 800 ms after the last counted frame and arrives while `check_commit` ≠ 0.
  - Normal commits always measure 100 %, so with the pen hovering every frame counts.
  - At `clean_frequency` the HWC either:
    - for 178/179: sets clean = 2, logs "trigger clean delay" @0xa6360, requests an SF refresh after 400 ms, logs
      "handle delay clean" and commits **GC16** (CommitInfo+0x80 = 1 then leads to mode 4 @0xf5d78);
    - for other modes: commits GC16 immediately.

## 2. The Keep session (Q2)

**Layer map** (shim `validateDisplay IN`, matched by geometry against today's `dumpsys SurfaceFlinger --hwclayers`):

| HWC layer | what it is |
|---|---|
| L135 | SurfaceView background colour layer (no buffer) [I] |
| L134 | SurfaceView, multi-buffered, 2287×1860 at x=193 [I] |
| **L140** | **front-buffered ink layer**, created at 22:17:47.8 when drawing started; the same handle `…df450` in every dump [I] |
| L133 | Keep window, 2480×1860 [I] |
| L132 | StatusBar [V] |
| L81 | Taskbar [V] |
| L101 | EinkNavRefresh [V] |

All layers were CLIENT, rm=−1 (no desc-71 modes) and `refresh_mode` was unset. [V]

**Dirty ratios** decode to whole layers:

| ratio | area | source |
|---|---|---|
| 0.922177 | 2287×1860 | L134 [V] |
| 1.0 | full screen | L133 [V] |
| 0.021371 | 53×1860 | StatusBar [V] |
| 0.465054 | 2480×865 | a band of L133 [I] |

**Stroke window**, pen-down 22:17:50.07 to 22:17:59.70 (wacom p>0 for 3,468 samples):
- Keep rendered about 80 GL frames a second (`eglDupNativeFence`). SF/HWC ran at about 40 fps.
- **470 "No dirty region" skips and only 13 commits**, all full-screen 0xb3. They occurred at 57.05 (ratio 1.0) and
  58.7–59.0 (ratio 0.465), i.e. Keep's own window redraws, and after pen-up.
- So the ink reached the panel only as a side effect of other layers' damage. [V]
- **Mechanism [I]:** re-submitting the same front buffer gives SF empty damage. SF may also reuse its cached client target.
  Either way the HWC sees nothing to do. The HWC side is [V], because damage is persistent and the HWC never clears it.

**Concrete reasons the pen commits that did happen were full-screen:**
1. Normal commit = (0,0,W,H) by design. [V]
2. No handwriting-flagged layer, so a rect commit is impossible. [V]

**Other costs seen in the session [V]:**
- **Colour pipeline on:** vivid/contrast/brightness 50/50/50 with colour mode 4 uses the 3D LUT, which costs 19.3–19.8 ms per
  full frame. Stock forces vivid 0 in 178/179.
- **Vendor auto-flush GC16 three times:** at 22:15:25.1, 22:16:27.4 and 22:17:29.1, roughly every 62 s, because
  `check_commit` was non-zero on every counted frame (pen hover [I]).
- **The shim's `auto_gc16` never fired:** "page update not counted: 0 %".

**Timing [V]:**

| measure | value |
|---|---|
| policy to commit start | median 15 ms, p90 25 ms |
| commit start to "commit OK" | median 36 ms, p90 55 ms, max 86 ms |
| shim-measured ioctl | 7.8, 9.5, 12.6, 15.8 ms |
| shim time before the ioctl | 15–42 ms |
| bursts | back-to-back: next commit 0–2 ms after "commit OK" |

## 3. RECT, HANDWRITTEN, disable_commit, Hyper (Q3) [V unless noted]

- **The stock pen path does not go through the HWC.**
  - `handwrittenservice` draws strokes and calls `EINK_UPDATE` itself.
  - The HWC's part is to keep out of the way: it ignores HANDWRITTEN layers with partial damage (v2) and SV+RECT+HW layers
    entirely (v3), and sends the remaining UI changes as rect commits so the pen area is not repainted full-screen.
  - **Consequence for us:** never put HANDWRITTEN on the layer that carries the ink. The HWC would drop its updates.
- **RECT 0x400:**
  - is meaningful only in combination with SV+HW (v3);
  - is ORed into every rect-commit element mode (@0xef2fc);
  - is stripped from normal commits (@0xf6280).
  - Rect elements take the *layer's* desc-71 mode, or the default 178, **not** `global_refresh_mode`. Dither 4 is cleared
    unless the mode is 0x404, 0x484 or 0x4b1. With an IME present, elements use 0x489 (RECT|AUTO).
- **`disable_commit`=1** makes the HWC skip every commit ("skip: system commit"). This is the global switch for the case where
  some other process owns the panel. No job reaches the commit stage.
- **0xb7 Hyper** is handled as a fast mode: no debounce (@0xefa78) and grouped with 178/179 for delay-clean (@0xa62d8).
  The HWC has no special path for it. The kernel rejects it through a panel-size gate (see `KERNEL-PEN-PATH.md` Q1).

## 4. Serialisation and cost (Q4)

One OverlayEngine thread per display. `threadLoop` @0xa8568 runs these steps in order [V]:

1. `sem_wait` for a job.
2. Debounce sleep for non-fast modes (@0xa865c).
3. `updateRefresh` (fps_limit; 0 on the GSI).
4. `requestNextVSync` and `pthread_cond_timedwait` (@0xa88fc–0xa8988): waits for the next vsync tick.
5. `waitAllFence` (@0xa8aa0).
6. `loopHandlerEink` (@0xa8f48), then `triggerEink`. If the wait-vsync or DRM-commit flags changed it first runs
   "wait last frames complete" (`HWCThread::wait` @0xa5f88).
7. `preProcessFrame`, then `processImage` @0xa4058:
   - `sync_wait` on the client-target fence @0xa4378;
   - full-frame `memcmp` with the previous commit buffer @0xa4574 ("skip same frame"; it returns 0 and the frame is
     dropped @0xa5e54/0xa5efc);
   - full-frame `memcpy` into one of **3** CommitBuffers @0xa48dc. If none is idle, the frame is dropped
     ("can not find idle commit buffer").
8. `DrmDevice::triggerEinkCommit`, then `mtk_commit`, which is a bare `ioctl(0xC04464CF)` @0xf1458.

The ioctl returns once the kernel has queued the update. **Nothing waits for the panel to finish.** [V]

| cost per commit | full-screen, Extreme |
|---|---|
| SF present to commit start (vsync wait, CT fence, 18 MB memcmp, 18 MB memcpy) | ~15 ms median [V total; split I] |
| shim colour pipeline (3D LUT, 4 threads) | 15–20 ms [V] |
| ioctl (kernel conversion and colour mapping of 4.6 Mpx) | 8–16 ms [V] |
| ceiling | ~28 commits/s, then queueing [V] |

A device experiment can split the 15 ms: E1 in §7 turns on the HWC's own "wait CT fence / compare / copy spend" logs.

## 5. Options for small-rect pen commits (Q5), ranked by effort and risk

| # | option | effort / risk | effect |
|---|---|---|---|
| 0 | Knobs only. Pen profile: colour pipeline off (`vendor.debug.hwc.shim.color=0`, or vivid 0 as stock does for 178/179) and `vendor.xrz.disable_auto_global_refresh=1` (or `clean_frequency=0`). | trivial / none | −15–20 ms per commit; no GC16 flash mid-stroke. Still full-screen. |
| 1 | **Shim pen path** (§6 a–c): small bbox, mode 0x405, vendor commit replaced, settle commit. | medium / medium | ioctl and processing ∝ bbox; DU waveform. Works for apps that post real damage. |
| 2 | Option 1 **plus front-buffer damage injection**, needed for Keep, androidx.ink and similar apps. | medium-high / medium | Makes front-buffered ink reach the commit path at all. Needs E3. |
| 3 | Vendor rect path: flag an "anchor" layer as v3 hwl through desc 71 (`push_refresh_mode`). | medium / high | Same kernel effect as option 1, plus an anchor-layer hack, per-layer modes and 0x4xx modes. **Option 1 makes it unnecessary.** |
| 4 | Direct pen rendering from evdev, handwrittenservice-style, inside the shim or a daemon. `EINK_UPDATE` needs no DRM master (kernel doc). | high / high | Lowest latency: bypasses app, SF and HWC. Ink style mismatch and palm-rejection problems. |
| – | "Shim passes real damage instead of the full rect" | – | **No effect on the area.** Normal commits ignore damage geometry (§1). It is useful only as a bbox input to option 1. |

## 6. Option 1 in detail (shim, `work/shim/hwcshim.cpp`; not modified here)

**(a) Small-bbox pen commits**

- **Activation.** New knobs read in `refresh_knobs()`, set by E-Ink Control for apps with a pen profile:
  - `vendor.debug.hwc.shim.pen` (0/1);
  - `pen_mode` (default 0x405; alternatives 0x10 A2 or 0x2 DU);
  - `pen_max_pct` (e.g. 15);
  - `pen_settle_ms` (e.g. 400);
  - `pen_settle_mode` (e.g. 0xb2, or the app's quality mode).
- **bbox source 1: the vendor's own dirty rects.**
  - Read them in `hook_processImage(self, param, ci)`: CommitInfo+0x20..+0x28 (28-byte elements, display coordinates,
    exclusive) and CommitInfo+0x88.
  - Stash them for the `hook_mtk_commit` call that follows on the same thread for the same frame.
  - Gate on BuildID `bdef0c8d…`. Today's `g_build_id_ok` targets the HiBreak build, so this needs its own flag.
  - The rects miss geometry changes. Track those in the shim: `LayerState` already records frame, crop, z, alpha, blend,
    transform, dataspace and create/destroy. On any change, use the full screen.
- **bbox source 2: exact tile diff.**
  - Compare the client target (`map_dmabuf(g_ct_ion_fd)`) with a shim "shadow" copy of what was committed last.
  - Use 32×32 tiles, a full-pixel compare and 4 threads like `color_apply`.
  - **Do not reuse `frame_change_pct`.** Its 4-sample-per-cell probe misses 1–2 px strokes.
  - Clear CommitInfo+0x8b before `real_processImage` so the vendor's own 18 MB memcmp is not done twice.
  - Update the shadow only inside the committed rects.
- **Commit.** In `hook_mtk_commit`, when pen mode is on, the vendor mode is fast (0xb2/0xb3/0x10/0x2), the change is not a
  geometry change and the bbox area is at most `pen_max_pct`:
  - build a `drm_eink_update` with mode = `pen_mode`, the bbox (inclusive, aligned outward to 8 px [I]), dither 0, fd = CT
    (format 2), and stride and height = full buffer;
  - call `real_mtk_commit` under `g_commit_lock`;
  - add the bbox to `g_pen_dirty` and set `g_pen_last_ms`;
  - skip `color_apply`, since DU output is 1-bit;
  - with an empty bbox, return 0 without an ioctl.
  - Several disjoint rects (at most 4) can be committed separately; the kernel runs them in parallel.
- **Settle.** Generalise `gc16_thread`/`recommit_gc16` so they take a mode and an area. Today they resend `g_last_update`,
  which after a pen commit would GC16 only a small rect.
  - Once `now − g_pen_last_ms ≥ pen_settle_ms` (or when einkd reports pen-up), commit `g_pen_dirty`:
    - mode `pen_settle_mode`, dither 3;
    - source = the vendor CommitBuffer fd of the last pen frame (`orig->fd`), after `color_apply` over that area from the
      client target. That buffer stays valid while the screen is quiet, because 3 buffers rotate.
  - Then clear `g_pen_dirty`.
  - Any vendor full commit outside pen mode also clears it.

**(b) Stopping the vendor's full-screen commit for those frames**

1. **Recommended: commit ourselves in `hook_mtk_commit` and return 0** without calling `real_mtk_commit` with the vendor
   rect.
   - `triggerEinkCommit` only logs the result ("commit OK"); the CommitBuffer and fence bookkeeping happen in
     `processImage`/`triggerEink` regardless [V @0xf6378–0xf644c; caller use I].
   - Pass mode-4 (GC16) and clean commits straight through.
   - The same hook also covers per-rect calls @0xf611c.
2. **Alternative: `hook_processImage` returns 0.** The vendor then drops the frame exactly as for "skip same frame"
   [V @0xa5e54/0xa5efc]. This saves the 18 MB memcpy.
   - It is safe only if the shim does it *instead of* calling `real_processImage`, which allocates a busy CommitBuffer.
   - The shim must then wait on the CT acquire fence itself (`dup` it in `hook_setClientTarget`) [I].
3. **Do not use `vendor.xrz.disable_commit`.** It stops the policy before any job exists, so no frame, fence wait or
   client-target sync reaches the shim. The shim would need its own SF synchronisation.
4. Patching `triggerEinkCommit` is unnecessary; the `mtk_commit` GOT hook is the single choke point.

**(c) Expected gain and risks**

| stage | now (Keep frame, Extreme, full) | pen path, small bbox |
|---|---|---|
| present to commit start | ~15 ms [V] | ~10–15 ms (memcmp skipped) [I] |
| shim before the ioctl | 15–42 ms (colour) [V] | diff 2–8 ms, or ~0 with vendor rects [I] |
| ioctl | 8–16 ms [V] | ~1–3 ms: DU, no colour conversion, small area [I; kernel cost ∝ frames × pixels] |
| commit-path throughput | ≤ 28/s [V] | SF-bound, ~40–60/s [I] |
| panel | Extreme (A2 + dither) on 4.6 Mpx | DU on the bbox; concurrent soft pipes, CPU boost, no merge (kernel doc) |

That is roughly **30–50 ms less per pen frame**, and no queueing at SF rate. End-to-end latency is still about 30–40 ms of
app, SF and vsync plus the DU waveform. Only option 4 removes that first part.

Risks:
- **Ghosting.** DU/A2 leaves residue and thresholds anti-aliased edges; the settle commit, plus an occasional `gc16_req`,
  cleans it up.
- **Colour loss.** Coloured or highlighter ink shows black (1-bit) until the settle commit. Translucent highlighters can turn
  into solid blocks.
- **Collisions with the next vendor commit.** A full-screen Extreme or settle commit that overlaps DU rects still in flight is
  serialised, or partially updated over the overlap, in the kernel ("handwritting collision with system update"). That
  adds delay or causes tearing.
  - Mitigations:
    - in pen mode, replace *all* fast vendor commits;
    - settle only after a quiet period;
    - keep the vendor auto-flush off (`disable_auto_global_refresh=1`);
    - leave HD/Regal out of pen profiles (120 ms debounce).
- **Missed areas.**
  - A bbox taken from damage misses geometry changes; the shim's own tracking falls back to full screen.
  - With front-buffer apps there is no damage and no commit at all, which is why option 2 exists.
- **Large UI changes in DU look poor.** The `pen_max_pct` cap sends those through the vendor path instead.

**Option 2 add-on (front-buffered apps):**
- In `hook_setLayerBuffer`, record (handle, acquire fence) per layer. A *same handle with a new fence* means a front-buffer
  re-submit [I; verify with E1/E3].
- In `hook_presentDisplay`, before `real_presentDisplay` (the policy runs inside present), call
  `real setLayerSurfaceDamage(layer, whole crop)` for such layers when their SF damage is empty.
- Restore the last SF-sent damage afterwards. The vendor stores damage persistently, and SF does not resend damage it
  considers unchanged.
- The bbox then comes from the tile diff; the vendor rect would be the whole layer.
- If E3 shows SF is not re-rendering the client target during strokes, the GSI SF has `debug.sf.disable_client_composition_cache`
  [V string in `/system/bin/surfaceflinger`]. It needs an SF restart and costs GPU work on every frame.

## 7. Device experiments (proposals only; nothing was run)

All commands run as `adb -s <SERIAL> shell "su -c '…'"`.

**E1. Turn on the HWC's own D-level logs.** The level is re-read on every `dumpsys` (`deviceDump` →
`updatePlatformConfig` @0x73e60).
- Commands: `setprop persist.vendor.debug.hwc.log D; dumpsys SurfaceFlinger >/dev/null`, draw for 5 s in Keep, then
  `logcat -d -b main | grep -E "POLICY|OVL|layerSetBuffer|spend"`.
- It shows: per-layer "use damage region" and "dirty rect[]" lines, "rectFlushEnabled=…", the `processImage` fence, compare
  and copy timings, and `layerSetBuffer` acquire fences.
- Risk: log volume while it is on. The property is persistent.
- Rollback: `setprop persist.vendor.debug.hwc.log I; dumpsys SurfaceFlinger >/dev/null`.

**E2. Kernel acceptance of 0x405 and of sub-area commits, without a rebuild.** Uses existing shim knobs.
- Commands: `setprop vendor.debug.hwc.shim.commit_mode 1029; setprop vendor.debug.hwc.shim.commit_height 930`. Per the shim
  header the kernel clamps the area to rows 0..929. Scroll or draw, then check:
  - `dmesg | grep "update image"` for mode, map and area;
  - the shim's "EINK_UPDATE … us" timing;
  - that only the top half changes, in 1-bit.
- Risk: the screen renders wrongly until rollback.
- Rollback: `setprop vendor.debug.hwc.shim.commit_mode -1; setprop vendor.debug.hwc.shim.commit_height 0`, then bump
  `vendor.debug.hwc.shim.gc16_req`.

**E3. What SF does with a front-buffer re-submit.**
- Run E1 plus `setprop vendor.debug.hwc.shim.log 2`, which logs every client-target handle change.
- Draw in Keep and check:
  - whether the client-target handle cycles (SF re-renders) or stays fixed (cache hit);
  - whether L140 gets `layerSetBuffer` with fresh fences.
- Rollback: `setprop vendor.debug.hwc.shim.log 1`.
- The cache property test (`debug.sf.disable_client_composition_cache 1` plus an SF restart) carries the known SF-wedge risk.
  Do it only with owner approval.

## 8. Corrections to earlier notes [V]

- **CONCLUSIONS §2d** says the vendor "auto global flush" is "not a GC16". On this build it **is** a GC16: delayed by 400 ms for
  178/179, immediate for other modes. It fired three times in the Keep log. Because normal commits count as 100 %, the vendor
  and the shim's `auto_gc16` can both flash; disable one of them.
- **Stock display doc:**
  - Rect commits, and therefore "disable rect commit, reason: auto clean", only ever occur while a handwriting layer is present.
    Ordinary UI is always full-area, on stock too.
  - MULTI_DAMAGE is unused by the HWC.
  - 0x89 (the IME mode) is the kernel AUTO mode.
  - ~~Anti-shake cannot work on the GSI while `eink_debug/anti_flicker` is not writable by the composer.~~ Works since 21.10 (0.1.28).
