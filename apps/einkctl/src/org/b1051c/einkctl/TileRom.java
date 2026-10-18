package org.b1051c.einkctl;

import android.app.AlertDialog;
import android.service.quicksettings.TileService;

/** "Switch system": Android 16 <-> stock (dynamic system), via one reboot. Asks before rebooting. */
public class TileRom extends TileService {
    @Override
    public void onStartListening() {
        new Thread(() -> {
            boolean dsu = Eink.runningDsu();
            Tiles.show(this, "Switch system", dsu ? "now: stock" : "now: Android 16", false);
        }).start();
    }

    @Override
    public void onClick() {
        AlertDialog d = new AlertDialog.Builder(this)
                .setMessage("Restart into the other system?")
                .setPositiveButton("Restart", (x, w) -> Eink.bootOtherSystem())
                .setNegativeButton("Cancel", null).create();
        showDialog(d);
    }
}
