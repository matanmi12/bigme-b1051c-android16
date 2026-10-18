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
        if (nightNow(p)) return new int[] {p.getInt("night_cold", 0), p.getInt("night_warm", 30)};
        return new int[] {p.getInt("cold", 40), p.getInt("warm", 40)};
    }

    /** True inside the night window [start, end) when the schedule is on; the window may wrap past midnight. */
    static boolean nightNow(SharedPreferences p) {
        if (!p.getBoolean("night_schedule", false)) return false;
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        int s = p.getInt("night_start", 21), e = p.getInt("night_end", 7);
        return s <= e ? (h >= s && h < e) : (h >= s || h < e);
    }

    static void applyFrontlight(Context c) {
        int[] v = frontlightValues(c);
        Root.async(frontlightCmd(v[0], v[1]));
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

    /** A QS slider moved: adopt the values as the light (day or night slot) and apply them. */
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
        int[] cur = frontlightValues(c);
        if (cur[0] == cold && cur[1] == warm) return;   // echo of our own publish
        SharedPreferences p = prefs(c);
        SharedPreferences.Editor e = p.edit().putBoolean("light_on", cold + warm > 0);
        if (cold + warm > 0) {
            e.putBoolean("link_brightness", false);
            boolean night = nightNow(p);
            e.putInt(night ? "night_cold" : "cold", clamp(cold, 0, FL_MAX)).putInt(night ? "night_warm" : "warm", clamp(warm, 0, FL_MAX));
        }
        e.apply();
        Root.async(frontlightCmd(cold, warm));
    }

    static void frontlightOff() { Root.async(frontlightCmd(0, 0)); }

    // ---- refresh mode -----------------------------------------------------------------------
    // Global override read live by the vendor HWC: vendor.xrz.global_refresh_mode (0 = no override, i.e. the
    // HWC default 178 / per-layer). force_global_refresh_mode is the HWC's one-shot full-refresh knob — reset
    // it to -1 so a value left by app v0.5 doesn't pin a mode.
    static final int UNSET = Integer.MIN_VALUE;

    /** Stock Eink Center modes (HD 256 = 177 | DITHER_256, Fast 178, Extreme 179, Regal 180, Hyper 183) + A2. */
    static final int[] MODES = {0, -2147483471, 178, 179, 180, 183, 16};
    static final String[] MODE_NAMES = {"Auto (app default)", "HD 256", "Fast", "Extreme", "Regal", "Hyper",
            "A2 (fastest, black/white)"};

    static int normMode(int m) { return m == -1 ? 0 : m; }   // app <= 0.5 stored -1 for Auto

    /** Mode for a package: its own profile if set, else the global mode. */
    static int modeFor(Context c, String pkg) {
        SharedPreferences p = prefs(c);
        int m = pkg == null ? UNSET : p.getInt("app:" + pkg, UNSET);
        return normMode(m != UNSET ? m : p.getInt("mode", 0));
    }

    static String modeCmd(int mode) { return "setprop vendor.xrz.global_refresh_mode " + normMode(mode); }

    static void applyRefreshMode(Context c) {
        Root.async(modeCmd(modeFor(c, AppProfileService.currentPackage())));
    }

    static int modeIndex(int mode) {
        mode = normMode(mode);
        for (int i = 0; i < MODES.length; i++) if (MODES[i] == mode) return i;
        return 0;
    }

    /** One full-screen GC16 refresh: the shim rewrites the next commits to mode 4, then lets go. */
    static void clearScreen(Context c) {
        Root.async("setprop vendor.debug.hwc.shim.commit_mode 4; service call SurfaceFlinger 1008 i32 0 >/dev/null; "
                + "sleep 0.25; service call SurfaceFlinger 1008 i32 1 >/dev/null; sleep 1.2; "
                + "setprop vendor.debug.hwc.shim.commit_mode -1");
    }

    // ---- colour profiles (stock Eink Center presets; rendered by the HWC shim's port of Bigme's shader) --
    static final String[] PROFILE_NAMES = {"Default", "Magazine", "Comic", "Video", "Custom"};
    /** {refresh mode or UNSET (keep), vivid, contrast, colour brightness, colour mode, auto clean, anti-shake}
     *  from the stock system build.prop sys.maga_* / sys.comic_* / sys.video_* (vivid is 0 in Extreme on stock). */
    static final int[][] PRESETS = {
            {UNSET, 0, 0, 0, 0, -1, -1},
            {-2147483471, 70, 0, 0, 1, 0, 0},
            {180, 90, 90, 0, 2, 0, 0},
            {179, 0, 0, 0, 3, 1, 1},
            {UNSET, -1, -1, -1, 4, -1, -1},
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
    static void applyImage(Context c) {
        SharedPreferences p = prefs(c);
        int prof = p.getInt("profile", 0);
        int[] v = PRESETS[clamp(prof, 0, PRESETS.length - 1)];
        int vivid = v[1] >= 0 ? v[1] : p.getInt("vivid", 0);
        int con = v[2] >= 0 ? v[2] : p.getInt("contrast", 0);
        int bri = v[3] >= 0 ? v[3] : p.getInt("cbright", 0);
        boolean af = p.getBoolean("anti_flicker", false), ac = p.getBoolean("auto_clean", false);
        StringBuilder sb = new StringBuilder();
        // colour pipeline (HWC shim) — same props as Bigme's framework
        sb.append("setprop vendor.xrz.global_color_enhance ").append(clamp(vivid, 0, 100)).append("; ");
        sb.append("setprop vendor.xrz.global_contrast ").append(clamp(con, 0, 100)).append("; ");
        sb.append("setprop vendor.xrz.global_brightness_level ").append(clamp(bri, 0, 100)).append("; ");
        sb.append("setprop vendor.xrz.global_color_mode ").append(v[4]).append("; ");
        // anti-shake: the HWC writes eink_debug/anti_flicker itself before each commit when this is 1
        sb.append("setprop vendor.xrz.anti_flicker_enabled ").append(af ? 1 : 0).append("; ");
        // auto clean: prop + kernel clean_a2 (stock setAutoCleanEnable)
        sb.append("setprop vendor.xrz.auto_clean_enabled ").append(ac ? 1 : 0).append("; ");
        sb.append("echo ").append(ac ? 1 : 0).append(" > ").append(ED).append("clean_a2; ");
        sb.append("setprop vendor.xrz.clean_frequency ").append(clamp(p.getInt("clean_every", 0), 0, 50)).append("; ");
        // undo the kernel-node experiments of app 0.3-0.5 (not used by stock; no proper effect)
        sb.append("for n in saturation contrast brightness; do echo 0 > ").append(ED).append("$n; done; ");
        Root.async(sb.toString());
    }

    static void applyAll(Context c) {
        Root.async("setprop vendor.xrz.force_global_refresh_mode -1");
        applyRefreshMode(c);
        applyImage(c);
        applyFrontlight(c);
    }

    // ---- dual system (DSU) ------------------------------------------------------------------------
    /** "gsi_tool status" output: "normal" (no DSU), "installed", "running" …; empty when unknown. */
    static String dsuStatus() { return Root.run("gsi_tool status 2>&1 | head -1"); }

    static boolean runningDsu() { return "1".equals(Root.run("getprop ro.gsid.image_running")); }

    /** Boot the other system once: from the base system enable the DSU for one boot; from the DSU just reboot. */
    static void bootOtherSystem() {
        Root.async(runningDsu() ? "svc power reboot || reboot"
                : "gsi_tool enable --single-boot && (svc power reboot || reboot)");
    }
}
