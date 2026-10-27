package org.b1051c.sleep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-render triggers: E-Ink Control's relays (WAKE, BATTERY, DATE, THEME), boot and the app's own update. Each one
 *  only re-renders the sleep picture; the signature check inside the renderer makes a no-op cheap. */
public class SleepReceiver extends BroadcastReceiver {
    static final String WAKE = "org.b1051c.sleep.WAKE", BATTERY = "org.b1051c.sleep.BATTERY",
            DATE = "org.b1051c.sleep.DATE", THEME = "org.b1051c.sleep.THEME";
    private static long last;

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        boolean wake = WAKE.equals(a);
        long now = android.os.SystemClock.uptimeMillis();
        if (!wake && now - last < 2000) return;              // rate limit for the exported actions
        last = now;
        PendingResult r = goAsync();
        Context app = c.getApplicationContext();
        boolean advance = wake && Prefs.MODE_FOLDER.equals(new Prefs(app).mode());
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try { Renderer.render(app, a.substring(a.lastIndexOf('.') + 1), advance, false, 0, null); }
            catch (Throwable t) { android.util.Log.w(Renderer.TAG, "render failed", t); }
            finally { r.finish(); }
        }, "sleep-receiver").start();
    }
}
