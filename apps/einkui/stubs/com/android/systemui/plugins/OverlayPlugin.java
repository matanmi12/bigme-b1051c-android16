package com.android.systemui.plugins;

/** Compile-time stub (SystemUI A16: version 4). setup(shadeRoot, navBar) receives NotificationShadeWindowView. */
public interface OverlayPlugin extends Plugin {
    String ACTION = "com.android.systemui.action.PLUGIN_OVERLAY";
    int VERSION = 4;
    void setup(android.view.View statusBar, android.view.View navBar);
}
