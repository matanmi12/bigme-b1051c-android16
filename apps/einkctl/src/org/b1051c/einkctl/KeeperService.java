package org.b1051c.einkctl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.IBinder;

/** Re-applies the saved settings after boot and keeps the frontlight off while the screen sleeps; brings the
 *  wallpaper, keyboard and boot-animation flag along when Android's Dark theme switches (ThemeSync); turns Android's
 *  Night Light into our night mode (NightMode). */
public class KeeperService extends Service {
    // "keeper2": the first channel showed a launcher notification dot on the app icon (owner 2026-10-19); a channel's
    // badge flag can't be changed after creation, so the old one is deleted and this one is made without a badge.
    private static final String CH = "keeper2", OLD_CH = "keeper";
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) { Eink.frontlightOff(); TileTouch.release(c); }
            else { Eink.applyFrontlight(c); checkPlugin(c, "screen on"); SleepRelay.send(c, SleepRelay.WAKE); }
        }
    };
    /** Battery and clock changes for the sleep picture (Lock & Sleep draws the level and the date on it). */
    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { SleepRelay.battery(c, i); }
    };
    private final BroadcastReceiver clock = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { SleepRelay.send(c, SleepRelay.DATE); }
    };

    /** Start the keeper, or keep it running. Android 12+ may refuse a foreground-service start from a background
     *  context (ForegroundServiceStartNotAllowedException, an IllegalStateException); root is exempt from that rule. */
    static void ensure(Context c) {
        try {
            c.startForegroundService(new Intent(c, KeeperService.class));
        } catch (IllegalStateException e) {
            android.util.Log.w("einkctl", "keeper start refused here, starting it as root: " + e);
            Root.async("am start-foreground-service -n " + c.getPackageName() + "/.KeeperService");
        }
    }

    // ---- E-Ink SystemUI plugin guard (review 2026-10-21) --------------------------------------------
    // SystemUI's PluginManagerImpl.PluginExceptionHandler disables every non-privileged plugin after any SystemUI crash
    // whose stack shows no plugin class (PluginActionManager.disableAll -> PluginEnabler.setDisabled, persisted in the
    // PackageManager); only a PACKAGE_REPLACED of the plugin re-enables it. Checked at every keeper start and SCREEN_ON
    // (one PackageManager call); re-enabled at most once per boot, so a plugin that really crashes SystemUI cannot
    // cause a crash loop. SystemUI reloads the plugin on the PACKAGE_CHANGED that `pm enable` sends, no restart needed.
    static final android.content.ComponentName PLUGIN =
            new android.content.ComponentName("org.b1051c.einkui", "org.b1051c.einkui.EinkOverlayPlugin");
    static final String PLUGIN_BOOT = ThemeSync.STATE + "plugin_boot";   // device state: never exported

    static void checkPlugin(Context c, String why) {
        int st;
        try {
            st = c.getPackageManager().getComponentEnabledSetting(PLUGIN);
        } catch (IllegalArgumentException e) {
            return;                                                      // einkui is not installed
        }
        if (st != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return;
        long boot = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
        android.content.SharedPreferences p = Eink.prefs(c);
        if (Math.abs(p.getLong(PLUGIN_BOOT, 0) - boot) < 60_000) {
            android.util.Log.w("einkctl", "SystemUI plugin is disabled again this boot (" + why + "): leaving it off");
            return;
        }
        p.edit().putLong(PLUGIN_BOOT, boot).apply();
        Root.async("pm enable " + PLUGIN.flattenToString(), r -> android.util.Log.w("einkctl",
                "SystemUI plugin was disabled (a SystemUI crash?); re-enabled (" + why + "): " + r));
    }

    /** ACTION_SHUTDOWN is not exempt from the implicit-broadcast limits, so it is registered here, not in the manifest.
     *  First the sleep picture is disarmed (synchronously): the display shim must not put it on the panel when
     *  SurfaceFlinger turns the display off at the end of the shutdown, over the shutdown picture drawn here. */
    private final BroadcastReceiver shutdown = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            PendingResult r = goAsync();
            Root.run("setprop vendor.debug.hwc.shim.standby 0");
            ShutdownScreen.show(c, r::finish);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "E-Ink Control", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat_eink)
                .setContentTitle("E-Ink Control active")
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        nm.deleteNotificationChannel(OLD_CH);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);
        registerReceiver(shutdown, new IntentFilter(Intent.ACTION_SHUTDOWN));
        registerReceiver(battery, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        IntentFilter cf = new IntentFilter(Intent.ACTION_DATE_CHANGED);
        cf.addAction(Intent.ACTION_TIME_CHANGED);
        cf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clock, cf);
        getContentResolver().registerContentObserver(
                android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS),
                false, brightness);
        getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(Eink.KEY_COLD), false, qsLight);
        getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(Eink.KEY_WARM), false, qsLight);
        getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(NightMode.ACTIVATED), false, night);
        getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(NightMode.TEMPERATURE), false, night);
        Profiles.seed(this);
        Eink.applyAll(this);
        // started while asleep (app update, late boot broadcast): applyAll lit the panel — keep it dark until SCREEN_ON
        if (!getSystemService(android.os.PowerManager.class).isInteractive()) Eink.frontlightOff();
        ThemeSync.onStart(this);   // boot, app update, keeper restart: catch up with a switch made meanwhile
        NightMode.sync(this, "start");
        checkPlugin(this, "keeper start");
        // the accessibility service off (fresh device, or dropped by an update): one notification, tap = turn on
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (AppProfileService.running() || AppProfileService.enabledInSettings(this)) return;
            Intent fix = new Intent(this, MainActivity.class);
            Notification n2 = new Notification.Builder(this, CH)
                    .setSmallIcon(R.drawable.ic_stat_eink)
                    .setContentTitle("E-Ink Control: accessibility service is off")
                    .setContentText("Per-app profiles, the refresh button and the shutdown picture need it. Tap to turn it on.")
                    .setContentIntent(PendingIntent.getActivity(this, 2, fix, PendingIntent.FLAG_IMMUTABLE))
                    .setAutoCancel(true).build();
            getSystemService(NotificationManager.class).notify(2, n2);
        }, 20_000);
    }

    /** Services get every global configuration change; ThemeSync keeps only Dark theme switches (debounced). */
    @Override
    public void onConfigurationChanged(Configuration nc) {
        super.onConfigurationChanged(nc);
        ThemeSync.onConfig(this, nc);
    }

    private final android.database.ContentObserver brightness =
            new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
                @Override public void onChange(boolean self) {
                    if (Eink.prefs(KeeperService.this).getBoolean("link_brightness", false)) Eink.applyFrontlight(KeeperService.this);
                }
            };

    /** The QS front-light sliders (SystemUI plugin) changed the shared values. */
    private final android.database.ContentObserver qsLight =
            new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
                @Override public void onChange(boolean self) { Eink.lightFromSystemUi(KeeperService.this); }
            };

    /** Android's Night Light switch or intensity (Settings, QS tile, its schedule): our night mode. */
    private final android.database.ContentObserver night =
            new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
                @Override public void onChange(boolean self, android.net.Uri uri) {
                    NightMode.sync(KeeperService.this, uri != null && NightMode.TEMPERATURE.equals(uri.getLastPathSegment())
                            ? "intensity" : "switch");
                }
            };

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        unregisterReceiver(screen);
        unregisterReceiver(shutdown);
        unregisterReceiver(battery);
        unregisterReceiver(clock);
        getContentResolver().unregisterContentObserver(brightness);
        getContentResolver().unregisterContentObserver(qsLight);
        getContentResolver().unregisterContentObserver(night);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
