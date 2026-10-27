package org.b1051c.einkctl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Maintenance actions sent over adb with an explicit component (the receiver is not exported). */
public class ActionReceiver extends BroadcastReceiver {
    static final String WHITE_WALLPAPER = "org.b1051c.einkctl.WHITE_WALLPAPER";
    /** --es pkg <package> [--ei density 640]: save the launcher icon(s) as files/icon_<pkg>_<n>.png */
    static final String DUMP_ICON = "org.b1051c.einkctl.DUMP_ICON";

    /** Render every launchable app's icon inside the Bigme squircle + ring: files/frames/*.png + index.json (icon pack). */
    static final String RENDER_FRAMES = "org.b1051c.einkctl.RENDER_FRAMES";
    /** Settings + profiles <-> files/einkctl-settings.json in the app's external dir (layer backup / restore). */
    static final String EXPORT = "org.b1051c.einkctl.EXPORT", IMPORT = "org.b1051c.einkctl.IMPORT";
    /** Show the shutdown screen for 8 s without shutting down (extra "mode" overrides the setting once,
     *  --es theme light|dark the Dark theme). */
    static final String PREVIEW_SHUTDOWN = "org.b1051c.einkctl.PREVIEW_SHUTDOWN";
    /** --es file <name in the app's external files dir> [--es which home|lock|both]: set that image as wallpaper. */
    static final String SET_WALLPAPER = "org.b1051c.einkctl.SET_WALLPAPER";
    /** Dark theme sync now [--ez force true] [--es light FILE] [--es dark FILE] (wallpaper names in the external
     *  files dir, stored); --ez dry true only reports the state (logcat einkctl and the broadcast's result data). */
    static final String THEME_SYNC = "org.b1051c.einkctl.THEME_SYNC";
    /** --ei cold N --ei warm N (0..222): set the front light, like the QS sliders (root/adb; KOReader uses KO_LIGHT). */
    static final String SET_LIGHT = "org.b1051c.einkctl.SET_LIGHT";
    /** Check the E-Ink SystemUI plugin's component state now (KeeperService.checkPlugin); --ez reset true forgets
     *  this boot's re-enable, so the guard can be exercised again in a test. */
    static final String PLUGIN_CHECK = "org.b1051c.einkctl.PLUGIN_CHECK";
    /** Add the accessibility service to the enabled ones (keeps the others); for adb and the ROM provisioning. */
    static final String A11Y_ENABLE = "org.b1051c.einkctl.A11Y_ENABLE";
    /** --es name Default|Magazine|Comic|Video|Custom: select the global color preset, like the radio buttons. */
    static final String SET_PROFILE = "org.b1051c.einkctl.SET_PROFILE";
    /** --es name Morning|Night|Bed|Color|Custom: select a front-light preset, like the tile. */
    static final String SET_LIGHT_PRESET = "org.b1051c.einkctl.SET_LIGHT_PRESET";

    @Override
    public void onReceive(Context c, Intent i) {
        if (PLUGIN_CHECK.equals(i.getAction())) {
            if (i.getBooleanExtra("reset", false)) Eink.prefs(c).edit().remove(KeeperService.PLUGIN_BOOT).apply();
            KeeperService.checkPlugin(c, "adb");
        } else if (A11Y_ENABLE.equals(i.getAction())) {
            AppProfileService.enable(c);
        } else if (SET_LIGHT_PRESET.equals(i.getAction())) {
            String name = i.getStringExtra("name");
            for (int k = 0; k < Eink.LIGHT_NAMES.length; k++)
                if (Eink.LIGHT_NAMES[k].equals(name)) { Eink.selectLight(c, k); return; }
            android.util.Log.w("einkctl", "SET_LIGHT_PRESET: no preset named " + name);
        } else if (SET_PROFILE.equals(i.getAction())) {
            String name = i.getStringExtra("name");
            for (int k = 0; k < Eink.PROFILE_NAMES.length; k++)
                if (Eink.PROFILE_NAMES[k].equals(name)) { Eink.selectProfile(c, k); return; }
            android.util.Log.w("einkctl", "SET_PROFILE: no preset named " + name);
        } else if (EXPORT.equals(i.getAction()) || IMPORT.equals(i.getAction())) {
            boolean exp = EXPORT.equals(i.getAction());
            PendingResult r = goAsync();
            new Thread(() -> {
                try { android.util.Log.i("einkctl", exp ? Eink.exportSettings(c) : Eink.importSettings(c)); } finally { r.finish(); }
            }).start();
        } else if (RENDER_FRAMES.equals(i.getAction())) {
            int size = i.getIntExtra("size", 192);
            PendingResult r = goAsync();
            new Thread(() -> {
                try { IconFrames.renderAll(c, size); } finally { r.finish(); }
            }).start();
        } else if (PREVIEW_SHUTDOWN.equals(i.getAction())) {
            ShutdownScreen.preview(c, i.getStringExtra("mode"), i.getStringExtra("theme"));
        } else if (SET_WALLPAPER.equals(i.getAction())) {
            String file = i.getStringExtra("file"), which = i.getStringExtra("which");
            PendingResult r = goAsync();
            new Thread(() -> {
                try { Eink.setWallpaper(c, file, which); } finally { r.finish(); }
            }).start();
        } else if (THEME_SYNC.equals(i.getAction())) {
            boolean dry = i.getBooleanExtra("dry", false), force = i.getBooleanExtra("force", false);
            String light = i.getStringExtra("light"), dark = i.getStringExtra("dark");
            PendingResult r = goAsync();
            new Thread(() -> {
                try {
                    if (light != null || dark != null) ThemeSync.setFiles(c, light, dark);
                    if (dry) {
                        String st = ThemeSync.status(c);
                        android.util.Log.i("einkctl", "theme sync status: " + st);
                        r.setResultData(st);                 // am broadcast prints it as data="…"
                    } else {
                        ThemeSync.request(c, "adb", force);
                    }
                } finally { r.finish(); }
            }).start();
        } else if (SET_LIGHT.equals(i.getAction())) {
            int cold = i.getIntExtra("cold", -1), warm = i.getIntExtra("warm", -1);
            if (cold >= 0 && warm >= 0) Eink.setLight(c, cold, warm, true);
        } else if (WHITE_WALLPAPER.equals(i.getAction())) {
            PendingResult r = goAsync();
            new Thread(() -> {
                try { Eink.whiteWallpaper(c); } finally { r.finish(); }
            }).start();
        } else if (DUMP_ICON.equals(i.getAction())) {
            String pkg = i.getStringExtra("pkg");
            int density = i.getIntExtra("density", 640);
            PendingResult r = goAsync();
            new Thread(() -> {
                try { dumpIcon(c, pkg, density); } finally { r.finish(); }
            }).start();
        }
    }

    /** The icon exactly as Launcher3 loads it (LauncherActivityInfo.getIcon at the launcher's icon density). */
    private static void dumpIcon(Context c, String pkg, int density) {
        android.content.pm.LauncherApps la = c.getSystemService(android.content.pm.LauncherApps.class);
        int n = 0;
        for (android.content.pm.LauncherActivityInfo ai : la.getActivityList(pkg, android.os.Process.myUserHandle())) {
            android.graphics.drawable.Drawable d = ai.getIcon(density);
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 256, 256);
            d.draw(new android.graphics.Canvas(b));
            java.io.File f = new java.io.File(c.getFilesDir(), "icon_" + pkg + "_" + n++ + ".png");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
            } catch (java.io.IOException e) {
                android.util.Log.w("einkctl", "icon dump failed: " + e);
            }
            android.util.Log.i("einkctl", "icon " + ai.getComponentName().flattenToShortString() + " res=0x"
                    + Integer.toHexString(ai.getActivityInfo().getIconResource()) + " " + d.getClass().getSimpleName() + " -> " + f);
        }
        if (n == 0) android.util.Log.w("einkctl", "icon dump: no launcher activity visible for " + pkg);
    }
}
