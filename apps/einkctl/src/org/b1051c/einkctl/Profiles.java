package org.b1051c.einkctl;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Per-app profile engine (roadmap wave 2, owner decisions 1.1 B, 1.2 C, 1.3 B, 2.3 B, 5.1 A, 7.x, 8.x, 9.x, 12.2 B).
 * Layers: global (the existing settings) <- "system" profile for home apps <- the app's own profile; a field an
 * app does not set is inherited. Stored as JSON per key in prefs: "profile:<package>" / "profile:@system".
 * Fields: refresh (mode int), preset (colour preset name; suggests refresh/anti-shake when those are unset),
 * grey / invert / hc (Android settings, tri-state), n (full refresh every N), shake (anti-shake).
 * The Android settings are only touched while an app overrides them: the live value is saved first and restored
 * when an app without an override comes up, so the system tiles keep working.
 */
final class Profiles {
    private Profiles() {}

    static final String SYSTEM = "@system";
    static final String F_REFRESH = "refresh", F_PRESET = "preset", F_GREY = "grey", F_INVERT = "invert", F_HC = "hc",
            F_N = "n", F_SHAKE = "shake";
    static final String GREY_ON = "accessibility_display_daltonizer_enabled", GREY_MODE = "accessibility_display_daltonizer";
    static final String INVERT = "accessibility_display_inversion_enabled", HC = "high_text_contrast_enabled";
    /** Built-in colour presets = Eink.PROFILE_NAMES; "Custom" = the global sliders. */
    static final String CUSTOM = "Custom";

    // ---- storage ------------------------------------------------------------------------------------
    static JSONObject get(Context c, String key) {
        try {
            String s = Eink.prefs(c).getString("profile:" + key, null);
            return s == null ? new JSONObject() : new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void put(Context c, String key, JSONObject o) {
        SharedPreferences.Editor e = Eink.prefs(c).edit();
        if (o.length() == 0) e.remove("profile:" + key); else e.putString("profile:" + key, o.toString());
        e.apply();
    }

    /** User colour presets: [{name, vivid, contrast, cbright, mode, refresh?}] */
    static JSONArray userPresets(Context c) {
        try { return new JSONArray(Eink.prefs(c).getString("presets", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static void saveUserPreset(Context c, String name, int vivid, int contrast, int cbright, int refresh) {
        JSONArray a = userPresets(c), out = new JSONArray();
        try {
            for (int i = 0; i < a.length(); i++) if (!a.getJSONObject(i).getString("name").equals(name)) out.put(a.get(i));
            out.put(new JSONObject().put("name", name).put("vivid", vivid).put("contrast", contrast).put("cbright", cbright)
                    .put("mode", 4).put("refresh", refresh));
        } catch (Exception ignored) {}
        Eink.prefs(c).edit().putString("presets", out.toString()).apply();
    }

    static String[] presetNames(Context c) {
        JSONArray a = userPresets(c);
        String[] out = new String[Eink.PROFILE_NAMES.length + a.length()];
        System.arraycopy(Eink.PROFILE_NAMES, 0, out, 0, Eink.PROFILE_NAMES.length);
        for (int i = 0; i < a.length(); i++) out[Eink.PROFILE_NAMES.length + i] = a.optJSONObject(i).optString("name");
        return out;
    }

    /** First run of the engine: our own seeds only (decision 1.2 C: no Bigme table import). */
    static void seed(Context c) {
        SharedPreferences p = Eink.prefs(c);
        if (!p.getBoolean("migrated_app_v1", false)) {        // app <= 0.9.5 stored per-app refresh as "app:<pkg>"
            SharedPreferences.Editor ed = p.edit();
            for (java.util.Map.Entry<String, ?> en : p.getAll().entrySet()) {
                if (!en.getKey().startsWith("app:") || !(en.getValue() instanceof Integer)) continue;
                String pkg = en.getKey().substring(4);
                try {
                    JSONObject o = get(c, pkg);
                    if (!o.has(F_REFRESH)) { o.put(F_REFRESH, (Integer) en.getValue()); ed.putString("profile:" + pkg, o.toString()); }
                } catch (Exception ignored) {}
                ed.remove(en.getKey());
            }
            ed.putBoolean("migrated_app_v1", true).apply();
        }
        if (p.getBoolean("seeded_v1", false)) return;
        try {
            if (get(c, "com.amazon.kindle").length() == 0) put(c, "com.amazon.kindle", new JSONObject().put(F_REFRESH, 180));
        } catch (Exception ignored) {}
        p.edit().putBoolean("seeded_v1", true).apply();
    }

    // ---- resolution ---------------------------------------------------------------------------------
    static final class Eff {
        int refresh, vivid, contrast, cbright, colorMode, n;
        boolean shake;
        Boolean grey, invert, hc;   // null = leave Android's setting alone
    }

    private static Set<String> homeCache;
    private static long homeAt;

    static synchronized Set<String> homePackages(Context c) {
        if (homeCache == null || System.currentTimeMillis() - homeAt > 60_000) {
            Set<String> s = new HashSet<>();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo r : c.getPackageManager().queryIntentActivities(i, 0)) s.add(r.activityInfo.packageName);
            homeCache = s;
            homeAt = System.currentTimeMillis();
        }
        return homeCache;
    }

    static Eff resolve(Context c, String pkg) {
        SharedPreferences p = Eink.prefs(c);
        Eff e = new Eff();
        e.refresh = Eink.normMode(p.getInt("mode", 0));
        presetInto(c, Eink.PROFILE_NAMES[Eink.clamp(p.getInt("profile", 0), 0, Eink.PROFILE_NAMES.length - 1)], e, null);
        e.shake = p.getBoolean("anti_flicker", false);
        e.n = p.getInt("clean_every", 0);
        if (pkg != null && homePackages(c).contains(pkg)) layer(c, get(c, SYSTEM), e);
        if (pkg != null && !pkg.isEmpty()) layer(c, get(c, pkg), e);
        int nDark = p.getInt("n_dark", 0);
        boolean dark = e.invert != null ? e.invert : Eink.secureInt(c, INVERT, 0) == 1;
        if (dark && nDark > 0) e.n = nDark;
        return e;
    }

    private static void layer(Context c, JSONObject o, Eff e) {
        if (o.length() == 0) return;
        if (o.has(F_PRESET)) presetInto(c, o.optString(F_PRESET), e, o);
        if (o.has(F_REFRESH)) e.refresh = Eink.normMode(o.optInt(F_REFRESH));
        if (o.has(F_N)) e.n = o.optInt(F_N);
        if (o.has(F_SHAKE)) e.shake = o.optBoolean(F_SHAKE);
        if (o.has(F_GREY)) e.grey = o.optBoolean(F_GREY);
        if (o.has(F_INVERT)) e.invert = o.optBoolean(F_INVERT);
        if (o.has(F_HC)) e.hc = o.optBoolean(F_HC);
    }

    /** Colour values of a preset; its suggested refresh / anti-shake apply unless the profile sets them (9.1 C). */
    private static void presetInto(Context c, String name, Eff e, JSONObject owner) {
        SharedPreferences p = Eink.prefs(c);
        int idx = -1;
        for (int i = 0; i < Eink.PROFILE_NAMES.length; i++) if (Eink.PROFILE_NAMES[i].equals(name)) idx = i;
        if (idx >= 0) {
            int[] v = Eink.PRESETS[idx];
            e.vivid = v[1] >= 0 ? v[1] : p.getInt("vivid", 0);
            e.contrast = v[2] >= 0 ? v[2] : p.getInt("contrast", 0);
            e.cbright = v[3] >= 0 ? v[3] : p.getInt("cbright", 0);
            e.colorMode = v[4];
            if (owner != null && v[0] != Eink.UNSET && !owner.has(F_REFRESH)) e.refresh = Eink.normMode(v[0]);
            if (owner != null && v[6] >= 0 && !owner.has(F_SHAKE)) e.shake = v[6] == 1;
            return;
        }
        JSONArray a = userPresets(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject u = a.optJSONObject(i);
            if (u == null || !name.equals(u.optString("name"))) continue;
            e.vivid = u.optInt("vivid"); e.contrast = u.optInt("contrast"); e.cbright = u.optInt("cbright");
            e.colorMode = u.optInt("mode", 4);
            if (owner != null && u.has("refresh") && u.optInt("refresh") != 0 && !owner.has(F_REFRESH)) e.refresh = u.optInt("refresh");
        }
    }

    // ---- applying -----------------------------------------------------------------------------------
    private static Eff last;
    private static volatile boolean imeOpen;

    static void setIme(Context c, boolean open) {
        if (open == imeOpen) return;
        imeOpen = open;
        apply(c, AppProfileService.currentPackage(), false);
    }

    /** Fast is the mode for typing (2.3 B); Extreme and A2 are faster already. */
    private static boolean slow(int mode) { return mode != 178 && mode != 179 && mode != 16; }

    static synchronized void apply(Context c, String pkg, boolean force) {
        Eff e = resolve(c, pkg);
        if (imeOpen && Eink.prefs(c).getBoolean("ime_fast", true) && slow(e.refresh)) e.refresh = 178;
        StringBuilder sb = new StringBuilder();
        if (force || last == null || e.refresh != last.refresh) sb.append(Eink.modeCmd(c, e.refresh)).append("; ");
        if (force || last == null || e.vivid != last.vivid || e.contrast != last.contrast || e.cbright != last.cbright
                || e.colorMode != last.colorMode) {
            sb.append("setprop vendor.xrz.global_color_enhance ").append(Eink.clamp(e.vivid, 0, 100)).append("; ");
            sb.append("setprop vendor.xrz.global_contrast ").append(Eink.clamp(e.contrast, 0, 100)).append("; ");
            sb.append("setprop vendor.xrz.global_brightness_level ").append(Eink.clamp(e.cbright, 0, 100)).append("; ");
            sb.append("setprop vendor.xrz.global_color_mode ").append(e.colorMode).append("; ");
        }
        if (force || last == null || e.shake != last.shake)
            sb.append("setprop vendor.xrz.anti_flicker_enabled ").append(e.shake ? 1 : 0).append("; ");
        if (force || last == null || e.n != last.n)
            sb.append("setprop vendor.xrz.clean_frequency ").append(Eink.clamp(e.n, 0, 50)).append("; ");
        if (sb.length() > 0) Root.async(sb.toString());
        android.util.Log.d("einkctl", "profile " + pkg + (imeOpen ? " +keyboard" : "") + " -> refresh " + e.refresh
                + (force ? " (forced)" : ""));
        tri(c, "base_grey", e.grey, GREY_ON);
        boolean darkChanged = tri(c, "base_invert", e.invert, INVERT);
        tri(c, "base_hc", e.hc, HC);
        if (darkChanged && Eink.prefs(c).getBoolean("clean_on_dark", true)) Eink.clearScreen(c);
        last = e;
    }

    /** Tri-state Android setting: override (saving the live value once) or restore the saved value. */
    private static boolean tri(Context c, String baseKey, Boolean want, String key) {
        SharedPreferences p = Eink.prefs(c);
        int live = Eink.secureInt(c, key, 0);
        if (want == null) {
            if (!p.contains(baseKey)) return false;
            int base = p.getInt(baseKey, 0);
            p.edit().remove(baseKey).apply();
            if (base == live) return false;
            writeSecure(c, key, base);
            return true;
        }
        if (!p.contains(baseKey)) p.edit().putInt(baseKey, live).apply();
        int w = want ? 1 : 0;
        if (w == live) return false;
        writeSecure(c, key, w);
        return true;
    }

    private static void writeSecure(Context c, String key, int v) {
        if (GREY_ON.equals(key) && v == 1) Eink.putSecure(c, GREY_MODE, 0);   // monochromacy, like the Greyscale tile
        Eink.putSecure(c, key, v);
    }

    // ---- foreground changes (AppProfileService) -----------------------------------------------------
    private static String prev = "";

    static void onForeground(Context c, String pkg) {
        boolean home = homePackages(c).contains(pkg);
        android.util.Log.d("einkctl", "foreground " + pkg + (home ? " (home)" : ""));
        boolean fromApp = !prev.isEmpty() && !homePackages(c).contains(prev);
        prev = pkg;
        apply(c, pkg, false);
        if (home && fromApp && Eink.prefs(c).getBoolean("home_clean", true)) Eink.clearScreen(c);   // 5.1 A
    }

    /** One-line summary for the per-app list. */
    static String summary(JSONObject o) {
        if (o.length() == 0) return "Global";
        StringBuilder s = new StringBuilder();
        if (o.has(F_REFRESH)) s.append(Eink.MODE_NAMES[Eink.modeIndex(o.optInt(F_REFRESH))].split(" \\(")[0]).append(" · ");
        if (o.has(F_PRESET)) s.append(o.optString(F_PRESET)).append(" · ");
        if (o.has(F_GREY)) s.append(o.optBoolean(F_GREY) ? "B/W" : "color").append(" · ");
        if (o.has(F_INVERT)) s.append(o.optBoolean(F_INVERT) ? "inverted" : "not inverted").append(" · ");
        if (o.has(F_HC)) s.append(o.optBoolean(F_HC) ? "high contrast" : "normal text").append(" · ");
        if (o.has(F_N)) s.append("N=").append(o.optInt(F_N)).append(" · ");
        if (o.has(F_SHAKE)) s.append(o.optBoolean(F_SHAKE) ? "anti-shake" : "no anti-shake").append(" · ");
        return s.substring(0, s.length() - 3);
    }

    /** Everything the app stores (settings, profiles, presets) as one JSON object, and back. */
    static JSONObject exportAll(Context c) throws Exception {
        JSONObject o = new JSONObject();
        for (java.util.Map.Entry<String, ?> e : Eink.prefs(c).getAll().entrySet()) {
            Object v = e.getValue();
            o.put(e.getKey(), new JSONObject().put("t", v instanceof Boolean ? "b" : v instanceof Integer ? "i" :
                    v instanceof Long ? "l" : v instanceof Float ? "f" : "s").put("v", v instanceof java.util.Set ? new JSONArray((java.util.Set<?>) v) : v));
        }
        return new JSONObject().put("app", "org.b1051c.einkctl").put("format", 1).put("prefs", o);
    }

    static void importAll(Context c, JSONObject root) throws Exception {
        JSONObject o = root.getJSONObject("prefs");
        SharedPreferences.Editor e = Eink.prefs(c).edit().clear();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject x = o.getJSONObject(k);
            switch (x.getString("t")) {
                case "b": e.putBoolean(k, x.getBoolean("v")); break;
                case "i": e.putInt(k, x.getInt("v")); break;
                case "l": e.putLong(k, x.getLong("v")); break;
                case "f": e.putFloat(k, (float) x.getDouble("v")); break;
                default: e.putString(k, x.getString("v"));
            }
        }
        e.commit();
        last = null;
    }
}
