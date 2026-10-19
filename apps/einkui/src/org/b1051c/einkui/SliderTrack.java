package org.b1051c.einkui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Front-light slider in the badge style (board 05): a pill track with an ink outline, coloured fill up to the value,
 * rivet dots on the empty part, a round thumb with a centre rivet, and the icon left of the track.
 * Used as the SeekBar's progress drawable (the SeekBar sets its level). The bounds span thumb centre 0..max, so a
 * touch maps exactly to where the thumb is drawn; the pill reaches {@code ext} px past both ends and the icon sits
 * in the SeekBar's left padding (ProgressBar draws the progress drawable translated by the padding, unclipped).
 */
final class SliderTrack extends Drawable {
    static final class Colors {
        int track, line, fill, thumb;
        boolean rivets;
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Colors k;
    private final float stroke, ext, iconZone;
    private final Drawable icon;

    SliderTrack(Colors k, float stroke, float ext, float iconZone, Drawable icon) {
        this.k = k;
        this.stroke = stroke;
        this.ext = ext;
        this.iconZone = iconZone;
        this.icon = icon;
    }

    @Override
    protected boolean onLevelChange(int level) {
        invalidateSelf();
        return true;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float h = b.height(), cy = b.exactCenterY();
        float th = Math.round(h * 0.5f), rad = th / 2f;
        float d = h * 0.8f;
        r.set(b.left - ext + stroke / 2f, cy - th / 2f, b.right + ext - stroke / 2f, cy + th / 2f);
        float x = b.left + b.width() * (getLevel() / 10000f);

        p.setStyle(Paint.Style.FILL);
        p.setColor(k.track);
        c.drawRoundRect(r, rad, rad, p);
        c.save();
        c.clipRect(r.left, r.top, x, r.bottom);
        p.setColor(k.fill);
        c.drawRoundRect(r, rad, rad, p);
        c.restore();
        if (k.rivets) {
            p.setColor(k.line);
            float rr = Math.max(1.5f, stroke * 0.5f), step = th * 0.75f;
            for (float rx = r.right - rad; rx > x + d / 2f + rr; rx -= step) c.drawCircle(rx, cy, rr, p);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(stroke);
        p.setColor(k.line);
        c.drawRoundRect(r, rad, rad, p);

        float tr = d / 2f - stroke / 2f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(k.thumb);
        c.drawCircle(x, cy, tr, p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(k.line);
        c.drawCircle(x, cy, tr, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(x, cy, Math.max(2f, d * 0.09f), p);

        if (icon != null) {
            int s = Math.round(h * 0.6f);
            int ix = Math.round(b.left - ext - iconZone / 2f - s / 2f), iy = Math.round(cy - s / 2f);
            icon.setBounds(ix, iy, ix + s, iy + s);
            icon.draw(c);
        }
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
