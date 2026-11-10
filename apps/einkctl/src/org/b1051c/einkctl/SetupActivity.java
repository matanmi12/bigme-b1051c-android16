package org.b1051c.einkctl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * InKognito Setup: the optional parts of the ROM, one switch each (on = keep), only the ones installed right now.
 * First run: started as root once Google setup is done (am start -n org.b1051c.einkctl/.SetupActivity; a non-exported
 * activity, which root and this app may start); afterwards a manual tool (settings screen, System). Apply removes the
 * switched-off parts as root, off the UI thread, then shows what worked. Keep everything / Apply mark setup done
 * (prefs file "setup": not in the settings export, so an import on another tablet never skips it) and write the
 * choices to /data/adb/b1051c/setup-choices.json. Same look as MainActivity: no animations, outlines, large targets.
 */
public class SetupActivity extends Activity {
    static final String KO = "org.koreader.launcher", LAWN = "app.lawnchair", ICONS = "org.b1051c.iconpack";
    /** System Launcher3 QuickStep: the taskbar / Recents provider, and home once Lawnchair is gone. Its runtime RRO
     *  org.b1051c.overlay.launcher (no first-screen search row, nav-row jump fix) targets Launcher3, not Lawnchair,
     *  so it stays. */
    static final String L3 = "com.android.launcher3";
    static final String ICON_MODULE = "/data/adb/modules/b1051c-icons";
    static final String CHOICES = "/data/adb/b1051c/setup-choices.json";
    static final String PREFS = "setup", K_DONE = "setup_done", K_TIME = "setup_time";
    private static final long UNINSTALL_MS = 60_000, HOME_MS = 20_000;

    /** Default home as apply-system-defaults.sh app_default_home does it (role HOME, then set-home-activity with the
     *  resolved HOME activity), but only while Lawnchair holds the role or nobody does: another home app the owner
     *  picked stays. Prints home=<holder>. */
    private static final String HOME_CMD = "h=$(cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null | tr -d '\\r'); "
            + "if [ -z \"$h\" ] || [ \"$h\" = " + LAWN + " ]; then "
            + "cmd role add-role-holder --user 0 android.app.role.HOME " + L3 + "; sleep 1; "
            + "h=$(cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null | tr -d '\\r'); "
            + "if [ \"$h\" != " + L3 + " ]; then "
            + "a=$(cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME " + L3 + " 2>/dev/null | tr -d '\\r' | tail -n 1); "
            + "case \"$a\" in " + L3 + "/*) cmd package set-home-activity --user 0 \"$a\" ;; esac; "
            + "h=$(cmd role get-role-holders --user 0 android.app.role.HOME 2>/dev/null | tr -d '\\r'); "
            + "fi; fi; echo \"home=$h\"";
    /** apply-system-defaults.sh start_home */
    private static final String START_HOME = "am start --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME";

    private SharedPreferences p;
    private Ink.Pal pal;
    private LinearLayout root;
    private boolean hasKo, hasLawn, hasIconApp, hasIconModule, busy, homeChanged;
    private Switch keepKo, keepLawn, keepIcons;

    // No uiMode in the manifest configChanges (as MainActivity): Android's Dark theme recreates the screen.
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = Eink.prefs(this);
        pal = Ink.pal(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(pal.paper);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        sv.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets bars = ins.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            root.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
        sv.addView(root);
        setContentView(sv);

        header(getString(R.string.setup_title));
        note(getString(R.string.setup_intro));
        TextView checking = note(getString(R.string.setup_checking));
        // the icon module lives in /data/adb (root only); a module already marked for removal counts as gone
        new Thread(() -> {
            boolean ko = installed(KO), lawn = installed(LAWN), iconApp = installed(ICONS);
            boolean module = Root.run("[ -d " + ICON_MODULE + " ] && [ ! -f " + ICON_MODULE + "/remove ] && echo yes")
                    .equals("yes");
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                hasKo = ko; hasLawn = lawn; hasIconApp = iconApp; hasIconModule = module;
                root.removeView(checking);
                build();
            });
        }).start();
    }

    private void build() {
        if (hasKo || hasLawn || hasIconApp || hasIconModule) {
            section(getString(R.string.setup_section_apps));
            if (hasKo) { keepKo = check(getString(R.string.setup_koreader), true, null); note(getString(R.string.setup_koreader_note)); }
            if (hasLawn) { keepLawn = check(getString(R.string.setup_lawnchair), true, null); note(getString(R.string.setup_lawnchair_note)); }
            if (hasIconApp || hasIconModule) { keepIcons = check(getString(R.string.setup_icons), true, null); note(getString(R.string.setup_icons_note)); }
        } else {
            note(getString(R.string.setup_nothing));
        }
        // the Quick Settings tile's code (TileAntiShake): the pref, then Eink.applyImage
        section(getString(R.string.setup_section_display));
        check(getString(R.string.setup_antishake), p.getBoolean("anti_flicker", false), v -> {
            p.edit().putBoolean("anti_flicker", v).apply();
            Eink.applyImage(this);
        });
        note(getString(R.string.setup_antishake_note));

        button(getString(R.string.setup_keep_all), false, v -> keepAll());
        button(getString(R.string.setup_apply), true, v -> apply());
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean off(Switch s) { return s != null && !s.isChecked(); }

    private void keepAll() {
        if (busy) return;
        JSONObject c = new JSONObject();
        put(c, "koreader", hasKo ? "kept" : "not_installed");
        put(c, "lawnchair", hasLawn ? "kept" : "not_installed");
        put(c, "iconpack", hasIconApp || hasIconModule ? "kept" : "not_installed");
        Root.async(saveCmd("keep", c, false));    // the keeper holds the process: the write outlives this screen
        markDone();
        finish();
    }

    private void apply() {
        if (busy) return;
        List<String> names = new ArrayList<>();
        if (off(keepKo)) names.add(getString(R.string.setup_koreader));
        if (off(keepLawn)) names.add(getString(R.string.setup_lawnchair));
        if (off(keepIcons)) names.add(getString(R.string.setup_icons));
        if (names.isEmpty()) { keepAll(); return; }
        StringBuilder m = new StringBuilder();
        for (String n : names) m.append(m.length() == 0 ? "" : "\n").append("• ").append(n);
        if (off(keepIcons) && hasIconModule) m.append("\n\n").append(getString(R.string.setup_restart_note));
        Ink.bodyWeight(new AlertDialog.Builder(this)
                .setTitle(R.string.setup_confirm_title)
                .setMessage(m)
                .setPositiveButton(R.string.setup_confirm_remove, (d, w) -> remove())
                .setNegativeButton(R.string.setup_cancel, null).show());
    }

    /** The removals, in order: KOReader, Lawnchair (home first), icon pack app + module. Only the switches are read
     *  here, on the UI thread; the rest runs on a worker. */
    private void remove() {
        busy = true;
        boolean ko = off(keepKo), lawn = off(keepLawn), icons = off(keepIcons);
        AlertDialog working = new AlertDialog.Builder(this).setMessage(R.string.setup_working).setCancelable(false).show();
        Ink.bodyWeight(working);
        new Thread(() -> {
            List<String> lines = new ArrayList<>();
            boolean failed = false, restart = false;
            JSONObject c = new JSONObject();

            if (ko) {
                String e = uninstall(KO);
                lines.add(step(R.string.setup_koreader, e));
                put(c, "koreader", e == null ? "removed" : "failed");
                failed |= e != null;
            } else {
                put(c, "koreader", hasKo ? "kept" : "not_installed");
            }

            if (lawn) {
                String h = Root.run(HOME_CMD, HOME_MS);
                String holder = h.contains("home=") ? h.substring(h.lastIndexOf("home=") + 5).trim() : "";
                if (holder.isEmpty() || holder.equals(LAWN)) {
                    lines.add(getString(R.string.setup_step_home_failed));
                    put(c, "lawnchair", "failed");
                    failed = true;
                } else {
                    if (holder.equals(L3)) { lines.add(getString(R.string.setup_step_home)); homeChanged = true; }
                    String e = uninstall(LAWN);
                    lines.add(step(R.string.setup_lawnchair, e));
                    put(c, "lawnchair", e == null ? "removed" : "failed");
                    put(c, "home", holder);
                    failed |= e != null;
                }
            } else {
                put(c, "lawnchair", hasLawn ? "kept" : "not_installed");
            }

            if (icons) {
                boolean ok = true;
                if (hasIconApp) {
                    String e = uninstall(ICONS);
                    lines.add(step(R.string.setup_step_icons_app, e));
                    ok = e == null;
                }
                if (hasIconModule) {
                    String r = Root.run("touch " + ICON_MODULE + "/remove; echo rc=$?");
                    if (r.endsWith("rc=0")) {
                        lines.add(getString(R.string.setup_step_icons_module));
                        restart = true;
                    } else {
                        lines.add(getString(R.string.setup_step_failed, getString(R.string.setup_icons), reason(r)));
                        ok = false;
                    }
                }
                put(c, "iconpack", ok ? "removed" : "failed");
                failed |= !ok;
            } else {
                put(c, "iconpack", hasIconApp || hasIconModule ? "kept" : "not_installed");
            }

            Root.run(saveCmd("apply", c, restart));
            markDone();
            boolean fail = failed, reboot = restart;
            runOnUiThread(() -> {
                busy = false;
                if (isDestroyed()) return;               // recreated meanwhile (Dark theme): the work is done anyway
                try { working.dismiss(); } catch (RuntimeException ignored) {}
                result(lines, fail, reboot);
            });
        }).start();
    }

    private void result(List<String> lines, boolean failed, boolean restart) {
        StringBuilder m = new StringBuilder();
        for (String l : lines) m.append(m.length() == 0 ? "" : "\n").append(l);
        AlertDialog.Builder d = new AlertDialog.Builder(this)
                .setTitle(failed ? R.string.setup_result_failed_title : R.string.setup_result_title)
                .setCancelable(false);
        if (restart) {
            m.append("\n\n").append(getString(R.string.setup_restart_note));
            d.setPositiveButton(R.string.setup_restart_now, (x, w) -> Root.async("svc power reboot"))
                    .setNegativeButton(R.string.setup_later, (x, w) -> close());
        } else {
            d.setPositiveButton(R.string.setup_ok, (x, w) -> close());
        }
        Ink.bodyWeight(d.setMessage(m).show());
    }

    /** Home was switched to Launcher3: bring it up as the ROM does after setting the default home (start_home). */
    private void close() {
        if (homeChanged) Root.async(START_HOME);
        finish();
    }

    /** null = removed, else a short reason. pm prints "Success" (exit codes vary between Android versions). */
    private static String uninstall(String pkg) {
        String r = Root.run("pm uninstall " + pkg, UNINSTALL_MS);
        return r.contains("Success") ? null : reason(r);
    }

    private static String reason(String out) {
        String s = out.trim();
        int nl = s.lastIndexOf('\n');
        if (nl >= 0) s = s.substring(nl + 1);
        if (s.isEmpty()) s = "no output";
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private String step(int name, String err) {
        return err == null ? getString(R.string.setup_step_ok, getString(name))
                : getString(R.string.setup_step_failed, getString(name), err);
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (org.json.JSONException ignored) {}
    }

    private void markDone() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(K_DONE, true)
                .putLong(K_TIME, System.currentTimeMillis()).apply();
    }

    /** setup-choices.json: action, components (kept / removed / failed / not_installed), anti-shake, time. Written
     *  through base64 (no shell quoting), via a temp file so a reader never sees half a file. */
    private String saveCmd(String action, JSONObject components, boolean restart) {
        long now = System.currentTimeMillis();
        JSONObject o = new JSONObject();
        put(o, "action", action);
        put(o, "components", components);
        put(o, "anti_shake", p.getBoolean("anti_flicker", false));
        put(o, "restart_needed", restart);
        put(o, "time", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US).format(new java.util.Date(now)));
        put(o, "epoch_ms", now);
        String version;
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { version = "?"; }
        put(o, "einkctl", version);
        String b64 = android.util.Base64.encodeToString(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        return "mkdir -p " + CHOICES.substring(0, CHOICES.lastIndexOf('/')) + " && echo " + b64 + " | base64 -d > "
                + CHOICES + ".tmp && chmod 0644 " + CHOICES + ".tmp && mv -f " + CHOICES + ".tmp " + CHOICES;
    }

    // ---- tiny UI helpers (MainActivity's, same look) ------------------------------------------------
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Board 05 identity line, then the title in the wordmark face (MainActivity.header). */
    private void header(String t) {
        TextView brand = new TextView(this);
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder("InKognito");
        int split = getResources().getInteger(R.integer.brand_split);
        s.setSpan(new android.text.style.ForegroundColorSpan(pal.brandInk), 0, split, 0);
        s.setSpan(new android.text.style.ForegroundColorSpan(pal.brandOgnito), split, 9, 0);
        s.append("  ·  Less bloat. More ink.");
        s.setSpan(new android.text.style.ForegroundColorSpan(pal.muted), 9, s.length(), 0);
        s.setSpan(new android.text.style.RelativeSizeSpan(0.7f), 9, s.length(), 0);
        brand.setText(s);
        brand.setTextSize(20);
        brand.setTypeface(Ink.display(this));
        root.addView(brand);
        TextView tv = text(t, 36, false);
        tv.setTextColor(pal.display);
        tv.setTypeface(Ink.display(this));
        tv.setPadding(0, dp(2), 0, dp(10));
    }

    /** Riveted steel band, then the section title (board 05). */
    private void section(String t) {
        View band = new View(this);
        band.setBackground(new Ink.RivetBand(this, pal));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10));
        lp.topMargin = dp(18);
        root.addView(band, lp);
        TextView tv = text(t, 22, true);
        tv.setTextColor(pal.heading);
        tv.setPadding(0, dp(12), 0, dp(6));
    }

    private TextView note(String t) {
        TextView tv = text(t, 15, false);
        tv.setTextColor(pal.muted);
        tv.setTypeface(Ink.mutedFont(this));
        tv.setPadding(0, dp(4), 0, dp(12));
        return tv;
    }

    private TextView text(String t, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(sp);
        tv.setTextColor(pal.ink);
        tv.setTypeface(Ink.font(this, bold ? 600 : 400));
        root.addView(tv);
        return tv;
    }

    private interface BoolCb { void on(boolean v); }

    /** On/off row: text left, badge toggle right (MainActivity.check); cb may be null (read on Apply). */
    private Switch check(String t, boolean val, BoolCb cb) {
        Switch c = new Switch(this);
        c.setText(t);
        c.setTextSize(18);
        c.setTextColor(pal.ink);
        c.setTypeface(Ink.font(this, 400));
        c.setTrackDrawable(Ink.switchTrack(this, pal));
        c.setThumbDrawable(Ink.switchThumb(this, pal));
        c.setTrackTintList(null);
        c.setThumbTintList(null);
        c.setSwitchMinWidth(dp(58));
        c.setSwitchPadding(dp(16));
        c.setShowText(false);
        c.setChecked(val);
        c.setPadding(dp(4), dp(12), dp(4), dp(12));
        if (cb != null) c.setOnCheckedChangeListener((b, v) -> cb.on(v));
        root.addView(c, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return c;
    }

    /** Pill button with an ink outline; primary = bordeaux with cream text (MainActivity.button). */
    private void button(String t, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(18);
        b.setAllCaps(false);
        b.setTypeface(Ink.font(this, 600));
        b.setTextColor(primary ? pal.onPrimary : pal.ink);
        b.setBackground(Ink.pill(this, pal, primary ? pal.primary : pal.card, 999));
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
        lp.setMargins(0, dp(8), 0, dp(10));
        root.addView(b, lp);
    }
}
