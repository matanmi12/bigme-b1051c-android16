# Bigme e-ink API emulation (feature 35 b): research, 2026-10-30

Question: can the GSI offer Bigme's app API (`com.xrz.HandwrittenClient`, `xrz.framework.manager.XrzEinkManager`) so
that apps built for it work, and through which mechanism. Research by a read-only agent over `work/ref/inksdk`,
the decompiled stock framework (`research/phase-d/pen/userspace/jadx-*`) and `stock-bigme-display-stack.md`. The two
deciding facts were re-checked by hand (marked "checked").

## Findings

- **inksdk finds the API only through reflection** on `com.xrz.HandwrittenClient` (with a `Proxy` for
  `HandwrittenClient$InputListener`). It has no `<uses-library>` and no compile-time stub. Its README (line 136) says
  it "requires `com.xrz.HandwrittenClient` in the boot classpath". It never touches `XrzEinkManager`.
- **Device gate (checked):** inksdk picks its Bigme controller only when `Build.MANUFACTURER` or `BRAND` is "Bigme"
  (`InkControllerFactoryTest.kt` 34-43). This B1051C reported `alps` / `alps` on stock firmware
  (`research/phase-c/20261017-231424-adb-stock/getprop_full.txt` 762, 782). The GSI reports `unknown` / `google` (adb,
  2026-10-30). So inksdk apps fall back to their Onyx path on this tablet even on stock firmware.
- **Stock Bigme apps** (handwriting service, xNote) compile directly against the classes. They are not on the GSI and
  are not wanted.
- **Where stock keeps the classes:** `framework.jar` (boot classpath), plus Bigme-modified AOSP classes (`View`,
  `Window`, `SurfaceView`, `PopupWindow.setRefreshMode`, `SurfaceControl`). Underneath:
  - `HandwrittenClient` uses the Binder service `handwrittenservice` (`com.xrz.IHandwrittenService`), its own
    InputChannel pair and `libHandwritten_jni.xrz.so`.
  - `XrzEinkManager` uses props, `libXrzFramework_runtime.so` and `xrz_display_policy_service`.

## API surface against what we have

| Stock call | Stock mechanism | Our equivalent |
|---|---|---|
| `XrzEinkManagerInternal.set/getGlobalRefreshMode` | prop `vendor.xrz.global_refresh_mode` | same prop |
| `forceGlobalRefresh(4 / 0)` | `vendor.xrz.force_global_refresh_mode` + SF code 61 | `vendor.debug.hwc.shim.gc16_req` (other modes: gap) |
| `HandwrittenClient.connect / disconnect` | Binder: client, ION buffer, input channel | Pen mode (`vendor.debug.hwc.shim.ink` 1/0), inferred |
| `getCanvas()` + `inValidate(rect, 1029/1030)` | the app draws into a shared buffer, the daemon commits | partial: our shim draws its own strokes, so the app's pixels and the eraser are lost |
| `registerInputListener` | the daemon forwards evdev | gap (einkd could feed it) |
| `setRefreshModeBy{View,Window,SurfaceView,PopupWindow}`, `setLayerRefreshMode` | modified AOSP classes, then SF / composer / HWC desc 71 | gap (global mode only) |
| `disableTouch/Stylus` | props read by a modified ViewRootImpl | gap |
| anti-flicker, colour restoration, auto clean, antialias | props / debugfs | possible through a root broker |

## Feasibility

- **Shared-library jar + permissions XML in a Magisk module: does not work.** Such a jar is loaded only into apps
  that declare `<uses-library>`, and no consumer does.
- **Boot-classpath jar in the ROM: the only path that works without changing apps.** It would be a pure-Java
  `com.xrz.HandwrittenClient` + `xrz.framework.manager.*` with a broker in E-Ink Control / einkd, since an app cannot
  set `vendor.*` props. Costs:
  - the system image changes for BOOTCLASSPATH;
  - the method signatures must be identical;
  - inksdk would still not engage unless the tablet reported manufacturer "Bigme": a fingerprint change with
    Play Integrity / GApps risk.
- **Zygisk / LSPosed class injection:** possible in principle (inferred), but fragile on a GSI.
- **Who would benefit:** inksdk's README names no app except its own demo. Third-party note apps never used this
  API on stock (stock served them through DisplayPolicyService + a launcher overlay; our Pen mode covers that ground).

## Recommendation

Skip the emulation for now; the broadcasts (E-Ink Control 0.15.22) are the useful part of 35. If a specific app turns
up that needs it, do the boot-classpath variant in the ROM with the mappings above (connect -> Pen mode,
forceGlobalRefresh -> gc16_req, setGlobalRefreshMode -> global_refresh_mode) and validate it with the inksdk demo.
This reverses the owner's 2026-10-18 answer to 35 ("everything"), so the owner decides.

**Decision (owner, 2026-10-30): skip the emulation.** Revisit only if a specific app needs it.
