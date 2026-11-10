package org.b1051c.sleep;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings of the sleep picture (SharedPreferences "sleep"). Keys starting with state_ are device state. */
final class Prefs {
    static final String NAME = "sleep";
    // mode: what the panel keeps while the tablet sleeps
    static final String MODE = "mode";               // off | picture | folder | cover
    static final String MODE_OFF = "off", MODE_PICTURE = "picture", MODE_FOLDER = "folder", MODE_COVER = "cover";
    static final String SOURCE = "source";           // builtin | file
    static final String SRC_BUILTIN = "builtin", SRC_FILE = "file";
    static final String FILE = "file";               // name inside files/picture/
    static final String FOLDER = "folder";           // pictures from a folder, in turn
    static final String FOLDER_DEFAULT = "/sdcard/Pictures/InKognito/Sleep";
    static final String SCALE = "scale";             // fit | fill
    static final String SCALE_FIT = "fit", SCALE_FILL = "fill";
    static final String SHOW_DATE = "show_date", SHOW_BATTERY = "show_battery", SHOW_TIME = "show_time";
    static final String ROT = "rot";                 // 270 (SurfaceFlinger's ROT_270) or 90 for the opposite mapping
    // power while asleep (0.2.0, ROADMAP 22): minutes until Wi-Fi goes off (0 = never), hours until power-off (0 = never)
    static final String WIFI_SLEEP_MIN = "wifi_sleep_min", POWER_OFF_H = "power_off_h";
    static final int WIFI_SLEEP_DEFAULT = 15;        // owner 2026-10-18 (22.1 b)
    // device state (never exported)
    static final String S_FOLDER_INDEX = "state_folder_index", S_FOLDER_LAST = "state_folder_last", S_SIG = "state_sig",
            S_STANDBY = "state_standby", S_BOOT = "state_boot", S_RENDERED_AT = "state_rendered_at",
            S_LAST_FILE = "state_last_file", S_LAST_ERROR = "state_last_error", S_LAST_MS = "state_last_ms",
            S_CLOCK = "state_clock", S_COVER_TITLE = "state_cover_title", S_COVER_AT = "state_cover_at",
            S_ASLEEP_AT = "state_asleep_at", S_WIFI_BY_US = "state_wifi_by_us", S_POWER_LOG = "state_power_log";

    final SharedPreferences p;

    Prefs(Context c) { p = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE); }

    String mode() { return p.getString(MODE, MODE_PICTURE); }
    String source() { return p.getString(SOURCE, SRC_BUILTIN); }
    String file() { return p.getString(FILE, ""); }
    String folder() { return p.getString(FOLDER, FOLDER_DEFAULT); }
    String scale() { return p.getString(SCALE, SCALE_FIT); }
    boolean showDate() { return p.getBoolean(SHOW_DATE, true); }
    boolean showBattery() { return p.getBoolean(SHOW_BATTERY, true); }
    boolean showTime() { return p.getBoolean(SHOW_TIME, true); }
    int rot() { return p.getInt(ROT, 270); }
    int wifiSleepMin() { return p.getInt(WIFI_SLEEP_MIN, WIFI_SLEEP_DEFAULT); }
    int powerOffHours() { return p.getInt(POWER_OFF_H, 0); }

    /** Boot identity, as E-Ink Control computes it: props are gone after a reboot, so the frame must be published again. */
    static long bootEpoch() {
        return (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000;   // minute resolution
    }
}
