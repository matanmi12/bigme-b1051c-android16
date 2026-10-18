package org.b1051c.einkctl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

/**
 * Picture left on the panel after power-off or during a reboot (e-ink keeps the last frame; stock showed
 * /data/xrz poweroff.png from ShutdownThread). On ACTION_SHUTDOWN (ordered, the framework waits up to 10 s; sent
 * for reboots too) the accessibility service covers the screen with the InKognito logo or black, the shim sends
 * one settled GC16, then shutdown continues. Setting "shutdown_screen": logo_cream (default) / logo_white /
 * black / off. The logo PNGs are private assets (work/bootlogo, not in the public repo).
 */
final class ShutdownScreen {
    private ShutdownScreen() {}

    static final String[] VALUES = {"logo_cream", "logo_white", "black", "off"};
    static final String[] LABELS = {"InKognito logo (cream)", "InKognito logo (white)", "Black", "Off (keep the last screen)"};

    static int index(Context c) {
        String v = Eink.prefs(c).getString("shutdown_screen", VALUES[0]);
        for (int i = 0; i < VALUES.length; i++) if (VALUES[i].equals(v)) return i;
        return 0;
    }

    /** Main thread. Calls done once the picture had time to reach the panel (or right away if there is none). */
    static void show(Context c, Runnable done) {
        if (cover(c, VALUES[index(c)]) == null) { done.run(); return; }
        new Handler(Looper.getMainLooper()).postDelayed(done, 1800);   // 350 ms quiet + GC16 commit, with margin
    }

    /** adb: am broadcast -n org.b1051c.einkctl/.ActionReceiver -a org.b1051c.einkctl.PREVIEW_SHUTDOWN [--es mode black] */
    static void preview(Context c, String mode) {
        android.view.View v = cover(c, mode != null ? mode : VALUES[index(c)]);
        if (v != null) new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { v.getContext().getSystemService(android.view.WindowManager.class).removeView(v); } catch (RuntimeException ignored) {}
            Eink.clearScreen(c);
        }, 8000);
    }

    private static android.view.View cover(Context c, String mode) {
        android.util.Log.i("einkctl", "shutdown screen requested: " + mode);
        if ("off".equals(mode)) return null;
        Bitmap b = null;
        if (mode.startsWith("logo")) {
            try (java.io.InputStream in = c.getAssets().open(mode.equals("logo_white") ? "shutdown-white.png" : "shutdown-cream.png")) {
                b = BitmapFactory.decodeStream(in);
            } catch (java.io.IOException e) {
                android.util.Log.w("einkctl", "shutdown image missing: " + e);
            }
        }
        android.view.View v = AppProfileService.coverScreen(b);
        if (v == null) return null;
        android.util.Log.i("einkctl", "shutdown screen: " + mode);
        Eink.clearScreen(c);
        return v;
    }
}
