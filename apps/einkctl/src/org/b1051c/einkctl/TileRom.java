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
        // TileDialog: a Service context carries no app theme, so without it this was a stock light dialog
        AlertDialog d = new AlertDialog.Builder(this, R.style.TileDialog)
                .setMessage("Restart into the other system?")
                .setPositiveButton("Restart", (x, w) -> Eink.bootOtherSystem())
                .setNegativeButton("Cancel", null).create();
        showDialog(d);                                          // shows it at once, so the message view exists
        Ink.bodyWeight(d);
    }
}
