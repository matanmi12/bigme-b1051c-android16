# Stock Bigme pen fast path, userspace side (handwrittenservice v1.4.0 and its clients)

Offline analysis of the stock A14 images (`work/super/*_b.img`) plus read-only device checks, 2026-10-20.
Tags: **[V]** verified (function and offset, string, or decompiled class quoted); **[I]** inferred.
The kernel side is in `KERNEL-PEN-PATH.md`. Its facts are used here, not repeated.

Artefacts (proprietary, local only): `pen/userspace/`
- `stock-system-20261020/`: `handwrittenservice` (sha256 6cd12f73…), the libs, `framework.jar`,
  `xrz.framework.server.jar`, `GlobalWritingService.apk`.
- `minidebug-20261020/`: `.gnu_debugdata` symbol tables. All binaries ship MiniDebugInfo, so every function has its real name.
- `disasm-20261020/`: annotated disassembly.
- `jadx-*`: decompiled `framework.jar` classes4/5, `xrz.framework.server.jar`, GlobalWritingService, Launcher classes4/5
  and xNote classes4 (the `com.xrz.handwrittenPlus` pen engine).
- `hwc-20261020/`: disassembly of the vendor HWC (sha256 93cd2430…).
- `stock-sepolicy-20261020/`.
- Scripts: `userspace/scripts/dis.py` (annotated disassembler: PLT names and strings) and `peek.py`.

## 0. The model in one paragraph
handwrittenservice does **not** render strokes. It is a broker with five jobs:
1. It reads the pen evdev itself, adds Kalman prediction if enabled, and pushes 32-byte samples to the client app over a
   socketpair.
2. The **app** draws the stroke into an ION buffer that both processes map (`HandwrittenClient.getCanvas()`), then calls
   `inValidate(rect, mode)`. This only records the rect locally and sends a binder `requestCommit(id)`.
3. The daemon's Committer thread **pulls** the accumulated rect and mode back from the app (synchronous binder callback
   `IClient.onCommit() → CommitInfo`).
4. The Committer passes the **app's whole-view RGBA buffer** to `DRM_IOCTL_EINK_UPDATE`, with the view's screen offset
   and the dirty rect.
5. It keeps SurfaceFlinger and the HWC from overwriting the ink: HWC layer flags, `vendor.xrz.disable_commit=1`, and SF
   throttled to 8 fps. After a timeout of pen inactivity it hands the screen back to the normal path ("recommit": a full
   repaint in a chosen mode). [V]

## 1. The commit loop

### 1.1 Threads [V]
| Thread | What it does | Evidence |
|---|---|---|
| main → binder pool | `main` parses `-p <pen> -t <touch>` (getopt "p:t:"), then `setpriority(-20)`, `set_sched_policy(FG)` and `sched_setscheduler(SCHED_FIFO, max)`. It publishes "handwrittenservice" and joins the binder pool, which serves the AIDL calls. | `main` @0xc060 |
| `InputMonitor_loop` | `epoll_wait(…, 4, 200 ms)` on the pen and touch fds (O_NONBLOCK, **no EVIOCGRAB**, so Android gets every event too). Reads 24-byte `input_event` records. It calls `onInputEvent` on its own thread. | `InputMonitor::threadLoop` @0x134b0 |
| `Committer_loop` | Runs SCHED_FIFO **99**. Waits on a cond var, pulls `CommitInfo` from the client and makes the ioctl. | `Committer::threadLoop` @0x16900 (`pthread_setschedparam(…,1,{0x63})`) |
| `Blender_loop` | Only when blend is enabled (see §2). It takes an SF screenshot, composites the client layer over it and asks the Committer for a frame commit. | `Blender::threadLoop` @0x147d0 |
| `VSyncThread` | Calls `ioctl(0xC01064D2)` (wait vsync, 16-byte arg, u64 timestamp at +8) after every HANDWRITE/RUBBER commit and signals `Committer::onVSync`. **Nobody waits on that signal in v1.4.0** (the cond at Committer+0x3b0 is used only in the ctor and in `onVSync`), so commits are **not** vsync-gated. | `VSyncThread::threadLoop` @0x189f0 |
| `native_message_handler` | A Looper running delayed jobs: what=0 recommit, 1 disable_commit=1, 2 sysBlend, 3 auto-clean restore, 4 `prepareCommit`, 5 restore the fps limit. | `MyMessageHandler::handleMessage` @0x11230, jump table @0x7ffd |

### 1.2 Pen event → client sample [V]
- **Device choice.** If `sys.handwritten.pen.name` or `sys.handwritten.tp.name` is set, the daemon scans `/dev/input`
  with EVIOCGNAME. Otherwise it uses the `-p` and `-t` paths. Stock rc passes `-p /dev/input/event0`.
  - On our GSI, event0 is ACCDET. The pen is **event4 "Wacom I2C Digitizer"**: ABS_X 0–2480, ABS_Y 0–1860, ABS_PRESSURE
    0–4095, keys BTN_TOOL_PEN/BTN_TOOL_RUBBER/BTN_TOUCH/BTN_STYLUS(2) [V getevent -lp].
  - The HWC track measured the pen at about 3 ms per sample (`hwc/keep-commit-timeline.txt`).
- **Parsing** (`handlePenEvent` @0x136b0):
  - ABS_X / ABS_MT_POSITION_X → x; ABS_Y / ABS_MT_POSITION_Y → y.
  - ABS_PRESSURE / ABS_MT_PRESSURE is normalised to 0…4096 using the EVIOCGABS range (`init_pressure_range`, default
    0–4096).
  - BTN_TOOL_PEN / BTN_TOOL_RUBBER give the tool and the proximity state; BTN_TOUCH gives contact.
  - SYN_DROPPED resets the state.
- **Actions** at SYN_REPORT (`processPenSync` @0x13aa0): **0 NEAR** (in proximity), **1 DOWN**, **2 MOVE**, **3 UP**,
  **4 LEAVE**. These match the `HandwrittenClient.ACTION_*` constants. The timestamp is the evdev time in ns.
- **`onInputEvent`** (@0xd640) runs for each registered layer with input enabled:
  1. NEAR → message what=4 → `Committer::prepareCommit()`. This is a **1×1 px HANDWRITE (0x405) commit at (0,0)** of the
     daemon's full-screen frame buffer: a warm-up before the stroke starts. [V] Its purpose is inferred to be powering
     up the EPD pipeline. [I]
  2. If the point is inside the layer and the layer has blend enabled, NEAR also triggers `Blender::triggerSysBlend()`
     (an SF screenshot, §2).
  3. Auto-clean control (layer flag +0x38):
     - DOWN/NEAR: `vendor.xrz.auto_clean_check_disable=1` and `eink_debug/clean_a2 ← "0"`.
     - UP/LEAVE: restore after **1 s** (what=3: `check_disable=0`, then `clean_a2 ← "1"` if
       `vendor.xrz.auto_clean_enabled==1`).
  4. Recommit (layer flag +0x25): on the first event, `vendor.xrz.disable_commit=1`. Every event re-arms what=0 after
     `delay` ms (the client's value, or **2000 ms** if ≤0).
  5. **Prediction.** Only if the layer enabled it **and** `vendor.xrz.input_predict_disabled==0` (read at
     `clientConnected`). MOVE and UP coordinates are replaced by `xrz_predict(x, y, &px, &py)`; any other action calls
     `xrz_predict_reset()`.
  6. `com::xrz::InputChannel::sendMessage`: a **32-byte** message over an AF_UNIX **SOCK_SEQPACKET** socketpair (32 KB
     buffers, MSG_DONTWAIT). Layout: `{u32 seq, action, x, y, pressure, tool, i64 ts_ns}`. [V `libXrzInput_jni`
     `InputChannel::sendMessage` @0x5480: `sendto(…,0x20,…)`]
- **Client receive** (`HandwrittenClient.HandwrittenInputEventReceiver`, a Looper): NEAR (0) and LEAVE (4) are
  **dropped**. Coordinates are converted from panel space to view space (`convertXY`, handles 0/90/180/270), then
  `InputListener.onInputTouch(action, x, y, p, tool, ts)` is called.

### 1.3 Prediction (libxrz_predict.so "1.2.6_20250805") [V]
- API:
  - `int xrz_predict_init()` (called once in the service ctor),
  - `void xrz_predict(uint x, uint y, uint *px, uint *py)`,
  - `void xrz_predict_reset()`.
- Internals: a Kalman filter with a 6-state constant-acceleration model (`KF_param`, `AKV_predict/AKV_correct`,
  `predict_with_anti_overshoot`, which clamps the predicted displacement by speed). The variant is chosen on every call
  from `ro.vendor.xrz.sytlus_type`: 0 or unknown → wacom, 1 → "fpp cap", 2 → "usi cap".
- It depends only on libc, libc++, libm, liblog and libcutils.
- The look-ahead horizon (velocity-dependent constants) was not decoded. [I]
- **xNote turns prediction off** (`HandwritingManager`: `b0.setPredictEnabled(false)`). [V]

### 1.4 Client draw → commit request [V]
- **The pen engine** is `com.xrz.handwrittenPlus` (bundled in xNote, Launcher and GlobalWritingService).
  - Points are queued; a draw thread runs `onDraw(points)`, which extends the `Path` and draws it **twice**:
    - on the app's own canvas: anti-aliased, real colour;
    - on the *mirror* canvas, which is the ION canvas from `getCanvas()`. Here `paint.setAntiAlias(false)` is used and
      the colour is forced to **black** (`onUpdateHandwritePaint` → `-16777216`) or to a `ColorMapper` shader.
  - Stroke width follows pressure: `SteelPen` range 1–10, default 4.
- **Dirty rect** (`BasePen` lines 140-170): the union of the segment bboxes, each **±strokeWidth/2**, with no other
  padding.
- **`FlushInfo` channel 1 = fast path.**
  - Allowed modes: **1029, 1030, 1201 (0x400|0xB1 HD), 1202 (0x400|0xB2 NORMAL), 1028 (0x400|4 GC16)**
    (`FlushInfo.checkValid`).
  - Channel 2 = the normal SF path (mode 178).
  - Pens return 1029 (`DefaultPen.getMode`); the rubber returns 1030.
- **`HandwrittenClient.inValidate(rect, mode)`**:
  1. Maps the rect into panel orientation and offsets it by the view's on-panel layout (∩ the view).
  2. If a commit is pending with a *different* mode, sleeps 30 ms at a time, up to 3 s (`needWait`).
  3. Unions the rect into `mDirtyRect` and stores the mode.
  4. **Only if `mDirtyRect` was empty**, sends a synchronous binder `requestCommit(id)` (AIDL 4).
- **AIDL `inValidate` (code 3) is vestigial.** It feeds `Committer::commitDirtyRect`, which ORs into a Region at
  Committer+0x118 that nothing ever reads (only the ctor @0x1602c, `commitDirtyRect` @0x16438 and the dtor @0x17420 touch
  it). The Java client never calls it.

### 1.5 Committer → ioctl [V]
1. `requestCommit` → `Committer::requestCommit(layer)` sets `layer.commitPending`, then signals. If the layer has blend
   enabled it calls `Blender::requestBlend` instead.
2. `Committer::threadLoop` runs **for each pending layer**:
   1. `listener->onCommit(layer, &ci)` → `BpClient::onCommit` (a **synchronous** transaction that reads a `CommitInfo`
      parcelable `{l, t, r, b, mode, effectMode}` back). The client returns the union rect and the mode, **and clears
      them**.
   2. If an *overlay* layer (flag +0x24) intersects this layer, or blend is on, it logs "Skip commit for overlay layer".
   3. Otherwise it calls `Committer::commit(layerHandle, layout = layer.getLayout(), dirty = ci.rect, ci.mode, ci.effect)`
      → `mtk_commit` → `ioctl(card0, 0xC04464CF)`.
   4. On a **gray** panel with an RGBA layer, it first `EffectHandler::convertToGray`s the rect into the gray frame buffer
      and commits that instead. Our panel is colour, so the client's RGBA buffer is committed directly.
3. **Batching is a pull model.** Everything the app draws while the previous ioctl (and the binder round trip) is in
   flight accumulates in `mDirtyRect`. The next pull takes the union. There is no timer and no vsync gate; the rate is
   limited only by ioctl latency plus 2 binder hops.
4. **Throttling the rest of the system** (`HandwrittenService::onCommit` @0xdf70):
   - On the first commit of a burst: `vendor.xrz.fps_limit_for_handwriting = "8"` and
     `SurfaceComposerClient::forceResync()` (Bigme's SF caps itself at 8 fps).
   - Restored to "0" **200 ms** after the last commit (what=5).
5. **Debug props:**
   - `sys.handwritten.disable=1`: skip every ioctl.
   - `sys.handwritten.capture=1`: dump the committed buffer to `mnt/pic/commit.png`.

### 1.6 Mode per phase
| Phase | Mode / action | Who | Tag |
|---|---|---|---|
| Pen enters proximity (NEAR) | 1×1 commit, mode 0x405, at (0,0), frame-buffer fd; SF screenshot if blend | daemon `prepareCommit` / `triggerSysBlend` | V |
| Down / move (pen) | **1029** (0x405) with the client's union rect | client `DefaultPen` | V |
| Eraser | **1030** (0x406); region and lasso erasers use 1029 or channel 2 | client | V |
| Pen leaves (xNote) | `inValidate(penArea, 1201)` = 0x400\|HD over the accumulated pen area | client `HandwritingManager` line 1631 | V (code). Reachable only via the MotionEvent path, because HandwrittenClient drops LEAVE [I] |
| Idle after `delay` ms | `disable_commit=0`, `force_global_refresh_mode=<mode>`, `SurfaceComposerClient::repaintEverything()` → full-screen HWC repaint in that mode (0 = "current mode") | daemon what=0 | V |
| Auto-clean | `clean_a2=0` while writing; `=1` 1 s after up (if `auto_clean_enabled`) | daemon | V |
| "clearOverlay" (AIDL 16) | **no-op**: `Committer::clearOverlay` is a 4-byte `ret` (ICF-folded with `VSyncThread::onFirstRef` @0x11b40) | daemon | V |

Recommit settings actually used:
- Launcher global handwriting over third-party apps: `enableRecommitMode(1000, 178)`; other global modes `(200, 178)`.
- GlobalWritingService: `(400, 0)`.

[V `GlobalNoteView` line 524-528; `GlobalWritingService.onCreate`]

### 1.7 The ioctl struct as handwrittenservice fills it (`mtk_commit` @0x18320)
`mtk_commit(int mode, int effectMode /*unused*/, int fd, Rect dirty, Rect layout, Rect bufSize, int format, bool remap)` [V]

| Offset | Value written | Tag |
|---|---|---|
| +0x00 | `mode` (raw client mode, e.g. 0x405) | V |
| +0x04/+0x08 | `dirty.left`, `dirty.top` | V |
| +0x0c/+0x10 | `dirty.right-1`, `dirty.bottom-1` (inclusive) | V |
| +0x14 | `vendor.xrz.gray_256_dither` (0 if unset) | V |
| +0x18 | 0 | V |
| +0x1c | `vendor.xrz.global_color_enhance` (kernel: saturation slot) | V |
| +0x20 | `persist.vendor.xrz.global_dark_level` (kernel: contrast slot) | V |
| +0x24 | `vendor.xrz.global_brightness_level` | V |
| +0x28 | dma-buf fd of the **client's ION buffer** (or the daemon's frame buffer) | V |
| +0x2c | format: **1 = RGBA8888** (client buffers are forced to 1 by `setHandwrittenBuffer`); 0 = gray8 | V |
| +0x30/+0x34 | `layout.left`, `layout.top` = the view's position on the panel | V |
| +0x38/+0x3c | buffer width (= stride) and height | V |
| +0x40 | `remap` = always 0 (`Committer::commit` passes `false`) | V |

Other ioctls the daemon uses:
- `0xC00464D0`, get colour/panel type, in `mtk_init`. The device reports `panel_type=0x21` → colour → RGBA frame buffer.
- `0xC01064D2`, wait vsync.

Both are on the fd from `open("/dev/dri/card0", O_RDWR)`. **There is no fallback**: if the ioctl fails it returns -1 and
logs "commit buffer to eink failed"; nothing retries and nothing changes mode. [V]

## 2. Buffer model [V]
- **Client buffer.** `setHandwrittenBuffer(id, w, h, fmt, MemInfo)` allocates `w·h·4` bytes. The format is forced to
  1 = RGBA.
  - Allocation: legacy ION, `ion_alloc(len, align 0x1000, heap_mask 0x400, flags 3 = CACHED|NEEDS_SYNC)`, client name
    "handwritten". The fd comes from `ion_share`, the daemon `mmap`s it and returns a dup to the app.
  - The app side (`libHandwritten_jni.nativePrepare`) mmaps it and wraps it as an `SkBitmap`: kRGBA_8888 if bpp 4,
    alpha **kOpaque**, sRGB. The bitmap goes into a `Canvas` via `ACanvas_setBuffer`.
  - Size = the **bound view** (usually a SurfaceView), in **panel orientation**. `getCanvas()` applies the rotation
    matrix.
- **Direct mode (default, blend off).** The client buffer holds the **whole view content**. inksdk's `syncOverlay` and
  the xNote mirror canvas both blit the page into it. The ioctl updates only the dirty rect, at
  `layout.xy + (dirty − layout)`.
  - The content under the stroke is "preserved" because the buffer *is* the page. Strokes are plain black, non-AA paths
    (or `ColorMapper` patterns).
  - The kernel then renders the rect with the handwriting DU path, 1-bit (`KERNEL-PEN-PATH.md` §Q3). So greys and colours
    inside the bbox are temporarily dithered until the recommit repaints them.
- **Blend / overlay mode.** The client canvas is a transparent layer: ION is zero-filled, so alpha is 0.
  1. When the pen comes near, `Blender::sysBlend` takes `ScreenshotClient::captureDisplay` of SF's composed screen.
  2. `rotateToFrameBuffer` copies it, rotated to panel orientation, into a **daemon-owned full-screen frame buffer**
     (RGBA because panel_type≠0; filled with 0xFF at alloc).
  3. On each commit, `blendToFrameBuffer` draws the client layer's dirty rect **SrcOver** onto that frame buffer (Skia
     `drawImageRect`, kSrcOver).
  4. `onBlendDone` → `requestCommitFrame(rect, mode, effect)` commits the frame buffer (layout 0,0, full panel).
- **libxrzRender** (GLES `GlesDraw` / `GlesProcessLayer`) is **not used** by the pen path: handwrittenservice does not
  link it. It uses only `EffectHandler::convertToGray` from libxrzUtil.

## 3. Coexistence with SF and the HWC and handoff to the real frame [V]
1. **HWC layer flags** (vendor HWC `CommitPolicy::checkLayersAndSetCommitInfo` @0xeea10). `bindView` sets the view's mode
   to `0x40000|0x400|178` (and `0x40000|178` on unbind; `setNormalCommitEnable`). For each layer:
   - **SURFACEVIEW (bit16) + 0x400 (bit10) + HANDWRITTEN (bit18) = "v3 hwl"**: the layer is skipped entirely. Its damage
     never produces an HWC commit (@0xeee88-0xeeee8, log "is v3 hwl").
   - **HANDWRITTEN without SURFACEVIEW = "v2 hwl"**: ignored unless `isWholeLayerDirty` ("ignore v2 hwl", @0xef38c).
   - HANDWRITTEN_BG (bit20) is tested at @0xef024; its effect was not decoded. [I]

   The A16 SF never sends these flags (desc 71). The shim can.
2. **`vendor.xrz.disable_commit=1`** while the pen is active over a layer with recommit enabled. The vendor HWC then
   returns early with "skip: system commit" (@0xeebd4 → 0xef618), so **no** HWC E Ink commit happens at all.
3. **SF capped at 8 fps** while committing, as described in §1.5.
4. **Handoff (recommit).** After `delay` ms with no pen events over the layer:
   1. `disable_commit=0`,
   2. `force_global_refresh_mode=<mode>`,
   3. `repaintEverything()`.

   The HWC repaints the full screen from the app's real frame, replacing the provisional 1-bit ink. Global mode also
   clears the overlay strokes (`GlobalNoteView` "global clear" callback; GlobalWritingService: `resetLayerList` after
   1.7 s, then `forceGlobalRefresh(0)` 300 ms later). **`clearOverlay` does nothing** (above).

## 4. Hard dependency on 1029/1030? [V/I]
- **Daemon.** Mode-agnostic. The client's `CommitInfo.mode` goes straight to ioctl +0x00. Its only hard-coded uses of
  0x405/0x406 are the 1×1 warm-up and `VSyncThread::setLoopAgain` (dead). It has no fallback. [V]
- **Client.** Policy restricts the fast channel to 1029/1030/0x400|{4, B1, B2}. [V]
- **Kernel.** Per the kernel track, low byte 5/6 = handwriting → DU waveform, colour path skipped, own collision rules,
  never merged; **no eink_key needed**. 1029 should therefore work on the GSI as it is. [V kernel doc]
- **Plain A2 (16) or DU (2) small rects** would work in the same loop mechanically. [I] They lose the handwriting
  collision and no-merge policy and may hit the AUTO-only partial-update rule. **1029 is the first choice.**

## 5. Global handwriting for third-party apps (OneNote, WPS, …) [V]
There are two different features on this firmware.

**a) Ink over listed apps** (stock **Launcher** `/system/app/Launcher`, `MenuFragmentActivity`):
1. DisplayPolicyService has a hard-coded activity list (OneNote navigation, WPS writer/sheet/ppt/pdf, Evernote/Yinxiang,
   WeRead, jxw…). On a top-activity match it broadcasts `com.xrz.eink.display.policy` with
   `action=REQUEST_GLOBAL_HANDWRITTEN, enable`.
2. The Launcher receiver calls `showFloatWindow(false)`, which adds a **full-screen window** with:
   - `type 2026` (TYPE_DISPLAY_OVERLAY, system uid),
   - `flags 24` = **NOT_FOCUSABLE|NOT_TOUCHABLE**,
   - `format TRANSLUCENT`,
   - content: a `GlobalNoteView`, a SurfaceView-based NoteView bound to HandwrittenClient. Settings: overlay enabled,
     transparent foreground, recommit **1000 ms / mode 178**, stylus **not** disabled.
3. The **app underneath gets the real MotionEvents** (the window is not touchable) and draws its own ink.
   handwrittenservice reads the same pen evdev and paints fast provisional ink into the overlay buffer.
4. After 1 s idle the overlay is cleared and the recommit repaints with the app's real frame.

**b) GlobalWritingService** (`com.xrz.globalwritingservice`, sharedUserId system) is **handwriting-to-text**.
1. `XrzSystemProxyService.onEditFocus` calls `IGlobalHandwritingListener.onStartHandwriting(inputType)` when a text editor
   gains focus. The setting `XRZ_GLOBAL_HANDWRITING_RECOGNITION_ENABLED` must be 1.
2. It then shows a type-2026 window (flags 0x40118 = NOT_FOCUSABLE|NOT_TOUCHABLE|LAYOUT_IN_SCREEN|WATCH_OUTSIDE) holding
   a NoteView bound to HandwrittenClient. It calls `XrzEinkManager.disableStylus(true)`, so only the overlay sees the pen.
3. Recognition runs through `content://com.xrz.handwriting.provider/recognize`, and `XrzSystemProxyService.commitText`
   inserts the text.

**For Google Keep on our GSI** the idea in (a) applies directly. Keep keeps receiving MotionEvents and renders the real
ink, and a system component paints provisional ink straight to the panel until Keep's frame catches up. We do not need a
window or HandwrittenClient: the provisional ink can be drawn by the component that commits to the panel (see §6).
- A16 SF/framework has no flags and no `com.xrz.*` classes. The stock binary links Bigme's A14 libgui
  (`repaintEverything`, `captureDisplay` ABI), libhwui and libskia_shard, so it cannot be reused. [V NEEDED/imports]

## 6. Re-implementation plan for the GSI

### 6.1 What the current path does wrong
The HWC track recorded 9.6 s of pen-down in Keep (`hwc/keep-commit-timeline.txt`):
- The HWC produced **only 13 commits** in that time; 470 policy runs were "no dirty" skips.
- Every commit was **full-screen 0xb3**, at about 31 ms per ioctl.
- The kernel cost scales with frames × area, and full-screen updates collide with each other.

The stock fast path does the opposite: a small rect, the DU handwriting mode and a direct ioctl, with no SF frame in
between.

### 6.2 Minimal components
1. Pen evdev reader: event4, no grab.
2. Stroke rasteriser: non-AA black disc or line stamping, width from pressure. Optional extrapolation; stock xNote turns
   prediction off.
3. Background = the frame currently on the panel.
4. Committer:
   - rect = segment bbox ±w/2+1, mode 1029;
   - commit one at a time; pull and coalesce whatever accumulated during the previous ioctl;
   - before the first stroke, a 1-px 1029 warm-up on proximity, like `prepareCommit`.
5. HWC gate while the pen session is active.
6. Handoff:
   - pen idle ≥ T (stock uses 1000 ms for third-party apps), then
   - release the gate and re-commit the app's latest frame over the stroke bbox (or full screen) in a clean mode
     (178/GU16/GC16 via the existing settled-refresh code).
7. Policy: on/off, per-app allowlist, excluded bar zones.

### 6.3 Where each piece can live
| Option | Background frame | DRM / ioctl | Pen input | Gating and handoff | SELinux / DAC needed | Risk |
|---|---|---|---|---|---|---|
| **A. Inside the composer (hwcshim)** | **Native**: the shim already has the last committed RGBA buffer (`g_last_update.fd`, client target) | **Native**: HWC fd, `real_mtk_commit`, `g_commit_lock` serialises with HWC commits | Composer is uid system, groups graphics+drmrpc, CapEff=SYS_NICE; event4 is root:input 0660 and has no `input_device` rule. **Needs** einkd (root) to pass the fd over SCM_RIGHTS or forward samples on an abstract SOCK_SEQPACKET socket | **Native**: hold or drop HWC commits in `hook_mtk_commit`, then re-commit (like `recommit_gc16`) | `sepolicy.rule`: `allow hal_graphics_composer_default self:unix_seqpacket_socket { create bind listen accept read write getopt setopt }`; if an fd is passed: `allow hal_graphics_composer_default magisk:fd use; allow hal_graphics_composer_default input_device:chr_file { read ioctl getattr }` | A crash in composer means `onrestart restart surfaceflinger`, i.e. a full UI restart. Keep the code small and bounded. |
| **B. Separate root daemon** (extend einkd, magisk domain) | **Not available.** Would need the shim to export the frame dma-buf (socket and fd pass) or an SF screenshot (no NDK API; slow) | Possible: kernel ioctl is RENDER_ALLOW, root opens card0 [V kernel doc]. ION through /dev/ion (0666) [V device] | Native (root; einkd already reads all input devices) | Through props: `vendor.xrz.disable_commit` (vendor_default_prop, composer can read it [V device getprop -Z]) + shim knobs. Two committers race unless gated | Magisk domain is unconfined; the composer side needs the socket rules above if it exports frames | Isolated crashes, but stale background or race windows |
| **C. E-Ink Control app** | none | **Not allowed**: untrusted or system_app has no `dri_device`; card0 is system:system 0660 | not allowed: input group, `input_device` | n/a | n/a | UI and policy only |

### 6.4 Recommendation
**A+B hybrid: the "pen engine" lives in hwcshim, and einkd is the pen feeder.**
1. **einkd (root)** already opens the input devices. It forwards Wacom samples `{x, y, p, tool, state, ts}`, or the event4
   fd once, to the shim over an abstract SOCK_SEQPACKET socket. It also handles policy props and `clean_a2`.
2. **hwcshim** runs one thread (normal priority, lower than RT):
   - **On proximity:** do the warm-up commit. Lazily copy the background from the last committed buffer into a private
     2480×1860 RGBA ION buffer. The pen axes appear to be panel-native (2480×1860 = the client-target size); calibrate
     with §7 E1.
   - **While the pen is down:** rasterise the stroke into the private buffer and commit small rects with mode 1029,
     coalescing during each ioctl. While the session is active, **hold** HWC commits (keep only the latest), the
     equivalent of stock `disable_commit`. Then:
     - optionally skip the shim's colour and `processImage` work to free CPU;
     - optionally push flags through desc 71 later.
   - **On idle T:** release the gate and commit the latest app frame. Use the bbox union or full screen, in mode 178 or
     GU16.
3. **E-Ink Control:**
   - a QS tile "Pen mode" and a per-app allowlist (Keep, Squid, OneNote…), stored as props that the shim reads;
   - stroke width, idle T and mode;
   - excluded top and bottom bar zones.

Fallback or prototype: option B as a stand-alone root test tool (see §7).

## 7. Proposed device experiments (each needs owner approval: they write props or issue ioctls)
- **E1 (read-only, owner drives it).** Run `getevent -lt /dev/input/event4` while touching the four corners in portrait,
  to fix the axis map from the pen to the 2480×1860 client target. This also measures the report rate and how proximity
  (BTN_TOOL_PEN) behaves.
- **E2.** A root test tool:
  1. take a background with one `screencap`;
  2. commit a 40×40 black square in mode 1029, and separately in 16 and 2, at a known spot;
  3. measure ioctl time;
  4. check that the background around the square survives;
  5. check how two adjacent rects behave (concurrency per the kernel's 90 soft pipes).
- **E3.** Run E2 while `vendor.xrz.disable_commit=1`, to confirm that HWC commits are suppressed on the GSI vendor HWC.
  Reset the prop to 0 afterwards.
- **E4.** Measure latency with a slow-motion phone video, comparing the Keep path with the E2 tool drawing at the pen
  position.
