package org.b1051c.einkctl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Requests from KOReader's Bigme patch (koreader/patches/1-bigme-eink.lua, roadmap 24 + 25.1): KOReader decides
 * when a page needs a full refresh and drives the front light from its own menu. Exported on purpose, so only
 * harmless actions are accepted: one settled GC16 (rate-limited) and a light level within the hardware range.
 *   KO_REFRESH                      -> Eink.clearScreen (the shim re-sends the final frame as GC16)
 *   KO_LIGHT  cold=0..222 warm=0..222 -> Eink.setLight (QS sliders follow)
 */
public class KoBridgeReceiver extends BroadcastReceiver {
    static final String REFRESH = "org.b1051c.einkctl.KO_REFRESH";
    static final String LIGHT = "org.b1051c.einkctl.KO_LIGHT";
    private static long lastFull;

    @Override
    public void onReceive(Context c, Intent i) {
        if (REFRESH.equals(i.getAction())) {
            long now = SystemClock.uptimeMillis();
            if (now - lastFull < 400) return;
            lastFull = now;
            Eink.clearScreen(c);
        } else if (LIGHT.equals(i.getAction())) {
            int cold = i.getIntExtra("cold", -1), warm = i.getIntExtra("warm", -1);
            if (cold >= 0 && warm >= 0) Eink.setLight(c, cold, warm, true);
        }
    }
}
