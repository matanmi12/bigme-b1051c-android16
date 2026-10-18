package org.b1051c.einkctl;

import android.content.SharedPreferences;
import android.service.quicksettings.TileService;

/** Colour restoration (stock B1051C 4.6.6): HWC dither 3 instead of 6 in Fast / Extreme. */
public class TileColorRestore extends TileService {
    @Override public void onStartListening() { update(); }

    @Override
    public void onClick() {
        SharedPreferences p = Eink.prefs(this);
        p.edit().putBoolean("color_restoration", !p.getBoolean("color_restoration", false)).apply();
        Eink.applyImage(this);
        update();
    }

    private void update() {
        boolean on = Eink.prefs(this).getBoolean("color_restoration", false);
        Tiles.show(this, "Color restore", on ? "on" : "off", on);
    }
}
