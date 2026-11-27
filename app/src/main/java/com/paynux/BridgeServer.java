package com.paynux;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Jembatan guest (PRoot) -> aplikasi Android.
 *
 * Server TCP hanya di 127.0.0.1 pada port acak. Port dan token acak ditulis ke
 * ~/.paynux/bridge (di guest: /root/.paynux/bridge), jadi hanya proses dengan UID
 * aplikasi yang bisa membaca token. Satu permintaan = satu baris:
 *   TOKEN \t perintah \t arg1 \t arg2 ...   (tiap arg di-percent-encode)
 * Balasan satu baris: "OK pesan" atau "ERR pesan".
 *
 * Perintah: ping, notify judul teks, install path-apk, open url-http(s).
 */
final class BridgeServer {
    private static final String CHANNEL = "paynux_agent";
    private static final int MAX_LINE = 16384;
    private static final int NOTIF_BASE = 3100;

    private static ServerSocket server;
    private static String token;
    private static final ExecutorService pool = Executors.newFixedThreadPool(3);
    private static final ArrayDeque<Long> notifTimes = new ArrayDeque<>();
    private static int notifSeq;

    private BridgeServer() { }

    static synchronized void start(final Context ctx) {
        if (server != null && !server.isClosed()) return;
        try {
            byte[] raw = new byte[16];
            new SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            token = sb.toString();
            server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            writeConfig(ctx, server.getLocalPort(), token);
            final ServerSocket ss = server;
            Thread t = new Thread(() -> acceptLoop(ctx.getApplicationContext(), ss), "paynux-bridge");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            server = null;
        }
    }

    static synchronized void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        server = null;
    }

    private static void writeConfig(Context ctx, int port, String tok) throws IOException {
        File dir = new File(new File(ctx.getFilesDir(), "home"), ".paynux");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdir " + dir);
        File f = new File(dir, "bridge");
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write((port + "\n" + tok + "\n").getBytes(StandardCharsets.UTF_8));
        }
        f.setReadable(false, false);
        f.setReadable(true, true);
        f.setWritable(false, false);
        f.setWritable(true, true);
    }

    private static void acceptLoop(final Context ctx, ServerSocket ss) {
        while (!ss.isClosed()) {
            try {
                final Socket s = ss.accept();
                pool.execute(() -> handle(ctx, s));
            } catch (Throwable e) {
                if (ss.isClosed()) return;
            }
        }
    }

    private static void handle(Context ctx, Socket s) {
        try {
            s.setSoTimeout(5000);
            String line = readLine(s.getInputStream());
            String[] f = line.split("\t", -1);
            String reply;
            if (f.length < 2 || !tokenOk(f[0])) {
                reply = "ERR unauthorized";
            } else {
                String[] args = new String[f.length - 2];
                for (int i = 2; i < f.length; i++) args[i - 2] = dec(f[i]);
                try {
                    reply = dispatch(ctx, f[1], args);
                } catch (Throwable e) {
                    reply = "ERR " + e.getMessage();
                }
            }
            OutputStream out = s.getOutputStream();
            out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static boolean tokenOk(String given) {
        String t = token;
        if (t == null) return false;
        return MessageDigest.isEqual(t.getBytes(StandardCharsets.US_ASCII),
                given.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (b.size() >= MAX_LINE) throw new IOException("request too long");
            b.write(c);
        }
        return b.toString("ISO-8859-1");
    }

    private static String dec(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    o.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) { }
            }
            o.write(c);
        }
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String dispatch(Context ctx, String cmd, String[] a) throws Exception {
        switch (cmd) {
            case "ping":
                return "OK paynux-bridge 1";
            case "notify":
                if (a.length < 2) return "ERR usage: notify title text";
                return doNotify(ctx, a[0], a[1]);
            case "install":
                if (a.length < 1) return "ERR usage: install file.apk";
                return doInstall(ctx, a[0]);
            case "open":
                if (a.length < 1) return "ERR usage: open url";
                return doOpen(ctx, a[0]);
            default:
                return "ERR unknown command: " + cmd;
        }
    }

    // ---------------- notify ----------------

    private static String doNotify(Context ctx, String title, String text) {
        long now = System.currentTimeMillis();
        int id;
        synchronized (notifTimes) {
            while (!notifTimes.isEmpty() && now - notifTimes.peekFirst() > 10000L) notifTimes.pollFirst();
            if (notifTimes.size() >= 10) return "ERR rate limited (max 10 notifications / 10 s)";
            notifTimes.addLast(now);
            id = NOTIF_BASE + (notifSeq++ % 20);
        }
        if (title.length() > 100) title = title.substring(0, 100);
        if (text.length() > 1000) text = text.substring(0, 1000);
        postNotif(ctx, id, title, text, null);
        MainActivity.addNotif("info", title, text);
        return "OK sent";
    }

    // ---------------- open ----------------

    private static String doOpen(Context ctx, String url) {
        String u = url.trim();
        String low = u.toLowerCase(Locale.US);
        if (!(low.startsWith("http://") || low.startsWith("https://")) || u.length() > 2000) {
            return "ERR only http(s) links up to 2000 chars are supported";
        }
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(u))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (MainActivity.isAppVisible()) {
            try {
                ctx.startActivity(view);
                return "OK opened";
            } catch (Throwable ignored) { }
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 3300, view,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        postNotif(ctx, 3300, "Open link", u, pi);
        return "OK tap the notification to open the link";
    }

    // ---------------- install ----------------

    private static String doInstall(Context ctx, String guestPath) throws Exception {
        File apk = mapGuestPath(ctx, guestPath);
        if (!apk.isFile()) return "ERR file not found: " + guestPath;
        if (!apk.getName().toLowerCase(Locale.US).endsWith(".apk")) return "ERR not an .apk file";
        long len = apk.length();
        if (len <= 0 || len > 1500L * 1024L * 1024L) return "ERR bad file size";
        PackageManager pm = ctx.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (info == null || info.packageName == null) return "ERR not a valid APK";

        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            Intent st = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            boolean started = false;
            if (MainActivity.isAppVisible()) {
                try { ctx.startActivity(st); started = true; } catch (Throwable ignored) { }
            }
            if (!started) {
                PendingIntent pi = PendingIntent.getActivity(ctx, 3201, st,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                postNotif(ctx, 3201, "Allow PayNux to install apps",
                        "Tap, enable the switch, then run paynux-install again.", pi);
            }
            return "ERR allow PayNux to install apps (Settings > Install unknown apps), then retry";
        }

        PackageInstaller pi = pm.getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(info.packageName);
        params.setSize(len);
        int id = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(id);
        try {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, len)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent cb = new Intent(ctx, InstallResultReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= 0x02000000;   // FLAG_MUTABLE
            PendingIntent pend = PendingIntent.getBroadcast(ctx, id, cb, flags);
            session.commit(pend.getIntentSender());
        } catch (IOException | RuntimeException e) {
            session.abandon();
            throw e;
        } finally {
            session.close();
        }
        String ver = info.versionName != null ? info.versionName : String.valueOf(info.versionCode);
        return "OK installing " + info.packageName + " " + ver + " - confirm on the phone";
    }

    /** Path guest -> path host; hanya di home, rootfs, atau penyimpanan bersama. */
    private static File mapGuestPath(Context ctx, String guest) throws IOException {
        File home = new File(ctx.getFilesDir(), "home");
        File root = MainActivity.ubuntuRoot();
        File f;
        if (guest.equals("/root") || guest.startsWith("/root/")) {
            f = new File(home, guest.substring(5));
        } else if (guest.startsWith("/sdcard/") || guest.startsWith("/storage/")) {
            f = new File(guest);
        } else if (root != null) {
            f = new File(root, guest);
        } else {
            throw new IOException("rootfs not ready");
        }
        File c = f.getCanonicalFile();
        boolean ok = within(c, home.getCanonicalFile())
                || (root != null && within(c, root.getCanonicalFile()))
                || within(c, Environment.getExternalStorageDirectory().getCanonicalFile())
                || c.getPath().startsWith("/storage/");
        if (!ok) throw new IOException("path not allowed");
        return c;
    }

    private static boolean within(File f, File dir) {
        String p = f.getPath();
        String d = dir.getPath();
        return p.equals(d) || p.startsWith(d + File.separator);
    }

    // ---------------- notifikasi ----------------

    static void postNotif(Context ctx, int id, String title, String text, PendingIntent pi) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "Agent and scripts", NotificationManager.IMPORTANCE_DEFAULT));
                b = new Notification.Builder(ctx, CHANNEL);
            } else {
                b = new Notification.Builder(ctx);
            }
            int icon = ctx.getApplicationInfo().icon;
            if (icon == 0) icon = android.R.drawable.sym_def_app_icon;
            if (pi == null) {
                Intent open = new Intent(ctx, MainActivity.class).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                pi = PendingIntent.getActivity(ctx, 12, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
            b.setSmallIcon(icon)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis());
            nm.notify(id, b.build());
        } catch (Throwable ignored) { }
    }
}
