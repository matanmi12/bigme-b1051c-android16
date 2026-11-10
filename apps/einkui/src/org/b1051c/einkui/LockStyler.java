package org.b1051c.einkui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.Locale;
import java.util.WeakHashMap;

/**
 * The InKognito lock screen (ROADMAP wave 3 §7, owner 2026-10-22: "the custom lock screen feature"), on top of the
 * stock keyguard inside the shade window:
 *  - the clock (small or large) in the display face (Lilita One) and the page's display colour;
 *  - a date line of our own under the clock (the GSI has no smartspace, so the stock lock screen shows no date), with
 *    the battery level after it (0.4.9, ROADMAP wave 5; Settings.Secure b1051c_lock_battery 0 = date only);
 *  - the bottom-left shortcut hidden (nothing useful behind it on this tablet);
 *  - a page-colour pill behind the bottom indication ("Charged"): the wallpaper's paw prints sit right behind it
 *    (owner 2026-10-22, the "C" of "Charged" on a black print).
 * Only on the bare lock screen: not with the shade open, not on the bouncer, not in the vanilla mode. Everything is
 * re-asserted on every frame (SystemUI re-applies its own colours on clock and theme changes) and undone when the
 * style is off or the plugin is destroyed. Settings.Secure b1051c_lock_style 0 = Android's, 1 = InKognito (default).
 */
final class LockStyler {
    static final String KEY = "b1051c_lock_style", KEY_BATTERY = "b1051c_lock_battery";

    private final ViewGroup root;
    private final Context sysui;
    private final int idClock, idClockLarge, idStart, idDate, idIndication;
    private final float density;
    private Typeface display, text;
    private TextView date;
    private String dateText = "";
    private long dateAt;
    private boolean ticking;
    /** Clock views we touched -> {ColorStateList colours, Typeface}. */
    private final WeakHashMap<TextView, Object[]> clocks = new WeakHashMap<>();
    private final WeakHashMap<View, Boolean> hiddenStarts = new WeakHashMap<>();
    /** Indication views we gave a pill -> their own background (usually null). */
    private final WeakHashMap<View, android.graphics.drawable.Drawable> pills = new WeakHashMap<>();
    private int errors;

    private static void findAll(View v, int id, java.util.List<View> out) {
        if (v.getId() == id) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) findAll(g.getChildAt(i), id, out);
        }
    }

    LockStyler(Context sysui, Context plugin, ViewGroup root) {
        this.root = root;
        this.sysui = sysui;
        density = root.getResources().getDisplayMetrics().density;
        idClock = id("lockscreen_clock_view");
        idClockLarge = id("lockscreen_clock_view_large");
        idStart = id("start_button");
        idIndication = id("keyguard_indication_text_bottom");
        idDate = View.generateViewId();
        try {
            display = Typeface.createFromAsset(plugin.getAssets(), "fonts/LilitaOne-Regular.ttf");
            text = Typeface.createFromAsset(plugin.getAssets(), "fonts/Rubik-Medium.ttf");
        } catch (RuntimeException e) {
            Log.w(EinkOverlayPlugin.TAG, "lock screen fonts missing, system fonts", e);
        }
    }

    private int id(String name) { return root.getResources().getIdentifier(name, "id", "com.android.systemui"); }

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { dateAt = 0; root.invalidate(); }
    };

    /** One frame of the shade window. `on` = the style applies now (lock screen bare, styled mode, setting on). */
    void frame(boolean on, Palette.Tokens tok) {
        try {
            if (!on) { restore(); return; }
            // the stock clock (AnimatableClockView) draws its own glyphs with its own paint: setTypeface / setTextColor
            // do not reach the panel (tried 2026-10-22). So it is kept invisible, and a clock of our own mirrors its
            // text, size and place in the display face and colour.
            TextView stock = null;
            for (int id : new int[] {idClockLarge, idClock}) {
                View v = id != 0 ? root.findViewById(id) : null;
                if (!(v instanceof TextView) || !attached(v) || v.getWidth() == 0) continue;
                TextView tv = (TextView) v;
                if (!clocks.containsKey(tv)) clocks.put(tv, new Object[] {tv.getVisibility()});
                if (tv.getVisibility() == View.VISIBLE) tv.setVisibility(View.INVISIBLE);
                if (stock == null) stock = tv;
            }
            // every view with the shortcut id (the layout can hold more than one): SystemUI animates it back in
            // (alpha, visibility), so both are forced every frame
            java.util.ArrayList<View> starts = new java.util.ArrayList<>();
            if (idStart != 0) findAll(root, idStart, starts);
            for (View sb : starts) {
                if (sb.getVisibility() == View.VISIBLE) sb.setVisibility(View.INVISIBLE);
                if (sb.getAlpha() != 0f) sb.setAlpha(0f);
                hiddenStarts.put(sb, Boolean.TRUE);
            }
            if (stock != null) { showClock(stock, tok.display); showDate(stock, tok.date); }
            else { hideClock(); hideDate(); }
            java.util.ArrayList<View> marks = new java.util.ArrayList<>();
            if (idIndication != 0) findAll(root, idIndication, marks);
            for (View v : marks) pill(v, tok.page);
        } catch (Throwable t) {
            if (errors++ < 5) Log.w(EinkOverlayPlugin.TAG, "lock screen frame failed", t);
        }
    }

    /** A page-colour pill over the view's bounds; the colour follows the theme. */
    private void pill(View v, int color) {
        if (!pills.containsKey(v)) pills.put(v, v.getBackground());
        android.graphics.drawable.Drawable bg = v.getBackground();
        if (bg instanceof android.graphics.drawable.GradientDrawable && bg != pills.get(v)) {
            android.graphics.drawable.GradientDrawable g = (android.graphics.drawable.GradientDrawable) bg;
            ColorStateList c = g.getColor();
            if (c == null || c.getDefaultColor() != color) g.setColor(color);
            return;
        }
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(1000f);  // clamped to half the height: a pill
        v.setBackground(g);
    }

    private void unpill() {
        try {
            for (View v : pills.keySet().toArray(new View[0])) v.setBackground(pills.remove(v));
        } catch (Throwable t) {
            if (errors++ < 5) Log.w(EinkOverlayPlugin.TAG, "lock screen pill restore failed", t);
        }
    }

    /** Visible as far as the view tree goes, ignoring INVISIBLE on the view itself (we set that). */
    private boolean attached(View v) {
        if (v.getVisibility() == View.GONE) return false;
        for (android.view.ViewParent p = v.getParent(); p instanceof View; p = p.getParent())
            if (((View) p).getVisibility() != View.VISIBLE) return false;
        return v.isAttachedToWindow();
    }

    private TextView clock;

    private void showClock(TextView stock, int color) {
        if (clock == null) {
            clock = new TextView(sysui);
            clock.setId(View.generateViewId());
            if (display != null) clock.setTypeface(display);
            clock.setIncludeFontPadding(false);
            clock.setClickable(false);
            clock.setFocusable(false);
            clock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        CharSequence t = stock.getText();
        if (t != null && !t.toString().contentEquals(clock.getText())) clock.setText(t.toString());
        float px = stock.getTextSize();
        if (Math.abs(clock.getTextSize() - px) > 0.5f) clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px);
        if (clock.getCurrentTextColor() != color) clock.setTextColor(color);
        if (clock.getLineSpacingMultiplier() != 0.9f) clock.setLineSpacing(0f, 0.9f);
        int[] loc = new int[2];
        stock.getLocationInWindow(loc);
        boolean large = stock.getId() == idClockLarge;
        place(clock, large, loc[0], loc[1]);
    }

    private void hideClock() {
        if (clock != null && clock.getVisibility() != View.GONE) clock.setVisibility(View.GONE);
    }

    /** Our view at (left, top) in the window (left ignored when centred), attached to the root once, shown. */
    private void place(TextView v, boolean centred, int left, int top) {
        FrameLayout.LayoutParams lp = v.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) v.getLayoutParams()
                : new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int gravity = centred ? (Gravity.TOP | Gravity.CENTER_HORIZONTAL) : (Gravity.TOP | Gravity.LEFT);
        int ml = centred ? 0 : left;
        if (lp.gravity != gravity || lp.topMargin != top || lp.leftMargin != ml) {
            lp.gravity = gravity; lp.topMargin = top; lp.leftMargin = ml;
            v.setLayoutParams(lp);
        }
        if (v.getParent() != root) {
            if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
            root.addView(v, lp);
        }
        if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
    }

    private void showDate(TextView clock, int color) {
        if (date == null) {
            date = new TextView(sysui);
            date.setId(idDate);
            if (text != null) date.setTypeface(text);
            date.setTextSize(19);
            date.setLetterSpacing(0.03f);
            date.setSingleLine(true);
            date.setClickable(false);
            date.setFocusable(false);
            date.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - dateAt > 30_000) {
            dateAt = now;
            Locale l = sysui.getResources().getConfiguration().getLocales().get(0);
            String pattern = DateFormat.getBestDateTimePattern(l, "EEEEdMMMM");
            String s = DateFormat.format(pattern, System.currentTimeMillis()).toString();
            int pct = android.provider.Settings.Secure.getInt(sysui.getContentResolver(), KEY_BATTERY, 1) == 1 ? battery() : -1;
            if (pct >= 0) s += " · " + pct + " %";   // as on the sleep picture; "Charging" stays in the bottom line
            if (!s.equals(dateText)) { dateText = s; date.setText(s); }
        }
        if (date.getCurrentTextColor() != color) date.setTextColor(color);
        // under the clock: left-aligned to a small (top-left) clock, centred under a large one
        int[] loc = new int[2];
        clock.getLocationInWindow(loc);
        boolean large = clock.getId() == idClockLarge;
        // under our clock once it has a size (its face is shorter than the stock one), else under the stock box
        int h = this.clock != null && this.clock.getHeight() > 0 ? this.clock.getHeight() : clock.getHeight();
        int top = loc[1] + h + Math.round(2 * density);
        place(date, large, loc[0] + Math.round(3 * density), top);
        if (!ticking) {
            IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            f.addAction(Intent.ACTION_DATE_CHANGED);
            try { sysui.registerReceiver(tick, f); ticking = true; } catch (RuntimeException ignored) {}
        }
    }

    /** The level from the sticky ACTION_BATTERY_CHANGED (no receiver kept: the minute tick re-reads it), or -1. */
    private int battery() {
        Intent i = sysui.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return -1;
        int level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
        return level < 0 || scale <= 0 ? -1 : level * 100 / scale;
    }

    private void hideDate() {
        if (date != null && date.getVisibility() != View.GONE) date.setVisibility(View.GONE);
    }

    /** Put the stock lock screen back (style off, shade open, unlocked, destroy). */
    void restore() {
        unpill();
        hideDate();
        hideClock();
        for (View sb : hiddenStarts.keySet().toArray(new View[0])) {
            hiddenStarts.remove(sb);
            if (sb.getVisibility() == View.INVISIBLE) sb.setVisibility(View.VISIBLE);
            if (sb.getAlpha() == 0f) sb.setAlpha(1f);
        }
        for (TextView tv : clocks.keySet().toArray(new TextView[0])) {
            clocks.remove(tv);
            if (tv.getVisibility() == View.INVISIBLE) tv.setVisibility(View.VISIBLE);
        }
    }

    void destroy() {
        restore();
        for (TextView v : new TextView[] {date, clock})
            if (v != null && v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        date = null; clock = null;
        if (ticking) { try { sysui.unregisterReceiver(tick); } catch (RuntimeException ignored) {} ticking = false; }
    }

    private static final android.graphics.Rect TMP = new android.graphics.Rect();
    private static boolean onScreen(View v) {
        return v != null && v.isShown() && v.getAlpha() > 0.01f && v.getGlobalVisibleRect(TMP) && TMP.height() > 0;
    }
}
