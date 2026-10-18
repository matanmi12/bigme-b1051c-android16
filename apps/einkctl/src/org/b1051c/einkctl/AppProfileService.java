package org.b1051c.einkctl;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;

/**
 * Per-app profiles: on every foreground-window change the profile engine (Profiles) applies the app's
 * effective settings; keyboard windows switch to Fast while typing. Also hosts the refresh button in the nav-button row (NavRefresh),
 * which looks only at the nav bar window.
 */
public class AppProfileService extends AccessibilityService {
    private static volatile String current = "";
    private static volatile AppProfileService instance;
    private NavRefresh nav;

    static String currentPackage() { return current; }

    /** Shutdown screen: cover the whole display (system bars included) with the picture, or black when null.
     *  Main thread; returns the view (the caller removes it, if ever), null when the service is not running. */
    static android.view.View coverScreen(android.graphics.Bitmap picture) {
        AppProfileService s = instance;
        if (s == null) { android.util.Log.w("einkctl", "shutdown screen: accessibility service not connected"); return null; }
        android.widget.ImageView v = new android.widget.ImageView(s);
        v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        v.setBackgroundColor(picture == null ? android.graphics.Color.BLACK : android.graphics.Color.WHITE);
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
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        nav.update();
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            checkIme();
            String a = activeApp();                      // events within notificationTimeout are merged:
            if (a != null && !ignored(a) && !a.equals(current)) foreground(a);   // a lost app switch shows up here
            return;
        }
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (ignored(pkg)) {                              // e.g. the keyboard's event replaced the app's own one
            pkg = activeApp();
            if (pkg == null || ignored(pkg)) return;
        }
        foreground(pkg);
    }

    private void foreground(String pkg) {
        current = pkg;
        Profiles.onForeground(this, pkg);
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

    /** Keyboard window shown/hidden -> Fast while typing (decision 2.3 B). */
    private void checkIme() {
        boolean ime = false;
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows())
                if (w.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) { ime = true; break; }
        } catch (RuntimeException ignored) {}
        Profiles.setIme(this, ime);
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (nav != null) nav.destroy();
        instance = null;
        super.onDestroy();
    }
}
