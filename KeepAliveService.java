package com.paynux;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service ala Termux: notifikasi "running" menjaga proses
 * Paynux tetap hidup di latar belakang, dengan tombol:
 *   - Acquire / Release wakelock (CPU + Wi-Fi tetap aktif saat layar mati)
 *   - Exit (menutup aplikasi dan semua sesi)
 * Wakelock default MATI (hemat baterai), dinyalakan manual seperti Termux.
 */
public class KeepAliveService extends Service {

    public static final String ACTION_WAKELOCK_ON = "com.paynux.WAKELOCK_ON";
    public static final String ACTION_WAKELOCK_OFF = "com.paynux.WAKELOCK_OFF";
    public static final String EXTRA_EXIT = "paynux_exit";

    private static final String CHANNEL = "paynux_running";
    private static final int NOTIF_ID = 1;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_WAKELOCK_ON.equals(action)) {
            acquireLocks();
        } else if (ACTION_WAKELOCK_OFF.equals(action)) {
            releaseLocks();
        }
        startForeground(NOTIF_ID, buildNotification());
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
    }

    private boolean locksHeld() {
        return wakeLock != null && wakeLock.isHeld();
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Paynux:session");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();   // tanpa timeout, seperti Termux
        } catch (Throwable ignored) { }
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(
                            WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Paynux:wifi");
                    wifiLock.setReferenceCounted(false);
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable ignored) { }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        catch (Throwable ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); }
        catch (Throwable ignored) { }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Paynux session", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }

        int icon = getApplicationInfo().icon;
        if (icon == 0) icon = android.R.drawable.sym_def_app_icon;

        int pif = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        int actFlags = Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP;

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(actFlags), pif);

        PendingIntent exit = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class)
                        .putExtra(EXTRA_EXIT, true).setFlags(actFlags), pif);

        boolean held = locksHeld();
        PendingIntent toggle = PendingIntent.getService(this, 2,
                new Intent(this, KeepAliveService.class)
                        .setAction(held ? ACTION_WAKELOCK_OFF : ACTION_WAKELOCK_ON), pif);

        Icon ic = Icon.createWithResource(this, icon);
        b.setContentTitle("Paynux")
                .setContentText(held
                        ? "Wake lock held - CPU and Wi-Fi stay on"
                        : "Ubuntu session is running")
                .setSmallIcon(icon)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(ic,
                        held ? "Release wakelock" : "Acquire wakelock", toggle).build())
                .addAction(new Notification.Action.Builder(ic, "Exit", exit).build());
        return b.build();
    }
}
