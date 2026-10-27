package org.b1051c.einkctl;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android's Dark theme (the QS "Dark theme" tile, ui_night_mode manual) is the one master switch of the InKognito look
 * (owner 2026-10-20). The app's screens, the tile dialogs and the shade (einkui) follow uiMode through resources
 * (values-night); this brings along what cannot:
 *  - the wallpaper, home + lock screen: wallpaper-D-cream.png <-> dark-D.png from the app's external files dir (prefs
 *    wallpaper_light / wallpaper_dark; switch "Wallpaper follows theme");
 *  - the AOSP keyboard: LatinIME has no -night resources and caches its prefs, so pref_keyboard_theme_20140509 goes
 *    3 (LXX Light) <-> 4 (LXX Dark), replaced atomically as root (a copy in the same directory with the file's owner,
 *    mode and SELinux label, then mv), and the IME process is restarted (switch "Keyboard follows theme"); a set
 *    that did not land (LatinIME busy writing, file missing) is retried at the next trigger (state_kb);
 *  - the flag /data/adb/b1051c/theme = light | dark, one line, written atomically (mv); the b1051c-bootanim module's
 *    post-fs-data plays the dark boot animation when it reads "dark" (missing = light). Also written on shutdown;
 *  - per-app inversion, off while dark (Profiles.resolve), re-applied here;
 *  - one settled GC16, once the shade has closed (the owner switches from the open shade).
 * Triggers: the keeper's onConfigurationChanged (night bit only, 1.5 s debounce, so quick toggles give one sync) and
 * its start (boot, app update, restart: after 3 s), the app (re-apply button, import) and adb (ActionReceiver
 * THEME_SYNC). The first run moves an automatic or scheduled Dark theme to manual at its current value, so nothing
 * flips by itself. Never changes the computed night mode, so no loop: a wallpaper set at most re-seeds Monet (same
 * night bit, dropped by onConfig). A switch back made while a sync runs is caught when it ends (run checks again).
 */
final class ThemeSync {
    private ThemeSync() {}

    private static final String T = "einkctl";
    /** Prefs (exported with the settings): the follow switches, default on, and the wallpaper file names. */
    static final String K_WALLPAPER = "theme_wallpaper", K_KEYBOARD = "theme_keyboard";
    static final String K_WP_LIGHT = "wallpaper_light", K_WP_DARK = "wallpaper_dark";
    static final String WP_LIGHT = "wallpaper-D-cream.png", WP_DARK = "dark-D.png";
    /** Prefix of this device's state (never exported, kept across an import): the theme applied last, the wallpaper
     *  we set and its id, the keyboard theme id we set. */
    static final String STATE = "state_";
    private static final String S_THEME = STATE + "theme", S_WP_NAME = STATE + "wp_name", S_WP_ID = STATE + "wp_id",
            S_KB = STATE + "kb";

    static final String FLAG_DIR = "/data/adb/b1051c", FLAG = FLAG_DIR + "/theme";
    static final String LATIN =
            "/data/user_de/0/com.android.inputmethod.latin/shared_prefs/com.android.inputmethod.latin_preferences.xml";
    static final String KB_KEY = "pref_keyboard_theme_20140509";
    static final int KB_LIGHT = 3, KB_DARK = 4;          // AOSP KeyboardTheme THEME_ID_LXX_LIGHT / THEME_ID_LXX_DARK

    private static final long DEBOUNCE_MS = 1500, START_MS = 3000, GC_DELAY_MS = 1500, SHADE_POLL_MS = 500;
    private static final int SHADE_POLLS = 40;           // 20 s, then the GC16 goes anyway (e.g. the lock screen)
    private static final int KB_TRIES = 3;               // "busy" (LatinIME's own commit in flight) is waited out
    private static final long KB_RETRY_MS = 300, KB_CHECK_MS = 1500;

    /** Main-thread handler, made on first use (class init stays free of framework calls). */
    private static final class H { static final Handler MAIN = new Handler(Looper.getMainLooper()); }
    /** One worker: syncs never overlap, and su calls / sleeps stay off the main thread. */
    private static final ExecutorService W = Executors.newSingleThreadExecutor();

    // main thread only: the pending sync and the GC16 wait
    private static Context app;
    private static Boolean pendingNight;
    private static String pendingWhy;
    private static boolean pendingForce;
    private static int gcPolls;

    static boolean night(Configuration c) {
        return (c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** The test einkui uses too (ShadeStyler): the night bit of the context's configuration. */
    static boolean night(Context c) { return night(c.getResources().getConfiguration()); }

    private static String name(boolean night) { return night ? "dark" : "light"; }

    // ---- triggers -----------------------------------------------------------------------------------
    static void onStart(Context c) { main(() -> schedule(c, "start", false, START_MS, night(c))); }

    /** Every global configuration change reaches the keeper: keep the ones that change the night bit. */
    static void onConfig(Context c, Configuration nc) {
        boolean n = night(nc);
        main(() -> {
            Boolean target = pendingNight;
            if (target == null) {
                String s = Eink.prefs(c).getString(S_THEME, null);
                if (s != null) target = "dark".equals(s);
            }
            if (target != null && target == n) return;   // Monet, font, density ... or back to the same theme
            schedule(c, "uimode", false, DEBOUNCE_MS, n);
        });
    }

    /** Sync now; force = also re-set the wallpaper and refresh when nothing changed (button, adb). */
    static void request(Context c, String why, boolean force) { main(() -> schedule(c, why, force, 0, night(c))); }

    /** A "follows theme" switch was turned on: bring that part in line with the current theme now. */
    static void followOn(Context c, boolean wallpaper) {
        Context a = c.getApplicationContext();
        W.execute(() -> guarded("switch", () -> {
            boolean n = night(a);
            if (!wallpaper) keyboard(Eink.prefs(a), n);
            else if (wallpaper(a, n, false)) gc16WhenShadeClosed(a);
        }));
    }

    /** adb: other wallpaper files for light / dark (names in the external files dir); null or empty = keep. */
    static void setFiles(Context c, String light, String dark) {
        SharedPreferences.Editor e = Eink.prefs(c).edit();
        if (light != null && !light.isEmpty()) e.putString(K_WP_LIGHT, light);
        if (dark != null && !dark.isEmpty()) e.putString(K_WP_DARK, dark);
        e.apply();
    }

    private static void main(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else H.MAIN.post(r);
    }

    /** The last trigger wins: its reason and theme; force sticks until the sync runs. */
    private static void schedule(Context c, String why, boolean force, long delayMs, boolean n) {
        app = c.getApplicationContext();
        H.MAIN.removeCallbacks(FIRE);
        pendingNight = n;
        pendingWhy = why;
        pendingForce |= force;
        H.MAIN.postDelayed(FIRE, delayMs);
    }

    private static final Runnable FIRE = () -> {
        Context c = app;
        String why = pendingWhy;
        boolean force = pendingForce;
        pendingNight = null;
        pendingWhy = null;
        pendingForce = false;
        W.execute(() -> guarded(why, () -> run(c, why, force)));
    };

    private static void guarded(String why, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {                   // a worker exception would take the whole app down
            Log.w(T, "theme sync (" + why + ") failed: " + e);
        }
    }

    // ---- the sync (worker) --------------------------------------------------------------------------
    private static void run(Context c, String why, boolean force) {
        SharedPreferences p = Eink.prefs(c);
        boolean first = !p.contains(S_THEME);
        if (first) leaveAuto(c);
        boolean n = night(c);
        String want = name(n), had = p.getString(S_THEME, null);
        if (!want.equals(had)) SleepRelay.send(c, SleepRelay.THEME);   // the sleep picture follows the theme too
        boolean applied = want.equals(had), flagOk = want.equals(flag());
        boolean wpOn = p.getBoolean(K_WALLPAPER, true), kbOn = p.getBoolean(K_KEYBOARD, true);
        boolean wpStale = wpOn && !wallpaperName(p, n).equals(p.getString(S_WP_NAME, null));   // e.g. new file name
        boolean kbStale = kbOn && !String.valueOf(kbId(n)).equals(p.getString(S_KB, null));   // e.g. LatinIME was busy
        if (!force && !first && applied && flagOk && !wpStale && !kbStale) {
            Log.i(T, "theme sync (" + why + "): up to date (" + want + ")");
            return;
        }
        Log.i(T, "theme sync (" + why + "): " + (first ? "first run, " : "") + (had == null ? "none" : had) + " -> "
                + want + (force ? " (forced)" : !first && applied ? " (catching up:" + (flagOk ? "" : " flag")
                + (wpStale ? " wallpaper" : "") + (kbStale ? " keyboard" : "") + ")" : ""));
        if (!flagOk || force) writeFlag(want);
        if (kbOn) keyboard(p, n);
        Profiles.apply(c, AppProfileService.currentPackage(), false);   // per-app inversion skipped / restored
        // a wallpaper set by hand stays until the next switch (or a forced re-apply); wallpaper() skips our own
        boolean wpSet = wpOn && (force || first || !applied || wpStale) && wallpaper(c, n, force);
        p.edit().putString(S_THEME, want).apply();
        // a switch back made during this run met the old state_theme in onConfig and was dropped: check again now
        // (one made after the write above is scheduled by onConfig itself; schedule() merges the two into one sync)
        boolean now = night(c);
        if (now != n) main(() -> schedule(c, "uimode", false, DEBOUNCE_MS, now));
        if (!applied || first || force || wpSet) gc16WhenShadeClosed(c);
    }

    /** First run only: an automatic (sunset) or scheduled Dark theme becomes manual at its current value. */
    private static void leaveAuto(Context c) {
        UiModeManager um = c.getSystemService(UiModeManager.class);
        int m = um == null ? -1 : um.getNightMode();
        if (m != UiModeManager.MODE_NIGHT_AUTO && m != UiModeManager.MODE_NIGHT_CUSTOM) return;
        boolean n = night(c);
        String r = Root.run("cmd uimode night " + (n ? "yes" : "no"));
        Log.i(T, "theme sync: Dark theme was " + (m == UiModeManager.MODE_NIGHT_AUTO ? "automatic" : "on a schedule")
                + ", now manual " + (n ? "on" : "off") + " (" + r + ")");
    }

    // ---- boot animation flag ------------------------------------------------------------------------
    private static String flag() { return Root.run("cat " + FLAG + " 2>/dev/null"); }

    /** Atomic: a reader (post-fs-data) sees the old or the new line, never a partial file. */
    static String flagCmd(String v) {
        String tmp = FLAG + ".new.$$";
        return "mkdir -p " + FLAG_DIR + " && chmod 0755 " + FLAG_DIR + " && echo " + v + " > " + tmp + " && chmod 0644 "
                + tmp + " && mv -f " + tmp + " " + FLAG;
    }

    private static void writeFlag(String v) {
        String r = Root.run(flagCmd(v));
        if (r.isEmpty()) Log.i(T, "theme flag " + FLAG + " = " + v);
        else Log.w(T, "theme flag " + v + " failed: " + r);
    }

    static String flagIfStaleCmd(String v) {
        return "[ \"$(cat " + FLAG + " 2>/dev/null)\" = " + v + " ] || { " + flagCmd(v) + " && echo written; }";
    }

    /** Shutdown path: a switch made within the debounce still boots with the right animation. Returns the started
     *  writer thread (the caller may wait for it). */
    static Thread writeFlagIfStale(Context c, boolean dark) {
        String v = name(dark);
        Thread t = new Thread(() -> {
            if (Root.run(flagIfStaleCmd(v)).endsWith("written")) Log.i(T, "theme flag " + v + " written on shutdown");
        }, "theme-flag");
        t.start();
        return t;
    }

    // ---- keyboard -----------------------------------------------------------------------------------
    /**
     * Root shell: set LatinIME's theme id, only when no SharedPreferences write is in flight (.bak), never writing a
     * result without the value or the closing tag. The new text goes to a copy in the same directory that gets the
     * file's owner u0_aNN, mode and SELinux label (with the MLS categories), is synced, then replaces the file with mv,
     * so a reader sees the old or the new file, never a partial one. A LatinIME commit that began meanwhile (.bak, or
     * a new inode) leaves the file alone ("busy"). Then kill the IME so the bound service restarts and reads it
     * (force-stop would reset the selected IME). Last line: changed | same | missing | busy | failed.
     */
    static String keyboardCmd(int v) {
        return "F=" + LATIN + "; K=" + KB_KEY + "; V=" + v + "; N=\"$F.einkctl\"; "
                + "[ -f \"$F\" ] || { echo missing; exit 0; }; "
                + "[ ! -f \"$F.bak\" ] || { echo busy; exit 0; }; "
                + "grep -q \"name=\\\"$K\\\">$V<\" \"$F\" && { echo same; exit 0; }; "
                + "set -- $(stat -c '%i %u:%g %a %C' \"$F\"); [ $# = 4 ] || { echo failed; exit 0; }; "
                + "if grep -q \"name=\\\"$K\\\"\" \"$F\"; then T=$(sed \"s#name=\\\"$K\\\">[0-9]*<#name=\\\"$K\\\">$V<#\" \"$F\"); "
                + "else T=$(sed \"s#</map>#<string name=\\\"$K\\\">$V</string></map>#\" \"$F\"); fi; "
                + "case \"$T\" in *\"name=\\\"$K\\\">$V<\"*\"</map>\"*) ;; *) echo failed; exit 0;; esac; "
                + "{ printf '%s\\n' \"$T\" > \"$N\" && chown $2 \"$N\" && chmod $3 \"$N\" && chcon $4 \"$N\" && sync; } "
                + "|| { rm -f \"$N\"; echo failed; exit 0; }; "
                + "[ ! -f \"$F.bak\" ] && [ \"$(stat -c %i \"$F\" 2>/dev/null)\" = \"$1\" ] || { rm -f \"$N\"; echo busy; exit 0; }; "
                + "mv -f \"$N\" \"$F\" || { rm -f \"$N\"; echo failed; exit 0; }; "
                + "P=$(pidof com.android.inputmethod.latin) && kill $P; echo changed";
    }

    private static String lastLine(String out) {
        int i = out.lastIndexOf('\n');
        return (i < 0 ? out : out.substring(i + 1)).trim();
    }

    private static int kbId(boolean night) { return night ? KB_DARK : KB_LIGHT; }

    /** keyboardCmd, a "busy" retried KB_TRIES times KB_RETRY_MS apart. */
    private static String keyboardRun(int v) {
        String r = "";
        for (int i = 0; i < KB_TRIES; i++) {
            if (i > 0) android.os.SystemClock.sleep(KB_RETRY_MS);
            r = lastLine(Root.runGlobal(keyboardCmd(v)));
            if (!"busy".equals(r)) break;
        }
        return r;
    }

    /** Worker. Stores state_kb once the value is in place; otherwise run() finds the keyboard stale and tries again. */
    private static void keyboard(SharedPreferences p, boolean night) {
        int v = kbId(night);
        String r = keyboardRun(v);
        if ("changed".equals(r)) {
            Log.i(T, "keyboard theme " + v + (night ? " (LXX Dark)" : " (LXX Light)") + ", keyboard restarted");
            android.os.SystemClock.sleep(KB_CHECK_MS);
            // the live process may have saved its old value just before the kill: check once more
            r = keyboardRun(v);
            if ("changed".equals(r)) Log.w(T, "keyboard theme " + v + " set a second time");
        }
        if ("changed".equals(r) || "same".equals(r)) p.edit().putString(S_KB, String.valueOf(v)).apply();
        else Log.w(T, "keyboard theme " + v + " not set: " + r + " (tried again at the next trigger)");
    }

    // ---- wallpaper ----------------------------------------------------------------------------------
    private static String wallpaperName(SharedPreferences p, boolean night) {
        return night ? p.getString(K_WP_DARK, WP_DARK) : p.getString(K_WP_LIGHT, WP_LIGHT);
    }

    /** Set the theme's wallpaper on home + lock unless it is the one we set last and still in place; true when set.
     *  A missing file leaves the wallpaper as it is (never a white fallback). */
    private static boolean wallpaper(Context c, boolean night, boolean force) {
        SharedPreferences p = Eink.prefs(c);
        String name = wallpaperName(p, night);
        int id = wallpaperId(c);
        if (!force && name.equals(p.getString(S_WP_NAME, null)) && id >= 0 && id == p.getInt(S_WP_ID, -1)) return false;
        java.io.File dir = c.getExternalFilesDir(null);
        if (dir == null || !new java.io.File(dir, name).isFile()) {
            Log.w(T, "theme sync: wallpaper " + name + " not found in " + dir + "; wallpaper left as it is");
            return false;
        }
        // the system colors should be pinned first (plan step 5, G2); this app never writes the pin. SystemUI seeds
        // from the wallpaper unless color_source is "preset" (also with the setting empty or without color_source)
        String m = monetSource();
        if (!"preset".equals(m)) Log.w(T, "theme sync: Monet source " + m + " (not pinned); this set may re-seed the system colors");
        if (!Eink.setWallpaper(c, name, "both")) return false;
        p.edit().putString(S_WP_NAME, name).putInt(S_WP_ID, wallpaperId(c)).apply();
        return true;
    }

    private static int wallpaperId(Context c) {
        try {
            return android.app.WallpaperManager.getInstance(c).getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Monet's color_source (home_wallpaper, lock_wallpaper, preset), "?" if unknown. */
    private static String monetSource() {
        String s = Root.run("settings get secure theme_customization_overlay_packages");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("color_source\"\\s*:\\s*\"([^\"]*)\"").matcher(s);
        return m.find() ? m.group(1) : "?";
    }

    // ---- refresh ------------------------------------------------------------------------------------
    /** GC16 after the shade has closed (a GC16 under the open shade would leave the home revealed behind it
     *  unrefreshed); the shim merges it with any other request still pending. */
    private static final Runnable GC = new Runnable() {
        @Override public void run() {
            if (gcPolls-- > 0 && AppProfileService.shadeOpen()) { H.MAIN.postDelayed(this, SHADE_POLL_MS); return; }
            Log.i(T, "theme sync: full refresh" + (gcPolls < 0 ? " (shade still open after 20 s)" : ""));
            Eink.clearScreen(app);
            AppProfileService.retintNav();          // the new wallpaper is drawn by now: the nav glyphs' final colour
        }
    };

    private static void gc16WhenShadeClosed(Context c) {
        Context a = c.getApplicationContext();
        main(() -> {
            app = a;
            H.MAIN.removeCallbacks(GC);
            gcPolls = SHADE_POLLS;
            H.MAIN.postDelayed(GC, GC_DELAY_MS);
        });
    }

    // ---- status (adb THEME_SYNC --ez dry true) ------------------------------------------------------
    /** One line: theme, Android's setting, what was applied, flag, keyboard, wallpaper, files, Monet. Worker / thread. */
    static String status(Context c) {
        SharedPreferences p = Eink.prefs(c);
        UiModeManager um = c.getSystemService(UiModeManager.class);
        int m = um == null ? -1 : um.getNightMode();
        String mode = m == UiModeManager.MODE_NIGHT_NO ? "manual, off" : m == UiModeManager.MODE_NIGHT_YES ? "manual, on"
                : m == UiModeManager.MODE_NIGHT_AUTO ? "automatic" : m == UiModeManager.MODE_NIGHT_CUSTOM ? "schedule" : "?";
        String kb = Root.runGlobal("grep -o 'name=\"" + KB_KEY + "\">[0-9]*' " + LATIN + " 2>/dev/null");
        kb = kb.isEmpty() ? "?" : kb.substring(kb.lastIndexOf('>') + 1);
        String fl = flag();
        java.io.File dir = c.getExternalFilesDir(null);
        String light = wallpaperName(p, false), dark = wallpaperName(p, true);
        return "theme " + name(night(c)) + " (Dark theme " + mode + ")"
                + " | applied " + p.getString(S_THEME, "never")
                + " | flag " + (fl.isEmpty() ? "none" : fl)
                + " | keyboard " + kb + ", set by us " + p.getString(S_KB, "-")
                + (p.getBoolean(K_KEYBOARD, true) ? " (follows)" : " (switch off)")
                + " | wallpaper id " + wallpaperId(c) + ", set by us " + p.getString(S_WP_NAME, "-") + " id "
                + p.getInt(S_WP_ID, -1) + (p.getBoolean(K_WALLPAPER, true) ? " (follows)" : " (switch off)")
                + " | files " + light + present(dir, light) + " / " + dark + present(dir, dark)
                + " | Monet " + monetSource()
                + " | shade " + (AppProfileService.shadeOpen() ? "open" : "closed")
                + " | shutdown frames " + (ShutdownScreen.hasDarkSet(c) ? "dark + cream" : "cream only");
    }

    private static String present(java.io.File dir, String name) {
        return dir != null && new java.io.File(dir, name).isFile() ? "" : " (missing)";
    }
}
