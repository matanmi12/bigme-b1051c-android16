package org.b1051c.einkctl;

import android.service.quicksettings.TileService;

/** Cycles the colour profiles: Default -> Magazine -> Comic -> Video -> Custom -> Default. */
public class TileColour extends TileService {
    @Override public void onStartListening() { update(); }

    @Override
    public void onClick() {
        int next = (Eink.clamp(Eink.prefs(this).getInt("profile", 0), 0, Eink.PROFILE_NAMES.length - 1) + 1)
                % Eink.PROFILE_NAMES.length;
        Eink.selectProfile(this, next);
        update();
    }

    private void update() {
        int p = Eink.clamp(Eink.prefs(this).getInt("profile", 0), 0, Eink.PROFILE_NAMES.length - 1);
        Tiles.show(this, "Colour", Eink.PROFILE_NAMES[p], p != 0);
    }
}
