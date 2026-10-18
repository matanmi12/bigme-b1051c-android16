package org.b1051c.einkctl;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import java.util.List;

/**
 * Full-refresh button in the navigation-button row: bottom-left, the mirror image of Recents (same size and height),
 * glyph tinted with the colour sampled from the Recents glyph. Shown only while the nav buttons are visible.
 * Only the nav bar window is inspected (bounds of its back/recents buttons); nothing else is read.
 */
final class NavRefresh {
    private static final String[] NAV_PKGS = {"com.android.launcher3", "com.android.systemui"};

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private ImageView view;
    private final Rect shown = new Rect();      // overlay rect on screen; empty = not attached
    private final Rect target = new Rect();     // where the nav buttons say it should be
    private final Rect sampleAt = new Rect();   // Recents button (colour source)
    private int tint;                           // 0 = not sampled yet
    private long lastShot;

    NavRefresh(AccessibilityService s) {
        svc = s;
        wm = s.getSystemService(WindowManager.class);
    }

    /** Called on window changes; debounced so a burst of events costs one lookup. */
    void update() {
        h.removeCallbacks(relayout);
        h.postDelayed(relayout, 300);
    }

    void destroy() {
        h.removeCallbacksAndMessages(null);
        hide();
    }

    private final Runnable relayout = this::relayout;

    private void relayout() {
        if (!Eink.prefs(svc).getBoolean("nav_refresh", true) || !findNavButtons()) { hide(); return; }
        sampleTint();
        if (tint == 0) return;                   // first show waits for the colour, avoiding a second e-ink update
        show();
    }

    /** Fills target (mirrored Recents, or Back) and sampleAt from the visible nav buttons; false if none. */
    private boolean findNavButtons() {
        List<AccessibilityWindowInfo> wins;
        try { wins = svc.getWindows(); } catch (RuntimeException e) { return false; }
        int screenW = svc.getResources().getDisplayMetrics().widthPixels;
        int screenH = svc.getResources().getDisplayMetrics().heightPixels;
        for (AccessibilityWindowInfo w : wins) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || root.getPackageName() == null) continue;
            String pkg = root.getPackageName().toString();
            for (String p : NAV_PKGS) {
                if (!p.equals(pkg)) continue;
                Rect r = visibleBounds(root, p + ":id/recent_apps");
                if (r == null) r = visibleBounds(root, p + ":id/back");
                if (r == null || r.top < screenH / 2 || r.width() > screenW / 3) continue;   // bottom row only
                sampleAt.set(r);
                target.set(screenW - r.right, r.top, screenW - r.left, r.bottom);
                return true;
            }
        }
        return false;
    }

    private static Rect visibleBounds(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(id);
        for (AccessibilityNodeInfo n : l) {
            if (!n.isVisibleToUser()) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (!r.isEmpty()) return r;
        }
        return null;
    }

    /** Takes the colour of the Recents glyph from a screenshot (at most about once a second). */
    private void sampleTint() {
        long now = SystemClock.uptimeMillis();
        if (now - lastShot < 1000) { if (tint == 0) h.postDelayed(relayout, 1000); return; }
        lastShot = now;
        final Rect at = new Rect(sampleAt);
        svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult res) {
                int c = 0;
                try (HardwareBuffer hb = res.getHardwareBuffer()) {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                    if (hw != null) {
                        Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                        c = glyphColour(sw, at);
                        sw.recycle();
                    }
                } catch (RuntimeException ignored) {}
                if (c == 0 && tint == 0) c = Color.WHITE;   // unreadable (e.g. secure window): system default
                if (c != 0 && c != tint) {
                    tint = c;
                    if (view != null) view.setColorFilter(tint);
                    if (shown.isEmpty()) show();
                }
            }

            @Override public void onFailure(int code) {
                if (tint == 0) { tint = Color.WHITE; show(); }
            }
        });
    }

    /** The pixel farthest from the button's corner colour = the glyph; 0 when nothing stands out. */
    private static int glyphColour(Bitmap b, Rect r) {
        Rect c = new Rect(r);
        if (!c.intersect(0, 0, b.getWidth(), b.getHeight()) || c.width() < 4 || c.height() < 4) return 0;
        int bg = b.getPixel(c.left + 1, c.top + 1);
        int best = 0, bestD = 0;
        for (int y = c.top; y < c.bottom; y += 1)
            for (int x = c.left; x < c.right; x += 1) {
                int p = b.getPixel(x, y);
                int d = Math.abs(Color.red(p) - Color.red(bg)) + Math.abs(Color.green(p) - Color.green(bg))
                        + Math.abs(Color.blue(p) - Color.blue(bg));
                if (d > bestD) { bestD = d; best = p; }
            }
        return bestD >= 90 ? (best | 0xFF000000) : 0;
    }

    private void show() {
        if (target.isEmpty() || tint == 0) return;
        if (view == null) {
            view = new ImageView(svc);
            view.setImageResource(R.drawable.ic_nav_refresh);
            view.setScaleType(ImageView.ScaleType.CENTER);
            view.setContentDescription("Refresh screen");
            view.setOnClickListener(v -> Eink.clearScreen(svc));
            view.setOnLongClickListener(v -> {
                svc.startActivity(new Intent(svc, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            });
        }
        view.setColorFilter(tint);
        if (target.equals(shown)) return;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(target.width(), target.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = target.left;
        lp.y = target.top;
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.windowAnimations = 0;
        lp.setTitle("EinkNavRefresh");
        try {
            if (shown.isEmpty()) wm.addView(view, lp); else wm.updateViewLayout(view, lp);
            shown.set(target);
        } catch (RuntimeException e) {
            shown.setEmpty();
        }
    }

    private void hide() {
        if (view != null && !shown.isEmpty()) {
            try { wm.removeView(view); } catch (RuntimeException ignored) {}
        }
        shown.setEmpty();
    }
}
