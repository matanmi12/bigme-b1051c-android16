# System defaults: notes for the integrator

The item table is in `ROM-DEFAULTS.md`. The code is in `apply-system-defaults.sh`.

The script was checked with `dash -n` and with `bash --posix -n`. It was also run against a mock Android
command set in the scratchpad: a fresh run, a re-run, FORCE, the re-assert after a restore, DRY_RUN, stage1, and a
`post_setup` timeout. **It has not run on a device.** The tablet was queried read-only. Nothing was written to it.

## When each step runs

| Phase | What | Why there |
|---|---|---|
| **Boot 1** (stage 1, post-fs-data, before the framework) | Module trees copied in. They provide `ro.sf.lcd_density 350`, phh backpressure off, `service.sf.prime_shader_cache 0`, the SF 1008 boot loop, boot damage, the ccci stop, the fwoverlay (Night Light unavailable, Wi-Fi only, icon mask), feature XMLs, `.kl`/`.idc`, the Launcher3 patch and nav RRO, bootanim, fonts, icons, and gapps (`ro.frp.pst` deleted, privapp `log`). Also `/data/adb/b1051c`, plus **`stage1`**: `persist.wallpapercolors.max_dark_area 25`. | The props are read once by SF or zygote, before boot 2's framework. max_dark_area must be in effect before ThemeSync's first wallpaper set computes colorHints. |
| **Boot 2, at boot_completed** (Setup Wizard on screen) | `apply-system-defaults.sh` (system), WebView **first** | Google sign-in in the wizard needs a WebView provider. Animations off and the 10 min timeout help the wizard on e-ink. |
| **Boot 2, after `pm install`** | `app_einkctl` (su policy, then grants, then a11y), `app_einkui` (check), `app_runtime_overlay`, `qs_tiles_default`, `ime_defaults`, `app_all_files_access`. Then the apps defaults stage the wallpapers and IMPORT. Then `app_einkctl_start`. | These need the packages. The su policy must exist before anything starts E-Ink Control. |
| **After setup completes** | `post_setup` (foreground, last): restart Launcher3, HOME role = Lawnchair, start Home, wait 20 s, `after_setup_reassert`, one GC16 | Home role, restore handling (see below). |

**Needs a reboot to take effect:**
- `persist.wallpapercolors.max_dark_area`, but only if it was first set in boot 2 (the `sys_wallpaper_dark_area` fallback). After that reboot, set the wallpaper once more.
- All module props (boot 1 → boot 2 handles them).

Everything else applies live:
- The Launcher3 overlay needs a Launcher3 restart, not a reboot.
- The LatinIME theme needs an IME restart, which ThemeSync does.
- SystemUI applies the Monet pin when setup completes.

## Risks and things to verify on the first real flash

1. **max_dark_area in boot 1.**
   - `stage1` uses `resetprop -p`, because persistent props are not loaded yet at post-fs-data and a plain setprop never reaches the disk.
   - It needs `/data/property` to exist. It does not create the directory, because init creates it with an FBE policy and a pre-made directory can break that.
   - This path is untested on freshly formatted data.
   - A more robust choice: have b1051c-eink-compat's post-fs-data run `resetprop -n persist.wallpapercolors.max_dark_area 25` when the prop is unset. That runs every boot, before zygote.
2. **Default home during the Setup Wizard.**
   - A preferred non-system HOME set while the wizard runs may let the Home button leave the wizard, with `user_setup_complete` still 0.
   - `post_setup` therefore sets the HOME role only after setup completes.
   - Cost: Launcher3 may show for a few seconds after the wizard finishes, until `post_setup` switches to Lawnchair and starts Home.
   - `app_default_home` can be called right away if you accept that risk.
3. **Google restore in the wizard.**
   - "Copy apps & data" can restore secure and system settings from another device: QS tiles, maybe the theme JSON, the color mode, the timeout, font size, density.
   - `post_setup` waits `REASSERT_DELAY_S` (20 s) and then sets the layer-critical values again: the QS layout (only if one of our custom tiles is missing), the Monet pin, display color mode 0, night mode (auto → light), the a11y service, WebView, and animations (only if they are back at 1).
   - Restored personal values (timeout, font, density) are kept.
   - Open: whether 20 s is enough. The restore may continue after setup.
4. **`post_setup` blocks.** An init `service`/`exec` kills its process group when the main process exits, so a `&` child dies with it. Run `post_setup` in the foreground as the last stage-2 step. `$MARK_DIR/post-setup.pending` survives a reboot during setup, so a boot hook can call `post_setup` again. `post-setup.done` makes it a no-op.
5. **Play Protect and root `pm install`.**
   - Installs by uid 0 or shell carry `INSTALL_FROM_ADB`, so the Play verifier may hold each APK for up to `verifier_timeout` (17 s on the dev tablet). It may also ask "send for check", which blocked the owner on 2026-10-18 15:15.
   - The A16 `pm` here has no `--skip-verification`.
   - Optional: `verifier_pause` before the installs and `verifier_restore` right after. They save and restore the previous value; nothing personal is left changed.
6. **Magisk 30.7 `--sqlite`.** A SELECT that returns NULL columns crashed magiskd (sqlite_master, incident 2026-10-21 00:03; su was down until a reboot). The script only uses `REPLACE INTO policies …` and `SELECT policy …` (a non-NULL int). Do not add other queries.
7. **The su policy must use the real uid.** The script resolves it with an exact match on `pm list packages -U`, because the substring filter also lists `org.b1051c.icon.org.b1051c.einkctl`. Without the Magisk app, a uid with no policy is denied without a prompt, so `app_einkctl` writes the policy before it enables the accessibility service.
8. **The keeper does not start on a fresh install.** BootReceiver fires only at boot or on package replace, so `app_einkctl_start` is needed. ThemeSync reads the wallpapers from E-Ink Control's external files dir (`wallpaper-D-cream.png`, `dark-D.png`), not from its assets. The apps step must place them first, with the app's owner and label. Otherwise the first sync fails and retries only at the next trigger.
9. **Setup Wizard and FRP.** The b1051c-gapps post-fs-data still says "No Google setup wizard on this ROM" and deletes `ro.frp.pst`. With the wizard bundled now, check that its FRP step behaves with no PersistentDataBlockService (expected: it treats the device as having no FRP). Untested.
10. **display_color_mode.** The 2026-10-18 00:35 change to 0 was found back at 2 in the 12:37 backup. It is 0 again now (written by `android`). Check on the fresh device, after a reboot, that it stays 0 and that `persist.sys.sf.color_saturation` stays 1.0.
11. **Accessibility warning.** `accessibility.xml` has no `isAccessibilityTool`. Android may later post "E-Ink Control can view and control your screen". If the user taps "Turn off", profiles, the nav refresh button and the shutdown screen stop working. Suppressing it (`notified_non_accessibility_category_services`) is not done: owner decision. Mention it in KNOWN-ISSUES.
12. **Monet during setup.** SystemUI logs "Theme application deferred" and applies the pin when setup completes. The wizard shows the default palette until then. Expected.

## Open questions for the owner

1. **Density and font size.** ~~Ship 300 / 1.0 or 350 / 1.3?~~ **Decided 2026-10-21: 350 dpi** (module prop, 0.1.27); the font scale stays 1.0.
2. **Anti-shake tile.** **Resolved 2026-10-21:** Anti-shake works since module 0.1.28, so the tile stays. It ships off (`anti_flicker=false`) and is turned on per app. (Old note: it is in the owner's layout, but the 2026-10-20 23:05 research said anti-shake cannot work on the GSI (the HWC's write to `eink_debug/anti_flicker` fails on every commit). I recommend dropping it from the public default layout: edit `QS_TILES_TEMPLATE`.
3. **"Switch system" (TileRom).** It is not in the default layout. Should the ROM also hide it from the tile editor (`app_einkctl_hide_dsu_tile`, which uses pm disable)? Without a DSU image it offers a reboot that cannot work.
4. **KOReader All files access.** Pre-grant it (`app_all_files_access`), or let KOReader ask on its first start? The Bigme patches in `/sdcard/koreader` need it.
5. **Default wallpaper during the wizard.** The TD16 default wallpaper is dark navy. The owner read it as a "black screen" at the 2026-10-18 13:26 cold boot. Option: a framework RRO in B1051cFrameworkOverlay (default wallpaper = the cream image), so the first frames are cream before ThemeSync runs.
6. **Earlier defaults (optional).** A static RRO for `com.android.providers.settings` (`def_screen_off_timeout`, `def_window_animation_scale`, `def_window_transition_scale`) would set these when the settings DB is created, before the wizard's first frame. The script stays as the guard either way.
7. **E-ink tweaks the owner never decided:**
   - heads-up notifications (each one is a panel update)
   - the LatinIME key popup (`popup_on`, AOSP default on)
   - the Lawnchair notification-dot listener (not granted, user's choice)

   All are left at the defaults.

## Items I could not classify with confidence

- **`accessibility_button_mode 1`** (floating menu; `package=android`). Probably the system set it. Not applied.
- **`qs_auto_tiles`.** Classified ROM-DEFAULT (keeps the layout stable). If SystemUI ever writes its in-memory set over ours, the effect is only that some tiles may auto-add later. Harmless.
- **`screen_off_timeout` 10 min.** The owner approved it at 2026-10-18 00:18 and plan B3 lists it, but it reads like a preference. Kept as ROM-DEFAULT. The guard only applies it over the GSI default.
- **`display_color_mode 0`.** The log gives no reason beyond "owner-approved". The reasoning about neutral SF output for the shim pipeline is mine.

## Reading the log

- The script logs one line per step: `<date> sys-defaults: <item>: old -> new`, or `(ok)`, or `kept`, or `WARN`/`ERROR`.
- `DRY_RUN=1` with `LOG`/`MARK_DIR` in a test directory logs every write instead of doing it. Reads still run, so a dry run against the dev tablet shows the diff.
- Markers in `$MARK_DIR`:
  - `system-defaults.done`
  - `post-setup.pending` / `post-setup.done`
  - `verifier.saved` (only while paused)
