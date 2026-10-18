package org.b1051c.einkctl;

import android.service.quicksettings.TileService;

/** Global colour profile (Default, Magazine, Comic, Video, Custom): a tap opens a list to pick from. */
public class TileColour extends TileService {
    @Override public void onStartListening() { update(); }

    @Override
    public void onClick() {
        String own = Tiles.foregroundPreset(this), note = null;
        if (own != null) note = Tiles.foregroundName(this) + " keeps its own colors (" + own
                + "). This choice is for all other apps.";
        Tiles.choose(this, "Color", note, Eink.PROFILE_NAMES, current(), i -> {
            Eink.selectProfile(this, i);
            update();
        });
    }

    private int current() { return Eink.clamp(Eink.prefs(this).getInt("profile", 0), 0, Eink.PROFILE_NAMES.length - 1); }

    private void update() {
        int p = current();
        Tiles.show(this, "Color", Eink.PROFILE_NAMES[p], p != 0);
    }
}
