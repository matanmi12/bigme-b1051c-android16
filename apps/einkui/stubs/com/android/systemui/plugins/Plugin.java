package com.android.systemui.plugins;

/** Compile-time stub of SystemUI's plugin base interface (the real one is loaded from SystemUI). */
public interface Plugin {
    default int getVersion() { return -1; }
    default void onCreate(android.content.Context sysuiContext, android.content.Context pluginContext) {}
    default void onDestroy() {}
}
