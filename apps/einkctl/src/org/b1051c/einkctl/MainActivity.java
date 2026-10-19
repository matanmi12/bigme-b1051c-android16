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

/** Single-screen control panel, built for e-ink: InKognito look (Ink), no animations, large targets. */
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
        sv.setBackgroundColor(Ink.CREAM);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        // Android 15+ draws targetSdk 35 apps edge to edge: keep the content clear of the status bar and the taskbar
        sv.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets bars = ins.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            root.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
        sv.addView(root);
        setContentView(sv);

        header("E-Ink Control");

        // Front light
        section("Front light");
        check("Front light on", p.getBoolean("light_on", false), v -> {
            p.edit().putBoolean("light_on", v).apply();
            Eink.applyFrontlight(this);
        });
        slider("Cold (white)", "cold", 0, Eink.FL_MAX, 40, v -> { if (p.getBoolean("light_on", false)) Eink.applyFrontlight(this); });
        slider("Warm (amber)", "warm", 0, Eink.FL_MAX, 40, Ink.GOLD, v -> { if (p.getBoolean("light_on", false)) Eink.applyFrontlight(this); });
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
            RadioButton rb = radio(Eink.MODE_NAMES[i], 1000 + i);
            rg.addView(rb);
            if (i == cur) rb.setChecked(true);
        }
        rg.setOnCheckedChangeListener((g, id) -> {
            p.edit().putInt("mode", Eink.MODES[id - 1000]).apply();
            Eink.applyRefreshMode(this);
        });
        root.addView(rg);
        button("Clear screen (full refresh)", true, v -> Eink.clearScreen(this));
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
        Ink.styleSeekBar(dpiBar, Ink.STEEL);
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
            RadioButton rb = radio(shadeNames[i], 3000 + i);
            sg.addView(rb);
            if (shadeVals[i] == curShade) rb.setChecked(true);
        }
        sg.setOnCheckedChangeListener((g, id) -> Eink.putSecure(this, "b1051c_shade_theme", shadeVals[id - 3000]));
        root.addView(sg);
        // the plugin's default outline: 2.5 dp with the InKognito colours, 1.5 dp plain
        boolean[] inkShade = {Eink.secureInt(this, "b1051c_shade_palette", 1) != 0};
        int strokeNow = Eink.secureInt(this, "b1051c_shade_stroke", -1);
        if (strokeNow <= 0) strokeNow = inkShade[0] ? 25 : 15;
        check("InKognito colors (off = black and white)", inkShade[0],
                v -> Eink.putSecure(this, "b1051c_shade_palette", v ? 1 : 0));
        TextView strokeLabel = text("Outline width: " + strokeNow / 10f + " dp", 17, false);
        SeekBar stroke = new SeekBar(this);
        stroke.setMin(5);
        stroke.setMax(40);
        stroke.setProgress(strokeNow);
        Ink.styleSeekBar(stroke, Ink.STEEL);
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
        for (int i = 0; i < Eink.PROFILE_NAMES.length; i++) pg.addView(radio(Eink.PROFILE_NAMES[i], 2000 + i));
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

    /** One app = an outlined card: name, then its profile summary in the caption colour. */
    private void appRow(String label, String key) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTextColor(Ink.INK);
        b.setTypeface(Ink.font(this, 400));
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setBackground(Ink.pill(this, Ink.CREAM, 14));
        b.setStateListAnimator(null);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setText(rowText(label, Profiles.summary(Profiles.get(this, key))));
        b.setOnClickListener(v -> editProfile(label, key, b));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, dp(5));
        root.addView(b, lp);
    }

    private CharSequence rowText(String label, String summary) {
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder(label);
        s.setSpan(new android.text.style.TypefaceSpan(Ink.font(this, 600)), 0, s.length(), 0);
        int at = s.length();
        s.append("\n").append(summary);
        s.setSpan(new android.text.style.ForegroundColorSpan(Ink.MUTED), at, s.length(), 0);
        s.setSpan(new android.text.style.RelativeSizeSpan(0.85f), at, s.length(), 0);
        return s;
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
        row.setText(rowText(label, Profiles.summary(x)));
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
        t.setTextColor(Ink.INK);
        t.setTypeface(Ink.font(this, 500));
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

    /** Board 05 identity: the title in the wordmark face, "InKognito" in the logo's steel + bordeaux. */
    private void header(String t) {
        TextView brand = new TextView(this);
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder("InKognito");
        s.setSpan(new android.text.style.ForegroundColorSpan(Ink.STEEL), 0, 2, 0);
        s.setSpan(new android.text.style.ForegroundColorSpan(Ink.BORDEAUX), 2, 9, 0);
        s.append("  ·  Less bloat. More ink.");
        s.setSpan(new android.text.style.ForegroundColorSpan(Ink.MUTED), 9, s.length(), 0);
        s.setSpan(new android.text.style.RelativeSizeSpan(0.7f), 9, s.length(), 0);
        brand.setText(s);
        brand.setTextSize(20);
        brand.setTypeface(Ink.display(this));
        root.addView(brand);
        TextView tv = text(t, 36, false);
        tv.setTypeface(Ink.display(this));
        tv.setPadding(0, dp(2), 0, dp(10));
    }

    /** Riveted steel band, then the section title (board 05). */
    private void section(String t) {
        View band = new View(this);
        band.setBackground(new Ink.RivetBand(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10));
        lp.topMargin = dp(18);
        root.addView(band, lp);
        TextView tv = text(t, 22, true);
        tv.setPadding(0, dp(12), 0, dp(6));
    }

    private TextView note(String t) {
        TextView tv = text(t, 15, false);
        tv.setTextColor(Ink.MUTED);
        tv.setPadding(0, dp(4), 0, dp(12));
        return tv;
    }

    private TextView text(String t, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(sp);
        tv.setTextColor(Ink.INK);
        tv.setTypeface(Ink.font(this, bold ? 600 : 400));
        root.addView(tv);
        return tv;
    }

    private RadioButton radio(String t, int id) {
        RadioButton rb = new RadioButton(this);
        rb.setText(t);
        rb.setTextSize(18);
        rb.setTextColor(Ink.INK);
        rb.setTypeface(Ink.font(this, 400));
        rb.setButtonTintList(Ink.tint(Ink.TEAL));
        rb.setId(id);
        rb.setPadding(dp(8), dp(10), dp(8), dp(10));
        return rb;
    }

    private interface IntCb { void on(int v); }
    private interface BoolCb { void on(boolean v); }

    /** On/off setting: text left, badge toggle right (teal track + light-gold knob when on). */
    private android.widget.Switch check(String t, boolean val, BoolCb cb) {
        android.widget.Switch c = new android.widget.Switch(this);
        c.setText(t);
        c.setTextSize(18);
        c.setTextColor(Ink.INK);
        c.setTypeface(Ink.font(this, 400));
        c.setTrackDrawable(Ink.switchTrack(this));
        c.setThumbDrawable(Ink.switchThumb(this));
        c.setTrackTintList(null);
        c.setThumbTintList(null);
        c.setSwitchMinWidth(dp(58));
        c.setSwitchPadding(dp(16));
        c.setShowText(false);
        c.setChecked(val);
        c.setPadding(dp(4), dp(12), dp(4), dp(12));
        c.setOnCheckedChangeListener((b, v) -> cb.on(v));
        root.addView(c, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return c;
    }

    /** Show the selected profile's vivid / contrast / brightness (Custom: the saved slider values). */
    private void showImageValues(SeekBar[] img) {
        int[] v = Eink.PRESETS[Eink.clamp(p.getInt("profile", 0), 0, Eink.PRESETS.length - 1)];
        String[] keys = {"vivid", "contrast", "cbright"};
        for (int i = 0; i < 3; i++) img[i].setProgress(v[i + 1] >= 0 ? v[i + 1] : p.getInt(keys[i], 0));
    }

    private SeekBar slider(String label, String key, int min, int max, int def, IntCb cb) {
        return slider(label, key, min, max, def, Ink.STEEL, cb);
    }

    /** Labelled badge slider; {@code fill} = steel, or gold for the warm light (as in the shade). */
    private SeekBar slider(String label, String key, int min, int max, int def, int fill, IntCb cb) {
        TextView tv = text(label + ": " + p.getInt(key, def), 17, false);
        SeekBar sb = new SeekBar(this);
        sb.setMin(min);
        sb.setMax(max);
        sb.setProgress(p.getInt(key, def));
        Ink.styleSeekBar(sb, fill);
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

    private void button(String t, View.OnClickListener l) { button(t, false, l); }

    /** Pill button with an ink outline; primary = bordeaux with cream text (board 05 "Apply"). */
    private void button(String t, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setTextSize(18);
        b.setAllCaps(false);
        b.setTypeface(Ink.font(this, 600));
        b.setTextColor(primary ? Ink.CREAM : Ink.INK);
        b.setBackground(Ink.pill(this, primary ? Ink.BORDEAUX : Ink.CREAM, 999));
        b.setStateListAnimator(null);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
        lp.setMargins(0, dp(8), 0, dp(10));
        root.addView(b, lp);
    }
}
