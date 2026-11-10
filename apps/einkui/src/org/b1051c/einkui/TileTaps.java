package org.b1051c.einkui;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.WeakHashMap;

/**
 * Plain tap / long-press timing for Quick Settings tiles (bug 2026-11-09: a normal tap on a tile body opened E-Ink
 * Control's App info, and Dark theme opened its settings page instead of toggling).
 *
 * Cause: A16's QSTileViewImpl decides long-press with QSLongPressEffect, not with a timer. ACTION_DOWN starts the
 * "squish" ValueAnimator after the tap timeout (100 ms), and the long click fires when that animation ends while
 * the finger is still down. We run with animator_duration_scale 0 (e-ink), so the animation ends on the next frame:
 * every press held longer than about 100 ms became a long click. Measured: 100/200/300 ms presses were all long
 * clicks with scale 0, normal clicks with scale 1. An instant down+up (adb input tap) always clicked. The visual-
 * haptics flag is read-only on this build.
 *
 * Fix: this listener holds the gesture and decides by ViewConfiguration timing, as ordinary Android views do:
 *  - lifted before the long-press timeout without leaving the touch slop: the tile gets DOWN + UP back to back
 *    (its own onTouchEvent; the same path as the instant tap that works, so its click, pressed state, sound and
 *    effect state all stay SystemUI's own);
 *  - held for the long-press timeout: DOWN + CANCEL (resets the effect from a stale CLICKED / LONG_CLICKED to IDLE)
 *    and then performLongClick(), which SystemUI's long-click listener accepts in IDLE;
 *  - moved past the slop, a second finger, or a CANCEL from a parent (shade swipe, page scroll): nothing.
 * Accessibility clicks (TalkBack) do not pass through here. SystemUI sets no touch listener on tiles itself (only
 * on their containers), so nothing of its own is replaced. Settings.Secure b1051c_qs_tap 0 = Android's behaviour.
 */
final class TileTaps implements View.OnTouchListener {
    static final String KEY = "b1051c_qs_tap";

    private final Handler h = new Handler(Looper.getMainLooper());
    private final WeakHashMap<View, Boolean> installed = new WeakHashMap<>();
    private View active;
    private MotionEvent down;
    private int slop = -1;
    private int errors;

    private final Runnable longPress = () -> {
        View v = active;
        MotionEvent d = down;
        active = null;
        down = null;                                      // ours now: recycled below, not by reset()
        if (v == null || d == null) return;
        try {
            MotionEvent cancel = MotionEvent.obtain(d);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            v.onTouchEvent(d);
            v.onTouchEvent(cancel);
            cancel.recycle();
            v.performLongClick();
        } catch (Throwable t) {
            fail(t);
        } finally {
            d.recycle();
        }
    };

    /** Called for every tile on every walk; installs once. */
    void attach(View tile) {
        if (installed.containsKey(tile)) return;
        tile.setOnTouchListener(this);
        installed.put(tile, Boolean.TRUE);
    }

    /** Kill switch off or plugin shutdown: every tile back to SystemUI's own touch handling. */
    void detachAll() {
        h.removeCallbacks(longPress);
        reset();
        for (View t : installed.keySet().toArray(new View[0])) t.setOnTouchListener(null);
        installed.clear();
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        try {
            if (!v.isEnabled()) return false;                 // SystemUI's own handling (it ignores the tap)
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    h.removeCallbacks(longPress);
                    reset();
                    if (slop < 0) slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                    active = v;
                    down = MotionEvent.obtain(e);
                    if (v.isLongClickable()) h.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (active == v && down != null && (Math.abs(e.getX() - down.getX()) > slop
                            || Math.abs(e.getY() - down.getY()) > slop)) abandon();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (active == v && down != null) {
                        h.removeCallbacks(longPress);
                        MotionEvent d = down;
                        down = null;
                        active = null;
                        MotionEvent up = MotionEvent.obtain(e);
                        up.setLocation(d.getX(), d.getY());          // inside the tile, as the press was
                        v.onTouchEvent(d);
                        v.onTouchEvent(up);
                        up.recycle();
                        d.recycle();
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    abandon();
                    return true;
                default:
                    return true;
            }
        } catch (Throwable t) {
            fail(t);
            abandon();
            return false;
        }
    }

    private void abandon() {
        h.removeCallbacks(longPress);
        reset();
    }

    private void reset() {
        active = null;
        if (down != null) down.recycle();
        down = null;
    }

    private void fail(Throwable t) {
        if (errors++ < 5) Log.w(EinkOverlayPlugin.TAG, "tile tap failed", t);
    }
}
