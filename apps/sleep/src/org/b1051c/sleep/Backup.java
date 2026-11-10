package org.b1051c.sleep;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * Export / import of the choices (ROADMAP 18 phase 2), the same JSON shape as E-Ink Control's export:
 * {"app": "org.b1051c.sleep", "format": 1, "prefs": {key: {"t": b|i|s, "v": value}}, "secure": {lock style, scrim,
 * battery on the lock screen, shutdown picture}}. Device state (state_*) never leaves the tablet. A picked picture is referenced by name only: on
 * another install it falls back to the badge until a picture is picked again.
 * File: /sdcard/Android/data/org.b1051c.sleep/files/sleep-settings.json (no storage permission needed); an adb restore
 * may drop it into files/sleep-import.json instead (root sets the owner there).
 */
final class Backup {
    private static final String APP = "org.b1051c.sleep";
    private static final String[][] KEYS = {   // key, type: what an import may set
            {Prefs.MODE, "s"}, {Prefs.SOURCE, "s"}, {Prefs.FILE, "s"}, {Prefs.FOLDER, "s"}, {Prefs.SCALE, "s"},
            {Prefs.SHOW_DATE, "b"}, {Prefs.SHOW_BATTERY, "b"}, {Prefs.SHOW_TIME, "b"}, {Prefs.ROT, "i"},
            {Prefs.WIFI_SLEEP_MIN, "i"}, {Prefs.POWER_OFF_H, "i"}, {Restrict.KEY, "s"}};

    private Backup() {}

    static File exportFile(Context c) { return new File(c.getExternalFilesDir(null), "sleep-settings.json"); }
    static File importDropFile(Context c) { return new File(c.getFilesDir(), "sleep-import.json"); }

    static String exportSettings(Context c) {
        try {
            JSONObject prefs = new JSONObject();
            Prefs p = new Prefs(c);
            Map<String, Object> eff = new LinkedHashMap<>();   // effective values: an import also resets a changed default
            eff.put(Prefs.MODE, p.mode()); eff.put(Prefs.SOURCE, p.source()); eff.put(Prefs.FILE, p.file());
            eff.put(Prefs.FOLDER, p.folder()); eff.put(Prefs.SCALE, p.scale()); eff.put(Prefs.SHOW_DATE, p.showDate());
            eff.put(Prefs.SHOW_BATTERY, p.showBattery()); eff.put(Prefs.SHOW_TIME, p.showTime()); eff.put(Prefs.ROT, p.rot());
            eff.put(Prefs.WIFI_SLEEP_MIN, p.wifiSleepMin()); eff.put(Prefs.POWER_OFF_H, p.powerOffHours());
            eff.put(Restrict.KEY, String.join(",", Restrict.list(c)));
            for (String[] k : KEYS) prefs.put(k[0], new JSONObject().put("t", k[1]).put("v", eff.get(k[0])));
            JSONObject sec = new JSONObject()
                    .put(Secure.LOCK_STYLE, Secure.getInt(c, Secure.LOCK_STYLE, 1))
                    .put(Secure.LOCK_SCRIM, Secure.getInt(c, Secure.LOCK_SCRIM, 1))
                    .put(Secure.LOCK_BATTERY, Secure.getInt(c, Secure.LOCK_BATTERY, 1));
            String sd = Secure.getString(c, Secure.SHUTDOWN);
            if (sd != null) sec.put(Secure.SHUTDOWN, sd);
            JSONObject o = new JSONObject().put("app", APP).put("format", 1).put("prefs", prefs).put("secure", sec);
            try (FileOutputStream out = new FileOutputStream(exportFile(c))) { out.write(o.toString(1).getBytes(StandardCharsets.UTF_8)); }
            return "Exported to " + exportFile(c);
        } catch (Exception e) {
            return "Export failed: " + e;
        }
    }

    /** All or nothing: every entry must be a known key with its type, else nothing changes. */
    static String importSettings(Context c) {
        File drop = importDropFile(c), src = drop.exists() ? drop : exportFile(c);
        try (FileInputStream in = new FileInputStream(src)) {
            JSONObject o = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!APP.equals(o.optString("app")) || o.optInt("format") != 1) throw new IllegalArgumentException("not a Lock & Sleep export (format 1)");
            JSONObject prefs = o.optJSONObject("prefs");
            Map<String, Object> set = new LinkedHashMap<>();
            if (prefs != null) {
                for (java.util.Iterator<String> it = prefs.keys(); it.hasNext(); ) {
                    String k = it.next();
                    String type = null;
                    for (String[] kk : KEYS) if (kk[0].equals(k)) type = kk[1];
                    if (type == null) throw new IllegalArgumentException("unknown setting " + k);
                    JSONObject x = prefs.getJSONObject(k);
                    if (!type.equals(x.getString("t"))) throw new IllegalArgumentException(k + ": wrong type");
                    set.put(k, "b".equals(type) ? (Object) x.getBoolean("v") : "i".equals(type) ? (Object) x.getInt("v") : x.getString("v"));
                }
            }
            JSONObject sec = o.optJSONObject("secure");
            if (sec != null && ((sec.has(Secure.LOCK_STYLE) && (sec.getInt(Secure.LOCK_STYLE) & ~1) != 0)
                    || (sec.has(Secure.LOCK_SCRIM) && (sec.getInt(Secure.LOCK_SCRIM) < 0 || sec.getInt(Secure.LOCK_SCRIM) > 2))
                    || (sec.has(Secure.LOCK_BATTERY) && (sec.getInt(Secure.LOCK_BATTERY) & ~1) != 0)
                    || (sec.has(Secure.SHUTDOWN) && !sec.getString(Secure.SHUTDOWN).matches("anim_bw|logo_cream|black|off"))))
                throw new IllegalArgumentException("lock screen / shutdown value out of range");
            Object restricted = set.remove(Restrict.KEY);   // applied through Restrict: it also frees the apps taken off
            SharedPreferences.Editor e = new Prefs(c).p.edit();
            for (Map.Entry<String, Object> en : set.entrySet()) {
                Object v = en.getValue();
                if (v instanceof Boolean) e.putBoolean(en.getKey(), (Boolean) v);
                else if (v instanceof Integer) e.putInt(en.getKey(), (Integer) v);
                else e.putString(en.getKey(), (String) v);
            }
            e.putString(Prefs.S_SIG, "").commit();   // render again with what came in
            if (sec != null) {
                if (sec.has(Secure.LOCK_STYLE)) Secure.putInt(c, Secure.LOCK_STYLE, sec.getInt(Secure.LOCK_STYLE));
                if (sec.has(Secure.LOCK_SCRIM)) Secure.putInt(c, Secure.LOCK_SCRIM, sec.getInt(Secure.LOCK_SCRIM));
                if (sec.has(Secure.LOCK_BATTERY)) Secure.putInt(c, Secure.LOCK_BATTERY, sec.getInt(Secure.LOCK_BATTERY));
                if (sec.has(Secure.SHUTDOWN)) Secure.putString(c, Secure.SHUTDOWN, sec.getString(Secure.SHUTDOWN));
            }
            if (restricted != null) {
                java.util.Set<String> l = new java.util.TreeSet<>();
                for (String s : ((String) restricted).split(",")) if (!s.isEmpty()) l.add(s);
                Restrict.save(c, l);
            }
            if (src == drop) drop.delete();
            Renderer.request(c, "import");
            return "Imported " + src;
        } catch (Exception e) {
            return "Import failed: " + e;
        }
    }
}
