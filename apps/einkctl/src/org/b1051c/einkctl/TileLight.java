package org.b1051c.einkctl;

import android.content.SharedPreferences;
import android.service.quicksettings.TileService;

public class TileLight extends TileService {
    @Override public void onStartListening() { update(); }

    @Override
    public void onClick() {
        SharedPreferences p = Eink.prefs(this);
        p.edit().putBoolean("light_on", !p.getBoolean("light_on", false)).apply();
        Eink.applyFrontlight(this);
        update();
    }

    private void update() {
        SharedPreferences p = Eink.prefs(this);
        boolean on = p.getBoolean("light_on", false);
        Tiles.show(this, "Front light", on ? ("cold " + p.getInt("cold", 40) + " / warm " + p.getInt("warm", 40)) : "off", on);
    }
}
