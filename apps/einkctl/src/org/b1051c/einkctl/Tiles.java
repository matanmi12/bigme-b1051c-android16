package org.b1051c.einkctl;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tiles. Each is its own small TileService (Android binds one service per tile). */
final class Tiles {
    private Tiles() {}

    static void show(TileService s, String label, String sub, boolean active) {
        Tile t = s.getQsTile();
        if (t == null) return;
        t.setLabel(label);
        t.setSubtitle(sub);
        t.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }
}
