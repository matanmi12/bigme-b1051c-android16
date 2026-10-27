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
 * Colors are roles (res/values/colors.xml, dark theme: values-night) read per Context into a {@link Pal}, so
 * screens, tile dialogs and services follow Android's Dark theme; light resolves to the board 05 values.
 */
final class Ink {
    private Ink() {}

    /** The palette roles of the current theme, read once per screen or dialog (see colors.xml). */
    static final class Pal {
        final int paper, ink, cream, muted, display, heading, line, card, pressed, disabledLine, primary, onPrimary,
                controlOn, switchOn, switchOff, knobOn, knobOnLine, knobOff, knobOffLine, band, rivet,
                sliderTrack, sliderCold, sliderWarm, sliderThumb, sliderDot, brandInk, brandOgnito;

        private Pal(Context c) {
            paper = c.getColor(R.color.paper);
            ink = c.getColor(R.color.ink);
            cream = c.getColor(R.color.cream);
            muted = c.getColor(R.color.muted);
            display = c.getColor(R.color.display);
            heading = c.getColor(R.color.heading);
            line = c.getColor(R.color.line);
            card = c.getColor(R.color.card);
            pressed = c.getColor(R.color.pressed);
            disabledLine = c.getColor(R.color.disabled_line);
            primary = c.getColor(R.color.primary);
            onPrimary = c.getColor(R.color.on_primary);
            controlOn = c.getColor(R.color.control_on);
            switchOn = c.getColor(R.color.switch_on);
            switchOff = c.getColor(R.color.switch_off);
            knobOn = c.getColor(R.color.knob_on);
            knobOnLine = c.getColor(R.color.knob_on_line);
            knobOff = c.getColor(R.color.knob_off);
            knobOffLine = c.getColor(R.color.knob_off_line);
            band = c.getColor(R.color.band);
            rivet = c.getColor(R.color.rivet);
            sliderTrack = c.getColor(R.color.slider_track);
            sliderCold = c.getColor(R.color.slider_cold);
            sliderWarm = c.getColor(R.color.slider_warm);
            sliderThumb = c.getColor(R.color.slider_thumb);
            sliderDot = c.getColor(R.color.slider_dot);
            brandInk = c.getColor(R.color.brand_ink);
            brandOgnito = c.getColor(R.color.brand_ognito);
        }
    }

    static Pal pal(Context c) { return new Pal(c); }

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

    /** Rubik 400 / 500 / 600. Body and small text (400) takes the theme's weight, R.integer.body_weight: 400, and
     *  500 (Rubik Medium) under the Dark theme (owner verdict G1: white text on black reads better at 500). */
    static Typeface font(Context c, int weight) {
        load(c);
        if (weight < 500) weight = c.getResources().getInteger(R.integer.body_weight);
        return face(weight);
    }

    /** Notes and row summaries (T3 gray): R.integer.muted_weight, 400 in light and dark (card E7a draws notes at 400;
     *  verdict G1 gives 500 to the white T1 text only). Light: the same face as font(c, 400). */
    static Typeface mutedFont(Context c) {
        load(c);
        return face(c.getResources().getInteger(R.integer.muted_weight));
    }

    private static Typeface face(int weight) {
        return weight >= 600 ? semibold : weight >= 500 ? medium : regular;
    }

    /** The message of a shown AlertDialog in the body weight. Android draws it with a fixed style
     *  (TextAppearance.Material.Subhead) that no theme attribute reaches, so this is code: call it right after show();
     *  later setMessage() calls reuse the same TextView. Keeps the system font (the variable Rubik), changes the weight
     *  only; light (body_weight 400) returns at once, untouched. */
    static <D extends android.app.Dialog> D bodyWeight(D d) {
        int w = d.getContext().getResources().getInteger(R.integer.body_weight);
        if (w == 400) return d;
        android.widget.TextView m = d.findViewById(android.R.id.message);
        if (m != null) m.setTypeface(Typeface.create(m.getTypeface(), w, false));
        return d;
    }

    /** Lilita One, the wordmark face. */
    static Typeface display(Context c) {
        load(c);
        return display;
    }

    static float dp(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }

    /** Rounded fill + ink outline; pressed = silver (no ripple: on e-ink it only costs refreshes). */
    static Drawable pill(Context c, Pal k, int fill, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_pressed}, shape(c, k.pressed, radiusDp, k.line));
        s.addState(new int[] {-android.R.attr.state_enabled}, shape(c, fill, radiusDp, k.disabledLine));
        s.addState(new int[0], shape(c, fill, radiusDp, k.line));
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
     *  like the toggles on board 05; dark: gold track with a black knob ringed white, off = outlined black, gray knob. */
    static Drawable switchTrack(Context c, Pal k) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_checked}, track(c, k.switchOn, k.line));
        s.addState(new int[0], track(c, k.switchOff, k.line));
        return s;
    }

    private static GradientDrawable track(Context c, int fill, int line) {
        GradientDrawable g = shape(c, fill, 999, line);
        g.setSize(Math.round(dp(c, 58)), Math.round(dp(c, 32)));
        return g;
    }

    static Drawable switchThumb(Context c, Pal k) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_checked}, thumb(c, k.knobOn, k.knobOnLine));
        s.addState(new int[0], thumb(c, k.knobOff, k.knobOffLine));
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

    /** Section divider: the riveted steel band of the badge (silver, ink outline, ink rivets; dark: outline only). */
    static final class RivetBand extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float line, step, rivet;
        private final int fill, dots, stroke;

        RivetBand(Context c, Pal k) {
            line = dp(c, 2);
            step = dp(c, 16);
            rivet = dp(c, 2);
            fill = k.band;
            dots = k.rivet;
            stroke = k.line;
        }

        @Override
        public void draw(Canvas cv) {
            r.set(getBounds());
            r.inset(line / 2f, line / 2f);
            float rad = Math.min(r.height() / 2f, dp6());
            p.setStyle(Paint.Style.FILL);
            p.setColor(fill);
            cv.drawRoundRect(r, rad, rad, p);
            p.setColor(dots);
            for (float x = r.left + step / 2f; x < r.right - rivet; x += step) cv.drawCircle(x, r.centerY(), rivet, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(line);
            p.setColor(stroke);
            cv.drawRoundRect(r, rad, rad, p);
        }

        private float dp6() { return line * 3f; }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * SeekBar in the badge style, same drawing as the QS light sliders (E-Ink SystemUI plugin SliderTrack): pill track
     * with an ink outline, colour up to the value, rivets on the empty part, round cream thumb with a centre rivet
     * (dark: black track, white outline and rivets, cream thumb ringed white with a black center).
     * The bounds span thumb centre 0..max (so a touch lands where the thumb is drawn); the pill reaches {@code ext} px
     * past both ends, into the SeekBar's padding (ProgressBar draws the progress drawable translated, unclipped).
     */
    static final class Slider extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Pal k;
        private final int fill;
        private final float stroke, ext;

        Slider(Pal k, int fill, float stroke, float ext) {
            this.k = k;
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
            p.setColor(k.sliderTrack);
            c.drawRoundRect(r, rad, rad, p);
            c.save();
            c.clipRect(r.left, r.top, x, r.bottom);
            p.setColor(fill);
            c.drawRoundRect(r, rad, rad, p);
            c.restore();
            p.setColor(k.rivet);
            float rr = Math.max(1.5f, stroke * 0.5f), step = th * 0.75f;
            for (float rx = r.right - rad; rx > x + d / 2f + rr; rx -= step) c.drawCircle(rx, cy, rr, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setColor(k.line);
            c.drawRoundRect(r, rad, rad, p);
            float tr = d / 2f - stroke / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(k.sliderThumb);
            c.drawCircle(x, cy, tr, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(k.line);
            c.drawCircle(x, cy, tr, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(k.sliderDot);
            c.drawCircle(x, cy, Math.max(2f, d * 0.09f), p);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Give a SeekBar the badge look; {@code fill} = the colour up to the value. */
    static void styleSeekBar(android.widget.SeekBar sb, Pal k, int fill) {
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
                new Slider(k, fill, stroke, ext), new android.graphics.drawable.ColorDrawable(0)});
        ld.setId(0, android.R.id.progress);
        ld.setId(1, android.R.id.secondaryProgress);
        sb.setProgressDrawable(ld);
    }
}
