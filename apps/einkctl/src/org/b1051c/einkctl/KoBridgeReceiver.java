package org.b1051c.einkctl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Requests from KOReader's Bigme patch (koreader/patches/1-bigme-eink.lua, roadmap 24 + 25.1): KOReader decides
 * when a page needs a full refresh and drives the front light from its own menu. Exported on purpose (KOReader is
 * signed by its own key, so no signature permission is possible), so only harmless actions are accepted: one settled
 * GC16 (rate-limited) and a light level within the hardware range.
 *   KO_REFRESH                      -> Eink.clearScreen (the shim re-sends the final frame as GC16)
 *   KO_LIGHT  cold=0..222 warm=0..222 -> Eink.setLight (QS sliders follow)
 * KOReader only sends while it is the app in front, so requests are accepted only then (review 2026-10-21: any
 * installed app could otherwise flash the panel or turn the light on from the background). Without the accessibility
 * service there is no foreground app to compare with and the old behaviour stays. Root/adb: ActionReceiver SET_LIGHT.
 */
public class KoBridgeReceiver extends BroadcastReceiver {
    static final String REFRESH = "org.b1051c.einkctl.KO_REFRESH";
    static final String LIGHT = "org.b1051c.einkctl.KO_LIGHT";
    static final String KOREADER = "org.koreader.launcher";
    private static long lastFull;

    @Override
    public void onReceive(Context c, Intent i) {
        AppProfileService.recover(c, "KOReader bridge");   // KOReader is reading: recover from Active apps -> Stop
        // Who sent it (review 2026-10-21): the KOReader patch shares its identity (BroadcastOptions, API 34); a
        // broadcast without it, or from anyone else, is dropped. The foreground check stays as a second condition.
        String sender = null;
        try { sender = getSentFromPackage(); } catch (RuntimeException ignored) {}
        if (!KOREADER.equals(sender)) {
            android.util.Log.w("einkctl", "KOReader bridge: " + i.getAction() + " ignored, sender " + sender);
            return;
        }
        String front = AppProfileService.frontPackage();
        if (AppProfileService.running() && !KOREADER.equals(front)) {
            android.util.Log.w("einkctl", "KOReader bridge: " + i.getAction() + " ignored, " + front + " is in front");
            return;
        }
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
