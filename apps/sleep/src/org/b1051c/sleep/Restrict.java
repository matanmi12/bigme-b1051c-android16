package org.b1051c.sleep;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Apps kept out of the background (ROADMAP 19, owner 2026-10-18: Android's own "Restricted" level, chosen by hand).
 * Restricted = appops RUN_ANY_IN_BACKGROUND ignore + standby bucket "restricted", the same level as Settings > Apps >
 * App battery usage > Restricted: the app opens and works as usual, it only does nothing while it is not on screen
 * (no jobs, alarms or background services). Taking an app off the list sets both back (appops allow, bucket active).
 * The list is applied again at boot and at every sleep, in case Android moved an app to another bucket meanwhile.
 * Pref "restricted": package names, comma-separated.
 */
final class Restrict {
    static final String KEY = "restricted";
    private static final String PKG_RE = "[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+";   // a package name, nothing a shell would read

    private Restrict() {}

    static Set<String> list(Context c) {
        String v = new Prefs(c).p.getString(KEY, "");
        Set<String> out = new TreeSet<>();
        for (String s : v.split(",")) if (s.matches(PKG_RE)) out.add(s);
        return out;
    }

    private static String restrictCmd(String pkg) {
        return "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND ignore; am set-standby-bucket " + pkg + " restricted";
    }

    private static String allowCmd(String pkg) {
        return "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow; am set-standby-bucket " + pkg + " active";
    }

    /** Save the new list and apply the difference; returns a summary line. Not on the UI thread. */
    static String save(Context c, Set<String> now) {
        Set<String> old = list(c), keep = new TreeSet<>();
        StringBuilder cmd = new StringBuilder();
        for (String p : now) if (p.matches(PKG_RE)) { keep.add(p); cmd.append(restrictCmd(p)).append("; "); }
        for (String p : old) if (!keep.contains(p)) cmd.append(allowCmd(p)).append("; ");
        new Prefs(c).p.edit().putString(KEY, String.join(",", keep)).apply();
        String r = cmd.length() == 0 ? "" : Root.run(cmd + "echo ok");
        int n = keep.size();
        String line = (n == 0 ? "No apps kept out of the background" : n == 1 ? "1 app kept out of the background"
                : n + " apps kept out of the background") + (r.isEmpty() || r.endsWith("ok") ? "" : ": " + r);
        Power.log(c, line);
        return line;
    }

    /** Boot and sleep: the list again (idempotent). */
    static void reapply(Context c) {
        Set<String> l = list(c);
        if (l.isEmpty()) return;
        StringBuilder cmd = new StringBuilder();
        for (String p : l) cmd.append(restrictCmd(p)).append(" >/dev/null 2>&1; ");
        Root.run(cmd.toString());
    }

    /** One app that can be chosen. */
    static final class App {
        final String pkg, label;
        App(String pkg, String label) { this.pkg = pkg; this.label = label; }
    }

    /** Launchable apps, by name; not this ROM's own apps, the home screen, Settings or Magisk (they must keep running). */
    static List<App> candidates(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> skip = new LinkedHashSet<>(Arrays.asList("com.android.settings", "com.topjohnwu.magisk", c.getPackageName()));
        ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && home.activityInfo != null) skip.add(home.activityInfo.packageName);
        List<ResolveInfo> all = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        java.util.Map<String, String> byPkg = new java.util.TreeMap<>();
        for (ResolveInfo ri : all) {
            String p = ri.activityInfo.packageName;
            if (skip.contains(p) || p.startsWith("org.b1051c.") || !p.matches(PKG_RE)) continue;
            byPkg.putIfAbsent(p, String.valueOf(ri.loadLabel(pm)));
        }
        List<App> out = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e : byPkg.entrySet()) out.add(new App(e.getKey(), e.getValue()));
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }
}
