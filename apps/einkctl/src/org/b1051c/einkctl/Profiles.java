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
 * Two more system rows (owner 2026-10-27): "@shade" while the notification shade / quick settings is open (over the
 * app, from the global settings) and "@recents" (Launcher3's overview while another app is the home, from "@system").
 * Fields: refresh (mode int), preset (colour preset name; suggests refresh/anti-shake when those are unset),
 * grey / invert / hc (Android settings, tri-state), n (full refresh every N), shake (anti-shake), sat (the shim's
 * OKLCh color boost, ok_sat), black (darker text, the shim's level_black; wave 3 step 4) and scroll (wave 4: what a
 * finger drag does, SCROLL_VALUES; einkd and the shim read vendor.debug.hwc.shim.scroll).
 * The Android settings are only touched while an app overrides them: the live value is saved first and restored
 * when an app without an override comes up, so the system tiles keep working.
 * Under Android's Dark theme a profile's invert=On is skipped (owner 2026-10-20: no double inversion); the global
 * inversion (QS tile) is left alone.
 */
final class Profiles {
    private Profiles() {}

    static final String SYSTEM = "@system", SHADE = "@shade", RECENTS = "@recents";
    static final String F_REFRESH = "refresh", F_PRESET = "preset", F_GREY = "grey", F_INVERT = "invert", F_HC = "hc",
            F_N = "n", F_SHAKE = "shake", F_SAT = "sat", F_BLACK = "black", F_SCROLL = "scroll";
    /** While a finger drags: Fast (stock, HD 256 / Regal only), Extreme (any mode), nothing. Global default Fast. */
    static final int[] SCROLL_VALUES = {178, 179, 0};
    static final String[] SCROLL_NAMES = {"Fast", "Extreme", "Off"};
    /** Reading apps get darker text (A/B round 2, 2026-10-22: the owner liked level_black 20 for reading only). */
    static final String[] READERS = {"org.koreader.launcher", "com.amazon.kindle"};
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

    /** The preset also keeps the current Color boost and Darker text settings. */
    static void saveUserPreset(Context c, String name, int vivid, int contrast, int cbright, int refresh) {
        SharedPreferences p = Eink.prefs(c);
        JSONArray a = userPresets(c), out = new JSONArray();
        try {
            for (int i = 0; i < a.length(); i++) if (!a.getJSONObject(i).getString("name").equals(name)) out.put(a.get(i));
            out.put(new JSONObject().put("name", name).put("vivid", vivid).put("contrast", contrast).put("cbright", cbright)
                    .put("mode", 4).put("refresh", refresh).put("ok_sat", p.getInt("ok_sat", Eink.OK_SAT))
                    .put("black", p.getInt("level_black", 0)));
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
        if (!p.getBoolean("seeded_v1", false)) {
            try {
                if (get(c, "com.amazon.kindle").length() == 0) put(c, "com.amazon.kindle", new JSONObject().put(F_REFRESH, 180));
            } catch (Exception ignored) {}
            p.edit().putBoolean("seeded_v1", true).apply();
        }
        if (!p.getBoolean("seeded_v2", false)) {             // 0.15.16: darker text for the reading apps
            for (String pkg : READERS) {
                try {
                    JSONObject o = get(c, pkg);
                    if (!o.has(F_BLACK)) put(c, pkg, o.put(F_BLACK, 20));
                } catch (Exception ignored) {}
            }
            p.edit().putBoolean("seeded_v2", true).apply();
        }
        if (!p.getBoolean("seeded_v3", false)) {             // 0.15.19: shade and Recents in Fast (owner 2026-10-27)
            for (String key : new String[] {SHADE, RECENTS}) {
                try { if (get(c, key).length() == 0) put(c, key, new JSONObject().put(F_REFRESH, 178)); } catch (Exception ignored) {}
            }
            p.edit().putBoolean("seeded_v3", true).apply();
        }
    }

    // ---- resolution ---------------------------------------------------------------------------------
    static final class Eff {
        int refresh, vivid, contrast, cbright, colorMode, okSat, black, n, scroll;
        boolean shake;
        Boolean grey, invert, hc;   // null = leave Android's setting alone
    }

    private static Set<String> homeCache;
    private static long homeAt;

    /** Packages that are a home screen: every HOME activity Android would offer as a default, so Launcher3's overview
     *  (Recents while Lawnchair is the home) counts as home too. Settings' FallbackHome (priority -1000, shown only
     *  until the real launcher is ready) is not one. Needs the HOME intent in the manifest's {@code <queries>}:
     *  Launcher3 QuickStep has no LAUNCHER activity and was invisible to the app, while Settings passed as home
     *  (review 2026-10-21). */
    static synchronized Set<String> homePackages(Context c) {
        if (homeCache == null || System.currentTimeMillis() - homeAt > 60_000) {
            Set<String> s = new HashSet<>();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo r : c.getPackageManager().queryIntentActivities(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY))
                if (r.priority >= 0) s.add(r.activityInfo.packageName);
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
        e.scroll = p.getInt("scroll", 178);
        if (pkg != null && (homePackages(c).contains(pkg) || RECENTS.equals(pkg))) layer(c, get(c, SYSTEM), e);
        if (pkg != null && !pkg.isEmpty()) layer(c, get(c, pkg), e);
        // per-app inversion off while the Dark theme is on: null = tri() restores the value saved before the
        // override (an explicit invert=Off still forces it off); ThemeSync re-applies on every switch
        boolean night = ThemeSync.night(c);
        if (night && Boolean.TRUE.equals(e.invert)) e.invert = null;
        int nDark = p.getInt("n_dark", 0);
        boolean dark = night || (e.invert != null ? e.invert : Eink.secureInt(c, INVERT, 0) == 1);
        if (dark && nDark > 0) e.n = nDark;
        // ranges of the UI controls: an imported or hand-edited value never reaches the HWC / shim out of range
        e.n = Eink.clamp(e.n, 0, 50);
        e.vivid = Eink.clamp(e.vivid, 0, 100);
        e.contrast = Eink.clamp(e.contrast, 0, 100);
        e.cbright = Eink.clamp(e.cbright, 0, 100);
        e.colorMode = Eink.clamp(e.colorMode, 0, 4);   // the stock colour modes: Default..Custom
        e.okSat = Eink.clamp(e.okSat, -100, 300);       // the shim's own range
        e.black = Eink.clamp(e.black, 0, 128);
        if (Eink.stepIndex(SCROLL_VALUES, e.scroll) < 0) e.scroll = 178;
        return e;
    }

    private static void layer(Context c, JSONObject o, Eff e) {
        if (o.length() == 0) return;
        if (o.has(F_PRESET)) presetInto(c, o.optString(F_PRESET), e, o);
        if (o.has(F_REFRESH)) e.refresh = Eink.normMode(o.optInt(F_REFRESH));
        if (o.has(F_N)) e.n = o.optInt(F_N);
        if (o.has(F_SHAKE)) e.shake = o.optBoolean(F_SHAKE);
        if (o.has(F_SAT)) e.okSat = o.optInt(F_SAT);
        if (o.has(F_BLACK)) e.black = o.optInt(F_BLACK);
        if (o.has(F_SCROLL)) e.scroll = o.optInt(F_SCROLL);
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
            e.okSat = v[7] >= 0 ? v[7] : p.getInt("ok_sat", Eink.OK_SAT);
            e.black = p.getInt("level_black", 0);
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
            e.okSat = u.optInt("ok_sat", p.getInt("ok_sat", Eink.OK_SAT));
            e.black = u.optInt("black", p.getInt("level_black", 0));
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

    /** Someone else set the refresh mode (CompatBroadcasts): the next apply sends the app's own mode again. */
    static synchronized void refreshOverridden() { if (last != null) last.refresh = Integer.MIN_VALUE; }

    /** Fast is the mode for typing (2.3 B); Extreme and A2 are faster already. */
    private static boolean slow(int mode) { return mode != 178 && mode != 179 && mode != 16; }

    static synchronized void apply(Context c, String pkg, boolean force) {
        Eff e = resolve(c, pkg);
        if (imeOpen && Eink.prefs(c).getBoolean("ime_fast", true) && slow(e.refresh)) e.refresh = 178;
        StringBuilder sb = new StringBuilder();
        if (force || last == null || e.refresh != last.refresh) sb.append(Eink.modeCmd(c, e.refresh)).append("; ");
        if (force || last == null || e.vivid != last.vivid || e.contrast != last.contrast || e.cbright != last.cbright
                || e.colorMode != last.colorMode || e.okSat != last.okSat || e.black != last.black) {
            sb.append("setprop vendor.xrz.global_color_enhance ").append(Eink.clamp(e.vivid, 0, 100)).append("; ");
            sb.append("setprop vendor.xrz.global_contrast ").append(Eink.clamp(e.contrast, 0, 100)).append("; ");
            sb.append("setprop vendor.xrz.global_brightness_level ").append(Eink.clamp(e.cbright, 0, 100)).append("; ");
            sb.append("setprop vendor.xrz.global_color_mode ").append(e.colorMode).append("; ");
            sb.append("setprop vendor.debug.hwc.shim.ok_sat ").append(e.okSat).append("; ");
            sb.append("setprop vendor.debug.hwc.shim.level_black ").append(e.black).append("; ");
        }
        if (force || last == null || e.shake != last.shake)
            sb.append("setprop vendor.xrz.anti_flicker_enabled ").append(e.shake ? 1 : 0).append("; ");
        if (force || last == null || e.n != last.n)
            sb.append("setprop vendor.xrz.clean_frequency ").append(Eink.clamp(e.n, 0, 50)).append("; ");
        if (force || last == null || e.scroll != last.scroll)
            sb.append("setprop vendor.debug.hwc.shim.scroll ").append(e.scroll).append("; ");
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
        if (SHADE.equals(pkg)) {                             // over the app: no app switch (prev, home clean)
            android.util.Log.d("einkctl", "foreground: notification shade");
            apply(c, pkg, false);
            return;
        }
        boolean home = homePackages(c).contains(pkg) || RECENTS.equals(pkg);
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
        if (o.has(F_SAT)) s.append("boost ").append(o.optInt(F_SAT)).append(" % · ");
        if (o.has(F_BLACK)) s.append(o.optInt(F_BLACK) > 0 ? "darker text " + o.optInt(F_BLACK) : "normal text weight").append(" · ");
        if (o.has(F_SCROLL)) {
            int i = Eink.stepIndex(SCROLL_VALUES, o.optInt(F_SCROLL));
            s.append(i == 2 ? "no scroll mode" : "scroll " + (i >= 0 ? SCROLL_NAMES[i] : "?")).append(" · ");
        }
        return s.substring(0, s.length() - 3);
    }

    /** Device state (never exported, kept across an import): ThemeSync's applied state, and the system values saved
     *  before a per-app override (base_*: importing another device's would leave inversion / B/W / high contrast stuck). */
    private static boolean deviceState(String key) { return key.startsWith(ThemeSync.STATE) || key.startsWith("base_"); }

    /** Everything the app stores (settings, profiles, presets) as one JSON object, and back. */
    static JSONObject exportAll(Context c) throws Exception {
        JSONObject o = new JSONObject();
        for (java.util.Map.Entry<String, ?> e : Eink.prefs(c).getAll().entrySet()) {
            if (deviceState(e.getKey())) continue;
            Object v = e.getValue();
            o.put(e.getKey(), new JSONObject().put("t", v instanceof Boolean ? "b" : v instanceof Integer ? "i" :
                    v instanceof Long ? "l" : v instanceof Float ? "f" : "s").put("v", v instanceof java.util.Set ? new JSONArray((java.util.Set<?>) v) : v));
        }
        return new JSONObject().put("app", "org.b1051c.einkctl").put("format", 1).put("prefs", o);
    }

    /** Throws (and changes nothing) unless every entry parses and keeps the type the key already has here: one wrong
     *  type used to be committed and crash every reader of that pref (review 2026-10-21). */
    static void importAll(Context c, JSONObject root) throws Exception {
        if (!"org.b1051c.einkctl".equals(root.optString("app")) || root.optInt("format") != 1)
            throw new IllegalArgumentException("not an E-Ink Control export (format 1)");
        JSONObject o = root.getJSONObject("prefs");
        java.util.Map<String, ?> keep = Eink.prefs(c).getAll();
        java.util.Map<String, Object> in = new java.util.LinkedHashMap<>();
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (deviceState(k)) continue;
            JSONObject x = o.getJSONObject(k);
            Object v;
            switch (x.getString("t")) {
                case "b": v = x.getBoolean("v"); break;
                case "i": v = x.getInt("v"); break;
                case "l": v = x.getLong("v"); break;
                case "f": v = (float) x.getDouble("v"); break;
                case "s": v = x.getString("v"); break;
                default: throw new IllegalArgumentException("unknown type for " + k);
            }
            Object cur = keep.get(k);
            if (cur != null && cur.getClass() != v.getClass())
                throw new IllegalArgumentException(k + ": " + v.getClass().getSimpleName() + " where " + cur.getClass().getSimpleName() + " is stored");
            if (v instanceof String && (k.startsWith("profile:") || k.equals("presets")))
                if (k.equals("presets")) new JSONArray((String) v); else new JSONObject((String) v);   // must parse
            in.put(k, v);
        }
        SharedPreferences.Editor e = Eink.prefs(c).edit().clear();
        for (java.util.Map.Entry<String, ?> en : keep.entrySet()) {   // device state survives, whatever its type
            if (deviceState(en.getKey())) put(e, en.getKey(), en.getValue());
        }
        for (java.util.Map.Entry<String, Object> en : in.entrySet()) put(e, en.getKey(), en.getValue());
        e.commit();
        last = null;
    }

    private static void put(SharedPreferences.Editor e, String k, Object v) {
        if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) e.putInt(k, (Integer) v);
        else if (v instanceof Long) e.putLong(k, (Long) v);
        else if (v instanceof Float) e.putFloat(k, (Float) v);
        else if (v instanceof String) e.putString(k, (String) v);
    }
}
