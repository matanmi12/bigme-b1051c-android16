package org.b1051c.einkctl;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;

/**
 * Per-app profiles: on every foreground-window change the profile engine (Profiles) applies the app's
 * effective settings; keyboard windows switch to Fast while typing. Also hosts the refresh button in the nav-button row (NavRefresh),
 * which looks only at the nav bar window. Two system surfaces have their own profile rows (owner 2026-10-27): the
 * notification shade / quick settings while open (a large SystemUI window, not over the lock screen) and Recents
 * (the system's recents package while it is not the home app).
 */
public class AppProfileService extends AccessibilityService {
    private static volatile String current = "";
    private static volatile AppProfileService instance;
    private static volatile boolean shade;
    private NavRefresh nav;

    /** The profile key in front: the shade while it is open, Recents, or the app. */
    static String currentPackage() {
        AppProfileService s = instance;
        return shade ? Profiles.SHADE : s != null ? s.key(current) : current;
    }

    /** The profile key of the app behind the shade (the QS tiles show and set its values): Recents, or the app. */
    static String appPackage() {
        AppProfileService s = instance;
        return s != null ? s.key(current) : current;
    }

    /** The accessibility service is connected (currentPackage() is meaningful). */
    static boolean running() { return instance != null; }

    /** The app in front: the tracked one or, right after a (re)connect and before any window change, the window list. */
    static String frontPackage() {
        String c = current;
        AppProfileService s = instance;
        if (c.isEmpty() && s != null) {
            String a = s.activeApp();
            if (a != null) return a;
        }
        return c;
    }

    /** Shutdown screen: cover the whole display (system bars included) with the picture on {@code background} (the
     *  letterbox: cream, or black for the dark set), or black when null. Main thread; returns the view (the caller
     *  removes it, if ever), null when the service is not running. */
    static android.view.View coverScreen(android.graphics.Bitmap picture, int background) {
        AppProfileService s = instance;
        if (s == null) { android.util.Log.w("einkctl", "shutdown screen: accessibility service not connected"); return null; }
        android.widget.ImageView v = new android.widget.ImageView(s);
        v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        v.setBackgroundColor(picture == null ? android.graphics.Color.BLACK : background);
        if (picture != null) v.setImageBitmap(picture);
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.OPAQUE);
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.windowAnimations = 0;
        lp.setTitle("EinkShutdownScreen");
        try {
            s.getSystemService(android.view.WindowManager.class).addView(v, lp);
            return v;
        } catch (RuntimeException e) {
            android.util.Log.w("einkctl", "shutdown screen failed: " + e);
            return null;
        }
    }

    /** ThemeSync, once the theme's wallpaper is drawn: sample the refresh button's colour again. */
    static void retintNav() {
        AppProfileService s = instance;
        if (s != null && s.nav != null) s.nav.retint();
    }

    /** The notification shade / quick settings are pulled down: SystemUI's full-screen TYPE_SYSTEM window is listed
     *  and focused or active (shade open, 2026-10-20: TYPE_SYSTEM 0,0-1860,2480, focused; closed: only the status
     *  bar and taskbar strips). False when the service is not running. ThemeSync waits for this before its GC16. */
    static boolean shadeOpen() {
        AppProfileService s = instance;
        if (s == null) return false;
        try {
            int h = s.getResources().getDisplayMetrics().heightPixels;
            android.graphics.Rect r = new android.graphics.Rect();
            for (android.view.accessibility.AccessibilityWindowInfo w : s.getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM || !(w.isFocused() || w.isActive())) continue;
                w.getBoundsInScreen(r);
                if (r.height() >= h * 9 / 10) return true;
            }
        } catch (RuntimeException ignored) {}
        return false;
    }

    // ---- recovery after Android's "Stop" (review 2026-10-21) ----------------------------------------
    // Active apps -> Stop (ActivityManager.stopAppForUser) kills the process without a force-stop. The accessibility
    // framework then lists this service as crashed and never rebinds it (verified 2026-10-21: still crashed a minute
    // later), and the keeper is gone with it. Only a change of the enabled-services setting clears the crashed state,
    // so the entry points that come back on their own (the QS tiles, the settings screen, KOReader's bridge) call
    // this: when the service is enabled but not connected after a grace period, the setting is written without it and
    // then with it again, and onServiceConnected brings the keeper back. Not called from the keeper's start: at boot
    // the first bind is still in progress.
    private static long recoverAt;

    static void recover(Context c, String why) {
        if (running()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (recoverAt != 0 && now - recoverAt < 15_000) return;      // one attempt at a time
        recoverAt = now;
        Context app = c.getApplicationContext();
        android.content.ComponentName me = new android.content.ComponentName(app, AppProfileService.class);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (running()) return;
            android.content.ContentResolver r = app.getContentResolver();
            String key = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
            String cur = android.provider.Settings.Secure.getString(r, key);
            if (cur == null) return;
            StringBuilder without = new StringBuilder();
            boolean enabled = false;
            for (String e : cur.split(":")) {
                if (e.isEmpty()) continue;
                if (me.equals(android.content.ComponentName.unflattenFromString(e))) { enabled = true; continue; }
                without.append(without.length() == 0 ? "" : ":").append(e);
            }
            if (!enabled) return;                                    // off by the user's choice: leave it
            android.util.Log.w("einkctl", "accessibility service enabled but not connected (" + why + "): re-enabling it");
            try {
                android.provider.Settings.Secure.putString(r, key, without.toString());
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        () -> android.provider.Settings.Secure.putString(r, key, cur), 700);
            } catch (SecurityException e) {                          // WRITE_SECURE_SETTINGS not granted: root
                Root.async("settings put secure " + key + " '" + without + "'; sleep 1; settings put secure " + key + " '" + cur + "'");
            }
        }, 5000);
    }

    // ---- enabled? turn on (review 2026-10-21: no status, prompt or recovery for the service) ----------------
    static boolean enabledInSettings(Context c) {
        android.content.ComponentName me = new android.content.ComponentName(c, AppProfileService.class);
        String cur = android.provider.Settings.Secure.getString(c.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (cur == null) return false;
        for (String e : cur.split(":"))
            if (!e.isEmpty() && me.equals(android.content.ComponentName.unflattenFromString(e))) return true;
        return false;
    }

    /** Add this service to the enabled accessibility services (WRITE_SECURE_SETTINGS; root as the fallback), keeping
     *  the others. The system binds it within a second or two. Also the ROM provisioning's path (adb: ActionReceiver
     *  A11Y_ENABLE). */
    static void enable(Context c) {
        if (enabledInSettings(c)) return;
        android.content.ContentResolver r = c.getContentResolver();
        String key = android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String me = new android.content.ComponentName(c, AppProfileService.class).flattenToString();
        String cur = android.provider.Settings.Secure.getString(r, key);
        String next = cur == null || cur.isEmpty() ? me : cur + ":" + me;
        try {
            android.provider.Settings.Secure.putString(r, key, next);
            android.provider.Settings.Secure.putInt(r, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException e) {
            Root.async("settings put secure " + key + " '" + next + "'; settings put secure accessibility_enabled 1");
        }
        android.util.Log.i("einkctl", "accessibility service enabled in settings");
    }

    /** Re-evaluate the nav refresh button (after its setting changed). */
    static void navChanged() {
        AppProfileService s = instance;
        if (s != null && s.nav != null) s.nav.update();
    }

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo i = new AccessibilityServiceInfo();
        i.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        i.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        i.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        i.notificationTimeout = 150;
        setServiceInfo(i);
        instance = this;
        nav = new NavRefresh(this);
        nav.update();
        uiNight = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        // "Stop" in Android's Active apps ends the keeper for good (no sticky restart) and only the activity and
        // BootReceiver started it, so the front light stayed on while the screen slept (review 2026-10-21). The system
        // rebinds this service after that stop: bring the keeper back from here.
        KeeperService.ensure(this);
    }

    private int uiNight = -1;

    /** A Dark theme switch re-tints the nav glyphs without a window event: sample the refresh button's colour again. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration nc) {
        super.onConfigurationChanged(nc);
        int n = nc.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (uiNight != -1 && n != uiNight && nav != null) nav.retint();
        uiNight = n;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        nav.update();
        boolean sh = shadeOpen() && !getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();   // not the lock screen
        if (sh != shade) {
            shade = sh;
            Profiles.onForeground(this, sh ? Profiles.SHADE : key(current));
        }
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            checkIme();
            String a = activeApp();                      // events within notificationTimeout are merged:
            if (a != null && !ignored(a) && !a.equals(current)) foreground(a);   // a lost app switch shows up here
            return;
        }
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        // The event's package is only what the sender claims (review 2026-10-21: any app can emit window-state events
        // and have another app's profile applied). The active application window decides; the event is a hint.
        String active = activeApp();
        if (active != null && !ignored(active) && !active.equals(pkg)) pkg = active;
        if (ignored(pkg)) {                              // e.g. the keyboard's event replaced the app's own one
            pkg = active;
            if (pkg == null || ignored(pkg)) return;
        }
        foreground(pkg);
    }

    private void foreground(String pkg) {
        current = pkg;
        if (!shade) Profiles.onForeground(this, key(pkg));   // with the shade open, its profile stays until it closes
    }

    private String recentsPkg, homePkg;
    private long recentsAt;

    /** Recents (Launcher3 QuickStep's overview while another launcher is the home app) has its own profile row. */
    private String key(String pkg) {
        if (System.currentTimeMillis() - recentsAt > 60_000) {
            recentsAt = System.currentTimeMillis();
            android.content.res.Resources r = android.content.res.Resources.getSystem();
            int id = r.getIdentifier("config_recentsComponentName", "string", "android");
            android.content.ComponentName cn = id == 0 ? null : android.content.ComponentName.unflattenFromString(r.getString(id));
            recentsPkg = cn == null ? null : cn.getPackageName();
            android.content.pm.ResolveInfo h = getPackageManager().resolveActivity(new android.content.Intent(
                    android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            homePkg = h == null || h.activityInfo == null ? null : h.activityInfo.packageName;
        }
        return pkg != null && pkg.equals(recentsPkg) && !pkg.equals(homePkg) ? Profiles.RECENTS : pkg;
    }

    /** Ignore transient system surfaces (a pulled-down shade must not flip the mode of the app below it), our own
     *  settings screen and keyboards (handled by the Fast-while-typing rule). */
    private boolean ignored(String pkg) {
        return pkg.equals("com.android.systemui") || pkg.equals(getPackageName()) || pkg.equals("android") || isKeyboard(pkg);
    }

    /** Package of the active application window, straight from the window list. */
    private String activeApp() {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION || !(w.isActive() || w.isFocused())) continue;
                android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null) return r.getPackageName().toString();
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    private java.util.Set<String> keyboards;
    private long keyboardsAt;

    private boolean isKeyboard(String pkg) {
        if (keyboards == null || System.currentTimeMillis() - keyboardsAt > 60_000) {
            java.util.Set<String> s = new java.util.HashSet<>();
            android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
            for (android.view.inputmethod.InputMethodInfo i : imm.getInputMethodList()) s.add(i.getPackageName());
            keyboards = s;
            keyboardsAt = System.currentTimeMillis();
        }
        return keyboards.contains(pkg);
    }

    /** Keyboard window shown/hidden -> Fast while typing (decision 2.3 B), and its area for the shim's keyboard modes. */
    private void checkIme() {
        boolean ime = false;
        android.graphics.Rect b = new android.graphics.Rect();
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows())
                if (w.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    ime = true;
                    w.getBoundsInScreen(b);
                    break;
                }
        } catch (RuntimeException ignored) {}
        Profiles.setIme(this, ime);
        publishImeRect(ime ? b : null);
    }

    private String imeRect = "";

    /** The keyboard window for the HWC shim (owner 2026-11-11, ALPHA7-PLAN 3.4): "l,t,r,b,rotation" in display pixels,
     *  "0" when no keyboard is shown. The shim redraws keyboard-only changes as that area alone (Fast while typing,
     *  A2 while a swipe trail runs) and cleans the area once after a pause. Sent only when it changes. */
    private void publishImeRect(android.graphics.Rect b) {
        int rot = 0;
        try { rot = getSystemService(android.view.WindowManager.class).getDefaultDisplay().getRotation(); } catch (RuntimeException ignored) {}
        String v = b == null || b.isEmpty() ? "0" : b.left + "," + b.top + "," + b.right + "," + b.bottom + "," + rot;
        if (v.equals(imeRect)) return;
        imeRect = v;
        android.util.Log.d("einkctl", "keyboard area " + v);
        Root.async("setprop vendor.debug.hwc.shim.ime_rect " + v);
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (nav != null) nav.destroy();
        instance = null;
        super.onDestroy();
    }
}
