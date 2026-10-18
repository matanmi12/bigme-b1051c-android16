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
        note("Quick settings and notifications drawn for e-ink: solid colors, outlines, no gray shade. The two "
                + "front-light sliders replace the brightness slider in every style.");
        button("White wallpaper (home + lock screen)", v -> new Thread(() -> Eink.whiteWallpaper(this)).start());

        // Colour
        section("Color");
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
        root.addView(pg);
        SeekBar[] img = new SeekBar[3];                             // vivid, contrast, colour brightness
        IntCb custom = v -> {                                       // moving a slider = Custom, starting from what is shown
            if (p.getInt("profile", 0) != 4) {
                p.edit().putInt("vivid", img[0].getProgress()).putInt("contrast", img[1].getProgress())
                        .putInt("cbright", img[2].getProgress()).apply();
                pg.check(2004);
            } else {
                Eink.applyImage(this);
            }
        };
        img[0] = slider("Vivid (saturation)", "vivid", 0, 100, 0, custom);
        img[1] = slider("Contrast", "contrast", 0, 100, 0, custom);
        img[2] = slider("Color brightness", "cbright", 0, 100, 0, custom);
        showImageValues(img);
        pg.setOnCheckedChangeListener((g, id) -> {
            if (id < 2000) return;
            Eink.selectProfile(this, id - 2000);
            rg.check(1000 + Eink.modeIndex(p.getInt("mode", 0)));   // presets may change the refresh mode
            showImageValues(img);
        });
        note("Bigme's own color processing (same formulas and color tables as the stock ROM), rendered by "
                + "the display shim. The sliders show the selected preset; moving one switches to Custom. Vivid and "
                + "the presets change colored content only, so a black-and-white screen looks the same. Comic uses "
                + "only its color table (like stock).");
        button("Save these colors as a named preset…", v -> {
            android.widget.EditText name = new android.widget.EditText(this);
            name.setHint("Preset name");
            new AlertDialog.Builder(this).setTitle("New color preset").setView(name)
                    .setPositiveButton("Save", (d, w) -> {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) return;
                        Profiles.saveUserPreset(this, n, img[0].getProgress(), img[1].getProgress(), img[2].getProgress(), 0);
                        android.widget.Toast.makeText(this, "Saved \"" + n + "\" — choose it in an app's profile below",
                                android.widget.Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Cancel", null).show();
        });

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
        check("Color restoration (Fast / Extreme)", p.getBoolean("color_restoration", false), v -> {
            p.edit().putBoolean("color_restoration", v).apply();
            Eink.applyImage(this);
        });
        note("Bigme's alternative dithering for color in the fast modes. Also a quick-settings tile.");
        check("Anti-aliasing (Fast mode only)", p.getBoolean("anti_alias", false), v -> {
            p.edit().putBoolean("anti_alias", v).apply();
            Eink.applyRefreshMode(this);
        });
        note("Slightly sharper text in Fast, but gray areas can look striped.");
        check("Full refresh when returning to the home screen", p.getBoolean("home_clean", true),
                v -> p.edit().putBoolean("home_clean", v).apply());
        check("Fast mode while the keyboard is open", p.getBoolean("ime_fast", true), v -> {
            p.edit().putBoolean("ime_fast", v).apply();
            Eink.applyRefreshMode(this);
        });
        check("Full refresh when inverted colors switch on/off", p.getBoolean("clean_on_dark", true),
                v -> p.edit().putBoolean("clean_on_dark", v).apply());
        slider("Full refresh every N while colors are inverted (0 = as above)", "n_dark", 0, 50, 0,
                v -> Eink.applyRefreshMode(this));
        check("Bold text (all apps)", Eink.secureInt(this, "font_weight_adjustment", 0) > 0,
                v -> Eink.putSecure(this, "font_weight_adjustment", v ? 300 : 0));
        note("Bold text is global (changing it per app would restart apps). High-contrast text, grayscale and "
                + "inverted colors can be set per app below.");

        // Dual system
        section("System");
        sysStatus = note("…");
        android.widget.Spinner sd = spinner(root, "Screen left on the panel after power-off / during a reboot",
                ShutdownScreen.LABELS, ShutdownScreen.index(this));
        sd.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> a, View v, int pos, long id) {
                p.edit().putString("shutdown_screen", ShutdownScreen.VALUES[pos]).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> a) {}
        });
        button("Boot the other system", v -> new AlertDialog.Builder(this)
                .setMessage("Restart into the other system now?")
                .setPositiveButton("Restart", (d, w) -> Eink.bootOtherSystem())
                .setNegativeButton("Cancel", null).show());
        button("Export all settings and profiles", v -> new Thread(() -> {
            String r = Eink.exportSettings(this);
            runOnUiThread(() -> android.widget.Toast.makeText(this, r, android.widget.Toast.LENGTH_LONG).show());
        }).start());
        button("Import settings and profiles", v -> new AlertDialog.Builder(this)
                .setMessage("Replace all E-Ink Control settings with " + Eink.exportFile(this) + "?")
                .setPositiveButton("Import", (d, w) -> new Thread(() -> {
                    String r = Eink.importSettings(this);
                    runOnUiThread(() -> { android.widget.Toast.makeText(this, r, android.widget.Toast.LENGTH_LONG).show(); recreate(); });
                }).start())
                .setNegativeButton("Cancel", null).show());
        // Per-app profiles
        section("Per-app profiles");
        note("Tap an app to set its own refresh mode, colors, grayscale, inverted colors, high-contrast text, "
                + "full-refresh frequency or anti-shake. Anything left on \"Inherit\" follows the settings above. "
                + "\"Home screen\" applies to the launcher. Needs the E-Ink Control accessibility service.");
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
        appRow("Home screen (launcher)", Profiles.SYSTEM);
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        java.util.Collections.sort(apps, (a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (android.content.pm.ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            appRow(ri.loadLabel(pm).toString(), pkg);
        }
    }

    private void appRow(String label, String key) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(Color.BLACK);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setText(label + "  —  " + Profiles.summary(Profiles.get(this, key)));
        b.setOnClickListener(v -> editProfile(label, key, b));
        root.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
    }

    private static final String[] TRI = {"Inherit", "On", "Off"};
    private static final int[] NS = {0, 1, 2, 3, 4, 5, 6, 8, 10, 15, 20, 30};

    /** Editor for one profile: every field has "Inherit" (decision 1.1 B). */
    private void editProfile(String label, String key, Button row) {
        org.json.JSONObject o = Profiles.get(this, key);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        String[] modes = new String[Eink.MODES.length + 1];
        modes[0] = "Inherit";
        System.arraycopy(Eink.MODE_NAMES, 0, modes, 1, Eink.MODE_NAMES.length);
        android.widget.Spinner refresh = spinner(box, "Refresh mode", modes,
                o.has(Profiles.F_REFRESH) ? 1 + Eink.modeIndex(o.optInt(Profiles.F_REFRESH)) : 0);
        String[] presets = Profiles.presetNames(this);
        String[] presetOpts = new String[presets.length + 1];
        presetOpts[0] = "Inherit";
        System.arraycopy(presets, 0, presetOpts, 1, presets.length);
        int pi = 0;
        for (int i = 0; i < presets.length; i++) if (presets[i].equals(o.optString(Profiles.F_PRESET, null))) pi = i + 1;
        android.widget.Spinner preset = spinner(box, "Colors (preset; suggests a refresh mode)", presetOpts, pi);
        android.widget.Spinner grey = spinner(box, "Grayscale (black and white)", TRI, tri(o, Profiles.F_GREY));
        android.widget.Spinner invert = spinner(box, "Inverted colors (dark)", TRI, tri(o, Profiles.F_INVERT));
        android.widget.Spinner hc = spinner(box, "High-contrast text", TRI, tri(o, Profiles.F_HC));
        String[] nOpts = new String[NS.length + 1];
        nOpts[0] = "Inherit";
        for (int i = 0; i < NS.length; i++) nOpts[i + 1] = NS[i] == 0 ? "Off" : "Every " + NS[i];
        int ni = 0;
        for (int i = 0; i < NS.length; i++) if (o.has(Profiles.F_N) && NS[i] == o.optInt(Profiles.F_N)) ni = i + 1;
        android.widget.Spinner n = spinner(box, "Full refresh every N page turns", nOpts, ni);
        android.widget.Spinner shake = spinner(box, "Anti-shake", TRI, tri(o, Profiles.F_SHAKE));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this).setTitle(label).setView(sv)
                .setPositiveButton("Save", (d, w) -> {
                    org.json.JSONObject x = new org.json.JSONObject();
                    try {
                        if (refresh.getSelectedItemPosition() > 0) x.put(Profiles.F_REFRESH, Eink.MODES[refresh.getSelectedItemPosition() - 1]);
                        if (preset.getSelectedItemPosition() > 0) x.put(Profiles.F_PRESET, presets[preset.getSelectedItemPosition() - 1]);
                        putTri(x, Profiles.F_GREY, grey);
                        putTri(x, Profiles.F_INVERT, invert);
                        putTri(x, Profiles.F_HC, hc);
                        if (n.getSelectedItemPosition() > 0) x.put(Profiles.F_N, NS[n.getSelectedItemPosition() - 1]);
                        putTri(x, Profiles.F_SHAKE, shake);
                    } catch (org.json.JSONException ignored) {}
                    saveProfile(label, key, x, row);
                })
                .setNeutralButton("Reset", (d, w) -> saveProfile(label, key, new org.json.JSONObject(), row))
                .setNegativeButton("Cancel", null).show();
    }

    private void saveProfile(String label, String key, org.json.JSONObject x, Button row) {
        Profiles.put(this, key, x);
        row.setText(label + "  —  " + Profiles.summary(x));
        Profiles.apply(this, AppProfileService.currentPackage(), true);
    }

    private static int tri(org.json.JSONObject o, String f) { return !o.has(f) ? 0 : o.optBoolean(f) ? 1 : 2; }

    private static void putTri(org.json.JSONObject x, String f, android.widget.Spinner s) throws org.json.JSONException {
        if (s.getSelectedItemPosition() == 1) x.put(f, true);
        else if (s.getSelectedItemPosition() == 2) x.put(f, false);
    }

    private android.widget.Spinner spinner(LinearLayout box, String label, String[] items, int sel) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(Color.BLACK);
        t.setPadding(0, dp(10), 0, 0);
        box.addView(t);
        android.widget.Spinner s = new android.widget.Spinner(this, android.widget.Spinner.MODE_DIALOG);
        android.widget.ArrayAdapter<String> a = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(sel);
        box.addView(s);
        return s;
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

    /** Show the selected profile's vivid / contrast / brightness (Custom: the saved slider values). */
    private void showImageValues(SeekBar[] img) {
        int[] v = Eink.PRESETS[Eink.clamp(p.getInt("profile", 0), 0, Eink.PRESETS.length - 1)];
        String[] keys = {"vivid", "contrast", "cbright"};
        for (int i = 0; i < 3; i++) img[i].setProgress(v[i + 1] >= 0 ? v[i + 1] : p.getInt(keys[i], 0));
    }

    private SeekBar slider(String label, String key, int min, int max, int def, IntCb cb) {
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
        return sb;
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
