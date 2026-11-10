package org.b1051c.sleep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The power alarms (Power.WIFI_OFF / POWER_OFF). Not exported: only this app's own PendingIntents reach it, so no
 *  other app can switch Wi-Fi off or shut the tablet down through it. The alarm's wake lock lasts until finish(). */
public class PowerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        PendingResult r = goAsync();
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try { Power.onAlarm(app, a, false); }
            catch (Throwable t) { android.util.Log.w(Renderer.TAG, "power alarm failed (" + a + ")", t); }
            finally { r.finish(); }
        }, "sleep-power").start();
    }
}
