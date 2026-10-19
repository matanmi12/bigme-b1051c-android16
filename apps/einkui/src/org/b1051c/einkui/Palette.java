package org.b1051c.einkui;

/**
 * InKognito palette (design board 05, sampled from the boot logo). Light shade = cream paper, ink text and outlines,
 * full-strength fills for active tiles (owner 2026-10-19: cream text on bordeaux/teal/steel, ink on gold/light gold).
 * Dark shade = black paper, white ink, light gold for "on" (the dark fills would vanish into black on Kaleido).
 * Ink is pure black here: the logo's #1A1414 looks the same on the panel and black keeps thin strokes undithered.
 * Settings.Secure b1051c_shade_palette: 1 = InKognito (default), 0 = plain black and white (the 0.2.x look).
 */
final class Palette {
    static final String KEY = "b1051c_shade_palette";

    static final int INK = 0xFF000000;
    static final int CREAM = 0xFFF7F4E7;
    static final int BORDEAUX = 0xFF932845;
    static final int TEAL = 0xFF194D50;
    static final int GOLD = 0xFFCD8E2C;
    static final int LIGHT_GOLD = 0xFFF7BF5D;
    static final int STEEL = 0xFF5D7E91;
    static final int SILVER = 0xFFA7B0B6;

    private Palette() {}
}
