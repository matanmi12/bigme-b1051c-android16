package com.android.systemui.shared.system;

/** Compile-only stub of Launcher3's own interface (all methods are default there too). */
public interface TaskStackChangeListener {
    default void onTaskStackChanged() {}
    default void onTaskMovedToFront(android.app.ActivityManager.RunningTaskInfo taskInfo) {}
}
