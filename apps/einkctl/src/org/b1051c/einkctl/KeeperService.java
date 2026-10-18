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
import android.os.IBinder;

/** Re-applies the saved settings after boot and keeps the frontlight off while the screen sleeps. */
public class KeeperService extends Service {
    private static final String CH = "keeper";
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) Eink.frontlightOff();
            else Eink.applyFrontlight(c);
        }
    };

    /** ACTION_SHUTDOWN is not exempt from the implicit-broadcast limits, so it is registered here, not in the manifest. */
    private final BroadcastReceiver shutdown = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            PendingResult r = goAsync();
            ShutdownScreen.show(c, r::finish);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "E-Ink Control", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat_eink)
                .setContentTitle("E-Ink Control active")
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screen, f);
        registerReceiver(shutdown, new IntentFilter(Intent.ACTION_SHUTDOWN));
        getContentResolver().registerContentObserver(
                android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS),
                false, brightness);
        getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(Eink.KEY_COLD), false, qsLight);
        getContentResolver().registerContentObserver(android.provider.Settings.Secure.getUriFor(Eink.KEY_WARM), false, qsLight);
        Profiles.seed(this);
        Eink.applyAll(this);
        // started while asleep (app update, late boot broadcast): applyAll lit the panel — keep it dark until SCREEN_ON
        if (!getSystemService(android.os.PowerManager.class).isInteractive()) Eink.frontlightOff();
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

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        unregisterReceiver(screen);
        unregisterReceiver(shutdown);
        getContentResolver().unregisterContentObserver(brightness);
        getContentResolver().unregisterContentObserver(qsLight);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
