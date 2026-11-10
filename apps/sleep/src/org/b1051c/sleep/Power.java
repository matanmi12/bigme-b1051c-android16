package org.b1051c.sleep;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/**
 * Power while asleep (ROADMAP wave 5, owner's answers 22.1 b / 22.2 of 2026-10-18). E-Ink Control relays SLEEP
 * (screen off) and WAKE (screen on); this class arms wake-up alarms at SLEEP and undoes them at WAKE.
 *  - Wi-Fi: off after N minutes asleep (default 15), back on at the next wake, and after a reboot that happened while
 *    it was off (only when this app switched it off; a Wi-Fi the user turned off stays off).
 *  - Power-off after N hours asleep (default never). The panel cannot be updated while it sleeps, so the shutdown
 *    picture needs the screen on: the screen wakes with Settings.Secure b1051c_quiet_wake set (E-Ink Control 0.15.21
 *    leaves the front light off), then Android shuts down and E-Ink Control draws the shutdown picture as usual.
 */
final class Power {
    static final String WIFI_OFF = "org.b1051c.sleep.WIFI_OFF", POWER_OFF = "org.b1051c.sleep.POWER_OFF";
    static final String QUIET_WAKE = "b1051c_quiet_wake";   // epoch ms until which a screen-on keeps the light off
    static final String TAG = Renderer.TAG;

    private Power() {}

    private static PendingIntent pi(Context c, String action, boolean create) {
        Intent i = new Intent(action).setClass(c, PowerReceiver.class);
        return PendingIntent.getBroadcast(c, action.hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE | (create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE));
    }

    private static void arm(Context c, String action, long atElapsed) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (!am.canScheduleExactAlarms()) {
            // a ROM-provisioned or adb-installed app has no exact-alarm grant: this app has root, so it grants itself
            Root.run("appops set --user 0 " + c.getPackageName() + " SCHEDULE_EXACT_ALARM allow");
        }
        PendingIntent p = pi(c, action, true);
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, p);
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, p);
    }

    private static void disarm(Context c, String action) {
        PendingIntent p = pi(c, action, false);
        if (p != null) { c.getSystemService(AlarmManager.class).cancel(p); p.cancel(); }
    }

    static void onSleep(Context c) {
        Prefs p = new Prefs(c);
        long now = SystemClock.elapsedRealtime();
        p.p.edit().putLong(Prefs.S_ASLEEP_AT, now).apply();
        int min = p.wifiSleepMin(), h = p.powerOffHours();
        if (min > 0 && wifiOn(c)) arm(c, WIFI_OFF, now + min * 60_000L); else disarm(c, WIFI_OFF);
        if (h > 0) arm(c, POWER_OFF, now + h * 3_600_000L); else disarm(c, POWER_OFF);
        log(c, "asleep: Wi-Fi " + (min > 0 ? "off in " + min + " min" : "kept") + ", power-off " + (h > 0 ? "in " + h + " h" : "never"));
        Restrict.reapply(c);
    }

    static void onWake(Context c) {
        disarm(c, WIFI_OFF);
        disarm(c, POWER_OFF);
        restoreWifi(c, "wake");
    }

    /** After a reboot: the alarms are gone, and a Wi-Fi this app switched off must come back. */
    static void onBoot(Context c) { restoreWifi(c, "boot"); Restrict.reapply(c); }

    static void restoreWifi(Context c, String why) {
        Prefs p = new Prefs(c);
        if (!p.p.getBoolean(Prefs.S_WIFI_BY_US, false)) return;
        String r = Root.run("cmd wifi set-wifi-enabled enabled && echo ok");
        p.p.edit().putBoolean(Prefs.S_WIFI_BY_US, false).apply();
        log(c, "Wi-Fi back on (" + why + "): " + r);
    }

    static void onAlarm(Context c, String action, boolean dryRun) {
        boolean awake = c.getSystemService(android.os.PowerManager.class).isInteractive();
        if (awake && !dryRun) { log(c, action.substring(action.lastIndexOf('.') + 1) + ": awake again, nothing to do"); return; }
        if (WIFI_OFF.equals(action)) {
            if (!wifiOn(c)) { log(c, "Wi-Fi already off"); return; }
            String r = Root.run("cmd wifi set-wifi-enabled disabled && echo ok");
            if ("ok".equals(r)) new Prefs(c).p.edit().putBoolean(Prefs.S_WIFI_BY_US, true).apply();
            log(c, "Wi-Fi off after " + asleepMin(c) + " min asleep: " + r);
        } else if (POWER_OFF.equals(action)) {
            powerOff(c, dryRun);
        }
    }

    /** Quiet wake (light stays off), so E-Ink Control can put the shutdown picture on the panel, then shut down. */
    static void powerOff(Context c, boolean dryRun) {
        android.provider.Settings.Secure.putLong(c.getContentResolver(), QUIET_WAKE, System.currentTimeMillis() + 60_000);
        String r = Root.run("input keyevent KEYCODE_WAKEUP; i=0; while [ $i -lt 30 ]; do dumpsys power 2>/dev/null | grep -q 'mWakefulness=Awake' && break;"
                + " sleep 0.1; i=$((i+1)); done; sleep 1; echo awake after $i");
        log(c, "power-off after " + asleepMin(c) / 60 + " h asleep (" + r + ")" + (dryRun ? ": dry run, not shutting down" : ""));
        if (!dryRun) Root.run("svc power shutdown");
    }

    static boolean wifiOn(Context c) {
        android.net.wifi.WifiManager w = c.getSystemService(android.net.wifi.WifiManager.class);
        return w != null && w.isWifiEnabled();
    }

    private static long asleepMin(Context c) {
        long at = new Prefs(c).p.getLong(Prefs.S_ASLEEP_AT, 0);
        return at > 0 ? (SystemClock.elapsedRealtime() - at) / 60_000 : -1;
    }

    /** Logcat plus the last few lines for the settings screen's status. */
    static void log(Context c, String line) {
        Log.i(TAG, "power: " + line);
        Prefs p = new Prefs(c);
        String stamp = android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis()) + " " + line;
        String old = p.p.getString(Prefs.S_POWER_LOG, "");
        String[] lines = old.isEmpty() ? new String[0] : old.split("\n");
        StringBuilder sb = new StringBuilder(stamp);
        for (int i = 0; i < Math.min(3, lines.length); i++) sb.append('\n').append(lines[i]);
        p.p.edit().putString(Prefs.S_POWER_LOG, sb.toString()).apply();
    }
}
