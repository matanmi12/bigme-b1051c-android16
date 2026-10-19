package org.b1051c.einkui;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;

/**
 * Two front-light sliders (cold above, warm below) in place of the QS brightness slider. They are clones of the
 * brightness slider (same layout height, same progress drawable, icon swapped), so they keep its design.
 * Values (raw lm3630a 0..222) go through Settings.Secure; E-Ink Control applies them to the hardware and publishes
 * the current values back, so both stay in sync.
 */
final class LightSliders {
    static final String KEY_COLD = "b1051c_fl_cold";
    static final String KEY_WARM = "b1051c_fl_warm";
    /** Stock Bigme 37-step frontlight table. */
    static final int[] STEPS = {0, 2, 20, 30, 40, 50, 60, 70, 80, 90, 102, 108, 114, 120, 126, 132, 138, 144, 150,
            156, 162, 168, 174, 180, 188, 192, 198, 204, 206, 208, 210, 212, 214, 216, 218, 220, 222};

    private final Context sysui;
    private final Context plugin;
    private final ContentResolver cr;
    private final Handler h = new Handler(Looper.getMainLooper());
    private View orig;
    private LinearLayout box;
    private SeekBar cold, warm;
    private int styledGen = -1;
    private int gap;                 // QS tile row spacing, also kept between the sliders and the tiles
    private boolean tracking;
    private boolean writeFailed;

    private final ContentObserver obs;

    LightSliders(Context sysui, Context plugin) {
        this.sysui = sysui;
        this.plugin = plugin;
        cr = sysui.getContentResolver();
        obs = new ContentObserver(h) {
            @Override public void onChange(boolean selfChange) { syncFromSettings(); }
        };
        cr.registerContentObserver(Settings.Secure.getUriFor(KEY_COLD), false, obs);
        cr.registerContentObserver(Settings.Secure.getUriFor(KEY_WARM), false, obs);
    }

    static int stepOf(int raw) {
        int best = 0;
        for (int i = 0; i < STEPS.length; i++) if (Math.abs(STEPS[i] - raw) < Math.abs(STEPS[best] - raw)) best = i;
        return best;
    }

    /** Called every frame with the brightness slider view; (re)builds and keeps our sliders next to it. */
    void attach(View brightness, ShadeStyler s) {
        if (orig != brightness || box == null) build(brightness, s.mode);
        if (brightness.getVisibility() != View.GONE) brightness.setVisibility(View.GONE);
        ViewGroup parent = (ViewGroup) brightness.getParent();
        if (parent != null && (box.getParent() != parent || parent.indexOfChild(box) != parent.indexOfChild(brightness) + 1)) {
            if (box.getParent() != null) ((ViewGroup) box.getParent()).removeView(box);
            parent.addView(box, parent.indexOfChild(brightness) + 1, boxParams(brightness));
        }
        if (styledGen != s.gen) style(s);
    }

    private ViewGroup.LayoutParams boxParams(View brightness) {
        ViewGroup.LayoutParams lp = brightness.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) lp);
            m.width = ViewGroup.LayoutParams.MATCH_PARENT;
            m.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            // The brightness slider's own spacing sized one slider; with two, the warm one ran into the first tile
            // row (owner 2026-10-18). Keep the tile-row gap below the box.
            m.bottomMargin = Math.max(m.bottomMargin, gap);
            return lp instanceof LinearLayout.LayoutParams ? new LinearLayout.LayoutParams(m) : m;
        }
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void build(View brightness, int mode) {
        detach();
        orig = brightness;
        Context c = brightness.getContext();             // the QS-themed context: ?attr colours resolve like the original
        int idSlider = c.getResources().getIdentifier("slider", "id", "com.android.systemui");
        SeekBar src = brightness.findViewById(idSlider);
        int h = src != null && src.getLayoutParams() != null && src.getLayoutParams().height > 0
                ? src.getLayoutParams().height : dp(c, 48);
        int gapId = c.getResources().getIdentifier("qs_tile_margin_vertical", "dimen", "com.android.systemui");
        gap = gapId != 0 ? c.getResources().getDimensionPixelSize(gapId) : dp(c, 8);

        box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        cold = slider(c, KEY_COLD, plugin.getString(R.string.light_cold));
        warm = slider(c, KEY_WARM, plugin.getString(R.string.light_warm));
        box.addView(cold, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
        lp.topMargin = gap;
        box.addView(warm, lp);
        styledGen = -1;
        syncFromSettings();
    }

    private SeekBar slider(Context c, String key, String label) {
        SeekBar b = new SeekBar(c);
        b.setMax(STEPS.length - 1);
        b.setThumb(null);
        b.setSplitTrack(false);
        b.setBackground(null);
        b.setPadding(0, 0, 0, 0);
        int h = dp(c, 48);
        b.setMinHeight(h);
        b.setMaxHeight(h);                               // the default SeekBar style caps the track at a thin line
        b.setContentDescription(label);
        b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private long last;
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                long now = android.os.SystemClock.uptimeMillis();
                if (now - last > 120) { last = now; write(key, STEPS[p]); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { tracking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { tracking = false; write(key, STEPS[s.getProgress()]); }
        });
        return b;
    }

    private void write(String key, int raw) {
        try {
            Settings.Secure.putInt(cr, key, raw);
        } catch (RuntimeException e) {
            if (!writeFailed) Log.w(EinkOverlayPlugin.TAG, "Settings.Secure write failed, using broadcast", e);
            writeFailed = true;
            sysui.sendBroadcast(new Intent("org.b1051c.einkctl.SET_LIGHT").setPackage("org.b1051c.einkctl")
                    .putExtra(key.equals(KEY_COLD) ? "cold" : "warm", raw));
        }
    }

    private void syncFromSettings() {
        if (cold == null || tracking) return;
        cold.setProgress(stepOf(Settings.Secure.getInt(cr, KEY_COLD, 0)));
        warm.setProgress(stepOf(Settings.Secure.getInt(cr, KEY_WARM, 0)));
    }

    /** InKognito palette: badge-style SliderTrack. Otherwise: a fresh brightness drawable from SystemUI, recoloured
     *  for the plain e-ink modes, icon swapped. */
    private void style(ShadeStyler s) {
        styledGen = s.gen;
        Context c = orig.getContext();
        if (s.inkPalette) {
            badge(cold, s, true);
            badge(warm, s, false);
            return;
        }
        cold.setPadding(0, 0, 0, 0);
        warm.setPadding(0, 0, 0, 0);
        cold.setProgressDrawable(drawable(c, s, plugin.getDrawable(R.drawable.ic_light_cold)));
        warm.setProgressDrawable(drawable(c, s, plugin.getDrawable(R.drawable.ic_light_warm)));
    }

    /** Board 05: silver riveted track, steel (cold) or gold (warm) fill, cream thumb; dark shade = black track,
     *  white ink, silver / light gold fill. */
    private void badge(SeekBar b, ShadeStyler s, boolean isCold) {
        SliderTrack.Colors k = new SliderTrack.Colors();
        boolean dark = s.mode == 2;
        k.track = dark ? 0xFF000000 : Palette.SILVER;
        k.line = s.fg;
        k.fill = isCold ? (dark ? Palette.SILVER : Palette.STEEL) : (dark ? Palette.LIGHT_GOLD : Palette.GOLD);
        k.thumb = dark ? 0xFF000000 : Palette.CREAM;
        k.rivets = true;
        Drawable icon = plugin.getDrawable(isCold ? R.drawable.ic_light_cold_badge : R.drawable.ic_light_warm_badge).mutate();
        if (dark) icon.setTint(s.fg);                   // the ink outlines would vanish on black: white silhouette
        int h = b.getLayoutParams() != null && b.getLayoutParams().height > 0 ? b.getLayoutParams().height : dp(b.getContext(), 48);
        float d = h * 0.8f;
        int ext = Math.round(d / 2f + s.stroke), zone = Math.round(h * 0.9f);
        b.setPadding(zone + ext, 0, ext, 0);
        // ProgressBar sets the level of the layer with the matching id, or of the whole drawable when there is none:
        // without its own secondaryProgress layer, the secondary progress (0) would overwrite the track's level.
        LayerDrawable ld = new LayerDrawable(new Drawable[] {
                new SliderTrack(k, s.stroke, ext, zone, icon), new android.graphics.drawable.ColorDrawable(0)});
        ld.setId(0, android.R.id.progress);
        ld.setId(1, android.R.id.secondaryProgress);
        b.setProgressDrawable(ld);
    }

    private Drawable drawable(Context c, ShadeStyler s, Drawable icon) {
        int id = c.getResources().getIdentifier("brightness_progress_drawable", "drawable", "com.android.systemui");
        Drawable d = c.getDrawable(id).mutate();
        if (!(d instanceof LayerDrawable)) return d;
        LayerDrawable outer = (LayerDrawable) d;
        Drawable prog = outer.findDrawableByLayerId(android.R.id.progress);
        LayerDrawable inner = prog instanceof DrawableWrapper && ((DrawableWrapper) prog).getDrawable() instanceof LayerDrawable
                ? (LayerDrawable) ((DrawableWrapper) prog).getDrawable() : null;
        int idIcon = c.getResources().getIdentifier("slider_icon", "id", "com.android.systemui");
        int idFg = c.getResources().getIdentifier("slider_foreground", "id", "com.android.systemui");
        int iconTint;
        if (s.mode == 0) {
            TypedArray a = c.obtainStyledAttributes(new int[] {
                    c.getResources().getIdentifier("onShadeActive", "attr", "com.android.systemui")});
            iconTint = a.getColor(0, 0xFF000000);
            a.recycle();
        } else {
            iconTint = s.bg;
            Drawable track = outer.findDrawableByLayerId(android.R.id.background);
            if (track instanceof DrawableWrapper) track = ((DrawableWrapper) track).getDrawable();
            if (track instanceof GradientDrawable) ((GradientDrawable) track).setColor(s.fg);   // thin solid line
            if (inner != null) {
                Drawable fgd = inner.findDrawableByLayerId(idFg);
                if (fgd instanceof GradientDrawable) ((GradientDrawable) fgd).setColor(s.fg);
                if (inner.getNumberOfLayers() > 1 && inner.getId(1) != idIcon) inner.getDrawable(1).setAlpha(0);
            }
        }
        if (inner != null && icon != null) {
            icon = icon.mutate();
            Drawable old = inner.findDrawableByLayerId(idIcon);
            if (old instanceof DrawableWrapper) {             // keep SystemUI's wrapper (size/alpha), swap the glyph
                ((DrawableWrapper) old).setDrawable(icon);
                old.setTintList(android.content.res.ColorStateList.valueOf(iconTint));
            } else {
                icon.setTint(iconTint);
                inner.setDrawableByLayerId(idIcon, icon);
            }
        }
        return d;
    }

    void detach() {
        if (box != null && box.getParent() != null) ((ViewGroup) box.getParent()).removeView(box);
        if (orig != null) orig.setVisibility(View.VISIBLE);
        box = null;
        cold = warm = null;
        orig = null;
    }

    void destroy() {
        cr.unregisterContentObserver(obs);
        detach();
    }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}
