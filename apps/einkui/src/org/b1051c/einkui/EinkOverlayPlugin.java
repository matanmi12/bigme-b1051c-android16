package org.b1051c.einkui;

import android.content.Context;
import android.util.Log;
import android.view.View;
import com.android.systemui.plugins.OverlayPlugin;

/**
 * SystemUI overlay plugin (loaded by SystemUI on debuggable builds; needs the platform-signed PLUGIN permission).
 * SystemUI hands us the notification-shade root view; ShadeStyler then
 *  - replaces the QS brightness slider with two front-light sliders (cold / warm), same design,
 *  - restyles the shade for e-ink (light or dark, outlines, solid colours) unless the theme setting is "off".
 * Any exception is caught and logged: SystemUI must never crash because of us.
 */
public class EinkOverlayPlugin implements OverlayPlugin {
    static final String TAG = "EinkUI";
    /** Process-wide slot (System properties are shared across plugin class loaders): the live styler of any
     *  version of this plugin, so an instance left behind by an APK update is shut down before a new one starts. */
    private static final String LIVE = "org.b1051c.einkui.live";

    private Context sysui;
    private Context plugin;
    private ShadeStyler styler;

    @Override public int getVersion() { return VERSION; }

    @Override
    public void onCreate(Context sysuiContext, Context pluginContext) {
        sysui = sysuiContext;
        plugin = pluginContext;
    }

    @Override
    public void setup(View shadeRoot, View navBar) {
        try {
            if (styler != null) styler.destroy();
            styler = null;
            // During an APK update SystemUI may run setup() of the old and the new instance in either order:
            // the highest build wins, an older one never replaces a newer one.
            Object live = System.getProperties().get(LIVE);
            int liveBuild = live == null ? -1 : buildOf(live);
            if (liveBuild > Build.CODE) {
                Log.i(TAG, "build " + Build.CODE + " stays idle: build " + liveBuild + " is active");
                return;
            }
            if (live != null) invoke(live, "destroy");
            styler = new ShadeStyler(sysui != null ? sysui : shadeRoot.getContext(), plugin, shadeRoot);
            styler.start();
            System.getProperties().put(LIVE, styler);
            Log.i(TAG, "build " + Build.CODE + " attached to " + shadeRoot.getClass().getName());
        } catch (Throwable t) {
            Log.e(TAG, "setup failed", t);
        }
    }

    private static int buildOf(Object styler) {
        try {
            java.lang.reflect.Field f = styler.getClass().getDeclaredField("BUILD");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            return 0;                                   // builds before the stamp
        }
    }

    private static void invoke(Object o, String name) {
        try {
            java.lang.reflect.Method m = o.getClass().getDeclaredMethod(name);
            m.setAccessible(true);                      // other class loader = other runtime package
            m.invoke(o);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "could not call " + name + " on previous instance", e);
        }
    }

    @Override
    public void onDestroy() {
        try {
            if (styler != null) styler.destroy();
            if (System.getProperties().get(LIVE) == styler) System.getProperties().remove(LIVE);
        } catch (Throwable t) {
            Log.e(TAG, "destroy failed", t);
        }
        styler = null;
    }
}
