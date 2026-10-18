package org.b1051c.einkctl;

import android.content.SharedPreferences;
import android.service.quicksettings.TileService;

/** Anti-shake (Bigme's name for the HWC anti-flicker mode). */
public class TileAntiShake extends TileService {
    @Override public void onStartListening() { update(); }

    @Override
    public void onClick() {
        SharedPreferences p = Eink.prefs(this);
        p.edit().putBoolean("anti_flicker", !p.getBoolean("anti_flicker", false)).apply();
        Eink.applyImage(this);
        update();
    }

    private void update() {
        boolean on = Eink.prefs(this).getBoolean("anti_flicker", false);
        Tiles.show(this, "Anti-shake", on ? "on" : "off", on);
    }
}
