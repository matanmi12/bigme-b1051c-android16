package org.b1051c.sleep;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;

/**
 * The time the tablet fell asleep. Android tells apps about the screen going off ~650 ms after the panel is already
 * off (measured 2026-10-29), so the time cannot be drawn into the frame ahead of the sleep. The frame leaves an empty
 * box after "asleep since"; clock.bin next to it carries the glyphs of the same font, and the HWC shim (eink-compat
 * 0.1.48+) writes the local time into the box when it commits the frame at power-off.
 * clock.bin, little-endian: header (64 B: "B1SLPCK1", frame counter, flags bit 0 = 24-hour / bit 1 = rot 90, UTC offset
 * now, offset after the next DST change and its epoch second, text colour R,G,B,A, box x/y/w/h and pen start x on the
 * page, glyph count), a glyph table (16 B each: char, advance in 1/64 px, bitmap left offset, bitmap width), then each
 * glyph's 8-bit coverage, box height rows of page-oriented pixels.
 */
final class Clock {
    static final int PAD = 6;                        // glyph overhang room on both sides
    static final int MODULE_VC = 49;                 // eink-compat 0.1.48: the first shim that draws it

    private Clock() {}

    /** Where the time goes (page px, k = 1), noted while the frame's scene is drawn. */
    static final class Spot {
        final float x0, baseline; final Paint paint;
        Spot(float x0, float baseline, Paint paint) { this.x0 = x0; this.baseline = baseline; this.paint = paint; }
    }

    /** The widest time the shim may write in this font ("88:88", or "88:88 AM"/"PM" in 12-hour format). */
    static float widest(Paint p, boolean h24) {
        float digit = 0;
        for (char d = '0'; d <= '9'; d++) digit = Math.max(digit, p.measureText(String.valueOf(d)));
        return 4 * digit + p.measureText(":")
                + (h24 ? 0 : p.measureText(" ") + Math.max(p.measureText("A"), p.measureText("P")) + p.measureText("M"));
    }

    /** The zone part of the frame signature: a new offset or DST rule re-renders (clock.bin carries the offsets). */
    static String zoneKey(boolean h24) {
        ZoneRules r = ZoneId.systemDefault().getRules();
        Instant now = Instant.now();
        ZoneOffsetTransition t = r.nextTransition(now);
        return (h24 ? "24" : "12") + "/" + r.getOffset(now).getTotalSeconds() + "/" + (t == null ? 0 : t.getInstant().getEpochSecond());
    }

    static byte[] build(Spot s, int seq, boolean h24, int rot, int argb) {
        Paint p = s.paint;
        String chars = h24 ? "0123456789:" : "0123456789: AMP";
        Paint.FontMetricsInt fm = p.getFontMetricsInt();
        int top = (int) Math.floor(s.baseline + fm.top) - 2, bottom = (int) Math.ceil(s.baseline + fm.bottom) + 2;
        top = Math.max(0, top);
        int boxH = Math.min(bottom, Pictures.PAGE_H) - top;
        float widest = widest(p, h24);
        int x0 = Math.round(s.x0);
        int boxX = Math.max(0, x0 - PAD), boxW = Math.min(Pictures.PAGE_W, x0 + (int) Math.ceil(widest) + PAD) - boxX;
        if (boxW < 1 || boxH < 1) return null;

        ByteBuffer table = ByteBuffer.allocate(16 * chars.length()).order(ByteOrder.LITTLE_ENDIAN);
        ByteArrayOutputStream bits = new ByteArrayOutputStream();
        Paint gp = new Paint(p);
        gp.setColor(0xff000000);                      // ALPHA_8 keeps coverage only
        for (int i = 0; i < chars.length(); i++) {
            String ch = String.valueOf(chars.charAt(i));
            float adv = gp.measureText(ch);
            int w = (int) Math.ceil(adv) + 2 * PAD;
            Bitmap b = Bitmap.createBitmap(w, boxH, Bitmap.Config.ALPHA_8);
            new Canvas(b).drawText(ch, PAD, s.baseline - top, gp);
            ByteBuffer px = ByteBuffer.allocate(b.getRowBytes() * boxH);
            b.copyPixelsToBuffer(px);
            byte[] all = px.array();
            for (int y = 0; y < boxH; y++) bits.write(all, y * b.getRowBytes(), w);
            b.recycle();
            table.put((byte) chars.charAt(i)).put((byte) 0).put((byte) 0).put((byte) 0)
                    .putInt(Math.round(adv * 64)).putInt(-PAD).putInt(w);
        }

        ZoneRules r = ZoneId.systemDefault().getRules();
        Instant now = Instant.now();
        ZoneOffsetTransition t = r.nextTransition(now);
        int off = r.getOffset(now).getTotalSeconds();
        ByteBuffer h = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        h.put("B1SLPCK1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(seq)
                .putInt((h24 ? 1 : 0) | (rot == 90 ? 2 : 0))
                .putInt(off).putInt(t == null ? off : t.getOffsetAfter().getTotalSeconds())
                .putLong(t == null ? 0 : t.getInstant().getEpochSecond())
                .putInt(((argb >> 16) & 0xff) | (argb & 0xff00) | ((argb & 0xff) << 16) | 0xff000000)
                .putInt(boxX).putInt(top).putInt(boxW).putInt(boxH).putInt(x0).putInt(chars.length()).putInt(0);
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 + table.capacity() + bits.size());
        out.write(h.array(), 0, 64);
        out.write(table.array(), 0, table.capacity());
        byte[] b = bits.toByteArray();
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    /** The shim of the running module draws the time (eink-compat versionCode 49+, not disabled). Cached per boot. */
    private static long checkedBoot = -1;
    private static boolean supported;

    static synchronized boolean shimDraws() {
        long boot = Prefs.bootEpoch();
        if (boot == checkedBoot) return supported;
        String r = Root.run("M=/data/adb/modules/b1051c-eink-compat; [ -f $M/disable ] && echo 0 || sed -n 's/^versionCode=//p' $M/module.prop");
        int vc;
        try { vc = Integer.parseInt(r.trim()); } catch (NumberFormatException e) { vc = 0; }
        supported = vc >= MODULE_VC;
        checkedBoot = boot;
        return supported;
    }
}
