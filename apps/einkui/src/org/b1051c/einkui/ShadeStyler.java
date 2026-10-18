package org.b1051c.einkui;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RenderEffect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.WeakHashMap;

/**
 * Walks the shade before each draw and keeps it in the e-ink style:
 *  - QS tiles: SystemUI's own colour fields are replaced (so its state logic keeps working) and the background shape
 *    becomes a solid fill + outline; inactive = page colour with outline, active = filled (inverted text/icon).
 *  - scrims behind QS/notifications: opaque page colour while the shade is open (no translucent grey over content).
 *  - shade header (clock, date, status icons) in the text colour; Compose footer gets a grey/invert colour filter.
 *  - brightness slider -> LightSliders (always, also with the theme "off").
 * Theme: Settings.Secure b1051c_shade_theme = 0 follow Dark theme (default), 1 light, 2 dark, -1 off (vanilla).
 * Outline width: b1051c_shade_stroke in tenths of dp (default 15 = 1.5 dp).
 */
final class ShadeStyler implements ViewTreeObserver.OnPreDrawListener {
    /** Read by other plugin builds through reflection (see EinkOverlayPlugin.setup). */
    static final int BUILD = Build.CODE;
    static final String KEY_THEME = "b1051c_shade_theme";
    static final String KEY_STROKE = "b1051c_shade_stroke";

    private static final String[] TILE_FIELDS = {"colorActive", "colorInactive", "colorUnavailable",
            "colorLabelActive", "colorLabelInactive", "colorLabelUnavailable", "colorSecondaryLabelActive",
            "colorSecondaryLabelInactive", "colorSecondaryLabelUnavailable", "overlayColorActive", "overlayColorInactive"};

    private final Context sysui;
    private final View root;
    private final Resources res;
    private final ContentResolver cr;
    private final LightSliders sliders;
    private final Handler h = new Handler(Looper.getMainLooper());

    /** Resolved style: 0 = vanilla, 1 = light, 2 = dark. */
    int mode = -1;
    int fg, bg, mid;
    float stroke;
    private int pref, strokePref;
    private int gen;
    private int errors;

    private final int idQsFrame, idBrightness, idFooterActions, idTileBase, idScrimBehind, idScrimNotif,
            idSplitHeader, idHeaderIcons, idClock, idDate, idCarrier, idQsFooter, idQsPanel, idQqsPanel;
    private final WeakHashMap<View, Object[]> tiles = new WeakHashMap<>();     // tile -> {gen, orig colours, orig bgs, our ripple}
    private final WeakHashMap<View, Integer> headerGen = new WeakHashMap<>();
    private final WeakHashMap<View, Boolean> scrimForced = new WeakHashMap<>();
    private View footer;
    private int footerGen = -1;
    private PorterDuffColorFilter scrimFilter;

    private final ContentObserver obs;

    ShadeStyler(Context sysui, Context plugin, View root) {
        this.sysui = sysui;
        this.root = root;
        res = root.getResources();
        cr = sysui.getContentResolver();
        sliders = new LightSliders(sysui, plugin);
        idQsFrame = id("qs_frame");
        idBrightness = id("brightness_slider");
        idFooterActions = id("qs_footer_actions");
        idTileBase = id("qs_tile_background_base");
        idScrimBehind = id("scrim_behind");
        idScrimNotif = id("scrim_notifications");
        idSplitHeader = id("split_shade_status_bar");
        idHeaderIcons = id("shade_header_system_icons");
        idClock = id("clock");
        idDate = id("date");
        idCarrier = id("carrier_group");
        idQsFooter = id("qs_footer");                       // page dots + edit pencil
        idQsPanel = id("quick_settings_panel");
        idQqsPanel = id("quick_qs_panel");
        obs = new ContentObserver(h) {
            @Override public void onChange(boolean selfChange) { readPrefs(); root.invalidate(); }
        };
        readPrefs();
    }

    private int id(String name) { return res.getIdentifier(name, "id", "com.android.systemui"); }

    void start() {
        cr.registerContentObserver(Settings.Secure.getUriFor(KEY_THEME), false, obs);
        cr.registerContentObserver(Settings.Secure.getUriFor(KEY_STROKE), false, obs);
        root.getViewTreeObserver().addOnPreDrawListener(this);
        root.invalidate();
    }

    private boolean destroyed;

    void destroy() {
        if (destroyed) return;
        destroyed = true;
        root.getViewTreeObserver().removeOnPreDrawListener(this);
        cr.unregisterContentObserver(obs);
        sliders.destroy();
        mode = 0;
        gen++;
        for (View t : tiles.keySet().toArray(new View[0])) restoreTile(t);
        for (View s : scrimForced.keySet().toArray(new View[0])) unforceScrim(s);
        if (footer != null) footer.setRenderEffect(null);
    }

    private void readPrefs() {
        pref = Settings.Secure.getInt(cr, KEY_THEME, 0);
        strokePref = Settings.Secure.getInt(cr, KEY_STROKE, 15);
    }

    private void resolveMode() {
        boolean night = (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int m = pref == -1 ? 0 : pref == 1 ? 1 : pref == 2 ? 2 : (night ? 2 : 1);
        float s = strokePref / 10f * res.getDisplayMetrics().density;
        if (m == mode && s == stroke) return;
        mode = m;
        stroke = s;
        fg = m == 2 ? 0xFFFFFFFF : 0xFF000000;
        bg = m == 2 ? 0xFF000000 : 0xFFFFFFFF;
        mid = m == 2 ? 0xFF8C8C8C : 0xFF7A7A7A;
        scrimFilter = new PorterDuffColorFilter(bg, PorterDuff.Mode.SRC_IN);
        gen++;
        Log.i(EinkOverlayPlugin.TAG, "style mode=" + m + " stroke=" + s + "px");
    }

    @Override
    public boolean onPreDraw() {
        try {
            resolveMode();
            // "open" = QS or quick QS actually on screen (also over the lock screen); the bare lock screen keeps
            // its own scrim so the wallpaper/clock look is untouched
            boolean shadeOpen = onScreen(root.findViewById(idQsPanel)) || onScreen(root.findViewById(idQqsPanel));
            styleScrim(root.findViewById(idScrimBehind), shadeOpen && mode != 0);
            styleScrim(root.findViewById(idScrimNotif), shadeOpen && mode != 0);
            debugTiles = debug() && android.os.SystemClock.uptimeMillis() - lastDump > 3000;
            walk(root, false);
            if (debug()) dump(root, 0);
        } catch (Throwable t) {
            if (errors++ < 5) Log.w(EinkOverlayPlugin.TAG, "frame failed", t);
        }
        return true;
    }

    private long lastDump;
    private boolean debugTiles;
    private boolean debug() { return Settings.Secure.getInt(cr, "b1051c_shade_debug", 0) == 1; }

    /** Debug aid (Settings.Secure b1051c_shade_debug=1): one line per interesting view, at most every 3 s. */
    private void dump(View v, int depth) {
        if (depth == 0) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastDump < 3000) return;
            lastDump = now;
        }
        String idName = "";
        try { if (v.getId() > 0) idName = res.getResourceEntryName(v.getId()); } catch (Resources.NotFoundException ignored) {}
        android.graphics.drawable.Drawable bgd = v.getBackground();
        if (!idName.isEmpty() || bgd != null || depth < 3)
            Log.d(EinkOverlayPlugin.TAG, "  ".repeat(Math.min(depth, 20)) + v.getClass().getSimpleName() + " " + idName
                    + " vis=" + v.getVisibility() + " a=" + v.getAlpha() + " " + v.getWidth() + "x" + v.getHeight()
                    + (bgd != null ? " bg=" + bgd.getClass().getSimpleName() : ""));
        if (v instanceof ViewGroup && depth < 40) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), depth + 1);
        }
    }

    private static final android.graphics.Rect TMP = new android.graphics.Rect();

    private static boolean onScreen(View v) {
        return v != null && v.isShown() && v.getAlpha() > 0.01f && v.getGlobalVisibleRect(TMP) && TMP.height() > 0;
    }

    private void walk(View v, boolean inHeader) { walk(v, inHeader, false); }

    private void walk(View v, boolean inHeader, boolean inNotif) {
        int id = v.getId();
        if (id == idBrightness && id != 0) {
            // only the QS panel's slider; the brightness mirror (another brightness_slider, normally GONE) is left alone
            if (v.getParent() != null && v.getParent().getClass().getName().endsWith(".QSPanel")) sliders.attach(v, this);
            return;
        }
        if (v.getVisibility() == View.GONE) return;
        if (id == idFooterActions && id != 0) { styleFooter(v); return; }
        if (mode != 0) {
            String cn = v.getClass().getName();
            if (cn.endsWith(".QSTileViewImpl")) { styleTile(v); return; }
            if (cn.endsWith(".NotificationBackgroundView")) { styleNotificationBg(v); return; }
            if (cn.endsWith(".ExpandableNotificationRow") || cn.endsWith(".FooterView")) inNotif = true;
            if (inNotif) styleNotificationChild(v);
        }
        boolean header = inHeader || (id != 0 && (id == idSplitHeader || id == idHeaderIcons || id == idCarrier
                || id == idQsFooter));
        if (header || (id != 0 && (id == idClock || id == idDate))) styleHeaderView(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), header, inNotif);
        }
    }

    // ---- tiles ------------------------------------------------------------------------------------
    // Tile backgrounds come from SystemUI's resource cache; changing them in place leaks into the cached state and
    // every tile ends up drawing one shared LayerDrawable. So each tile gets its own background (ripple > layers >
    // TileShape + overlay) and SystemUI's fields are pointed at it; its state logic then tints our TileShape.
    private static final String[] BG_FIELDS = {"qsTileBackground", "backgroundDrawable", "backgroundBaseDrawable",
            "backgroundOverlayDrawable"};

    private void styleTile(View t) {
        Object[] st = tiles.get(t);
        if (st == null) {
            int[] orig = new int[TILE_FIELDS.length];
            for (int i = 0; i < orig.length; i++) orig[i] = Refl.getInt(t, TILE_FIELDS[i], 0);
            Object[] bgs = new Object[BG_FIELDS.length];
            for (int i = 0; i < bgs.length; i++) bgs[i] = Refl.get(t, BG_FIELDS[i]);
            st = new Object[] {-1, orig, bgs, null};
            tiles.put(t, st);
        }
        int state = Refl.getInt(t, "lastState", 1);
        TileShape shape = st[3] instanceof android.graphics.drawable.RippleDrawable
                && t.getBackground() == st[3] ? (TileShape) Refl.get(t, "backgroundBaseDrawable") : null;
        if ((int) st[0] != gen || shape == null) {
            st[0] = gen;
            int[] c = {fg, bg, bg, bg, fg, mid, bg, fg, mid, 0, 0};
            for (int i = 0; i < c.length; i++) Refl.set(t, TILE_FIELDS[i], c[i]);
            if (shape == null) {
                int rid = res.getIdentifier("qs_corner_radius", "dimen", "com.android.systemui");
                float radius = rid != 0 ? res.getDimension(rid) : 28 * res.getDisplayMetrics().density;
                shape = new TileShape(radius, stroke, fg, bg);
                android.graphics.drawable.ColorDrawable overlay = new android.graphics.drawable.ColorDrawable(0);
                LayerDrawable ld = new LayerDrawable(new Drawable[] {shape, overlay});
                ld.setId(0, idTileBase);
                ld.setId(1, res.getIdentifier("qs_tile_background_overlay", "id", "com.android.systemui"));
                // no visible ripple: on e-ink it would only cost extra refreshes
                android.graphics.drawable.RippleDrawable ripple = new android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(0), ld, null);
                ripple.setId(0, res.getIdentifier("background", "id", "com.android.systemui"));
                Refl.set(t, "qsTileBackground", ripple);
                Refl.set(t, "backgroundDrawable", ld);
                Refl.set(t, "backgroundBaseDrawable", shape);
                Refl.set(t, "backgroundOverlayDrawable", overlay);
                t.setBackground(ripple);
                st[3] = ripple;
            }
            Refl.call(t, "setAllColors", new Class<?>[] {int.class, int.class, int.class, int.class, int.class},
                    state == 2 ? fg : bg, state == 2 ? bg : state == 0 ? mid : fg,
                    state == 2 ? bg : state == 0 ? mid : fg, state == 2 ? bg : state == 0 ? mid : fg, 0);
        }
        shape.setLine(stroke, state == 0 ? mid : fg);
        // icons are tinted from theme attributes on every state change: enforce ours
        Object icon = Refl.get(t, "icon");
        if (icon != null) {
            Object iv = Refl.get(icon, "mIcon");
            int want = state == 2 ? bg : state == 0 ? mid : fg;
            if (iv instanceof ImageView) tint((ImageView) iv, want);
        }
    }

    private void restoreTile(View t) {
        Object[] st = tiles.remove(t);
        if (st == null) return;
        int[] orig = (int[]) st[1];
        for (int i = 0; i < orig.length; i++) Refl.set(t, TILE_FIELDS[i], orig[i]);
        Object[] bgs = (Object[]) st[2];
        for (int i = 0; i < bgs.length; i++) Refl.set(t, BG_FIELDS[i], bgs[i]);
        if (bgs[0] instanceof Drawable) t.setBackground((Drawable) bgs[0]);
        int s = Refl.getInt(t, "lastState", 1);
        Refl.call(t, "setAllColors", new Class<?>[] {int.class, int.class, int.class, int.class, int.class},
                s == 2 ? orig[0] : s == 0 ? orig[2] : orig[1], s == 2 ? orig[3] : s == 0 ? orig[5] : orig[4],
                s == 2 ? orig[6] : s == 0 ? orig[8] : orig[7], s == 2 ? orig[6] : s == 0 ? orig[8] : orig[7],
                s == 2 ? orig[9] : orig[10]);
    }

    private static void tint(ImageView iv, int color) {
        ColorStateList cur = iv.getImageTintList();
        if (cur == null || cur.getDefaultColor() != color) iv.setImageTintList(ColorStateList.valueOf(color));
    }

    // ---- notification cards ------------------------------------------------------------------------
    private final WeakHashMap<View, Object[]> notifGen = new WeakHashMap<>();

    /**
     * Card fill = page colour, outline on the (untinted) state-overlay layer, so SystemUI's per-row corner radii
     * and tint logic keep working. Only when our mode matches the system theme: the notification text colours come
     * from the system theme and must stay readable.
     */
    private void styleNotificationBg(View v) {
        boolean night = (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if ((mode == 2) != night) return;
        Object bgd = Refl.get(v, "mBackground");
        if (!(bgd instanceof LayerDrawable)) return;
        LayerDrawable ld = (LayerDrawable) bgd;
        Object[] st = notifGen.get(v);
        if (st == null || (int) st[0] != gen || st[1] != bgd) {
            notifGen.put(v, new Object[] {gen, bgd});
            Refl.set(v, "mNormalColor", bg);
            if (ld.getNumberOfLayers() > 1 && ld.getDrawable(1) instanceof GradientDrawable)
                ((GradientDrawable) ld.getDrawable(1).mutate()).setStroke(Math.round(stroke), fg);
            v.invalidate();
        }
        if (Refl.getInt(v, "mTintColor", bg) != bg) Refl.call(v, "setTint", new Class<?>[] {int.class}, bg);
    }

    private final WeakHashMap<View, Integer> notifChildGen = new WeakHashMap<>();
    private int idNIcon = -1, idNExpandPill, idNExpandIcon, idNExpandNumber, idNConvIcon;

    /** Inside notifications: small app icon, expand pill and footer buttons in the page/text colours. */
    private void styleNotificationChild(View v) {
        boolean night = (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if ((mode == 2) != night) return;
        if (idNIcon == -1) {
            idNIcon = res.getIdentifier("icon", "id", "android");
            idNConvIcon = res.getIdentifier("conversation_icon", "id", "android");
            idNExpandPill = res.getIdentifier("expand_button_pill", "id", "android");
            idNExpandIcon = res.getIdentifier("expand_button_icon", "id", "android");
            idNExpandNumber = res.getIdentifier("expand_button_number", "id", "android");
        }
        int id = v.getId();
        if (id == 0 || id == View.NO_ID) {
            if (v instanceof android.widget.Button) styleFooterButton((android.widget.Button) v);
            return;
        }
        if (id == idNIcon) {
            // framework NotificationRowIconView paints its own coloured circle: grey + contrast the whole view
            Integer g = notifChildGen.get(v);
            if (g == null || g != gen) {
                notifChildGen.put(v, gen);
                float k = 1.8f, o = 255f * (0.5f - 0.5f * k);
                float r = 0.299f * k, gg = 0.587f * k, b = 0.114f * k;
                v.setRenderEffect(RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(new ColorMatrix(
                        new float[] {r, gg, b, 0, o, r, gg, b, 0, o, r, gg, b, 0, o, 0, 0, 0, 1, 0}))));
            }
        } else if (id == idNExpandIcon && v instanceof ImageView) {
            tint((ImageView) v, fg);
            if (v.getBackground() != null && (v.getBackgroundTintList() == null || v.getBackgroundTintList().getDefaultColor() != bg))
                v.setBackgroundTintList(ColorStateList.valueOf(bg));
        } else if (id == idNExpandPill) {
            Integer g = notifChildGen.get(v);
            if (g == null || g != gen) {
                notifChildGen.put(v, gen);
                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(1000f);
                pill.setColor(bg);
                pill.setStroke(Math.max(1, Math.round(stroke * 0.7f)), fg);
                v.setBackground(pill);
            }
        } else if (id == idNExpandNumber && v instanceof TextView) {
            if (((TextView) v).getCurrentTextColor() != fg) ((TextView) v).setTextColor(fg);
        } else if (v instanceof android.widget.Button) {
            styleFooterButton((android.widget.Button) v);
        }
    }

    /** "Manage" / "Clear all": outlined pill, our own drawable (never mutate SystemUI's cached ones). */
    private void styleFooterButton(android.widget.Button b) {
        Integer g = notifChildGen.get(b);
        if (g != null && g == gen) return;
        notifChildGen.put(b, gen);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(1000f);
        pill.setColor(bg);
        pill.setStroke(Math.round(stroke), fg);
        b.setBackground(pill);
        b.setTextColor(fg);
    }

    // ---- scrims -----------------------------------------------------------------------------------
    private void styleScrim(View scrim, boolean force) {
        if (scrim == null) return;
        Object d = Refl.get(scrim, "mDrawable");
        if (!(d instanceof Drawable)) return;
        Drawable dr = (Drawable) d;
        float va = Refl.getFloat(scrim, "mViewAlpha", 1f);
        if (force && va > 0.01f) {
            boolean dirty = false;
            if (dr.getColorFilter() != scrimFilter) { dr.setColorFilter(scrimFilter); dirty = true; }  // no self-invalidate
            if (dr.getAlpha() != 255) { dr.setAlpha(255); dirty = true; }
            if (dirty) scrim.invalidate();
            scrimForced.put(scrim, true);
        } else if (scrimForced.containsKey(scrim)) {
            unforceScrim(scrim);
        }
    }

    private void unforceScrim(View scrim) {
        scrimForced.remove(scrim);
        Object d = Refl.get(scrim, "mDrawable");
        if (!(d instanceof Drawable)) return;
        ((Drawable) d).setColorFilter(null);
        ((Drawable) d).setAlpha(Math.round(Refl.getFloat(scrim, "mViewAlpha", 1f) * 255f));
        scrim.invalidate();
    }

    // ---- header / footer --------------------------------------------------------------------------
    private void styleHeaderView(View v) {
        if (mode == 0) return;
        if (v instanceof TextView) {
            if (((TextView) v).getCurrentTextColor() != fg) ((TextView) v).setTextColor(fg);
        } else if (v instanceof ImageView) {
            tint((ImageView) v, fg);
        } else if (v.getClass().getName().endsWith("BatteryMeterView")) {
            Integer g = headerGen.get(v);
            if (g == null || g != gen) {
                headerGen.put(v, gen);
                Refl.call(v, "updateColors", new Class<?>[] {int.class, int.class, int.class}, fg, bg, fg);
            }
        }
    }

    /** The QS footer actions are Compose: render them through a grey (+invert in light mode) colour matrix. */
    private void styleFooter(View v) {
        if (footer == v && footerGen == gen) return;
        footer = v;
        footerGen = gen;
        if (mode == 0) { v.setRenderEffect(null); return; }
        // SystemUI draws QS in its dark palette: light mode inverts luminance, both modes drop colour and add contrast.
        float k = 1.6f;
        float sign = mode == 1 ? -1f : 1f;
        float r = 0.299f * k * sign, g = 0.587f * k * sign, b = 0.114f * k * sign;
        float off = 255f * (mode == 1 ? (0.5f + 0.5f * k) : (0.5f - 0.5f * k));
        ColorMatrix m = new ColorMatrix(new float[] {
                r, g, b, 0, off,
                r, g, b, 0, off,
                r, g, b, 0, off,
                0, 0, 0, 1, 0});
        v.setRenderEffect(RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(m)));
    }
}
