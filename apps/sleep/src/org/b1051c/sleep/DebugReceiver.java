package org.b1051c.sleep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** adb, as root (the shell user is ignored by a non-exported receiver):
 *  su -c 'am broadcast -n org.b1051c.sleep/.DebugReceiver -a org.b1051c.sleep.RENDER [--ez png true] [--ei rot 90] [--es theme light|dark]'
 *  ... -a org.b1051c.sleep.SET --es key K --es value V  (prefs: mode off|picture|folder, source builtin|file, file, folder,
 *  scale fit|fill, show_date, show_battery, show_time, rot 270|90, wifi_sleep_min N, power_off_h N)  |  -a org.b1051c.sleep.NEXT
 *  |  -a org.b1051c.sleep.STATUS  |  -a org.b1051c.sleep.POWER --es what sleep|wake|wifi_off|power_off [--ez dry true]
 *  (power_off without dry=true really shuts the tablet down)  |  -a org.b1051c.sleep.COVER_IMPORT [--es title T]
 *  (copies KOReader's export like the patch's COVER does, without the sender check)  |  -a org.b1051c.sleep.EXPORT
 *  |  -a org.b1051c.sleep.IMPORT  (the settings screen's "Settings file" buttons; see Backup)
 *  |  -a org.b1051c.sleep.RESTRICT --es pkgs "a.b,c.d"  (the list of apps kept out of the background; "" = none)
 *  The result line ("data=...") is the render summary or the status. */
public class DebugReceiver extends BroadcastReceiver {
    static final String RENDER = "org.b1051c.sleep.RENDER", SET = "org.b1051c.sleep.SET", NEXT = "org.b1051c.sleep.NEXT",
            STATUS = "org.b1051c.sleep.STATUS", POWER = "org.b1051c.sleep.POWER", COVER_IMPORT = "org.b1051c.sleep.COVER_IMPORT",
            EXPORT = "org.b1051c.sleep.EXPORT", IMPORT = "org.b1051c.sleep.IMPORT", RESTRICT = "org.b1051c.sleep.RESTRICT";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        Context app = c.getApplicationContext();
        if (EXPORT.equals(a) || IMPORT.equals(a) || RESTRICT.equals(a)) {
            PendingResult r = goAsync();
            String pkgs = i.getStringExtra("pkgs");
            new Thread(() -> {
                String res;
                try {
                    if (RESTRICT.equals(a)) {
                        java.util.Set<String> l = new java.util.TreeSet<>();
                        for (String s : (pkgs == null ? "" : pkgs).split(",")) if (!s.trim().isEmpty()) l.add(s.trim());
                        res = Restrict.save(app, l) + ": " + Restrict.list(app);
                    } else res = EXPORT.equals(a) ? Backup.exportSettings(app) : Backup.importSettings(app);
                }
                catch (Throwable t) { res = "failed: " + t; }
                r.setResultData(res);
                r.finish();
            }, "sleep-debug-backup").start();
            return;
        }
        if (POWER.equals(a) || COVER_IMPORT.equals(a)) {
            PendingResult r = goAsync();
            String what = i.getStringExtra("what"), title = i.getStringExtra("title");
            boolean dry = i.getBooleanExtra("dry", false);
            new Thread(() -> {
                String res = "ok";
                try {
                    if (COVER_IMPORT.equals(a)) {
                        res = Cover.importFromKoreader(app, title == null ? "adb" : title);
                        if (res.isEmpty()) res = Renderer.render(app, "cover-adb", false, false, 0, null);
                    } else if ("sleep".equals(what)) Power.onSleep(app);
                    else if ("wake".equals(what)) Power.onWake(app);
                    else if ("wifi_off".equals(what)) Power.onAlarm(app, Power.WIFI_OFF, dry);
                    else if ("power_off".equals(what)) Power.onAlarm(app, Power.POWER_OFF, dry);
                    else res = "what=sleep|wake|wifi_off|power_off";
                } catch (Throwable t) { res = "failed: " + t; }
                r.setResultData(res + " | " + new Prefs(app).p.getString(Prefs.S_POWER_LOG, "").replace('\n', ' '));
                r.finish();
            }, "sleep-debug-power").start();
            return;
        }
        if (SET.equals(a)) {
            String k = i.getStringExtra("key"), v = i.getStringExtra("value");
            if (k == null || v == null) return;
            android.content.SharedPreferences.Editor e = new Prefs(app).p.edit();
            if ("true".equals(v) || "false".equals(v)) e.putBoolean(k, "true".equals(v));
            else if (v.matches("-?\\d+")) e.putInt(k, Integer.parseInt(v));
            else e.putString(k, v);
            e.apply();
            setResultData("set " + k + "=" + v);
            return;
        }
        if (STATUS.equals(a)) { setResultData(status(app)); return; }
        PendingResult r = goAsync();
        boolean png = i.getBooleanExtra("png", false);
        int rot = i.getIntExtra("rot", 0);
        String theme = i.getStringExtra("theme");
        boolean advance = NEXT.equals(a);
        new Thread(() -> {
            String res;
            try { res = Renderer.render(app, advance ? "next" : "adb", advance, png, rot, theme); }
            catch (Throwable t) { res = "failed: " + t; }
            android.util.Log.i(Renderer.TAG, "debug render: " + res);
            r.setResultData(res);
            r.finish();
        }, "sleep-debug").start();
    }

    static String status(Context c) {
        Prefs p = new Prefs(c);
        return "mode=" + p.mode() + " source=" + p.source() + " file=" + p.file() + " folder=" + p.folder() + " scale=" + p.scale()
                + " date=" + p.showDate() + " battery=" + p.showBattery() + " time=" + p.showTime() + "("
                + p.p.getString(Prefs.S_CLOCK, "?") + ") rot=" + p.rot() + " wifi_sleep_min=" + p.wifiSleepMin()
                + " power_off_h=" + p.powerOffHours() + " wifi_by_us=" + p.p.getBoolean(Prefs.S_WIFI_BY_US, false)
                + " cover=" + p.p.getString(Prefs.S_COVER_TITLE, "") + " restricted=" + Restrict.list(c)
                + " | frame#" + p.p.getInt(Prefs.S_STANDBY, 0) + " last=" + p.p.getString(Prefs.S_LAST_FILE, "") + " at "
                + p.p.getLong(Prefs.S_RENDERED_AT, 0) + " " + p.p.getLong(Prefs.S_LAST_MS, 0) + "ms err=" + p.p.getString(Prefs.S_LAST_ERROR, "")
                + " | lock_style=" + Secure.getInt(c, Secure.LOCK_STYLE, 1) + " lock_scrim=" + Secure.getInt(c, Secure.LOCK_SCRIM, 1)
                + " shutdown=" + Secure.getString(c, Secure.SHUTDOWN);
    }
}
