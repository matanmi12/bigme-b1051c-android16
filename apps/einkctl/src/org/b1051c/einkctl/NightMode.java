package org.b1051c.einkctl;

import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android's Night Light ("reduce blue light") is our night mode (owner 2026-10-27): its QS tile and Settings > Display >
 * Night Light (switch, schedule, intensity) stay Android's, the framework overlay makes its colour matrix the identity
 * (no amber tint on the panel), and this turns the switch into
 *  - the front light at the Night preset, intensity moving it towards Bed (raw values interpolated: the raw scale is
 *    exponential, so this is an even step in brightness); the light's on/off is kept, so a schedule never lights a
 *    dark room, and the keeper applies it at SCREEN_ON as always;
 *  - Dark theme on (ThemeSync brings the wallpaper, keyboard and boot animation along).
 * Off: the light (preset, levels, brightness follow) is back to what it was before, Dark theme off again if this
 * switched it on and it is still on. Changes made meanwhile (a slider, a preset, the Dark theme tile) are not kept.
 * Triggers: the keeper's observers on night_display_activated / night_display_color_temperature and its start (catches
 * a switch made while it was not running; an active mode is never re-applied there, so slider tweaks survive).
 * State: STATE-prefixed prefs, never exported.
 */
final class NightMode {
    private NightMode() {}

    private static final String T = "einkctl";
    static final String ACTIVATED = "night_display_activated", TEMPERATURE = "night_display_color_temperature";
    private static final String S = ThemeSync.STATE + "night_", S_ON = S + "on", S_PRESET = S + "preset",
            S_COLD = S + "cold", S_WARM = S + "warm", S_LINK = S + "link", S_DARK = S + "dark";
    private static final int NIGHT = 1, BED = 2;                     // Eink.LIGHT_PRESETS
    /** AOSP values, used when the framework does not expose them. */
    private static final int T_MIN = 2596, T_MAX = 4082;

    /** One worker: settings reads may need su, and switches never overlap. */
    private static final ExecutorService W = Executors.newSingleThreadExecutor();

    /** why: "start" (reconcile only), "switch" or "intensity" (an observer fired). */
    static void sync(Context c, String why) {
        Context a = c.getApplicationContext();
        W.execute(() -> {
            try { run(a, why); }
            catch (RuntimeException e) { Log.w(T, "night mode (" + why + "): " + e); }
        });
    }

    private static void run(Context c, String why) {
        SharedPreferences p = Eink.prefs(c);
        boolean want = secure(c, ACTIVATED, 0) == 1, on = p.getBoolean(S_ON, false);
        if (want && !on) enter(c, p);
        else if (!want && on) leave(c, p);
        else if (want && "intensity".equals(why)) light(c, p, "intensity");
    }

    private static void enter(Context c, SharedPreferences p) {
        boolean dark = darkOn(c);
        p.edit().putBoolean(S_ON, true).putInt(S_PRESET, Eink.lightPreset(c)).putInt(S_COLD, p.getInt("cold", 40))
                .putInt(S_WARM, p.getInt("warm", 40)).putBoolean(S_LINK, p.getBoolean("link_brightness", false))
                .putBoolean(S_DARK, !dark).apply();
        light(c, p, "on");
        if (!dark) Log.i(T, "night mode: Dark theme on: " + Root.run("cmd uimode night yes").trim());
    }

    private static void leave(Context c, SharedPreferences p) {
        int preset = p.getInt(S_PRESET, Eink.LIGHT_CUSTOM);
        p.edit().putInt("light_preset", preset).putInt("cold", p.getInt(S_COLD, 40)).putInt("warm", p.getInt(S_WARM, 40))
                .putBoolean("link_brightness", p.getBoolean(S_LINK, false)).apply();
        Eink.applyFrontlight(c);
        boolean undoDark = p.getBoolean(S_DARK, false) && darkOn(c);
        p.edit().remove(S_ON).remove(S_PRESET).remove(S_COLD).remove(S_WARM).remove(S_LINK).remove(S_DARK).apply();
        Log.i(T, "night mode off: light back to preset " + preset + (undoDark ? ", Dark theme off" : ""));
        if (undoDark) Root.run("cmd uimode night no");
    }

    /** The light for the current intensity: 0 = the Night preset, 1 = Bed. */
    private static void light(Context c, SharedPreferences p, String why) {
        float t = intensity(c);
        int[] n = Eink.LIGHT_PRESETS[NIGHT], b = Eink.LIGHT_PRESETS[BED];
        int cold = Math.round(n[0] + (b[0] - n[0]) * t), warm = Math.round(n[1] + (b[1] - n[1]) * t);
        p.edit().putInt("light_preset", t == 0f ? NIGHT : Eink.LIGHT_CUSTOM).putBoolean("link_brightness", false)
                .putInt("cold", cold).putInt("warm", warm).apply();
        Eink.applyFrontlight(c);
        Log.i(T, "night mode " + why + ": intensity " + Math.round(t * 100) + " % -> cold " + cold + " / warm " + warm);
    }

    /** Settings' intensity slider runs from the highest temperature (least intense) to the lowest. */
    static float intensity(Context c) {
        int min = sysInt("config_nightDisplayColorTemperatureMin", T_MIN);
        int max = sysInt("config_nightDisplayColorTemperatureMax", T_MAX);
        int temp = secure(c, TEMPERATURE, sysInt("config_nightDisplayColorTemperatureDefault", max));
        if (max <= min) return 0f;
        return Math.max(0f, Math.min(1f, (max - temp) / (float) (max - min)));
    }

    private static boolean darkOn(Context c) {
        return c.getSystemService(UiModeManager.class).getNightMode() == UiModeManager.MODE_NIGHT_YES;
    }

    private static int sysInt(String name, int def) {
        Resources r = Resources.getSystem();
        int id = r.getIdentifier(name, "integer", "android");
        return id == 0 ? def : r.getInteger(id);
    }

    /** Hidden Secure keys may be unreadable for an app targeting 31+: then as root. Not on the main thread. */
    private static int secure(Context c, String key, int def) {
        try {
            return android.provider.Settings.Secure.getInt(c.getContentResolver(), key, def);
        } catch (SecurityException e) {
            try { return Integer.parseInt(Root.run("settings get secure " + key).trim()); }
            catch (NumberFormatException n) { return def; }
        }
    }
}
