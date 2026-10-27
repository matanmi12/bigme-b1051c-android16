package org.b1051c.einkctl;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Device knobs of the Bigme B1051C Pro (MT6877, EC103KH3C6 Kaleido 3) as seen from a GSI.
 * Mechanisms (verified on the device 2026-10-18):
 *  - frontlight: lm3630a sysfs, two channels, raw 0..255 each (values wrap above 255 -> always clamp)
 *  - refresh mode: vendor HWC reads vendor.xrz.global_refresh_mode live (0 = no override)
 *  - colour: vendor.xrz.global_{color_enhance,contrast,brightness_level,color_mode} -> HWC shim colour pipeline
 *  - kernel e-ink driver knobs: /sys/kernel/debug/eink_debug/* (never touch waveform / wf_ota / vcom)
 */
final class Eink {
    static final String FL = "/sys/devices/platform/11d20000.i2c1/i2c-1/1-0036/";
    static final String ED = "/sys/kernel/debug/eink_debug/";

    /** Frontlight maximum per channel on the B1051C (stock / dbenjaminb value; the register itself accepts 255). */
    static final int FL_MAX = 222;

    static final String P = "einkctl";

    private Eink() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ---- frontlight -------------------------------------------------------------------------
    static String frontlightCmd(int cold, int warm) {
        // On this unit the sysfs names are swapped relative to the LEDs (owner-verified 2026-10-18):
        // lm3630a_warm_light drives the cold/white LEDs and lm3630a_cold_light drives the warm/amber ones.
        return "echo " + clamp(cold, 0, FL_MAX) + " > " + FL + "lm3630a_warm_light; echo " + clamp(warm, 0, FL_MAX)
                + " > " + FL + "lm3630a_cold_light";
    }

    /** cold/warm from the manual sliders, or — when linked — from Android's brightness slider + the warmth mix. */
    static int[] frontlightValues(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("light_on", false)) return new int[] {0, 0};
        if (p.getBoolean("link_brightness", false)) {
            int level = android.provider.Settings.System.getInt(c.getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) * FL_MAX / 255;   // 0..255 -> 0..222
            int warmth = p.getInt("warmth", 50);                                        // 0 = all cold, 100 = all warm
            return new int[] {level * (100 - warmth) / 100, level * warmth / 100};
        }
        return new int[] {p.getInt("cold", 40), p.getInt("warm", 40)};
    }

    static void applyFrontlight(Context c) {
        int[] v = frontlightValues(c);
        // asleep: never light the panel (brightness follow, import, keeper start); the keeper applies it at SCREEN_ON
        // (review 2026-10-21, same rule as setLight)
        if (c.getSystemService(android.os.PowerManager.class).isInteractive()) Root.async(frontlightCmd(v[0], v[1]));
        publishLight(c, v);
    }

    static int secureInt(Context c, String key, int def) {
        try { return android.provider.Settings.Secure.getInt(c.getContentResolver(), key, def); }
        catch (SecurityException e) { return def; }
    }

    static void putSecure(Context c, String key, int v) {
        try { android.provider.Settings.Secure.putInt(c.getContentResolver(), key, v); }
        catch (SecurityException e) { Root.async("settings put secure " + key + " " + v); }
    }

    // ---- display density ----------------------------------------------------------------------
    /** Base density set by the b1051c-eink-compat module (ro.sf.lcd_density); `wm density reset` returns to it. */
    static final int DPI_DEFAULT = 300;
    static final int DPI_MIN = 100, DPI_MAX = 500;

    /** Effective density from `wm density` (override if set, else physical); -1 if unknown. Not on the UI thread. */
    static int currentDpi() {
        int phys = -1, over = -1;
        for (String line : Root.run("wm density").split("\n")) {
            line = line.trim();
            try {
                if (line.startsWith("Physical density:")) phys = Integer.parseInt(line.substring(17).trim());
                else if (line.startsWith("Override density:")) over = Integer.parseInt(line.substring(17).trim());
            } catch (NumberFormatException ignored) {}
        }
        return over > 0 ? over : phys;
    }

    /** Applied at once and persisted by the window manager across reboots. */
    static void setDpi(int dpi) {
        Root.async(dpi == DPI_DEFAULT ? "wm density reset" : "wm density " + clamp(dpi, DPI_MIN, DPI_MAX));
    }

    // ---- QS front-light sliders (E-Ink SystemUI plugin) --------------------------------------------
    // Shared state in Settings.Secure: the plugin writes when a slider moves, we write the light we apply.
    static final String KEY_COLD = "b1051c_fl_cold";
    static final String KEY_WARM = "b1051c_fl_warm";

    static void publishLight(Context c, int[] v) {
        android.content.ContentResolver cr = c.getContentResolver();
        try {
            if (android.provider.Settings.Secure.getInt(cr, KEY_COLD, -1) != v[0]) android.provider.Settings.Secure.putInt(cr, KEY_COLD, v[0]);
            if (android.provider.Settings.Secure.getInt(cr, KEY_WARM, -1) != v[1]) android.provider.Settings.Secure.putInt(cr, KEY_WARM, v[1]);
        } catch (SecurityException e) {                  // WRITE_SECURE_SETTINGS not granted yet
            Root.async("settings put secure " + KEY_COLD + " " + v[0] + "; settings put secure " + KEY_WARM + " " + v[1]);
        }
    }

    /** A QS slider moved: adopt the values as the light and apply them. */
    static void lightFromSystemUi(Context c) {
        android.content.ContentResolver cr = c.getContentResolver();
        int cold, warm;
        try {
            cold = android.provider.Settings.Secure.getInt(cr, KEY_COLD, -1);
            warm = android.provider.Settings.Secure.getInt(cr, KEY_WARM, -1);
        } catch (SecurityException e) {
            return;
        }
        if (cold < 0 || warm < 0) return;
        setLight(c, cold, warm, false);
    }

    /** Adopt cold/warm (raw 0..FL_MAX) as the light and apply it. From the QS sliders (already published) or from
     *  KOReader (publish, so the QS sliders follow; the echo comes back through lightFromSystemUi and stops here). */
    static void setLight(Context c, int cold, int warm, boolean publish) {
        cold = clamp(cold, 0, FL_MAX);
        warm = clamp(warm, 0, FL_MAX);
        int[] cur = frontlightValues(c);
        if (cur[0] == cold && cur[1] == warm) return;   // echo of our own publish
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit().putBoolean("light_on", cold + warm > 0);
        if (cold + warm > 0) {
            e.putBoolean("link_brightness", false).putInt("light_preset", LIGHT_CUSTOM);   // a slider moved: Custom
            e.putInt("cold", clamp(cold, 0, FL_MAX)).putInt("warm", clamp(warm, 0, FL_MAX));
        }
        e.apply();
        // asleep: only remember it (the keeper lights the panel at SCREEN_ON) — a change from settings / KOReader /
        // adb must never switch the light on in a dark room while the screen is off (seen 2026-10-19)
        if (c.getSystemService(android.os.PowerManager.class).isInteractive()) Root.async(frontlightCmd(cold, warm));
        if (publish) publishLight(c, new int[] {cold, warm});
    }

    static void frontlightOff() { Root.async(frontlightCmd(0, 0)); }

    // ---- front-light presets (owner 2026-10-22; redone 2026-10-26: "not useful") ---------------------------
    // The raw scale is exponential (stock's 37-step table is evenly spaced at the top; Gentle Glow models the same
    // LEDs as lux = e^(raw/34)/17), so the 22.10 mixes jumped 0.14..27 "lux" while only meaning to change the tint.
    // Now brightness and warmth (share of the light from the amber LEDs) are chosen per use, after stock's
    // Day/Night/Bed and Gentle Glow's Day 60%/33%, Sunset 30%/80%, Night 12%/85% (x1.5 for the darker colour panel):
    //   Morning ~40 lux 25 % warm (the owner's daily brightness, enough amber to stop the blue grays)
    //   Night   ~3.8 lux 80 % warm (evening reading in a lit room)
    //   Bed     ~0.8 lux amber only (dark room)
    //   Color   ~41 lux 54 % warm (most neutral grays + skin near its hue, light-mix test 22.10; calibration target)
    // Custom = the sliders (moving one, in the app, in the shade or from KOReader, switches to it).
    static final String[] LIGHT_NAMES = {"Morning", "Night", "Bed", "Color", "Custom"};
    /** {cold, warm}, raw 0..FL_MAX; Custom (-1) keeps the sliders. */
    static final int[][] LIGHT_PRESETS = {{212, 175}, {87, 134}, {0, 90}, {196, 202}, {-1, -1}};
    static final int LIGHT_CUSTOM = 4;

    static int lightPreset(Context c) { return clamp(prefs(c).getInt("light_preset", LIGHT_CUSTOM), 0, LIGHT_CUSTOM); }

    /** Select a light preset: switches the light on at its values (the QS sliders follow), or keeps the sliders. */
    static void selectLight(Context c, int idx) {
        idx = clamp(idx, 0, LIGHT_CUSTOM);
        SharedPreferences.Editor e = prefs(c).edit().putInt("light_preset", idx);
        int[] v = LIGHT_PRESETS[idx];
        if (v[0] >= 0) e.putBoolean("light_on", true).putBoolean("link_brightness", false).putInt("cold", v[0]).putInt("warm", v[1]);
        e.apply();
        applyFrontlight(c);
    }

    // ---- refresh mode -----------------------------------------------------------------------
    // Global override read live by the vendor HWC: vendor.xrz.global_refresh_mode (0 = no override, i.e. the
    // HWC default 178 / per-layer). force_global_refresh_mode is the HWC's one-shot full-refresh knob — reset
    // it to -1 so a value left by app v0.5 doesn't pin a mode.
    static final int UNSET = Integer.MIN_VALUE;

    /** Stock Eink Center modes (HD 256 = 177 | DITHER_256, Fast 178, Extreme 179, Regal 180) + A2. Stock Hyper (183)
     *  is gated by the kernel on panel size (is_support_hyper_mode: w*h < 2,123,521; this panel is 2496x1944), so
     *  every 0xb7 update is rejected ("This mode(0xb7) is unsupport") and the panel freezes (owner 2026-10-20)
     *  -> not offered, stored 183 = Fast. */
    static final int HYPER = 183;
    /** Pen (owner 2026-10-20, stock's ink layer over WPS & co.): the app runs in Fast, and while the pen writes the
     *  HWC shim draws the stroke itself and commits only its box in the stock handwriting mode 1029 (einkd feeds it
     *  the Wacom reports). Stored as 1029; never sent to the vendor HWC as a mode. */
    static final int PEN = 1029;
    static final int[] MODES = {0, -2147483471, 178, 179, 180, 16, PEN};
    // "Regal (GU16)": without the stock /system/eink_key the kernel remaps Regal 0xb4 to GU16 0x84 (dmesg "this machine
    // not support regal mode", 2026-10-20); the mode value stays 180
    static final String[] MODE_NAMES = {"Auto (app default)", "HD 256", "Fast", "Extreme", "Regal (GU16)",
            "A2 (fastest, black/white)", "Pen (ink layer)"};

    static int normMode(int m) {   // app <= 0.5 stored -1 for Auto
        if (m == -1) return 0;
        if ((m & 0xffff) == HYPER) return 178;
        for (int x : MODES) if (x == m) return m;
        return 0;   // anything else (an imported or hand-edited value) never reaches the vendor HWC (review 2026-10-21)
    }

    /** Mode for a package: its own profile if set, else the global mode. */
    static int modeFor(Context c, String pkg) {
        SharedPreferences p = prefs(c);
        int m = pkg == null ? UNSET : p.getInt("app:" + pkg, UNSET);
        return normMode(m != UNSET ? m : p.getInt("mode", 0));
    }

    /** The mode, plus the kernel anti-alias switch: on only in Fast (178, also the HWC default for Auto), like stock.
     *  Owner test 2026-10-18: slightly sharper text, but grey backgrounds turn into stripes -> off by default. */
    static String modeCmd(Context c, int mode) {
        mode = normMode(mode);
        boolean pen = mode == PEN;
        if (pen) mode = 178;
        boolean aa = prefs(c).getBoolean("anti_alias", false) && (mode == 178 || mode == 0);
        return "setprop vendor.xrz.global_refresh_mode " + mode + "; setprop vendor.debug.hwc.shim.ink " + (pen ? 1 : 0)
                + "; echo " + (aa ? 1 : 0) + " > " + ED + "anti_alias";
    }

    /** Global refresh setting changed: the profile engine decides what the current app gets. */
    static void applyRefreshMode(Context c) {
        Profiles.apply(c, AppProfileService.currentPackage(), true);
    }

    static int modeIndex(int mode) {
        mode = normMode(mode);
        for (int i = 0; i < MODES.length; i++) if (MODES[i] == mode) return i;
        return 0;
    }

    /**
     * One full-screen GC16 of the settled screen. The shim (2026-10-19) re-sends the final frame as GC16 once commits
     * have been quiet for ~350 ms, so a screen change flashes once (the old 1.2 s commit_mode-4 window turned every
     * frame of a transition into a flash: owner "too many flashes") and a static screen works too (the vendor HWC
     * commits nothing for an unchanged frame, which is why the nav button needed a pressed-state trick before).
     */
    static void clearScreen(Context c) {
        Root.async("setprop vendor.debug.hwc.shim.gc16_req " + (android.os.SystemClock.uptimeMillis() % 1_000_000_000L));
    }

    // ---- colour profiles (stock Eink Center presets; rendered by the HWC shim's port of Bigme's shader) --
    static final String[] PROFILE_NAMES = {"Default", "Magazine", "Comic", "Video", "Custom"};
    /** The shim's OKLCh chroma stage (vendor.debug.hwc.shim.ok_sat) when a preset does not set its own: the owner's
     *  pick from the colour A/B of 2026-10-22 (research/phase-d/color-baseline-2026-10-22/round2/RESULTS.md). */
    static final int OK_SAT = 60;
    /** Steps offered by the tiles and the per-app editor (sliders in the app take any value). Color boost = ok_sat;
     *  Darker text = the shim's input black point (level_black): the owner liked 20 for reading apps, 40 merges the
     *  darkest grays (A/B round 2), off elsewhere. */
    static final int[] SAT_VALUES = {0, 30, 60, 100};
    static final String[] SAT_NAMES = {"Off", "Low (30 %)", "Normal (60 %)", "High (100 %)"};
    static final int[] TEXT_VALUES = {0, 20, 40};
    static final String[] TEXT_NAMES = {"Off", "Darker (20)", "Darkest (40)"};

    /** Index of v in a step list, or -1. */
    static int stepIndex(int[] steps, int v) {
        for (int i = 0; i < steps.length; i++) if (steps[i] == v) return i;
        return -1;
    }
    /** {refresh mode or UNSET (keep), vivid, contrast, colour brightness, colour mode, auto clean, anti-shake, ok_sat
     *  (-1 = the "ok_sat" setting)}. Magazine / Comic / Video from the stock system build.prop sys.maga_* / sys.comic_*
     *  / sys.video_* (vivid is 0 in Extreme on stock). Default = stock's Default (0.15.12 made it the owner's look for an
     *  hour; the owner reverted that the same day and kept ok_sat 60 for every preset). */
    static final int[][] PRESETS = {
            {UNSET, 0, 0, 0, 0, -1, -1, -1},
            {-2147483471, 70, 0, 0, 1, 0, 0, -1},
            {180, 90, 90, 0, 2, 0, 0, -1},
            {179, 0, 0, 0, 3, 1, 1, -1},
            {UNSET, -1, -1, -1, 4, -1, -1, -1},
    };

    /** Select a profile: stores its values (Custom keeps the sliders) and applies everything. */
    static void selectProfile(Context c, int idx) {
        SharedPreferences.Editor e = prefs(c).edit().putInt("profile", idx);
        int[] v = PRESETS[idx];
        if (v[0] != UNSET) e.putInt("mode", v[0]);
        if (v[5] >= 0) e.putBoolean("auto_clean", v[5] == 1);
        if (v[6] >= 0) e.putBoolean("anti_flicker", v[6] == 1);
        e.apply();
        applyRefreshMode(c);
        applyImage(c);
    }

    // ---- image knobs ------------------------------------------------------------------------------
    /** Device-wide image knobs; colour, anti-shake and full-refresh-every-N go through the profile engine. */
    static void applyImage(Context c) {
        SharedPreferences p = prefs(c);
        boolean ac = p.getBoolean("auto_clean", false), cr = p.getBoolean("color_restoration", false);
        StringBuilder sb = new StringBuilder();
        // auto clean: prop + kernel clean_a2 (stock setAutoCleanEnable)
        sb.append("setprop vendor.xrz.auto_clean_enabled ").append(ac ? 1 : 0).append("; ");
        sb.append("echo ").append(ac ? 1 : 0).append(" > ").append(ED).append("clean_a2; ");
        // colour restoration: the HWC uses dither 3 instead of 6 in Fast / Extreme (stock B1051C 4.6.6 checkbox)
        sb.append("setprop vendor.xrz.color_restoration_enabled ").append(cr ? 1 : 0).append("; ");
        // undo the kernel-node experiments of app 0.3-0.5 (not used by stock; no proper effect)
        sb.append("for n in saturation contrast brightness; do echo 0 > ").append(ED).append("$n; done; ");
        Root.async(sb.toString());
        Profiles.apply(c, AppProfileService.currentPackage(), true);
    }

    /** Modes for parts of the screen the HWC shim recognises by their layer (owner 2026-10-27): the navigation button
     *  row when only it changed, and a playing video. 0 = the app's own mode. */
    static final int[] NAV_MODES = {178, 179, 16, 0}, VIDEO_MODES = {179, 178, 0};
    static final String[] NAV_NAMES = {"Fast", "Extreme", "A2 (black/white)", "Same as the app"},
            VIDEO_NAMES = {"Extreme", "Fast", "Same as the app"};

    static void applySystemModes(Context c) {
        SharedPreferences p = prefs(c);
        Root.async("setprop vendor.debug.hwc.shim.nav_mode " + p.getInt("nav_mode", 178)
                + "; setprop vendor.debug.hwc.shim.video_mode " + p.getInt("video_mode", 179));
    }

    static void applyAll(Context c) {
        Root.async("setprop vendor.xrz.force_global_refresh_mode -1; setprop persist.b1051c.pen_palm "
                + (prefs(c).getBoolean("pen_palm", true) ? 1 : 0));
        applySystemModes(c);
        applyRefreshMode(c);
        applyImage(c);
        applyFrontlight(c);
    }

    // ---- export / import (roadmap 32: one JSON for the layer backup) -------------------------------------
    /** /sdcard/Android/data/org.b1051c.einkctl/files/einkctl-settings.json (no storage permission needed). */
    static java.io.File exportFile(Context c) {
        return new java.io.File(c.getExternalFilesDir(null), "einkctl-settings.json");
    }

    static String exportSettings(Context c) {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(exportFile(c))) {
            o.write(Profiles.exportAll(c).toString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "Exported to " + exportFile(c);
        } catch (Exception e) {
            return "Export failed: " + e;
        }
    }

    /** adb restores drop the JSON here (root can set the owner; Android/data files from adb are not the app's). */
    static java.io.File importDropFile(Context c) { return new java.io.File(c.getFilesDir(), "einkctl-import.json"); }

    static String importSettings(Context c) {
        java.io.File drop = importDropFile(c), src = drop.exists() ? drop : exportFile(c);
        try (java.io.FileInputStream in = new java.io.FileInputStream(src)) {
            Profiles.importAll(c, new org.json.JSONObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            if (src == drop) drop.delete();
            applyAll(c);
            ThemeSync.request(c, "import", false);   // the follow switches / wallpaper names may have changed
            return "Imported " + src;
        } catch (Exception e) {
            return "Import failed: " + e;
        }
    }

    // ---- wallpaper ----------------------------------------------------------------------------------
    /** Plain white on home and lock screen: the TrebleDroid default is almost all dark navy, which reads as a
     *  black screen on e-ink (owner, 2026-10-18). Not on the UI thread. */
    static void whiteWallpaper(Context c) {
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(Math.max(1, dm.widthPixels),
                Math.max(1, dm.heightPixels), android.graphics.Bitmap.Config.ARGB_8888);
        b.eraseColor(android.graphics.Color.WHITE);
        try {
            android.app.WallpaperManager.getInstance(c).setBitmap(b, null, true,
                    android.app.WallpaperManager.FLAG_SYSTEM | android.app.WallpaperManager.FLAG_LOCK);
            android.util.Log.i("einkctl", "white wallpaper set (home + lock)");
        } catch (java.io.IOException | SecurityException e) {
            android.util.Log.w("einkctl", "white wallpaper failed: " + e);
        } finally {
            b.recycle();
        }
    }

    /** Wallpaper from an image in the app's external files dir (adb: push it there, then ActionReceiver
     *  SET_WALLPAPER; ThemeSync on a Dark theme switch). which = home, lock or both (default). Used for panel-tuned
     *  versions of the owner's images. Not on the UI thread; true when the wallpaper was set. */
    static boolean setWallpaper(Context c, String name, String which) {
        java.io.File dir = c.getExternalFilesDir(null);
        java.io.File f = new java.io.File(dir, name == null ? "wallpaper.png" : name);
        int flags = "home".equals(which) ? android.app.WallpaperManager.FLAG_SYSTEM
                : "lock".equals(which) ? android.app.WallpaperManager.FLAG_LOCK
                : android.app.WallpaperManager.FLAG_SYSTEM | android.app.WallpaperManager.FLAG_LOCK;
        try {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + "/")) throw new java.io.IOException("outside " + dir);
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                android.app.WallpaperManager.getInstance(c).setStream(in, null, true, flags);
            }
            android.util.Log.i("einkctl", "wallpaper set from " + f + " (flags " + flags + ")");
            return true;
        } catch (java.io.IOException | SecurityException e) {
            android.util.Log.w("einkctl", "wallpaper failed: " + e);
            return false;
        }
    }

    // ---- dual system (DSU) ------------------------------------------------------------------------
    /** "gsi_tool status" output: "normal" (no DSU), "installed", "running" …; empty when unknown. */
    static String dsuStatus() { return Root.run("gsi_tool status 2>&1 | head -1"); }

    private static Boolean dsuCached;
    /** ro.* is fixed until reboot: read once per process (review 2026-10-21: a root shell on every shade pull). */
    static boolean runningDsu() {
        Boolean v = dsuCached;
        if (v == null) {
            String r = "";
            try {   // plain getprop, no root: ro.* is world-readable
                Process p = new ProcessBuilder("getprop", "ro.gsid.image_running").redirectErrorStream(true).start();
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line = br.readLine();
                    if (line != null) r = line.trim();
                }
                p.waitFor();
            } catch (Exception ignored) {}
            v = "1".equals(r);
            dsuCached = v;
        }
        return v;
    }

    /** Boot the other system once: from the base system enable the DSU for one boot; from the DSU just reboot. */
    static void bootOtherSystem() {
        Root.async(runningDsu() ? "svc power reboot || reboot"
                : "gsi_tool enable --single-boot && (svc power reboot || reboot)");
    }
}
