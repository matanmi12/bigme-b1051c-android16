package org.b1051c.einkctl;

import android.content.SharedPreferences;
import android.service.quicksettings.TileService;

/** Cycles through the stock refresh modes: Auto -> HD 256 -> Fast -> Extreme -> Regal -> Hyper -> A2 -> Auto. */
public class TileRefresh extends TileService {
    private static final int[] CYCLE = Eink.MODES;

    @Override public void onStartListening() { update(); }

    @Override
    public void onClick() {
        SharedPreferences p = Eink.prefs(this);
        int cur = Eink.normMode(p.getInt("mode", 0)), next = CYCLE[0];
        for (int i = 0; i < CYCLE.length; i++) if (CYCLE[i] == cur) { next = CYCLE[(i + 1) % CYCLE.length]; break; }
        p.edit().putInt("mode", next).apply();
        Eink.applyRefreshMode(this);
        update();
    }

    private void update() {
        int m = Eink.normMode(Eink.prefs(this).getInt("mode", 0));
        Tiles.show(this, "Refresh mode", Eink.MODE_NAMES[Eink.modeIndex(m)], m != 0);
    }
}
