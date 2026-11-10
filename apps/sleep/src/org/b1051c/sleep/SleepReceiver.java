package org.b1051c.sleep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-render triggers: E-Ink Control's relays (WAKE, BATTERY, DATE, THEME), boot and the app's own update. Each one
 *  only re-renders the sleep picture; the signature check inside the renderer makes a no-op cheap. Since 0.2.0 also
 *  SLEEP (E-Ink Control 0.15.21: arms the power alarms, see Power) and COVER (KOReader's patch, accepted only when
 *  Android names KOReader as the sender). */
public class SleepReceiver extends BroadcastReceiver {
    static final String WAKE = "org.b1051c.sleep.WAKE", BATTERY = "org.b1051c.sleep.BATTERY",
            DATE = "org.b1051c.sleep.DATE", THEME = "org.b1051c.sleep.THEME", SLEEP = "org.b1051c.sleep.SLEEP",
            COVER = "org.b1051c.sleep.COVER";
    private static long last;

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        boolean wake = WAKE.equals(a), sleep = SLEEP.equals(a), cover = COVER.equals(a);
        if (cover && !Cover.KOREADER.equals(getSentFromPackage())) {
            android.util.Log.w(Renderer.TAG, "COVER from " + getSentFromPackage() + " ignored (only KOReader's patch may send it)");
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (!wake && !sleep && !cover && now - last < 2000) return;   // rate limit for the other exported actions
        if (!wake && !sleep && !cover) last = now;
        PendingResult r = goAsync();
        Context app = c.getApplicationContext();
        String title = cover ? i.getStringExtra("title") : null;
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                if (sleep) { Power.onSleep(app); return; }
                if (wake) Power.onWake(app);
                if (Intent.ACTION_BOOT_COMPLETED.equals(a)) Power.onBoot(app);
                if (cover) {
                    String err = Cover.importFromKoreader(app, title);
                    android.util.Log.i(Renderer.TAG, "cover from KOReader" + (err.isEmpty() ? ": " + title : " not imported: " + err));
                    if (!err.isEmpty() || !Prefs.MODE_COVER.equals(new Prefs(app).mode())) return;
                }
                boolean advance = wake && Prefs.MODE_FOLDER.equals(new Prefs(app).mode());
                Renderer.render(app, a.substring(a.lastIndexOf('.') + 1), advance, false, 0, null);
            }
            catch (Throwable t) { android.util.Log.w(Renderer.TAG, "receiver failed (" + a + ")", t); }
            finally { r.finish(); }
        }, "sleep-receiver").start();
    }
}
