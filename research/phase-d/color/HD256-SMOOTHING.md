# HD 256 "image smoothing": what the stock vendor HWC actually does

Date: 2026-10-26. Offline RE only. No device was touched.

Sources:
- `work/vendor_files/lib64/libimage_process.so` (arm64, 84 KB)
- `work/vendor_files/lib64/hw/hwcomposer.mt6877.so` (BuildID bdef0c8d…)

Tags: **[V]** = read from the disassembly (function + offset given). **[E]** = verified by running the vendor code in an emulator (see "Verification"). **[I]** = inferred.

## TL;DR

`xrz::EffectHandler::imageSmoothing` does **not** smooth or blur anything. It is a thin wrapper around `xrz::EffectHandler::colorDither`.

That function is a **Floyd–Steinberg error diffusion that quantises each of the three colour bytes to 16 levels (0, 17, 34 … 255)**. Its details:
- It runs on the full frame in 8 independent vertical strips.
- It has a **temporal-stability rule**: a pixel whose source did not change since the previous smoothed frame keeps its previous output.

The kernel then receives exact 16-level values with dither 0, so no second dither is applied.

"HD 256" means 16 physical levels that look like about 256 through error diffusion. It is not anti-aliasing.

Note: the guess in `color/WAVE3-NIGHT-2026-10-22.md` §3 ("a light blur/anti-alias") is wrong.

A clean-room C reference matches the vendor code **bit for bit** on a full 2480×1860 frame, run through the vendor code under emulation (see "Verification").

---

## 1. The algorithm

### 1.1 Call chain and signatures

- **[V]** `EffectHandler::imageSmoothing(void* src, void* dst, void* prevSrc, void* prevDst, android::Rect r, int strideBytes, int unused)` @0xa18c, 0x18 bytes long:
  - It tail-calls `EffectHandler::colorDither` with the same registers (`bl colorDither@plt` @0xa194).
  - It then returns 0 (@0xa198).
  - These are static functions with no `this`: x0..x3 are the four buffers, x4/x5 are the Rect (left|top<<32, right|bottom<<32) and w6 is the stride.
- **[V]** `EffectHandler::colorDither` @0x90e0:
  - It builds `xrz::ThreadPool(10)` @0x9150, so **10 threads are created per call**.
  - It queues **8 tasks**. Each task is `std::bind(EffectTask::colorDither, src, dst, prevSrc, prevDst, Rect{x_k, top, x_{k+1}, bottom}, stride)`. Task layout: +0x10..+0x28 = the 4 pointers, +0x30 = l,t, +0x38 = r,b, +0x40 = stride.
  - The 7th argument (w7) is **never read**. The HWC passes 1.
  - Strip edges [V @0x9154-0x9580]: `w = right-left`; `x_k = left + (k*w)/8` (C signed division) for k = 1..7; `x_8 = right`. For W = 2480 that gives 8 strips of 310 px.
  - It waits by polling `ThreadPool::size()==0` with `usleep(1000)` (@0x95c4).
  - It then calls `stop()`, which sets running=0, broadcasts and `pthread_join`s every thread [V @0xcfb4], so all running tasks finish. Finally it destroys the pool.
- **[V]** The per-strip worker `EffectTask::colorDither(void*,void*,void*,void*,Rect,int)` @0xa9d4. It is the only exported function with the bound signature `int(*)(void*,void*,void*,void*,Rect,int)`.
  - It first calls `sched_setscheduler(0, SCHED_FIFO, prio 99)` (@0xaa10-0xaa38). On failure it logs "Couldn't set SCHED_FIFO: %d", tag "EffectTask".
  - It returns 0.

### 1.2 Quantiser LUT [V @0xaa74-0xaae4, E]

A 256-entry int table on the stack, built with NEON float math. For each i: `round_half_away(i/255.0f*15)/15*255`, truncated to int.

Computed exactly in float32, this equals:

```
Q[i] = ((i*15 + 127) / 255) * 17          // integer division; 16 output levels 0,17,…,255
     = ((i*240 + 2160) >> 12) * 17        // equivalent, branch/LUT-free, exact for i in 0..255
```

The level boundaries (first input of each new level) are 9, 26, 43, 60, 77, 94, 111, 128, 145, 162, 179, 196, 213, 230, 247.

NEON appears only here. **The pixel loop is plain scalar code.** No NEON path computes anything different.

### 1.3 Per-strip loop [V @0xaae8-0xae98, E]

**Buffers and layout**
- All four buffers are 32-bit pixels with the same `strideBytes`. Pixel (x, y) is at `base + y*stride + x*4`.
- The three low bytes (byte 0, 1, 2 = R, G, B for RGBA/RGBX in memory) are dithered **independently, per channel**. No luma is used.
- Byte 3 (A/X) is copied unchanged.
- `src` is only read at (x, y) before `dst` is written at (x, y), so running in place would work [V, by structure].

**Error state**
- For each channel there is `calloc(2*w, 4)`: two rows of `int32`, ping-ponged by row parity. Row `cur = (y % 2)` and row `nxt = ((y+1) % 2)`, using absolute y.
- At the start of every row the `nxt` row is zeroed (memset w*4).
- Errors are stored ×16 (FS numerators). The diffused amount applied to a pixel is `err >> 4` (arithmetic shift, so it floors).

**Per pixel, in row-major order inside the strip**

```
x0,x1 = strip columns, y0,y1 = rect rows (always 0..H)
for y in y0..y1-1:
  for each channel c: nxt_c[0..w-1] = 0
  for x in x0..x1-1:  i = x - x0
    px = src[y][x]
    for c in 0..2:
      v   = clamp(byte_c(px) + (cur_c[i] >> 4), 0, 255)
      q_c = Q[v]
      e_c = v - q_c                       // range -8..+8 (error of the CLAMPED value)
    out = (px & 0xFF000000) | q_2<<16 | q_1<<8 | q_0
    if prevDst != NULL and prevSrc[y][x] == px:   // whole 32-bit word compare
        out = prevDst[y][x]                         // temporal reuse; errors still use e_c
    dst[y][x] = out
    for c:
      if x < x1-1:            cur_c[i+1] += 7*e_c
      if y < y1-1:
        if x >= 1:            nxt_c[i-1] += 3*e_c   // NOTE: tests absolute x, not i (quirk below)
                              nxt_c[i]   += 5*e_c
        if x < x1-1:          nxt_c[i+1] += 1*e_c
```

The core is classic Floyd–Steinberg: weights 7/16 right, 3/16 down-left, 5/16 down, 1/16 down-right.

Edge and border handling:
- There is no serpentine scan. Error does not wrap at row ends.
- **Error never crosses strip boundaries.** Each strip starts with zero error at its top row and its left column.
- The last row of the rect diffuses nothing downwards.

**Quirk (affects bit-exactness) [V @0xadec, E]**

The down-left test is `x >= 1` on the absolute x, not `i >= 1`. For strips k = 1..7 (x0 > 0), at i = 0 the vendor writes `nxt[-1]`:
- **(y+1) odd** (y even): `nxt` is the second half of the calloc block, so `nxt[-1]` is `cur[w-1]`, the last pixel of the *current* row of the same strip. It is read later in the same row. So in even rows, the rightmost pixel of strips 1..7 also gets `3*e(left pixel)`.
- **(y+1) even** (y odd): the write lands 4 bytes before the heap block, in the allocator's header padding [I: harmless on scudo, which keeps 16 bytes in front of a block]. It has no visible effect.

Without this quirk, a full frame differs in about 0.8% of bytes (148k of 18.4M), all near strip edges and visually irrelevant. To match bit for bit, reproduce it as in §6.

### 1.4 What it is, in one line

Strip-parallel, temporally-stabilised 4-bit-per-channel Floyd–Steinberg. It has no spatial filter, no thresholds and no edge preservation, apart from the "unchanged source pixel keeps old output" rule.

---

## 2. How the HWC calls it

### 2.1 Gate [V `CommitPolicy::setCommitInfoForJob` @0xf02ac-0xf0388]

- `w23 = vendor.xrz.hwc.image_smoothing_suppoort` (string @0x5377d).
- If `w23 == 1 && CommitInfo.dither(+0x3c) == 4 && CommitInfo.mode(+0x08) == 0xB1 (177) && CommitInfo+0x8d == 0`, then `+0x8a = 1` and `dither = 0`.
- **+0x8d is the "scrolling" flag** [V `checkLayersAndSetCommitInfo` @0xefec8]: in modes 177/180 it is `vendor.xrz.is_scrolling > 0`. When scrolling, the mode is also forced to 178. So **no smoothing while scrolling**.
- After that, if `vendor.xrz.antutu_test >= 1` → mode 181 and `+0x8a = 0` (@0xf0394-0xf039c).
- So HD 256 reaches the kernel as **mode 177, dither 0** [V].
- [I] `vendor.xrz.gray_256_dither` (written in `triggerEinkCommit` @0xf63d4 from dither == 4) therefore reads "0" in HD 256 when smoothing is supported.

### 2.2 Call sites [V `OverlayEngine::processImage(OverlayPortParam*, CommitInfo*)` @0xa4058]

There are two call sites: **@0xa4788** (colour panel, `format` +0x54 != 0, our device) and **@0xa50c8** (mono panel, format 0).

**Source.** The port's buffer:
- fd from `param+0x78` is `ionImport`ed and `ionMMap`ped at `stride*4*height` (from the GraphicBuffer at `param+0x178`: +0x4c height, +0x50 stride).
- The HWC waits on its fence `param+0x12c` (log "wait CT fence") with `sync_wait(…, 1000)`.
- So the source is the **SurfaceFlinger client target (CT)**, read-only [V strings @0xa43b4/0xa4440].

**Same-frame skip.**
- If `CommitInfo+0x8b` is set and `this+0x530 == 0`, the HWC `memcmp`s the whole CT against the previous source copy (`this+0x618`).
- If they are identical it logs and does not commit [V @0xa4470-0xa45b4].

**Buffer choice.** Under `this+0x428` it picks a free CommitBuffer among `this+0x5f0/0x5f8/0x600` (triple buffer).

**If `+0x8a`** (@0xa45fc):
- `prevSrc`/`prevDst` are NULL unless `this+0x620` (the "last smoothed output") is set. In that case:
  - `prevSrc` = locked `this+0x618` (the copy of the previous raw CT).
  - `prevDst` = locked `this+0x620` (the previous smoothed CommitBuffer).
  - [V @0xa4604-0xa46c0]
- **Colour path @0xa4710-0xa4788:**
  - `dst` = the chosen CommitBuffer, locked with usage 0x33.
  - Call: `imageSmoothing(src = CT mapping, dst, prevSrc, prevDst, Rect{0, 0, dst.width, dst.height}, strideBytes = CT.stride*4, 1)`.
  - So it runs on the **full frame, not the dirty rect**. All four buffers share the CT stride.
  - Then:
    - `this+0x620 = dst` (@0xa47e4).
    - The raw CT is `memcpy`d into `this+0x608` (usage 0x30), and `this+0x618 = this+0x608` (@0xa48a0-0xa4934). This is the next frame's `prevSrc`.
    - Log "processImage: smoothing spend: %ld ms" (@0xa4870).
- **Mono path @0xa5050-0xa50c8:**
  - The same call writes into an intermediate RGBA buffer `this+0x610`.
  - `convertToGray(smoothed → CommitBuffer, Rect, stride, 1)` then runs on it (@0xa4c70).
  - So on mono panels the order is smoothing first, then grey.

**If not `+0x8a`** (@0xa4a00):
- `this+0x620 = NULL`. The next smoothed frame therefore starts fresh, with no reuse.
- On colour: CT → memcpy into the CommitBuffer, and `this+0x618` = that buffer.

**Colour processing order.**
- On stock, saturation, contrast, brightness and the 3D LUT are done by SurfaceFlinger's client-composition shader (see `stock-bigme-display-stack.md`).
- So the CT is already colour-processed when the HWC dithers it.
- The pipeline is: SF colour shader → CT → **FS16** → CommitBuffer → kernel (mode 177, dither 0, partial rect as usual) [V for the HWC part].

**Rotation.** [I] The buffer is dithered in its own row order, which is the orientation committed to the panel (native 2480-wide rows). The 8 strips are therefore 310-px bands of native x.

---

## 3. Props and constants

| Item | Value | Tag |
|---|---|---|
| `vendor.xrz.hwc.image_smoothing_suppoort` | must be 1 (gate only) | V @0xf02ac |
| request | mode 177 + dither 4 (0x800000B1) | V |
| `vendor.xrz.is_scrolling` > 0 | suppresses (via +0x8d) | V |
| `vendor.xrz.antutu_test` ≥ 1 | suppresses (mode 181) | V |
| `ro.vendor.xrz.screen_type` | selects the colour (direct) or mono (then grey) call site | V |
| levels | 16 per channel (×17 values) | V/E |
| kernel | FS 7/3/5/1, /16 via `>>4` | V/E |
| strips | 8, edges `(k*W)/8` | V/E |
| threads | pool of 10 created and joined per call; tasks at SCHED_FIFO 99 | V |
| 7th arg | ignored (HWC passes 1) | V |

**No prop or argument tunes strength, levels or thresholds.**

---

## 4. Cost

**Scalar inner loop.** About 75 instructions per pixel for the 3 channels, with 4 loads, 1 store and 9 read-modify-writes of error per pixel [V, by count].

**Measured reference.** The clean-room C reference (`-O2`, single thread) on this Mac takes **31 ms per full 2480×1860 frame**.

**[I] Device estimate.** MT6877 is 2×A78 @ 2.4 GHz + 6×A55 @ 2.0 GHz, so 8 cores, not 4.
- Whole-frame single-thread time: A78 roughly 60–90 ms, A55 roughly 250–350 ms.
- Stock's 8 strips run in parallel, so wall time is bounded by a strip on an A55: **about 30–45 ms**.
- Stock adds:
  - an 18 MB `memcpy` (prevSrc), about 3–5 ms;
  - the 18 MB same-frame `memcmp`;
  - reading prevSrc and prevDst inside the loop (about 74 MB of memory traffic per frame in total);
  - creating and joining 10 threads, about 1 ms.
- **Stock total: roughly 35–55 ms per HD 256 frame.**
- A shim with 4 worker threads over the same 8 strips would take about 30–60 ms, depending on which cores it lands on.

**Can it be restricted to changed rows?** Yes, given the temporal rule.

- *Exact (stock-identical output):*
  - Every pixel with `src == prevSrc` outputs `prevDst`. So rows below the last changed row, and whole strips with no changed pixel, give unchanged output and can be skipped.
  - Rows above the first changed row must still run the error recursion, without writing, to get the exact error state.
  - **Cost: rows 0 .. bbox.bottom of the strips that touch the bbox columns.**
  - The bbox must come from an exact `src != prevSrc` diff (or a trusted dirty rect). Error diffusion makes "changed area" mean changed source pixels, not changed output pixels.
- *Approximate (recommended for speed):*
  - Start each touched strip at `bbox.top` with zero error.
  - Only pixels whose source changed get new values, as on stock. Their grain pattern differs slightly from stock, which is invisible.
  - Cost is proportional to the dirty area: a 200-row band of one strip is about 1–2 ms.

**Optional NEON.** One pixel per step with the 3 channels in int16 lanes (|err×16| ≤ 128 fits) and `Q` computed as `((v*240+2160)>>12)*17`. This needs no table lookup. It is still serial along a row, so the expected gain is about 2×.

---

## 5. Expected visual effect, and why dither 0

**[I] Visual effect.**
- The panel's 16-level waveform (mode 177) can only show 16 values per grey pixel. With dither 0 and no smoothing, gradients and photos posterise into 16 bands per channel.
- Stock's kernel dither 4 is the non-smoothing fallback for HD 256, kernel algorithm [R]. Our earlier observation of plain 177/dither 0 was "sharp and clean" for UI and text, with banding only in photos.
- FS16 turns the banding into fine, non-periodic grain that averages to the right tone over a few pixels. Hence "256": smoother photos and gradients, and no ordered-dither cross-hatch.
- Hard edges stay sharp: there is no spatial filter. Antialiased glyph edges pick up a little grain.
- Under the Kaleido colour filter, each grey pixel shows one channel. Per-channel diffusion done at full resolution and then sampled by the CFA does not preserve mean tone exactly per colour, so expect some faint colour grain or chroma noise in flat mid-tones. That is the price of the extra tones.
- The strip structure can leave very faint seams at native x = 310·k. In practice these are below visibility.

**[V + I] Why stock pairs it with dither 0.**
- After FS16 every channel value is exactly 17·k, so any kernel quantiser (`>>4` or rounding) maps it losslessly to level k.
- Kernel dithering on top would add a second, uncorrelated noise to an already-dithered image.
- The temporal rule exists because FS is chaotic: one changed pixel reshuffles the grain everywhere below and to the right in its strip. Reusing `prevDst` keeps the panel content stable ("no crawling grain") and keeps the output consistent with partial-rect commits.

**Current GSI state.**
- Since Phase C the shim commits the CT or its own processed buffer, so this stage never reaches the panel.
- On the GSI, HD 256 is therefore plain 177 with dither 0, as WAVE3 noted.

---

## 6. Implementation spec for the shim

### Knob

`vendor.debug.hwc.shim.smooth`
- **0** = off (default; today's behaviour).
- **1** = stock-equivalent: apply only when the vendor marked the frame. This means CommitInfo+0x8a == 1 in `hook_processImage`, which already implies 177 + dither 4 + suppoort == 1 + not scrolling + not antutu. Commit dither 0.
- **2** = also on any mode-177 or 180 (Regal) commit, even without the dither-4 request. This is an experiment.
- Never in 178/179 (fast modes, kernel dither 3/6), pen/handwrite modes, 4/GC16 clean frames, or A2/DU.

Optional: `vendor.debug.hwc.shim.smooth_exact`
- **1** = stock-bit-exact (full recursion from row 0, quirk on).
- **0** = fast (start at bbox.top, quirk off).

### Placement

- Run on the buffer the shim actually commits: after the shim's own colour pipeline, matching stock's "after SF colour shader".
- Write into the shim's own commit buffer, never into the CT. SF may rely on CT contents (buffer age / partial redraw).
- Commit with dither 0.
- Keep the same partial-rect logic. Pixels outside the changed set are unchanged by construction.

### State

- `prevSrc`: copy of the last smoothed frame's input.
- `prevOut`: last smoothed output.
- `valid` flag. Invalidate (reuse off for one frame) when:
  - the previous commit was not smoothed (stock does this);
  - size, stride or format changes;
  - after a GC16 clean.

### Pseudocode (own implementation; bit-exact in exact mode)

```cpp
// Q: 16 levels per channel
static inline uint8_t q16(int v) { return uint8_t(((v * 240 + 2160) >> 12) * 17); }   // v in 0..255

// One strip. Error rows hold FS numerators (x16). One guard slot before each row.
void fs16_strip(const uint32_t* src, uint32_t* dst, const uint32_t* prevSrc, const uint32_t* prevOut,
                int strideW /*in pixels*/, int x0, int x1, int yStart, int yEnd, int H, bool quirk) {
  const int w = x1 - x0;
  // err[c][parity] : int32_t[w+1], index -1 valid (guard)
  for (int y = yStart; y < yEnd; ++y) {
    const int cp = y & 1, np = cp ^ 1;
    for (c : 0..2) zero(err[c][np][0..w-1]);
    for (int x = x0; x < x1; ++x) {
      const int i = x - x0;
      const uint32_t px = src[y*strideW + x];
      uint32_t out = px & 0xFF000000u; int e[3];
      for (int c = 0; c < 3; ++c) {
        int v = int((px >> (8*c)) & 0xFF) + (err[c][cp][i] >> 4);   // arithmetic shift
        v = v < 0 ? 0 : (v > 255 ? 255 : v);
        const int q = q16(v);
        e[c] = v - q;
        out |= uint32_t(q) << (8*c);
      }
      if (prevOut && prevSrc[y*strideW + x] == px) out = prevOut[y*strideW + x];
      if (y >= writeTop && y < writeBottom) dst[y*strideW + x] = out;   // exact mode: write only in [bbox.top, bbox.bottom)
      for (int c = 0; c < 3; ++c) {
        if (x < x1 - 1) err[c][cp][i+1] += 7*e[c];
        if (y < H - 1) {                                  // NOTE: H = full rect bottom, not yEnd
          if (x >= 1) {
            if (i > 0)                 err[c][np][i-1] += 3*e[c];
            else if (quirk && np == 1) err[c][0][w-1]  += 3*e[c];   // stock aliasing of nxt[-1]
          }
          err[c][np][i] += 5*e[c];
          if (x < x1 - 1) err[c][np][i+1] += e[c];
        }
      }
    }
  }
}

void hd256_smooth(frame, W, H) {
  // 8 strips exactly like stock: edges (k*W)/8, k=0..8 (last = W). Strip edges are part of the output, so keep 8 for exactness.
  // Exact mode: for each strip touching bbox columns run rows 0 .. bbox.bottom, write only bbox rows (outside = prevOut anyway).
  // Fast mode:  for each strip touching bbox columns run rows bbox.top .. bbox.bottom, zero initial error, quirk=false.
  // If !valid (first frame): full frame, prevSrc/prevOut = null.
  // Then: copy src rows [bbox] into prevSrc, dst rows [bbox] into prevOut (or swap buffers on full frame); valid = true.
  // Run strips on a persistent pool (4 threads), normal priority. Do NOT copy stock's SCHED_FIFO 99 or per-frame thread creation.
}
```

Notes for exact equivalence:
- Row parity uses absolute y (stock rect top is always 0).
- The reuse compare covers the full 32-bit word.
- The A/X byte comes from `src` (or from `prevOut` on reuse).
- In fast mode, `writeTop..writeBottom` = run range, and the down-diffusion bound should be `y < yEnd - 1` or `H - 1`. Either is invisible.

---

## Verification [E]

Scratch files (not part of the repo), in `/private/tmp/claude-501/-Users-owner-Desktop-Bigme/732c8451-807d-4b32-a537-d33e64c4f2bd/scratchpad/smooth/`:
- `emu.py`: unicorn-engine harness. It loads `libimage_process.so` and runs the vendor's `EffectTask::colorDither` @0xa9d4 for each of the 8 strips, with PLT stubs (calloc with 16-byte front padding, memset, sched_setscheduler…) emulated.
- `ref.c`: the clean-room reference.

Results:
- Bit-identical output on random and gradient images at 203×37 (stride 208), 64×9, 2480×4, 1860×4 (stride 1872) and a **full 2480×1860 frame**.
- Bit-identical with temporal reuse on (prevSrc with 30% of pixels changed, random prevOut).
- The output contains exactly the 16 levels 0…255 step 17. Alpha is preserved.
- With the quirk disabled the full frame differs in 148,545 bytes, which confirms the quirk is real and matters for bit-exactness.
- Not verified by emulation: `EffectHandler::colorDither`'s strip split and thread pool. The split was re-implemented from @0x9154-0x9580 [V].

## Open points

- [R] Kernel semantics of dither 4 (the non-smoothing fallback) and of the CFA mapping. They are not needed to reproduce stock.
- [I] Whether `vendor.xrz.gray_256_dither` really reads 0 in stock HD 256 (ordering inferred).
- [I] The visual claims in §5 need an on-panel A/B: shim smooth=0 vs smooth=1 on a gradient and photo page in HD 256.
