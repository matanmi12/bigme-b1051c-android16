package org.b1051c.einkctl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * E-ink broadcasts other apps already send (ROADMAP wave 6, feature 35 a; owner 2026-10-18: "everything"). Only harmless
 * actions: a full refresh, or a refresh mode for the app on screen until the next app switch (the profile engine then
 * applies that app's own mode again), so another app never changes a saved setting.
 *  - com.xrz.GlobalRefresh: stock Bigme (its navigation bar button, a BT pen key; apps built on Bigme's SDK) -> one
 *    settled full refresh, as stock's forceGlobalRefresh(4);
 *  - the a9 family's EINK_FORCE_CLEAR -> the same; EINK_REFRESH_SPEED_CLEAR / BALANCED / SMOOTH / FAST -> Regal / HD 256
 *    / Fast / Extreme (Tasker and KOReader setups made for those GSIs keep working).
 * Implicit broadcasts reach only receivers registered at runtime, so the keeper (always running) holds this one,
 * exported. Off with the switch "Accept e-ink broadcasts from other apps" (pref compat_broadcasts, default on).
 */
final class CompatBroadcasts extends BroadcastReceiver {
    static final String GLOBAL_REFRESH = "com.xrz.GlobalRefresh", FORCE_CLEAR = "EINK_FORCE_CLEAR", SPEED = "EINK_REFRESH_SPEED_";
    static final String[] SPEEDS = {"CLEAR", "BALANCED", "SMOOTH", "FAST"};
    static final int[] SPEED_MODES = {180, -2147483471, 178, 179};   // Regal, HD 256, Fast, Extreme
    private static long lastClear;

    static void register(Context c, CompatBroadcasts r) {
        IntentFilter f = new IntentFilter(GLOBAL_REFRESH);
        f.addAction(FORCE_CLEAR);
        for (String s : SPEEDS) f.addAction(SPEED + s);
        c.registerReceiver(r, f, Context.RECEIVER_EXPORTED);
    }

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null || !Eink.prefs(c).getBoolean("compat_broadcasts", true)) return;
        if (!c.getSystemService(android.os.PowerManager.class).isInteractive()) return;   // asleep: the panel takes nothing
        if (GLOBAL_REFRESH.equals(a) || FORCE_CLEAR.equals(a)) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastClear < 1000) return;                  // one flash per second at most, whatever the sender does
            lastClear = now;
            Eink.clearScreen(c);
            android.util.Log.i("einkctl", "compat: " + a + " -> full refresh");
            return;
        }
        for (int k = 0; k < SPEEDS.length; k++) {
            if (!(SPEED + SPEEDS[k]).equals(a)) continue;
            Root.async(Eink.modeCmd(c, SPEED_MODES[k]));
            Profiles.refreshOverridden();
            android.util.Log.i("einkctl", "compat: " + a + " -> " + Eink.MODE_NAMES[Eink.modeIndex(SPEED_MODES[k])]
                    + " until the next app switch");
            return;
        }
    }
}
