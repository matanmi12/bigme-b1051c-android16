package org.b1051c.einkctl;

import android.service.quicksettings.TileService;

/**
 * Magnetic cover on/off (owner 2026-10-19). On (default): closing the cover sleeps, opening wakes (hall_key.kl in
 * the eink-compat module). Off: einkd grabs the cover sensor (EVIOCGRAB), so Android never sees it. The switch is
 * persist.b1051c.cover_wake (survives reboots); einkd re-checks it every 2 s, and if einkd stops the cover works again.
 */
public class TileCover extends TileService {
    static final String PREF = "cover_wake", PROP = "persist.b1051c.cover_wake";

    @Override public void onStartListening() { update(); }

    @Override
    public void onClick() {
        boolean on = !Eink.prefs(this).getBoolean(PREF, true);
        Eink.prefs(this).edit().putBoolean(PREF, on).apply();
        Root.async("setprop " + PROP + " " + (on ? 1 : 0));
        update();
    }

    private void update() {
        boolean on = Eink.prefs(this).getBoolean(PREF, true);
        Tiles.show(this, "Cover", on ? "wakes and sleeps" : "ignored", on);
    }
}
