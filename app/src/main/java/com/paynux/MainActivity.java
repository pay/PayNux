package com.paynux;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.paynux.terminal.PasteFormatter;
import com.paynux.terminal.TerminalEmulator;
import com.paynux.terminal.TerminalSession;
import com.paynux.setup.AndroidFsOps;
import com.paynux.setup.Downloader;
import com.paynux.setup.OfflineInstaller;
import com.paynux.setup.ProotSelfTest;
import com.paynux.setup.RootfsSource;
import com.paynux.setup.SetupListener;
import com.paynux.setup.SetupManager;
import com.paynux.compat.AboutInfo;
import com.paynux.compat.AndroidPaths;
import com.paynux.compat.CommandDone;
import com.paynux.compat.FakeProc;
import com.paynux.compat.ShmDir;
import com.paynux.compat.SessionName;
import com.paynux.compat.ToolbarConfig;
import com.paynux.compat.UrlFinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    // ============================================================
    // TERMINAL
    // ============================================================

    private TextView scrollbackView;
    private TextView screenView;
    private EditText hiddenInput;
    private ScrollView terminalScroll;
    private LinearLayout keyBar;

    /** Satu sesi terminal: emulator + shell sendiri. */
    /** Bar progres merah->oranye. Determinate = isi sesuai fraksi; indeterminate = segmen gradien bergeser. */
    private static final class GradientBar extends View {
        private final android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private float fraction;
        private boolean indeterminate;

        GradientBar(android.content.Context c) {
            super(c);
            track.setColor(Palette.LINE);
        }

        void setFraction(float f) {
            fraction = Math.max(0f, Math.min(1f, f));
            indeterminate = false;
            invalidate();
        }

        void setIndeterminate(boolean on) {
            if (indeterminate != on) {
                indeterminate = on;
                invalidate();
            }
        }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h / 2f;
            rect.set(0, 0, w, h);
            cv.drawRoundRect(rect, r, r, track);
            float left;
            float right;
            if (indeterminate) {
                float seg = w * 0.35f;
                float t = (android.os.SystemClock.uptimeMillis() % 1400L) / 1400f;
                left = -seg + (w + seg) * t;
                right = left + seg;
            } else {
                left = 0f;
                right = w * fraction;
            }
            left = Math.max(0f, left);
            right = Math.min(w, right);
            if (right - left > 0.5f) {
                fill.setShader(new android.graphics.LinearGradient(left, 0f, right, 0f,
                        Palette.RED_FILL, Palette.ORANGE, android.graphics.Shader.TileMode.CLAMP));
                rect.set(left, 0f, right, h);
                cv.drawRoundRect(rect, r, r, fill);
            }
            if (indeterminate) postInvalidateOnAnimation();
        }
    }

    /** TextView terminal: tidak boleh men-scroll dirinya sendiri (itu yang membuat teks goyang saat disentuh). */
    private static final class PinnedTextView extends TextView {
        PinnedTextView(android.content.Context c) { super(c); }
        @Override public void scrollTo(int x, int y) { super.scrollTo(0, 0); }
    }

    /** ScrollView terminal: abaikan lompatan otomatis (fokus/kursor) selama jari menyentuh layar. */
    private static final class SteadyScrollView extends ScrollView {
        private boolean touching;
        SteadyScrollView(android.content.Context c) { super(c); }
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
            int a = ev.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) touching = true;
            boolean r = super.dispatchTouchEvent(ev);
            if (a == android.view.MotionEvent.ACTION_UP
                    || a == android.view.MotionEvent.ACTION_CANCEL) touching = false;
            return r;
        }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
            return touching ? 0 : super.computeScrollDeltaToGetChildRectOnScreen(rect);
        }
    }

    private static final class Sess {
        final int id;
        TerminalEmulator emu;
        TerminalSession ts;
        volatile boolean ready;
        volatile boolean exited;
        volatile boolean closing;   // ditutup sengaja (bukan exit tak terduga)
        volatile String name;       // nama pilihan pengguna (null = "Session N"), hanya di memori
        volatile boolean busy;      // shell sedang menjalankan perintah (dari hook OSC)
        volatile long busyAt;       // elapsedRealtime saat perintah mulai
        Sess(int id) { this.id = id; }
    }

    private static final int MAX_SESSIONS = 8;
    // STATE SESI = level proses (static), bukan level Activity. Activity hanya
    // "jendela" yang menempel ke state ini. Dengan begitu menggeser/menutup
    // aplikasi dari recents TIDAK membunuh shell (ala Termux) selama
    // foreground service hidup. Keluar sungguhan: tombol Exit di notifikasi.
    private static final List<Sess> sessions =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile Sess active;
    private static int nextSessId = 1;
    private static volatile MainActivity current;   // Activity yang sedang tampil (atau null)
    private Button sessBtn;          // key sesi di toolbar (buka daftar sesi)
    private TextView sessBadgeView;  // badge jumlah sesi pada key sesi
    private float swipeDownY;        // awal gestur untuk aksi kedua (geser naik)
    private boolean swipeFired;      // true = gestur geser naik sudah memicu aksi kedua
    private int maxDecorHeightPx = -1; // cadangan deteksi IME (API < 30)

    // ---- geometri toolbar (cukup ubah di sini) ----
    static final int KEY_H_DP    = 38;   // tinggi satu baris toolbar
    static final int SWIPE_UP_DP = 24;   // geser ke atas > ini = aksi kedua

    // emulator/session = penunjuk ke sesi AKTIF (dipakai kode lama)
    private static TerminalEmulator emulator;
    private static TerminalSession session;
    // kursor blok berkedip saat idle (ala Termux)
    private boolean cursorBlinkOn = true;
    private android.os.Handler blinkHandler;
    private static volatile boolean restarting = false;
    private android.view.ScaleGestureDetector scaleDetector;
    private float tapDownX, tapDownY;
    private long tapDownAt;
    private volatile boolean touchInProgress = false;
    private static volatile boolean shellReady = false;

    private int fontSizeSp = 14;
    private boolean userAtBottom = true;
    private boolean altScreenShown = false;   // riwayat disembunyikan saat alt-screen
    private android.widget.FrameLayout drawer;   // panel sesi geser dari kiri
    private View drawerScrim;
    private boolean drawerOpen = false;
    private static final int DRAWER_W_DP = 300;
    private static long lastBellAt = 0;       // throttle getaran BEL

    /** BEL dari shell -> getar singkat (throttle 400 ms). */
    private static void vibrateBell() {
        MainActivity a = current;
        if (a == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastBellAt < 400) return;
        lastBellAt = now;
        try {
            android.os.Vibrator v =
                    (android.os.Vibrator) a.getSystemService(VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(25, 140));
                } else {
                    v.vibrate(25);
                }
            }
        } catch (Throwable ignored) { }
    }
    private boolean renderPending = false;
    private int lastRows = -1, lastCols = -1;

    private final boolean[] ctrlMode = {false};
    private final boolean[] altMode = {false};

    private static final int CURSOR_COLOR = Palette.ORANGE;

    // ============================================================
    // RUNTIME PATHS
    // ============================================================

    private static File ubuntuDir;
    private static File runtimeDir;
    private static File prootFile;
    private static File symlinkDir;

    // ============================================================
    // ON CREATE
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appCtx = getApplicationContext();

        // Dibuka dari tombol "Exit" notifikasi saat Activity sudah tidak ada
        if (getIntent() != null
                && getIntent().getBooleanExtra(KeepAliveService.EXTRA_EXIT, false)) {
            finish();
            shutdownAll(this);
            return;
        }

        current = this;
        installCrashLogger();
        try {
            android.content.Intent keep = new android.content.Intent(this, KeepAliveService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(keep);
            else startService(keep);
        } catch (Throwable ignored) { }
        keepApplyMode(true);

        boolean fresh = (emulator == null);
        if (fresh) {
            Sess first = createSess();
            sessions.add(first);
            active = first;
            emulator = first.emu;
        }

        buildTerminalUI();
        updateSessionButton();
        checkVersionChange();

        if (fresh) {
            showBanner();
            reportPreviousExit();
        } else {
            scheduleRender(); // repaint existing state after recreation
        }

        if (bootStarted) {
            startFocusGuard();
            startCursorBlink();
            return;
        }
        bootStarted = true;
        bootBusy = true;
        startFocusGuard();
        startCursorBlink();


        // Izin storage ditanyakan SETELAH terminal siap, dan boot Ubuntu
        // baru jalan setelah user menjawab -> urutan di log sama dengan
        // yang terjadi di layar (tidak ada bind /sdcard yang gagal diam-diam).
        requestStoragePermission();
    }

    private static volatile boolean ubuntuBootStarted = false;

    /**
     * Boot Ubuntu (rootfs + bind /sdcard). Dijalankan setelah izin
     * storage dijawab supaya keputusan bind /sdcard sesuai kondisi
     * izin yang benar-benar berlaku.
     */
    private void startUbuntuBoot() {
        if (ubuntuBootStarted) return;
        ubuntuBootStarted = true;
        new Thread(
                () -> {
                    try {

                        prepareRuntime();

                        startUbuntu(sessions.get(0));
                        addNotif("ok", "Terminal ready", "Ubuntu shell started.");

                    } catch (Exception e) {

                        setupFail();
                        addNotif("err", "Setup failed", notifShort(e));
                        appendOutput(
                                "\n[PAYNUX ERROR]\n" +
                                e.getClass().getName() +
                                "\n" +
                                String.valueOf(e.getMessage()) +
                                "\n\n"
                        );

                        e.printStackTrace();
                    } finally {
                        bootBusy = false;
                    }
                },
                "Paynux-Boot"
        ).start();
    }

    private static volatile boolean bootStarted = false;

    // ============================================================
    // DIAGNOSTIK: crash log + alasan proses sebelumnya berhenti
    // File ada di /root/paynux-crash.log dan /root/paynux-exit.log
    // ============================================================

    private static boolean crashLoggerInstalled = false;

    private void appendHomeLog(String name, String text) {
        try {
            File home = new File(getFilesDir(), "home");
            if (!home.exists()) home.mkdirs();
            File f = new File(home, name);
            if (f.length() > 200 * 1024) f.delete();
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f, true)) {
                o.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) { }
    }

    private void installCrashLogger() {
        if (crashLoggerInstalled) return;
        crashLoggerInstalled = true;
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        // jangan menangkap Activity di handler statis: salin hanya path & versi
        final File logFile = new File(new File(getFilesDir(), "home"), "paynux-crash.log");
        final String ver = appVersion();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                logFile.getParentFile().mkdirs();
                if (logFile.length() > 200 * 1024) logFile.delete();
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(logFile, true)) {
                    o.write(("==== " + new java.util.Date() + " v" + ver
                            + " thread=" + t.getName() + "\n" + sw + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private static String exitReasonName(int r) {
        switch (r) {
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            default: return "UNKNOWN(" + r + ")";
        }
    }

    // ============================================================
    // KEEP ALIVE: wakelock otomatis saat ada perintah yang berjalan lama
    //   Auto (default) = kunci bila perintah >= 10 dtk, lepas 30 dtk setelah semua idle
    //   On = selalu kunci, Off = hanya manual lewat notifikasi
    // ============================================================

    private static final int KEEP_OFF = 0, KEEP_AUTO = 1, KEEP_ON = 2;
    private static final long KEEP_AFTER_MS = 10000L;
    private static final long KEEP_RELEASE_MS = 30000L;
    private static final long KEEP_RECHECK_MS = 15000L;
    private static final android.os.Handler keepHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable keepTick = MainActivity::keepEvaluate;
    private static boolean keepAutoHeld;
    private static long keepLastBusySeen;

    private static int keepMode() {
        if (appCtx == null) return KEEP_AUTO;
        return appCtx.getSharedPreferences("paynux_ui", Context.MODE_PRIVATE)
                .getInt("keep_mode", KEEP_AUTO);
    }

    private static void keepSetMode(int mode) {
        if (appCtx == null) return;
        appCtx.getSharedPreferences("paynux_ui", Context.MODE_PRIVATE)
                .edit().putInt("keep_mode", mode).apply();
        keepApplyMode(false);
    }

    private static String keepLabel(int mode) {
        return mode == KEEP_ON ? "On" : (mode == KEEP_OFF ? "Off" : "Auto");
    }

    /** Terapkan mode saat ini (startup = true: jangan melepas kunci yang mungkin sudah dipegang). */
    private static void keepApplyMode(boolean startup) {
        keepHandler.removeCallbacks(keepTick);
        if (appCtx == null) return;
        int mode = keepMode();
        keepAutoHeld = false;
        if (mode == KEEP_ON) {
            KeepAliveService.setLocks(appCtx, true);
        } else if (mode == KEEP_OFF) {
            if (!startup) KeepAliveService.setLocks(appCtx, false);
        } else {
            if (!startup) KeepAliveService.setLocks(appCtx, false);
            keepEvaluate();
        }
    }

    /** Dari thread mana pun: evaluasi ulang di main thread. */
    private static void keepKick() {
        keepHandler.post(keepTick);
    }

    private static void keepEvaluate() {
        keepHandler.removeCallbacks(keepTick);
        if (appCtx == null || keepMode() != KEEP_AUTO) return;
        long now = android.os.SystemClock.elapsedRealtime();
        boolean anyBusy = false;
        long oldest = Long.MAX_VALUE;
        for (Sess sc : sessions) {
            if (sc.busy && !sc.exited) {
                anyBusy = true;
                oldest = Math.min(oldest, sc.busyAt);
            }
        }
        if (anyBusy) {
            keepLastBusySeen = now;
            if (!keepAutoHeld) {
                long age = now - oldest;
                if (age >= KEEP_AFTER_MS) {
                    keepAutoHeld = true;
                    KeepAliveService.setLocks(appCtx, true);
                    keepHandler.postDelayed(keepTick, KEEP_RECHECK_MS);
                } else {
                    keepHandler.postDelayed(keepTick, KEEP_AFTER_MS - age);
                }
            } else {
                keepHandler.postDelayed(keepTick, KEEP_RECHECK_MS);
            }
        } else if (keepAutoHeld) {
            long since = now - keepLastBusySeen;
            if (since >= KEEP_RELEASE_MS) {
                keepAutoHeld = false;
                KeepAliveService.setLocks(appCtx, false);
            } else {
                keepHandler.postDelayed(keepTick, KEEP_RELEASE_MS - since);
            }
        }
    }

    /** Ketuk item sidebar: Auto -> On -> Off -> Auto. */
    private void cycleKeepAlive() {
        int m = keepMode();
        int next = m == KEEP_AUTO ? KEEP_ON : (m == KEEP_ON ? KEEP_OFF : KEEP_AUTO);
        keepSetMode(next);
        String msg = next == KEEP_AUTO
                ? "Keep alive: Auto - wake lock while a command runs 10s+"
                : (next == KEEP_ON
                ? "Keep alive: On - wake lock always held (uses more battery)"
                : "Keep alive: Off - wake lock only from the notification");
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
    }

    private void reportPreviousExit() {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        pruneExitLog();
        // clean_exit: exit disengaja (tombol Exit / lepas wakelock) bukan crash.
        // Hapus penanda (sekali pakai) dan jangan tampilkan peringatan.
        java.io.File marker = cleanExitMarker(this);
        if (marker.exists()) {
            //noinspection ResultOfMethodCallIgnored
            marker.delete();
            return;
        }
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ApplicationExitInfo> l =
                    am.getHistoricalProcessExitReasons(getPackageName(), 0, 1);
            if (l == null || l.isEmpty()) return;
            android.app.ApplicationExitInfo i = l.get(0);
            int r = i.getReason();
            String name = exitReasonName(r);
            // Hanya CRASH_NATIVE yang masuk crash log. Exit normal
            // (EXIT_SELF, USER_REQUESTED, PACKAGE_UPDATED, [REMOVE TASK])
            // bukan crash dan memenuhi log. Java crash sudah tercatat
            // terpisah di paynux-crash.log oleh UncaughtExceptionHandler.
            if (r == 5) {
                appendHomeLog("paynux-exit.log",
                        new java.util.Date(i.getTimestamp()) + " reason=" + name
                                + " status=" + i.getStatus()
                                + " importance=" + i.getImportance()
                                + " desc=" + i.getDescription() + "\n");
            }
            // Peringatan hanya untuk crash native; exit normal (EXIT_SELF,
            // USER_REQUESTED, USER_STOPPED, update, SIGNAL) senyap agar
            // tidak ada "[!] Last run ended" yang barisnya sudah tidak
            // dicatat di paynux-exit.log.
            if (r == 2 || r == 3 || r == 9 || r == 14) {   // SIGNALED, LOW_MEMORY, EXCESSIVE_RESOURCE, FREEZER
                appendOutput("\u001b[33m[!] Last run was killed by Android: " + name + ". Sessions were lost.\u001b[0m\n"
                        + "\u001b[90m    Sidebar > Keep alive: On, and set Battery > Unrestricted for Paynux.\u001b[0m\n\n");
                addNotif("warn", "App was killed by Android",
                        "Last run ended: " + name + ". Set Keep alive to On and Battery to Unrestricted.");
                return;
            }
            if (r != 5) return;
            appendOutput("\u001b[33m[!] Last run ended: " + name
                    + " (status " + i.getStatus() + ")\u001b[0m\n"
                    + "\u001b[90m    details: /root/paynux-exit.log\u001b[0m\n\n");
            addNotif("warn", "App stopped unexpectedly",
                    "Last run ended: " + name + ". Details: /root/paynux-exit.log");
        } catch (Throwable ignored) { }
    }
    private static volatile boolean bootBusy = false;

    /**
     * Banner sambutan. Log boot rutin ("Preparing...", "Runtime
     * ready", ...) DISNYAPKAN agar layar bersih seperti Termux —
     * hanya error/instalasi yang tampil. BOOT_VERBOSE=true
     * mengembalikan log verbose lama untuk diagnostik.
     */
    private static final boolean BOOT_VERBOSE = false;

    private static void bootLog(String text) {
        if (BOOT_VERBOSE) appendOutput(text);
    }

    private String appVersion() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0.1";
        }
    }

    private Typeface monoTypeface = null;

    /**
     * Font terminal: DejaVu Sans Mono (dibundel di assets/fonts/paynux-mono.ttf).
     * Alasan: DroidSansMono tidak punya glyph kotak/blok (U+2500..U+2591) yang
     * dipakai banner dan prompt, jadi Android memakai font fallback dengan
     * metrik lebih tinggi -> celah kosong antar baris. DejaVu Sans Mono punya
     * semua glyph itu dan tinggi barisnya sama (1,1641 em).
     */
    private Typeface monoTypeface() {
        if (monoTypeface == null) {
            try {
                monoTypeface = Typeface.createFromAsset(getAssets(),
                        "fonts/paynux-mono.ttf");
            } catch (Exception e) {
                monoTypeface = Typeface.MONOSPACE;
            }
        }
        return monoTypeface;
    }

    /** Perkiraan jumlah kolom sebelum layout selesai diukur. */
    private int approxCols() {
        if (lastCols > 0) return lastCols;
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.Paint p = new android.graphics.Paint();
            p.setTypeface(monoTypeface());
            p.setTextSize(fontSizeSp * dm.scaledDensity);
            float cw = p.measureText("M");
            if (cw > 0) return Math.max(20, (int) ((dm.widthPixels - dp(34)) / cw));
        } catch (Exception ignored) { }
        return 40;
    }

    private static String center(String t, int w) {
        if (t.length() >= w) return t.substring(0, w);
        int left = (w - t.length()) / 2;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < left; i++) sb.append(' ');
        sb.append(t);
        while (sb.length() < w) sb.append(' ');
        return sb.toString();
    }

    private static String rep(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static String clip(String t, int w) {
        return t.length() <= w ? t : t.substring(0, Math.max(0, w));
    }

    // ============================================================
    // IZIN STORAGE (untuk bind /sdcard ke dalam Ubuntu)
    // ============================================================

    private static final int REQ_STORAGE = 1001;

    /**
     * Minta izin storage. Dipanggil setelah terminal siap: pesan ditulis
     * ke log lebih dulu, baru dialog sistem muncul, dan boot Ubuntu
     * baru jalan setelah user menjawab -- jadi yang tertulis di log
     * sama persis dengan urutan yang dilihat user.
     * targetSdk 28 -> izin runtime WRITE_EXTERNAL_STORAGE cukup untuk /sdcard.
     */
    private void requestStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT < 23) { startUbuntuBoot(); return; }
        try {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startUbuntuBoot();
                return;
            }
            appendOutput("\n\u001b[38;5;240m[?] Requesting /sdcard access "
                    + "(Files and media)...\u001b[0m\n");
            requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        } catch (Throwable t) {
            bootLog("[!] storage permission: " + t + "\n");
            startUbuntuBoot();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_STORAGE) return;
        boolean ok = res.length > 0
                && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (ok) {
            appendOutput("\n\u001b[38;5;240m[+] /sdcard permission: OK.\u001b[0m\n");
        } else {
            appendOutput("\n\u001b[1;33m[!] Storage permission denied \u2014 /sdcard cannot be opened.\n"
                    + "    Enable: Settings > Apps > Paynux > Permissions > Files and media,\n"
                    + "    then open a new session (session key > + New session).\u001b[0m\n");
        }
        startUbuntuBoot();
    }

    /** Banner simpel: logo PAYNUX gradasi + satu baris info. */
    private void showBanner() {
        int cols = approxCols();
        String ver = appVersion();

        if (cols < 26) { // layar sangat sempit: tanpa logo
            appendOutput("\u001b[1;97mPAY\u001b[1;38;2;220;38;38mNUX \u001b[0;90mv" + ver + "\n");
            return;
        }

        // Logo blok gaya "pagga": 23 kolom, 3 baris. Karakter U+2591 (light
        // shade) jadi pengisi gelap supaya hurufnya menonjol; sisa karakter
        // diberi gradasi cyan -> biru per kolom.
        String[] art = {
            "\u2588\u2580\u2588\u2591\u2588\u2580\u2588\u2591\u2588\u2591\u2588\u2591\u2588\u2580\u2588\u2591\u2588\u2591\u2588\u2591\u2588\u2591\u2588",
            "\u2588\u2580\u2580\u2591\u2588\u2580\u2588\u2591\u2591\u2588\u2591\u2591\u2588\u2591\u2588\u2591\u2588\u2591\u2588\u2591\u2584\u2580\u2584",
            "\u2580\u2591\u2591\u2591\u2580\u2591\u2580\u2591\u2591\u2580\u2591\u2591\u2580\u2591\u2580\u2591\u2580\u2580\u2580\u2591\u2580\u2591\u2580"
        };
        int[] shade = {196, 160, 124, 88, 240, 236}; // merah -> gelap

        for (int row = 0; row < art.length; row++) {
            String line = art[row];
            StringBuilder sb = new StringBuilder("  ");
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '\u2591') {
                    sb.append("\u001b[38;5;238m").append(ch);
                } else {
                    // PAY putih (kolom 0-10), NUX merah (kolom 12-22)
                    if (i < 12) sb.append("\u001b[1;38;2;255;255;255m").append(ch);
                    else sb.append("\u001b[1;38;2;220;38;38m").append(ch);
                }
            }
            sb.append("\u001b[0m\n");
            appendOutput(sb.toString());
        }
        // pemisah gradasi selebar logo
        int sepLen = Math.min(23, Math.max(8, cols - 4));
        StringBuilder sep = new StringBuilder("  ");
        for (int i = 0; i < sepLen; i++) {
            int sh = shade[Math.min(shade.length - 1, i * shade.length / sepLen)];
            sep.append("\u001b[38;5;").append(sh).append("m\u2501");
        }
        sep.append("\u001b[0m\n");
        appendOutput(sep.toString());

        String dot = "\u001b[38;2;220;38;38m\u2022\u001b[0m ";
        appendOutput("  " + dot + "\u001b[97mUbuntu 24.04\u001b[0m"
                + " \u001b[90m\u00B7 ARM64 \u00B7 PRoot\u001b[0m\n");
        appendOutput("  " + dot + "\u001b[97mAndroid "
                + android.os.Build.VERSION.RELEASE + "\u001b[0m"
                + " \u001b[90m\u00B7 v" + ver + "\u001b[0m\n");
        appendOutput("  \u001b[38;2;161;161;170mPayNux \u00B7 Linux On Your Pocket\u001b[0m\n");
    }

    private volatile boolean actionModeActive = false;
    private android.os.Handler focusGuard;
    private final Runnable focusGuardRunnable = new Runnable() {
        @Override public void run() {
            if (focusGuard == null) return;
            // Kembalikan fokus ke kolom input bila tidak sedang
            // seleksi teks — kalau TextView terminal yang menahan
            // fokus, ketikan keyboard terbuang (bukan editor).
            if (!actionModeActive && !touchInProgress && shellReady && hiddenInput != null) {
                View f = getCurrentFocus();
                if (f != null && f != hiddenInput && f instanceof TextView) {
                    hiddenInput.requestFocus();
                }
            }
            focusGuard.postDelayed(this, 1200);
        }
    };

    private void startFocusGuard() {
        if (focusGuard == null) focusGuard = new android.os.Handler(getMainLooper());
        focusGuard.removeCallbacks(focusGuardRunnable);
        focusGuard.postDelayed(focusGuardRunnable, 1200);
    }

    /**
     * Kursor blok berkedip ~2x/detik saat shell idle (ala Termux).
     * Saat ada output/ketikan, scheduleRender() mengembalikan kursor
     * solid; kedip hanya berlaku kalau emulator masih menampilkan
     * kursor dan tidak ada pilihan teks (action mode).
     */
    private void startCursorBlink() {
        if (blinkHandler == null) blinkHandler = new android.os.Handler(getMainLooper());
        blinkHandler.removeCallbacks(blinkRunnable);
        blinkHandler.postDelayed(blinkRunnable, 530);
    }

    private final Runnable blinkRunnable = new Runnable() {
        @Override public void run() {
            if (emulator != null && emulator.isCursorVisible()
                    && !actionModeActive && !renderPending) {
                cursorBlinkOn = !cursorBlinkOn;
                applyCursorSpan();
            }
            if (blinkHandler != null) blinkHandler.postDelayed(this, 530);
        }
    };

    /** Key event dari window: bila fokus di terminal TextView (bukan
     *  kolom input), teruskan ke shell agar ketikan tetap sampai. */
    private boolean volUpHeld = false;   // Volume-Up ditahan = modifier (ala Termux)

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Volume-Up dipakai sebagai modifier: VolUp+E=Esc, VolUp+H/J/K/L=panah,
        // VolUp+1..0=F1..F10 (sama seperti 'volume-keys = volume-up' di Termux).
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
            volUpHeld = event.getAction() == KeyEvent.ACTION_DOWN;
            return super.dispatchKeyEvent(event);   // volume tetap berfungsi
        }
        if (volUpHeld && event.getAction() == KeyEvent.ACTION_DOWN && shellReady) {
            String seq = volumeKeySequence(event.getKeyCode());
            if (seq != null) { shellWrite(seq); return true; }
        }
        if (shellReady && event.getAction() == KeyEvent.ACTION_DOWN) {
            View f = getCurrentFocus();
            boolean onTextView = (f instanceof TextView && f != hiddenInput);
            if (onTextView && handleHardwareKey(event.getKeyCode(), event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onActionModeStarted(android.view.ActionMode mode) {
        super.onActionModeStarted(mode);
        actionModeActive = true;
    }

    @Override
    public void onActionModeFinished(android.view.ActionMode mode) {
        super.onActionModeFinished(mode);
        actionModeActive = false;
        // seleksi selesai -> segera serahkan fokus ke kolom input
        if (hiddenInput != null && shellReady) {
            hiddenInput.requestFocus();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // window Paynux mendapat fokus (awal / kembali dari app lain)
        // -> pastikan keyboard terminal siap dipakai
        if (hasFocus && shellReady && !actionModeActive) {
            showKeyboard();
        }
    }

    // ============================================================
    // TERMINAL UI
    // ============================================================

    private void buildTerminalUI() {

        // --------------------------------------------------------
        // COLORS (Termux palette)
        // --------------------------------------------------------

        themeIdx = themeIndex();
        int BG       = THEMES[themeIdx][0];
        int BG2      = Palette.PANEL;
        int BG3      = THEMES[themeIdx][1];
        int GREEN    = Palette.GREEN;
        int YELLOW   = Palette.AMBER;
        int CYAN     = Palette.ORANGE;
        int FG       = THEMES[themeIdx][2];
        int GRAY     = Palette.DIM;

        SharedPreferences prefs =
                getSharedPreferences("paynux_ui", MODE_PRIVATE);
        fontSizeSp = Math.max(8, Math.min(24,
                prefs.getInt("font_size", 14)));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // --------------------------------------------------------
        // SCROLLBACK (output lama, bisa dipilih/disinpan)
        // --------------------------------------------------------

        scrollbackView = new PinnedTextView(this);
        scrollbackView.setTextColor(FG);
        scrollbackView.setTextSize(fontSizeSp);
        scrollbackView.setTypeface(monoTypeface());
        scrollbackView.setGravity(Gravity.START);
        scrollbackView.setPadding(16, 8, 16, 0);
        scrollbackView.setTextIsSelectable(true); // SELECT: crash isolation test
        installCopyAllSelectionAction(scrollbackView);
        // Tanpa padding font + tanpa jarak baris tambahan: font fallback untuk
        // glyph kotak (DroidSansMono tidak punya ─..░) punya metrik
        // lebih tinggi, jadi jarak ekstra ini terlihat sebagai baris kosong.
        scrollbackView.setIncludeFontPadding(false);
        scrollbackView.setLineSpacing(0, 1.0f);

        // --------------------------------------------------------
        // SCREEN (grid terminal saat ini; tanpa wrap agar kolom
        // program full-screen seperti vim/top tetap presisi)
        // --------------------------------------------------------

        screenView = new PinnedTextView(this);
        screenView.setTextColor(FG);
        screenView.setTextSize(fontSizeSp);
        screenView.setTypeface(monoTypeface());
        screenView.setGravity(Gravity.TOP | Gravity.START);
        screenView.setPadding(dp(10), dp(4), dp(10), dp(4));
        screenView.setTextIsSelectable(true); // SELECT: crash isolation test
        installCopyAllSelectionAction(screenView);
        screenView.setIncludeFontPadding(false);
        screenView.setLineSpacing(0, 1.0f);
        screenView.setHorizontallyScrolling(true);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setBackgroundColor(Color.TRANSPARENT);
        inner.addView(screenView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        terminalScroll = new SteadyScrollView(this);
        terminalScroll.setBackgroundColor(Color.TRANSPARENT);
        terminalScroll.setFillViewport(true);
        terminalScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);   // tanpa efek regang di tepi
        terminalScroll.addView(inner, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        // saat tinggi konten berubah (scrollback bertambah) dan user di
        // dasar, langsung tempel ke dasar di pass layout yang sama —
        // tidak ada frame "loncat" antara layout dan scroll
        inner.addOnLayoutChangeListener((v, l, t, r, b, oL, oT, oR, oB) -> {
            if (userAtBottom && (b - t) != (oB - oT)) scrollToBottom();
        });

        // auto-scroll HANYA bila user memang di posisi paling bawah
        terminalScroll.setOnScrollChangeListener(
                (v, scrollX, scrollY, oldX, oldY) -> {
                    View child = ((ScrollView) v).getChildAt(0);
                    if (child == null) return;
                    int distance = child.getHeight()
                            - (scrollY + v.getHeight());
                    userAtBottom = distance < 48;
                    // alt-screen: tidak ada riwayat untuk digeser -> tempel ke dasar
                    if (!userAtBottom && emulator != null && emulator.isAltScreen()) {
                        userAtBottom = true;
                        v.post(MainActivity.this::scrollToBottom);
                    }
                });

        // --------------------------------------------------------
        // INPUT BRIDGE (IME anchor): teks yang diketik user
        // langsung diteruskan ke shell aktif (PTY), bukan menumpuk
        // di kolom input. Buffer dibuat selalu kosong.
        // --------------------------------------------------------

        hiddenInput = new EditText(this) {
            @Override
            public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_FULLSCREEN;
                return new ShellInputConnection(this);
            }
        };
        hiddenInput.setInputType(
                InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS |
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        hiddenInput.setTextColor(Color.TRANSPARENT);
        hiddenInput.setHighlightColor(Color.TRANSPARENT);
        hiddenInput.setCursorVisible(false);
        hiddenInput.setSingleLine(true);
        hiddenInput.setTypeface(monoTypeface());
        hiddenInput.setTextSize(Math.min(fontSizeSp, 14));
        // strip input DISARMONIKAN: tinggi 1px transparan — keyboard
        // tetap mengetik lurus ke shell (ala Termux), layar terminal
        // dapat ruang penuh. View harus VISIBLE (bukan GONE) agar
        // bisa memegang fokus IME.
        hiddenInput.setBackgroundColor(Color.TRANSPARENT);
        hiddenInput.setPadding(0, 0, 0, 0);
        hiddenInput.setOnEditorActionListener((v, actionId, event) -> {
            sendEnter();
            return true;
        });
        hiddenInput.setOnKeyListener((v, keyCode, event) ->
                handleHardwareKey(keyCode, event));

        // --------------------------------------------------------
        // EXTRA KEYS BAR — dua baris ala Termux: 7 kolom, lebar rata,
        // tanpa scroll horizontal. SELALU terlihat (spesifikasi).
        // Ketuk = aksi biasa; geser ke atas > SWIPE_UP_DP = aksi kedua.
        // --------------------------------------------------------

        keyBar = new LinearLayout(this);
        keyBar.setOrientation(LinearLayout.VERTICAL);
        keyBar.setBackgroundColor(BG3);

        keyBar.setPadding(dp(6), dp(6), dp(6), dp(4));

        // --- panel extra-keys: tiap 2 baris konfigurasi = 1 halaman (geser) ---
        buildKeyPanel(keyBar);

        // --------------------------------------------------------
        // ASSEMBLE
        // --------------------------------------------------------

        // --------------------------------------------------------
        // HEADER (48dp): tombol sidebar, judul, lalu chip aksi yang selalu
        // terlihat: copy log, paste, hapus log, enter, keyboard. Aksi lain
        // (sesi baru, tutup sesi, reset, notifikasi, tema, about) ada di sidebar.
        // --------------------------------------------------------

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(THEMES[themeIdx][3]);
        header.setPadding(dp(4), 0, dp(8), 0);

        android.widget.ImageView menuBtn = headerIcon(R.drawable.ic_menu);
        menuBtn.setColorFilter(Palette.TEXT);
        menuBtn.setContentDescription("Menu");
        menuBtn.setOnClickListener(v -> openDrawer());
        header.addView(menuBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView title = new TextView(this);
        SpannableString titleText = new SpannableString("> PayNux");
        titleText.setSpan(new ForegroundColorSpan(Palette.ORANGE), 0, 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        titleText.setSpan(new ForegroundColorSpan(Palette.TEXT), 2, 5,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        titleText.setSpan(new ForegroundColorSpan(ACCENT_RED), 5, 8,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(titleText);
        title.setTextSize(17);
        title.setTypeface(Typeface.create(monoTypeface(), Typeface.BOLD));
        title.setIncludeFontPadding(false);
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        header.addView(headerChip(R.drawable.ic_copy, Palette.KEY, Palette.TEXT,
                "Copy all log", v -> copyAllLog()), chipLp());
        header.addView(headerChip(R.drawable.ic_paste, Palette.KEY, Palette.TEXT,
                "Paste", v -> pasteFromClipboard()), chipLp());
        header.addView(headerChip(R.drawable.ic_delete, Palette.CHIP_RED, Palette.RED,
                "Clear log", v -> clearTerminal()), chipLp());
        header.addView(headerChip(R.drawable.ic_enter, Palette.KEY, Palette.TEXT,
                "Enter", v -> sendEnter()), chipLp());
        header.addView(headerChip(R.drawable.ic_keyboard, Palette.CHIP_ORANGE, Palette.ORANGE,
                "Keyboard", v -> toggleKeyboard()), chipLp());
        refreshBell();
        getWindow().setStatusBarColor(THEMES[themeIdx][3]);
        getWindow().setNavigationBarColor(THEMES[themeIdx][0]);

        View headerLine = new View(this);
        if (themeIdx == 0) {
            headerLine.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{Palette.RED_FILL, Palette.ORANGE, Palette.LINE}));
        } else {
            headerLine.setBackgroundColor(THEMES[themeIdx][4]);
        }

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        root.addView(headerLine, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, themeIdx == 0 ? dp(2) : 1));

        // strip tab sesi: nomor sesi + garis bawah pada tab aktif, "+" = sesi baru
        tabsScroll = new android.widget.HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        tabsScroll.setFillViewport(true);
        tabsScroll.setBackgroundColor(THEMES[themeIdx][1]);
        tabsBox = new LinearLayout(this);
        tabsBox.setOrientation(LinearLayout.HORIZONTAL);
        tabsBox.setPadding(dp(6), 0, dp(6), 0);
        tabsScroll.addView(tabsBox, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(tabsScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(16)));
        refreshTabs();

        // terminal di dalam kotak berbingkai tipis (gaya setup.log)
        android.widget.FrameLayout termBox = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable termBg = new android.graphics.drawable.GradientDrawable();
        termBg.setColor(themeIdx == 0 ? Palette.PANEL_IN : THEMES[themeIdx][0]);
        termBg.setCornerRadius(dp(12));
        termBg.setStroke(dp(1), themeIdx == 0 ? Palette.EDGE : THEMES[themeIdx][4]);
        termBox.setBackground(termBg);
        termBox.setClipToOutline(true);
        termBox.addView(terminalScroll, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams tblp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        tblp.setMargins(dp(6), dp(4), dp(6), dp(4));
        root.addView(termBox, tblp);
        root.addView(hiddenInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        root.addView(keyBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Bungkus dengan FrameLayout agar tombol keyboard bisa melayang
        // di atas terminal (muncul hanya saat keyboard tertutup).
        DrawerFrame frame = new DrawerFrame(this);
        frame.setBackgroundColor(BG);
        frame.addView(root, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));


        setupDrawer(frame);
        setContentView(frame);
        if (setupActive) showSetupOverlay();   // activity dibuat ulang saat setup masih jalan

        // jepit dua jari = ubah ukuran font (pengganti tombol A-/A+)
        scaleDetector = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    private float accumulatedScale = 1.0f;

                    @Override public boolean onScaleBegin(
                            android.view.ScaleGestureDetector d) {
                        accumulatedScale = 1.0f;
                        return true;
                    }

                    @Override public boolean onScale(
                            android.view.ScaleGestureDetector d) {
                        accumulatedScale *= d.getScaleFactor();

                        if (accumulatedScale >= 1.12f) {
                            changeFontSize(1);
                            accumulatedScale = 1.0f;
                        } else if (accumulatedScale <= 0.89f) {
                            changeFontSize(-1);
                            accumulatedScale = 1.0f;
                        }
                        return true;
                    }

                    @Override public void onScaleEnd(
                            android.view.ScaleGestureDetector d) {
                        accumulatedScale = 1.0f;
                    }
                });

        // Toolbar extra-keys SELALU terlihat (spesifikasi v0.3.8-ui):
        // hanya kolom input IME yang mengikuti buka/tutup keyboard.
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            boolean kbd = insets.getSystemWindowInsetBottom() > 100;
            if (hiddenInput != null) {
                hiddenInput.setVisibility(kbd ? View.VISIBLE : View.GONE);
            }
            if (keyBar != null) keyBar.setVisibility(View.VISIBLE);
            return insets;
        });

        // ketuk area terminal -> fokus input & buka keyboard;
        // jepit dua jari -> zoom font.
        // Fokus HANYA pada tap pendek di ACTION_UP: merebut fokus pada
        // ACTION_DOWN mengubah layout (keyboard muncul) di tengah gestur
        // sehingga long-press untuk seleksi teks tidak pernah selesai.
        terminalScroll.setOnTouchListener((v, ev) -> {
            if (scaleDetector != null) {
                scaleDetector.onTouchEvent(ev);
                if (scaleDetector.isInProgress()) return true;
            }
            // Aplikasi full-screen (htop, mc, ...) bisa meminta laporan mouse
            // lewat ?1000/?1002/?1003 -> sentuhan diteruskan sebagai event mouse.
            if (emulator != null && emulator.isMouseTracking()) {
                handleMouseTouch(ev);
                return true;
            }
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    touchInProgress = true;
                    tapDownX = ev.getX();
                    tapDownY = ev.getY();
                    tapDownAt = System.currentTimeMillis();
                    break;
                case android.view.MotionEvent.ACTION_CANCEL:
                    touchInProgress = false;
                    break;
                case android.view.MotionEvent.ACTION_UP:
                    touchInProgress = false;
                    long dt = System.currentTimeMillis() - tapDownAt;
                    float dx = Math.abs(ev.getX() - tapDownX);
                    float dy = Math.abs(ev.getY() - tapDownY);
                    // tap pendek tanpa geser = minta keyboard.
                    // (dulu dibatasi !hasFocus() -> tap tidak membuka
                    // keyboard lagi setelah fokus pindah ke input)
                    if (dt < 250 && dx < 24 && dy < 24 && hiddenInput != null) {
                        if (hiddenInput.getVisibility() != View.VISIBLE) {
                            hiddenInput.setVisibility(View.VISIBLE);
                            if (keyBar != null) keyBar.setVisibility(View.VISIBLE);
                        }
                        if (!hiddenInput.hasFocus()) hiddenInput.requestFocus();
                        showKeyboard();
                    }
                    break;
                default:
                    break;
            }
            return false; // scroll & seleksi teks (long-press) tetap berfungsi
        });

        // ukuran terminal dihitung ulang saat layout berubah
        // (rotasi, muncul/hilang keyboard, ganti font)
        root.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    // Animasi keyboard memicu banyak layout dengan tinggi perantara;
    // ukur ulang hanya setelah ukuran stabil agar baris tidak
    // bolak-balik terbuang/ditarik (penyebab layar berantakan).
    private final Runnable measureRunnable = () -> measureTerminal();

    private final android.view.ViewTreeObserver.OnGlobalLayoutListener layoutListener =
            () -> {
                if (terminalScroll == null) return;
                if (lastRows < 0) { measureTerminal(); return; }
                terminalScroll.removeCallbacks(measureRunnable);
                terminalScroll.postDelayed(measureRunnable, 150);
            };

    // --------------------------------------------------------
    // EXTRA KEY FACTORY
    // --------------------------------------------------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ============================================================
    // PUSAT NOTIFIKASI (di dalam aplikasi, bukan notifikasi sistem)
    // Disimpan di SharedPreferences "paynux_notif" sebagai JSON,
    // maksimal 50 entri. Badge merah di lonceng = jumlah belum dibaca.
    // ============================================================

    private static android.content.Context appCtx;
    private static final Object NOTIF_LOCK = new Object();
    private static final int NOTIF_MAX = 50;
    private TextView bellBadge;
    private android.widget.HorizontalScrollView tabsScroll;   // strip tab sesi
    private LinearLayout tabsBox;

    private static SharedPreferences notifPrefs() {
        return appCtx.getSharedPreferences("paynux_notif", Context.MODE_PRIVATE);
    }

    private static org.json.JSONArray notifLoad() {
        try {
            return new org.json.JSONArray(notifPrefs().getString("list", "[]"));
        } catch (Throwable t) {
            return new org.json.JSONArray();
        }
    }

    private static void notifSave(org.json.JSONArray a) {
        notifPrefs().edit().putString("list", a.toString()).apply();
    }

    /** kind: "ok", "info", "warn", "err". Aman dipanggil dari thread mana pun. */
    static void addNotif(String kind, String title, String message) {
        if (appCtx == null) return;
        try {
            synchronized (NOTIF_LOCK) {
                org.json.JSONArray old = notifLoad();
                long now = System.currentTimeMillis();
                // Pemberitahuan identik yang berulang dalam 10 menit digabung.
                if (old.length() > 0) {
                    org.json.JSONObject last = old.optJSONObject(old.length() - 1);
                    if (last != null
                            && title.equals(last.optString("h"))
                            && message.equals(last.optString("m"))
                            && now - last.optLong("t") < 10 * 60 * 1000L) {
                        return;
                    }
                }
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("t", now);
                o.put("k", kind);
                o.put("h", title);
                o.put("m", message);
                o.put("r", 0);
                old.put(o);
                org.json.JSONArray out = new org.json.JSONArray();
                for (int i = Math.max(0, old.length() - NOTIF_MAX); i < old.length(); i++) {
                    out.put(old.get(i));
                }
                notifSave(out);
            }
        } catch (Throwable ignored) { }
        uiPost(() -> {
            final MainActivity a = current;
            if (a != null) a.refreshBell();
        });
    }

    private static String notifShort(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        int nl = m.indexOf('\n');
        if (nl > 0) m = m.substring(0, nl);
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    private static int notifUnread() {
        synchronized (NOTIF_LOCK) {
            org.json.JSONArray a = notifLoad();
            int n = 0;
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o != null && o.optInt("r", 0) == 0) n++;
            }
            return n;
        }
    }

    private static void notifMarkAllRead() {
        synchronized (NOTIF_LOCK) {
            try {
                org.json.JSONArray a = notifLoad();
                for (int i = 0; i < a.length(); i++) {
                    org.json.JSONObject o = a.optJSONObject(i);
                    if (o != null) o.put("r", 1);
                }
                notifSave(a);
            } catch (Throwable ignored) { }
        }
    }

    private static void notifClear() {
        synchronized (NOTIF_LOCK) {
            notifPrefs().edit().remove("list").apply();
        }
    }

    private static String notifAgo(long t) {
        long d = System.currentTimeMillis() - t;
        if (d < 60000L) return "just now";
        if (d < 3600000L) return (d / 60000L) + " min ago";
        if (d < 86400000L) return (d / 3600000L) + " h ago";
        return new java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US)
                .format(new java.util.Date(t));
    }

    /** Perbarui badge lonceng (UI thread). */
    private void refreshBell() {
        if (bellBadge == null) return;
        int n = notifUnread();
        if (n <= 0) {
            bellBadge.setVisibility(View.GONE);
            return;
        }
        bellBadge.setText(n > 9 ? "9+" : String.valueOf(n));
        bellBadge.setVisibility(View.VISIBLE);
    }

    /** Catat versi aplikasi; beri tahu bila sudah diperbarui. */
    private void checkVersionChange() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            int vc = android.os.Build.VERSION.SDK_INT >= 28
                    ? (int) pi.getLongVersionCode() : pi.versionCode;
            String vn = pi.versionName == null ? "?" : pi.versionName;
            SharedPreferences p = getSharedPreferences("paynux_ui", MODE_PRIVATE);
            int lastVc = p.getInt("last_version_code", 0);
            String lastVn = p.getString("last_version_name", "?");
            if (lastVc != vc) {
                if (lastVc == 0) {
                    addNotif("info", "Notifications on",
                            "Setup, terminal and update events appear here. PayNux v" + vn + ".");
                } else {
                    addNotif("ok", "App updated",
                            "PayNux updated from v" + lastVn + " to v" + vn + ".");
                }
                p.edit().putInt("last_version_code", vc)
                        .putString("last_version_name", vn).apply();
            }
        } catch (Throwable ignored) { }
    }

    private static LinearLayout.LayoutParams wrapLp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private View notifRow(org.json.JSONObject o, boolean unread) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));

        View strip = new View(this);
        strip.setBackgroundColor(unread ? ACCENT_RED : Color.TRANSPARENT);
        row.addView(strip, new LinearLayout.LayoutParams(
                dp(3), LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10), 0, 0, 0);

        String kind = o.optString("k", "info");
        int dot;
        if ("ok".equals(kind)) dot = Palette.GREEN;
        else if ("warn".equals(kind)) dot = Palette.AMBER;
        else if ("err".equals(kind)) dot = ACCENT_RED;
        else dot = CYAN_COLOR;

        TextView title = new TextView(this);
        SpannableString ts = new SpannableString("\u25CF  " + o.optString("h", ""));
        ts.setSpan(new ForegroundColorSpan(dot), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(ts);
        title.setTextColor(Palette.TEXT);
        title.setTextSize(14);
        title.setTypeface(Typeface.create(monoTypeface(), Typeface.BOLD));
        box.addView(title, wrapLp());

        TextView msg = new TextView(this);
        msg.setText(o.optString("m", ""));
        msg.setTextColor(Palette.TEXT2);
        msg.setTextSize(12);
        msg.setTypeface(Typeface.create(monoTypeface(), Typeface.NORMAL));
        box.addView(msg, wrapLp());

        TextView when = new TextView(this);
        when.setText(notifAgo(o.optLong("t", 0)));
        when.setTextColor(Palette.DIM);
        when.setTextSize(11);
        when.setTypeface(Typeface.create(monoTypeface(), Typeface.NORMAL));
        box.addView(when, wrapLp());

        row.addView(box, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void showNotificationCenter() {
        final org.json.JSONArray items;
        synchronized (NOTIF_LOCK) { items = notifLoad(); }

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(4), dp(16), dp(4));

        int shown = 0;
        for (int i = items.length() - 1; i >= 0; i--) {
            org.json.JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            list.addView(notifRow(o, o.optInt("r", 0) == 0));
            shown++;
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("No notifications yet.\nSetup, terminal and update events will appear here.");
            empty.setTextColor(Palette.TEXT2);
            empty.setTextSize(13);
            empty.setTypeface(Typeface.create(monoTypeface(), Typeface.NORMAL));
            empty.setPadding(0, dp(16), 0, dp(16));
            list.addView(empty, wrapLp());
        }

        // Dibuka = dianggap terbaca; item baru tetap ditandai garis merah.
        notifMarkAllRead();
        refreshBell();

        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Notifications")
                .setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Clear all", (d, w) -> {
                    notifClear();
                    refreshBell();
                })
                .show();
    }

    // ============================================================
    // TEMA WARNA TERMINAL
    // Urutan kolom: latar terminal, latar baris tombol, teks default,
    // latar header, garis header. Berlaku setelah aplikasi dibuka ulang.
    // Palet ANSI 16 warna tidak diubah oleh tema.
    // ============================================================

    private static final String[] THEME_NAMES = {
            "PayNux", "Dracula", "Solarized Dark", "Gruvbox Dark"};
    private static final int[][] THEMES = {
            {Palette.BG, Palette.PANEL, Palette.TEXT, Palette.PANEL, Palette.LINE},
            {0xFF282A36, 0xFF21222C, 0xFFF8F8F2, 0xFF21222C, 0xFF44475A},
            {0xFF002B36, 0xFF073642, 0xFF93A1A1, 0xFF073642, 0xFF0F4A58},
            {0xFF282828, 0xFF1D2021, 0xFFEBDBB2, 0xFF1D2021, 0xFF3C3836},
    };
    private int themeIdx = 0;

    private int themeIndex() {
        int i = getSharedPreferences("paynux_ui", MODE_PRIVATE).getInt("theme", 0);
        return (i < 0 || i >= THEMES.length) ? 0 : i;
    }

    private void showThemeMenu(View anchor) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Theme");
        pm.getMenu().add(0, 2, 1, "About");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 2) showAbout(); else showThemePicker();
            return true;
        });
        pm.show();
    }

    /** Segarkan About tiap sekian ms selama dialog terbuka (0 = tampil statis saja). */
    private static final int ABOUT_REFRESH_MS = 2000;

    private void showAbout() {
        String distro = null;
        try {
            distro = AboutInfo.prettyName(
                    readText(new File(ubuntuDir, "etc/os-release")));
        } catch (Throwable ignored) { }
        final String distroFinal = distro;

        final TextView tv = new TextView(this);
        tv.setTypeface(monoTypeface());
        tv.setTextSize(11);
        tv.setTextColor(Palette.TEXT);
        tv.setText(renderAbout(AboutInfo.rows(collectAboutData(distroFinal))));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setPadding(dp(24), dp(20), dp(24), dp(4));
        sv.addView(tv);

        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setView(sv)
                .setPositiveButton("OK", null)
                .create();

        final android.os.Handler h =
                new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!dlg.isShowing()) return;
                tv.setText(renderAbout(
                        AboutInfo.rows(collectAboutData(distroFinal))));
                h.postDelayed(this, ABOUT_REFRESH_MS);
            }
        };
        dlg.setOnDismissListener(d -> h.removeCallbacks(tick));
        dlg.show();
        if (ABOUT_REFRESH_MS > 0) h.postDelayed(tick, ABOUT_REFRESH_MS);
    }

    /** Kumpulkan info HP (tanpa izin khusus). Nilai yang gagal dibaca dibiarkan kosong ("-"). */
    private AboutInfo.Data collectAboutData(String distro) {
        AboutInfo.Data d = new AboutInfo.Data();
        d.distro = distro;
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            d.appVersionName = pi.versionName;
            d.appVersionCode = pi.versionCode;
        } catch (Throwable ignored) { }
        try {
            d.manufacturer = android.os.Build.MANUFACTURER;
            d.model = android.os.Build.MODEL;
            d.androidRelease = android.os.Build.VERSION.RELEASE;
            d.sdkInt = android.os.Build.VERSION.SDK_INT;
            d.securityPatch = android.os.Build.VERSION.SECURITY_PATCH;
            d.kernel = System.getProperty("os.version");
            d.cpuCores = Runtime.getRuntime().availableProcessors();
            if (android.os.Build.SUPPORTED_ABIS.length > 0) {
                d.abi = android.os.Build.SUPPORTED_ABIS[0];
            }
        } catch (Throwable ignored) { }
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi =
                    new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            d.ramTotalBytes = mi.totalMem;
            d.ramAvailBytes = mi.availMem;
        } catch (Throwable ignored) { }
        try {
            android.os.StatFs sf = new android.os.StatFs(
                    android.os.Environment.getDataDirectory().getPath());
            d.storageTotalBytes = sf.getTotalBytes();
            d.storageFreeBytes = sf.getAvailableBytes();
        } catch (Throwable ignored) { }
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            d.screenW = dm.widthPixels;
            d.screenH = dm.heightPixels;
            d.dpi = dm.densityDpi;
        } catch (Throwable ignored) { }
        try {
            android.content.Intent b = registerReceiver(null,
                    new android.content.IntentFilter(
                            android.content.Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) d.batteryPercent = level * 100 / scale;
                int st = b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                d.charging = Boolean.valueOf(
                        st == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                                || st == android.os.BatteryManager.BATTERY_STATUS_FULL);
            }
        } catch (Throwable ignored) { }
        d.uptimeMs = android.os.SystemClock.elapsedRealtime();
        Sess s = active;
        if (s != null && s.emu != null) {
            d.termRows = s.emu.getRows();
            d.termCols = s.emu.getCols();
        }
        return d;
    }

    /** Gaya aplikasi: monospace, label cyan, nilai putih, judul bagian redup. */
    private CharSequence renderAbout(java.util.List<AboutInfo.Row> rows) {
        final int dim = Palette.DIM;
        int w = AboutInfo.labelWidth(rows);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            AboutInfo.Row r = rows.get(i);
            int start = sb.length();
            switch (r.kind) {
                case TITLE:
                    sb.append(r.value);
                    styleSpan(sb, start, sb.length(), CYAN_COLOR, 14, true);
                    break;
                case SUBTITLE:
                    sb.append(r.value);
                    styleSpan(sb, start, sb.length(), dim, 10, false);
                    break;
                case SECTION:
                    sb.append(r.value);
                    styleSpan(sb, start, sb.length(), dim, 9, true);
                    break;
                case KV: {
                    sb.append("  ");
                    int ls = sb.length();
                    StringBuilder label = new StringBuilder(r.label);
                    while (label.length() < w) label.append(' ');
                    sb.append(label);
                    styleSpan(sb, ls, sb.length(), CYAN_COLOR, 11, false);
                    int vs = sb.length();
                    sb.append(r.value);
                    styleSpan(sb, vs, sb.length(), Color.WHITE, 11, false);
                    break;
                }
                default:
                    break;
            }
            if (i < rows.size() - 1) sb.append('\n');
        }
        return sb;
    }

    private static void styleSpan(android.text.SpannableStringBuilder sb,
                                  int from, int to, int color, int sizeDp, boolean bold) {
        if (to <= from) return;
        sb.setSpan(new android.text.style.ForegroundColorSpan(color), from, to,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.AbsoluteSizeSpan(sizeDp, true), from, to,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), from, to,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** Judul dialog bergaya aplikasi. */
    private TextView dialogTitleView(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(monoTypeface(), Typeface.BOLD);
        t.setTextSize(14);
        t.setTextColor(CYAN_COLOR);
        t.setPadding(dp(24), dp(20), dp(24), dp(8));
        return t;
    }

    /** Isi dialog bergaya aplikasi. */
    private TextView dialogMessageView(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(monoTypeface());
        t.setTextSize(12);
        t.setTextColor(Palette.TEXT);
        t.setPadding(dp(24), dp(8), dp(24), dp(8));
        return t;
    }

    private void showThemePicker() {
        new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Theme")
                .setSingleChoiceItems(THEME_NAMES, themeIdx, (d, which) -> {
                    getSharedPreferences("paynux_ui", MODE_PRIVATE).edit()
                            .putInt("theme", which).apply();
                    d.dismiss();
                    android.widget.Toast.makeText(this,
                            THEME_NAMES[which] + " theme saved. "
                                    + "Close the app from recent apps and reopen it.",
                            android.widget.Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Ikon 44dp untuk header. */
    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
        lp.setMargins(dp(5), 0, 0, 0);
        return lp;
    }

    /** Chip aksi bulat di header (bubble + ikon berwarna). */
    private android.widget.ImageView headerChip(int res, int fill, int tint,
                                                String desc, View.OnClickListener click) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        iv.setContentDescription(desc);
        android.graphics.drawable.GradientDrawable n = new android.graphics.drawable.GradientDrawable();
        n.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        n.setColor(fill);
        android.graphics.drawable.GradientDrawable pr = new android.graphics.drawable.GradientDrawable();
        pr.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        pr.setColor(Palette.KEY_PRESSED);
        pr.setStroke(dp(1), Palette.ORANGE);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pr);
        sl.addState(new int[]{}, n);
        iv.setBackground(sl);
        iv.setClickable(true);
        iv.setOnClickListener(click);
        return iv;
    }

    /**
     * Strip penanda sesi: satu garis slim per sesi (lebar sama). Sesi aktif =
     * garis merah-oranye yang lebih tebal. Ketuk garis = pindah sesi, tahan = rename.
     * "+" kecil = sesi baru; tutup sesi ada di sidebar.
     */
    private void refreshTabs() {
        if (tabsBox == null) return;
        tabsBox.removeAllViews();
        for (final Sess sc : sessions) {
            final boolean act = sc == active;
            android.widget.FrameLayout cell = new android.widget.FrameLayout(this);
            View line = new View(this);
            android.graphics.drawable.GradientDrawable g;
            if (act) {
                g = new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{Palette.RED_FILL, Palette.ORANGE});
            } else {
                g = new android.graphics.drawable.GradientDrawable();
                g.setColor(Palette.SEG);
            }
            g.setCornerRadius(dp(2));
            line.setBackground(g);
            cell.addView(line, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    dp(act ? 3 : 2), Gravity.CENTER_VERTICAL));
            cell.setOnClickListener(v -> { if (sessions.contains(sc)) activate(sc); });
            cell.setOnLongClickListener(v -> { showRenameSession(sc); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            tabsBox.addView(cell, lp);
        }
        // "+" kecil di ujung kanan = sesi baru
        TextView plus = new TextView(this);
        plus.setText("+");
        plus.setTextSize(14);
        plus.setTextColor(Palette.ORANGE);
        plus.setTypeface(Typeface.create(monoTypeface(), Typeface.BOLD));
        plus.setIncludeFontPadding(false);
        plus.setGravity(Gravity.CENTER);
        plus.setContentDescription("New session");
        plus.setOnClickListener(v -> newSession());
        tabsBox.addView(plus, new LinearLayout.LayoutParams(
                dp(30), LinearLayout.LayoutParams.MATCH_PARENT));
    }

    private android.widget.ImageView headerIcon(int resId) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageResource(resId);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        iv.setPadding(dp(10), dp(10), dp(10), dp(10));
        return iv;
    }

    private final android.os.Handler repeatHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private boolean isRepeatable(String label) {
        return label.equals("\u2190") || label.equals("\u2191")
                || label.equals("\u2193") || label.equals("\u2192")
                || label.equals("PGUP") || label.equals("PGDN");
    }

    /**
     * Panel extra-keys berbingkai: tiap 2 baris konfigurasi = 1 halaman.
     * Geser kiri/kanan untuk pindah halaman; titik di bawah = posisi halaman.
     */
    private void buildKeyPanel(LinearLayout bar) {
        java.util.List<java.util.List<String>> rows = loadToolbarRows();
        final int pageCount = Math.max(1, (rows.size() + 1) / 2);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable pbg =
                new android.graphics.drawable.GradientDrawable();
        pbg.setColor(Palette.PANEL_IN);
        pbg.setCornerRadius(dp(12));
        pbg.setStroke(dp(1), Palette.EDGE);
        panel.setBackground(pbg);
        panel.setPadding(dp(3), dp(3), dp(3), dp(3));

        final int[] downState = new int[2];   // [x awal, halaman awal]
        final android.widget.HorizontalScrollView[] hsRef =
                new android.widget.HorizontalScrollView[1];
        final android.widget.HorizontalScrollView hs =
                new android.widget.HorizontalScrollView(this) {
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
                int w = Math.max(1, getWidth());
                int a = ev.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) {
                    downState[0] = (int) ev.getX();
                    downState[1] = Math.round(getScrollX() / (float) w);
                }
                boolean r = super.dispatchTouchEvent(ev);
                if (a == android.view.MotionEvent.ACTION_UP
                        || a == android.view.MotionEvent.ACTION_CANCEL) {
                    int dx = (int) ev.getX() - downState[0];
                    int page = downState[1];
                    if (dx < -dp(40)) page++; else if (dx > dp(40)) page--;
                    final int target = Math.max(0, Math.min(pageCount - 1, page));
                    postDelayed(() -> smoothScrollTo(target * Math.max(1, getWidth()), 0), 30);
                }
                return r;
            }
        };
        hsRef[0] = hs;
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setFillViewport(true);

        final LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        final java.util.List<LinearLayout> pages = new java.util.ArrayList<>();
        for (int pg = 0; pg < pageCount; pg++) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            for (int ri = pg * 2; ri < Math.min(rows.size(), pg * 2 + 2); ri++) {
                LinearLayout row = newToolbarRow();
                for (String tok : rows.get(ri)) addToolbarToken(row, tok);
                page.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(KEY_H_DP)));
            }
            pages.add(page);
            strip.addView(page, new LinearLayout.LayoutParams(
                    dp(300), LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        hs.addView(strip, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        // lebar tiap halaman = lebar panel
        hs.addOnLayoutChangeListener((v, a1, a2, a3, a4, a5, a6, a7, a8) -> {
            int w = a3 - a1;
            if (w <= 0) return;
            for (LinearLayout pgv : pages) {
                android.view.ViewGroup.LayoutParams lp = pgv.getLayoutParams();
                if (lp.width != w) { lp.width = w; pgv.setLayoutParams(lp); }
            }
        });
        panel.addView(hs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        if (pageCount > 1) {
            final LinearLayout dots = new LinearLayout(this);
            dots.setOrientation(LinearLayout.HORIZONTAL);
            dots.setGravity(Gravity.CENTER);
            final View[] dotViews = new View[pageCount];
            for (int i = 0; i < pageCount; i++) {
                dotViews[i] = new View(this);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(5), dp(5));
                dlp.setMargins(dp(3), 0, dp(3), 0);
                dots.addView(dotViews[i], dlp);
            }
            final Runnable paintDots = () -> {
                int w = Math.max(1, hs.getWidth());
                int cur = Math.min(pageCount - 1, Math.max(0, Math.round(hs.getScrollX() / (float) w)));
                for (int i = 0; i < dotViews.length; i++) {
                    android.graphics.drawable.GradientDrawable g =
                            new android.graphics.drawable.GradientDrawable();
                    g.setCornerRadius(dp(3));
                    g.setColor(i == cur ? Palette.ORANGE : Palette.DOT);
                    dotViews[i].setBackground(g);
                    LinearLayout.LayoutParams lp =
                            (LinearLayout.LayoutParams) dotViews[i].getLayoutParams();
                    int want = dp(i == cur ? 14 : 5);
                    if (lp.width != want) { lp.width = want; dotViews[i].setLayoutParams(lp); }
                }
            };
            paintDots.run();
            hs.setOnScrollChangeListener((v, sx, sy, ox, oy) -> paintDots.run());
            panel.addView(dots, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(14)));
        }
        bar.addView(panel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** Satu baris toolbar: 7 kolom, lebar sama, tinggi KEY_H_DP. */
    private LinearLayout newToolbarRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        return row;
    }

    /**
     * Tambah satu tombol ke baris toolbar.
     * Ketuk  -> jalur lama (makeKey: klik / auto-repeat / CTRL-ALT lengket).
     * Geser ke atas > SWIPE_UP_DP -> aksi kedua (bila disediakan).
     */
    /** File konfigurasi toolbar di dalam Ubuntu (~/.paynux-keys). */
    private File toolbarConfigFile() {
        return new File(new File(getFilesDir(), "home"), ".paynux-keys");
    }

    /**
     * Baris tombol toolbar. Dibaca dari ~/.paynux-keys (format ala Termux):
     *   extra-keys = [[ESC,TAB,HOME,UP,END,PGUP],[CTRL,ALT,LEFT,DOWN,RIGHT,PGDN],[/,-,|,~,_,$],[=,+,*,&,<,>]]
     * Bila file tidak ada / tidak valid -> default 2x7.
     */
    private java.util.List<java.util.List<String>> loadToolbarRows() {
        java.util.List<java.util.List<String>> def = new java.util.ArrayList<>();
        def.add(java.util.Arrays.asList("ESC", "TAB", "HOME", "UP", "END", "PGUP"));
        def.add(java.util.Arrays.asList("CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN"));
        def.add(java.util.Arrays.asList("/", "-", "|", "~", "_", "$"));
        def.add(java.util.Arrays.asList("=", "+", "*", "&", "<", ">"));
        // default lama (2x7): bila file masih persis default lama -> migrasi ke yang baru
        java.util.List<java.util.List<String>> oldDef = new java.util.ArrayList<>();
        oldDef.add(java.util.Arrays.asList("ESC", "/", "-", "HOME", "UP", "END", "SESS"));
        oldDef.add(java.util.Arrays.asList("TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "KEYBOARD"));
        File f = toolbarConfigFile();
        try {
            if (!f.isFile()) { writeDefaultToolbarConfig(f); return def; }
            String txt = readText(f);
            java.util.List<java.util.List<String>> rows = ToolbarConfig.parse(txt);
            if (rows != null && !rows.isEmpty()) {
                if (rows.equals(oldDef)) { writeDefaultToolbarConfig(f); return def; }
                return rows;
            }
        } catch (Throwable ignored) { }
        return def;
    }

    private void writeDefaultToolbarConfig(File f) {
        try {
            f.getParentFile().mkdirs();
            writeText(f, "# PayNux toolbar - same format as Termux extra-keys.\n"
                    + "# Edit, then reopen the app. Delete this file to restore the defaults.\n"
                    + "# Every 2 rows = 1 page (swipe the key panel left/right).\n"
                    + "# Termux style also works: quoted keys, \\ line breaks, DRAWER = SESS.\n"
                    + "# Keys: ESC TAB CTRL ALT HOME END PGUP PGDN UP DOWN LEFT RIGHT\n"
                    + "#       SESS (session panel) KEYBOARD (\u2328) SPACE ENTER BACKSPACE\n"
                    + "# Any other key is sent as-is (up to 3 letters are shown).\n"
                    + "extra-keys = [[ESC,TAB,HOME,UP,END,PGUP],[CTRL,ALT,LEFT,DOWN,RIGHT,PGDN],[/,-,|,~,_,$],[=,+,*,&,<,>]]\n");
        } catch (Throwable ignored) { }
    }

    /** Parse "[[A,B],[C,D]]" -> [[A,B],[C,D]]; null bila tidak ada baris. */
    static java.util.List<java.util.List<String>> parseToolbarRows(String v) {
        return ToolbarConfig.parseRows(v);
    }

    /** Tambahkan satu token konfigurasi sebagai tombol toolbar. */
    private void addToolbarToken(LinearLayout row, String token) {
        String t = token.trim();
        if (t.isEmpty()) return;
        String up = t.toUpperCase();
        switch (up) {
            case "ESC":   addToolbarKey(row, "ESC", "\u001b", () -> shellWriteControl(3)); return;
            case "TAB":   addToolbarKey(row, "TAB", "\t", () -> shellWriteControl(4)); return;
            case "/":     addToolbarKey(row, "/", "/", () -> shellWrite("\\")); return;
            case "-":     addToolbarKey(row, "-", "-", () -> shellWrite("|")); return;
            case "HOME":  addToolbarKey(row, "HOME", "\u001b[H", null); return;
            case "END":   addToolbarKey(row, "END", "\u001b[F", null); return;
            case "PGUP":  addToolbarKey(row, "PGUP", "\u001b[5~", null); return;
            case "PGDN":  addToolbarKey(row, "PGDN", "\u001b[6~", null); return;
            case "UP":    addToolbarKey(row, "\u2191", "\u001b[A", null); return;
            case "DOWN":  addToolbarKey(row, "\u2193", "\u001b[B", null); return;
            case "LEFT":  addToolbarKey(row, "\u2190", "\u001b[D", null); return;
            case "RIGHT": addToolbarKey(row, "\u2192", "\u001b[C", null); return;
            case "CTRL":  addToolbarKey(row, "CTRL", "", null); return;
            case "ALT":   addToolbarKey(row, "ALT", "", null); return;
            case "SPACE": addToolbarKey(row, "SPC", " ", null); return;
            case "ENTER": addToolbarKey(row, "ENTER", "\r", null); return;
            case "BACKSPACE": case "BS": addToolbarKey(row, "DEL", "\u007f", null); return;
            case "SESS": case "SESSION":
                addToolbarKey(row, "SESS", "", () -> shellWrite("\u001b[5~")); return;
            case "KEYBOARD": case "KBD":
                addToolbarKey(row, "\u2328", "", () -> shellWrite("\u001b[6~")); return;
            default:
                String label = t.length() > 3 ? t.substring(0, 3) : t;
                addToolbarKey(row, label, t, null);
        }
    }

    // ============================================================
    // DRAWER SESI (geser dari tepi kiri, ala Termux)
    // ============================================================

    private void setupDrawer(android.widget.FrameLayout host) {
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x99000000);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        host.addView(drawerScrim, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        drawer = new android.widget.FrameLayout(this);
        drawer.setBackgroundColor(Palette.PANEL);
        drawer.setElevation(dp(8));
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                drawerWidthPx(), android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        drawer.setTranslationX(-drawerWidthPx());
        drawer.setVisibility(View.GONE);
        host.addView(drawer, lp);
    }

    /** Isi drawer dibangun ulang setiap kali dibuka (daftar sesi terbaru). */
    private void fillDrawer() {
        if (drawer == null) return;
        drawer.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        // merek: logo + nama + tagline
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(16), dp(14), dp(16), dp(12));
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.ic_logo_header);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(12), 0, 0, 0);
        TextView nm = new TextView(this);
        SpannableString nmText = new SpannableString("PayNux");
        nmText.setSpan(new ForegroundColorSpan(Palette.TEXT), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        nmText.setSpan(new ForegroundColorSpan(ACCENT_RED), 3, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        nm.setText(nmText);
        nm.setTextSize(18);
        nm.setTypeface(Typeface.create(monoTypeface(), Typeface.BOLD));
        nm.setIncludeFontPadding(false);
        names.addView(nm);
        TextView tg = new TextView(this);
        tg.setText("Linux On Your Pocket");
        tg.setTextColor(Palette.TEXT2);
        tg.setTextSize(11);
        tg.setAllCaps(true);
        tg.setLetterSpacing(0.08f);
        tg.setTypeface(monoTypeface());
        tg.setIncludeFontPadding(false);
        names.addView(tg);
        brand.addView(names);
        box.addView(brand);
        box.addView(drawerLine());

        // daftar sesi
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(buildSessionPanel(this::closeDrawer, false));
        box.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        box.addView(drawerLine());

        // aksi lain (dipindah dari layar utama)
        box.addView(drawerItem("+  New session", Palette.ORANGE, this::newSession));
        box.addView(drawerItem("x  Close session", Palette.RED, () -> { if (active != null) confirmCloseSession(active); }));
        box.addView(drawerItem("~  Reset terminal", Palette.TEXT, this::resetTerminal));
        box.addView(drawerItem("#  Keep alive: " + keepLabel(keepMode())
                + (KeepAliveService.isHeld() ? "  (locked)" : ""),
                KeepAliveService.isHeld() ? Palette.AMBER : Palette.TEXT, this::cycleKeepAlive));

        LinearLayout notif = drawerItemRow("!  Notifications", Palette.TEXT,
                this::showNotificationCenter);
        bellBadge = newBellBadge();
        notif.addView(bellBadge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(18)));
        box.addView(notif);
        refreshBell();

        box.addView(drawerItem("*  Theme", Palette.TEXT, this::showThemePicker));
        box.addView(drawerItem("i  About", Palette.TEXT, this::showAbout));

        drawer.addView(box, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private View drawerLine() {
        View v = new View(this);
        v.setBackgroundColor(Palette.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    /** Baris menu sidebar (tinggi 48dp): ketuk = tutup sidebar lalu jalankan aksi. */
    private LinearLayout drawerItemRow(String label, int color, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), 0, dp(18), 0);
        row.setMinimumHeight(dp(48));
        android.graphics.drawable.StateListDrawable bg =
                new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.ColorDrawable(Palette.PRESSED));
        bg.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        row.setBackground(bg);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14);
        tv.setTextColor(color);
        tv.setTypeface(monoTypeface());
        row.addView(tv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.setClickable(true);
        row.setOnClickListener(v -> { closeDrawer(); action.run(); });
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        return row;
    }

    private LinearLayout drawerItem(String label, int color, Runnable action) {
        return drawerItemRow(label, color, action);
    }

    private TextView newBellBadge() {
        TextView b = new TextView(this);
        b.setTextColor(Color.WHITE);
        b.setTextSize(10);
        b.setTypeface(Typeface.create(monoTypeface(), Typeface.BOLD));
        b.setIncludeFontPadding(false);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(18));
        b.setPadding(dp(4), 0, dp(4), 0);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Palette.RED_FILL);
        bg.setCornerRadius(dp(9));
        b.setBackground(bg);
        b.setVisibility(View.GONE);
        return b;
    }

    /** Lebar drawer: maks 280dp, tapi tidak lebih dari 78% lebar layar. */
    private int drawerWidthPx() {
        int screen = getResources().getDisplayMetrics().widthPixels;
        return Math.min(dp(280), (int) (screen * 0.78f));
    }

    /** Samakan lebar drawer dengan layar saat ini (mis. setelah rotasi). */
    private void syncDrawerWidth() {
        if (drawer == null) return;
        android.view.ViewGroup.LayoutParams lp = drawer.getLayoutParams();
        int w = drawerWidthPx();
        if (lp != null && lp.width != w) {
            lp.width = w;
            drawer.setLayoutParams(lp);
        }
    }

    private void openDrawer() {
        if (drawer == null) return;
        syncDrawerWidth();
        boolean wasHidden = !drawerOpen || drawer.getVisibility() != View.VISIBLE;
        if (wasHidden) fillDrawer();   // segar saat dibuka; jangan saat sudah terbuka
        drawerOpen = true;
        drawer.setVisibility(View.VISIBLE);
        drawerScrim.setVisibility(View.VISIBLE);
        drawer.animate().translationX(0f).setDuration(180).start();
        drawerScrim.animate().alpha(1f).setDuration(180).start();
    }

    private void closeDrawer() {
        if (drawer == null) return;
        drawerOpen = false;
        drawer.animate().translationX(-drawerWidthPx()).setDuration(160)
                .withEndAction(() -> {
                    if (!drawerOpen && drawer != null) drawer.setVisibility(View.GONE);
                }).start();
        drawerScrim.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> {
                    if (!drawerOpen && drawerScrim != null) drawerScrim.setVisibility(View.GONE);
                }).start();
    }

    /** Geser drawer mengikuti jari: 0 = terbuka, -lebar = tertutup. */
    private void dragDrawer(float dx) {
        if (drawer == null) return;
        syncDrawerWidth();
        int w = drawerWidthPx();
        float tx = Math.max(-w, Math.min(0f, -w + dx));
        if (drawer.getChildCount() == 0) fillDrawer();
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(tx);
        drawerScrim.setVisibility(View.VISIBLE);
        drawerScrim.setAlpha((tx + w) / (float) w);
    }

    /**
     * Tepi kiri layar = pembuka drawer. Gestur hanya direbut bila jari mulai
     * di 20dp pertama dan arahnya horizontal, jadi ketukan/geser di terminal
     * tidak terpengaruh.
     */
    private class DrawerFrame extends android.widget.FrameLayout {
        private float downX, downY;
        private boolean edge;
        // ketuk pendek di area terminal -> tampilkan keyboard
        private float tapX, tapY;
        private long tapT;
        private boolean tapCand;

        DrawerFrame(android.content.Context c) { super(c); }

        private boolean isInsideTerminal(float rx, float ry) {
            int[] loc = new int[2];
            terminalScroll.getLocationOnScreen(loc);
            return rx >= loc[0] && rx < loc[0] + terminalScroll.getWidth()
                    && ry >= loc[1] && ry < loc[1] + terminalScroll.getHeight();
        }

        @Override
        public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
            // Ketuk pendek di area terminal = tampilkan keyboard. Event tidak
            // ditelan, jadi seleksi teks (tekan lama) dan scroll tetap jalan.
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    tapCand = ev.getPointerCount() == 1 && !drawerOpen
                            && !actionModeActive && terminalScroll != null
                            && isInsideTerminal(ev.getRawX(), ev.getRawY());
                    tapX = ev.getRawX();
                    tapY = ev.getRawY();
                    tapT = ev.getEventTime();
                    break;
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                case android.view.MotionEvent.ACTION_CANCEL:
                    tapCand = false;
                    break;
                case android.view.MotionEvent.ACTION_UP:
                    if (tapCand && ev.getEventTime() - tapT < 250
                            && Math.abs(ev.getRawX() - tapX) < dp(10)
                            && Math.abs(ev.getRawY() - tapY) < dp(10)
                            && !(emulator != null && emulator.isMouseTracking())) {
                        showKeyboard();
                    }
                    tapCand = false;
                    break;
                default:
                    break;
            }
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    float x = ev.getX();
                    edge = drawerOpen ? x <= drawerWidthPx() : x <= dp(20);
                    downX = x;
                    downY = ev.getY();
                    break;
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (!edge) break;
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (!drawerOpen && dx > dp(10) && Math.abs(dx) > Math.abs(dy)) {
                        dragDrawer(dx);
                        return true;
                    }
                    if (drawerOpen && dx < -dp(10) && Math.abs(dx) > Math.abs(dy)) {
                        dragDrawer(-drawerWidthPx() + dx);
                        return true;
                    }
                    break;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (!edge) break;
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    edge = false;
                    // Ketukan (nyaris tanpa gerakan): bukan gestur drawer.
                    // Dulu cabang ini memanggil openDrawer() -> isi drawer
                    // dibangun ulang -> tombol yang ditekan hilang sebelum klik.
                    if (Math.abs(dx) <= dp(10) && Math.abs(dy) <= dp(10)) break;
                    if (drawerOpen) {
                        if (dx < -dp(70)) closeDrawer(); else openDrawer();
                    } else if (dx > dp(90)) {
                        openDrawer();
                    } else if (drawer != null && drawer.getVisibility() == View.VISIBLE) {
                        closeDrawer();
                    }
                    if (Math.abs(dx) > dp(10)) return true;
                    break;
                }
            }
            return super.dispatchTouchEvent(ev);
        }
    }

    private void addToolbarKey(LinearLayout row, String label, String seq,
                               final Runnable second) {
        Button btn = makeKey(label, seq);
        View cell = (second == null) ? btn : wrapSwipeKey(btn, second);
        if (label.equals("SESS") && cell instanceof android.view.ViewGroup) {
            buildSessionIcon((android.view.ViewGroup) cell, btn);
        }
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        klp.setMargins(dp(3), dp(2), dp(3), dp(2));
        row.addView(cell, klp);
    }

    /**
     * Pembungkus gestur untuk aksi kedua. Event diteruskan apa adanya ke
     * tombol asli (tidak mengubah makeKey), kecuali bila jari digeser naik
     * lebih dari SWIPE_UP_DP: tombol dibatalkan, aksi kedua dijalankan.
     */
    /**
     * Sel tombol yang mendukung aksi kedua: geser ke atas > SWIPE_UP_DP.
     * Deteksi harus lewat onInterceptTouchEvent (ViewGroup tidak punya
     * setOnTouchListener yangdipanggil sebelum anak consume touch), jadi
     * sel ini merebut gestur -> tombol dapat ACTION_CANCEL -> aksi kedua
     * dijalankan pada ACTION_UP.
     */
    private class SwipeCell extends android.widget.FrameLayout {
        private final Runnable second;

        SwipeCell(View child, Runnable second) {
            super(MainActivity.this);
            this.second = second;
            addView(child, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        }

        @Override public boolean onInterceptTouchEvent(
                android.view.MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    swipeDownY = ev.getY();
                    swipeFired = false;
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!swipeFired && swipeDownY - ev.getY() > dp(SWIPE_UP_DP)) {
                        swipeFired = true;
                        requestDisallowInterceptTouchEvent(true);
                        return true;   // rebut gestur -> anak dibatalkan
                    }
                    break;
                case android.view.MotionEvent.ACTION_CANCEL:
                    swipeFired = false;
                    break;
                default:
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent ev) {
            if (!swipeFired) return false;
            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                swipeFired = false;
                if (second != null) second.run();
            }
            return true;
        }
    }

    private View wrapSwipeKey(final View child, final Runnable second) {
        return new SwipeCell(child, second);
    }



    /**
     * Ikon sesi pada key toolbar: tiga garis + badge jumlah sesi.
     * Ditambahkan sebagai child View biasa ke sel (ViewGroup) — bukan
     * onDraw kustom — supaya tergambar selama tombolnya tergambar.
     */
    private void buildSessionIcon(android.view.ViewGroup cell, Button btn) {
        btn.setText("");

        int barW = dp(13), barH = Math.max(2, dp(2)), gap = dp(3);
        for (int i = 0; i < 3; i++) {
            View bar = new View(this);
            bar.setBackgroundColor(CYAN_COLOR);
            android.widget.FrameLayout.LayoutParams blp =
                    new android.widget.FrameLayout.LayoutParams(barW, barH,
                            Gravity.CENTER);
            blp.topMargin = (i - 1) * (barH + gap);
            cell.addView(bar, blp);
        }

        sessBadgeView = new TextView(this);
        sessBadgeView.setText(String.valueOf(sessions.size()));
        sessBadgeView.setTextSize(9);
        sessBadgeView.setTextColor(Palette.BG);
        sessBadgeView.setGravity(Gravity.CENTER);
        sessBadgeView.setIncludeFontPadding(false);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(CYAN_COLOR);
        sessBadgeView.setBackground(bg);
        cell.addView(sessBadgeView, new android.widget.FrameLayout.LayoutParams(
                dp(14), dp(14), Gravity.END | Gravity.BOTTOM));
    }

    /** Bubble tombol: sudut membulat + garis tipis; ditekan = oranye gelap; terkunci (CTRL/ALT) = amber. */
    private android.graphics.drawable.StateListDrawable keyBubble() {
        android.graphics.drawable.StateListDrawable sl =
                new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_selected},
                bubbleShape(Palette.AMBER, Palette.AMBER));
        sl.addState(new int[]{android.R.attr.state_pressed},
                bubbleShape(Palette.KEY_PRESSED, Palette.ORANGE));
        sl.addState(new int[]{}, bubbleShape(Palette.KEY, Palette.LINE));
        return sl;
    }

    private android.graphics.drawable.GradientDrawable bubbleShape(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(8));
        g.setStroke(dp(1), stroke);
        return g;
    }

    /** Tombol pengubah (ESC/TAB/CTRL/ALT) oranye, sisanya putih lembut. */
    private int keyTextColor(String label) {
        switch (label) {
            case "ESC": case "TAB": case "CTRL": case "ALT": return Palette.ORANGE;
            default: return Palette.TEXT;
        }
    }

    /** Gaya CTRL/ALT: terkunci = isi amber + teks gelap, normal = bubble biasa. */
    private void setLatchStyle(Button b, boolean on, String label) {
        if (b == null) return;
        b.setSelected(on);
        b.setTextColor(on ? Palette.BG : keyTextColor(label));
        b.setTypeface(Typeface.create(monoTypeface(), on ? Typeface.BOLD : Typeface.NORMAL));
    }

    private Button makeKey(String label, String seq) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(11);
        btn.setTextColor(keyTextColor(label));
        btn.setAllCaps(false);
        btn.setIncludeFontPadding(false);
        btn.setMinHeight(0);
        btn.setMinWidth(0);
        btn.setSingleLine(true);
        btn.setPadding(dp(2), dp(4), dp(2), dp(4));
        btn.setFocusable(false); // jangan curi fokus dari kolom input

        // umpan balik sentuh: gelap saat ditekan
        btn.setBackground(keyBubble());
        btn.setStateListAnimator(null);

        if (label.equals("CTRL")) ctrlKeyBtn = btn;
        if (label.equals("ALT"))  altKeyBtn  = btn;
        if (label.equals("SESS")) { sessBtn = btn; }

        if (isRepeatable(label)) {
            // tahan = ulang otomatis (navigasi histori / kursor)
            final Runnable[] rep = new Runnable[1];
            rep[0] = () -> {
                shellWrite(seq);
                repeatHandler.postDelayed(rep[0], 60);
            };
            btn.setOnTouchListener((v, ev) -> {
                switch (ev.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        shellWrite(seq);
                        disarmLatches();
                        repeatHandler.postDelayed(rep[0], 400);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        repeatHandler.removeCallbacks(rep[0]);
                        return true;
                }
                return true;
            });
            return btn;
        }

        btn.setOnClickListener(v -> {
            switch (label) {
                case "CTRL":
                    ctrlMode[0] = !ctrlMode[0];
                    setLatchStyle(btn, ctrlMode[0], "CTRL");
                    if (ctrlMode[0]) {
                        altMode[0] = false;
                        if (altKeyBtn != null) {
                            setLatchStyle(altKeyBtn, false, "ALT");
                        }
                    }
                    break;
                case "ALT":
                    altMode[0] = !altMode[0];
                    setLatchStyle(btn, altMode[0], "ALT");
                    if (altMode[0]) {
                        ctrlMode[0] = false;
                        if (ctrlKeyBtn != null) {
                            setLatchStyle(ctrlKeyBtn, false, "CTRL");
                        }
                    }
                    break;
                case "^C":
                    shellWriteControl(3);
                    break;
                case "^D":
                    shellWriteControl(4);
                    break;
                case "PASTE":
                    pasteFromClipboard();
                    break;
                case "\u2328":           // KEYBOARD: toggle keyboard virtual
                    toggleKeyboard();
                    break;
                case "NEW":
                    newSession();
                    break;
                case "SESS":
                    openDrawer();
                    break;
                case "COPY":
                    copyAllLog();
                    break;
                default:
                    if (ctrlMode[0] && seq.length() == 1) {
                        shellWriteControl(seq.charAt(0));
                        disarmLatches();
                    } else if (altMode[0] && seq.length() >= 1) {
                        shellWrite("\u001b" + seq);
                        disarmLatches();
                    } else {
                        shellWrite(seq);
                    }
                    break;
            }
        });
        return btn;
    }

    // ============================================================
    // STATUS / BOOT OUTPUT -> EMULATOR (satu jalur render)
    // ============================================================

    private static void appendOutput(String text) {
        // selama setup pertama: semua status ke mini terminal di layar setup,
        // bukan ke terminal utama (terminal penuh baru dibuka setelah selesai)
        if (setupActive) { setupMirror(text); return; }
        if (emulator == null || text == null) return;
        text = colorizeStatusLines(text);
        // status boot memakai \n telanjang; terminal butuh \r\n agar
        // kolom kembali ke 0 (semantik ONLCR yang dijalankan tty asli)
        emulator.feed(text.replace("\r\n", "\n").replace("\n", "\r\n"));
    }

    /**
     * Warnai baris status aplikasi: [*] dan [+] redup (dim), [!] kuning.
     * Baris yang sudah mengandung escape (mis. pesan izin) dibiarkan.
     * Diterapkan di satu tempat supaya baris baru otomatis ikut berwarna.
     */
    private static String colorizeStatusLines(String text) {
        if (text.indexOf('[') < 0) return text;
        String[] lines = text.split("\n", -1);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.indexOf('\u001b') >= 0) continue;
            String col = null;
            // Log boot: redup (dim) seperti baris motto, error tetap kuning.
            if (l.startsWith("[*] ")) col = "\u001b[38;5;240m";
            else if (l.startsWith("[+] ")) col = "\u001b[38;5;240m";
            else if (l.startsWith("[!] ")) col = "\u001b[1;33m";
            if (col != null) {
                lines[i] = col + l + "\u001b[0m";
                changed = true;
            }
        }
        return changed ? String.join("\n", lines) : text;
    }

    // ============================================================
    // CLEAR (clear = escape sequence asli dari shell/tools)
    // ============================================================

    private void clearTerminal() {
        if (emulator != null) {
            emulator.feed("\u001b[H\u001b[2J\u001b[3J");
            scheduleRender();
            // layar lokal sudah kosong; minta shell menggambar ulang prompt
            // (Ctrl+L = clear-screen readline) supaya prompt langsung muncul
            if (session != null && shellReady) session.write("\u000c");
        }
    }

    // ============================================================
    // RENDER: emulator cell grid -> Spannable TextView
    // ============================================================

    private static final int CYAN_COLOR   = Palette.ORANGE; // nama lama dipertahankan; kini oranye
    /** Merah aksen (teks/strip). Isi tombol/badge pakai Palette.RED_FILL. */
    private static final int ACCENT_RED   = Palette.RED;
    private static final int YELLOW_COLOR = Palette.AMBER;

    private Button ctrlKeyBtn;
    private Button altKeyBtn;
    private long renderedScrollStart = 0;   // nomor global baris pertama yang tampil
    private long renderedScrollEnd = 0;     // nomor global akhir (monotonik)
    private int renderedScrollCount = 0;    // jumlah baris yang tampil
    private long syncedFirst = -1;          // firstIdx emulator saat sync terakhir
    private final java.util.ArrayList<TerminalEmulator.Line> scrollSnapshot =
            new java.util.ArrayList<>();

    // View hanya menyimpan jendela riwayat terakhir: menyalin seluruh
    // scrollback (4000 baris ~ 324k karakter) ke TextView tiap frame adalah
    // sumber utama jank. Riwayat penuh tetap di emulator untuk "Salin log".
    private static final int MAX_VIEW_HISTORY_LINES = 600;
    private static final int VIEW_HISTORY_SLACK = 200;

    /** Batas riwayat saat sistem menekan memori (onTrimMemory/onLowMemory). */
    private static final int TRIM_KEEP_LINES = 500;

    private final Runnable renderRunnable = () -> {
        renderPending = false;
        renderScreen();
        syncScrollback();
        if (userAtBottom) {
            terminalScroll.post(this::scrollToBottom);
        }
    };

    /**
     * Gulir ke dasar TANPA fullScroll(): fullScroll memindahkan fokus
     * ke TextView terminal -> fokus bolak-balik dengan kolom input
     * (focus guard) -> keyboard/IME restart -> layar "bergerak-gerak".
     */
    private void scrollToBottom() {
        if (terminalScroll == null) return;
        View child = terminalScroll.getChildAt(0);
        if (child == null) return;
        int y = Math.max(0, child.getHeight() - terminalScroll.getHeight());
        if (terminalScroll.getScrollY() != y) terminalScroll.scrollTo(0, y);
    }

    private void scheduleRender() {
        if (screenView == null) return;
        cursorBlinkOn = true; // ada aktivitas -> kursor solid dulu
        if (renderPending) return;
        renderPending = true;
        screenView.post(renderRunnable);
        // watchdog: bila runnable utama tidak pernah dieksekusi
        // (antrean frame berhenti), paksa render setelah 400ms agar
        // tampilan tidak membeku pada status boot lama
        screenView.postDelayed(() -> {
            if (renderPending) {
                renderPending = false;
                renderRunnable.run();
            }
        }, 400);
    }

    private final BackgroundColorSpan cursorSpan =
            new BackgroundColorSpan(CURSOR_COLOR);
    private int cursorOffset = -1;

    /** Pasang/lepas blok kursor langsung di teks yang sudah tampil
     *  (tanpa setText -> tanpa relayout/gerak saat kursor berkedip). */
    private void applyCursorSpan() {
        if (screenView == null) return;
        CharSequence cs = screenView.getText();
        if (!(cs instanceof android.text.Spannable)) return;
        android.text.Spannable sp = (android.text.Spannable) cs;
        sp.removeSpan(cursorSpan);
        if (cursorBlinkOn && cursorOffset >= 0 && cursorOffset < sp.length()) {
            sp.setSpan(cursorSpan, cursorOffset, cursorOffset + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void renderScreen() {
        if (emulator == null || screenView == null) return;

        // Pertahankan buffer saat pengguna sedang menyeleksi teks.
        if (actionModeActive && screenView.hasSelection()) return;

        // Perbarui cache riwayat sebelum menyusun transcript.
        syncScrollback();

        SpannableStringBuilder all = new SpannableStringBuilder();
        if (scrollbackView != null) {
            all.append(scrollbackView.getText());
        }

        int rows = emulator.getRows();
        boolean curVis = emulator.isCursorVisible();
        int curRow = emulator.getCursorRow();
        int curCol = emulator.getCursorCol();
        int activeCursorOffset = -1;

        for (int r = 0; r < rows; r++) {
            if (r > 0) all.append('\n');

            int lineStart = all.length();
            TerminalEmulator.Line ln = emulator.getScreenLine(r);
            boolean isCur = curVis && r == curRow;
            all.append(styleLine(ln, isCur ? curCol : -1, false));

            if (isCur && ln != null) {
                int n = 0;
                for (int i = 0; i < curCol && i < ln.text.length; i++) {
                    if (ln.text[i] != 0) n++;
                }
                activeCursorOffset = lineStart + n;
            }
        }

        // lineStart di dalam 'all' sudah ABSOLUT (scrollback + layar),
        // jadi historyLength tidak boleh ditambah lagi -- kalau ditambah,
        // span kursor tertempat jauh di luar teks dan tidak terlihat.
        cursorOffset = activeCursorOffset;

        screenView.setText(all, TextView.BufferType.SPANNABLE);
        applyCursorSpan();
    }

    private SpannableStringBuilder styleLine(TerminalEmulator.Line line,
                                             int cursorCol) {
        return styleLine(line, cursorCol, true);
    }

    private SpannableStringBuilder styleLine(TerminalEmulator.Line line,
                                             int cursorCol,
                                             boolean paintCursor) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (line == null) return b;
        int end = line.text.length;
        while (end > 0 && line.text[end - 1] == ' '
                && line.fg[end - 1] == TerminalEmulator.DEFAULT_COLOR
                && line.bg[end - 1] == TerminalEmulator.DEFAULT_COLOR
                && line.attrs[end - 1] == 0) end--;
        if (cursorCol >= 0 && cursorCol >= end) end = Math.min(cursorCol + 1, line.text.length);

        int i = 0;
        while (i < end) {
            if (line.text[i] == 0) { i++; continue; }
            int j = i;
            int fg = line.fg[i], bg = line.bg[i], at = line.attrs[i];
            while (j < end && line.fg[j] == fg && line.bg[j] == bg
                    && line.attrs[j] == at && line.text[j] != 0) j++;
            int start = b.length();
            b.append(new String(line.text, i, j - i));

            if (paintCursor && cursorCol >= i && cursorCol < j) {
                b.setSpan(new BackgroundColorSpan(CURSOR_COLOR),
                        start + (cursorCol - i), start + (cursorCol - i) + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // Emulator menyimpan warna 0xRRGGBB TANPA alpha; span Android
            // butuh ARGB. Tanpa 0xFF000000 teks berwarna jadi transparan
            // (banner "gelap", ls --color tak terlihat).
            boolean hasFg, hasBg;
            int fgc, bgc;
            if ((at & TerminalEmulator.ATTR_REVERSE) != 0) {
                hasFg = true;
                fgc = (bg == TerminalEmulator.DEFAULT_COLOR)
                        ? THEMES[themeIdx][0] : (bg | 0xFF000000);
                hasBg = true;
                bgc = (fg == TerminalEmulator.DEFAULT_COLOR)
                        ? THEMES[themeIdx][2] : (fg | 0xFF000000);
            } else {
                hasFg = fg != TerminalEmulator.DEFAULT_COLOR;
                fgc = fg | 0xFF000000;
                hasBg = bg != TerminalEmulator.DEFAULT_COLOR;
                bgc = bg | 0xFF000000;
            }
            if (hasFg)
                b.setSpan(new ForegroundColorSpan(fgc), start, b.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (hasBg)
                b.setSpan(new BackgroundColorSpan(bgc), start, b.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if ((at & TerminalEmulator.ATTR_BOLD) != 0)
                b.setSpan(new StyleSpan(Typeface.BOLD), start, b.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if ((at & TerminalEmulator.ATTR_ITALIC) != 0)
                b.setSpan(new StyleSpan(Typeface.ITALIC), start, b.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if ((at & TerminalEmulator.ATTR_UNDERLINE) != 0)
                b.setSpan(new UnderlineSpan(), start, b.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = j;
        }
        return b;
    }

    /**
     * Sinkronkan scrollback. Ambil snapshot dalam satu lock (thread PTY terus
     * menambah baris -> iterasi langsung memicu ConcurrentModificationException).
     * Baris yang sudah dibuang cukup dipotong dari depan view; rebuild penuh
     * hanya kalau keadaan view tidak lagi sinkron.
     */
    private void syncScrollback() {
        if (emulator == null || scrollbackView == null) return;
        if (actionModeActive && scrollbackView.hasSelection()) return;

        // Alt-screen (nano/vim/htop): seperti Termux, riwayat tidak ditampilkan
        // dan layar tidak bisa digeser ke atas.
        if (emulator.isAltScreen()) {
            if (!altScreenShown) {
                altScreenShown = true;
                scrollbackView.setText("");
                renderedScrollStart = 0;
                renderedScrollCount = 0;
                renderedScrollEnd = -1;
            }
            if (!userAtBottom) {
                userAtBottom = true;
                if (terminalScroll != null) terminalScroll.post(this::scrollToBottom);
            }
            return;
        }
        if (altScreenShown) {          // keluar alt-screen -> gambar ulang riwayat
            altScreenShown = false;
            scrollbackView.setText("");
            renderedScrollStart = 0;
            renderedScrollCount = 0;
            renderedScrollEnd = -1;
        }

        long firstIdx = emulator.snapshotScrollback(scrollSnapshot);
        int total = scrollSnapshot.size();
        long endIdx = firstIdx + total;
        if (endIdx == renderedScrollEnd && firstIdx == syncedFirst) return;  // tidak ada perubahan
        syncedFirst = firstIdx;

        long viewEnd = renderedScrollStart + renderedScrollCount;
        long newLines = endIdx - viewEnd;
        boolean rebuild = renderedScrollCount == 0
                || renderedScrollStart < firstIdx     // baris di view sudah dibuang emulator
                || viewEnd != renderedScrollEnd       // view tidak berakhir tepat di data lama
                || renderedScrollCount + newLines
                        > MAX_VIEW_HISTORY_LINES + VIEW_HISTORY_SLACK;

        if (rebuild) {
            // Jendela dibatasi: sisanya tetap di emulator untuk "Salin log".
            int keep = Math.min(total, MAX_VIEW_HISTORY_LINES);
            SpannableStringBuilder all = new SpannableStringBuilder();
            for (int i = total - keep; i < total; i++) {
                all.append(styleLine(scrollSnapshot.get(i), -1));
                all.append('\n');
            }
            scrollbackView.setText(all, TextView.BufferType.SPANNABLE);
            renderedScrollStart = endIdx - keep;
            renderedScrollCount = keep;
        } else if (newLines > 0) {
            SpannableStringBuilder add = new SpannableStringBuilder();
            for (long g = viewEnd; g < endIdx; g++) {
                int idx = (int) (g - firstIdx);
                if (idx < 0 || idx >= total) continue;
                add.append(styleLine(scrollSnapshot.get(idx), -1));
                add.append('\n');
            }
            if (add.length() > 0) scrollbackView.append(add);
            renderedScrollCount += (int) newLines;
        }

        renderedScrollEnd = endIdx;
    }

    /** Hitung rows/cols dari ukuran layar & font; resize bila berubah. */
    private void measureTerminal() {
        if (emulator == null || terminalScroll == null || screenView == null)
            return;
        int vw = terminalScroll.getWidth()
                - screenView.getPaddingLeft() - screenView.getPaddingRight();
        int vh = terminalScroll.getHeight()
                - screenView.getPaddingTop() - screenView.getPaddingBottom();
        if (vw <= 0 || vh <= 0) return;
        float charW = screenView.getPaint().measureText("M");
        int lineH = screenView.getLineHeight();
        if (charW <= 0 || lineH <= 0) return;
        int cols = Math.max(20, (int) (vw / charW));
        int rows = Math.max(4, (int) (vh / lineH));
        if (rows == lastRows && cols == lastCols) return;
        lastRows = rows;
        lastCols = cols;
        emulator.resize(rows, cols);
        if (session != null) session.resize(rows, cols);
        // Isi sudah dibungkus ulang oleh emulator (atau baris/kolom berubah):
        // buang hasil render lama supaya log digambar ulang dengan lebar baru.
        renderedScrollStart = 0;
        renderedScrollCount = 0;
        renderedScrollEnd = -1;
        if (scrollbackView != null) scrollbackView.setText("");
        scheduleRender();
    }

    // ============================================================
    // MULTI SESI
    // ============================================================

    private static void feedTo(TerminalEmulator e, String text) {
        if (e == null || text == null) return;
        e.feed(text.replace("\r\n", "\n").replace("\n", "\r\n"));
    }

    /** Render di Activity yang sedang tampil; bila tidak ada, state tetap terkumpul di emulator. */
    private static void uiRender() {
        final MainActivity a = current;
        if (a != null) a.scheduleRender();
    }

    private static void uiPost(Runnable r) {
        final MainActivity a = current;
        if (a != null) a.runOnUiThread(r);
    }

    private static Sess createSess() {
        final Sess sc = new Sess(nextSessId++);
        sc.emu = new TerminalEmulator(24, 80, new TerminalEmulator.Listener() {
            @Override public void onScreenChanged() { if (sc == active) uiRender(); }
            @Override public void onScrollbackChanged() { if (sc == active) uiRender(); }
            @Override public void onBell() { vibrateBell(); }
            @Override public void onResponse(String data) {
                if (sc.ts != null) sc.ts.write(data);
            }
        });
        sc.emu.setCommandListener(new TerminalEmulator.CommandListener() {
            @Override public void onCommandDone(int exit, long seconds, String cmd) {
                onCommandFinished(sc, exit, seconds, cmd);
            }
            @Override public void onBusy(boolean busy) {
                if (busy && !sc.busy) sc.busyAt = android.os.SystemClock.elapsedRealtime();
                sc.busy = busy;
                keepKick();
            }
        });
        return sc;
    }

    private static long lastDoneNotifMs;
    private static int doneNotifSeq;

    /**
     * A long command finished (reported by the shell hook). Always recorded in
     * the in-app bell list; a system notification is posted only when PayNux is
     * in the background, at most one every 3 seconds.
     */
    private static void onCommandFinished(Sess sc, int exit, long seconds, String cmd) {
        String title = CommandDone.title(exit, cmd);
        String text = CommandDone.text(exit, seconds, sessionTitle(sc));
        addNotif(exit == 0 ? "ok" : "warn", title, text);
        if (appVisible || appCtx == null) return;
        long now = System.currentTimeMillis();
        synchronized (MainActivity.class) {
            if (now - lastDoneNotifMs < 3000) return;
            lastDoneNotifMs = now;
        }
        postDoneNotification(appCtx, title, text);
    }

    private static void postDoneNotification(android.content.Context ctx, String title, String text) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            android.app.Notification.Builder b;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new android.app.NotificationChannel(
                        "paynux_done", "Command finished",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT));
                b = new android.app.Notification.Builder(ctx, "paynux_done");
            } else {
                b = new android.app.Notification.Builder(ctx);
            }
            int icon = ctx.getApplicationInfo().icon;
            if (icon == 0) icon = android.R.drawable.sym_def_app_icon;
            android.content.Intent open = new android.content.Intent(ctx, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, 11, open,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            b.setSmallIcon(icon)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis());
            nm.notify(3000 + (doneNotifSeq++ % 20), b.build());
        } catch (Throwable ignored) { }
    }

    private static TerminalSession.Callback callbackFor(final Sess sc) {
        return new TerminalSession.Callback() {
            @Override public void onOutput() {
                if (sc == active) uiRender();
            }
            @Override public void onExit(int code) {
                final boolean wasReady = sc.ready;
                sc.ready = false;
                sc.exited = true;
                sc.busy = false;
                keepKick();
                final boolean killed = (code == 137 || code == -9);   // SIGKILL
                if (wasReady && !sc.closing) {
                    if (killed) {
                        addNotif("warn", "Session killed by Android",
                                sessionTitle(sc) + " got signal 9. Open the terminal for the fix.");
                    } else if (code == 0) {
                        addNotif("info", "Session ended",
                                sessionTitle(sc) + " exited normally.");
                    } else {
                        addNotif("warn", "Session exited",
                                sessionTitle(sc) + " exited with code " + code + ".");
                    }
                }
                feedTo(sc.emu, "\n\n[Ubuntu process exited] code=" + code + "\n");
                if (killed && wasReady && !sc.closing) {
                    feedTo(sc.emu, "[!] Killed by Android (signal 9). Likely the Phantom Process Killer\n"
                            + "    (Android 12+) or low memory. To fix:\n"
                            + "    1. Developer options > Disable child process restrictions (Android 14+)\n"
                            + "    2. Sidebar > Keep alive: set to On (or leave Auto)\n"
                            + "    3. Settings > Apps > Paynux > Battery > Unrestricted\n");
                }
                if (sessions.size() <= 1) {
                    feedTo(sc.emu, "[Tap the session button, long-press this session, then Close session"
                            + " to start a new one]\n");
                }
                if (sc == active) {
                    shellReady = false;
                    uiRender();
                }
                uiPost(() -> {
                    final MainActivity a = current;
                    if (a == null) return;
                    // 'exit' di shell = tutup tab otomatis (bila bukan yang terakhir)
                    if (wasReady && sessions.contains(sc) && sessions.size() > 1) {
                        a.closeSession(sc);
                    } else {
                        a.updateSessionButton();
                    }
                });
            }
            @Override public void onStatus(String line) {
                feedTo(sc.emu, line + "\n");
                if (sc == active) uiRender();
            }
            @Override public void onResponse(String data) {
                if (sc.ts != null) sc.ts.write(data);
            }
            @Override public void onBell() { vibrateBell(); }
        };
    }

    /** Jadikan sesi ini yang tampil di layar (UI thread). */
    private void activate(Sess sc) {
        active = sc;
        emulator = sc.emu;
        session = sc.ts;          // null bila shell sesi baru belum jalan
        shellReady = sc.ready;
        userAtBottom = true;
        actionModeActive = false;

        // bersihkan TOTAL tampilan sesi sebelumnya (teks + seleksi +
        // kursor) sebelum menggambar sesi ini
        cursorOffset = -1;
        renderedScrollStart = 0;
        renderedScrollEnd = 0;
        renderedScrollCount = 0;
        if (scrollbackView != null) {
            scrollbackView.setTextIsSelectable(true);
            scrollbackView.setText("");
            scrollbackView.setTextIsSelectable(true); // SELECT: pertahankan seleksi saat ganti sesi
        }
        if (screenView != null) {
            screenView.setTextIsSelectable(true);
            screenView.setText("");
            screenView.setTextIsSelectable(true); // SELECT: pertahankan seleksi saat ganti sesi
        }
        if (terminalScroll != null) terminalScroll.scrollTo(0, 0);

        lastRows = -1;
        lastCols = -1;
        measureTerminal();

        // gambar sesi ini sekarang juga (jangan menunggu antrean render)
        renderScreen();
        syncScrollback();
        if (terminalScroll != null) terminalScroll.post(this::scrollToBottom);
        scheduleRender();
        updateSessionButton();
        if (hiddenInput != null && sc.ready) hiddenInput.requestFocus();
    }

    private void updateSessionButton() {
        refreshTabs();
        if (sessBadgeView != null) {
            sessBadgeView.setText(String.valueOf(sessions.size()));
        }
        if (sessBtn != null) sessBtn.invalidate();
    }

    /** Tombol NEW: tambah sesi baru (sesi lama tetap hidup di latar). */
    private void newSession() {
        if (restarting) return;
        if (sessions.size() >= MAX_SESSIONS) {
            android.widget.Toast.makeText(this,
                    "Maximum " + MAX_SESSIONS + " sessions",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        restarting = true;
        final Sess sc = createSess();
        sessions.add(sc);
        activate(sc);
        showBanner();
        new Thread(() -> {
            try {
                startUbuntu(sc);
            } catch (Exception e) {
                feedTo(sc.emu, "[PAYNUX ERROR] new session failed: "
                        + e.getClass().getName() + " "
                        + String.valueOf(e.getMessage()) + "\n");
                if (sc == active) scheduleRender();
            } finally {
                restarting = false;
            }
        }, "Paynux-NewSession").start();
    }

    /**
     * Menutup sesi TERAKHIR = mengganti dengan sesi baru (terminal tidak
     * pernah kosong). prepareRuntime() dipanggil ulang (idempotent) supaya
     * boot yang gagal (mis. ekstraksi rootfs) bisa dicoba lagi tanpa
     * menutup aplikasi.
     */
    private void replaceLastSession(final Sess old) {
        if (restarting || bootBusy) {
            android.widget.Toast.makeText(this,
                    "Session is starting, please wait",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        restarting = true;
        old.closing = true;
        try { if (old.ts != null) old.ts.finish(); } catch (Exception ignored) { }
        old.ready = false;
        final Sess sc = createSess();
        sessions.add(sc);
        sessions.remove(old);
        activate(sc);
        showBanner();
        new Thread(() -> {
            try {
                prepareRuntime();
                startUbuntu(sc);
            } catch (Exception e) {
                addNotif("err", "Session failed", notifShort(e));
                feedTo(sc.emu, "\n[PAYNUX ERROR]\n"
                        + e.getClass().getName() + "\n"
                        + String.valueOf(e.getMessage()) + "\n");
                if (sc == active) scheduleRender();
            } finally {
                restarting = false;
            }
        }, "Paynux-ReplaceSession").start();
    }

    private void closeSession(Sess sc) {
        if (!sessions.contains(sc)) return;
        if (sessions.size() <= 1) {
            replaceLastSession(sc);
            return;
        }
        int idx = sessions.indexOf(sc);
        sc.closing = true;
        try { if (sc.ts != null) sc.ts.finish(); } catch (Exception ignored) { }
        sc.ready = false;
        sessions.remove(sc);
        if (sc == active) {
            activate(sessions.get(Math.max(0, Math.min(idx - 1, sessions.size() - 1))));
        } else {
            updateSessionButton();
        }
    }

    private String sessionPreview(Sess sc) {
        try {
            int r = sc.emu.getCursorRow();
            TerminalEmulator.Line ln = sc.emu.getScreenLine(r);
            if (ln == null) return "";
            StringBuilder sb = new StringBuilder();
            for (char ch : ln.text) sb.append(ch == 0 ? ' ' : ch);
            String t = sb.toString().trim();
            return t.length() > 32 ? t.substring(0, 32) : t;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Panel daftar sesi ala Termux: header, daftar sesi (sesi aktif ditandai
     * dengan latar+Warna cyan), pemisah antar baris, lalu baris aksi.
     * Ketuk baris = pindah sesi, tahan baris = konfirmasi tutup.
     * Fungsi yang dipakai (tanpa diubah): activate(), newSession(),
     * copyAllLog(), pasteFromClipboard(), closeSession(), sessionPreview().
     */
    private void showSessionList() {
        final android.app.AlertDialog[] holder = new android.app.AlertDialog[1];
        LinearLayout panel = buildSessionPanel(() -> {
            if (holder[0] != null) holder[0].dismiss();
        });
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(
                this, android.R.style.Theme_Material_Dialog_Alert)
                .setView(panel)
                .create();
        holder[0] = dlg;
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Palette.PANEL));
        }
    }

    /**
     * Isi panel sesi (dipakai dialog maupun drawer geser).
     * @param onDismiss dijalankan sebelum aksi apa pun (tutup dialog/drawer)
     */
    private LinearLayout buildSessionPanel(final Runnable onDismiss) {
        return buildSessionPanel(onDismiss, true);
    }

    private LinearLayout buildSessionPanel(final Runnable onDismiss,
                                           boolean withActions) {
        final List<Sess> snap = new ArrayList<>(sessions);
        final int n = snap.size();

        final int BG_PANEL = Palette.PANEL;
        final int BG_ROW   = Palette.ROW;
        final int FG_DIM   = Palette.DIM;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(BG_PANEL);

        // ---- header ----
        TextView title = new TextView(this);
        title.setText("Sessions");
        title.setTextColor(CYAN_COLOR);
        title.setTextSize(14);
        title.setTypeface(monoTypeface(), Typeface.BOLD);
        title.setPadding(dp(16), dp(14), dp(16), dp(2));
        panel.addView(title);

        TextView hint = new TextView(this);
        hint.setText(n + (n == 1 ? " session" : " sessions")
                + "  \u00B7  tap to switch  \u00B7  hold to close");
        hint.setTextColor(FG_DIM);
        hint.setTextSize(11);
        hint.setTypeface(monoTypeface());
        hint.setPadding(dp(16), 0, dp(16), dp(10));
        panel.addView(hint);

        // ---- daftar sesi ----
        if (n == 0) {
            TextView empty = new TextView(this);
            empty.setText("No sessions");
            empty.setTextColor(FG_DIM);
            empty.setTextSize(11);
            empty.setTypeface(monoTypeface());
            empty.setPadding(dp(16), dp(14), dp(16), dp(18));
            panel.addView(empty);
        }

        for (int i = 0; i < n; i++) {
            final Sess sc = snap.get(i);
            final boolean act = (sc == active);
            String st = sc.exited ? "exited" : (sc.ready ? "running" : "starting");
            String pv = sessionPreview(sc);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(9), dp(16), dp(9));

            android.graphics.drawable.GradientDrawable rowBg =
                    new android.graphics.drawable.GradientDrawable();
            rowBg.setColor(act ? Palette.ROW_ACTIVE : BG_ROW);
            rowBg.setCornerRadius(dp(2));
            if (act) {
                android.graphics.drawable.ColorDrawable bar =
                        new android.graphics.drawable.ColorDrawable(Palette.ORANGE);
                android.graphics.drawable.LayerDrawable ld =
                        new android.graphics.drawable.LayerDrawable(
                                new android.graphics.drawable.Drawable[]{rowBg, bar});
                ld.setLayerWidth(1, dp(3));
                ld.setLayerGravity(1, Gravity.START | Gravity.FILL_VERTICAL);
                row.setBackground(ld);
            } else {
                row.setBackground(rowBg);
            }

            TextView t1 = new TextView(this);
            t1.setText((act ? "\u25B6 " : "  ") + sessionTitle(sc));
            t1.setTextColor(act ? CYAN_COLOR : Palette.TEXT);
            t1.setTextSize(12);
            t1.setTypeface(monoTypeface(),
                    act ? Typeface.BOLD : Typeface.NORMAL);
            row.addView(t1);

            TextView t2 = new TextView(this);
            t2.setText(st + (pv.isEmpty() ? "" : "   " + pv));
            t2.setTextColor(FG_DIM);
            t2.setTextSize(11);
            t2.setTypeface(monoTypeface());
            t2.setSingleLine(true);
            t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(t2);

            row.setOnClickListener(v -> {
                onDismiss.run();
                if (sessions.contains(sc)) activate(sc);
            });
            row.setOnLongClickListener(v -> {
                onDismiss.run();
                showRenameSession(sc);
                return true;
            });
            panel.addView(row);

            View sep = new View(this);
            sep.setBackgroundColor(Palette.LINE);
            panel.addView(sep, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }

        // ---- baris aksi (di drawer dipasang terpisah di bawah) ----
        if (withActions) panel.addView(buildSessionActions(onDismiss));

        return panel;
    }

    /** Baris aksi panel sesi: New, Reset, Close. */
    private LinearLayout buildSessionActions(final Runnable onDismiss) {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(8), dp(6), dp(8), dp(6));
        actions.setBackgroundColor(Palette.PANEL);

        addSessionAction(actions, "New",
                () -> { onDismiss.run(); newSession(); });
        addSessionAction(actions, "Reset",
                () -> { onDismiss.run(); resetTerminal(); });
        addSessionAction(actions, "Close", onDismiss);

        return actions;
    }

    /** Tombol aksi kecil di panel sesi (warna tetap cyan). */
    private void addSessionAction(LinearLayout row, String label,
                                  final Runnable action) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setTextColor(CYAN_COLOR);
        b.setAllCaps(false);
        b.setIncludeFontPadding(false);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setTypeface(monoTypeface());
        b.setFocusable(false);
        android.graphics.drawable.StateListDrawable bg =
                new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.ColorDrawable(Palette.PRESSED));
        bg.addState(new int[]{},
                new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setOnClickListener(v -> action.run());
        row.addView(b, new LinearLayout.LayoutParams(0, dp(36), 1f));
    }

    // ============================================================
    // COPY SELURUH LOG
    // ============================================================

    private String getTranscript() {
        if (emulator == null) return "";
        StringBuilder out = new StringBuilder();
        // snapshot sekali (O(n)); getScrollbackLine(i) O(n) per indeks membuat
        // loop lama O(n^2) — 35 ms untuk 4000 baris.
        java.util.List<TerminalEmulator.Line> lines = new java.util.ArrayList<>();
        emulator.snapshotScrollback(lines);
        for (int i = 0; i < lines.size(); i++) appendLine(out, lines.get(i));
        int rows = emulator.getRows();
        for (int r = 0; r < rows; r++) appendLine(out, emulator.getScreenLine(r));
        int end = out.length();
        while (end > 0 && (out.charAt(end - 1) == '\n' || out.charAt(end - 1) == ' ')) end--;
        return out.substring(0, end);
    }

    private void appendLine(StringBuilder out, TerminalEmulator.Line ln) {
        if (ln == null) { out.append('\n'); return; }
        int e = ln.text.length;
        while (e > 0 && (ln.text[e - 1] == ' ' || ln.text[e - 1] == 0)) e--;
        for (int i = 0; i < e; i++) if (ln.text[i] != 0) out.append(ln.text[i]);
        out.append('\n');
    }

    // id menu seleksi milik aplikasi (harus != id sistem)
    private static final int MENU_ID_PASTE = 0x7f0a0001;
    private static final int MENU_ID_OPEN_URL = 0x7f0a0002;
    private static final int MENU_ID_SELECT_ALL = 0x7f0a0003;

    private static boolean exitLogPruned = false;

    /**
     * Pangkas /root/paynux-exit.log: simpan HANYA baris CRASH_NATIVE,
     * buang duplikat. Exit normal (USER_REQUESTED, PACKAGE_UPDATED,
     * SIGNALED, [REMOVE TASK] ...) bukan crash dan mengotori crash log.
     * Idempoten, dijalankan sekali per proses.
     */
    private void pruneExitLog() {
        if (exitLogPruned) return;
        exitLogPruned = true;
        try {
            File f = new File(new File(getFilesDir(), "home"), "paynux-exit.log");
            if (!f.exists() || f.length() == 0) return;
            java.util.List<String> keep = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(f),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains("reason=CRASH_NATIVE") && seen.add(line))
                        keep.add(line);
                }
            }
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f, false)) {
                for (String s : keep)
                    o.write((s + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) { }
    }

    /** Teks yang sedang dipilih di TextView (null bila tidak ada). */
    private static String selectedText(TextView v) {
        int s = v.getSelectionStart(), e = v.getSelectionEnd();
        if (s < 0 || e <= s || e > v.getText().length()) return null;
        return v.getText().subSequence(s, e).toString().trim();
    }

    /** URL yang disentuh seleksi (seleksi sebagian cukup), atau seluruh seleksi bila itu URL. */
    private static String urlForSelection(TextView v) {
        String url = UrlFinder.urlAround(v.getText(),
                v.getSelectionStart(), v.getSelectionEnd());
        return url != null ? url : asUrl(selectedText(v));
    }

    /** Kenali URL; "example.com" dilengkapi https://. Null bila bukan URL. */
    static String asUrl(String s) {
        if (s == null) return null;
        s = s.trim();
        int sp = s.indexOf(' ');
        if (sp > 0) s = s.substring(0, sp);
        while (s.endsWith(")") || s.endsWith(",") || s.endsWith(".") || s.endsWith(";"))
            s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return null;
        if (s.matches("(?i)^(https?|ftp)://[^\\s]+$")) return s;
        if (s.matches("(?i)^[\\w.-]+\\.[a-z]{2,}(/[^\\s]*)?$")) return "https://" + s;
        return null;
    }

    private void openUrl(String url) {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
        } catch (Throwable t) {
            appendOutput("[!] No app found to open the link.\n");
            scheduleRender();
        }
    }

    private void installCopyAllSelectionAction(TextView view) {
        view.setCustomSelectionActionModeCallback(
                new android.view.ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(
                    android.view.ActionMode mode,
                    android.view.Menu menu) {
                // TextView ini bukan editable, jadi "Tempel" bawaan
                // Android tidak pernah muncul — tambahkan sendiri
                // supaya bisa menempel clipboard ke shell.
                menu.add(android.view.Menu.NONE, MENU_ID_PASTE, 100, "Paste");
                // Termux punya "Select all" di menu seleksi.
                menu.add(android.view.Menu.NONE, MENU_ID_SELECT_ALL, 101, "Select all");
                // Termux: tautan bisa langsung dibuka dari menu seleksi.
                if (urlForSelection(view) != null) {
                    menu.add(android.view.Menu.NONE, MENU_ID_OPEN_URL, 99, "Open link");
                }
                return true;
            }

            @Override
            public boolean onPrepareActionMode(
                    android.view.ActionMode mode,
                    android.view.Menu menu) {
                // Selection may change (handles): add/remove "Open link" to match.
                boolean has = menu.findItem(MENU_ID_OPEN_URL) != null;
                boolean want = urlForSelection(view) != null;
                if (want && !has) {
                    menu.add(android.view.Menu.NONE, MENU_ID_OPEN_URL, 99, "Open link");
                    return true;
                }
                if (!want && has) {
                    menu.removeItem(MENU_ID_OPEN_URL);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onActionItemClicked(
                    android.view.ActionMode mode,
                    android.view.MenuItem item) {
                if (item.getItemId() == android.R.id.copy) {
                    // "Salin" pada menu seleksi = salin HANYA blok yang
                    // dipilih. Menu tetap ditutup dan salin bawaan
                    // dimatikan (return true) agar tidak dobel.
                    copySelectionOrAll(view);
                    mode.finish();
                    return true;
                }
                if (item.getItemId() == MENU_ID_PASTE) {
                    // tempel ke shell, bukan ke TextView (read-only)
                    pasteFromClipboard();
                    mode.finish();
                    return true;
                }
                if (item.getItemId() == MENU_ID_SELECT_ALL) {
                    // android.jar di perangkat ini tidak punya TextView.selectAll()
                    CharSequence t = view.getText();
                    if (t instanceof android.text.Spannable) {
                        android.text.Selection.setSelection(
                                (android.text.Spannable) t, 0, t.length());
                    }
                    return true;
                }
                if (item.getItemId() == MENU_ID_OPEN_URL) {
                    String url = urlForSelection(view);
                    if (url != null) openUrl(url);
                    mode.finish();
                    return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(
                    android.view.ActionMode mode) {
            }
        });
    }

    private static int copyCalls = 0;

    /** Tombol COPY / fallback: salin SELURUH transcript. */
    private void copyAllLog() {
        // Transcript bisa >4000 baris: menyusunnya di UI thread memakan
        // puluhan ms (frame drop saat log sedang ramai). Susun di thread latar,
        // pasang clipboard di UI thread.
        new Thread(() -> {
            final String t = getTranscript();
            runOnUiThread(() -> copyText(t, "full"));
        }, "paynux-copy").start();
    }

    /**
     * "Salin" pada menu seleksi teks: salin hanya potongan yang diblok
     * (view.getSelectionStart()..getSelectionEnd()). Bila tidak ada blok
     * (menu dibuka tanpa seleksi), jatuh kembali ke seluruh transcript.
     */
    private void copySelectionOrAll(TextView view) {
        try {
            int s = view.getSelectionStart();
            int e = view.getSelectionEnd();
            CharSequence cs = view.getText();
            if (s >= 0 && e > s && cs != null && e <= cs.length()) {
                copyText(cs.subSequence(s, e).toString(), "selection");
                return;
            }
        } catch (Throwable ignored) { }
        copyAllLog();
    }

    private void copyText(String t, String mode) {
        copyCalls++;
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder who = new StringBuilder();
            for (int i = 1; i < Math.min(st.length, 4); i++) {
                who.append(st[i].getMethodName()).append(i < 3 ? "<" : "");
            }
            appendHomeLog("paynux-copy.log", new java.util.Date() + " call#" + copyCalls
                    + " mode=" + mode + " len=" + t.length() + " by=" + who + "\n");
        } catch (Throwable ignored) { }
        ClipboardManager cm =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || t.isEmpty()) {
            android.widget.Toast.makeText(this, "Log is empty",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("paynux-log", t));
        // Baca-balik: bukti apa yang BENAR-BENAR tersimpan di clipboard.
        String verified = "unknown";
        try {
            ClipData back = cm.getPrimaryClip();
            String got = (back != null && back.getItemCount() > 0)
                    ? String.valueOf(back.getItemAt(0).coerceToText(this)) : "";
            verified = got.equals(t) ? "match" : "MISMATCH(" + got.length() + ")";
        } catch (Throwable ignored) { }
        try {
            appendHomeLog("paynux-copy.log",
                    "    verify=" + verified + " mode=" + mode + "\n");
        } catch (Throwable ignored) { }
        // Android 13+ sudah menampilkan konfirmasi "disalin" milik sistem;
        // toast sendiri menimbulkan kesan salin dua kali.
        if (android.os.Build.VERSION.SDK_INT < 33) {
            int lines = 1;
            for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '\n') lines++;
            android.widget.Toast.makeText(this,
                    "Log copied (" + lines + " lines)",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Reset tampilan terminal (ala "Reset terminal" Termux): buang scrollback
     * dan layar yang kacau (mis. setelah 'cat' file biner). Shell tetap jalan
     * dan akan menggambar prompt baru pada output berikutnya.
     */
    private void resetTerminal() {
        if (emulator == null) return;
        emulator.reset();
        altScreenShown = false;
        actionModeActive = false;
        renderedScrollStart = 0;
        renderedScrollCount = 0;
        renderedScrollEnd = -1;
        userAtBottom = true;
        if (scrollbackView != null) scrollbackView.setText("");
        if (screenView != null) screenView.setText("");
        scheduleRender();
    }

    private boolean mouseDown = false;

    /** Terjemahkan sentuhan di terminal menjadi laporan mouse untuk shell. */
    private void handleMouseTouch(android.view.MotionEvent ev) {
        if (emulator == null) return;
        boolean sgr = emulator.isMouseSgr();
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN: {
                int[] cell = touchToCell(ev.getX(), ev.getY());
                if (cell == null) return;
                mouseDown = true;
                shellWrite(TerminalEmulator.mouseReport(sgr, 0, cell[0], cell[1], true));
                break;
            }
            case android.view.MotionEvent.ACTION_MOVE: {
                if (!mouseDown) return;
                int[] cell = touchToCell(ev.getX(), ev.getY());
                if (cell == null) return;
                shellWrite(TerminalEmulator.mouseReport(sgr, 32, cell[0], cell[1], true));
                break;
            }
            case android.view.MotionEvent.ACTION_UP: {
                if (!mouseDown) return;
                mouseDown = false;
                int[] cell = touchToCell(ev.getX(), ev.getY());
                if (cell == null) return;
                shellWrite(TerminalEmulator.mouseReport(sgr, 0, cell[0], cell[1], false));
                break;
            }
            case android.view.MotionEvent.ACTION_CANCEL:
                mouseDown = false;
                break;
            default:
                break;
        }
    }

    /** Koordinat sentuhan -> indeks sel (kolom, baris) di layar terminal. */
    private int[] touchToCell(float x, float y) {
        if (screenView == null || terminalScroll == null || emulator == null) return null;
        int[] viewLoc = new int[2];
        screenView.getLocationOnScreen(viewLoc);
        int[] scrollLoc = new int[2];
        terminalScroll.getLocationOnScreen(scrollLoc);
        float fx = x + scrollLoc[0] - viewLoc[0] - screenView.getPaddingLeft();
        float fy = y + scrollLoc[1] - viewLoc[1] - screenView.getPaddingTop();
        float charW = screenView.getPaint().measureText("M");
        int lineH = screenView.getLineHeight();
        if (charW <= 0 || lineH <= 0) return null;
        int col = (int) (fx / charW);
        int row = (int) (fy / lineH);
        col = Math.max(0, Math.min(emulator.getCols() - 1, col));
        row = Math.max(0, Math.min(emulator.getRows() - 1, row));
        return new int[]{col, row};
    }

    private void changeFontSize(int delta) {
        fontSizeSp = Math.max(8, Math.min(24, fontSizeSp + delta));
        if (scrollbackView != null) scrollbackView.setTextSize(fontSizeSp);
        if (screenView != null) screenView.setTextSize(fontSizeSp);
        if (hiddenInput != null)
            hiddenInput.setTextSize(Math.min(fontSizeSp, 16));
        getSharedPreferences("paynux_ui", MODE_PRIVATE).edit()
                .putInt("font_size", fontSizeSp).apply();
        lastRows = -1; // paksa ukur ulang
        if (terminalScroll != null) terminalScroll.post(this::measureTerminal);
    }

    // ============================================================
    // INPUT: keyboard Android -> shell aktif (langsung)
    // ============================================================

    private void shellWrite(String s) {
        if (session == null || !shellReady) {
            appendOutput("[Shell not ready]\n");
            scheduleRender();
            return;
        }
        session.write(s);
    }

    private void shellWriteControl(int c) {
        if (session == null || !shellReady) return;
        session.writeControl(c);
    }

    private void sendEnter() {
        shellWrite("\r");
    }

    private void disarmLatches() {
        if (ctrlMode[0] || altMode[0]) {
            ctrlMode[0] = false;
            altMode[0] = false;
            runOnUiThread(() -> {
                if (ctrlKeyBtn != null) {
                    setLatchStyle(ctrlKeyBtn, false, "CTRL");
                }
                if (altKeyBtn != null) {
                    setLatchStyle(altKeyBtn, false, "ALT");
                }
            });
        }
    }

    /** Dipanggil InputConnection / keyboard: teks masuk ke shell. */
    private void sendToShell(String text) {
        if (text == null || text.length() == 0) return;
        if (session == null || !shellReady) {
            appendOutput("[Shell not ready]\n");
            scheduleRender();
            return;
        }
        if (text.length() == 1) {
            char c = text.charAt(0);
            if (ctrlMode[0] && c >= 0x20 && c < 0x7f) {
                shellWriteControl(c);
                disarmLatches();
                return;
            }
            if (altMode[0]) {
                shellWrite("\u001b" + text);
                disarmLatches();
                return;
            }
        }
        shellWrite(text);
    }

    /**
     * Key event perangkat keras (dan beberapa IME) diteruskan apa
     * adanya ke shell: Enter -> \r, Del -> 0x7f, panah -> CSI.
     */
    /** Kombinasi Volume-Up + tombol -> urutan escape (null = bukan kombinasi). */
    static String volumeKeySequence(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_E: return "\u001b";           // Esc
            case KeyEvent.KEYCODE_T: return "\t";               // Tab
            case KeyEvent.KEYCODE_H: case KeyEvent.KEYCODE_A: return "\u001b[D";  // kiri
            case KeyEvent.KEYCODE_J: case KeyEvent.KEYCODE_S: return "\u001b[B";  // bawah
            case KeyEvent.KEYCODE_K: case KeyEvent.KEYCODE_W: return "\u001b[A";  // atas
            case KeyEvent.KEYCODE_L: case KeyEvent.KEYCODE_D: return "\u001b[C";  // kanan
            case KeyEvent.KEYCODE_1: return "\u001bOP";
            case KeyEvent.KEYCODE_2: return "\u001bOQ";
            case KeyEvent.KEYCODE_3: return "\u001bOR";
            case KeyEvent.KEYCODE_4: return "\u001bOS";
            case KeyEvent.KEYCODE_5: return "\u001b[15~";
            case KeyEvent.KEYCODE_6: return "\u001b[17~";
            case KeyEvent.KEYCODE_7: return "\u001b[18~";
            case KeyEvent.KEYCODE_8: return "\u001b[19~";
            case KeyEvent.KEYCODE_9: return "\u001b[20~";
            case KeyEvent.KEYCODE_0: return "\u001b[21~";
            default: return null;
        }
    }

    private boolean handleHardwareKey(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                sendEnter();
                return true;
            case KeyEvent.KEYCODE_DEL:
                shellWrite("\u007f");
                return true;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                shellWrite("\u001b[3~");
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                shellWrite("\u001b[A");
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                shellWrite("\u001b[B");
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                shellWrite("\u001b[D");
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                shellWrite("\u001b[C");
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                shellWrite("\u001b");
                return true;
            case KeyEvent.KEYCODE_TAB:
                if (ctrlMode[0]) { shellWriteControl(9); disarmLatches(); }
                else shellWrite("\t");
                return true;
            default:
                int uni = event.getUnicodeChar();
                if (uni == 0) return false;
                char c = (char) uni;
                if (ctrlMode[0] && c >= 0x20 && c < 0x7f) {
                    shellWriteControl(c);
                    disarmLatches();
                    return true;
                }
                if (altMode[0]) {
                    shellWrite("\u001b" + c);
                    disarmLatches();
                    return true;
                }
                shellWrite(String.valueOf(c));
                return true;
        }
    }

    /** InputConnection untuk soft keyboard: semua masuk ke shell. */
    private class ShellInputConnection extends BaseInputConnection {
        ShellInputConnection(EditText target) { super(target, true); }

        @Override public boolean commitText(CharSequence text, int newCursorPosition) {
            sendToShell(text == null ? "" : text.toString());
            return true;
        }
        @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
            sendToShell(text == null ? "" : text.toString());
            return true;
        }
        @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            for (int i = 0; i < beforeLength; i++) shellWrite("\u007f");
            return true;
        }
        @Override public boolean sendKeyEvent(KeyEvent event) {
            return handleHardwareKey(event.getKeyCode(), event);
        }
        @Override public boolean performEditorAction(int editorActionId) {
            sendEnter();
            return true;
        }
        @Override public boolean finishComposingText() { return true; }
        @Override public boolean setComposingRegion(int start, int end) { return true; }
    }

    /** Nama yang ditampilkan untuk sebuah sesi. */
    private static String sessionTitle(Sess sc) {
        String n = sc.name;
        return (n == null || n.isEmpty()) ? "Session " + sc.id : n;
    }

    /** Tekan lama sesi = ganti nama (bukan hapus). Kosong = kembali ke "Session N". */
    private void showRenameSession(final Sess sc) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setTypeface(monoTypeface());
        input.setTextSize(12);
        input.setTextColor(Palette.TEXT);
        input.setHintTextColor(Palette.DIM);
        input.setHint("Session " + sc.id);
        input.setText(sc.name == null ? "" : sc.name);
        input.setSelection(input.getText().length());
        new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setCustomTitle(dialogTitleView("Rename session"))
                .setView(input)
                .setPositiveButton("Save", (d, w) ->
                        sc.name = SessionName.clean(input.getText().toString()))
                .setNeutralButton("Close session", (d, w) -> confirmCloseSession(sc))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Menutup sesi butuh dua langkah sadar: tombol di dialog ganti nama + konfirmasi ini. */
    private void confirmCloseSession(final Sess sc) {
        new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert)
                .setCustomTitle(dialogTitleView("Close session"))
                .setView(dialogMessageView("Close " + sessionTitle(sc) + "?"))
                .setPositiveButton("Close", (d, w) -> closeSession(sc))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** True while the active session's app asked for bracketed paste (?2004). */
    private static boolean isBracketedPasteActive() {
        Sess s = active;
        return s != null && s.emu != null && s.emu.isBracketedPaste();
    }

    private void pasteFromClipboard() {
        ClipboardManager cm =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            appendOutput("[clipboard is empty]\n");
            scheduleRender();
            return;
        }
        ClipData d = cm.getPrimaryClip();
        if (d != null && d.getItemCount() > 0) {
            CharSequence t = d.getItemAt(0).coerceToText(this);
            if (t != null) {
                shellWrite(PasteFormatter.format(
                        t.toString(), isBracketedPasteActive()));
            }
        }
    }

    private void showKeyboard() {
        runOnUiThread(() -> {
            if (hiddenInput != null) {
                // pastikan tampil dulu — view GONE tidak bisa fokus,
                // listener insets akan menyembunyikan lagi kalau
                // keyboard benar-benar tidak muncul
                if (hiddenInput.getVisibility() != View.VISIBLE) {
                    hiddenInput.setVisibility(View.VISIBLE);
                    if (keyBar != null) keyBar.setVisibility(View.VISIBLE);
                }
                hiddenInput.requestFocus();
                hiddenInput.postDelayed(() -> {
                    InputMethodManager imm = (InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        // SHOW_FORCED: keyboard terminal harus langsung
                        // siap dipakai saat boot (SHOW_IMPLICIT sering
                        // diabaikan Gboard sebelum ada interaksi user)
                        boolean shown = imm.showSoftInput(hiddenInput,
                                InputMethodManager.SHOW_FORCED);
                        if (!shown) {
                            // percobaan kedua bila window belum siap
                            hiddenInput.postDelayed(() -> {
                                InputMethodManager im2 = (InputMethodManager)
                                        getSystemService(Context.INPUT_METHOD_SERVICE);
                                if (im2 != null) {
                                    im2.showSoftInput(hiddenInput,
                                            InputMethodManager.SHOW_FORCED);
                                }
                            }, 700);
                        }
                    }
                }, 300);
            }
        });
    }

    /**
     * Tombol ⌨ (KEYBOARD) ala Termux: ketuk = toggle keyboard virtual.
     * Isi showKeyboard() tidak diubah — dipakai hanya sebagai jalur "tampilkan".
     */
    private void toggleKeyboard() {
        runOnUiThread(() -> {
            if (hiddenInput == null) return;
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;

            if (isImeVisible()) {
                imm.hideSoftInputFromWindow(hiddenInput.getWindowToken(), 0);
                return;
            }

            // tampilkan: pastikan anchor IME hidup lalu minta fokus
            if (hiddenInput.getVisibility() != View.VISIBLE) {
                hiddenInput.setVisibility(View.VISIBLE);
            }
            hiddenInput.requestFocus();

            // SHOW_FORCED: tetap dipakai walau deprecated (API 31+) karena
            // keyboard hardware/Bluetooth membuat SHOW_IMPLICIT diabaikan.
            boolean shown = imm.showSoftInput(hiddenInput,
                    InputMethodManager.SHOW_FORCED);
            if (!shown) {
                // paksa IME tampil bila showSoftInput ditolak
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            }
        });
    }

    /**
     * Keyboard virtual sedang tampil?
     * API 30+: WindowInsets.Type.ime(); cadangan (API < 30): selisih
     * tinggi layout — tinggi maksimum yang pernah terlihat = tanpa IME.
     */
    private boolean isImeVisible() {
        android.view.View decor = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsets wi = decor.getRootWindowInsets();
            if (wi != null) {
                return wi.isVisible(android.view.WindowInsets.Type.ime());
            }
        }
        int h = decor.getHeight();
        if (h > maxDecorHeightPx) maxDecorHeightPx = h;
        return maxDecorHeightPx > 0 && h < maxDecorHeightPx - dp(100);
    }

    // ============================================================
    // PREPARE RUNTIME
    // ============================================================

    private void prepareRuntime()
            throws Exception {

        File files =
                getFilesDir();

        // --------------------------------------------------------
        // DIRECTORIES
        // --------------------------------------------------------

        ubuntuDir =
                new File(
                        files,
                        "ubuntu"
                );

        /*
         * Since Android 10 (API 29), the OS enforces W^X:
         * a file that the app itself writes into its private
         * storage (getFilesDir()) can never also be executable,
         * no matter what chmod says - exec() on it fails with
         * "Permission denied" (errno 13). That's what was
         * happening here (and would have happened for proot
         * too, the moment it was actually exec'd).
         *
         * The fix is to ship native executables the same way
         * Android ships .so files: under jniLibs/<abi>/ as
         * "lib*.so". The package installer then extracts them
         * into applicationInfo.nativeLibraryDir, a location
         * that IS exec-mounted and is not app-writable, so it
         * satisfies W^X. We run proot straight from there
         * instead of copying it into getFilesDir() first.
         */

        runtimeDir =
                new File(
                        getApplicationInfo()
                                .nativeLibraryDir
                );

        // --------------------------------------------------------
        // PROOT EXECUTABLE
        // --------------------------------------------------------

        prootFile =
                new File(
                        runtimeDir,
                        "libproot.so"
                );

        bootLog(
                "[*] Preparing PRoot...\n"
        );

        if (!prootFile.exists() ||
                prootFile.length() == 0) {

            throw new Exception(
                    "PRoot executable missing: " +
                    prootFile
            );
        }


        // --------------------------------------------------------
        // PROOT LIBRARIES
        // --------------------------------------------------------

        /*
         * libtalloc2.so and libandroid-shmem.so are proot's
         * runtime dependencies. They already live alongside
         * libproot.so in nativeLibraryDir (packaged there the
         * same way), so there is nothing left to copy - just
         * verify they're present.
         */

        String[] libraries = {

                "libtalloc2.so",

                "libandroid-shmem.so"
        };

        bootLog(
                "[*] Preparing PRoot libraries...\n"
        );

        for (
                String library :
                libraries
        ) {

            File target =
                    new File(
                            runtimeDir,
                            library
                    );

            if (!target.exists()) {

                throw new Exception(
                        "Missing PRoot library: " +
                        target
                );
            }
        }

        // --------------------------------------------------------
        // PROOT LIBRARIES
        // --------------------------------------------------------
        symlinkDir =
                new File(
                        getFilesDir(),
                        "proot_libs"
                );

        if (!symlinkDir.exists() && !symlinkDir.mkdirs()) {
            throw new Exception(
                    "Cannot create PRoot library directory: " +
                    symlinkDir
            );
        }

        String[][] libMappings = {
                { "libtalloc2.so", "libtalloc.so.2" },
                { "liblzma5.so",   "liblzma.so.5" }
        };

        for (String[] mapping : libMappings) {
            File src =
                    new File(
                            runtimeDir,
                            mapping[0]
                    );

            File dst =
                    new File(
                            symlinkDir,
                            mapping[1]
                    );

            if (!src.exists()) {
                throw new Exception(
                        "Missing PRoot source library: " +
                        src
                );
            }

            java.nio.file.Files.copy(
                    src.toPath(),
                    dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );
        }

        // DNS RESOLV.CONF
        // --------------------------------------------------------

        File resolvConf =
                new File(
                        ubuntuDir,
                        "etc/resolv.conf"
                );

        if (resolvConf.getParentFile().exists()) {

            java.io.FileWriter rv =
                    new java.io.FileWriter(resolvConf, false);

            rv.write("nameserver 8.8.8.8\n");
            rv.write("nameserver 8.8.4.4\n");
            rv.close();
        }

        // RESOLV.CONF
        // --------------------------------------------------------

        File resolvConfBind =
                new File(getFilesDir(), "resolv.conf");

        java.io.FileWriter rv =
                new java.io.FileWriter(resolvConfBind, false);

        rv.write("nameserver 8.8.8.8\n");
        rv.write("nameserver 1.1.1.1\n");
        rv.close();

        // UBUNTU ROOTFS
        // --------------------------------------------------------

        // --------------------------------------------------------
        // PROOT LOADER (from Termux)
        // --------------------------------------------------------

        File loaderSrc =
                new File(
                        runtimeDir,
                        "libprootloader.so"
                );

        File loaderDst =
                new File(
                        getFilesDir(),
                        "proot_loader"
                );

        if (!loaderSrc.exists()) {

            throw new Exception(
                    "PRoot loader not found: " +
                    loaderSrc
            );
        }

        if (!loaderDst.exists()) {

            java.nio.file.Files.copy(
                    loaderSrc.toPath(),
                    loaderDst.toPath()
            );

            loaderDst.setExecutable(true, false);
        }

        File bash =
                new File(
                        ubuntuDir,
                        "bin/bash"
                );

        boolean freshInstall = false;

        if (!bash.exists()) {

            freshInstall = true;
            setupBegin();

            // 1) Online setup (pinned + verified download).
            installUbuntuOnline();

            // 2) Bundled rootfs, extracted with exact modes (xz -> TarExtractor).
            if (!bash.exists()) {

                appendOutput(
                        "[*] Using bundled offline rootfs...\n"
                );

                installUbuntuBundled();
            }

            // 3) Last resort: the original installer, unchanged.
            if (!bash.exists()) {

                appendOutput(
                        "[*] Trying legacy installer...\n"
                );

                installUbuntu();
            }

        } else {

            bootLog(
                    "[*] Ubuntu rootfs already installed.\n"
            );
        }

        // --------------------------------------------------------
        // VERIFY
        // --------------------------------------------------------

        if (!bash.exists()) {

            throw new Exception(
                    "Ubuntu /bin/bash does not exist: " +
                    bash
            );
        }

        if (freshInstall) {
            runProotSelfTest();
        }

        bootLog(
                "[+] Runtime ready.\n\n"
        );
    }

    // ============================================================
    // ONLINE SETUP (SetupManager) + PROOT SELF-TEST
    // ============================================================

    // ============================================================
    // LAYAR SETUP: mini terminal saat instalasi pertama
    // (download, verifikasi, extract, ...). Terminal penuh dibuka setelah selesai.
    // ============================================================

    private static final String[] SETUP_STEPS = {"Download", "Verify", "Extract", "Install", "Shell"};

    private static volatile boolean setupActive;
    private static volatile boolean setupFailed;
    private static volatile int setupStep;
    private static volatile String setupStageText = "";
    private static volatile long setupDone, setupTotal;
    private static final java.util.ArrayList<String> setupLines = new java.util.ArrayList<>();
    private static final StringBuilder setupPartial = new StringBuilder();

    private android.widget.FrameLayout setupOverlay;
    private TextView setupTitleTv, setupSubTv, setupStageTv, setupPctTv, setupSizeTv, setupLogTv;
    private GradientBar setupBar;
    private ScrollView setupLogScroll;
    private LinearLayout setupButtons;
    private View[] setupDots;
    private TextView[] setupLabels;

    private static void setupBegin() {
        synchronized (setupLines) {
            setupLines.clear();
            setupPartial.setLength(0);
        }
        setupStep = 0;
        setupStageText = "Preparing";
        setupDone = 0;
        setupTotal = 0;
        setupFailed = false;
        setupActive = true;
        setupNotify();
    }

    private static void setupSetStage(String s) {
        int st;
        if (s.startsWith("Verifying SHA")) st = 1;
        else if (s.startsWith("Extract")) st = 2;
        else if (s.startsWith("Install")) st = 3;
        else if (s.startsWith("Starting") || s.startsWith("Verifying PRoot")) st = 4;
        else st = 0;   // free space, cached download, downloading, bundled rootfs
        setupStep = st;
        setupStageText = s;
        setupDone = 0;
        setupTotal = 0;
        setupNotify();
    }

    private static void setupSetProgress(long done, long total) {
        setupDone = done;
        setupTotal = total;
        setupNotify();
    }

    private static void setupFail() {
        if (!setupActive) return;
        setupFailed = true;
        setupNotify();
    }

    private static void setupFinish() {
        if (!setupActive) return;
        setupStep = SETUP_STEPS.length;
        setupStageText = "Ready";
        setupDone = 1;
        setupTotal = 1;
        setupActive = false;
        setupNotify();
    }

    /** Teks status -> baris log mini terminal (escape ANSI dibuang, \r = timpa baris). */
    private static void setupMirror(String text) {
        if (text == null) return;
        text = text.replace("\r\n", "\n");
        synchronized (setupLines) {
            int n = text.length();
            for (int i = 0; i < n; i++) {
                char c = text.charAt(i);
                if (c == '\u001b') {
                    if (i + 1 < n && text.charAt(i + 1) == '[') {
                        int j = i + 2;
                        while (j < n && !Character.isLetter(text.charAt(j))) j++;
                        i = j;
                    }
                } else if (c == '\r') {
                    setupPartial.setLength(0);
                } else if (c == '\n') {
                    setupLines.add(setupPartial.toString());
                    setupPartial.setLength(0);
                    while (setupLines.size() > 400) setupLines.remove(0);
                } else {
                    setupPartial.append(c);
                }
            }
        }
        setupNotify();
    }

    private static void setupNotify() {
        uiPost(() -> {
            MainActivity a = current;
            if (a != null) a.refreshSetup();
        });
    }

    private void refreshSetup() {
        if (setupActive && setupOverlay == null) showSetupOverlay();
        if (setupOverlay == null) return;
        renderSetup();
        if (!setupActive) {
            // selesai: tampilkan 100% sebentar, lalu pudar -> terminal penuh
            final android.widget.FrameLayout ov = setupOverlay;
            setupOverlay = null;
            ov.animate().alpha(0f).setStartDelay(700).setDuration(250)
                    .withEndAction(() -> {
                        android.view.ViewParent par = ov.getParent();
                        if (par instanceof android.view.ViewGroup) {
                            ((android.view.ViewGroup) par).removeView(ov);
                        }
                        getWindow().setStatusBarColor(THEMES[themeIdx][3]);
                    }).start();
        }
    }

    /** Setup gagal: buka terminal penuh, log setup ikut disalin ke sana. */
    private void dismissSetupOverlay() {
        StringBuilder sb2 = new StringBuilder();
        synchronized (setupLines) {
            for (String l : setupLines) sb2.append(l).append('\n');
            if (setupPartial.length() > 0) sb2.append(setupPartial).append('\n');
        }
        setupActive = false;
        setupFailed = false;
        if (emulator != null) {
            emulator.feed(sb2.toString().replace("\n", "\r\n"));
            scheduleRender();
        }
        if (setupOverlay != null) {
            android.view.ViewParent par = setupOverlay.getParent();
            if (par instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) par).removeView(setupOverlay);
            }
            setupOverlay = null;
        }
        getWindow().setStatusBarColor(THEMES[themeIdx][3]);
    }

    private TextView setupButton(String label, int color, View.OnClickListener click) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(color);
        b.setTypeface(Typeface.create(monoTypeface(), Typeface.BOLD));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(11), dp(12), dp(11));
        b.setBackground(bubbleShape(Palette.KEY, color));
        b.setClickable(true);
        b.setOnClickListener(click);
        return b;
    }

    private View setupTitleDot(int color) {
        View d = new View(this);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        d.setBackground(g);
        return d;
    }

    private void showSetupOverlay() {
        if (setupOverlay != null) return;
        android.view.ViewGroup content = (android.view.ViewGroup) findViewById(android.R.id.content);
        if (content == null) return;

        final android.widget.FrameLayout ov = new android.widget.FrameLayout(this);
        ov.setBackgroundColor(Palette.BG);
        ov.setClickable(true);   // sentuhan tidak tembus ke terminal di belakang

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(20), dp(18), dp(18));

        // merek
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.ic_logo_header);
        brand.addView(logo, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(12), 0, 0, 0);
        TextView nm = new TextView(this);
        SpannableString nmText = new SpannableString("PayNux");
        nmText.setSpan(new ForegroundColorSpan(Palette.TEXT), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        nmText.setSpan(new ForegroundColorSpan(ACCENT_RED), 3, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        nm.setText(nmText);
        nm.setTextSize(20);
        nm.setTypeface(Typeface.create(monoTypeface(), Typeface.BOLD));
        nm.setIncludeFontPadding(false);
        names.addView(nm);
        setupTitleTv = new TextView(this);
        setupTitleTv.setTextSize(13);
        setupTitleTv.setTypeface(monoTypeface());
        setupTitleTv.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(4);
        names.addView(setupTitleTv, tlp);
        brand.addView(names);
        col.addView(brand);

        setupSubTv = new TextView(this);
        setupSubTv.setTextSize(12);
        setupSubTv.setTextColor(Palette.DIM);
        setupSubTv.setTypeface(monoTypeface());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        col.addView(setupSubTv, slp);

        // langkah
        LinearLayout steps = new LinearLayout(this);
        steps.setOrientation(LinearLayout.HORIZONTAL);
        setupDots = new View[SETUP_STEPS.length];
        setupLabels = new TextView[SETUP_STEPS.length];
        for (int i = 0; i < SETUP_STEPS.length; i++) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            View dot = new View(this);
            setupDots[i] = dot;
            item.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
            TextView lb = new TextView(this);
            lb.setText(SETUP_STEPS[i]);
            lb.setTextSize(10);
            lb.setTypeface(monoTypeface());
            lb.setGravity(Gravity.CENTER);
            lb.setIncludeFontPadding(false);
            setupLabels[i] = lb;
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(5);
            item.addView(lb, llp);
            steps.addView(item, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = dp(18);
        col.addView(steps, stlp);

        // tahap + persen
        LinearLayout stageRow = new LinearLayout(this);
        stageRow.setOrientation(LinearLayout.HORIZONTAL);
        stageRow.setGravity(Gravity.CENTER_VERTICAL);
        setupStageTv = new TextView(this);
        setupStageTv.setTextSize(14);
        setupStageTv.setTextColor(Palette.TEXT);
        setupStageTv.setTypeface(Typeface.create(monoTypeface(), Typeface.BOLD));
        setupStageTv.setSingleLine(true);
        setupStageTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        stageRow.addView(setupStageTv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        setupPctTv = new TextView(this);
        setupPctTv.setTextSize(14);
        setupPctTv.setTextColor(Palette.ORANGE);
        setupPctTv.setTypeface(Typeface.create(monoTypeface(), Typeface.BOLD));
        stageRow.addView(setupPctTv);
        LinearLayout.LayoutParams srlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        srlp.topMargin = dp(16);
        col.addView(stageRow, srlp);

        // bar progres (merah -> oranye)
        setupBar = new GradientBar(this);
        LinearLayout.LayoutParams blp3 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        blp3.topMargin = dp(8);
        col.addView(setupBar, blp3);

        setupSizeTv = new TextView(this);
        setupSizeTv.setTextSize(11);
        setupSizeTv.setTextColor(Palette.DIM);
        setupSizeTv.setTypeface(monoTypeface());
        setupSizeTv.setSingleLine(true);
        LinearLayout.LayoutParams szlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        szlp.topMargin = dp(6);
        col.addView(setupSizeTv, szlp);

        // mini terminal
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable boxBg = new android.graphics.drawable.GradientDrawable();
        boxBg.setColor(Palette.PANEL_IN);
        boxBg.setCornerRadius(dp(12));
        boxBg.setStroke(dp(1), Palette.EDGE);
        box.setBackground(boxBg);
        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        barRow.setPadding(dp(12), dp(8), dp(12), dp(8));
        int[] dotColors = {Palette.RED_FILL, Palette.AMBER, Palette.GREEN};
        for (int dc : dotColors) {
            LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(dp(8), dp(8));
            dlp2.rightMargin = dp(6);
            barRow.addView(setupTitleDot(dc), dlp2);
        }
        TextView bt = new TextView(this);
        bt.setText("setup.log");
        bt.setTextSize(11);
        bt.setTextColor(Palette.DIM);
        bt.setTypeface(monoTypeface());
        bt.setPadding(dp(4), 0, 0, 0);
        barRow.addView(bt);
        box.addView(barRow);
        View sep = new View(this);
        sep.setBackgroundColor(Palette.LINE);
        box.addView(sep, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        setupLogScroll = new ScrollView(this);
        setupLogScroll.setVerticalScrollBarEnabled(false);
        setupLogScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setupLogTv = new TextView(this);
        setupLogTv.setTextSize(11);
        setupLogTv.setTextColor(Palette.TEXT2);
        setupLogTv.setTypeface(monoTypeface());
        setupLogTv.setIncludeFontPadding(false);
        setupLogTv.setLineSpacing(0, 1.15f);
        setupLogTv.setPadding(dp(12), dp(8), dp(12), dp(8));
        setupLogScroll.addView(setupLogTv, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        box.addView(setupLogScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams bxlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bxlp.topMargin = dp(14);
        col.addView(box, bxlp);

        // tombol (hanya saat gagal)
        setupButtons = new LinearLayout(this);
        setupButtons.setOrientation(LinearLayout.HORIZONTAL);
        setupButtons.setVisibility(View.GONE);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b1.rightMargin = dp(6);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b2.leftMargin = dp(6);
        setupButtons.addView(setupButton("Copy log", Palette.TEXT, vv -> {
            StringBuilder sb3 = new StringBuilder();
            synchronized (setupLines) {
                for (String l : setupLines) sb3.append(l).append('\n');
                if (setupPartial.length() > 0) sb3.append(setupPartial).append('\n');
            }
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("PayNux setup log", sb3.toString()));
            }
            android.widget.Toast.makeText(this, "Log copied", android.widget.Toast.LENGTH_SHORT).show();
        }), b1);
        setupButtons.addView(setupButton("Open terminal", Palette.ORANGE,
                vv -> dismissSetupOverlay()), b2);
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sblp.topMargin = dp(12);
        col.addView(setupButtons, sblp);

        ov.addView(col, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        content.addView(ov, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        setupOverlay = ov;
        getWindow().setStatusBarColor(Palette.BG);   // menyatu dengan layar setup
        renderSetup();
    }

    private void appendSetupLine(SpannableStringBuilder sb, String l) {
        int c;
        if (l.startsWith("[+]")) c = Palette.GREEN;
        else if (l.startsWith("[!]")) c = Palette.AMBER;
        else if (l.startsWith("[*]")) c = Palette.DIM;
        else if (l.startsWith("[PAYNUX ERROR]")) c = Palette.RED;
        else c = Palette.TEXT2;
        int st = sb.length();
        sb.append(l).append('\n');
        sb.setSpan(new ForegroundColorSpan(c), st, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void renderSetup() {
        if (setupOverlay == null || setupDots == null || setupLogTv == null) return;
        final int step = setupStep;
        final boolean failed = setupFailed;

        for (int i = 0; i < setupDots.length; i++) {
            int dc = i < step ? Palette.GREEN
                    : (i == step ? (failed ? Palette.RED : Palette.ORANGE) : Palette.LINE);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(dc);
            setupDots[i].setBackground(g);
            setupLabels[i].setTextColor(i <= step ? Palette.TEXT : Palette.DIM);
        }

        setupTitleTv.setText(failed ? "Setup failed" : "Setting up Ubuntu 24.04");
        setupTitleTv.setTextColor(failed ? Palette.RED : Palette.ORANGE);
        setupSubTv.setText(failed
                ? "Check the log below, then open the terminal."
                : "First run only. This can take a few minutes.");
        setupStageTv.setText(setupStageText);

        long d = setupDone;
        long t = setupTotal;
        String pct = "";
        String size = "";
        if (step >= SETUP_STEPS.length) {
            setupBar.setFraction(1f);
            pct = "100%";
        } else if (failed) {
            setupBar.setIndeterminate(false);
        } else if (t > 0) {
            setupBar.setFraction((float) d / (float) t);
            pct = (d * 100L / t) + "%";
            size = String.format(java.util.Locale.US, "%.1f / %.1f MB", d / 1048576.0, t / 1048576.0);
        } else {
            setupBar.setIndeterminate(true);
            if (d > 0) {
                size = setupStageText.startsWith("Downloading")
                        ? String.format(java.util.Locale.US, "%.1f MB", d / 1048576.0)
                        : d + " entries";
            }
        }
        setupPctTv.setText(pct);
        setupSizeTv.setText(size);
        setupButtons.setVisibility(failed ? View.VISIBLE : View.GONE);

        SpannableStringBuilder sb = new SpannableStringBuilder();
        synchronized (setupLines) {
            for (String l : setupLines) appendSetupLine(sb, l);
            if (setupPartial.length() > 0) appendSetupLine(sb, setupPartial.toString());
        }
        setupLogTv.setText(sb);
        setupLogScroll.post(() -> {
            View ch = setupLogScroll.getChildAt(0);
            if (ch != null) {
                setupLogScroll.scrollTo(0, Math.max(0, ch.getHeight() - setupLogScroll.getHeight()));
            }
        });
    }

    /** Renders SetupManager progress in the terminal (one updating line). */
    private static final class SetupUi implements SetupListener {
        private String stage = "";
        private long lastMs;
        private boolean lineOpen;

        @Override
        public void onStage(String s) {
            closeLine();
            stage = s;
            setupSetStage(s);
            appendOutput("[*] " + s + "...\n");
        }

        @Override
        public void onProgress(long done, long total) {
            long now = System.currentTimeMillis();
            boolean last = total > 0 && done >= total;
            if (now - lastMs < 400 && !last) return;
            lastMs = now;
            setupSetProgress(done, total);
            String text;
            if (total > 0) {
                text = String.format(java.util.Locale.US,
                        "    %.1f / %.1f MB (%d%%)",
                        done / 1048576.0, total / 1048576.0, done * 100 / total);
            } else if (stage.startsWith("Downloading")) {
                text = String.format(java.util.Locale.US,
                        "    %.1f MB", done / 1048576.0);
            } else {
                text = "    " + done + " entries";
            }
            appendOutput("\r\u001b[2K\u001b[90m" + text + "\u001b[0m");
            lineOpen = true;
        }

        @Override
        public void onLog(String line) {
            closeLine();
            appendOutput(line + "\n");
        }

        void close() {
            closeLine();
        }

        private void closeLine() {
            if (lineOpen) {
                appendOutput("\n");
                lineOpen = false;
            }
        }
    }

    /**
     * Downloads, verifies and installs the pinned Ubuntu rootfs. Never throws:
     * on any failure it reports and returns, and the caller falls back to the
     * bundled offline rootfs because /bin/bash is still missing.
     */
    private void installUbuntuOnline() {

        SetupUi ui = new SetupUi();

        try {

            SetupManager mgr = new SetupManager(
                    getFilesDir(),
                    ubuntuDir,
                    SetupManager.Config.official(),
                    Downloader.defaults(),
                    new AndroidFsOps(),
                    ui
            );

            mgr.install(null);

            ui.close();

            addNotif("ok", "Ubuntu installed",
                    "Ubuntu " + RootfsSource.VERSION
                            + " downloaded and verified.");

        } catch (SetupManager.SetupException e) {

            ui.close();

            appendOutput("[!] Online setup failed: " + e.kind + "\n");
            appendOutput("    " + e.getMessage() + "\n");

            addNotif("warn", "Online setup failed", notifShort(e));

        } catch (Throwable t) {

            ui.close();

            appendOutput("[!] Online setup error: " + t + "\n");
        }
    }

    /**
     * Installs the rootfs bundled in the APK: xz output is piped straight
     * into TarExtractor (exact modes, no temporary .tar, no chmod -R). Never
     * throws; on failure the caller tries the legacy installer next.
     */
    private void installUbuntuBundled() {

        SetupUi ui = new SetupUi();

        try {

            Map<String, String> env = new HashMap<>();
            env.put("LD_LIBRARY_PATH",
                    symlinkDir.getAbsolutePath() + ":"
                            + runtimeDir.getAbsolutePath());

            OfflineInstaller installer = new OfflineInstaller(
                    getFilesDir(),
                    ubuntuDir,
                    new File(runtimeDir, "libxz.so"),
                    env,
                    new AndroidFsOps(),
                    ui,
                    "ubuntu-24.04-aarch64.tar.xz",
                    new OfflineInstaller.AssetSource() {
                        @Override
                        public java.io.InputStream open()
                                throws java.io.IOException {
                            return getAssets().open(
                                    "ubuntu-24.04-aarch64.tar.xz");
                        }
                    },
                    OfflineInstaller.DEFAULT_MIN_FREE_BYTES
            );

            installer.install(null);

            ui.close();

            addNotif("ok", "Ubuntu installed",
                    "Ubuntu 24.04 extracted from the bundled archive.");

        } catch (SetupManager.SetupException e) {

            ui.close();

            appendOutput(
                    "[!] Bundled setup failed (" + e.kind + "):\n"
                            + "    " + e.getMessage() + "\n"
            );

            addNotif("warn", "Bundled setup failed", notifShort(e));

        } catch (Throwable t) {

            ui.close();

            appendOutput("[!] Bundled setup error: " + t + "\n");
        }
    }

    /**
     * Runs a short command inside the new rootfs through PRoot. Environment
     * mirrors startUbuntu(); keep the two in sync. A failure is reported but
     * does not stop the boot (startUbuntu will surface the real error).
     */
    private void runProotSelfTest() {

        try {

            setupSetStage("Verifying PRoot");
            appendOutput("[*] Verifying PRoot...\n");

            Map<String, String> env = new HashMap<>();
            env.put("LD_LIBRARY_PATH",
                    symlinkDir.getAbsolutePath() + ":"
                            + runtimeDir.getAbsolutePath());
            env.put("PROOT_TMP_DIR", getCacheDir().getAbsolutePath());
            if (!kernelSupportsSeccompTrace()) {
                env.put("PROOT_NO_SECCOMP", "1");
            }
            env.put("PROOT_LOADER",
                    getFilesDir().getAbsolutePath() + "/proot_loader");

            ProotSelfTest.Result r = new ProotSelfTest(
                    prootFile, ubuntuDir, env, "24.04", 30000L).run();

            appendOutput(r.report());

            if (!r.ok) {
                addNotif("warn", "PRoot self-test failed",
                        "Ubuntu is installed but the shell check failed.");
            }

        } catch (Throwable t) {

            appendOutput("[!] PRoot self-test error: " + t + "\n");
        }
    }

    // ============================================================
    // INSTALL UBUNTU
    // ============================================================

    private void installUbuntu()
            throws Exception {

        appendOutput(
                "[*] Ubuntu rootfs not found.\n"
        );

        appendOutput(
                "[*] Installing Ubuntu...\n"
        );

        if (!ubuntuDir.exists()) {

            if (!ubuntuDir.mkdirs()) {

                throw new Exception(
                        "Cannot create Ubuntu directory: " +
                        ubuntuDir
                );
            }
        }

        // --------------------------------------------------------
        // XZ RUNTIME
        // --------------------------------------------------------

        /*
         * Same W^X issue as PRoot above: xz must be run from
         * nativeLibraryDir (packaged as jniLibs/<abi>/libxz.so
         * and liblzma5.so), not copied into getFilesDir().
         */

        File xz =
                new File(
                        runtimeDir,
                        "libxz.so"
                );

        File liblzma =
                new File(
                        runtimeDir,
                        "liblzma5.so"
                );

        appendOutput(
                "[*] Preparing XZ...\n"
        );

        if (!xz.exists()) {

            throw new Exception(
                    "xz executable missing: " +
                    xz
            );
        }

        if (!liblzma.exists()) {

            throw new Exception(
                    "liblzma5.so missing: " +
                    liblzma
            );
        }

        // --------------------------------------------------------
        // UBUNTU ARCHIVE
        // --------------------------------------------------------

        File archive =
                new File(
                        getFilesDir(),
                        "ubuntu-24.04-aarch64.tar.xz"
                );

        appendOutput(
                "[*] Copying Ubuntu archive...\n"
        );

        copyAsset(
                "ubuntu-24.04-aarch64.tar.xz",
                archive
        );

        if (!archive.exists() ||
                archive.length() == 0) {

            throw new Exception(
                    "Ubuntu archive missing or empty: " +
                    archive
            );
        }

        appendOutput(
                "[*] Extracting Ubuntu...\n"
        );

        // --------------------------------------------------------
        // DECOMPRESS WITH BUNDLED XZ
        // --------------------------------------------------------

        /*
         * Android's /system/bin/tar does NOT decompress .xz
         * itself: its -J flag just shells out to a program
         * named "xz" looked up on PATH. Since the bundled xz
         * binary lives in our app's private files dir (not on
         * PATH), that lookup fails with "exec xz: No such file
         * or directory". So we invoke the bundled xz directly
         * to decompress to a plain .tar first, then hand that
         * to tar with no -J needed.
         */

        File decompressed =
                new File(
                        getFilesDir(),
                        "ubuntu-24.04-aarch64.tar"
                );

        ProcessBuilder xzPb =
                new ProcessBuilder(
                        xz.getAbsolutePath(),
                        "-d",
                        "-k",
                        "-c",
                        archive.getAbsolutePath()
                );

        xzPb.environment().put(
                "LD_LIBRARY_PATH",
                symlinkDir.getAbsolutePath() +
                ":" +
                runtimeDir.getAbsolutePath()
        );

        xzPb.redirectOutput(
                decompressed
        );

        Process xzProc =
                xzPb.start();

        BufferedReader xzErr =
                new BufferedReader(
                        new InputStreamReader(
                                xzProc.getErrorStream()
                        )
                );

        String xzLine;

        while (
                (xzLine = xzErr.readLine())
                        != null
        ) {

            appendOutput(
                    xzLine + "\n"
            );
        }

        int xzExit =
                xzProc.waitFor();

        if (xzExit != 0) {

            throw new Exception(
                    "xz decompression failed. exit code=" +
                    xzExit
            );
        }

        if (!decompressed.exists() ||
                decompressed.length() == 0) {

            throw new Exception(
                    "Decompressed tar missing or empty: " +
                    decompressed
            );
        }

        // --------------------------------------------------------
        // TAR (plain, archive is already decompressed)
        // --------------------------------------------------------

        /*
         * toybox tar di Android 12 (dan lebih lama) TIDAK mengenal
         * --strip-components. Ekstrak ke folder staging, lalu pindahkan
         * ubuntu/rootfs -> ubuntuDir dengan rename (atomik, tanpa salin).
         */
        File stageDir = new File(getFilesDir(), "ubuntu-stage");
        rmrf(stageDir);
        if (!stageDir.mkdirs() && !stageDir.isDirectory()) {
            throw new Exception("Cannot create staging directory: " + stageDir);
        }

        ProcessBuilder pb =
                new ProcessBuilder(
                        "/system/bin/tar",
                        "-xf",
                        decompressed.getAbsolutePath(),
                        "-C",
                        stageDir.getAbsolutePath()
                );

        pb.redirectErrorStream(
                true
        );

        Process tar =
                pb.start();

        BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                tar.getInputStream()
                        )
                );

        String line;

        while (
                (line = reader.readLine())
                        != null
        ) {

            appendOutput(
                    line + "\n"
            );
        }

        int exitCode =
                tar.waitFor();

        if (exitCode != 0) {

            throw new Exception(
                    "Ubuntu extraction failed. " +
                    "tar exit code=" +
                    exitCode
            );
        }

        File stagedRoot = new File(stageDir, "ubuntu/rootfs");
        if (!stagedRoot.isDirectory()) {
            throw new Exception(
                    "Archive layout unexpected, missing: " + stagedRoot);
        }
        rmrf(ubuntuDir);   // buang sisa ekstraksi gagal sebelumnya
        if (!stagedRoot.renameTo(ubuntuDir)) {
            throw new Exception(
                    "Cannot move rootfs into place: " + ubuntuDir);
        }
        rmrf(stageDir);    // sisa: manifest.json + folder kosong

        // --------------------------------------------------------
        // VERIFY ROOTFS
        // --------------------------------------------------------

        File bash =
                new File(
                        ubuntuDir,
                        "bin/bash"
                );

        if (!bash.exists()) {

            throw new Exception(
                    "Extraction finished but " +
                    "/bin/bash was not found at:\n" +
                    bash.getAbsolutePath()
            );
        }

        appendOutput(
                "[+] Ubuntu extraction complete.\n"
        );
        addNotif("ok", "Ubuntu installed", "Ubuntu 24.04 rootfs extracted and ready.");

        // --------------------------------------------------------
        // FIX PERMISSIONS
        // --------------------------------------------------------

        /*
         * The tarball stores /usr/bin/bash (and everything else)
         * as mode 755 - confirmed by inspecting the archive
         * directly. But Android's bundled /system/bin/tar
         * (toybox) does not reliably restore those permission
         * bits on extraction, which is why proot's exec of
         * /usr/bin/bash was failing with "Permission denied"
         * even though the file itself was clearly present.
         * Force sane permissions across the whole rootfs so
         * everything under it is at least readable+executable.
         */

        appendOutput(
                "[*] Fixing rootfs permissions...\n"
        );

        ProcessBuilder chmodPb =
                new ProcessBuilder(
                        "/system/bin/chmod",
                        "-R",
                        "755",
                        ubuntuDir.getAbsolutePath()
                );

        chmodPb.redirectErrorStream(
                true
        );

        Process chmodProc =
                chmodPb.start();

        BufferedReader chmodReader =
                new BufferedReader(
                        new InputStreamReader(
                                chmodProc.getInputStream()
                        )
                );

        String chmodLine;

        while (
                (chmodLine = chmodReader.readLine())
                        != null
        ) {

            appendOutput(
                    chmodLine + "\n"
            );
        }

        int chmodExit =
                chmodProc.waitFor();

        if (chmodExit != 0) {

            appendOutput(
                    "[!] chmod -R exited with code " +
                    chmodExit +
                    " (continuing anyway)\n"
            );
        }

        // --------------------------------------------------------
        // CLEAN ARCHIVE
        // --------------------------------------------------------

        if (!archive.delete()) {

            appendOutput(
                    "[!] Could not delete archive.\n"
            );
        }

        if (!decompressed.delete()) {

            appendOutput(
                    "[!] Could not delete decompressed tar.\n"
            );
        }
    }

    /** Hapus rekursif tanpa mengikuti symlink (rm -rf bawaan Android). */
    private void rmrf(File f) {
        if (f == null || !f.exists()) return;
        try {
            Process c = new ProcessBuilder("/system/bin/chmod", "-R", "u+rwx",
                    f.getAbsolutePath()).redirectErrorStream(true).start();
            while (c.getInputStream().read() != -1) { }
            c.waitFor();
        } catch (Exception ignored) { }
        try {
            Process p = new ProcessBuilder("/system/bin/rm", "-rf",
                    f.getAbsolutePath()).redirectErrorStream(true).start();
            while (p.getInputStream().read() != -1) { }
            p.waitFor();
        } catch (Exception ignored) { }
    }

    // ============================================================
    // COPY ASSET
    // ============================================================

    private void copyAsset(
            String assetPath,
            File target
    ) throws Exception {

        /*
         * Don't overwrite an existing valid runtime file.
         */

        if (target.exists() &&
                target.length() > 0) {

            return;
        }

        File parent =
                target.getParentFile();

        if (parent != null &&
                !parent.exists()) {

            if (!parent.mkdirs() &&
                    !parent.exists()) {

                throw new Exception(
                        "Cannot create directory: " +
                        parent
                );
            }
        }

        try (
                InputStream input =
                        getAssets().open(
                                assetPath
                        );

                FileOutputStream output =
                        new FileOutputStream(
                                target
                        )
        ) {

            byte[] buffer =
                    new byte[64 * 1024];

            int count;

            while (
                    (count =
                            input.read(buffer))
                            != -1
            ) {

                output.write(
                        buffer,
                        0,
                        count
                );
            }

            output.flush();
        }
    }

    // ============================================================
    // START UBUNTU
    // ============================================================

    /**
     * Konfigurasi shell. /etc/paynux.sh (di dalam rootfs) SELALU ditimpa
     * agar pembaruan app sampai ke instalasi lama; ~/.bashrc milik user
     * hanya disisipi satu baris pemanggil. Dipanggil SEBELUM shell
     * dijalankan, sehingga prompt benar sejak sesi pertama.
     */
    private static final String PAYNUX_SH =
            "# Paynux shell config - managed by the app, overwritten on every session start.\n"
                + "# Customize in ~/.bashrc; PAYNUX_PLAIN_PROMPT=1 disables this prompt.\n"
                + "case $- in *i*) ;; *) return 0 2>/dev/null ;; esac\n"
                + "export LANG=C.UTF-8\n"
                + "export PATH=\"/usr/local/bin:$PATH\"\n"
                + "\n"
                + "# command history: write it right away, do not wait for the shell to exit\n"
                + "# (the session may be killed when the app closes -> normal history is lost)\n"
                + "export HISTSIZE=4000 HISTFILESIZE=8000\n"
                + "shopt -s histappend 2>/dev/null\n"
                + "case \";${PROMPT_COMMAND:-};\" in\n"
                + "  *\";history -a;\"*) ;;\n"
                + "  *) PROMPT_COMMAND=\"history -a${PROMPT_COMMAND:+;$PROMPT_COMMAND}\" ;;\n"
                + "esac\n"
                + "if [ -t 1 ] && [ \"${TERM:-dumb}\" != \"dumb\" ] && [ -z \"${NO_COLOR:-}\" ]; then\n"
                + "  eval \"$(dircolors -b 2>/dev/null)\"\n"
                + "  alias ls='ls --color=auto'\n"
                + "  alias grep='grep --color=auto'\n"
                + "  alias ll='ls -alF'\n"
                + "  if [ -z \"${PAYNUX_PLAIN_PROMPT:-}\" ]; then\n"
                + "    PS1='\\[\\e[1;38;2;255;74;61m\\]\\u\\[\\e[1;38;2;255;138;31m\\]@paynux\\[\\e[0;38;2;138;138;148m\\]:\\[\\e[0;38;2;236;236;238m\\]\\w\\[\\e[1;38;2;255;74;61m\\]#\\[\\e[0m\\] '\n"
                + "  fi\n"
                + "else\n"
                + "  PS1='root@ubuntu:\\w# '\n"
                + "fi\n"
                + "\n"
                + "# portcheck [port]       - who is using the port (safe, stops nothing)\n"
                + "# killport  <port> --force - stop ONLY the process listening on that port\n"
                + "portbusy() { (exec 3<>\"/dev/tcp/127.0.0.1/${1:-20128}\") 2>/dev/null; }\n"
                + "portcheck() {\n"
                + "  local p=${1:-20128}\n"
                + "  if portbusy \"$p\"; then\n"
                + "    echo \"[!] port $p is in use - do not start a second instance\"\n"
                + "    command -v ss >/dev/null 2>&1 && ss -ltnp 2>/dev/null | grep \":$p \"\n"
                + "    ps -eo pid,cmd 2>/dev/null | grep -E \"bin/9router$\" | grep -v grep | head -3\n"
                + "    echo \"    -> use the running one (e.g. open http://localhost:$p)\"\n"
                + "    echo \"    -> or run a new instance on another port\"\n"
                + "    echo \"    -> force stop: killport $p --force\"\n"
                + "  else\n"
                + "    echo \"[+] port $p is free\"\n"
                + "  fi\n"
                + "}\n"
                + "killport() {\n"
                + "  local p=${1:-20128}\n"
                + "  if [ \"${2:-}\" != \"--force\" ]; then\n"
                + "    echo \"[!] killport $p requires --force\"\n"
                + "    echo \"    (this port may be used by another service, e.g. a model proxy - killing it can break other sessions)\"\n"
                + "    return 1\n"
                + "  fi\n"
                + "  command -v fuser >/dev/null 2>&1 && fuser -k -n tcp \"$p\" 2>/dev/null || echo \"[!] fuser not found (apt install psmisc)\"\n"
                + "  sleep 1\n"
                + "  if portbusy \"$p\"; then echo \"[!] port $p is still in use\"; else echo \"[+] port $p released\"; fi\n"
                + "}\n"
                + CommandDone.HOOK;

    private static void writeText(File f, String text) throws Exception {
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(f, false)) {
            o.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String readText(File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) != -1) bo.write(b, 0, n);
            return new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Isi skrip 'uname' & 'hostname' di /usr/local/bin (override nama host). */
    private static final String UNAME_SH =
            "#!/bin/sh\n"
            + "# Paynux: tampilkan nama host & sistem operasi ala distro Linux\n"
            + "case \"$1\" in\n"
            + "  -a|--all)\n"
            + "    echo \"Linux paynux $(/bin/uname -r) #1 SMP PREEMPT "
            + "Thu Jun 13 08:03:55 UTC 2024 $(/bin/uname -m) GNU/Linux\"\n"
            + "    ;;\n"
            + "  -n|--nodename) echo \"paynux\" ;;\n"
            + "  -o|--operating-system) echo \"GNU/Linux\" ;;\n"
            + "  *) exec /bin/uname \"$@\" ;;\n"
            + "esac\n";

    private static final String HOSTNAME_SH =
            "#!/bin/sh\n"
            + "case \"$1\" in\n"
            + "  \"\") echo \"paynux\" ;;\n"
            + "  *) exec /bin/hostname \"$@\" ;;\n"
            + "esac\n";

    /**
     * PRoot tidak mengimplementasikan sethostname (ENOSYS), jadi nama host
     * asli Android tidak bisa diubah di level kernel. Wrapper di
     * /usr/local/bin (PATH mendahulukan /bin) yang menampilkan "paynux".
     */
    private void writeTerminalTools() {
        try {
            File bin = new File(ubuntuDir, "usr/local/bin");
            if (!bin.isDirectory() && !bin.mkdirs()) return;
            File uname = new File(bin, "uname");
            writeText(uname, UNAME_SH);
            uname.setExecutable(true, false);
            File host = new File(bin, "hostname");
            writeText(host, HOSTNAME_SH);
            host.setExecutable(true, false);
        } catch (Exception e) {
            bootLog("[!] writeTerminalTools: " + e + "\n");
        }
    }

    private void writeShellConfig() {
        try {
            writeTerminalTools();
            File etc = new File(ubuntuDir, "etc");
            if (etc.isDirectory()) {
                writeText(new File(etc, "paynux.sh"), PAYNUX_SH);
            }
            File home = new File(getFilesDir(), "home");
            if (!home.exists()) home.mkdirs();

            File bashrc = new File(home, ".bashrc");
            String cur = bashrc.exists() ? readText(bashrc) : "";
            if (!cur.contains("/etc/paynux.sh")) {
                // buang PS1 polos bawaan versi lama
                cur = cur.replace("export PS1='root@ubuntu:\\w# '\n", "");
                if (cur.length() > 0 && !cur.endsWith("\n")) cur += "\n";
                cur += "[ -f /etc/paynux.sh ] && . /etc/paynux.sh  # paynux\n";
                writeText(bashrc, cur);
            }
            File bashProfile = new File(home, ".bash_profile");
            if (!bashProfile.exists()) {
                writeText(bashProfile, "[ -f ~/.bashrc ] && source ~/.bashrc\n");
            }
        } catch (Exception e) {
            bootLog("[!] writeShellConfig: " + e + "\n");
        }
    }

    private void startUbuntu(final Sess sc)
            throws Exception {

        writeShellConfig();

        // --------------------------------------------------------
        // ARGUMEN PROOT — identik dengan build yang terverifikasi
        // (hanya transport I/O yang dipilih di blok START berikut)
        // --------------------------------------------------------

        List<String> command =
                new ArrayList<>();

        command.add(
                prootFile.getAbsolutePath()
        );

        command.add(
                "-0"
        );

        // Kernel release ala Ubuntu (PRoot memalsukan uname -r).
        command.add(
                "-k"
        );
        command.add(
                "6.8.0-52-generic"
        );

        command.add(
                "-r"
        );

        command.add(
                ubuntuDir.getAbsolutePath()
        );

        // BIND /data/user/0 -> /data/data (bila keduanya ada)
        File dataUser = new File("/data/user/0");
        File dataData = new File("/data/data");
        if (dataUser.exists() && dataData.exists()) {
            command.add("-b");
            command.add(
                    dataUser.getAbsolutePath() +
                    ":/data/data"
            );
        }

        // BIND HOME (persisten di luar rootfs)
        File homeDir =
                new File(
                        getFilesDir(),
                        "home"
                );
        if (!homeDir.exists() &&
                !homeDir.mkdirs()) {
            throw new Exception(
                    "Cannot create home directory: " +
                    homeDir
            );
        }
        try {
            File hush = new File(homeDir, ".hushlogin");
            if (!hush.exists()) hush.createNewFile();
        } catch (Exception ignored) { }
        command.add("-b");
        command.add(
                homeDir.getAbsolutePath() +
                ":/root"
        );

        // BIND /sdcard -> penyimpanan bersama (butuh izin storage; targetSdk 28
        // = legacy storage, jadi izin runtime WRITE_EXTERNAL_STORAGE cukup).
        File shared = new File("/storage/emulated/0");
        if (shared.isDirectory()) {
            if (shared.canRead()) {
                command.add("-b");
                command.add(shared.getAbsolutePath() + ":/sdcard");
            } else {
                appendOutput("\u001b[1;33m[!] /sdcard not mounted: storage permission is not enabled yet.\u001b[0m\n");
            }
        }

        command.add(
                "--link2symlink"
        );

        command.add("-b");
        command.add("/proc");
        // Android denies /proc/stat, /proc/loadavg and /proc/uptime to apps;
        // fake only the ones that cannot be read (htop, top, ps, uptime).
        addFakeProcBinds(command);
        command.add("-b");
        command.add("/dev");
        // Android has no /dev/shm; POSIX shared memory (Python multiprocessing,
        // Chromium, PostgreSQL) needs one: bind an app-private folder (mode 1777).
        addShmBind(command);
        command.add("-b");
        command.add("/sys");
        // Android system partitions at the same path inside the guest: only
        // those that exist and are readable on this device.
        addAndroidBinds(command);
        command.add("-b");
        command.add("/system/etc/hosts:/etc/hosts");
        command.add("-b");
        command.add(
                getCacheDir().getAbsolutePath() +
                ":/data/user/0/com.paynux/cache"
        );
        command.add("-b");
        command.add(
                getFilesDir().getAbsolutePath() +
                "/resolv.conf:/etc/resolv.conf"
        );

        command.add(
                "-w"
        );
        command.add(
                "/root"
        );

        // stty sane dijalankan DULU (PTY lahir dalam mode raw: tanpa
        // ini \n tidak jadi \r\n -> teks "menjorok"), lalu exec login
        // shell. Tidak perlu lagi mengetik perintah awal ke shell
        // (yang dulu memunculkan prompt ganda).
        command.add("/bin/bash");
        command.add("-c");
        command.add("stty sane intr '^C' erase '^?' susp '^Z' eof '^D' 2>/dev/null;"
                + " exec /bin/bash --login");

        // --------------------------------------------------------
        // PROCESS BUILDER
        // --------------------------------------------------------

        ProcessBuilder pb =
                new ProcessBuilder(
                        command
                );

        pb.environment().put(
                "LD_LIBRARY_PATH",
                symlinkDir.getAbsolutePath() +
                ":" +
                runtimeDir.getAbsolutePath()
        );

        pb.environment().put(
                "PROOT_TMP_DIR",
                getCacheDir().getAbsolutePath()
        );

        // PROOT_NO_SECCOMP=1 mematikan akselerasi seccomp proot: setiap syscall
        // ditranslasi lewat ptrace biasa. Kernel >= 3.5 mendukung
        // PTRACE_EVENT_SECCOMP; dengan akselerasi aktif proot ~1,9x lebih cepat
        // (median 0,038 s vs 0,072 s untuk 200 execve). Flag hanya dipasang
        // untuk kernel lama / yang tidak terbaca.
        if (!kernelSupportsSeccompTrace()) {
            pb.environment().put(
                    "PROOT_NO_SECCOMP",
                    "1"
            );
        }

        pb.environment().put(
                "PROOT_LOADER",
                getFilesDir().getAbsolutePath() +
                "/proot_loader"
        );

        pb.environment().put("HOME", "/root");
        pb.environment().put("USER", "root");
        pb.environment().put("LOGNAME", "root");
        pb.environment().put("TERM", "xterm-256color");
        pb.environment().put("LANG", "C.UTF-8");
        pb.environment().put("SHELL", "/bin/bash");
        pb.environment().put(
                "PATH",
                "/usr/local/sbin:" +
                "/usr/local/bin:" +
                "/usr/sbin:" +
                "/usr/bin:" +
                "/sbin:" +
                "/bin"
        );

        pb.redirectErrorStream(
                true
        );

        // --------------------------------------------------------
        // START UBUNTU (PTY dulu; gagal -> pipe. Argumen proot/env
        // TIDAK berubah — hanya transport I/O yang dipilih.)

        if (setupActive) setupSetStage("Starting shell");
        bootLog("[*] Starting shell...\n\n");

        sc.ts = new TerminalSession(sc.emu, callbackFor(sc));
        if (sc == active) session = sc.ts;
        int r0 = lastRows > 0 ? lastRows : 24;
        int c0 = lastCols > 0 ? lastCols : 80;
        boolean started = sc.ts.startPty(command, pb.environment(), r0, c0);
        if (!started) {
            started = sc.ts.startPipe(pb);
            if (!started) {
                throw new Exception(
                        "Shell could not be started (both PTY and pipe failed)");
            }
        }
        sc.ready = true;
        if (sc == active) setupFinish();
        if (sc == active) shellReady = true;
        if (sc.ts.isPtyMode()) {
            bootLog("[+] PTY active - interactive shell ready.\n\n");
        } else {
            feedTo(sc.emu, "[!] Pipe mode (no PTY) - limited interaction.\n\n");
            addNotif("warn", "Limited terminal", "PTY unavailable; running in pipe mode.");
        }
        if (sc == active) scheduleRender();
        runOnUiThread(() -> updateSessionButton());

        // --------------------------------------------------------
        // WRITE .bashrc / .bash_profile (persisten: hanya dibuat
        // bila belum ada, agar konfigurasi user tidak ditimpa)
        // --------------------------------------------------------

        // (.bashrc / .bash_profile kini ditulis oleh writeShellConfig()
        //  SEBELUM shell dimulai.)

        if (sc == active) showKeyboard();
    }

    // ============================================================
    // SESSION CALLBACK (dipanggil dari thread pembaca sesi)
    // ============================================================

    // ============================================================
    // LIFECYCLE
    // ============================================================

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        // tombol "Exit" di notifikasi: tutup aplikasi + semua sesi
        if (intent != null
                && intent.getBooleanExtra(KeepAliveService.EXTRA_EXIT, false)) {
            finishAndRemoveTask();
            shutdownAll(this);
        }
    }

    private static java.io.File cleanExitMarker(android.content.Context ctx) {
        return new java.io.File(new java.io.File(ctx.getFilesDir(), "home"),
                ".paynux-clean-exit");
    }

    /**
     * Keluar sungguhan (tombol Exit di notifikasi): matikan semua sesi,
     * hentikan service (wakelock ikut lepas), lalu akhiri proses agar state
     * static tidak tersisa.
     */
    private static void shutdownAll(final android.content.Context ctx) {
        for (Sess sc : sessions) {
            sc.closing = true;
            try { if (sc.ts != null) sc.ts.finish(); } catch (Throwable ignored) { }
        }
        sessions.clear();
        try {
            ctx.stopService(new android.content.Intent(ctx, KeepAliveService.class));
        } catch (Throwable ignored) { }
        // clean_exit: tandai bahwa proses ini berhenti karena disengaja
        // (tombol Exit / lepas wakelock), bukan crash. Dibaca sekali
        // saat startup berikutnya lalu dihapus.
        try {
            java.io.FileOutputStream o = new java.io.FileOutputStream(
                    cleanExitMarker(ctx), false);
            o.write((System.currentTimeMillis() + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            o.flush();
            o.getFD().sync();
            o.close();
        } catch (Throwable ignored) { }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> android.os.Process.killProcess(android.os.Process.myPid()), 400);
    }

    // ============================================================
    // TEKANAN MEMORI (LMK)
    // ============================================================

    /**
     * Android memberi sinyal memori sempit sebelum membunuh proses. Tanpa
     * handler ini riwayat 4000 baris (teks + span) tetap ditahan sampai
     * LowMemoryKiller mematikan aplikasi — gejala "minta tutup app".
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                || level == android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            trimHistory();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        trimHistory();
    }

    /** Pangkas riwayat lama supaya proses tidak dibunuh LMK. */
    private void trimHistory() {
        if (emulator == null) return;
        try {
            emulator.trimScrollback(TRIM_KEEP_LINES);
        } catch (Throwable ignored) { }
        scheduleRender();
    }

    /** Binds /system, /apex, /vendor and /product when usable. Never throws; skipped paths just stay absent. */
    private void addAndroidBinds(List<String> command) {

        try {

            for (String path : AndroidPaths.select(AndroidPaths.defaultProbe())) {

                command.add("-b");
                command.add(path);
            }

        } catch (Throwable t) {

            appendOutput(
                    "[!] Android system binds skipped: " + t + "\n"
            );
        }
    }

    /** Binds files/shm as /dev/shm. Never throws: on failure /dev/shm simply stays absent. */
    private void addShmBind(List<String> command) {

        try {

            String spec = ShmDir.prepare(
                    new File(getFilesDir(), "shm"), new AndroidFsOps());

            command.add("-b");
            command.add(spec);

        } catch (Throwable t) {

            appendOutput(
                    "[!] /dev/shm not available: " + t + "\n"
            );
        }
    }

    /**
     * Adds PRoot binds for fake /proc files, but only for the files the host
     * refuses to open. Never throws: on failure /proc is left as is.
     */
    private void addFakeProcBinds(List<String> command) {

        try {

            File dir = new File(getFilesDir(), "fake_proc");

            int cpus = Math.max(1,
                    Runtime.getRuntime().availableProcessors());

            long uptimeSec =
                    android.os.SystemClock.elapsedRealtime() / 1000L;

            long nowSec = System.currentTimeMillis() / 1000L;

            for (String spec : FakeProc.prepare(
                    dir, FakeProc.defaultProbe(),
                    cpus, uptimeSec, nowSec)) {

                command.add("-b");
                command.add(spec);
            }

        } catch (Throwable t) {

            appendOutput(
                    "[!] Fake /proc not available: " + t + "\n"
            );
        }
    }

    /**
     * Kernel Linux >= 3.5 mendukung PTRACE_EVENT_SECCOMP, syarat agar proot
     * bisa memakai akselerasi seccomp (tanpa itu proot jatuh ke ptrace biasa
     * yang jauh lebih lambat). Versi kernel dibaca dari properti os.version
     * karena /proc/sys/kernel/osrelease tidak terbaca di Android (Permission
     * denied); bila ragu -> false supaya proot memakai jalur lama yang aman.
     */
    private static boolean kernelSupportsSeccompTrace() {
        String rel = System.getProperty("os.version");
        if (rel == null || rel.trim().isEmpty()) {
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.FileReader("/proc/sys/kernel/osrelease"));
                try { rel = br.readLine(); } finally { br.close(); }
            } catch (Throwable ignored) { }
        }
        if (rel == null) return false;
        try {
            String s = rel.trim();
            int dot = s.indexOf('.');
            if (dot <= 0) return false;
            int major = Integer.parseInt(s.substring(0, dot));
            int end = dot + 1;
            while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
            if (end == dot + 1) return false;
            int minor = Integer.parseInt(s.substring(dot + 1, end));
            return major > 3 || (major == 3 && minor >= 5);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True while the Activity is visible: "command finished" notifications are only shown in the background. */
    private static volatile boolean appVisible;

    @Override
    protected void onStart() {
        super.onStart();
        appVisible = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        appVisible = false;
    }

    @Override
    protected void onDestroy() {

        // Activity hilang (geser dari recents, back, rotasi tak tertahan):
        // sesi Ubuntu & service TETAP hidup. Hanya Exit yang mematikan semuanya.
        if (current == this) current = null;
        repeatHandler.removeCallbacksAndMessages(null);

        if (focusGuard != null) {
            focusGuard.removeCallbacks(focusGuardRunnable);
            focusGuard = null;
        }

        if (blinkHandler != null) {
            blinkHandler.removeCallbacks(blinkRunnable);
            blinkHandler = null;
        }

        try {
            if (screenView != null) {
                screenView.getRootView().getViewTreeObserver()
                        .removeOnGlobalLayoutListener(layoutListener);
            }
        } catch (Exception ignored) {
        }

        super.onDestroy();
    }
}
