package org.b1051c.sleep;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.BatteryManager;
import android.text.format.DateFormat;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The sleep picture, rendered ahead of time: the panel's raw frame (2480x1860 RGBX, 4 B/px, R,G,B,A, pitch 9920) is
 * composed here, written to the app's files dir, copied as root to /data/vendor/b1051c/sleep/frame.rgbx (readable by
 * the HWC composer) and announced with the counter prop vendor.debug.hwc.shim.standby. The HWC shim commits that frame
 * as one GC16 when SurfaceFlinger turns the panel off, so the bistable panel keeps it while the tablet sleeps.
 * The portrait page (1860x2480) is drawn into the raw landscape buffer with SurfaceFlinger's ROT_270:
 * raw[Y][X] = page[y = X][x = 1859 - Y] (pref "rot" = 90 gives the opposite mapping, for the device check).
 */
final class Renderer {
    static final String TAG = "sleep";
    static final String DIR = "/data/vendor/b1051c/sleep";
    static final String FRAME = DIR + "/frame.rgbx";
    static final String PROP = "vendor.debug.hwc.shim.standby";
    static final int W = 2480, H = 1860, BYTES = W * H * 4;
    // the keyguard's block (clock top-left at 332,53 with the small clock, see LockStyler): the date takes its place
    static final float BLOCK_X = 332f, BLOCK_Y = 53f, DATE_PX = 110f, BATTERY_PX = 44f, GAP_PX = 14f;
    static final float PLATE_PAD_X = 28f, PLATE_PAD_Y = 18f, PLATE_R = 30f;   // page-colour plate over a picture

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sleep-render");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private Renderer() {}

    /** Queue a render (coalesced by the signature check inside). `advance` = a wake: the next picture of a folder. */
    static void request(Context c, String why, boolean advance) {
        Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try { render(app, why, advance, false, 0, null); }
            catch (Throwable t) { Log.w(TAG, "render failed (" + why + ")", t); }
        });
    }

    static void request(Context c, String why) { request(c, why, false); }

    /** Run a render now on the calling thread; returns a one-line result for logs and the debug receiver. */
    static synchronized String render(Context c, String why, boolean advance, boolean png, int rotOverride, String themeOverride) {
        long t0 = android.os.SystemClock.uptimeMillis();
        Prefs p = new Prefs(c);
        String mode = p.mode();
        if (Prefs.MODE_OFF.equals(mode)) {
            String r = Root.run("setprop " + PROP + " 0; rm -f " + FRAME + " " + FRAME + ".tmp; echo ok");
            p.p.edit().putString(Prefs.S_SIG, "off").putInt(Prefs.S_STANDBY, 0).putLong(Prefs.S_BOOT, Prefs.bootEpoch())
                    .putString(Prefs.S_LAST_FILE, "").putString(Prefs.S_LAST_ERROR, "ok".equals(r) ? "" : r).apply();
            Log.i(TAG, "sleep picture off (" + why + "): " + r);
            return "off: " + r;
        }
        boolean night = themeOverride != null ? "dark".equals(themeOverride)
                : (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int rot = rotOverride != 0 ? rotOverride : p.rot();
        String date = p.showDate() ? dateText(c) : "";
        String battery = p.showBattery() ? batteryText(c) : "";
        boolean h24 = DateFormat.is24HourFormat(c);
        boolean time = p.showTime() && Clock.shimDraws();   // the shim writes the time at power-off (Clock)
        Pictures.Choice choice = Pictures.choose(c, p, night, advance);   // names only: no decode before the skip check
        String name = choice == null ? "(none)" : choice.name;
        String sig = mode + "|" + p.source() + "|" + name + "|" + (night ? "dark" : "light") + "|" + p.scale() + "|" + date + "|"
                + battery + "|" + (time ? Clock.zoneKey(h24) : "-") + "|" + rot + "|" + BuildInfo.CODE;
        p.p.edit().putString(Prefs.S_CLOCK, !p.showTime() ? "off" : time ? "on" : "needs eink-compat 0.1.48").apply();
        long boot = Prefs.bootEpoch();
        if (!advance && !png && themeOverride == null && rotOverride == 0 && sig.equals(p.p.getString(Prefs.S_SIG, ""))
                && p.p.getLong(Prefs.S_BOOT, -1) == boot && p.p.getInt(Prefs.S_STANDBY, 0) > 0) {
            Log.i(TAG, "unchanged, skipped (" + why + ")");
            return "unchanged";
        }
        Bitmap pic = Pictures.load(c, choice);
        // theme-correct palette, also for the debug override
        Context tc = c;
        if (themeOverride != null) {
            Configuration conf = new Configuration(c.getResources().getConfiguration());
            conf.uiMode = (conf.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            tc = c.createConfigurationContext(conf);
        }
        Ink.Pal pal = Ink.pal(tc);
        Scene s = new Scene(pal, pic, p.scale(), date, battery, time, h24, null, Ink.display(c), Ink.font(c, 500));

        Bitmap frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(frame);
        if (rot == 90) { cv.translate(W, 0); cv.rotate(90); } else { cv.translate(0, H); cv.rotate(270); }
        s.draw(cv, 1f);
        String result;
        try {
            File tmp = new File(c.getFilesDir(), "frame.rgbx.tmp"), ctmp = new File(c.getFilesDir(), "clock.bin.tmp");
            ByteBuffer buf = ByteBuffer.allocateDirect(BYTES);
            if (frame.getRowBytes() != W * 4) throw new IllegalStateException("row bytes " + frame.getRowBytes());
            frame.copyPixelsToBuffer(buf);
            buf.rewind();
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.getChannel().write(buf); }
            int seq = Math.max(1, p.p.getInt(Prefs.S_STANDBY, 0) + 1);
            if (p.p.getLong(Prefs.S_BOOT, -1) != boot) seq = 1;
            // clock.bin carries the frame's counter: it is in place before the counter announces the frame
            byte[] clock = s.spot != null ? Clock.build(s.spot, seq, h24, rot, pal.display) : null;
            if (clock != null) try (FileOutputStream o = new FileOutputStream(ctmp)) { o.write(clock); }
            String clockCmd = clock != null
                    ? " && cp -f $C $D/clock.bin.tmp && chown root:root $D/clock.bin.tmp && chmod 0644 $D/clock.bin.tmp && mv -f $D/clock.bin.tmp $D/clock.bin"
                    : " && rm -f $D/clock.bin";
            String cmd = "D=" + DIR + "; S=" + tmp.getAbsolutePath() + "; C=" + ctmp.getAbsolutePath()
                    + "; mkdir -p $D && chown root:root /data/vendor/b1051c $D"
                    + " && chmod 0755 /data/vendor/b1051c $D && cp -f $S $D/frame.rgbx.tmp && chown root:root $D/frame.rgbx.tmp"
                    + " && chmod 0644 $D/frame.rgbx.tmp && [ \"$(stat -c %s $D/frame.rgbx.tmp)\" = " + BYTES + " ]" + clockCmd
                    + " && mv -f $D/frame.rgbx.tmp $D/frame.rgbx && restorecon -R /data/vendor/b1051c >/dev/null 2>&1; setprop " + PROP + " " + seq + " && echo ok";
            String r = Root.run(cmd);
            tmp.delete();
            ctmp.delete();
            boolean ok = "ok".equals(r);
            p.p.edit().putString(Prefs.S_SIG, ok ? sig : "").putInt(Prefs.S_STANDBY, ok ? seq : 0).putLong(Prefs.S_BOOT, boot)
                    .putLong(Prefs.S_RENDERED_AT, System.currentTimeMillis()).putString(Prefs.S_LAST_FILE, name)
                    .putString(Prefs.S_LAST_ERROR, ok ? "" : ("publish: " + r)).putLong(Prefs.S_LAST_MS, android.os.SystemClock.uptimeMillis() - t0).apply();
            result = (ok ? "frame #" + seq : "publish failed: " + r) + " " + name + " " + (night ? "dark" : "light") + " rot " + rot + " "
                    + (android.os.SystemClock.uptimeMillis() - t0) + " ms";
            Log.i(TAG, "rendered (" + why + "): " + result);
        } catch (Exception e) {
            p.p.edit().putString(Prefs.S_LAST_ERROR, "write: " + e).apply();
            Log.w(TAG, "write failed", e);
            result = "write failed: " + e;
        } finally {
            frame.recycle();
        }
        if (png) {
            try { savePng(c, s, new File(c.getFilesDir(), "preview.png")); } catch (Exception e) { Log.w(TAG, "preview png: " + e); }
        }
        if (pic != null) pic.recycle();
        return result;
    }

    /** A portrait preview of the current settings (the settings screen's dialog), scaled from the page. */
    static Bitmap preview(Context c, float scale) {
        Prefs p = new Prefs(c);
        boolean night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        Bitmap pic = Prefs.MODE_OFF.equals(p.mode()) ? null : Pictures.load(c, Pictures.choose(c, p, night, false));
        boolean time = p.showTime() && Clock.shimDraws(), h24 = DateFormat.is24HourFormat(c);
        String sample = h24 ? DateFormat.format("HH:mm", System.currentTimeMillis()).toString()
                : DateFormat.format("h:mm a", System.currentTimeMillis()).toString().toUpperCase(Locale.ROOT);
        Scene s = new Scene(Ink.pal(c), pic, p.scale(), p.showDate() ? dateText(c) : "",
                p.showBattery() ? batteryText(c) : "", time, h24, sample, Ink.display(c), Ink.font(c, 500));
        Bitmap b = Bitmap.createBitmap(Math.round(Pictures.PAGE_W * scale), Math.round(Pictures.PAGE_H * scale), Bitmap.Config.ARGB_8888);
        s.draw(new Canvas(b), scale);
        if (pic != null) pic.recycle();
        return b;
    }

    private static void savePng(Context c, Scene s, File out) throws java.io.IOException {
        Bitmap b = Bitmap.createBitmap(Pictures.PAGE_W / 2, Pictures.PAGE_H / 2, Bitmap.Config.ARGB_8888);
        s.draw(new Canvas(b), 0.5f);
        try (FileOutputStream o = new FileOutputStream(out)) { b.compress(Bitmap.CompressFormat.PNG, 90, o); }
        b.recycle();
    }

    static String dateText(Context c) {
        Locale l = c.getResources().getConfiguration().getLocales().get(0);
        return DateFormat.format(DateFormat.getBestDateTimePattern(l, "EEEEdMMMM"), System.currentTimeMillis()).toString();
    }

    /** "87 %" or "87 % · charging"; bucketed to 5 % above 20 % so a 1 % tick does not re-render the frame. */
    static String batteryText(Context c) {
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return "";
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level < 0 || scale <= 0) return "";
        int pct = level * 100 / scale;
        if (pct > 20) pct = (pct / 5) * 5;
        boolean plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        return pct + " %" + (plugged ? " · charging" : "");
    }

    /** What is drawn, in portrait page coordinates; the canvas transform decides the raw layout. With `time`, line 2
     *  ends in "asleep since" and then the time: `sample` in a preview, else an empty box noted in `spot` (k = 1)
     *  that the shim fills at power-off. */
    static final class Scene {
        final Ink.Pal pal; final Bitmap pic; final String scale, date, battery, sample; final boolean time, h24;
        final android.graphics.Typeface display, text;
        Clock.Spot spot;
        Scene(Ink.Pal pal, Bitmap pic, String scale, String date, String battery, boolean time, boolean h24, String sample,
              android.graphics.Typeface display, android.graphics.Typeface text) {
            this.pal = pal; this.pic = pic; this.scale = scale; this.date = date; this.battery = battery; this.time = time;
            this.h24 = h24; this.sample = sample; this.display = display; this.text = text;
        }

        void draw(Canvas cv, float k) {
            cv.drawColor(pal.paper);
            if (pic != null) {
                float pw = Pictures.PAGE_W * k, ph = Pictures.PAGE_H * k;
                float f = Prefs.SCALE_FILL.equals(scale) ? Math.max(pw / pic.getWidth(), ph / pic.getHeight())
                                                         : Math.min(pw / pic.getWidth(), ph / pic.getHeight());
                float dw = pic.getWidth() * f, dh = pic.getHeight() * f;
                RectF dst = new RectF((pw - dw) / 2f, (ph - dh) / 2f, (pw + dw) / 2f, (ph + dh) / 2f);
                Paint pp = new Paint(Paint.FILTER_BITMAP_FLAG);
                cv.save();
                cv.clipRect(0, 0, pw, ph);
                cv.drawBitmap(pic, null, dst, pp);
                cv.restore();
            }
            Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
            dp.setTypeface(display);
            dp.setTextSize(DATE_PX * k);
            dp.setColor(pal.display);
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setTypeface(text);
            bp.setTextSize(BATTERY_PX * k);
            bp.setColor(pal.display);
            bp.setLetterSpacing(0.03f);
            Paint.FontMetrics dfm = dp.getFontMetrics(), bfm = bp.getFontMetrics();
            String line2 = !time ? battery : battery.isEmpty() ? "Asleep since " : battery + " · asleep since ";
            // layout first: the top of the date's glyphs at BLOCK_Y, line 2 under it
            float y = BLOCK_Y * k, yDate = 0, y2 = 0, bottom = y;
            if (!date.isEmpty()) { yDate = y - dfm.ascent; y = yDate + dfm.descent + GAP_PX * k; bottom = yDate + dfm.descent; }
            if (!line2.isEmpty()) { y2 = y - bfm.ascent; bottom = y2 + bfm.descent; }
            float x2 = (BLOCK_X + 3f) * k;
            if (pic != null && bottom > BLOCK_Y * k) {
                // a picture behind the text (a busy book cover): a page-colour plate, as the lock screen's pill behind
                // "Charged" (einkui 0.4.8), wide enough for the widest time the shim may write
                float w = date.isEmpty() ? 0 : dp.measureText(date);
                if (!line2.isEmpty()) w = Math.max(w, 3f * k + bp.measureText(line2) + (time ? Clock.widest(bp, h24) : 0));
                Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
                plate.setColor(pal.paper);
                cv.drawRoundRect(BLOCK_X * k - PLATE_PAD_X * k, BLOCK_Y * k - PLATE_PAD_Y * k, BLOCK_X * k + w + PLATE_PAD_X * k,
                        bottom + PLATE_PAD_Y * k, PLATE_R * k, PLATE_R * k, plate);
            }
            if (!date.isEmpty()) cv.drawText(date, BLOCK_X * k, yDate, dp);
            if (!line2.isEmpty()) {
                cv.drawText(line2, x2, y2, bp);
                if (time) {
                    float tx = x2 + bp.measureText(line2);
                    if (sample != null) cv.drawText(sample, tx, y2, bp);
                    else if (k == 1f) spot = new Clock.Spot(tx, y2, new Paint(bp));
                }
            }
            Rect r = cv.getClipBounds();   // keep the compiler quiet about unused imports in some builds
            if (r == null) Log.v(TAG, "no clip");
        }
    }
}
