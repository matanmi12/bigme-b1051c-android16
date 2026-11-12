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
    private Ink.Pal pal;
    private LinearLayout root;
    private TextView sysStatus;

    // No uiMode in the manifest configChanges: Android's Dark theme recreates the screen with the night palette.
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = Eink.prefs(this);
        pal = Ink.pal(this);
        startForegroundService(new Intent(this, KeeperService.class));
        AppProfileService.recover(this, "settings screen");

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(pal.paper);
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

        // The accessibility service carries half the features (review 2026-10-21): show its state, offer the switch
        if (!AppProfileService.running()) {
            boolean enabled = AppProfileService.enabledInSettings(this);
            note(enabled
                    ? "The E-Ink Control accessibility service is enabled but not connected right now. It reconnects by itself within a few seconds, or after a reboot."
                    : "The E-Ink Control accessibility service is off. Per-app profiles, Fast while typing, the refresh button and the shutdown picture need it.");
            if (!enabled) button("Turn the accessibility service on", v -> {
                AppProfileService.enable(this);
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> { if (!isDestroyed()) recreate(); }, 1500);
            });
        }

        // Front light
        section("Front light");
        check("Front light on", p.getBoolean("light_on", false), v -> {
            p.edit().putBoolean("light_on", v).apply();
            Eink.applyFrontlight(this);
        });
        RadioGroup lp = new RadioGroup(this);
        for (int i = 0; i < Eink.LIGHT_NAMES.length; i++) {
            int[] v = Eink.LIGHT_PRESETS[i];
            lp.addView(radio(Eink.LIGHT_NAMES[i] + (v[0] >= 0 ? "  (cold " + v[0] + " / warm " + v[1] + ")" : ""), 5000 + i));
        }
        lp.check(5000 + Eink.lightPreset(this));
        root.addView(lp);
        SeekBar[] fl = new SeekBar[2];                              // cold, warm
        lp.setOnCheckedChangeListener((g, id) -> {
            if (id - 5000 == Eink.lightPreset(this)) return;       // set from a slider below
            Eink.selectLight(this, id - 5000);
            fl[0].setProgress(p.getInt("cold", 40)); fl[1].setProgress(p.getInt("warm", 40));
        });
        IntCb lightCustom = v -> {                                  // moving a slider = Custom
            if (Eink.lightPreset(this) != Eink.LIGHT_CUSTOM) {
                p.edit().putInt("light_preset", Eink.LIGHT_CUSTOM).apply();
                lp.check(5000 + Eink.LIGHT_CUSTOM);
            }
            if (p.getBoolean("light_on", false)) Eink.applyFrontlight(this);
        };
        fl[0] = slider("Cold (white)", "cold", 0, Eink.FL_MAX, 40, lightCustom);
        fl[1] = slider("Warm (amber)", "warm", 0, Eink.FL_MAX, 40, pal.sliderWarm, lightCustom);
        note("Morning: bright, a little amber, for the day. Night: dim and warm, for reading in a lit room in the "
                + "evening. Bed: amber only and very dim, for a dark room. Color: bright and half amber, for comics and "
                + "photos (the truest colors on this panel). Moving a slider switches to Custom.");
        note("Night Light (Android's quick-settings tile, or Settings > Display > Night Light, with its schedule): the "
                + "Night light and Dark theme together. Its Intensity goes from Night to Bed. Turning it off brings back "
                + "your light and theme.");
        button("Night Light settings", v -> {
            try { startActivity(new android.content.Intent(android.provider.Settings.ACTION_NIGHT_DISPLAY_SETTINGS)); }
            catch (android.content.ActivityNotFoundException e) { /* Night Light hidden (framework overlay < 1.3) */ }
        });
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
        check("Accept e-ink broadcasts from other apps", p.getBoolean("compat_broadcasts", true),
                v -> p.edit().putBoolean("compat_broadcasts", v).apply());
        note("For Tasker and apps made for Bigme or other e-ink Android builds: a full refresh (com.xrz.GlobalRefresh, "
                + "EINK_FORCE_CLEAR), or a refresh mode for the app on screen until you switch apps "
                + "(EINK_REFRESH_SPEED_CLEAR / BALANCED / SMOOTH / FAST = Regal / HD 256 / Fast / Extreme).");

        // Display density
        section("Display density (DPI)");
        dpiLabel = text("DPI: …", 17, false);
        dpiBar = new SeekBar(this);
        dpiBar.setMin(Eink.DPI_MIN / 10);
        dpiBar.setMax(Eink.DPI_MAX / 10);                       // steps of 10 dpi
        Ink.styleSeekBar(dpiBar, pal, pal.sliderCold);
        dpiBar.setEnabled(false);
        dpiBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean touching;
            private final Runnable apply = () -> applyDpi(dpiBar.getProgress() * 10);
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (user) dpiLabel.setText("DPI: " + v * 10 + "  (release to apply)");
                if (user && !touching) { s.removeCallbacks(apply); s.postDelayed(apply, 600); }   // keyboard / TalkBack
            }
            @Override public void onStartTrackingTouch(SeekBar s) { touching = true; s.removeCallbacks(apply); }
            @Override public void onStopTrackingTouch(SeekBar s) { touching = false; apply.run(); }
        });
        root.addView(dpiBar);
        button("Reset to " + Eink.DPI_DEFAULT + " dpi", v -> applyDpi(Eink.DPI_DEFAULT));
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
        live = this;
        if (dpiWant >= 0) { dpiLabel.setText(dpiText(dpiWant)); showDpiDialog(); }   // recreated mid-countdown

        // Notification shade (E-Ink SystemUI plugin). The forced "E-ink light" / "E-ink dark" choices (1 / 2) are off
        // the screen since the owner chose one master switch (2026-10-20: Android's Dark theme); the key still takes
        // them over adb (settings put secure b1051c_shade_theme 1|2) as a rollback.
        section("Notification shade");
        RadioGroup sg = new RadioGroup(this);
        String[] shadeNames = {"E-ink, follows Dark theme", "Android default"};
        int[] shadeVals = {0, -1};
        int curShade = Eink.secureInt(this, "b1051c_shade_theme", 0);
        for (int i = 0; i < shadeNames.length; i++) {
            RadioButton rb = radio(shadeNames[i], 3000 + i);
            sg.addView(rb);
            if (shadeVals[i] == curShade) rb.setChecked(true);
        }
        sg.setOnCheckedChangeListener((g, id) -> Eink.putSecure(this, "b1051c_shade_theme", shadeVals[id - 3000]));
        root.addView(sg);
        if (curShade == 1 || curShade == 2) note("The shade is forced " + (curShade == 1 ? "light" : "dark")
                + " (set over adb). Choose an option above to follow the Dark theme again.");
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
        Ink.styleSeekBar(stroke, pal, pal.sliderCold);
        stroke.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean touching;
            private final Runnable apply = () -> Eink.putSecure(MainActivity.this, "b1051c_shade_stroke", stroke.getProgress());
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                strokeLabel.setText("Outline width: " + v / 10f + " dp");
                if (user && !touching) { s.removeCallbacks(apply); s.postDelayed(apply, 400); }   // keyboard / TalkBack
            }
            @Override public void onStartTrackingTouch(SeekBar s) { touching = true; s.removeCallbacks(apply); }
            @Override public void onStopTrackingTouch(SeekBar s) { touching = false; apply.run(); }
        });
        root.addView(stroke);
        note("Quick settings and notifications drawn for e-ink: solid colors, outlines, no gray shade. The two "
                + "front-light sliders replace the brightness slider in every style.");

        // Lock screen, sleep picture and shutdown picture: the Lock & Sleep app (0.15.11; the sections lived here in
        // 0.15.7–0.15.10). The plugin still reads Settings.Secure b1051c_lock_style / b1051c_lock_scrim, and the
        // shutdown picture is still drawn here (ShutdownScreen reads b1051c_shutdown_screen).
        section("Lock screen and sleep picture");
        Intent sleepApp = getPackageManager().getLaunchIntentForPackage(SleepRelay.PKG);
        note(sleepApp != null
                ? "The lock screen's look, the picture the panel keeps while the tablet sleeps and the shutdown picture are set in the Lock & Sleep app."
                : "The lock screen's look, the picture the panel keeps while the tablet sleeps and the shutdown picture are set in the Lock & Sleep app, which is not installed.");
        if (sleepApp != null) button("Open Lock & Sleep", v -> startActivity(sleepApp));

        // Dark theme (ThemeSync); replaces the "White wallpaper" button (the adb WHITE_WALLPAPER action stays)
        section("Dark theme");
        note("Follows Android's Dark theme (quick-settings tile). Switching also changes the wallpaper (home + lock "
                + "screen), the keyboard and the shutdown screen, then does one full refresh.");
        check("Wallpaper follows theme", p.getBoolean(ThemeSync.K_WALLPAPER, true), v -> {
            p.edit().putBoolean(ThemeSync.K_WALLPAPER, v).apply();
            if (v) ThemeSync.followOn(this, true);
        });
        check("Keyboard follows theme", p.getBoolean(ThemeSync.K_KEYBOARD, true), v -> {
            p.edit().putBoolean(ThemeSync.K_KEYBOARD, v).apply();
            if (v) ThemeSync.followOn(this, false);
        });
        button("Re-apply the theme now", v -> ThemeSync.request(this, "button", true));

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
                + "only its color table (like stock). Every preset also gets the color boost below.");
        IntCb shim = v -> Profiles.apply(this, AppProfileService.currentPackage(), false);
        slider("Color boost (%; grays stay neutral)", "ok_sat", 0, 150, Eink.OK_SAT, shim);
        slider("Darker text (0 = off)", "level_black", 0, 60, 0, shim);
        note("Color boost raises color in our own stage after Bigme's (60 is the measured best; more darkens skin). "
                + "Darker text turns the darkest grays black, so text edges get heavier; it costs shadow detail in "
                + "pictures, so KOReader and Kindle get 20 from their profiles and other apps stay at 0. Both are also "
                + "quick-settings tiles and per-app profile fields.");
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
        note("While scrolling with a finger:");
        RadioGroup scrollGroup = new RadioGroup(this);
        for (int i = 0; i < Profiles.SCROLL_VALUES.length; i++) scrollGroup.addView(radio(Profiles.SCROLL_NAMES[i], 6000 + i));
        scrollGroup.check(6000 + Math.max(0, Eink.stepIndex(Profiles.SCROLL_VALUES, p.getInt("scroll", 178))));
        root.addView(scrollGroup);
        scrollGroup.setOnCheckedChangeListener((g, id) -> {
            p.edit().putInt("scroll", Profiles.SCROLL_VALUES[id - 6000]).apply();
            Eink.applyRefreshMode(this);
        });
        note("Fast: HD 256 and Regal scroll in Fast, then one full refresh cleans up (like stock). Extreme: faster and "
                + "rougher, in every mode. Per app in its profile.");
        note("Navigation buttons: when only the button row changes (a button pressed), just that row is redrawn, in:");
        RadioGroup navGroup = new RadioGroup(this);
        for (int i = 0; i < Eink.NAV_MODES.length; i++) navGroup.addView(radio(Eink.NAV_NAMES[i], 6100 + i));
        navGroup.check(6100 + Math.max(0, Eink.stepIndex(Eink.NAV_MODES, p.getInt("nav_mode", 178))));
        root.addView(navGroup);
        navGroup.setOnCheckedChangeListener((g, id) -> {
            p.edit().putInt("nav_mode", Eink.NAV_MODES[id - 6100]).apply();
            Eink.applySystemModes(this);
        });
        note("Video: while a video plays, the whole screen in:");
        RadioGroup videoGroup = new RadioGroup(this);
        for (int i = 0; i < Eink.VIDEO_MODES.length; i++) videoGroup.addView(radio(Eink.VIDEO_NAMES[i], 6200 + i));
        videoGroup.check(6200 + Math.max(0, Eink.stepIndex(Eink.VIDEO_MODES, p.getInt("video_mode", 179))));
        root.addView(videoGroup);
        videoGroup.setOnCheckedChangeListener((g, id) -> {
            p.edit().putInt("video_mode", Eink.VIDEO_MODES[id - 6200]).apply();
            Eink.applySystemModes(this);
        });
        note("When the video stops, one full refresh cleans up.");
        note("Keyboard: when only the keyboard changes (a key pressed), just its area is redrawn, in:");
        RadioGroup imeGroup = new RadioGroup(this);
        for (int i = 0; i < Eink.IME_MODES.length; i++) imeGroup.addView(radio(Eink.IME_NAMES[i], 6300 + i));
        imeGroup.check(6300 + Math.max(0, Eink.stepIndex(Eink.IME_MODES, p.getInt("ime_mode", 178))));
        root.addView(imeGroup);
        imeGroup.setOnCheckedChangeListener((g, id) -> {
            p.edit().putInt("ime_mode", Eink.IME_MODES[id - 6300]).apply();
            Eink.applySystemModes(this);
        });
        note("While you swipe (gesture typing, also with the pen), the keyboard area in:");
        RadioGroup swipeGroup = new RadioGroup(this);
        for (int i = 0; i < Eink.IME_SWIPE_MODES.length; i++) swipeGroup.addView(radio(Eink.IME_SWIPE_NAMES[i], 6400 + i));
        swipeGroup.check(6400 + Math.max(0, Eink.stepIndex(Eink.IME_SWIPE_MODES, p.getInt("ime_swipe_mode", 16))));
        root.addView(swipeGroup);
        swipeGroup.setOnCheckedChangeListener((g, id) -> {
            p.edit().putInt("ime_swipe_mode", Eink.IME_SWIPE_MODES[id - 6400]).apply();
            Eink.applySystemModes(this);
        });
        check("Clean the keyboard area after a pause in typing (one quality refresh of that area)", p.getBoolean("ime_clean", true), v -> {
            p.edit().putBoolean("ime_clean", v).apply();
            Eink.applySystemModes(this);
        });
        check("Ignore touch while the pen is near (apps in Pen mode)", p.getBoolean("pen_palm", true), v -> {
            p.edit().putBoolean("pen_palm", v).apply();
            Root.async("setprop persist.b1051c.pen_palm " + (v ? 1 : 0));
        });
        note("Your hand can rest on the screen while you write. The \"Touch\" quick-settings tile turns touch off "
                + "until the screen goes off.");
        check("Full refresh when inverted colors switch on/off", p.getBoolean("clean_on_dark", true),
                v -> p.edit().putBoolean("clean_on_dark", v).apply());
        slider("Full refresh every N while dark: Dark theme or inverted colors (0 = as above)", "n_dark", 0, 50, 0,
                v -> Eink.applyRefreshMode(this));
        check("Bold text (all apps)", Eink.secureInt(this, "font_weight_adjustment", 0) > 0,
                v -> Eink.putSecure(this, "font_weight_adjustment", v ? 300 : 0));
        note("Bold text is global (changing it per app would restart apps). High-contrast text, grayscale and "
                + "inverted colors can be set per app below.");

        // Dual system
        section("System");
        sysStatus = note("…");
        note("Shutdown picture: " + ShutdownScreen.LABELS[ShutdownScreen.index(this)] + " (set in Lock & Sleep).");
        button("Boot the other system", v -> Ink.bodyWeight(new AlertDialog.Builder(this)
                .setMessage("Restart into the other system now?")
                .setPositiveButton("Restart", (d, w) -> Eink.bootOtherSystem())
                .setNegativeButton("Cancel", null).show()));
        button("Export all settings and profiles", v -> new Thread(() -> {
            String r = Eink.exportSettings(this);
            runOnUiThread(() -> android.widget.Toast.makeText(this, r, android.widget.Toast.LENGTH_LONG).show());
        }).start());
        button("Import settings and profiles", v -> Ink.bodyWeight(new AlertDialog.Builder(this)
                .setMessage("Replace all E-Ink Control settings with " + Eink.exportFile(this) + "?")
                .setPositiveButton("Import", (d, w) -> new Thread(() -> {
                    String r = Eink.importSettings(this);
                    runOnUiThread(() -> { android.widget.Toast.makeText(this, r, android.widget.Toast.LENGTH_LONG).show(); recreate(); });
                }).start())
                .setNegativeButton("Cancel", null).show()));
        // InKognito Setup: the first-run screen, reopened as a manual tool
        button(getString(R.string.setup_open), v -> startActivity(new Intent(this, SetupActivity.class)));
        note(getString(R.string.setup_open_note));
        // Per-app profiles
        section("Per-app profiles");
        note("Tap an app to set its own refresh mode, colors, grayscale, inverted colors, high-contrast text, "
                + "full-refresh frequency or anti-shake. Anything left on \"Inherit\" follows the settings above. "
                + "\"Home screen\" applies to the launcher, \"Notification shade\" while the shade is pulled down, "
                + "\"Recents\" to the app overview (it starts from the home screen's settings). Needs the E-Ink Control "
                + "accessibility service.");
        perAppList();

        refreshSystemStatus();
    }

    private int dpiCurrent = Eink.DPI_DEFAULT;
    private SeekBar dpiBar;
    private TextView dpiLabel;

    // The "keep?" countdown belongs to the process, not to this activity (review 2026-10-21): a Dark theme or Bold
    // switch recreates the activity (uiMode / fontWeight are not in configChanges) and the user can swipe it away, and
    // a dialog dismissed after that crashed the whole process (keeper and accessibility service included) before the
    // revert ran. Now a destroyed activity only drops its dialog, a recreated one shows it again with the time left,
    // and the revert always runs.
    private static final android.os.Handler dpiH = new android.os.Handler(android.os.Looper.getMainLooper());
    private static int dpiWant = -1, dpiPrev, dpiLeft;          // dpiWant >= 0 while a countdown runs
    private static Runnable dpiTick;
    private static MainActivity live;                            // the activity on screen, null when there is none
    private AlertDialog dpiDialog;

    private static String dpiText(int dpi) {
        return "DPI: " + dpi + (dpi == Eink.DPI_DEFAULT ? "  (default)" : "  (default " + Eink.DPI_DEFAULT + ")");
    }

    private static String dpiMessage() { return "Going back to " + dpiPrev + " dpi in " + dpiLeft + " s"; }

    /** Apply a density with a 15 s "keep?" countdown; anything but Keep restores the previous value. */
    private void applyDpi(int want) {
        int prev = dpiWant >= 0 ? dpiPrev : dpiCurrent;          // a second choice mid-countdown still goes back to the original
        dpiCancel();
        if (want == prev) {
            if (dpiCurrent != prev) Eink.setDpi(prev);           // back to the original before Keep: a plain revert
            dpiCurrent = prev;
            dpiLabel.setText(dpiText(prev));
            dpiBar.setProgress(prev / 10);
            return;
        }
        Eink.setDpi(want);
        dpiWant = want; dpiPrev = prev; dpiLeft = 15;
        dpiTick = new Runnable() {
            @Override public void run() {
                if (dpiWant < 0) return;
                if (--dpiLeft <= 0) { dpiRevert(); return; }
                MainActivity a = live;
                if (a != null && a.dpiDialog != null && a.dpiDialog.isShowing()) a.dpiDialog.setMessage(dpiMessage());
                dpiH.postDelayed(this, 1000);
            }
        };
        dpiH.postDelayed(dpiTick, 1000);
        showDpiDialog();
    }

    private void showDpiDialog() {
        dismissDpiDialog();
        dpiDialog = new AlertDialog.Builder(this)
                .setTitle("Keep " + dpiWant + " dpi?")
                .setMessage(dpiMessage())
                .setPositiveButton("Keep", (x, w) -> dpiKeep())
                .setNegativeButton("Go back", (x, w) -> dpiRevert())
                .setCancelable(false)
                .create();
        dpiDialog.show();
        Ink.bodyWeight(dpiDialog);                               // the countdown's setMessage keeps this TextView
    }

    private void dismissDpiDialog() {
        AlertDialog d = dpiDialog;
        dpiDialog = null;
        if (d == null || !d.isShowing()) return;
        try { d.dismiss(); } catch (RuntimeException ignored) {} // its window is already gone
    }

    /** Stop the countdown; the live activity's dialog goes with it. */
    private static void dpiCancel() {
        dpiWant = -1;
        if (dpiTick != null) dpiH.removeCallbacks(dpiTick);
        dpiTick = null;
        MainActivity a = live;
        if (a != null) a.dismissDpiDialog();
    }

    private static void dpiKeep() {
        int want = dpiWant;
        if (want < 0) return;
        dpiCancel();
        MainActivity a = live;
        if (a != null) { a.dpiCurrent = want; a.dpiLabel.setText(dpiText(want)); }
    }

    private static void dpiRevert() {
        int prev = dpiPrev;
        dpiCancel();
        Eink.setDpi(prev);
        MainActivity a = live;
        if (a != null) { a.dpiCurrent = prev; a.dpiBar.setProgress(prev / 10); a.dpiLabel.setText(dpiText(prev)); }
    }

    @Override
    protected void onDestroy() {
        dismissDpiDialog();                                      // the countdown itself keeps running (see applyDpi)
        if (live == this) live = null;
        super.onDestroy();
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
        appRow("Notification shade and quick settings", Profiles.SHADE);
        appRow("Recents", Profiles.RECENTS);
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
        b.setTextColor(pal.ink);
        b.setTypeface(Ink.font(this, 400));
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setBackground(Ink.pill(this, pal, pal.card, 14));
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
        s.setSpan(new android.text.style.ForegroundColorSpan(pal.muted), at, s.length(), 0);
        s.setSpan(new android.text.style.RelativeSizeSpan(0.85f), at, s.length(), 0);
        // the gray summary keeps the notes' weight (Ink.mutedFont) when the row's body weight differs (dark only)
        Typeface mf = Ink.mutedFont(this);
        if (mf != Ink.font(this, 400)) s.setSpan(new android.text.style.TypefaceSpan(mf), at, s.length(), 0);
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
        android.widget.Spinner invert = spinner(box, "Inverted colors (skipped while Dark theme is on)", TRI,
                tri(o, Profiles.F_INVERT));
        android.widget.Spinner hc = spinner(box, "High-contrast text", TRI, tri(o, Profiles.F_HC));
        String[] nOpts = new String[NS.length + 1];
        nOpts[0] = "Inherit";
        for (int i = 0; i < NS.length; i++) nOpts[i + 1] = NS[i] == 0 ? "Off" : "Every " + NS[i];
        int ni = 0;
        for (int i = 0; i < NS.length; i++) if (o.has(Profiles.F_N) && NS[i] == o.optInt(Profiles.F_N)) ni = i + 1;
        android.widget.Spinner n = spinner(box, "Full refresh every N page turns", nOpts, ni);
        android.widget.Spinner shake = spinner(box, "Anti-shake", TRI, tri(o, Profiles.F_SHAKE));
        int[] satSteps = steps(Eink.SAT_VALUES, o, Profiles.F_SAT), blackSteps = steps(Eink.TEXT_VALUES, o, Profiles.F_BLACK);
        android.widget.Spinner sat = spinner(box, "Color boost", stepNames(satSteps, Eink.SAT_VALUES, Eink.SAT_NAMES, " %"),
                stepSel(satSteps, o, Profiles.F_SAT));
        android.widget.Spinner black = spinner(box, "Darker text", stepNames(blackSteps, Eink.TEXT_VALUES, Eink.TEXT_NAMES, ""),
                stepSel(blackSteps, o, Profiles.F_BLACK));
        String[] scrollOpts = new String[Profiles.SCROLL_NAMES.length + 1];
        scrollOpts[0] = "Inherit";
        System.arraycopy(Profiles.SCROLL_NAMES, 0, scrollOpts, 1, Profiles.SCROLL_NAMES.length);
        int si = o.has(Profiles.F_SCROLL) ? 1 + Math.max(0, Eink.stepIndex(Profiles.SCROLL_VALUES, o.optInt(Profiles.F_SCROLL))) : 0;
        android.widget.Spinner scroll = spinner(box, "While scrolling with a finger", scrollOpts, si);
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
                        if (sat.getSelectedItemPosition() > 0) x.put(Profiles.F_SAT, satSteps[sat.getSelectedItemPosition() - 1]);
                        if (black.getSelectedItemPosition() > 0) x.put(Profiles.F_BLACK, blackSteps[black.getSelectedItemPosition() - 1]);
                        if (scroll.getSelectedItemPosition() > 0) x.put(Profiles.F_SCROLL, Profiles.SCROLL_VALUES[scroll.getSelectedItemPosition() - 1]);
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

    /** The editor's steps for an int field, plus the profile's own value when it is not one of them (set by an
     *  import or an older build), so opening and saving never changes it. */
    private static int[] steps(int[] base, org.json.JSONObject o, String f) {
        if (!o.has(f) || Eink.stepIndex(base, o.optInt(f)) >= 0) return base;
        int[] s = java.util.Arrays.copyOf(base, base.length + 1);
        s[base.length] = o.optInt(f);
        return s;
    }

    private static String[] stepNames(int[] steps, int[] base, String[] names, String unit) {
        String[] out = new String[steps.length + 1];
        out[0] = "Inherit";
        for (int i = 0; i < steps.length; i++) out[i + 1] = i < base.length ? names[i] : steps[i] + unit;
        return out;
    }

    private static int stepSel(int[] steps, org.json.JSONObject o, String f) {
        return o.has(f) ? 1 + Eink.stepIndex(steps, o.optInt(f)) : 0;
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
        t.setTextColor(pal.ink);
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

    /** Board 05 identity: the title in the wordmark face, "In" + "Kognito" in the logo's steel + bordeaux (dark, card
     *  E4a: "InK" light gold + "ognito" lifted bordeaux, cream title; the split is R.integer.brand_split). */
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
        tv.setTypeface(Ink.mutedFont(this));                     // T3 gray: muted_weight (light: the same Regular)
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

    private RadioButton radio(String t, int id) {
        RadioButton rb = new RadioButton(this);
        rb.setText(t);
        rb.setTextSize(18);
        rb.setTextColor(pal.ink);
        rb.setTypeface(Ink.font(this, 400));
        rb.setButtonTintList(Ink.tint(pal.controlOn));
        rb.setId(id);
        rb.setPadding(dp(8), dp(10), dp(8), dp(10));
        return rb;
    }

    private interface IntCb { void on(int v); }
    private interface BoolCb { void on(boolean v); }

    /** On/off setting: text left, badge toggle right (teal track + light-gold knob when on; dark: Ink.switchTrack). */
    private android.widget.Switch check(String t, boolean val, BoolCb cb) {
        android.widget.Switch c = new android.widget.Switch(this);
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
        return slider(label, key, min, max, def, pal.sliderCold, cb);
    }

    /** Labelled badge slider; {@code fill} = steel, or gold for the warm light (as in the shade). */
    private SeekBar slider(String label, String key, int min, int max, int def, int fill, IntCb cb) {
        TextView tv = text(label + ": " + p.getInt(key, def), 17, false);
        SeekBar sb = new SeekBar(this);
        sb.setMin(min);
        sb.setMax(max);
        sb.setProgress(p.getInt(key, def));
        Ink.styleSeekBar(sb, pal, fill);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            // touch: apply on release (one e-ink refresh, not dozens). Keyboard, TalkBack and Switch Access move the
            // bar without touch events (review 2026-10-21): then apply 400 ms after the last change.
            private boolean touching;
            private final Runnable apply = () -> { p.edit().putInt(key, sb.getProgress()).apply(); cb.on(sb.getProgress()); };
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) {
                tv.setText(label + ": " + v);
                if (user && !touching) { s.removeCallbacks(apply); s.postDelayed(apply, 400); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { touching = true; s.removeCallbacks(apply); }
            @Override public void onStopTrackingTouch(SeekBar s) { touching = false; apply.run(); }
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
