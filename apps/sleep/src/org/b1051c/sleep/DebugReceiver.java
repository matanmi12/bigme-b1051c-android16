package org.b1051c.sleep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** adb, as root (the shell user is ignored by a non-exported receiver):
 *  su -c 'am broadcast -n org.b1051c.sleep/.DebugReceiver -a org.b1051c.sleep.RENDER [--ez png true] [--ei rot 90] [--es theme light|dark]'
 *  ... -a org.b1051c.sleep.SET --es key K --es value V  (prefs: mode off|picture|folder, source builtin|file, file, folder,
 *  scale fit|fill, show_date, show_battery, rot 270|90)  |  -a org.b1051c.sleep.NEXT  |  -a org.b1051c.sleep.STATUS
 *  The result line ("data=...") is the render summary or the status. */
public class DebugReceiver extends BroadcastReceiver {
    static final String RENDER = "org.b1051c.sleep.RENDER", SET = "org.b1051c.sleep.SET", NEXT = "org.b1051c.sleep.NEXT",
            STATUS = "org.b1051c.sleep.STATUS";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        Context app = c.getApplicationContext();
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
                + " date=" + p.showDate() + " battery=" + p.showBattery() + " rot=" + p.rot()
                + " | frame#" + p.p.getInt(Prefs.S_STANDBY, 0) + " last=" + p.p.getString(Prefs.S_LAST_FILE, "") + " at "
                + p.p.getLong(Prefs.S_RENDERED_AT, 0) + " " + p.p.getLong(Prefs.S_LAST_MS, 0) + "ms err=" + p.p.getString(Prefs.S_LAST_ERROR, "")
                + " | lock_style=" + Secure.getInt(c, Secure.LOCK_STYLE, 1) + " lock_scrim=" + Secure.getInt(c, Secure.LOCK_SCRIM, 1)
                + " shutdown=" + Secure.getString(c, Secure.SHUTDOWN);
    }
}
