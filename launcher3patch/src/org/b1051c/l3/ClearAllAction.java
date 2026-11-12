package org.b1051c.l3;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * "Clear all" in the Recents action row, next to Screenshot and Split (owner 2026-11-11). Stock Launcher3 only has
 * the ClearAllButton behind the last task, i.e. after scrolling through the whole list. One smali line at the end of
 * RecentsView.init() calls {@link #attach}: it adds one more button to OverviewActionsView's action_buttons row, with
 * the row's own style (OverviewActionButton) and the stock string recents_clear_all, so it is there as soon as Recents
 * opens. A tap runs RecentsView.dismissAllTasks(View), exactly what the stock button does.
 *
 * <p>The row's own rules apply to it: hidden with no tasks, during split selection and while the list is scrolled
 * away from the first task (where the stock button takes over), so the two never show together. Covers both the
 * fallback RecentsActivity (Lawnchair as home) and Launcher3's own home. No resources are added.
 * Settings.Secure b1051c_recents_clear_all: 1 (default) = shown, 0 = stock row.
 *
 * <p>Main thread only (init() and the observer run there).
 */
public final class ClearAllAction {
    private static final String TAG = "B1051cClearAll";
    private static final String KEY = "b1051c_recents_clear_all";
    private static final String VIEW_TAG = "b1051c_clear_all";

    private static final WeakHashMap<View, Boolean> BUTTONS = new WeakHashMap<>();
    private static Context appContext;

    private ClearAllAction() {}

    /** Hook: RecentsView.init(actionsView, ...) has just stored its OverviewActionsView. */
    public static void attach(Object recentsView, Object actionsView) {
        try {
            if (!(recentsView instanceof View) || !(actionsView instanceof ViewGroup)) return;
            ViewGroup actions = (ViewGroup) actionsView;
            Context ctx = actions.getContext();
            Resources res = ctx.getResources();
            String pkg = ctx.getPackageName();
            int rowId = res.getIdentifier("action_buttons", "id", pkg);
            View row = rowId != 0 ? actions.findViewById(rowId) : null;
            if (!(row instanceof LinearLayout)) {
                Log.w(TAG, "no action_buttons row; Recents keeps the stock actions");
                return;
            }
            View button = row.findViewWithTag(VIEW_TAG);
            if (button == null) button = create((LinearLayout) row, ctx, res, pkg);
            final View rv = (View) recentsView;
            button.setOnClickListener(v -> clearAll(rv, v));
            observe(ctx);
            button.setVisibility(enabled() ? View.VISIBLE : View.GONE);
            Log.i(TAG, "attached to " + recentsView.getClass().getSimpleName() + " enabled=" + enabled());
        } catch (Throwable t) {
            Log.w(TAG, "attach failed, Recents keeps the stock actions", t);
        }
    }

    private static View create(LinearLayout row, Context ctx, Resources res, String pkg) {
        int style = res.getIdentifier("OverviewActionButton", "style", pkg);
        int theme = res.getIdentifier("ThemeControlHighlightWorkspaceColor", "style", pkg);
        if (style == 0) style = android.R.style.Widget_DeviceDefault_Button_Borderless;
        Context themed = theme != 0 ? new ContextThemeWrapper(ctx, theme) : ctx;
        // OverviewActionsView disables every child while the list scrolls or the task has no thumbnail (Screenshot
        // and Split need one); clearing does not.
        Button b = new Button(themed, null, 0, style) {
            @Override public void setEnabled(boolean enabled) { super.setEnabled(true); }
        };
        b.setTag(VIEW_TAG);
        int text = res.getIdentifier("recents_clear_all", "string", pkg);
        if (text != 0) b.setText(text); else b.setText("Clear all");

        // Same icon size and text look as the Screenshot button
        int size = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, res.getDisplayMetrics()));
        int refId = res.getIdentifier("action_screenshot", "id", pkg);
        View ref = refId != 0 ? row.findViewById(refId) : null;
        if (ref instanceof TextView) {
            TextView r = (TextView) ref;
            Drawable d = r.getCompoundDrawablesRelative()[0];
            if (d != null && d.getIntrinsicWidth() > 0) size = d.getIntrinsicWidth();
            b.setTypeface(r.getTypeface());
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, r.getTextSize());
        }
        Icon icon = new Icon(size);
        icon.setTintList(b.getCompoundDrawableTintList() != null ? b.getCompoundDrawableTintList() : b.getTextColors());
        icon.setBounds(0, 0, size, size);
        b.setCompoundDrawablesRelative(icon, null, null, null);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int gap = res.getIdentifier("overview_actions_button_spacing", "dimen", pkg);
        lp.setMarginStart(gap != 0 ? res.getDimensionPixelSize(gap)
                : Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 36, res.getDisplayMetrics())));
        row.addView(b, lp);
        BUTTONS.put(b, Boolean.TRUE);
        return b;
    }

    private static void clearAll(View recentsView, View button) {
        try {
            Method m = null;
            for (Class<?> c = recentsView.getClass(); c != null && m == null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod("dismissAllTasks", View.class);
                } catch (NoSuchMethodException e) {
                    // declared further up (RecentsView)
                }
            }
            if (m == null) throw new NoSuchMethodException("dismissAllTasks");
            m.setAccessible(true);
            m.invoke(recentsView, button);
        } catch (Throwable t) {
            Log.w(TAG, "dismissAllTasks unavailable, using the stock button", t);
            try {
                Object stock = recentsView.getClass().getMethod("getClearAllButton").invoke(recentsView);
                if (stock instanceof View) ((View) stock).performClick();
            } catch (Throwable t2) {
                Log.w(TAG, "clear all failed", t2);
            }
        }
    }

    private static boolean enabled() {
        return appContext == null || Settings.Secure.getInt(appContext.getContentResolver(), KEY, 1) != 0;
    }

    private static void observe(Context ctx) {
        if (appContext != null) return;
        appContext = ctx.getApplicationContext();
        appContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(KEY), false,
                new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean self) {
                        int vis = enabled() ? View.VISIBLE : View.GONE;
                        for (Object b : BUTTONS.keySet().toArray()) ((View) b).setVisibility(vis);
                    }
                });
    }

    /** Material "clear all" glyph (three staggered bars), drawn here so the APK's resources stay untouched. */
    private static final class Icon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;
        private ColorStateList tint;

        Icon(int size) { this.size = size; }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float u = b.width() / 24f, x = b.left, y = b.top;
            canvas.drawRect(x + 7 * u, y + 7 * u, x + 21 * u, y + 9 * u, paint);
            canvas.drawRect(x + 5 * u, y + 11 * u, x + 19 * u, y + 13 * u, paint);
            canvas.drawRect(x + 3 * u, y + 15 * u, x + 17 * u, y + 17 * u, paint);
        }

        private boolean updateColor() {
            if (tint == null) return false;
            int c = tint.getColorForState(getState(), tint.getDefaultColor());
            if (c == paint.getColor()) return false;
            paint.setColor(c);
            invalidateSelf();
            return true;
        }

        @Override public void setTintList(ColorStateList t) { tint = t; updateColor(); }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] state) { return updateColor(); }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
