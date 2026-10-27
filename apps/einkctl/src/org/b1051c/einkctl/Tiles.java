package org.b1051c.einkctl;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;
import java.util.function.IntConsumer;
import org.json.JSONObject;

/** Quick Settings tiles. Each is its own small TileService (Android binds one service per tile). */
final class Tiles {
    private Tiles() {}

    static void show(TileService s, String label, String sub, boolean active) {
        AppProfileService.recover(s, "tile");    // the shade is open: a chance to recover from Active apps -> Stop
        Tile t = s.getQsTile();
        if (t == null) return;
        t.setLabel(label);
        t.setSubtitle(sub);
        t.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setStateDescription(sub);                     // TalkBack reads the mode / preset / level (review 2026-10-21)
        t.updateTile();
    }

    /**
     * Pick one of several modes from a small list over the shade (owner 2026-10-18: cycling on tap is unreadable on
     * e-ink, you can't see what you are switching to). The current item is checked; a tap applies it and closes.
     * TileDialog follows the Dark theme (values-night); the custom title takes its color from the dialog's context.
     */
    static void choose(TileService s, String title, String note, String[] items, int checked, IntConsumer pick) {
        AlertDialog.Builder b = new AlertDialog.Builder(s, R.style.TileDialog)
                .setSingleChoiceItems(items, checked, (d, which) -> { d.dismiss(); pick.accept(which); })
                .setNegativeButton("Cancel", null);
        if (note == null) {
            b.setTitle(title);
        } else {
            SpannableStringBuilder t = new SpannableStringBuilder(title);
            t.setSpan(new StyleSpan(Typeface.BOLD), 0, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int at = t.length();
            t.append("\n").append(note);
            t.setSpan(new RelativeSizeSpan(0.75f), at, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            Context dc = b.getContext();               // themed with TileDialog
            TextView tv = new TextView(dc);
            int pad = Math.round(24 * s.getResources().getDisplayMetrics().density);
            tv.setPadding(pad, pad, pad, pad / 3);
            tv.setTextSize(20);
            tv.setTextColor(Ink.pal(dc).ink);
            tv.setTypeface(Ink.font(dc, 400));
            tv.setText(t);
            b.setCustomTitle(tv);
        }
        s.showDialog(b.create());
    }

    /** The app behind the shade (null if unknown) as the user knows it: its label, or "The home screen" for launchers
     *  that have no profile of their own (the home-screen profile applies to them). */
    static String foregroundName(TileService s) {
        String pkg = AppProfileService.appPackage();
        if (pkg == null || pkg.isEmpty()) return null;
        if (Profiles.RECENTS.equals(pkg)) return "Recents";
        if (Profiles.get(s, pkg).length() == 0 && Profiles.homePackages(s).contains(pkg)) return "The home screen";
        return label(s, pkg);
    }

    /** The preset name the app behind the shade gets from its own (or the home-screen) profile, or null. */
    static String foregroundPreset(TileService s) {
        Object v = foregroundField(s, Profiles.F_PRESET);
        return v == null ? null : v.toString();
    }

    /** The value of a profile field the app behind the shade gets from its own (or the home-screen) profile, or null. */
    static Object foregroundField(TileService s, String f) {
        String pkg = AppProfileService.appPackage();
        if (pkg == null || pkg.isEmpty()) return null;
        JSONObject o = Profiles.get(s, pkg);
        if (o.has(f)) return o.opt(f);
        o = Profiles.get(s, Profiles.SYSTEM);
        return (Profiles.homePackages(s).contains(pkg) || Profiles.RECENTS.equals(pkg)) && o.has(f) ? o.opt(f) : null;
    }

    private static String label(TileService s, String pkg) {
        try {
            PackageManager pm = s.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
