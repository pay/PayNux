package com.paynux;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Parcelable;

/** Hasil PackageInstaller dari paynux-install: minta konfirmasi, atau laporkan sukses/gagal. */
public final class InstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        if (pkg == null) pkg = "app";

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Parcelable p = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (!(p instanceof Intent)) return;
            Intent confirm = (Intent) p;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (MainActivity.isAppVisible()) {
                try {
                    ctx.startActivity(confirm);
                    return;
                } catch (Throwable ignored) { }
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 3202, confirm,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            BridgeServer.postNotif(ctx, 3202, "Install " + pkg, "Tap to confirm the installation.", pi);
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            BridgeServer.postNotif(ctx, 3200, "Installed", pkg + " was installed.", null);
            MainActivity.addNotif("ok", "Installed", pkg);
        } else {
            String text = (msg != null && !msg.isEmpty()) ? msg : ("status " + status);
            BridgeServer.postNotif(ctx, 3200, "Install failed", pkg + ": " + text, null);
            MainActivity.addNotif("warn", "Install failed", pkg + ": " + text);
        }
    }
}
