# Scroll benchmarks, 2026-10-21

Method: `tools/scripts/bench/*.sh` (run as root on the tablet). 8 scripted swipes in Settings per round; the kernel's
`update image:order=N` dmesg lines give the panel-update count and the gap between consecutive updates (< 1 s = inside
a scroll burst); CPU = utime+stime of the composer and of init over the round. Rounds alternate conditions to cancel
drift; all toggles are runtime (magiskpolicy --live, setprop), nothing persistent.

| Mode | Vendor-prop writes | Anti-shake | Updates/round | Mean gap | Max gap | Composer CPU | init CPU |
|---|---|---|---|---|---|---|---|
| Regal 180 | allow | on | 23.7 | 244 ms | 516 ms | 5.7 s | 230 ms |
| Regal 180 | deny | on | 24.3 | 236 ms | 492 ms | 6.0 s | 130 ms |
| Regal 180 | deny | on/off | 20 / 20 | 240 / 240 ms | ~510 / ~505 ms | 2.7 s | — |
| Extreme 179 | allow | on / off | 130 / 126 | 55 / 37 ms | 653 / 389 ms | 8.0 / 7.9 s | 255 / 265 ms |
| Extreme 179 | deny | on / off | 126 / 125 | 59 / 36 ms | 653 / 397 ms | 7.9 / 7.8 s | 140 / 145 ms |
| Fast 178 | allow | on / off | 134 / 131 | 54 / 38 ms | 654 / 389-849 ms | 8.3 / 8.2 s | 270 ms |
| Fast 178 | deny | on / off | 131 / 128 | 55 / 38 ms | 653 / 394-734 ms | 8.1 / 8.0 s | 145 ms |
| Regal 180 | dropped in the shim (0.1.30) | off | 21-23 | 248-255 ms | 499-520 ms | 5.2-5.7 s | 0 |

Conclusions:
- Regal is panel-bound (~240 ms per update): neither Anti-shake nor the vendor-prop writes change it.
- Anti-shake in Fast/Extreme: ~+18 ms per update (+50%) and max stall ~650 vs ~390 ms; looks smoother. Owner: off
  globally, per-app only (E-Ink Control profile).
- The vendor HWC's status-prop writes (fps_limit, hwc_idle, gray_256_dither) do not change latency in any mode but cost
  init CPU and ~140 log lines per two swipes when denied. Since eink-compat 0.1.30 the shim drops them before the call.
