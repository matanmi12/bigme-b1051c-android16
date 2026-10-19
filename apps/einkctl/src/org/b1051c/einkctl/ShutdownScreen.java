package org.b1051c.einkctl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

/**
 * Picture left on the panel after power-off or during a reboot (e-ink keeps the last frame; stock showed
 * /data/xrz poweroff.png from ShutdownThread). On ACTION_SHUTDOWN (ordered, the framework waits up to 10 s; sent
 * for reboots too) the accessibility service covers the screen, the shim sends one settled GC16, then shutdown
 * continues. Setting "shutdown_screen":
 *  - anim_bw (default, owner 2026-10-19): the boot animation backwards - the coloured InKognito badge loses bordeaux,
 *    teal, steel and gold one per ~0.9 s and the black-pen drawing stays (the LK logo shows the same drawing at
 *    power-on, then the boot animation colours it in again);
 *  - logo_cream / logo_white: the coloured logo; black; off.
 * The PNGs are private assets (work/bootlogo: bw/bw-step0..4 from make_bw_frames.py, logos with the darks
 * neutralized by work/design/neutralize_darks.py so the black cat does not turn red on Kaleido; not in the public repo).
 */
final class ShutdownScreen {
    private ShutdownScreen() {}

    static final String[] VALUES = {"anim_bw", "logo_cream", "logo_white", "black", "off"};
    static final String[] LABELS = {"InKognito: color to black-and-white (animation)", "InKognito logo (cream)",
            "InKognito logo (white)", "Black", "Off (keep the last screen)"};

    private static final int STEPS = 5;                  // assets shutdown-bw-4 (all colours) .. shutdown-bw-0 (pen)
    private static final long STEP_MS = 900;             // one e-ink update per colour, unhurried
    private static final long SETTLE_MS = 1500;          // 350 ms quiet + one GC16, with margin

    static int index(Context c) {
        String v = Eink.prefs(c).getString("shutdown_screen", VALUES[0]);
        for (int i = 0; i < VALUES.length; i++) if (VALUES[i].equals(v)) return i;
        return 0;
    }

    /** Main thread. Calls done once the picture had time to reach the panel (or right away if there is none). */
    static void show(Context c, Runnable done) {
        String mode = VALUES[index(c)];
        android.view.View v = cover(c, mode);
        if (v == null) { done.run(); return; }
        Handler h = new Handler(Looper.getMainLooper());
        if (!"anim_bw".equals(mode)) { h.postDelayed(done, 1800); return; }
        animate(c, (android.widget.ImageView) v, STEPS - 2, done);
    }

    /** adb: su -c am broadcast -n org.b1051c.einkctl/.ActionReceiver -a org.b1051c.einkctl.PREVIEW_SHUTDOWN [--es mode black] */
    static void preview(Context c, String mode) {
        String m = mode != null ? mode : VALUES[index(c)];
        android.view.View v = cover(c, m);
        if (v == null) return;
        Runnable remove = () -> new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { v.getContext().getSystemService(android.view.WindowManager.class).removeView(v); } catch (RuntimeException ignored) {}
            Eink.clearScreen(c);
        }, 6000);
        if ("anim_bw".equals(m)) animate(c, (android.widget.ImageView) v, STEPS - 2, remove);
        else remove.run();
    }

    /** Show frame k (k = number of colours left), decode the next one meanwhile; after frame 0: GC16, then done. */
    private static void animate(Context c, android.widget.ImageView v, int k, Runnable done) {
        Handler h = new Handler(Looper.getMainLooper());
        long due = android.os.SystemClock.uptimeMillis() + STEP_MS;
        new Thread(() -> {
            Bitmap b = load(c, "shutdown-bw-" + k + ".png");
            h.postAtTime(() -> {
                if (b != null) v.setImageBitmap(b);
                if (k > 0) { animate(c, v, k - 1, done); return; }
                android.util.Log.i("einkctl", "shutdown screen: black-and-white drawing");
                Eink.clearScreen(c);
                h.postDelayed(done, SETTLE_MS);
            }, due);
        }, "shutdown-anim").start();
    }

    private static Bitmap load(Context c, String name) {
        try (java.io.InputStream in = c.getAssets().open(name)) {
            return BitmapFactory.decodeStream(in);
        } catch (java.io.IOException e) {
            android.util.Log.w("einkctl", "shutdown image missing: " + e);
            return null;
        }
    }

    private static android.view.View cover(Context c, String mode) {
        android.util.Log.i("einkctl", "shutdown screen requested: " + mode);
        if ("off".equals(mode)) return null;
        Bitmap b = null;
        if (mode.startsWith("logo")) b = load(c, mode.equals("logo_white") ? "shutdown-white.png" : "shutdown-cream.png");
        else if ("anim_bw".equals(mode)) b = load(c, "shutdown-bw-" + (STEPS - 1) + ".png");
        android.view.View v = AppProfileService.coverScreen(b);
        if (v == null) return null;
        android.util.Log.i("einkctl", "shutdown screen: " + mode);
        Eink.clearScreen(c);
        return v;
    }
}
