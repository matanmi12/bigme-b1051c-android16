package org.b1051c.l3;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * Transparent taskbar (3-button nav row) on the home screen when the home app is not Launcher3 itself (owner
 * 2026-10-19, Lawnchair as home). Launcher3 only clears the taskbar background through LauncherTaskbarUIController,
 * i.e. on its own home screen; with another home app it uses the bare TaskbarUIController and paints the background
 * everywhere. One smali line in TaskbarDragLayerController.updateBackgroundAlpha() routes the computed alpha through
 * {@link #alpha}; this class tracks whether the default home app's task is on top and re-runs that method when it
 * changes. Settings.Secure b1051c_taskbar_home_bg: 1 (default) = transparent on home, 0 = stock behaviour.
 * Runs on the main thread only (TaskStackChangeListeners dispatches there; the hook is called from the UI thread).
 */
public final class HomeBg {
    private static final String TAG = "B1051cHomeBg";
    private static final String KEY = "b1051c_taskbar_home_bg";

    private static final WeakHashMap<Object, Boolean> CONTROLLERS = new WeakHashMap<>();
    private static Context context;
    private static boolean registered, enabled = true, homeOnTop;
    private static Method update;

    private HomeBg() {}

    /** The hook: the background alpha the controller is about to apply, 0 while the home app is on top. */
    public static float alpha(Object controller, float alpha) {
        try {
            if (!CONTROLLERS.containsKey(controller)) track(controller);
            return enabled && homeOnTop ? 0f : alpha;
        } catch (Throwable t) {
            Log.w(TAG, "hook failed, keeping stock alpha", t);
            return alpha;
        }
    }

    private static void track(Object controller) throws ReflectiveOperationException {
        CONTROLLERS.put(controller, Boolean.TRUE);
        if (registered) return;
        registered = true;
        Field f = controller.getClass().getDeclaredField("mActivity");
        f.setAccessible(true);
        context = ((Context) f.get(controller)).getApplicationContext();
        update = controller.getClass().getDeclaredMethod("updateBackgroundAlpha");
        update.setAccessible(true);
        Handler main = new Handler(Looper.getMainLooper());
        context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(KEY), false,
                new ContentObserver(main) {
                    @Override public void onChange(boolean self) { refresh(currentTop()); }
                });
        TaskStackChangeListeners.getInstance().registerTaskStackListener(new TaskStackChangeListener() {
            @Override public void onTaskMovedToFront(ActivityManager.RunningTaskInfo info) { refresh(info); }
            @Override public void onTaskStackChanged() { refresh(currentTop()); }
        });
        // first answer right away; the controller re-reads it in the updateBackgroundAlpha() that follows
        enabled = Settings.Secure.getInt(context.getContentResolver(), KEY, 1) != 0;
        homeOnTop = isHome(currentTop());
        Log.i(TAG, "tracking taskbar background; home on top=" + homeOnTop + " enabled=" + enabled);
    }

    private static ActivityManager.RunningTaskInfo currentTop() {
        try {
            return ActivityManagerWrapper.getInstance().getRunningTask();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isHome(ActivityManager.RunningTaskInfo info) {
        if (info == null || context == null) return false;
        ComponentName top = info.topActivity != null ? info.topActivity : info.baseActivity;
        if (top == null) return false;
        ResolveInfo ri = context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        String home = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : null;
        // without a default home the resolver ("android") answers: then nothing counts as home
        return home != null && !"android".equals(home) && home.equals(top.getPackageName())
                && !"com.android.launcher3".equals(home);       // Launcher3 as home handles this itself
    }

    private static void refresh(ActivityManager.RunningTaskInfo top) {
        boolean wasEnabled = enabled, wasHome = homeOnTop;
        enabled = Settings.Secure.getInt(context.getContentResolver(), KEY, 1) != 0;
        homeOnTop = isHome(top);
        if (enabled == wasEnabled && homeOnTop == wasHome) return;
        Log.i(TAG, "home on top=" + homeOnTop + " enabled=" + enabled);
        for (Object c : CONTROLLERS.keySet().toArray()) {
            try {
                update.invoke(c);
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w(TAG, "could not refresh a taskbar", e);
            }
        }
    }
}
