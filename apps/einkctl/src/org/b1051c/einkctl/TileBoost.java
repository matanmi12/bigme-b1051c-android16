package org.b1051c.einkctl;

import android.service.quicksettings.TileService;

/** Color boost (the shim's OKLCh chroma stage, ok_sat) for every app without its own: a tap opens the steps. */
public class TileBoost extends TileService {
    @Override public void onStartListening() { update(); }

    @Override
    public void onClick() {
        Object own = Tiles.foregroundField(this, Profiles.F_SAT);
        String note = own == null ? null : Tiles.foregroundName(this) + " keeps its own boost (" + own
                + " %). This choice is for all other apps.";
        Tiles.choose(this, "Color boost", note, Eink.SAT_NAMES, Eink.stepIndex(Eink.SAT_VALUES, current()), i -> {
            Eink.prefs(this).edit().putInt("ok_sat", Eink.SAT_VALUES[i]).apply();
            Profiles.apply(this, AppProfileService.currentPackage(), false);
            update();
        });
    }

    private int current() { return Eink.prefs(this).getInt("ok_sat", Eink.OK_SAT); }

    private void update() {
        int v = current(), i = Eink.stepIndex(Eink.SAT_VALUES, v);
        Tiles.show(this, "Color boost", i >= 0 ? Eink.SAT_NAMES[i] : v + " %", v != 0);
    }
}
