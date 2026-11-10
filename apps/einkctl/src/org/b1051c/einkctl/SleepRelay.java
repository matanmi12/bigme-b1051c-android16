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
 * 0.15.21 (Lock & Sleep 0.2.0): SLEEP (screen off) arms the app's power alarms (Wi-Fi off after N minutes asleep,
 * power-off after N hours). The app's power-off wakes the screen with Settings.Secure b1051c_quiet_wake set to a time
 * a minute ahead: until then a screen-on leaves the front light off (quietWake), so a power-off at night does not
 * light the room while the shutdown picture is drawn.
 */
final class SleepRelay {
    static final String PKG = "org.b1051c.sleep";
    static final String WAKE = PKG + ".WAKE", BATTERY = PKG + ".BATTERY", DATE = PKG + ".DATE", THEME = PKG + ".THEME",
            SLEEP = PKG + ".SLEEP";
    static final String QUIET_WAKE = "b1051c_quiet_wake";
    private static String lastBattery;

    private SleepRelay() {}

    /** Lock & Sleep is waking the screen only to power off: keep the light off. */
    static boolean quietWake(Context c) {
        long until;
        try { until = android.provider.Settings.Secure.getLong(c.getContentResolver(), QUIET_WAKE, 0); }
        catch (SecurityException e) { return false; }
        long now = System.currentTimeMillis();
        return until > now && until - now <= 120_000;   // a stale or far-future value never keeps the light off for good
    }

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
