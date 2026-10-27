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
 *
 * <p>Recents stash fix (2026-10-20). While Launcher3's fallback RecentsActivity exists, FallbackTaskbarUIController
 * drives the taskbar and, on every Recents state change that runs while the third-party home OR Recents is the top
 * task, sets TaskbarStashController FLAG_IN_STASHED_LAUNCHER_STATE (taskbar icons, incl. all apps, hidden). The last
 * change after Recents -> Home is RecentsActivity.onStop -> moveToRestState -> BG_LAUNCHER, which runs with Lawnchair
 * already on top; nothing re-evaluates the flag afterwards (3-button nav has no gesture end), so the icons stay hidden
 * on home and in every app until the process restarts. Second smali hook: createAnimToRecentsState() passes its stock
 * answer through {@link #stashLauncherState}, which keeps the stash only for Recents states that show the Recents view
 * (DEFAULT, MODAL, BACKGROUND_APP, SPLIT_SELECT), not for HOME / BG_LAUNCHER. Backup: {@link #unstick} clears a
 * leftover stash on any task change whose top task is not Recents. Settings.Secure b1051c_taskbar_unstash: 1 (default)
 * = fix, 0 = stock behaviour.
 *
 * <p>Runs on the main thread only (TaskStackChangeListeners dispatches there; both hooks are called from the UI thread).
 */
public final class HomeBg {
    private static final String TAG = "B1051cHomeBg";
    private static final String KEY = "b1051c_taskbar_home_bg";
    private static final String UNSTASH_KEY = "b1051c_taskbar_unstash";
    private static final String RECENTS_ACTIVITY = "com.android.quickstep.RecentsActivity";
    // TaskbarStashController flags (A16 Launcher3QuickStep, getStateString)
    private static final long FLAG_IN_APP = 1L, FLAG_IN_STASHED_LAUNCHER_STATE = 16L;

    private static final WeakHashMap<Object, Boolean> CONTROLLERS = new WeakHashMap<>();
    private static Context context;
    private static boolean registered, enabled = true, homeOnTop;
    private static Method update;
    // unstick(): TaskbarDragLayerController.mControllers -> TaskbarControllers fields -> TaskbarStashController methods
    private static Field fControllers, fStash, fUi, fTaskbarContext;
    private static Method mUpdateFlag, mApplyState, mIsDestroyed, mRecentsVisible;

    private HomeBg() {}

    /** Hook 1: the background alpha the controller is about to apply, 0 while the home app is on top. */
    public static float alpha(Object controller, float alpha) {
        try {
            if (!CONTROLLERS.containsKey(controller)) track(controller);
            return enabled && homeOnTop ? 0f : alpha;
        } catch (Throwable t) {
            Log.w(TAG, "hook failed, keeping stock alpha", t);
            return alpha;
        }
    }

    /**
     * Hook 2, FallbackTaskbarUIController.createAnimToRecentsState(state): {@code stash} is the stock "stash like a
     * launcher" answer (third-party home or Recents on top); kept only while {@code state} shows the Recents view.
     */
    public static boolean stashLauncherState(Object state, boolean stash) {
        try {
            if (!stash || !unstashEnabled()) return stash;
            if (mRecentsVisible == null) mRecentsVisible = state.getClass().getMethod("isRecentsViewVisible");
            if ((Boolean) mRecentsVisible.invoke(state)) return true;
            Log.i(TAG, "Recents state " + state + ": taskbar stays in the app state");
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "stash hook failed, keeping stock value", t);
            return stash;
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
        try {
            fControllers = controller.getClass().getDeclaredField("mControllers");
            fControllers.setAccessible(true);
            Class<?> controllers = fControllers.getType();
            fStash = controllers.getField("taskbarStashController");
            fUi = controllers.getField("uiController");
            fTaskbarContext = controllers.getField("taskbarActivityContext");
            mUpdateFlag = fStash.getType().getMethod("updateStateForFlag", long.class, boolean.class);
            mApplyState = fStash.getType().getMethod("applyState");
            mIsDestroyed = fTaskbarContext.getType().getMethod("isDestroyed");
        } catch (ReflectiveOperationException | RuntimeException e) {
            fControllers = null;                                  // unstick() off, everything else unchanged
            Log.w(TAG, "taskbar stash backup unavailable", e);
        }
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

    private static ComponentName topComponent(ActivityManager.RunningTaskInfo info) {
        if (info == null) return null;
        return info.topActivity != null ? info.topActivity : info.baseActivity;
    }

    private static boolean isHome(ActivityManager.RunningTaskInfo info) {
        ComponentName top = topComponent(info);
        if (top == null || context == null) return false;
        ResolveInfo ri = context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        String home = ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : null;
        // without a default home the resolver ("android") answers: then nothing counts as home
        return home != null && !"android".equals(home) && home.equals(top.getPackageName())
                && !"com.android.launcher3".equals(home);       // Launcher3 as home handles this itself
    }

    private static boolean isRecents(ActivityManager.RunningTaskInfo info) {
        ComponentName top = topComponent(info);
        return top != null && RECENTS_ACTIVITY.equals(top.getClassName())
                && (context == null || context.getPackageName().equals(top.getPackageName()));
    }

    private static boolean unstashEnabled() {
        return context == null || Settings.Secure.getInt(context.getContentResolver(), UNSTASH_KEY, 1) != 0;
    }

    /**
     * Backup for hook 2: the top task is not Recents but a taskbar driven by the fallback (or bare) UI controller is
     * still in the launcher stash, e.g. RecentsActivity was destroyed or stopped without a state change. Back to the
     * fresh-process state FLAG_IN_APP. Taskbars of Launcher3-as-home (LauncherTaskbarUIController) are left alone.
     */
    private static void unstick(ActivityManager.RunningTaskInfo top) {
        if (fControllers == null || isRecents(top) || !unstashEnabled()) return;
        for (Object c : CONTROLLERS.keySet().toArray()) {
            try {
                Object controllers = fControllers.get(c);
                if (controllers == null) continue;
                Object ui = fUi.get(controllers);
                String uiClass = ui != null ? ui.getClass().getName() : "";
                if (!uiClass.equals("com.android.launcher3.taskbar.FallbackTaskbarUIController")
                        && !uiClass.equals("com.android.launcher3.taskbar.TaskbarUIController")) continue;
                Object taskbarContext = fTaskbarContext.get(controllers);
                if (taskbarContext == null || (Boolean) mIsDestroyed.invoke(taskbarContext)) continue;
                Object stash = fStash.get(controllers);
                if (stash == null) continue;
                if (!(Boolean) mUpdateFlag.invoke(stash, FLAG_IN_STASHED_LAUNCHER_STATE, false)) continue;  // not set
                mUpdateFlag.invoke(stash, FLAG_IN_APP, true);
                mApplyState.invoke(stash);
                ComponentName tc = topComponent(top);
                Log.i(TAG, "taskbar left the Recents stash; top=" + (tc != null ? tc.flattenToShortString() : null));
            } catch (ReflectiveOperationException | RuntimeException e) {
                Log.w(TAG, "could not unstash a taskbar", e);
            }
        }
    }

    private static void refresh(ActivityManager.RunningTaskInfo top) {
        unstick(top);
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
