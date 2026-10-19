package org.b1051c.einkctl;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/**
 * InKognito look (design board 05, palette sampled from the boot logo): cream paper, black ink, heavy outlines,
 * full-strength fills (cream text on bordeaux / teal / steel, ink on the golds), Rubik for the interface and
 * Lilita One for display. The fonts (SIL OFL, google/fonts) are optional assets: without them (public repo
 * builds copy text files only) everything falls back to the system font.
 */
final class Ink {
    static final int INK = 0xFF000000;
    static final int CREAM = 0xFFF7F4E7;
    static final int BORDEAUX = 0xFF932845;
    static final int TEAL = 0xFF194D50;
    static final int GOLD = 0xFFCD8E2C;
    static final int LIGHT_GOLD = 0xFFF7BF5D;
    static final int STEEL = 0xFF5D7E91;
    static final int SILVER = 0xFFA7B0B6;
    /** Secondary text (board 05 captions). */
    static final int MUTED = 0xFF4A4342;

    private Ink() {}

    private static Typeface regular, medium, semibold, display;
    private static boolean loaded;

    private static Typeface asset(Context c, String name, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), "fonts/" + name);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static void load(Context c) {
        if (loaded) return;
        loaded = true;
        regular = asset(c, "Rubik-Regular.ttf", Typeface.DEFAULT);
        medium = asset(c, "Rubik-Medium.ttf", Typeface.DEFAULT);
        semibold = asset(c, "Rubik-SemiBold.ttf", Typeface.DEFAULT_BOLD);
        display = asset(c, "LilitaOne-Regular.ttf", Typeface.DEFAULT_BOLD);
    }

    /** Rubik 400 / 500 / 600. */
    static Typeface font(Context c, int weight) {
        load(c);
        return weight >= 600 ? semibold : weight >= 500 ? medium : regular;
    }

    /** Lilita One, the wordmark face. */
    static Typeface display(Context c) {
        load(c);
        return display;
    }

    static float dp(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }

    /** Rounded fill + ink outline; pressed = silver (no ripple: on e-ink it only costs refreshes). */
    static Drawable pill(Context c, int fill, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_pressed}, shape(c, SILVER, radiusDp, INK));
        s.addState(new int[] {-android.R.attr.state_enabled}, shape(c, fill, radiusDp, SILVER));
        s.addState(new int[0], shape(c, fill, radiusDp, INK));
        return s;
    }

    static GradientDrawable shape(Context c, int fill, float radiusDp, int line) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(Math.round(dp(c, 2.5f)), line);
        return g;
    }

    /** Switch track (teal when on, silver when off, ink outline) and thumb (light gold on, white with ink ring off),
     *  like the toggles on board 05. */
    static Drawable switchTrack(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_checked}, track(c, TEAL));
        s.addState(new int[0], track(c, SILVER));
        return s;
    }

    private static GradientDrawable track(Context c, int fill) {
        GradientDrawable g = shape(c, fill, 999, INK);
        g.setSize(Math.round(dp(c, 58)), Math.round(dp(c, 32)));
        return g;
    }

    static Drawable switchThumb(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_checked}, thumb(c, LIGHT_GOLD, 0));
        s.addState(new int[0], thumb(c, 0xFFFFFFFF, INK));
        return s;
    }

    private static Drawable thumb(Context c, int fill, int line) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (line != 0) g.setStroke(Math.round(dp(c, 2.5f)), line);
        int d = Math.round(dp(c, 22)), in = Math.round(dp(c, 5));
        g.setSize(d, d);
        android.graphics.drawable.InsetDrawable i = new android.graphics.drawable.InsetDrawable(g, in);
        return i;
    }

    static ColorStateList tint(int c) { return ColorStateList.valueOf(c); }

    /** Section divider: the riveted steel band of the badge (silver, ink outline, ink rivets). */
    static final class RivetBand extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float line, step, rivet;

        RivetBand(Context c) {
            line = dp(c, 2);
            step = dp(c, 16);
            rivet = dp(c, 2);
        }

        @Override
        public void draw(Canvas cv) {
            r.set(getBounds());
            r.inset(line / 2f, line / 2f);
            float rad = Math.min(r.height() / 2f, dp6());
            p.setStyle(Paint.Style.FILL);
            p.setColor(SILVER);
            cv.drawRoundRect(r, rad, rad, p);
            p.setColor(INK);
            for (float x = r.left + step / 2f; x < r.right - rivet; x += step) cv.drawCircle(x, r.centerY(), rivet, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(line);
            cv.drawRoundRect(r, rad, rad, p);
        }

        private float dp6() { return line * 3f; }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * SeekBar in the badge style, same drawing as the QS light sliders (E-Ink SystemUI plugin SliderTrack): pill track
     * with an ink outline, colour up to the value, rivets on the empty part, round cream thumb with a centre rivet.
     * The bounds span thumb centre 0..max (so a touch lands where the thumb is drawn); the pill reaches {@code ext} px
     * past both ends, into the SeekBar's padding (ProgressBar draws the progress drawable translated, unclipped).
     */
    static final class Slider extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final int fill;
        private final float stroke, ext;

        Slider(int fill, float stroke, float ext) {
            this.fill = fill;
            this.stroke = stroke;
            this.ext = ext;
        }

        @Override
        protected boolean onLevelChange(int level) {
            invalidateSelf();
            return true;
        }

        @Override
        public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float h = b.height(), cy = b.exactCenterY();
            float th = Math.round(h * 0.5f), rad = th / 2f, d = h * 0.8f;
            r.set(b.left - ext + stroke / 2f, cy - th / 2f, b.right + ext - stroke / 2f, cy + th / 2f);
            float x = b.left + b.width() * (getLevel() / 10000f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(SILVER);
            c.drawRoundRect(r, rad, rad, p);
            c.save();
            c.clipRect(r.left, r.top, x, r.bottom);
            p.setColor(fill);
            c.drawRoundRect(r, rad, rad, p);
            c.restore();
            p.setColor(INK);
            float rr = Math.max(1.5f, stroke * 0.5f), step = th * 0.75f;
            for (float rx = r.right - rad; rx > x + d / 2f + rr; rx -= step) c.drawCircle(rx, cy, rr, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            c.drawRoundRect(r, rad, rad, p);
            float tr = d / 2f - stroke / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(CREAM);
            c.drawCircle(x, cy, tr, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(INK);
            c.drawCircle(x, cy, tr, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(x, cy, Math.max(2f, d * 0.09f), p);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Give a SeekBar the badge look; {@code fill} = the colour up to the value. */
    static void styleSeekBar(android.widget.SeekBar sb, int fill) {
        Context c = sb.getContext();
        int h = Math.round(dp(c, 44));
        float stroke = dp(c, 2.5f);
        int ext = Math.round(h * 0.4f + stroke);
        sb.setThumb(null);
        sb.setSplitTrack(false);
        sb.setBackground(null);
        sb.setMinHeight(h);
        sb.setMaxHeight(h);
        sb.setPadding(ext, 0, ext, 0);
        // own secondaryProgress layer: otherwise the secondary progress (0) resets the level of the whole drawable
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new Drawable[] {
                new Slider(fill, stroke, ext), new android.graphics.drawable.ColorDrawable(0)});
        ld.setId(0, android.R.id.progress);
        ld.setId(1, android.R.id.secondaryProgress);
        sb.setProgressDrawable(ld);
    }
}
