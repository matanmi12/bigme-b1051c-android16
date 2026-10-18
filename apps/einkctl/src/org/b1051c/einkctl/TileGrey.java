package org.b1051c.einkctl;

import android.provider.Settings;
import android.service.quicksettings.TileService;

/** Greyscale = Android's colour correction in monochromacy mode (what the stock Control Center used). */
public class TileGrey extends TileService {
    private static final String ENABLED = "accessibility_display_daltonizer_enabled";
    private static final String MODE = "accessibility_display_daltonizer";

    @Override public void onStartListening() { update(); }

    @Override
    public void onClick() {
        boolean on = !isOn();
        try {
            Settings.Secure.putInt(getContentResolver(), MODE, 0);
            Settings.Secure.putInt(getContentResolver(), ENABLED, on ? 1 : 0);
        } catch (SecurityException e) {              // WRITE_SECURE_SETTINGS not granted: go through root
            Root.async("settings put secure " + MODE + " 0; settings put secure " + ENABLED + " " + (on ? 1 : 0));
        }
        Tiles.show(this, "Grayscale", on ? "on" : "off", on);
    }

    private boolean isOn() {
        return Settings.Secure.getInt(getContentResolver(), ENABLED, 0) == 1
                && Settings.Secure.getInt(getContentResolver(), MODE, -1) == 0;
    }

    private void update() { boolean on = isOn(); Tiles.show(this, "Grayscale", on ? "on" : "off", on); }
}
