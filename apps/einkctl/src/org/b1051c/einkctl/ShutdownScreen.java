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
 *  - logo_cream: the colored logo on cream (owner 2026-10-19 evening: cream backgrounds everywhere except the
 *    charging screen; a stored "logo_white" falls back to logo_cream); black; off.
 * Under Android's Dark theme (owner 2026-10-20) the same badge drawing sits on black with a thin cream outline around
 * the silhouette (shutdown-bw-dark-0..4, optional shutdown-logo-dark); a build without those assets shows the cream
 * set. The shutdown also writes ThemeSync's boot-animation flag, in case the theme switched within its debounce.
 * The PNGs are private assets (work/bootlogo: bw/bw-step0..4 and bw-dark from make_bw_frames.py, logos with the darks
 * neutralized by work/design/neutralize_darks.py so the black cat does not turn red on Kaleido; not in the public repo).
 */
final class ShutdownScreen {
    private ShutdownScreen() {}

    static final String[] VALUES = {"anim_bw", "logo_cream", "black", "off"};
    static final String[] LABELS = {"InKognito: color to black-and-white (animation)", "InKognito logo (color)",
            "Black", "Off (keep the last screen)"};

    private static final int STEPS = 5;                  // assets shutdown-bw-4 (all colours) .. shutdown-bw-0 (pen)
    private static final long STEP_MS = 900;             // one e-ink update per colour, unhurried
    private static final long SETTLE_MS = 1500;          // 350 ms quiet + one GC16, with margin

    /** The choice is made in the Lock & Sleep app (Settings.Secure b1051c_shutdown_screen, since 0.15.11); the
     *  pref "shutdown_screen" is the older place (and an imported settings file) and still counts when the key is unset. */
    static final String KEY = "b1051c_shutdown_screen";

    static int index(Context c) {
        String v = null;
        try { v = android.provider.Settings.Secure.getString(c.getContentResolver(), KEY); } catch (SecurityException ignored) {}
        if (v == null || v.isEmpty()) v = Eink.prefs(c).getString("shutdown_screen", VALUES[0]);
        if ("logo_white".equals(v)) v = "logo_cream";
        for (int i = 0; i < VALUES.length; i++) if (VALUES[i].equals(v)) return i;
        return 0;
    }

    /** The picture set of one theme: frame prefix, logo and the letterbox around each (black only behind a dark
     *  asset: in dark the frames and the logo may come from different sets). */
    private static final class Look {
        final String bw, logo, name;
        final int bgFrames, bgLogo;

        Look(Context c, boolean dark) {
            java.util.Set<String> a = assets(c);
            boolean set = dark && hasDarkSet(a), logoDark = dark && a.contains("shutdown-logo-dark.png");
            bw = set ? "shutdown-bw-dark-" : "shutdown-bw-";
            logo = logoDark ? "shutdown-logo-dark.png" : "shutdown-cream.png";
            int cream = c.getColor(R.color.cream);
            bgFrames = set ? android.graphics.Color.BLACK : cream;
            bgLogo = logoDark ? android.graphics.Color.BLACK : cream;
            name = !dark ? "" : " (dark" + (set ? "" : ", no dark frames in this build: cream frames")
                    + (logoDark ? ", dark logo" : "") + ")";
        }
    }

    private static java.util.Set<String> assets(Context c) {
        try {
            String[] all = c.getAssets().list("");
            if (all != null) return new java.util.HashSet<>(java.util.Arrays.asList(all));
        } catch (java.io.IOException ignored) {}
        return java.util.Collections.emptySet();
    }

    private static boolean hasDarkSet(java.util.Set<String> a) {
        for (int k = 0; k < STEPS; k++) if (!a.contains("shutdown-bw-dark-" + k + ".png")) return false;
        return true;
    }

    /** All five dark frames are in this build (they come from the frame generator, not the code; may be missing). */
    static boolean hasDarkSet(Context c) { return hasDarkSet(assets(c)); }

    /** Main thread. Calls done once the picture had time to reach the panel (or right away if there is none). */
    static void show(Context c, Runnable done) {
        boolean dark = ThemeSync.night(c);
        Thread flag = ThemeSync.writeFlagIfStale(c, dark);   // read by the boot animation module at the next boot
        String mode = VALUES[index(c)];
        Look look = new Look(c, dark);
        android.view.View v = cover(c, mode, look);
        Handler h = new Handler(Looper.getMainLooper());
        if (v == null) {                                     // nothing to show: only let the flag write land first
            new Thread(() -> {
                try { flag.join(2000); } catch (InterruptedException ignored) {}
                h.post(done);
            }, "shutdown-flag").start();
            return;
        }
        if (!"anim_bw".equals(mode)) { h.postDelayed(done, 1800); return; }
        animate(c, (android.widget.ImageView) v, look, STEPS - 2, done);
    }

    /** adb: su -c am broadcast -n org.b1051c.einkctl/.ActionReceiver -a org.b1051c.einkctl.PREVIEW_SHUTDOWN
     *  [--es mode black] [--es theme light|dark] (theme: this preview only; default = the current theme). */
    static void preview(Context c, String mode, String theme) {
        String m = mode != null ? mode : VALUES[index(c)];
        boolean dark = "dark".equals(theme) || (!"light".equals(theme) && ThemeSync.night(c));
        Look look = new Look(c, dark);
        android.view.View v = cover(c, m, look);
        if (v == null) return;
        Runnable remove = () -> new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { v.getContext().getSystemService(android.view.WindowManager.class).removeView(v); } catch (RuntimeException ignored) {}
            Eink.clearScreen(c);
        }, 6000);
        if ("anim_bw".equals(m)) animate(c, (android.widget.ImageView) v, look, STEPS - 2, remove);
        else remove.run();
    }

    /** Show frame k (k = number of colours left), decode the next one meanwhile; after frame 0: GC16, then done. */
    private static void animate(Context c, android.widget.ImageView v, Look look, int k, Runnable done) {
        Handler h = new Handler(Looper.getMainLooper());
        long due = android.os.SystemClock.uptimeMillis() + STEP_MS;
        new Thread(() -> {
            Bitmap b = load(c, look.bw + k + ".png");
            h.postAtTime(() -> {
                if (b != null) v.setImageBitmap(b);
                if (k > 0) { animate(c, v, look, k - 1, done); return; }
                android.util.Log.i("einkctl", "shutdown screen: black-and-white drawing" + look.name);
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

    private static android.view.View cover(Context c, String mode, Look look) {
        android.util.Log.i("einkctl", "shutdown screen requested: " + mode);
        if ("off".equals(mode)) return null;
        Bitmap b = null;
        if (mode.startsWith("logo")) b = load(c, look.logo);
        else if ("anim_bw".equals(mode)) b = load(c, look.bw + (STEPS - 1) + ".png");
        android.view.View v = AppProfileService.coverScreen(b, mode.startsWith("logo") ? look.bgLogo : look.bgFrames);
        if (v == null) return null;
        android.util.Log.i("einkctl", "shutdown screen: " + mode + look.name);
        Eink.clearScreen(c);
        return v;
    }
}
