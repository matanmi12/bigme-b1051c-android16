package org.b1051c.einkctl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Single-screen control panel, built for e-ink: black on white, no animations, large targets. */
public class MainActivity extends Activity {
    private SharedPreferences p;
    private LinearLayout root;
    private TextView sysStatus;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = Eink.prefs(this);
        startForegroundService(new Intent(this, KeeperService.class));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);
        setContentView(sv);

        header("E-Ink Control");

        // Front light
        section("Front light");
        CheckBox lightOn = check("Front light on", p.getBoolean("light_on", false), v -> {
            p.edit().putBoolean("light_on", v).apply();
            Eink.applyFrontlight(this);
        });
        slider("Cold (white)", "cold", 0, Eink.FL_MAX, 40, v -> { if (p.getBoolean("light_on", false)) Eink.applyFrontlight(this); });
        slider("Warm (amber)", "warm", 0, Eink.FL_MAX, 40, v -> { if (p.getBoolean("light_on", false)) Eink.applyFrontlight(this); });
        check("Follow Android's brightness slider", p.getBoolean("link_brightness", false), v -> {
            p.edit().putBoolean("link_brightness", v).apply();
            Eink.applyFrontlight(this);
        });
        slider("Warmth when following (0 cold … 100 warm)", "warmth", 0, 100, 50, v -> Eink.applyFrontlight(this));
        check("Night schedule (warmer light at night)", p.getBoolean("night_schedule", false), v -> {
            p.edit().putBoolean("night_schedule", v).apply();
            Eink.applyFrontlight(this);
        });
        slider("Night starts at (hour)", "night_start", 0, 23, 21, v -> Eink.applyFrontlight(this));
        slider("Night ends at (hour)", "night_end", 0, 23, 7, v -> Eink.applyFrontlight(this));
        slider("Night cold", "night_cold", 0, Eink.FL_MAX, 0, v -> Eink.applyFrontlight(this));
        slider("Night warm", "night_warm", 0, Eink.FL_MAX, 30, v -> Eink.applyFrontlight(this));
        note("The light switches off automatically while the screen sleeps. When \"follow\" is on, the "
                + "quick-settings brightness slider sets the level and Warmth sets the mix.");

        // Refresh
        section("Refresh mode");
        RadioGroup rg = new RadioGroup(this);
        int cur = Eink.modeIndex(p.getInt("mode", 0));
        for (int i = 0; i < Eink.MODES.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(Eink.MODE_NAMES[i]);
            rb.setTextSize(18);
            rb.setTextColor(Color.BLACK);
            rb.setId(1000 + i);
            rb.setPadding(dp(8), dp(10), dp(8), dp(10));
            rg.addView(rb);
            if (i == cur) rb.setChecked(true);
        }
        rg.setOnCheckedChangeListener((g, id) -> {
            p.edit().putInt("mode", Eink.MODES[id - 1000]).apply();
            Eink.applyRefreshMode(this);
        });
        root.addView(rg);
        button("Clear screen (full refresh)", v -> Eink.clearScreen(this));
        check("Refresh button next to the navigation buttons", p.getBoolean("nav_refresh", true), v -> {
            p.edit().putBoolean("nav_refresh", v).apply();
            AppProfileService.navChanged();
        });
        note("Bottom-left, in the row of back / home / recents. Tap = full refresh (clears ghosting); "
                + "long-press = open E-Ink Control.");

        // Display density
        section("Display density (DPI)");
        TextView dpiLabel = text("DPI: …", 17, false);
        SeekBar dpiBar = new SeekBar(this);
        dpiBar.setMin(Eink.DPI_MIN / 10);
        dpiBar.setMax(Eink.DPI_MAX / 10);                       // steps of 10 dpi
        dpiBar.setPadding(dp(16), dp(14), dp(16), dp(14));
        dpiBar.setEnabled(false);
        dpiBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) dpiLabel.setText("DPI: " + v * 10 + "  (release to apply)");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { applyDpi(s.getProgress() * 10, dpiBar, dpiLabel); }
        });
        root.addView(dpiBar);
        button("Reset to " + Eink.DPI_DEFAULT + " dpi", v -> applyDpi(Eink.DPI_DEFAULT, dpiBar, dpiLabel));
        note("Lower = smaller text and more on screen; higher = larger. Applies at once to all apps. Every change "
                + "asks to be kept and goes back to the previous value after 15 s otherwise.");
        new Thread(() -> {
            int dpiNow = Eink.currentDpi();
            runOnUiThread(() -> {
                dpiCurrent = dpiNow > 0 ? dpiNow : Eink.DPI_DEFAULT;
                dpiLabel.setText(dpiText(dpiCurrent));
                dpiBar.setProgress(Math.round(dpiCurrent / 10f));
                dpiBar.setEnabled(true);
            });
        }).start();

        // Notification shade (E-Ink SystemUI plugin)
        section("Notification shade");
        RadioGroup sg = new RadioGroup(this);
        String[] shadeNames = {"E-ink, follows Dark theme", "E-ink light", "E-ink dark", "Android default"};
        int[] shadeVals = {0, 1, 2, -1};
        int curShade = Eink.secureInt(this, "b1051c_shade_theme", 0);
        for (int i = 0; i < shadeNames.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(shadeNames[i]);
            rb.setTextSize(18);
            rb.setTextColor(Color.BLACK);
            rb.setId(3000 + i);
            rb.setPadding(dp(8), dp(10), dp(8), dp(10));
            sg.addView(rb);
            if (shadeVals[i] == curShade) rb.setChecked(true);
        }
        sg.setOnCheckedChangeListener((g, id) -> Eink.putSecure(this, "b1051c_shade_theme", shadeVals[id - 3000]));
        root.addView(sg);
        TextView strokeLabel = text("Outline width: " + Eink.secureInt(this, "b1051c_shade_stroke", 15) / 10f + " dp", 17, false);
        SeekBar stroke = new SeekBar(this);
        stroke.setMin(5);
        stroke.setMax(40);
        stroke.setProgress(Eink.secureInt(this, "b1051c_shade_stroke", 15));
        stroke.setPadding(dp(16), dp(14), dp(16), dp(14));
        stroke.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) { strokeLabel.setText("Outline width: " + v / 10f + " dp"); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { Eink.putSecure(MainActivity.this, "b1051c_shade_stroke", s.getProgress()); }
        });
        root.addView(stroke);
        note("Quick settings and notifications drawn for e-ink: solid colours, outlines, no grey shade. The two "
                + "front-light sliders replace the brightness slider in every style.");

        // Colour
        section("Colour");
        RadioGroup pg = new RadioGroup(this);
        for (int i = 0; i < Eink.PROFILE_NAMES.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(Eink.PROFILE_NAMES[i]);
            rb.setTextSize(18);
            rb.setTextColor(Color.BLACK);
            rb.setId(2000 + i);
            rb.setPadding(dp(8), dp(10), dp(8), dp(10));
            pg.addView(rb);
        }
        pg.check(2000 + Eink.clamp(p.getInt("profile", 0), 0, Eink.PROFILE_NAMES.length - 1));
        pg.setOnCheckedChangeListener((g, id) -> {
            if (id < 2000) return;
            Eink.selectProfile(this, id - 2000);
            rg.check(1000 + Eink.modeIndex(p.getInt("mode", 0)));   // presets may change the refresh mode
        });
        root.addView(pg);
        IntCb custom = v -> {                                       // moving a slider = Custom profile
            if (p.getInt("profile", 0) != 4) pg.check(2004); else Eink.applyImage(this);
        };
        slider("Vivid (saturation)", "vivid", 0, 100, 0, custom);
        slider("Contrast", "contrast", 0, 100, 0, custom);
        slider("Colour brightness", "cbright", 0, 100, 0, custom);
        note("Bigme's own colour processing (same formulas and colour tables as the stock ROM), rendered by "
                + "the display shim. Magazine, Comic and Video use the stock presets; the sliders apply in Custom.");

        // Image
        section("Image");
        check("Anti-shake (anti-flicker)", p.getBoolean("anti_flicker", false), v -> {
            p.edit().putBoolean("anti_flicker", v).apply();
            Eink.applyImage(this);
        });
        check("Auto clean (clear ghosting in Fast / Extreme)", p.getBoolean("auto_clean", false), v -> {
            p.edit().putBoolean("auto_clean", v).apply();
            Eink.applyImage(this);
        });
        slider("Full refresh every N big updates (0 = off)", "clean_every", 0, 50, 0, v -> Eink.applyImage(this));

        // Dual system
        section("System");
        sysStatus = note("…");
        button("Boot the other system", v -> new AlertDialog.Builder(this)
                .setMessage("Restart into the other system now?")
                .setPositiveButton("Restart", (d, w) -> Eink.bootOtherSystem())
                .setNegativeButton("Cancel", null).show());
        // Per-app profiles
        section("Per-app refresh mode");
        note("Tap an app to cycle its mode. \"Global\" follows the refresh mode above. "
                + "Needs the E-Ink Control accessibility service (enabled by the installer).");
        perAppList();

        refreshSystemStatus();
    }

    private int dpiCurrent = Eink.DPI_DEFAULT;

    private static String dpiText(int dpi) {
        return "DPI: " + dpi + (dpi == Eink.DPI_DEFAULT ? "  (default)" : "  (default " + Eink.DPI_DEFAULT + ")");
    }

    /** Apply a density with a 15 s "keep?" countdown; anything but Keep restores the previous value. */
    private void applyDpi(int want, SeekBar bar, TextView label) {
        int prev = dpiCurrent;
        if (want == prev) { label.setText(dpiText(prev)); bar.setProgress(prev / 10); return; }
        Eink.setDpi(want);
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        int[] left = {15};
        boolean[] done = {false};
        Runnable revert = () -> {
            if (done[0]) return;
            done[0] = true;
            Eink.setDpi(prev);
            bar.setProgress(prev / 10);
            label.setText(dpiText(prev));
        };
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Keep " + want + " dpi?")
                .setMessage("Going back to " + prev + " dpi in " + left[0] + " s")
                .setPositiveButton("Keep", (x, w) -> {
                    done[0] = true;
                    dpiCurrent = want;
                    label.setText(dpiText(want));
                })
                .setNegativeButton("Go back", (x, w) -> revert.run())
                .setCancelable(false)
                .create();
        d.show();
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (done[0]) return;
                if (--left[0] <= 0) { d.dismiss(); revert.run(); return; }
                d.setMessage("Going back to " + prev + " dpi in " + left[0] + " s");
                h.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void refreshSystemStatus() {
        new Thread(() -> {
            boolean dsu = Eink.runningDsu();
            String st = Eink.dsuStatus();
            String txt = (dsu ? "Running: stock (dynamic system)" : "Running: Android 16 (TrebleDroid)")
                    + "\nDynamic system: " + (st.isEmpty() ? "unknown" : st);
            runOnUiThread(() -> sysStatus.setText(txt));
        }).start();
    }

    private void perAppList() {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        java.util.Collections.sort(apps, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (android.content.pm.ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            String label = ri.loadLabel(pm).toString();
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setTextSize(16);
            b.setTextColor(Color.BLACK);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setText(appLine(label, pkg));
            b.setOnClickListener(v -> {
                int cur = p.getInt("app:" + pkg, Eink.UNSET);
                int idx = cur == Eink.UNSET ? -1 : Eink.modeIndex(cur);
                int next = idx + 1;                                  // -1 (Global) -> 0 ... -> last -> Global
                if (next >= Eink.MODES.length) p.edit().remove("app:" + pkg).apply();
                else p.edit().putInt("app:" + pkg, Eink.MODES[next]).apply();
                b.setText(appLine(label, pkg));
            });
            root.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        }
    }

    private String appLine(String label, String pkg) {
        int m = p.getInt("app:" + pkg, Eink.UNSET);
        return label + "  —  " + (m == Eink.UNSET ? "Global" : Eink.MODE_NAMES[Eink.modeIndex(m)]);
    }

    // ---- tiny UI helpers --------------------------------------------------------------------------
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void header(String t) {
        TextView tv = text(t, 26, true);
        tv.setPadding(0, 0, 0, dp(12));
    }

    private void section(String t) {
        View line = new View(this);
        line.setBackgroundColor(Color.BLACK);
        root.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));
        TextView tv = text(t, 21, true);
        tv.setPadding(0, dp(14), 0, dp(6));
    }

    private TextView note(String t) {
        TextView tv = text(t, 15, false);
        tv.setPadding(0, dp(4), 0, dp(12));
        return tv;
    }

    private TextView text(String t, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(sp);
        tv.setTextColor(Color.BLACK);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tv);
        return tv;
    }

    private interface IntCb { void on(int v); }
    private interface BoolCb { void on(boolean v); }

    private CheckBox check(String t, boolean val, BoolCb cb) {
        CheckBox c = new CheckBox(this);
        c.setText(t);
        c.setTextSize(18);
        c.setTextColor(Color.BLACK);
        c.setChecked(val);
        c.setPadding(dp(8), dp(10), dp(8), dp(10));
        c.setOnCheckedChangeListener((b, v) -> cb.on(v));
        root.addView(c);
        return c;
    }

    private void slider(String label, String key, int min, int max, int def, IntCb cb) {
        TextView tv = text(label + ": " + p.getInt(key, def), 17, false);
        SeekBar sb = new SeekBar(this);
        sb.setMin(min);
        sb.setMax(max);
        sb.setProgress(p.getInt(key, def));
        sb.setPadding(dp(16), dp(14), dp(16), dp(14));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) { tv.setText(label + ": " + v); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                p.edit().putInt(key, s.getProgress()).apply();   // apply on release: one e-ink refresh, not dozens
                cb.on(s.getProgress());
            }
        });
        root.addView(sb);
    }

    private void button(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(18);
        b.setAllCaps(false);
        b.setTextColor(Color.BLACK);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        lp.setMargins(0, dp(6), 0, dp(10));
        root.addView(b, lp);
    }
}
