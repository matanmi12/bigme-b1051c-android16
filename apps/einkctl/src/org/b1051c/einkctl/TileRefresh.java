package org.b1051c.einkctl;

import android.service.quicksettings.TileService;

/** Global refresh mode (Auto, HD 256, Fast, Extreme, Regal, Hyper, A2): a tap opens a list to pick from. */
public class TileRefresh extends TileService {
    @Override public void onStartListening() { update(); }

    @Override
    public void onClick() {
        int global = Eink.normMode(Eink.prefs(this).getInt("mode", 0));
        String note = null, who = Tiles.foregroundName(this);
        if (who != null) {
            int eff = Profiles.resolve(this, AppProfileService.currentPackage()).refresh;
            if (eff != global) note = who + " keeps its own mode (" + Eink.MODE_NAMES[Eink.modeIndex(eff)]
                    + "). This choice is for all other apps.";
        }
        Tiles.choose(this, "Refresh mode", note, Eink.MODE_NAMES, Eink.modeIndex(global), i -> {
            Eink.prefs(this).edit().putInt("mode", Eink.MODES[i]).apply();
            Eink.applyRefreshMode(this);
            update();
        });
    }

    private void update() {
        int m = Eink.normMode(Eink.prefs(this).getInt("mode", 0));
        Tiles.show(this, "Refresh mode", Eink.MODE_NAMES[Eink.modeIndex(m)], m != 0);
    }
}
