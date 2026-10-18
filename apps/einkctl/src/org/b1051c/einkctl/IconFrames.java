package org.b1051c.einkctl;

import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Bigme-style frames for the private icon pack (Lawnchair has no iconback/iconmask/iconupon yet): every launchable
 * app's own icon, cropped to Bigme's squircle (stock ic_menu_app_bg_1051) on white, with Bigme's black ring
 * (4/120 of the tile) on the edge. Output: files/frames/<package>__<activity>.png + files/frames/index.json,
 * pulled by tools/scripts/build-iconpack.py. Icons that the RRO module already replaced come out as the Bigme icon;
 * the pack builder uses the Bigme drawable for those instead of the frame.
 */
final class IconFrames {
    private IconFrames() {}

    /** Bigme's squircle, 60-unit viewport (stock ic_menu_app_bg_1051). */
    private static Path squircle(float size, float inset) {
        Path p = new Path();
        p.moveTo(0, 30);
        p.cubicTo(0, 54.7f, 5.29f, 60, 30, 60);
        p.cubicTo(54.7f, 60, 60, 54.7f, 60, 30);
        p.cubicTo(60, 5.29f, 54.7f, 0, 30, 0);
        p.cubicTo(5.29f, 0, 0, 5.29f, 0, 30);
        p.close();
        Matrix m = new Matrix();
        m.setRectToRect(new RectF(0, 0, 60, 60), new RectF(inset, inset, size - inset, size - inset), Matrix.ScaleToFit.FILL);
        p.transform(m);
        return p;
    }

    static Bitmap frame(Drawable icon, int size) {
        Bitmap art = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(art);
        c.drawColor(Color.WHITE);
        if (icon instanceof AdaptiveIconDrawable) {
            // the adaptive icon's visible 72/108 area fills the tile: draw the full layers 1.5x around it
            int extra = size / 4;
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            if (a.getBackground() != null) { a.getBackground().setBounds(-extra, -extra, size + extra, size + extra); a.getBackground().draw(c); }
            if (a.getForeground() != null) { a.getForeground().setBounds(-extra, -extra, size + extra, size + extra); a.getForeground().draw(c); }
        } else {
            int pad = Math.round(size * 0.14f);                            // legacy icon centred on white
            icon.setBounds(pad, pad, size - pad, size - pad);
            icon.draw(c);
        }
        // anti-aliased squircle crop (DST_IN with an AA mask), then the ring on its edge
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas o = new Canvas(out);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        o.drawPath(squircle(size, 0), fill);
        Paint in = new Paint(Paint.FILTER_BITMAP_FLAG);
        in.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        o.drawBitmap(art, 0, 0, in);
        float sw = size * 4f / 120f;
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(sw);
        ring.setColor(Color.BLACK);
        o.drawPath(squircle(size, sw / 2), ring);
        art.recycle();
        return out;
    }

    static void renderAll(Context c, int size) {
        File dir = new File(c.getFilesDir(), "frames");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        dir.mkdirs();
        LauncherApps la = c.getSystemService(LauncherApps.class);
        org.json.JSONArray index = new org.json.JSONArray();
        for (LauncherActivityInfo ai : la.getActivityList(null, android.os.Process.myUserHandle())) {
            String pkg = ai.getComponentName().getPackageName(), cls = ai.getComponentName().getClassName();
            String name = (pkg + "__" + cls).replaceAll("[^A-Za-z0-9_.]", "_") + ".png";
            try {
                Bitmap b = frame(ai.getIcon(640), size);
                try (FileOutputStream os = new FileOutputStream(new File(dir, name))) { b.compress(Bitmap.CompressFormat.PNG, 100, os); }
                b.recycle();
                index.put(new org.json.JSONObject().put("package", pkg).put("activity", cls)
                        .put("label", String.valueOf(ai.getLabel())).put("file", name));
            } catch (Exception e) {
                Log.w("einkctl", "frame failed for " + pkg + ": " + e);
            }
        }
        try (FileOutputStream os = new FileOutputStream(new File(dir, "index.json"))) {
            os.write(index.toString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w("einkctl", "index failed: " + e);
        }
        Log.i("einkctl", "frames rendered: " + index.length() + " apps -> " + dir);
    }
}
