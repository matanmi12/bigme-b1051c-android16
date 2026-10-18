package org.b1051c.einkctl;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;

/**
 * Per-app refresh profiles: on every foreground-window change, apply the app's saved refresh mode
 * (or the global one when the app has none). Also hosts the refresh button in the nav-button row (NavRefresh),
 * which looks only at the nav bar window.
 */
public class AppProfileService extends AccessibilityService {
    private static volatile String current = "";
    private static volatile AppProfileService instance;
    private int applied = Integer.MIN_VALUE;
    private NavRefresh nav;

    static String currentPackage() { return current; }

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
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        // Ignore transient system surfaces so a pulled-down shade doesn't flip the mode of the app below it.
        if (pkg.equals("com.android.systemui") || pkg.equals(getPackageName()) || pkg.equals("android")) return;
        current = pkg;
        int mode = Eink.modeFor(this, pkg);
        if (mode != applied) {
            applied = mode;
            Root.async(Eink.modeCmd(mode));
        }
    }

    @Override public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (nav != null) nav.destroy();
        instance = null;
        super.onDestroy();
    }
}
