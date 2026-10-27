package org.b1051c.einkui;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
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
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Walks the shade before each draw and keeps it in the e-ink style:
 *  - QS tiles: SystemUI's own colour fields are replaced (so its state logic keeps working) and the background shape
 *    becomes a solid fill + outline; inactive = page colour with outline, active = filled (inverted text/icon).
 *  - scrims behind QS/notifications: opaque page colour while the shade is open (no translucent grey over content).
 *  - shade header (clock, date, status icons) in the text colour; Compose footer gets a grey/invert colour filter.
 *  - brightness slider -> LightSliders (always, also with the theme "off").
 * Theme: Settings.Secure b1051c_shade_theme = 0 follow Dark theme (default), 1 light, 2 dark, -1 off (vanilla).
 * "Follow" reads SystemUI's Configuration night bit on every frame (not ui_night_mode, which changes before the new
 * configuration arrives), so a Dark theme flip restyles live.
 * Colours: one Palette.Tokens instance per mode (b1051c_shade_palette = 1 InKognito LIGHT/DARK, default; 0 plain
 * black and white), live overrides through b1051c_shade_tune_light / b1051c_shade_tune_dark (Palette.tuned). A new
 * token instance bumps gen, which restyles everything. Nothing here uses inline colours.
 * Outline width: b1051c_shade_stroke in tenths of dp (default 25 = 2.5 dp with InKognito, 15 plain).
 */
final class ShadeStyler implements ViewTreeObserver.OnPreDrawListener {
    /** Read by other plugin builds through reflection (see EinkOverlayPlugin.setup). */
    static final int BUILD = Build.CODE;
    static final String KEY_THEME = "b1051c_shade_theme";
    static final String KEY_STROKE = "b1051c_shade_stroke";
    /** Lock screen (ROADMAP wave 3 §7, owner 2026-10-21): SystemUI's ScrimController keeps a 20 % black scrim over the
     *  wallpaper on the keyguard, which turned the cream wallpaper light grey. 0 = Android's scrim, 1 = none (the
     *  wallpaper as it is; default), 2 = the page colour (cream / black) opaque. Never touches the bouncer. */
    static final String KEY_LOCK = "b1051c_lock_scrim";

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
    /** Tokens in effect; fg/bg/mid/inkPalette mirror tok (ink, page, mid, badge) for the existing call sites. */
    Palette.Tokens tok = Palette.PLAIN_LIGHT;
    int fg, bg, mid;
    float stroke;
    /** InKognito colours in effect (palette setting on and a style mode). */
    boolean inkPalette;
    private int pref, strokePref = -1, palettePref = 1, lockPref = 1, lockStyle = 1;
    private final LockStyler lock;
    private final android.app.KeyguardManager keyguardManager;
    private boolean keyguardLocked;
    private long keyguardAt;
    private final int idBouncer;
    private Palette.Tokens lightTok = Palette.LIGHT, darkTok = Palette.DARK;
    private String tuneLight, tuneDark;
    /** Bumped on every style change; LightSliders restyles when it moves. */
    int gen;
    private int errors;

    private final int idQsFrame, idBrightness, idFooterActions, idTileBase, idScrimBehind, idScrimNotif,
            idSplitHeader, idHeaderIcons, idClock, idDate, idCarrier, idQsFooter, idQsPanel, idQqsPanel, idDismissText;
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
        idDismissText = id("dismiss_text");                 // FooterView's "Clear all" (manage_text = "Manage")
        // this SystemUI's bouncer has no container id of its own; its message area is on screen whenever it is
        int b = id("bouncer_primary_message_area");
        idBouncer = b != 0 ? b : id("bouncer_message_area");
        keyguardManager = sysui.getSystemService(android.app.KeyguardManager.class);
        lock = root instanceof ViewGroup ? new LockStyler(sysui, plugin, (ViewGroup) root) : null;
        obs = new ContentObserver(h) {
            @Override public void onChange(boolean selfChange) { readPrefs(); root.invalidate(); }
        };
        readPrefs();
    }

    private int id(String name) { return res.getIdentifier(name, "id", "com.android.systemui"); }

    void start() {
        cr.registerContentObserver(Settings.Secure.getUriFor(KEY_THEME), false, obs);
        cr.registerContentObserver(Settings.Secure.getUriFor(KEY_STROKE), false, obs);
        cr.registerContentObserver(Settings.Secure.getUriFor(Palette.KEY), false, obs);
        cr.registerContentObserver(Settings.Secure.getUriFor(Palette.KEY_TUNE_LIGHT), false, obs);
        cr.registerContentObserver(Settings.Secure.getUriFor(Palette.KEY_TUNE_DARK), false, obs);
        cr.registerContentObserver(Settings.Secure.getUriFor(KEY_LOCK), false, obs);
        cr.registerContentObserver(Settings.Secure.getUriFor(LockStyler.KEY), false, obs);
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
        if (lock != null) lock.destroy();
    }

    /** Runs on SystemUI's main thread (observer callback): any exception here would take SystemUI down, so it falls
     *  back to the built-in tokens instead. An unchanged tune spec keeps its token instance (no restyle). */
    private void readPrefs() {
        try {
            pref = Settings.Secure.getInt(cr, KEY_THEME, 0);
            strokePref = Settings.Secure.getInt(cr, KEY_STROKE, -1);
            lockPref = Settings.Secure.getInt(cr, KEY_LOCK, 1);
            lockStyle = Settings.Secure.getInt(cr, LockStyler.KEY, 1);
            palettePref = Settings.Secure.getInt(cr, Palette.KEY, 1);
            String tl = Settings.Secure.getString(cr, Palette.KEY_TUNE_LIGHT);
            String td = Settings.Secure.getString(cr, Palette.KEY_TUNE_DARK);
            if (palettePref == 0) {
                lightTok = Palette.PLAIN_LIGHT;
                darkTok = Palette.PLAIN_DARK;
            } else {
                if (lightTok == Palette.PLAIN_LIGHT || !Objects.equals(tl, tuneLight)) lightTok = Palette.tuned(Palette.LIGHT, tl);
                if (darkTok == Palette.PLAIN_DARK || !Objects.equals(td, tuneDark)) darkTok = Palette.tuned(Palette.DARK, td);
            }
            tuneLight = tl;
            tuneDark = td;
        } catch (Throwable t) {
            Log.w(EinkOverlayPlugin.TAG, "settings read failed, built-in colors", t);
            lightTok = palettePref == 0 ? Palette.PLAIN_LIGHT : Palette.LIGHT;
            darkTok = palettePref == 0 ? Palette.PLAIN_DARK : Palette.DARK;
            // forget the cached specs: the tokens are untuned now, so the next good read re-applies an unchanged spec
            tuneLight = null;
            tuneDark = null;
        }
    }

    private boolean isNight() {
        return (res.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void resolveMode() {
        int m = pref == -1 ? 0 : pref == 1 ? 1 : pref == 2 ? 2 : (isNight() ? 2 : 1);
        // vanilla: nothing is styled; the plain tokens keep fg/bg/stroke as before (the slider drawable reads them)
        Palette.Tokens t = m == 0 ? Palette.PLAIN_LIGHT : m == 2 ? darkTok : lightTok;
        float s = (strokePref > 0 ? strokePref : t.strokeTenths) / 10f * res.getDisplayMetrics().density;
        if (m == mode && s == stroke && t == tok) return;     // a new token instance (tune, palette) restyles too
        mode = m;
        stroke = s;
        tok = t;
        fg = t.ink;
        bg = t.page;
        mid = t.mid;
        inkPalette = t.badge && m != 0;
        scrimFilter = new PorterDuffColorFilter(t.page, PorterDuff.Mode.SRC_IN);
        gen++;
        Log.i(EinkOverlayPlugin.TAG, "style mode=" + m + " tokens=" + t.name + " stroke=" + s + "px");
    }

    /** Accent pair of an active tile (board 05): 1 = E-Ink Control's "Refresh mode", 2 = "Color", 0 = every other
     *  tile; the plain palette has one pair (inverted tile). The labels are hard-coded in einkctl
     *  (TileRefresh.java:27, TileColour.java:24): renaming them there silently drops the accent. */
    private int accentIndex(View t) {
        if (!tok.badge) return 0;
        Object l = Refl.get(t, "label");
        CharSequence s = l instanceof TextView ? ((TextView) l).getText() : null;
        if (s != null && "Refresh mode".contentEquals(s)) return 1;
        if (s != null && "Color".contentEquals(s)) return 2;
        return 0;
    }

    @Override
    public boolean onPreDraw() {
        try {
            resolveMode();
            // "open" = QS or quick QS actually on screen (also over the lock screen); the bare lock screen keeps
            // its own scrim so the wallpaper/clock look is untouched
            boolean shadeOpen = onScreen(root.findViewById(idQsPanel)) || onScreen(root.findViewById(idQqsPanel));
            int behind = shadeOpen && mode != 0 ? SCRIM_PAGE : SCRIM_NONE;
            // the bare lock screen: no 20 % grey over the wallpaper (or the page colour), never on the bouncer
            boolean bareLock = !shadeOpen && mode != 0 && keyguardLocked() && !onScreen(root.findViewById(idBouncer));
            if (bareLock && lockPref != 0) behind = lockPref == 2 ? SCRIM_PAGE : SCRIM_CLEAR;
            styleScrim(root.findViewById(idScrimBehind), behind);
            styleScrim(root.findViewById(idScrimNotif), shadeOpen && mode != 0 ? SCRIM_PAGE : SCRIM_NONE);
            if (lock != null) lock.frame(bareLock && lockStyle != 0, tok);
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
        if (v.getClass().getName().endsWith(".QSTileViewImpl")) noMarquee(v);
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

    /** Long tile labels scroll (marquee) forever; on e-ink that is a panel update every frame while the shade is
     *  open (seen 2026-10-19: "Refresh mode", "Colour inversion", "Aeroplane mode"). Truncate with an ellipsis. */
    private static void noMarquee(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (tv.getEllipsize() == android.text.TextUtils.TruncateAt.MARQUEE) tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) noMarquee(g.getChildAt(i));
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
            st = new Object[] {-1, orig, bgs, null, 0};
            tiles.put(t, st);
        }
        int state = Refl.getInt(t, "lastState", 1);
        int ai = accentIndex(t), fill = tok.accentFill[ai], on = tok.accentText[ai];
        TileShape shape = st[3] instanceof android.graphics.drawable.RippleDrawable
                && t.getBackground() == st[3] ? (TileShape) Refl.get(t, "backgroundBaseDrawable") : null;
        if ((int) st[0] != gen || shape == null || (int) st[4] != ai) {    // token changes move gen
            st[0] = gen;
            st[4] = ai;
            int[] c = {fill, bg, bg, on, fg, mid, on, fg, mid, 0, 0};
            for (int i = 0; i < c.length; i++) Refl.set(t, TILE_FIELDS[i], c[i]);
            int rid = res.getIdentifier("qs_corner_radius", "dimen", "com.android.systemui");
            float radius = rid != 0 ? res.getDimension(rid) : 28 * res.getDisplayMetrics().density;
            if (tok.badge) radius = Math.min(radius, 16 * res.getDisplayMetrics().density);   // board 05: badge tiles, not pills
            if (shape != null) shape.setRadius(radius);
            if (shape == null) {
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
                    state == 2 ? fill : bg, state == 2 ? on : state == 0 ? mid : fg,
                    state == 2 ? on : state == 0 ? mid : fg, state == 2 ? on : state == 0 ? mid : fg, 0);
        }
        shape.setLine(stroke, state == 0 ? mid : fg);
        // icons are tinted from theme attributes on every state change: enforce ours
        Object icon = Refl.get(t, "icon");
        if (icon != null) {
            Object iv = Refl.get(icon, "mIcon");
            int want = state == 2 ? on : state == 0 ? mid : fg;
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
     * Card fill (tok.card), outline (tok.cardLine) on the (untinted) state-overlay layer, so SystemUI's per-row
     * corner radii and tint logic keep working. Only when our mode matches the system theme: the notification text
     * colours come from the system theme and must stay readable (always true when following Dark theme).
     */
    private void styleNotificationBg(View v) {
        if ((mode == 2) != isNight()) return;
        Object bgd = Refl.get(v, "mBackground");
        if (!(bgd instanceof LayerDrawable)) return;
        LayerDrawable ld = (LayerDrawable) bgd;
        int card = tok.card;
        Object[] st = notifGen.get(v);
        if (st == null || (int) st[0] != gen || st[1] != bgd) {
            notifGen.put(v, new Object[] {gen, bgd});
            Refl.set(v, "mNormalColor", card);
            if (ld.getNumberOfLayers() > 1 && ld.getDrawable(1) instanceof GradientDrawable)
                ((GradientDrawable) ld.getDrawable(1).mutate()).setStroke(Math.round(stroke), tok.cardLine);
            v.invalidate();
        }
        if (Refl.getInt(v, "mTintColor", card) != card) Refl.call(v, "setTint", new Class<?>[] {int.class}, card);
    }

    private final WeakHashMap<View, Integer> notifChildGen = new WeakHashMap<>();
    private int idNIcon = -1, idNExpandPill, idNExpandIcon, idNExpandNumber, idNConvIcon;

    /** Inside notifications: small app icon, expand pill and footer buttons in the page/text colours. */
    private void styleNotificationChild(View v) {
        if ((mode == 2) != isNight()) return;
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
            tint((ImageView) v, tok.ink);
            int ex = tok.expand;
            if (v.getBackground() != null && (v.getBackgroundTintList() == null || v.getBackgroundTintList().getDefaultColor() != ex))
                v.setBackgroundTintList(ColorStateList.valueOf(ex));
        } else if (id == idNExpandPill) {
            Integer g = notifChildGen.get(v);
            if (g == null || g != gen) {
                notifChildGen.put(v, gen);
                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(1000f);
                pill.setColor(tok.expand);
                pill.setStroke(Math.max(1, Math.round(stroke * 0.7f)), tok.ink);
                v.setBackground(pill);
            }
        } else if (id == idNExpandNumber && v instanceof TextView) {
            if (((TextView) v).getCurrentTextColor() != tok.ink) ((TextView) v).setTextColor(tok.ink);
        } else if (v instanceof android.widget.Button) {
            styleFooterButton((android.widget.Button) v);
        }
    }

    private final WeakHashMap<View, Drawable> footerPills = new WeakHashMap<>();

    /** "Manage" / "Clear all": outlined pill (tok.pill* / tok.clear*), our own drawable (never mutate SystemUI's
     *  cached ones; no tint list, which would paint the stroke in the fill colour). SystemUI re-applies its own
     *  background and text colour when the theme changes (seen: a gray "Manage" in the dark shade), so this re-checks
     *  every frame and restyles whenever the button no longer carries our pill and text colour. */
    private void styleFooterButton(android.widget.Button b) {
        boolean clr = idDismissText != 0 && b.getId() == idDismissText;
        int fill = clr ? tok.clear : tok.pill, line = clr ? tok.clearLine : tok.pillLine, text = clr ? tok.clearText : tok.pillText;
        Integer g = notifChildGen.get(b);
        Drawable mine = footerPills.get(b);
        if (g != null && g == gen && mine != null && b.getBackground() == mine && b.getCurrentTextColor() == text) return;
        notifChildGen.put(b, gen);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(1000f);
        pill.setColor(fill);
        pill.setStroke(Math.round(stroke), line);
        b.setBackgroundTintList(null);
        b.setBackground(pill);
        b.setTextColor(text);
        footerPills.put(b, pill);
    }

    // ---- scrims -----------------------------------------------------------------------------------
    static final int SCRIM_NONE = 0, SCRIM_PAGE = 1, SCRIM_CLEAR = 2;

    /** KeyguardManager over binder, at most 4 times a second (onPreDraw runs on every shade frame). */
    private boolean keyguardLocked() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - keyguardAt > 250) {
            keyguardAt = now;
            try { keyguardLocked = keyguardManager != null && keyguardManager.isKeyguardLocked(); }
            catch (RuntimeException e) { keyguardLocked = false; }
        }
        return keyguardLocked;
    }

    /** want: SCRIM_PAGE = opaque page colour (the shade is open), SCRIM_CLEAR = fully transparent (the bare lock
     *  screen), SCRIM_NONE = SystemUI's own. Only the keyguard's light scrim (view alpha ~0.2) is cleared: a darker
     *  one is the bouncer or a transition and stays. Invalidates only on a change, or every frame would redraw. */
    private void styleScrim(View scrim, int want) {
        if (scrim == null) return;
        Object d = Refl.get(scrim, "mDrawable");
        if (!(d instanceof Drawable)) return;
        Drawable dr = (Drawable) d;
        float va = Refl.getFloat(scrim, "mViewAlpha", 1f);
        if (want == SCRIM_PAGE && va <= 0.01f) want = SCRIM_NONE;
        if (want == SCRIM_CLEAR && (va <= 0.01f || va > 0.35f)) want = SCRIM_NONE;
        if (want == SCRIM_NONE) {
            if (scrimForced.containsKey(scrim)) unforceScrim(scrim);
            return;
        }
        PorterDuffColorFilter cf = want == SCRIM_PAGE ? scrimFilter : null;
        int alpha = want == SCRIM_PAGE ? 255 : 0;
        boolean dirty = false;
        if (dr.getColorFilter() != cf) { dr.setColorFilter(cf); dirty = true; }  // no self-invalidate
        if (dr.getAlpha() != alpha) { dr.setAlpha(alpha); dirty = true; }
        if (dirty) scrim.invalidate();
        scrimForced.put(scrim, true);
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
    /** Header (status icons, battery, carrier, page dots, edit) in tok.header; the clock, the one large type there,
     *  in tok.display; the date in tok.date (dark: cream like the clock, card V2b; light and plain: as tok.header). */
    private void styleHeaderView(View v) {
        if (mode == 0) return;
        int vid = v.getId();
        int c = vid != 0 && vid == idClock ? tok.display : vid != 0 && vid == idDate ? tok.date : tok.header;
        if (v instanceof TextView) {
            if (((TextView) v).getCurrentTextColor() != c) ((TextView) v).setTextColor(c);
        } else if (v instanceof ImageView) {
            tint((ImageView) v, c);
        } else if (v.getClass().getName().endsWith("BatteryMeterView")) {
            Integer g = headerGen.get(v);
            if (g == null || g != gen) {
                headerGen.put(v, gen);
                Refl.call(v, "updateColors", new Class<?>[] {int.class, int.class, int.class}, tok.header, tok.page, tok.header);
            }
        }
    }

    /** The QS footer actions are Compose: render them through a grey (+invert in light mode) colour matrix. */
    private void styleFooter(View v) {
        if (footer == v && footerGen == gen) return;
        footer = v;
        footerGen = gen;
        if (mode == 0) { v.setRenderEffect(null); return; }
        // SystemUI draws QS in its dark palette: light paper inverts luminance, both drop colour and add contrast.
        float k = 1.6f;
        boolean inv = tok.footerInvert;
        float sign = inv ? -1f : 1f;
        float r = 0.299f * k * sign, g = 0.587f * k * sign, b = 0.114f * k * sign;
        float off = 255f * (inv ? (0.5f + 0.5f * k) : (0.5f - 0.5f * k));
        ColorMatrix m = new ColorMatrix(new float[] {
                r, g, b, 0, off,
                r, g, b, 0, off,
                r, g, b, 0, off,
                0, 0, 0, 1, 0});
        RenderEffect grey = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(m));
        // Clamp to [floor, cap] = [ink, paper] on light paper, [paper, ink] on dark. The effect chain works in float
        // and does not clamp between passes, so the contrast overshoot (> white, < black) survives later passes; only
        // the final output clamps to black/white, so exact black/white bounds need no pass (light: cream cap only,
        // dark #000000/#FFFFFF: none, as in 0.3.5). DARKEN with the cap = per-channel min(cap, pixel), LIGHTEN with the
        // floor = max. Both also turn transparent pixels opaque, and such an effect has unbounded output: it painted
        // cream over the whole QS panel (0.3.3). DST_IN with the untouched content keeps only what the footer itself
        // drew, with its own coverage.
        int cap = inv ? tok.page : tok.ink, floor = inv ? tok.ink : tok.page;
        RenderEffect e = grey;
        if (cap != Palette.WHITE) e = RenderEffect.createColorFilterEffect(new BlendModeColorFilter(cap, BlendMode.DARKEN), e);
        if (floor != Palette.BLACK) e = RenderEffect.createColorFilterEffect(new BlendModeColorFilter(floor, BlendMode.LIGHTEN), e);
        if (e != grey) e = RenderEffect.createBlendModeEffect(e, RenderEffect.createOffsetEffect(0, 0), BlendMode.DST_IN);
        v.setRenderEffect(e);
    }
}
