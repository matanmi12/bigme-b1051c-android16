package org.b1051c.einkctl;

import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.TileService;

/**
 * Touch off (wave 4, stock Control Center "Touch"): only the pen works, for writing with the hand on the glass or
 * reading in bed. einkd grabs the touch screen (EVIOCGRAB) while vendor.debug.b1051c.touch_off is 1, lifting any
 * finger for Android first. Never sticks: the keeper turns it back on when the screen goes off, and if einkd stops
 * the grab ends with it. Device state, never exported.
 */
public class TileTouch extends TileService {
    static final String PREF = ThemeSync.STATE + "touch_off", PROP = "vendor.debug.b1051c.touch_off";

    @Override public void onStartListening() { update(); }

    @Override
    public void onClick() {
        set(this, !Eink.prefs(this).getBoolean(PREF, false));
        update();
    }

    static void set(Context c, boolean off) {
        Eink.prefs(c).edit().putBoolean(PREF, off).apply();
        Root.async("setprop " + PROP + " " + (off ? 1 : 0));
        requestListeningState(c, new ComponentName(c, TileTouch.class));
    }

    /** Screen off: touch on again (the keeper). */
    static void release(Context c) { if (Eink.prefs(c).getBoolean(PREF, false)) set(c, false); }

    private void update() {
        boolean off = Eink.prefs(this).getBoolean(PREF, false);
        Tiles.show(this, "Touch", off ? "off, pen only" : "on", off);
    }
}
