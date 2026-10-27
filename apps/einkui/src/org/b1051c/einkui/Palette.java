package org.b1051c.einkui;

import android.util.Log;
import java.util.Locale;

/**
 * InKognito palette (design board 05, sampled from the boot logo) and the shade's colour tokens: the single place
 * to retune. Every styler reads only a {@link Tokens} instance, never inline colours.
 * Light shade (LIGHT) = cream paper, ink text and outlines, full-strength fills for active tiles (owner 2026-10-19:
 * cream text on bordeaux/teal/steel, ink on gold/light gold).
 * Dark shade (DARK, "Night Badge", owner verdict G1 2026-10-20, judged on the panel under HD 256) = exact #000000
 * paper, white ink, every gray exactly R=G=B. Color goes only into fills and rings, which keep the white outline so
 * they never sink into the black: gold leads (default "on", warm fill, the "Clear all" ring), bordeaux second
 * (Refresh mode), steel secondary (cold fill); cream for the header clock and date (card V2b).
 * Ink is pure black here: the logo's #1A1414 looks the same on the panel and black keeps thin strokes undithered.
 * Settings.Secure b1051c_shade_palette: 1 = InKognito (default), 0 = plain black and white (the 0.2.x look,
 * PLAIN_LIGHT / PLAIN_DARK). Live overrides without a rebuild: b1051c_shade_tune_dark / b1051c_shade_tune_light,
 * see {@link #tuned}; ignored with the plain palette.
 */
final class Palette {
    static final String KEY = "b1051c_shade_palette";
    static final String KEY_TUNE_LIGHT = "b1051c_shade_tune_light";
    static final String KEY_TUNE_DARK = "b1051c_shade_tune_dark";

    static final int INK = 0xFF000000;
    static final int CREAM = 0xFFF7F4E7;
    static final int BORDEAUX = 0xFF932845;
    static final int TEAL = 0xFF194D50;
    static final int GOLD = 0xFFCD8E2C;
    static final int LIGHT_GOLD = 0xFFF7BF5D;
    static final int STEEL = 0xFF5D7E91;
    static final int SILVER = 0xFFA7B0B6;

    static final int WHITE = 0xFFFFFFFF;
    static final int BLACK = 0xFF000000;
    /** Lifted fills for the black page (the logo's dark tones sink on Kaleido). TEAL_LIFT is a card candidate. */
    static final int BORDEAUX_LIFT = 0xFFB03A57;
    static final int TEAL_LIFT = 0xFF2B6E70;
    static final int STEEL_LIFT = 0xFF8FAABA;
    /** Unavailable tiles (label, icon, outline). */
    static final int GRAY_LIGHT = 0xFF7A7A7A;
    static final int GRAY_DARK = 0xFF8C8C8C;

    /** Longest tune spec accepted; anything longer is ignored as a whole. */
    private static final int MAX_SPEC = 2000;

    /**
     * One complete colour scheme of the shade. Mutable only inside Palette (built-ins and {@link #tuned} copies);
     * treated as immutable afterwards, so ShadeStyler can compare instances by identity.
     * Accent pairs: index 0 = default "on" tile, 1 = E-Ink Control's "Refresh mode", 2 = "Color".
     */
    static final class Tokens {
        String name;
        /** Badge look: 16 dp tiles, accents, SliderTrack. False = plain black and white. */
        boolean badge;
        /** The Compose footer is drawn in SystemUI's dark palette: invert it on light paper. */
        boolean footerInvert;
        /** Default outline width in tenths of dp (b1051c_shade_stroke overrides). */
        int strokeTenths;
        int page, ink, mid, header, display, date;
        final int[] accentFill = new int[3], accentText = new int[3];
        int card, cardLine, pill, pillLine, pillText, clear, clearLine, clearText, expand;
        int track, line, cold, warm, thumb, thumbRing, thumbDot, iconLine, iconCold, iconWarm;

        private Tokens(String name) { this.name = name; }

        Tokens copy(String newName) {
            Tokens t = new Tokens(newName);
            t.badge = badge; t.footerInvert = footerInvert; t.strokeTenths = strokeTenths;
            t.page = page; t.ink = ink; t.mid = mid; t.header = header; t.display = display; t.date = date;
            System.arraycopy(accentFill, 0, t.accentFill, 0, 3);
            System.arraycopy(accentText, 0, t.accentText, 0, 3);
            t.card = card; t.cardLine = cardLine; t.pill = pill; t.pillLine = pillLine; t.pillText = pillText;
            t.clear = clear; t.clearLine = clearLine; t.clearText = clearText; t.expand = expand;
            t.track = track; t.line = line; t.cold = cold; t.warm = warm;
            t.thumb = thumb; t.thumbRing = thumbRing; t.thumbDot = thumbDot;
            t.iconLine = iconLine; t.iconCold = iconCold; t.iconWarm = iconWarm;
            return t;
        }

        /** Sets one token by its override name (see {@link #tuned}); false for an unknown name. */
        boolean set(String key, int c) {
            switch (key) {
                case "page": page = c; break;                  // paper: tiles, scrims, footer clamp
                case "ink": ink = c; break;                    // text, outlines, icons on the paper
                case "mid": mid = c; break;                    // unavailable tiles
                case "header": header = c; break;              // status icons, battery, carrier, page dots
                case "display": display = c; break;            // header clock (large type)
                case "date": date = c; break;                  // header date (0.4.2; was part of "header")
                case "on": accentFill[0] = c; break;
                case "on.text": accentText[0] = c; break;
                case "refresh": accentFill[1] = c; break;
                case "refresh.text": accentText[1] = c; break;
                case "color": accentFill[2] = c; break;
                case "color.text": accentText[2] = c; break;
                case "card": card = c; break;                  // notification card fill
                case "card.line": cardLine = c; break;
                case "pill": pill = c; break;                  // "Manage"
                case "pill.line": pillLine = c; break;
                case "pill.text": pillText = c; break;
                case "clear": clear = c; break;                // "Clear all"
                case "clear.line": clearLine = c; break;
                case "clear.text": clearText = c; break;
                case "expand": expand = c; break;              // notification expand pill fill
                case "track": track = c; break;                // light sliders
                case "line": line = c; break;
                case "cold": cold = c; break;
                case "warm": warm = c; break;
                case "thumb": thumb = c; break;
                case "thumb.ring": thumbRing = c; break;
                case "thumb.dot": thumbDot = c; break;
                case "icon.line": iconLine = c; break;
                case "icon.cold": iconCold = c; break;
                case "icon.warm": iconWarm = c; break;
                default: return false;
            }
            return true;
        }
    }

    /** Fills that have a ".text" partner: a fill set without it gets {@link #textOn}. */
    private static final String[] FILLS_WITH_TEXT = {"on", "refresh", "color", "pill", "clear"};

    static final Tokens LIGHT = light();
    static final Tokens DARK = dark();
    static final Tokens PLAIN_LIGHT = plain("plain-light", false);
    static final Tokens PLAIN_DARK = plain("plain-dark", true);

    /** Pixel-identical to 0.3.5. */
    private static Tokens light() {
        Tokens t = new Tokens("light");
        t.badge = true;
        t.footerInvert = true;
        t.strokeTenths = 25;
        t.page = CREAM; t.ink = INK; t.mid = GRAY_LIGHT; t.header = INK; t.display = INK; t.date = INK;
        accent(t, 0, TEAL, CREAM);
        accent(t, 1, BORDEAUX, CREAM);
        accent(t, 2, GOLD, INK);
        t.card = CREAM; t.cardLine = INK;
        t.pill = CREAM; t.pillLine = INK; t.pillText = INK;
        t.clear = CREAM; t.clearLine = INK; t.clearText = INK;
        t.expand = CREAM;
        t.track = SILVER; t.line = INK; t.cold = STEEL; t.warm = GOLD;
        t.thumb = CREAM; t.thumbRing = INK; t.thumbDot = INK;
        t.iconLine = INK; t.iconCold = SILVER; t.iconWarm = GOLD;
        return t;
    }

    /** Owner verdict G1 (2026-10-20, panel card work/design/palette_card.py --dark), baked in 0.4.1: on F1 F7BF5D +
     *  ink, Refresh mode F4 B03A57 + cream, Color V5b CD8E2C + ink, unavailable 8C8C8C, clock V2b cream, sliders
     *  E3b 8FAABA / F7BF5D, thumb V4a (cream, white ring, black dot), icons V3a (white lines, silver core, gold
     *  flame), Clear all V1d (black, light-gold ring, white text), Manage black + white ring + white text. Changes
     *  from the 0.4.0 draft: the Clear all ring (0.4.1, was white) and the header date (0.4.2, was white): card V2b
     *  draws "10:24" and "Oct 20" both in cream. Live check without a rebuild: tune key date=FFFFFF. */
    private static Tokens dark() {
        Tokens t = new Tokens("dark");
        t.badge = true;
        t.footerInvert = false;
        t.strokeTenths = 25;
        t.page = BLACK; t.ink = WHITE; t.mid = GRAY_DARK; t.header = WHITE; t.display = CREAM; t.date = CREAM;
        accent(t, 0, LIGHT_GOLD, INK);
        accent(t, 1, BORDEAUX_LIFT, CREAM);
        accent(t, 2, GOLD, INK);
        t.card = BLACK; t.cardLine = WHITE;
        t.pill = BLACK; t.pillLine = WHITE; t.pillText = WHITE;
        t.clear = BLACK; t.clearLine = LIGHT_GOLD; t.clearText = WHITE;
        t.expand = BLACK;
        t.track = BLACK; t.line = WHITE; t.cold = STEEL_LIFT; t.warm = LIGHT_GOLD;
        t.thumb = CREAM; t.thumbRing = WHITE; t.thumbDot = INK;
        t.iconLine = WHITE; t.iconCold = SILVER; t.iconWarm = GOLD;
        return t;
    }

    /** b1051c_shade_palette=0: white (or black) paper, inverted active tiles, 1.5 dp lines. The sliders use
     *  SystemUI's own drawable then, so the slider tokens only mirror ink and paper. */
    private static Tokens plain(String name, boolean dark) {
        Tokens t = new Tokens(name);
        int page = dark ? BLACK : WHITE, ink = dark ? WHITE : INK;
        t.badge = false;
        t.footerInvert = !dark;
        t.strokeTenths = 15;
        t.page = page; t.ink = ink; t.mid = dark ? GRAY_DARK : GRAY_LIGHT; t.header = ink; t.display = ink; t.date = ink;
        for (int i = 0; i < 3; i++) accent(t, i, ink, page);
        t.card = page; t.cardLine = ink;
        t.pill = page; t.pillLine = ink; t.pillText = ink;
        t.clear = page; t.clearLine = ink; t.clearText = ink;
        t.expand = page;
        t.track = page; t.line = ink; t.cold = ink; t.warm = ink;
        t.thumb = page; t.thumbRing = ink; t.thumbDot = ink;
        t.iconLine = ink; t.iconCold = ink; t.iconWarm = ink;
        return t;
    }

    private static void accent(Tokens t, int i, int fill, int text) {
        t.accentFill[i] = fill;
        t.accentText[i] = text;
    }

    /**
     * Base tokens with a live override spec applied: {@code name=RRGGBB[,name=RRGGBB...]}, names as in
     * {@link Tokens#set} (case-insensitive), '#' optional, 6 or 8 hex digits with the alpha forced to FF.
     * Bad items are skipped with one warning; a spec over 2000 characters is ignored. A fill changed without its
     * ".text" gets {@link #textOn}. Returns {@code base} itself when nothing applies, so the identity check in
     * ShadeStyler does not restyle. Never throws: this runs on SystemUI's main thread.
     */
    static Tokens tuned(Tokens base, String spec) {
        if (spec == null || spec.trim().isEmpty()) return base;
        try {
            if (spec.length() > MAX_SPEC) {
                Log.w(EinkOverlayPlugin.TAG, "tune " + base.name + ": spec longer than " + MAX_SPEC + " chars, ignored");
                return base;
            }
            Tokens t = base.copy(base.name + "+tune");
            StringBuilder bad = new StringBuilder();
            boolean[] textSet = new boolean[FILLS_WITH_TEXT.length];
            int applied = 0;
            for (String item : spec.split(",")) {
                String it = item.trim();
                if (it.isEmpty()) continue;
                int eq = it.indexOf('=');
                String key = eq > 0 ? it.substring(0, eq).trim().toLowerCase(Locale.ROOT) : "";
                int c = eq > 0 ? parseColor(it.substring(eq + 1).trim()) : 0;
                if (key.isEmpty() || c == 0 || !t.set(key, c)) {
                    if (bad.length() < 200) bad.append(bad.length() > 0 ? " " : "").append(it);
                    continue;
                }
                applied++;
                for (int i = 0; i < FILLS_WITH_TEXT.length; i++)
                    if (key.equals(FILLS_WITH_TEXT[i] + ".text")) textSet[i] = true;
            }
            if (bad.length() > 0) Log.w(EinkOverlayPlugin.TAG, "tune " + base.name + ": skipped " + bad);
            if (applied == 0) return base;
            pairText(t, base, 0, textSet[0]);
            pairText(t, base, 1, textSet[1]);
            pairText(t, base, 2, textSet[2]);
            if (!textSet[3] && t.pill != base.pill) t.pillText = textOn(t.pill);
            if (!textSet[4] && t.clear != base.clear) t.clearText = textOn(t.clear);
            return t;
        } catch (Throwable e) {
            Log.w(EinkOverlayPlugin.TAG, "tune " + base.name + ": spec ignored", e);
            return base;
        }
    }

    private static void pairText(Tokens t, Tokens base, int i, boolean textSet) {
        if (!textSet && t.accentFill[i] != base.accentFill[i]) t.accentText[i] = textOn(t.accentFill[i]);
    }

    /** "RRGGBB", "#RRGGBB" or 8 digits (alpha dropped) -> opaque colour; 0 = invalid (0 is never opaque). */
    private static int parseColor(String v) {
        if (v.startsWith("#")) v = v.substring(1);
        if (v.length() != 6 && v.length() != 8) return 0;
        int rgb = 0;
        for (int i = 0; i < v.length(); i++) {
            int d = Character.digit(v.charAt(i), 16);
            if (d < 0) return 0;
            rgb = rgb << 4 | d;                        // 8 digits: the alpha shifts out of the low 24 bits
        }
        return 0xFF000000 | (rgb & 0xFFFFFF);
    }

    /** Label colour on a fill: ink when the fill's WCAG relative luminance is above 0.30, else cream. Reproduces
     *  every owner pairing (ink on the golds, silver and lifted steel; cream on steel, bordeaux and teal). */
    static int textOn(int fill) {
        double l = 0.2126 * lin(fill >> 16 & 0xFF) + 0.7152 * lin(fill >> 8 & 0xFF) + 0.0722 * lin(fill & 0xFF);
        return l > 0.30 ? INK : CREAM;
    }

    private static double lin(int v) {
        double c = v / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private Palette() {}
}
