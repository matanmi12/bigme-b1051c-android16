package org.b1051c.sleep;

import android.content.Context;
import android.provider.Settings;

/** Settings.Secure keys shared with the SystemUI plugin (lock screen look) and E-Ink Control (shutdown picture).
 *  WRITE_SECURE_SETTINGS is granted with `pm grant`; without it the write goes through root. */
final class Secure {
    static final String LOCK_STYLE = "b1051c_lock_style";        // 1 InKognito (default), 0 Android's lock screen
    static final String LOCK_SCRIM = "b1051c_lock_scrim";        // 1 none (default), 2 page colour, 0 Android's 20 %
    static final String SHUTDOWN = "b1051c_shutdown_screen";     // anim_bw | logo_cream | black | off (E-Ink Control draws it)

    private Secure() {}

    static int getInt(Context c, String key, int def) {
        try { return Settings.Secure.getInt(c.getContentResolver(), key, def); } catch (SecurityException e) { return def; }
    }

    static String getString(Context c, String key) {
        try { return Settings.Secure.getString(c.getContentResolver(), key); } catch (SecurityException e) { return null; }
    }

    static void putInt(Context c, String key, int v) {
        try { Settings.Secure.putInt(c.getContentResolver(), key, v); }
        catch (SecurityException e) { Root.async("settings put secure " + key + " " + v); }
    }

    static void putString(Context c, String key, String v) {
        try { Settings.Secure.putString(c.getContentResolver(), key, v); }
        catch (SecurityException e) { Root.async("settings put secure " + key + " '" + v.replace("'", "") + "'"); }
    }
}
