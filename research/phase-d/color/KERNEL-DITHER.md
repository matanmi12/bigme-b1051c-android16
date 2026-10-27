# Kernel e-ink dither: types, selection, bit depth (B1051C Pro, eink driver v5.03_20260301)

Offline RE of the stock kernel `pen/kernel/bin/Image` with `pen/kallsyms-20261020.txt` (tools: `pen/kernel/scripts/d.sh`, `x.sh`, `jtab.py`).
Offsets are **Image file offsets** (runtime addr − `_text` 0xffffffa6ea080000). Tags: **[V]** = read in the disassembly at the given offset,
**[I]** = inferred. Scratch disassembly is in the session scratchpad (`dither/*.s`), not kept here.

## TL;DR
- The kernel knows dither types **0..8**. Error diffusion is always **Atkinson** (no Floyd-Steinberg, no Bayer). Type 2 is a **512×512 blue-noise mask**, type 1 is a fixed threshold, and type 6 is a **colour (CFA) variant** of Atkinson.
- **Precedence:** a non-zero `global_dither` replaces the commit's dither. After that, a per-mode fixup applies **only to A2-class modes** (DU 2/3, A2 0x10/0x11, 0x89, 0x90, 0xb5, 0xb7). After that, **every GC16 (mode 4) inherits the dither of the last displayed update**. 16-level modes (HD→0x21, Regal→0x84, GU16→0x21) take any dither value unchanged.
- **Bit depth:** in every 16-level mode the colour panel gets **16 levels per subpixel**. The kernel CFA mapper **floors each channel to 4 bits (`& 0xF0`)** before dithering, so the Y4 dither (type 4, "DITHER_256") does nothing on this panel. Fast and Extreme are **1-bit** (binary dither on full 8-bit CFA data).
- Correction to the 2026-10-22 05:16 log entry: the `def=` value in the kernel's "update image" line **is the effective dither**. `echo 4 > global_dither` did apply dither 4. It changed nothing on screen because type 4 does nothing on this panel.

## Pipeline (colour panel, panel_type 0x21) [V]
1. `eink_pre_process` (0x7eb1ec): if the debugfs globals are non-zero, they overwrite cfg.mode, cfg+0x1c (saturation), +0x20 (contrast) and +0x24 (brightness) (0x7eb33c–0x7eb3a8). It never touches cfg+0x14 (dither).
2. `__queue_image` (0x7ce198): `remap_eink_mode` (0x7ce2a0). Then `get_default_dither_type(mapped_mode, cfg+0x14)` (0x7ce2c0/0x7ce2d0). The result goes to `[sp+0x70]` (0x7ce3fc) and then to **image-node +0xc** (0x7ce770–0x7ce778). The log line `update image:...mode=0x%x(map=0x%x),fd=%d,dither=%d(def=%d)` (printk 0x7ce3e0) prints raw cfg+0x14 as `dither` and the effective value as `def`.
3. Conversion (0x7ce8a8–0x7ceb24):
   - **A2-class mapped modes** {2,3,0x10,0x11,0x89,0x90,0xb7} and 0xb5 → `convert_rgb2gray_for_kaleido3_A2` (0x7cd134) → `k3_map_argb_to_gray` (0x7b89d8): a NEON `tbl` picks one channel per panel pixel and **keeps full 8 bits** (no mask, 0x7b8a9c–0x7b8ab8).
   - **All other modes** → `convert_rgb2gray_for_color_panel` (0x7cc93c) runs `eink_convert_rgba_to_rgb16_neon` (0x7b86e0). Despite the name, this is RGBA8888→RGB888 (ld4/st3, 16 px per iteration). Then `map_color_panel_rect` (0x7f0d50) calls `map_103inch_color_panel_rect_33_ttl` (0x7ef4c8) or `_33` (0x7ef1f0); `is_kaleido3_kh3_panel` (0x7d976c) picks which: panel 0x21 and mgr+0x9c==0x10. Both are called with flag=1, which selects **`and #0xf0`** on every output byte (0x7ef6b4–0x7ef6bc; 0x7ef3dc–0x7ef3e4).
   - The CFA is 1:1, one channel per pixel, with period 3 in x and y (diagonal stripes). The channel-index tables (0=R, 1=G, 2=B) are `_33_ttl` at Image 0x1aca28e = rows [0,2,1] [1,0,2] [2,1,0], and `_33` at 0x1aca285 = [2,1,0] [0,2,1] [1,0,2]. Which of the two runs on this unit is [I], because mgr+0x9c was not read.
4. `do_effect_process` (0x7e8950, called from `eink_pixel_process_thread` with flag=1 and from `eink_new_process` with flag=0):
   - **If node.mode == 4 (GC16) and `g_last_dither_type` != 0, the node's dither is replaced by `g_last_dither_type`** (0x7e89b8–0x7e89d0). `g_last_dither_type` is written for every update that starts its waveform (0x7ed488–0x7ed494).
   - dither == 4 with flag=1 → the area is forced to the **full screen** (0x7e8a34–0x7e8a5c).
   - Mode byte 0x11 → no dither (0x7e8a60–0x7e8a68).
   - Otherwise `dither_process(node.dither, rect, node)` runs on the node's Y8 buffer +0x10 (0x7e8a74), then `auto_select_mode_mp` (colour panel).
5. Index: `sw_calc_index_for_5bit_neon` (0x7b83d8) and `xrz_calc_index_partial_neon` (0x7b87e0) compute the new level as `(Y>>3)&0x1e` (an even 5-bit index) and store old = `Y&0xF0`. So the waveform always sees **16 target levels**.

## Q1. Dither types [V unless marked]
`dither_process` (0x7c72b0) does: type 0 → return (0x7c7330); 1 and 2 inline; {3,4,6,7,8} (mask 0x1d8, 0x7c7448) → `dithering_Y8_to_Y1` (0x7c5684). The jump table is at Image 0x1ac9700 and dispatches on type−3. 5 and anything above 8 → "unkown dither type, no dither" (0x7c7678 / 0x7c64a8). On a full-screen area, types {3,4,6,8} (mask 0x158, 0x7c7390) run on 3 kthreads (`d_thread_0..2`, 75-jiffy timeout each; a timeout is logged and the update continues). Everything else runs single-threaded.

| Type | Internal name (kernel strings) | What it does | Out levels (Y8) | Offset |
|---|---|---|---|---|
| 0 | none | no dither | input | 0x7c7330 |
| 1 | (inline) | hard threshold: pixels already 0 or 0xFF are left alone; ≥0xA0 → 0xFF, else 0 | 2 | 0x7c7558–0x7c75d8 |
| 2 | `blue_noise_Y8_to_Y1` | ordered dither against `DitherArray` (512×512 at Image 0x20f4e70, `[y&511][x&511]`). First a ±8 contrast push (0x80..0xF7 → +8; 0x09..0x7E → −8); v<0x10 → 0, v>0xF0 → 0xF0; else out = mask≤v ? 0xFF : 0. Mask check: every value 0..255 appears exactly 1024 times, horizontal neighbour correlation −0.19, 2×2 low-pass variance 0.46× white noise → blue noise | 2 | 0x7c7784–0x7c7838 |
| 3 | `Atkinson_dithering_Y8_to_Y1` / `Atkinson_frame_dithering_Y8_to_Y1` | standard Atkinson (1/8 each to x+1, x+2; x−1, x, x+1 on the next row; x on row+2; 6/8 of the error kept). **Threshold 150** (v≥0x96 → 0xF0), so the result runs dark. Starts 3 rows above the area to warm up the error buffer, writes only from the area's first row | 2 | 0x7c5ff4–0x7c6194 (0x96 @0x7c6168) |
| 4 | `Atkinson_dithering_Y8_to_Y4` | Atkinson to 16 levels: v>254 → 0xF0, v<1 → 0, else `v & 0xF0` (floor). No warm-up rows, no frame variant | 16 | 0x7c590c–0x7c5a8c |
| 5 | – | unknown → no dither | input | 0x7c64a8 |
| 6 | `EINK_PICTURE_DITHER_Y8_Y1_CLR` / `Atkinson_(frame_)dithering_Y8_to_Y1_CLR_left` | colour variant. Threshold **128**. Kernel in sixteenths: row0 x+1:2, x+2:1, **x+3:2**; row1 x−1:2, **x:4**, x+1:2; row2 x:1 (14/16 kept). x+3 and (x+1,y+1) have the same CFA colour (period 3), so more error stays in the same colour plane [I on intent]. Refused on grey panels ("not support for Gray panel", 0x7c5a94) | 2 | 0x7c62fc–0x7c64a4 (0x80 @0x7c6480) |
| 7 | `Atkinson_dithering_Y8_to_Y2` | Atkinson to 4 levels {0, 0x50, 0xA0, 0xF0} (table at Image 0x1a1b3e8 = {0,10,20,30}<<3, index v>>6, floor) | 4 | 0x7c5ca4–0x7c5e34 |
| 8 | `Atkinson_(frame_)dithering_Y8_to_Gray3` | Atkinson to 3 levels: v>0x99 → 0xF0, 0x67..0x99 → 0x70, <0x67 → 0 | 3 | 0x7c6198–0x7c62f8 (consts 0x7c61b8–0x7c61c0) |

- **"frame" variants (3, 6, 8):** used when `g_anti_flicker_level` != 0 (= Bigme "Anti-shake", node `eink_debug/anti_flicker`; forced on for OED panels) and `g_last_fs_frame` exists (0x7c7310, 0x7c753c–0x7c7554). The previous output pixel pulls the decision toward itself (+(prev−cur)/8) and the output is also written to `g_last_fs_frame`. This is temporal hysteresis: it stops the dither pattern from crawling between frames. [V for type 6 at 0x7c5c54–0x7c5c6c. For 3 and 8 the same structure was seen but not decoded tap by tap: [I].]
- No Floyd-Steinberg, no Bayer matrix, no RGB-domain dither. All dithering runs on the Y8 CFA mosaic. `err_dither_final` (0x7c70a0) has no BL caller (unused) [V xref].

## Q2. How the effective dither is chosen [V]
`get_default_dither_type(mode_after_remap, cfg_dither)` (0x7ce074–0x7ce184), with m = mode & 0xff:
1. `g_eink_global_dither` != 0 → cfg_dither = global (0x7ce088–0x7ce0a8). Written by debugfs `global_dither`: decimal, `simple_strtoul` (0x7c4c4c). `eink_set_global_dither` (0x7eb780) has no in-kernel caller.
2. Per-mode fixup:
   - m ∈ {2, 3, 0xb5}: 0 → **3**; 6 → 6 (3 on ED58 panels); anything else passes through.
   - m ∈ {0x10, 0x11, 0xb7}: 0 → **6** on a colour panel (3 on grey or ED58); others pass through.
   - m == 0x89 → always 3. m == 0x90 → `clean_a2` ? 8 : 3.
   - **Every other mode returns the value unchanged.**
3. In `do_effect_process`: **mode 4 inherits `g_last_dither_type`** (step 4 of the pipeline). 4+flag → full screen. 0x11 → skipped.
4. `need_dither_again` (0x7e9110, called after A2-class updates): if the area was last drawn by mode 4/7 with the same dither, or the update is 0x90 over a non-3/8 dither, the kernel queues a follow-up **GU16 0x84** update with dither (6 if 0) or 8 (0x7ecd0c–0x7ecd34).

Mode remap that decides which branch applies (`remap_eink_mode` 0x7cdc20; jump tables decoded; matches every `map=` value in our logs):

| Request | Mapped | Default dither if 0 | Honours other values? |
|---|---|---|---|
| HD 177 0xb1, GU16 0x84 | **0x21 (GLX16)**, because `disable_glx16`=0 (0x7cdc98→0x7cdd70; log "0xb1(map=0x21)" ×44) | stays 0 | yes, unchanged |
| Regal 180 0xb4 | 0xb4 → not `is_regal_support` → **0x84** (0x7ce044); 0x484 for 3 s after pen/A2 input | stays 0 | yes |
| GC16 4, 0xb0 | 4 | stays 0, **but inherits the last dither** | yes |
| Fast 178 0xb2 | **2 (DU)** (0x7cdcf8) | → 3 | yes |
| Extreme 179 0xb3 | 0x10 (A2), or 0x11 if `clean_a2` (0x7cdd00) | → 6 | yes |
| Handwrite 0x405/0x406 | 5/6 | stays 0 | yes |
| Hyper 0xb7 | rejected later by `check_valid_waveform_mode` (panel-size gate) | (→ 6) | n/a |

**No path rejects, or gates on, a dither value**, unlike Hyper's `is_support_hyper_mode`. Bad values (5, >8) silently mean "no dither". A bad area only skips the dither (0x7c761c). [V for `__queue_image`, `do_effect_process` and `dither_process`]

## Q3. Fast: dither 3 vs 6; dither 4 with 177
- **3:** monochrome Atkinson at threshold 150 on the 8-bit CFA mosaic. Error crosses colour planes freely, which mixes colours, and the picture leans dark (128..149 → black). **6:** CFA-weighted Atkinson at threshold 128. More error stays inside each colour plane, and the tone balance is neutral, so colour should hold better. **Both are 1-bit.**
- Conflicting sources: the stock-stack doc (`stock-bigme-display-stack.md` "Colour restoration" row) says restoration on → 3. Our measurement (`settings-changes.log` 2026-10-21 17:09) says restoration on → **6**. The kernel name `..._Y1_CLR` for 6 fits the measurement. [I: trust the measurement]
- **Dither 4 with 177:** 0xb1 → 0x21 GLX16, and dither 4 passes through. The pixel thread then runs a **full-screen**, 3-thread Atkinson Y4 pass. The 16-level CFA data is already `&0xF0`, so the error is 0 everywhere and the output equals the input: **no visible effect, only CPU time** [V code, I outcome]. The next GC16 then inherits dither 4, which is also a no-op. Stock "HD 256" quality comes only from the HWC's `imageSmoothing` in userspace.

## Q4. Bit depth the waveform gets
| Mode (mapped) | Levels per subpixel | Why |
|---|---|---|
| GLX16 0x21 (HD 177, GU16 req), GU16 0x84 (Regal req), GC16 4 | **16 (4-bit)** | the CFA mapper floors to `&0xF0`; the index uses `(Y>>3)&0x1e` [V]. The waveform is 5-bit (DT `eink_bit_num=5`; runtime value [I]), but odd indices are never used |
| GC16 4 right after a Fast/Extreme update | **2** | inherits dither 3/6 [V code; call-site coverage I] |
| DU 2 (Fast), A2 0x10 (Extreme) | **2 (1-bit)** | forced binary dither on 8-bit data |
| 0x90 (clean A2) | 3 | Gray3 default |
| 0x11 (A2 4-bit, `clean_a2`) | dither skipped in `do_effect_process`; separate xrapid index path | [I] |
| Handwrite 5/6 | DU waveform, no dither | [V Q1 of KERNEL-PEN-PATH] |

So in 16-level modes, the kernel dither can only *reduce* levels (types 1/2/3/6/7/8) or do nothing (type 4). The 8→4-bit **floor** is the real precision loss: it bands gradients and darkens by about 7.5/255 on average.

## Q5. Recommendations for the shim
**Worth exposing (experimental knobs):**
1. **Fast/Extreme dither selector** (commit field for 0xb2/0xb3 only): `-1` = stock (3/6), or **6**, **3**, **2** (blue noise), 1 (text-only threshold). Risk is **low**: there is no gate, and the default fixup only replaces 0. Blue noise is cheap (single pass, no error buffer). Its fixed pattern does not crawl while scrolling, and it never bleeds error between colours, but it looks coarser than error diffusion at 1 bit. A/B: colour card + grey ramp + text page + scrolling list in Fast, with 6 vs 3 vs 2, each with `anti_flicker` 0 and 1.
2. **Anti-flicker together with 3/6** (`eink_debug/anti_flicker`, which already exists): it switches to the temporal-hysteresis "frame" variants. A/B: pattern crawl during scroll, and ghost trails.
3. **Shim-side dithering for 16-level modes** (not a kernel knob; keep kernel dither 0 there). Pre-quantise each channel to multiples of 16 with **rounding (+8)** plus a blue-noise or error-diffusion dither, so that the kernel's `&0xF0` floor is exact. The plain per-channel version needs no CFA knowledge. A CFA-aware version must first check the channel table and its alignment with a test pattern. A/B in Regal (→0x84) and HD (→0x21): grey and R/G/B ramps, skin tones, a photo; compare current / round only / round + dither.

**Also fix or A/B: the GC16 inheritance trap.** After any Fast or Extreme update, a GC16 commit becomes a binary dither of 3/6. That covers our "full refresh (settled)", `gc16_req` and the sleep picture. Options: send the settled refresh as **0x84** (it becomes 0x21 and does not inherit), or send a dither-0 non-GC16 update first. A/B: scroll in Fast, then trigger `gc16_req`, and look for a 1-bit pattern.

**Pointless:**
- Type 4 (no-op on this panel, costs a full-screen pass).
- Types 5 and >8.
- Types 7 and 8 in 16-level modes (they lower the level count).
- Binary types in 16-level modes.
- `global_dither` as a product knob: it overrides every update, including pen and GC16. Use it only for experiments.

**Risky:**
- Multi-level types (4/7/8) in DU or A2 (Fast/Extreme). Intermediate levels go into a 2-level waveform, with unknown visual results (ghosting) [I]. No freeze path was found.
- Never put the HWC flag bits (0x8000_0000 etc.) into the kernel mode: `remap_eink_mode` keeps the high bits, and several later checks compare the whole 32-bit mode [I].

## Not determined
- Which CFA table (`_33` vs `_33_ttl`) runs on this unit (mgr+0x9c), and how the table lines up with the panel/fb origin after `eink_expand_buffer` and mirroring.
- Exact taps of the frame variants of types 3 and 8. The 0x11 index path.
- Consumers of cfg saturation/contrast/brightness (debugfs `saturation`/`contrast`/`brightness`). `eink_color_enhance_process*` has no BL/ADRP callers [V], so these are probably inert on this build [I]. Not traced further.
- `dither_mp_flag` / `set_eink_dither_mp`: no in-kernel caller found. The multi-thread choice in `dither_process` depends only on type and full-screen area [V].
- Whether every GC16 actually reaches `do_effect_process` (call-site conditions in `eink_pixel_process_thread` not fully traced).
