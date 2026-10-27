package org.b1051c.einkctl;

import android.service.quicksettings.TileService;

/** Darker text (the shim's input black point, level_black) for every app without its own: a tap opens the steps. */
public class TileText extends TileService {
    @Override public void onStartListening() { update(); }

    @Override
    public void onClick() {
        Object own = Tiles.foregroundField(this, Profiles.F_BLACK);
        String note = own == null ? null : Tiles.foregroundName(this) + " keeps its own text setting (" + own
                + "). This choice is for all other apps.";
        Tiles.choose(this, "Darker text", note, Eink.TEXT_NAMES, Eink.stepIndex(Eink.TEXT_VALUES, current()), i -> {
            Eink.prefs(this).edit().putInt("level_black", Eink.TEXT_VALUES[i]).apply();
            Profiles.apply(this, AppProfileService.currentPackage(), false);
            update();
        });
    }

    private int current() { return Eink.prefs(this).getInt("level_black", 0); }

    private void update() {
        int v = current(), i = Eink.stepIndex(Eink.TEXT_VALUES, v);
        Tiles.show(this, "Darker text", i >= 0 ? Eink.TEXT_NAMES[i] : String.valueOf(v), v != 0);
    }
}
