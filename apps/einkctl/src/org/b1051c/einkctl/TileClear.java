package org.b1051c.einkctl;

import android.service.quicksettings.TileService;

public class TileClear extends TileService {
    @Override public void onStartListening() { Tiles.show(this, "Clear screen", "full refresh", false); }

    @Override public void onClick() { Eink.clearScreen(this); }
}
