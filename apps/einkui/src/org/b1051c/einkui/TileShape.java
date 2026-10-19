package org.b1051c.einkui;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Tile background for e-ink: solid fill + outline. SystemUI keeps driving the fill through setTint() (its own state
 * logic and colours, which we replace), while the outline keeps its own colour — a tinted GradientDrawable would
 * paint the stroke in the fill colour.
 */
final class TileShape extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float radius;

    TileShape(float radius, float lineWidth, int lineColor, int fillColor) {
        this.radius = radius;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(fillColor);
        line.setStyle(Paint.Style.STROKE);
        setLine(lineWidth, lineColor);
    }

    void setRadius(float radius) {
        if (this.radius == radius) return;
        this.radius = radius;
        invalidateSelf();
    }

    void setLine(float width, int color) {
        if (line.getStrokeWidth() == width && line.getColor() == color) return;
        line.setStrokeWidth(width);
        line.setColor(color);
        invalidateSelf();
    }

    @Override
    public void setTintList(ColorStateList tint) {
        int c = tint == null ? 0 : tint.getDefaultColor();
        if (c != fill.getColor()) {
            fill.setColor(c);
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        float w = line.getStrokeWidth();
        r.set(getBounds());
        r.inset(w / 2f, w / 2f);
        float rad = Math.min(radius, Math.min(r.width(), r.height()) / 2f);
        canvas.drawRoundRect(r, rad, rad, fill);
        if (w > 0) canvas.drawRoundRect(r, rad, rad, line);
    }

    @Override public Drawable mutate() { return this; }
    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter cf) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
