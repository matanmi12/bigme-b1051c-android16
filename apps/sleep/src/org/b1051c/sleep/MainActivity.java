package org.b1051c.sleep;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Lock & Sleep: everything the panel shows while the tablet is locked or asleep. Single screen in the InKognito look
 * (helpers copied from E-Ink Control; every helper adds its own view, never root.addView() their result).
 *  - Lock screen: the SystemUI plugin reads Settings.Secure b1051c_lock_style / b1051c_lock_scrim.
 *  - Sleep picture: rendered by Renderer into the raw frame the HWC shim commits at panel power-off.
 *  - Shutdown: E-Ink Control draws the picture; the choice is Settings.Secure b1051c_shutdown_screen.
 *  - Power while asleep (0.2.0): Wi-Fi off after N minutes, power-off after N hours (Power).
 *  - Settings file (0.2.0): export / import (Backup).
 */
public class MainActivity extends Activity {
    private static final int REQ_PICK = 11, REQ_PERM = 12, REQ_FOLDER = 13;
    private static final String EINKCTL = "org.b1051c.einkctl";
    private static final String[] SHUTDOWN_VALUES = {"anim_bw", "logo_cream", "black", "off"};
    private static final String[] SHUTDOWN_LABELS = {"InKognito: color to black-and-white (animation)", "InKognito logo (color)",
            "Black", "Off (keep the last screen)"};

    private Prefs prefs;
    private Ink.Pal pal;
    private LinearLayout root;
    private TextView status, sourceLine, folderLine, coverLine, powerLine, restrictLine;
    private LinearLayout pictureBox, folderBox, coverBox;

    // No uiMode in the manifest configChanges: Android's Dark theme recreates the screen with the night palette.
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
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

        header("Lock & Sleep");
        note("What the panel shows while the tablet is locked or asleep. E-ink keeps its last picture without power, "
                + "so the sleep picture costs no battery.");

        // ---- Lock screen (SystemUI plugin 0.4.3+): moved here from E-Ink Control 0.15.10 ----------------------
        section("Lock screen");
        RadioGroup lg = new RadioGroup(this);
        String[] lockNames = {"InKognito: display clock, date line, no corner shortcut", "Android's lock screen"};
        int[] lockVals = {1, 0};
        int curLock = Secure.getInt(this, Secure.LOCK_STYLE, 1);
        for (int i = 0; i < lockNames.length; i++) {
            RadioButton rb = radio(lockNames[i], 3100 + i);
            lg.addView(rb);
            if (lockVals[i] == curLock) rb.setChecked(true);
        }
        lg.setOnCheckedChangeListener((g, id) -> Secure.putInt(this, Secure.LOCK_STYLE, lockVals[id - 3100]));
        root.addView(lg);
        text("Over the wallpaper", 17, false);
        RadioGroup sc = new RadioGroup(this);
        String[] scrimNames = {"Nothing (the wallpaper as it is)", "The page color (cream, or black in the Dark theme)", "Android's 20% gray shade"};
        int[] scrimVals = {1, 2, 0};
        int curScrim = Secure.getInt(this, Secure.LOCK_SCRIM, 1);
        for (int i = 0; i < scrimNames.length; i++) {
            RadioButton rb = radio(scrimNames[i], 3200 + i);
            sc.addView(rb);
            if (scrimVals[i] == curScrim) rb.setChecked(true);
        }
        sc.setOnCheckedChangeListener((g, id) -> Secure.putInt(this, Secure.LOCK_SCRIM, scrimVals[id - 3200]));
        root.addView(sc);
        check("Battery level after the date", Secure.getInt(this, Secure.LOCK_BATTERY, 1) == 1,
                v -> Secure.putInt(this, Secure.LOCK_BATTERY, v ? 1 : 0));
        note("The lock screen itself stays Android's: swipe or PIN, notifications, the lock icon. Only its look changes.");

        // ---- Sleep picture ------------------------------------------------------------------------------------
        section("Sleep picture");
        note("Shown on the panel from the moment the screen goes to sleep until it wakes. It is drawn ahead of time, "
                + "so falling asleep is not delayed.");
        RadioGroup mg = new RadioGroup(this);
        String[] modeNames = {"Nothing (the last screen stays)", "One picture", "Pictures from a folder, in turn",
                "The cover of the book open in KOReader"};
        String[] modeVals = {Prefs.MODE_OFF, Prefs.MODE_PICTURE, Prefs.MODE_FOLDER, Prefs.MODE_COVER};
        String curMode = prefs.mode();
        for (int i = 0; i < modeNames.length; i++) {
            RadioButton rb = radio(modeNames[i], 4100 + i);
            mg.addView(rb);
            if (modeVals[i].equals(curMode)) rb.setChecked(true);
        }
        root.addView(mg);

        // One picture: built-in badge or the user's file
        pictureBox = new LinearLayout(this);
        pictureBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(pictureBox);
        LinearLayout saved = root;
        root = pictureBox;                                       // helpers add into the box
        RadioGroup sg = new RadioGroup(this);
        String[] srcNames = {"The InKognito badge (cream, or black in the Dark theme)", "My picture"};
        String[] srcVals = {Prefs.SRC_BUILTIN, Prefs.SRC_FILE};
        String curSrc = prefs.source();
        for (int i = 0; i < srcNames.length; i++) {
            RadioButton rb = radio(srcNames[i], 4200 + i);
            sg.addView(rb);
            if (srcVals[i].equals(curSrc)) rb.setChecked(true);
        }
        sg.setOnCheckedChangeListener((g, id) -> {
            prefs.p.edit().putString(Prefs.SOURCE, srcVals[id - 4200]).apply();
            if (id == 4201 && prefs.file().isEmpty()) pick(); else rerender();
            showSource();
        });
        root.addView(sg);
        sourceLine = note("…");
        button("Pick an image…", v -> pick());
        root = saved;

        // Folder rotation
        folderBox = new LinearLayout(this);
        folderBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(folderBox);
        root = folderBox;
        folderLine = note("…");
        button("Choose folder…", v -> pickFolder());
        button("Back to the default folder", v -> useFolder(Prefs.FOLDER_DEFAULT));
        button("Allow photo access", v -> requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_IMAGES}, REQ_PERM));
        button("Next picture now", v -> {
            Renderer.request(this, "next-button", true);
            refreshStatusSoon();
        });
        root = saved;

        // Book cover: KOReader's patch exports the cover of each book it opens
        coverBox = new LinearLayout(this);
        coverBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(coverBox);
        root = coverBox;
        coverLine = note("…");
        root = saved;

        mg.setOnCheckedChangeListener((g, id) -> {
            prefs.p.edit().putString(Prefs.MODE, modeVals[id - 4100]).apply();
            showMode();
            rerender();
        });

        text("How the picture fills the page", 17, false);
        RadioGroup fg = new RadioGroup(this);
        String[] scaleNames = {"Fit (whole picture, page color around it)", "Fill (crop to the page)"};
        String[] scaleVals = {Prefs.SCALE_FIT, Prefs.SCALE_FILL};
        String curScale = prefs.scale();
        for (int i = 0; i < scaleNames.length; i++) {
            RadioButton rb = radio(scaleNames[i], 4300 + i);
            fg.addView(rb);
            if (scaleVals[i].equals(curScale)) rb.setChecked(true);
        }
        fg.setOnCheckedChangeListener((g, id) -> {
            prefs.p.edit().putString(Prefs.SCALE, scaleVals[id - 4300]).apply();
            rerender();
        });
        root.addView(fg);

        // ---- Overlays -----------------------------------------------------------------------------------------
        section("On the picture");
        check("Date", prefs.showDate(), v -> { prefs.p.edit().putBoolean(Prefs.SHOW_DATE, v).apply(); rerender(); });
        check("Battery", prefs.showBattery(), v -> { prefs.p.edit().putBoolean(Prefs.SHOW_BATTERY, v).apply(); rerender(); });
        check("Time it fell asleep", prefs.showTime(), v -> { prefs.p.edit().putBoolean(Prefs.SHOW_TIME, v).apply(); rerender(); });
        note("Top-left, where the lock screen's clock sits. The date and the time are when the tablet fell asleep: "
                + "a sleeping panel cannot be redrawn, so they do not move on overnight.");

        // ---- Preview + status ---------------------------------------------------------------------------------
        section("Preview");
        button("Preview the sleep picture", true, v -> preview());
        button("Render now", v -> {
            Renderer.request(this, "render-button");
            refreshStatusSoon();
        });
        status = note("…");

        // ---- Shutdown picture (E-Ink Control draws it; the choice lives here since 0.15.11) ---------------------
        section("Shutdown");
        String cur = Secure.getString(this, Secure.SHUTDOWN);
        if ("logo_white".equals(cur)) cur = "logo_cream";
        int sel = 0;
        for (int i = 0; i < SHUTDOWN_VALUES.length; i++) if (SHUTDOWN_VALUES[i].equals(cur)) sel = i;
        android.widget.Spinner sd = spinner(root, "Screen left on the panel after power-off / during a reboot", SHUTDOWN_LABELS, sel);
        sd.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private boolean first = true;
            @Override public void onItemSelected(android.widget.AdapterView<?> a, View v, int pos, long id) {
                if (first) { first = false; return; }           // the initial selection is not a change
                Secure.putString(MainActivity.this, Secure.SHUTDOWN, SHUTDOWN_VALUES[pos]);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> a) {}
        });
        note("Drawn by E-Ink Control when the tablet shuts down or reboots. Follows the Dark theme (black around the drawing).");

        // ---- Power while asleep (ROADMAP 22) ------------------------------------------------------------------
        section("Power while asleep");
        int[] wifiVals = {0, 5, 15, 30, 60};
        String[] wifiNames = {"Keep Wi-Fi on", "Off after 5 minutes", "Off after 15 minutes", "Off after 30 minutes", "Off after an hour"};
        android.widget.Spinner ws = spinner(root, "Wi-Fi", wifiNames, indexOf(wifiVals, prefs.wifiSleepMin(), 2));
        onPick(ws, pos -> prefs.p.edit().putInt(Prefs.WIFI_SLEEP_MIN, wifiVals[pos]).apply());
        note("Wi-Fi comes back on when the tablet wakes. A Wi-Fi you switched off yourself stays off.");
        int[] offVals = {0, 6, 12, 24, 48, 72};
        String[] offNames = {"Never", "After 6 hours asleep", "After 12 hours asleep", "After a day asleep", "After 2 days asleep", "After 3 days asleep"};
        android.widget.Spinner ps = spinner(root, "Power off", offNames, indexOf(offVals, prefs.powerOffHours(), 0));
        onPick(ps, pos -> prefs.p.edit().putInt(Prefs.POWER_OFF_H, offVals[pos]).apply());
        note("To power off, the screen wakes for a moment with the light off, so the shutdown picture reaches the panel. "
                + "Starting again takes a long press of the power button.");
        powerLine = note("…");

        // ---- Apps kept out of the background (ROADMAP 19) -----------------------------------------------------
        section("Apps in the background");
        note("Apps on this list still open and work as usual, but do nothing while they are not on screen: no syncing, "
                + "no alarms, no background services. It is Android's own \"Restricted\" battery setting, for several apps at once.");
        button("Choose apps…", v -> chooseRestricted());
        restrictLine = note("…");
        showRestricted();

        // ---- Settings file ------------------------------------------------------------------------------------
        section("Settings file");
        button("Export the settings", v -> new Thread(() -> {
            String r = Backup.exportSettings(this);
            runOnUiThread(() -> android.widget.Toast.makeText(this, r, android.widget.Toast.LENGTH_LONG).show());
        }).start());
        button("Import the settings", v -> Ink.bodyWeight(new AlertDialog.Builder(this)
                .setMessage("Replace the Lock & Sleep settings with " + Backup.exportFile(this) + "?")
                .setPositiveButton("Import", (d, w) -> new Thread(() -> {
                    String r = Backup.importSettings(this);
                    runOnUiThread(() -> { android.widget.Toast.makeText(this, r, android.widget.Toast.LENGTH_LONG).show(); recreate(); });
                }).start())
                .setNegativeButton("Cancel", null).show()));
        note("The choices on this screen, in " + Backup.exportFile(this) + ". A picked picture is not part of it.");

        showMode();
        showSource();
        showFolder();
        showStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showStatus();
        showFolder();
    }

    private void rerender() {
        Renderer.request(this, "settings");
        refreshStatusSoon();
    }

    private void refreshStatusSoon() {
        root.postDelayed(() -> { if (!isDestroyed()) showStatus(); }, 2500);
    }

    private void showMode() {
        String m = prefs.mode();
        pictureBox.setVisibility(Prefs.MODE_PICTURE.equals(m) ? View.VISIBLE : View.GONE);
        folderBox.setVisibility(Prefs.MODE_FOLDER.equals(m) ? View.VISIBLE : View.GONE);
        coverBox.setVisibility(Prefs.MODE_COVER.equals(m) ? View.VISIBLE : View.GONE);
        showCover();
    }

    private void showCover() {
        String title = prefs.p.getString(Prefs.S_COVER_TITLE, "");
        long at = prefs.p.getLong(Prefs.S_COVER_AT, 0);
        boolean have = Cover.file(this).isFile();
        coverLine.setText((have ? "Cover: " + (title.isEmpty() ? "(no title)" : title) + ", from "
                + android.text.format.DateUtils.getRelativeTimeSpanString(at) + "."
                : "No cover yet: open a book in KOReader. Until then the badge is shown.")
                + "\nKOReader sends the cover of each book it opens (its patch 3-inkognito-cover.lua). The last book's cover stays.");
    }

    private void showRestricted() {
        java.util.Set<String> l = Restrict.list(this);
        if (l.isEmpty()) { restrictLine.setText("No apps on the list."); return; }
        StringBuilder sb = new StringBuilder("On the list: ");
        android.content.pm.PackageManager pm = getPackageManager();
        boolean first = true;
        for (String p : l) {
            String label;
            try { label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(p, 0))); }
            catch (PackageManager.NameNotFoundException e) { label = p + " (not installed)"; }
            sb.append(first ? "" : ", ").append(label);
            first = false;
        }
        restrictLine.setText(sb.append('.'));
    }

    private void chooseRestricted() {
        java.util.List<Restrict.App> apps = Restrict.candidates(this);
        java.util.Set<String> cur = Restrict.list(this);
        String[] labels = new String[apps.size()];
        boolean[] on = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) { labels[i] = apps.get(i).label; on[i] = cur.contains(apps.get(i).pkg); }
        Ink.bodyWeight(new AlertDialog.Builder(this).setTitle("Keep out of the background")
                .setMultiChoiceItems(labels, on, (d, w, checked) -> on[w] = checked)
                .setPositiveButton("Save", (d, w) -> {
                    java.util.Set<String> now = new java.util.TreeSet<>();
                    for (String p : cur) {                          // apps not offered now (uninstalled) stay as they are
                        boolean offered = false;
                        for (Restrict.App a : apps) if (a.pkg.equals(p)) offered = true;
                        if (!offered) now.add(p);
                    }
                    for (int i = 0; i < apps.size(); i++) if (on[i]) now.add(apps.get(i).pkg);
                    new Thread(() -> {
                        String r = Restrict.save(this, now);
                        runOnUiThread(() -> {
                            if (isDestroyed()) return;
                            android.widget.Toast.makeText(this, r, android.widget.Toast.LENGTH_LONG).show();
                            showRestricted();
                            showPower();
                        });
                    }).start();
                })
                .setNegativeButton("Cancel", null).create()).show();
    }

    private interface PosCb { void on(int pos); }

    private void onPick(android.widget.Spinner s, PosCb cb) {
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private boolean first = true;
            @Override public void onItemSelected(android.widget.AdapterView<?> a, View v, int pos, long id) {
                if (first) { first = false; return; }           // the initial selection is not a change
                cb.on(pos);
                showPower();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> a) {}
        });
    }

    private static int indexOf(int[] vals, int v, int def) {
        for (int i = 0; i < vals.length; i++) if (vals[i] == v) return i;
        return def;
    }

    private void showPower() {
        String log = prefs.p.getString(Prefs.S_POWER_LOG, "");
        powerLine.setText((log.isEmpty() ? "Nothing done yet." : "Last actions:\n" + log)
                + (relayCode() >= 71 ? "" : "\nNeeds E-Ink Control 0.15.21 or newer (it tells this app when the screen goes off)."));
    }

    private void showSource() {
        String f = prefs.file();
        sourceLine.setText(Prefs.SRC_FILE.equals(prefs.source())
                ? (f.isEmpty() ? "No picture picked yet." : "Picture: " + f)
                : "The built-in picture. Pick an image to use your own (it is copied into the app, any size).");
    }

    private void showFolder() {
        boolean granted = checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        int n = Pictures.folderPictures(prefs.folder()).size();
        String last = prefs.p.getString(Prefs.S_FOLDER_LAST, "");
        folderLine.setText("Folder: " + prefs.folder() + "\n" + n + (n == 1 ? " picture" : " pictures") + " (png, jpg, webp, bmp), by name. "
                + "Each wake moves to the next one." + (last.isEmpty() ? "" : " Now: " + last)
                + (granted ? "" : "\nPhoto access is off: pictures in shared folders are not visible."));
    }

    private void showStatus() {
        StringBuilder s = new StringBuilder();
        int seq = prefs.p.getInt(Prefs.S_STANDBY, 0);
        long at = prefs.p.getLong(Prefs.S_RENDERED_AT, 0);
        String err = prefs.p.getString(Prefs.S_LAST_ERROR, "");
        if (Prefs.MODE_OFF.equals(prefs.mode())) s.append("Sleep picture off.");
        else if (seq <= 0 || at == 0) s.append("Not rendered yet.");
        else s.append("Frame #").append(seq).append(": ").append(prefs.p.getString(Prefs.S_LAST_FILE, "")).append(", rendered ")
                .append(android.text.format.DateUtils.getRelativeTimeSpanString(at)).append(" in ")
                .append(prefs.p.getLong(Prefs.S_LAST_MS, 0)).append(" ms.");
        if (!err.isEmpty()) s.append("\nLast error: ").append(err);
        String clock = prefs.p.getString(Prefs.S_CLOCK, "");
        if (clock.startsWith("needs")) s.append("\nTime it fell asleep: ").append(clock).append(" (the display module draws it).");
        s.append("\nE-Ink Control relay: ").append(relay());
        status.setText(s);
        showPower();
        showCover();
    }

    /** E-Ink Control 0.15.11 (build 61) relays wake, battery, date and theme changes to this app; 0.15.21 (71) also
     *  the screen going off, for the power section. */
    private String relay() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(EINKCTL, 0);
            long vc = pi.getLongVersionCode();
            return vc >= 61 ? "installed (" + pi.versionName + ")" : "E-Ink Control " + pi.versionName + " is too old (0.15.11+ needed)";
        } catch (PackageManager.NameNotFoundException e) {
            return "E-Ink Control is missing; the picture only updates when this app runs";
        }
    }

    private long relayCode() {
        try { return getPackageManager().getPackageInfo(EINKCTL, 0).getLongVersionCode(); }
        catch (PackageManager.NameNotFoundException e) { return 0; }
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try { startActivityForResult(i, REQ_PICK); }
        catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "No file picker on this system", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** Android's own folder picker, opened at the current folder. */
    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        android.net.Uri start = Pictures.pathToDocument(prefs.folder());
        if (start != null) i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, start);
        try { startActivityForResult(i, REQ_FOLDER); }
        catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "No folder picker on this system", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void useFolder(String f) {
        prefs.p.edit().putString(Prefs.FOLDER, f).putInt(Prefs.S_FOLDER_INDEX, 0).apply();
        showFolder();
        rerender();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FOLDER) {
            if (res != RESULT_OK || data == null || data.getData() == null) return;
            String path = Pictures.treePath(data.getData());
            if (path == null) {
                android.widget.Toast.makeText(this, "Pick a folder on the tablet or the SD card", android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            useFolder(path);
            return;
        }
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) { showSource(); return; }
        android.net.Uri uri = data.getData();
        new Thread(() -> {
            String msg;
            try {
                String name = Pictures.importUri(this, uri);
                prefs.p.edit().putString(Prefs.SOURCE, Prefs.SRC_FILE).putString(Prefs.FILE, name).apply();
                Renderer.request(this, "picked");
                msg = "Picture: " + name;
            } catch (Exception e) {
                msg = "Could not read the picture: " + e.getMessage();
            }
            String m = msg;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                showSource();
                android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show();
                refreshStatusSoon();
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERM) { showFolder(); rerender(); }
    }

    private void preview() {
        android.widget.Toast.makeText(this, "Drawing…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Bitmap b;
            try { b = Renderer.preview(this, 0.3f); }
            catch (Throwable t) { b = null; android.util.Log.w(Renderer.TAG, "preview", t); }
            Bitmap bm = b;
            runOnUiThread(() -> {
                if (isDestroyed()) { if (bm != null) bm.recycle(); return; }
                if (bm == null) { android.widget.Toast.makeText(this, "Preview failed (see logcat)", android.widget.Toast.LENGTH_LONG).show(); return; }
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bm);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setBackground(Ink.shape(this, pal.card, 4, pal.line));
                int p = dp(12);
                iv.setPadding(p, p, p, p);
                AlertDialog d = new AlertDialog.Builder(this).setTitle("Sleep picture").setView(iv)
                        .setPositiveButton("Close", null).create();
                d.setOnDismissListener(x -> bm.recycle());
                Ink.bodyWeight(d).show();
            });
        }).start();
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

    // ---- tiny UI helpers (copied from E-Ink Control; each adds its own view) ------------------------------------
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

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

    private interface BoolCb { void on(boolean v); }

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

    private void button(String t, View.OnClickListener l) { button(t, false, l); }

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
