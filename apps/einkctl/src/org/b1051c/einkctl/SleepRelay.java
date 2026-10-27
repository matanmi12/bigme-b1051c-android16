package org.b1051c.einkctl;

import android.content.Context;
import android.content.Intent;

/**
 * Relay to the Lock & Sleep app (org.b1051c.sleep), which pre-renders the picture the panel keeps while the tablet
 * sleeps. A manifest receiver cannot get SCREEN_ON, battery or time ticks on its own, and the keeper is always
 * alive, so it forwards them as explicit broadcasts: WAKE (screen on; a folder moves to its next picture), BATTERY
 * (level bucket or charging changed), DATE (date / time / zone changed), THEME (Dark theme switched). Each one only
 * re-renders the sleep picture. FLAG_INCLUDE_STOPPED_PACKAGES: a freshly installed app that was never opened still
 * gets them. Not installed: the broadcast goes nowhere (no error).
 */
final class SleepRelay {
    static final String PKG = "org.b1051c.sleep";
    static final String WAKE = PKG + ".WAKE", BATTERY = PKG + ".BATTERY", DATE = PKG + ".DATE", THEME = PKG + ".THEME";
    private static String lastBattery;

    private SleepRelay() {}

    static void send(Context c, String action) {
        Intent i = new Intent(action).setPackage(PKG)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
        try { c.sendBroadcast(i); } catch (RuntimeException e) { android.util.Log.w("einkctl", "sleep relay: " + e); }
    }

    /** The app draws "87 %" bucketed to 5 % above 20 % (exact below) and "charging": relay only when that text changes. */
    static void battery(Context c, Intent sticky) {
        int level = sticky.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
        int scale = sticky.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return;
        int pct = level * 100 / scale;
        if (pct > 20) pct = (pct / 5) * 5;
        String key = pct + (sticky.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0 ? "+" : "-");
        if (key.equals(lastBattery)) return;
        boolean first = lastBattery == null;
        lastBattery = key;
        if (first) return;                                       // the sticky value at registration: nothing changed
        send(c, BATTERY);
    }
}
