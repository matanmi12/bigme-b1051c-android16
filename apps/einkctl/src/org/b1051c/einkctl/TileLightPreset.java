package org.b1051c.einkctl;

import android.service.quicksettings.TileService;

/** Front-light preset (Morning, Night, Bed, Color, Custom): a tap opens a list to pick from, like Color. */
public class TileLightPreset extends TileService {
    @Override public void onStartListening() { update(); }

    @Override
    public void onClick() {
        Tiles.choose(this, "Light preset", null, Eink.LIGHT_NAMES, Eink.lightPreset(this), i -> {
            Eink.selectLight(this, i);
            update();
        });
    }

    private void update() {
        boolean on = Eink.prefs(this).getBoolean("light_on", false);
        Tiles.show(this, "Light preset", Eink.LIGHT_NAMES[Eink.lightPreset(this)], on);
    }
}
