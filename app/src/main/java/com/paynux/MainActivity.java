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

import com.paynux.terminal.TerminalEmulator;
import com.paynux.terminal.TerminalSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

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
    private static final class Sess {
        final int id;
        TerminalEmulator emu;
        TerminalSession ts;
        volatile boolean ready;
        volatile boolean exited;
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
    static final int KEY_H_DP    = 32;   // tinggi satu baris toolbar
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

    private static final int CURSOR_COLOR = Color.rgb(180, 200, 255);

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

        boolean fresh = (emulator == null);
        if (fresh) {
            Sess first = createSess();
            sessions.add(first);
            active = first;
            emulator = first.emu;
        }

        buildTerminalUI();
        updateSessionButton();

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

                    } catch (Exception e) {

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
            if (r != 5) return;
            appendOutput("\u001b[33m[!] Last run ended: " + name
                    + " (status " + i.getStatus() + ")\u001b[0m\n"
                    + "\u001b[90m    details: /root/paynux-exit.log\u001b[0m\n\n");
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

    /** Perkiraan jumlah kolom sebelum layout selesai diukur. */
    private int approxCols() {
        if (lastCols > 0) return lastCols;
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.Paint p = new android.graphics.Paint();
            p.setTypeface(Typeface.MONOSPACE);
            p.setTextSize(fontSizeSp * dm.scaledDensity);
            float cw = p.measureText("M");
            if (cw > 0) return Math.max(20, (int) ((dm.widthPixels - 32) / cw));
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
            appendOutput("\n\u001b[1;36m[?] Meminta izin akses /sdcard "
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
            appendOutput("\n\u001b[1;32m[+] Izin /sdcard: OK.\u001b[0m\n");
        } else {
            appendOutput("\n\u001b[1;33m[!] Izin penyimpanan ditolak \u2014 /sdcard tidak bisa dibuka.\n"
                    + "    Aktifkan: Settings > Apps > Paynux > Permissions > Files and media,\n"
                    + "    lalu buka sesi baru (tombol sesi > + New session).\u001b[0m\n");
        }
        startUbuntuBoot();
    }

    /** Banner simpel: logo PAYNUX gradasi + satu baris info. */
    private void showBanner() {
        int cols = approxCols();
        String ver = appVersion();

        if (cols < 26) { // layar sangat sempit: tanpa logo
            appendOutput("\u001b[1;38;5;45mPAYNUX \u001b[90mv" + ver + "\n");
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
        int[] shade = {51, 45, 39, 33, 99, 135}; // cyan -> biru -> ungu

        for (int row = 0; row < art.length; row++) {
            String line = art[row];
            StringBuilder sb = new StringBuilder("  ");
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == '\u2591') {
                    sb.append("\u001b[38;5;238m").append(ch);
                } else {
                    int sh = shade[Math.min(shade.length - 1,
                            i * shade.length / Math.max(1, line.length()))];
                    sb.append("\u001b[1;38;5;").append(sh).append('m').append(ch);
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

        String dot = "\u001b[38;5;45m\u2022\u001b[0m ";
        appendOutput("  " + dot + "\u001b[97mUbuntu 24.04\u001b[0m"
                + " \u001b[90m\u00B7 ARM64 \u00B7 PRoot\u001b[0m\n");
        appendOutput("  " + dot + "\u001b[97mAndroid "
                + android.os.Build.VERSION.RELEASE + "\u001b[0m"
                + " \u001b[90m\u00B7 v" + ver + "\u001b[0m\n");
        appendOutput("  \u001b[38;5;240mpinch = zoom \u00B7 hold a session = close\u001b[0m\n");
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

        int BG       = Color.BLACK;
        int BG2      = Color.rgb(  18,  18,  18);
        int BG3      = Color.rgb(  28,  28,  28);
        int GREEN    = Color.rgb(  87, 201, 120);
        int YELLOW   = Color.rgb( 229, 192,  82);
        int CYAN     = Color.rgb(  51, 187, 200);
        int FG       = Color.WHITE;
        int GRAY     = Color.rgb( 128, 128, 128);

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

        scrollbackView = new TextView(this);
        scrollbackView.setTextColor(FG);
        scrollbackView.setTextSize(fontSizeSp);
        scrollbackView.setTypeface(Typeface.MONOSPACE);
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

        screenView = new TextView(this);
        screenView.setTextColor(FG);
        screenView.setTextSize(fontSizeSp);
        screenView.setTypeface(Typeface.MONOSPACE);
        screenView.setGravity(Gravity.TOP | Gravity.START);
        screenView.setPadding(16, 0, 16, 4);
        screenView.setTextIsSelectable(true); // SELECT: crash isolation test
        installCopyAllSelectionAction(screenView);
        screenView.setIncludeFontPadding(false);
        screenView.setLineSpacing(0, 1.0f);
        screenView.setHorizontallyScrolling(true);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setBackgroundColor(BG);
        inner.addView(screenView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        terminalScroll = new ScrollView(this);
        terminalScroll.setBackgroundColor(BG);
        terminalScroll.setFillViewport(true);
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
        hiddenInput.setTypeface(Typeface.MONOSPACE);
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

        // garis tipis pemisah di atas toolbar
        View topLine = new View(this);
        topLine.setBackgroundColor(Color.rgb(60, 60, 60));
        keyBar.addView(topLine, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

        // --- isi toolbar dari konfigurasi (default 2x7 seperti sebelumnya) ---
        for (java.util.List<String> tokens : loadToolbarRows()) {
            LinearLayout row = newToolbarRow();
            for (String t : tokens) addToolbarToken(row, t);
            keyBar.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(KEY_H_DP)));
        }

        // --------------------------------------------------------
        // ASSEMBLE
        // --------------------------------------------------------

        root.addView(terminalScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
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

    private final android.os.Handler repeatHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private boolean isRepeatable(String label) {
        return label.equals("\u2190") || label.equals("\u2191")
                || label.equals("\u2193") || label.equals("\u2192")
                || label.equals("PGUP") || label.equals("PGDN");
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
     *   extra-keys = [[ESC,/,-,HOME,UP,END,SESS],[TAB,CTRL,ALT,LEFT,DOWN,RIGHT,KEYBOARD]]
     * Bila file tidak ada / tidak valid -> default 2x7.
     */
    private java.util.List<java.util.List<String>> loadToolbarRows() {
        java.util.List<java.util.List<String>> def = new java.util.ArrayList<>();
        def.add(java.util.Arrays.asList("ESC", "/", "-", "HOME", "UP", "END", "SESS"));
        def.add(java.util.Arrays.asList("TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "KEYBOARD"));
        File f = toolbarConfigFile();
        try {
            if (!f.isFile()) { writeDefaultToolbarConfig(f); return def; }
            String txt = readText(f);
            for (String line : txt.split("\n")) {
                String l = line.trim();
                if (!l.startsWith("extra-keys")) continue;
                int eq = l.indexOf('=');
                if (eq < 0) continue;
                java.util.List<java.util.List<String>> rows = parseToolbarRows(l.substring(eq + 1));
                if (rows != null && !rows.isEmpty()) return rows;
            }
        } catch (Throwable ignored) { }
        return def;
    }

    private void writeDefaultToolbarConfig(File f) {
        try {
            f.getParentFile().mkdirs();
            writeText(f, "# Toolbar PayNux - format sama seperti extra-keys Termux.\n"
                    + "# Ubah, lalu tutup-buka app. Hapus file ini untuk kembali ke default.\n"
                    + "# Tombol: ESC TAB CTRL ALT HOME END PGUP PGDN UP DOWN LEFT RIGHT\n"
                    + "#         SESS (panel sesi) KEYBOARD (\u2328) SPACE ENTER BACKSPACE\n"
                    + "# Tombol lain = dikirim apa adanya (maks 3 huruf ditampilkan).\n"
                    + "extra-keys = [[ESC,/,-,HOME,UP,END,SESS],[TAB,CTRL,ALT,LEFT,DOWN,RIGHT,KEYBOARD]]\n");
        } catch (Throwable ignored) { }
    }

    /** Parse "[[A,B],[C,D]]" -> [[A,B],[C,D]]; null bila tidak ada baris. */
    static java.util.List<java.util.List<String>> parseToolbarRows(String v) {
        java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
        java.util.List<String> row = null;
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '[') {
                depth++;
                if (depth == 2) row = new java.util.ArrayList<>();
                continue;
            }
            if (c == ']') {
                if (depth == 2 && row != null) {
                    String t = cur.toString().trim();
                    if (!t.isEmpty()) row.add(t);
                    cur.setLength(0);
                    if (!row.isEmpty()) rows.add(row);
                    row = null;
                }
                depth--;
                continue;
            }
            if (depth == 2 && row != null) {
                if (c == ',') {
                    String t = cur.toString().trim();
                    if (!t.isEmpty()) row.add(t);
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        return rows.isEmpty() ? null : rows;
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
        drawer.setBackgroundColor(Color.rgb(18, 18, 18));
        drawer.setElevation(dp(8));
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                dp(DRAWER_W_DP), android.widget.FrameLayout.LayoutParams.MATCH_PARENT);
        drawer.setTranslationX(-dp(DRAWER_W_DP));
        drawer.setVisibility(View.GONE);
        host.addView(drawer, lp);
    }

    /** Isi drawer dibangun ulang setiap kali dibuka (daftar sesi terbaru). */
    private void fillDrawer() {
        if (drawer == null) return;
        drawer.removeAllViews();
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(buildSessionPanel(this::closeDrawer));
        drawer.addView(sv, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void openDrawer() {
        if (drawer == null) return;
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
        drawer.animate().translationX(-dp(DRAWER_W_DP)).setDuration(160)
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
        int w = dp(DRAWER_W_DP);
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

        DrawerFrame(android.content.Context c) { super(c); }

        @Override
        public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    float x = ev.getX();
                    edge = drawerOpen ? x <= dp(DRAWER_W_DP) : x <= dp(20);
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
                        dragDrawer(-dp(DRAWER_W_DP) + dx);
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
        row.addView(cell, new LinearLayout.LayoutParams(0, dp(KEY_H_DP), 1f));
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
        sessBadgeView.setTextSize(6);
        sessBadgeView.setTextColor(Color.rgb(16, 16, 16));
        sessBadgeView.setGravity(Gravity.CENTER);
        sessBadgeView.setIncludeFontPadding(false);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(CYAN_COLOR);
        sessBadgeView.setBackground(bg);
        cell.addView(sessBadgeView, new android.widget.FrameLayout.LayoutParams(
                dp(11), dp(11), Gravity.END | Gravity.BOTTOM));
    }

    private Button makeKey(String label, String seq) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(11);
        btn.setTextColor(CYAN_COLOR);
        btn.setAllCaps(false);
        btn.setIncludeFontPadding(false);
        btn.setMinHeight(0);
        btn.setMinWidth(0);
        btn.setSingleLine(true);
        btn.setPadding(dp(2), dp(4), dp(2), dp(4));
        btn.setFocusable(false); // jangan curi fokus dari kolom input

        // umpan balik sentuh: gelap saat ditekan
        android.graphics.drawable.StateListDrawable bg =
                new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.ColorDrawable(Color.rgb(70, 70, 70)));
        bg.addState(new int[]{},
                new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        btn.setBackground(bg);
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
                    btn.setTextColor(ctrlMode[0] ? YELLOW_COLOR : CYAN_COLOR);
                    btn.setTypeface(null, ctrlMode[0] ? Typeface.BOLD : Typeface.NORMAL);
                    if (ctrlMode[0]) {
                        altMode[0] = false;
                        if (altKeyBtn != null) {
                            altKeyBtn.setTextColor(CYAN_COLOR);
                            altKeyBtn.setTypeface(null, Typeface.NORMAL);
                        }
                    }
                    break;
                case "ALT":
                    altMode[0] = !altMode[0];
                    btn.setTextColor(altMode[0] ? YELLOW_COLOR : CYAN_COLOR);
                    btn.setTypeface(null, altMode[0] ? Typeface.BOLD : Typeface.NORMAL);
                    if (altMode[0]) {
                        ctrlMode[0] = false;
                        if (ctrlKeyBtn != null) {
                            ctrlKeyBtn.setTextColor(CYAN_COLOR);
                            ctrlKeyBtn.setTypeface(null, Typeface.NORMAL);
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
        if (emulator == null || text == null) return;
        text = colorizeStatusLines(text);
        // status boot memakai \n telanjang; terminal butuh \r\n agar
        // kolom kembali ke 0 (semantik ONLCR yang dijalankan tty asli)
        emulator.feed(text.replace("\r\n", "\n").replace("\n", "\r\n"));
    }

    /**
     * Warnai baris status aplikasi: [*] cyan, [+] hijau, [!] kuning.
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
            if (l.startsWith("[*] ")) col = "\u001b[1;36m";
            else if (l.startsWith("[+] ")) col = "\u001b[1;32m";
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
        }
    }

    // ============================================================
    // RENDER: emulator cell grid -> Spannable TextView
    // ============================================================

    private static final int CYAN_COLOR   = Color.rgb(51, 187, 200);
    private static final int YELLOW_COLOR = Color.rgb(229, 192, 82);

    private Button ctrlKeyBtn;
    private Button altKeyBtn;
    private long renderedScrollStart = 0;   // nomor global baris pertama yang tampil
    private long renderedScrollEnd = 0;     // nomor global akhir (monotonik)
    private int renderedScrollCount = 0;    // jumlah baris yang tampil
    private final java.util.ArrayList<TerminalEmulator.Line> scrollSnapshot =
            new java.util.ArrayList<>();

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
                        ? Color.BLACK : (bg | 0xFF000000);
                hasBg = true;
                bgc = (fg == TerminalEmulator.DEFAULT_COLOR)
                        ? Color.WHITE : (fg | 0xFF000000);
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
        if (endIdx == renderedScrollEnd) return;   // tidak ada baris baru

        int drop = (int) Math.max(0, firstIdx - renderedScrollStart);
        int keep = renderedScrollCount - drop;
        boolean trimmed = false;
        if (drop > 0 && keep >= 0) {
            CharSequence cs = scrollbackView.getText();
            if (cs instanceof android.text.SpannableStringBuilder) {
                android.text.SpannableStringBuilder sb =
                        (android.text.SpannableStringBuilder) cs;
                int cut = 0;
                for (int i = 0; i < drop && cut < sb.length(); i++) {
                    int nl = android.text.TextUtils.indexOf(sb, '\n', cut);
                    if (nl < 0) { cut = sb.length(); break; }
                    cut = nl + 1;
                }
                sb.delete(0, cut);
                trimmed = true;
            }
        }

        if (!trimmed && drop > 0) keep = -1;          // tidak bisa dipotong -> rebuild
        if (keep < 0 || keep > total) {
            SpannableStringBuilder all = new SpannableStringBuilder();
            for (int i = 0; i < total; i++) {
                all.append(styleLine(scrollSnapshot.get(i), -1));
                all.append('\n');
            }
            scrollbackView.setText(all, TextView.BufferType.SPANNABLE);
        } else {
            SpannableStringBuilder add = new SpannableStringBuilder();
            for (int i = keep; i < total; i++) {
                add.append(styleLine(scrollSnapshot.get(i), -1));
                add.append('\n');
            }
            if (add.length() > 0) scrollbackView.append(add);
        }

        renderedScrollStart = firstIdx;
        renderedScrollCount = total;
        renderedScrollEnd = endIdx;
    }

    /** Hitung rows/cols dari ukuran layar & font; resize bila berubah. */
    private void measureTerminal() {
        if (emulator == null || terminalScroll == null || screenView == null)
            return;
        int vw = terminalScroll.getWidth() - 32;
        int vh = terminalScroll.getHeight();
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
        return sc;
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
                feedTo(sc.emu, "\n\n[Ubuntu process exited] code=" + code + "\n");
                if (sessions.size() <= 1) {
                    feedTo(sc.emu, "[Tap the session button, long-press this session, then Close"
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
                    new android.graphics.drawable.ColorDrawable(Color.rgb(18, 18, 18)));
        }
    }

    /**
     * Isi panel sesi (dipakai dialog maupun drawer geser).
     * @param onDismiss dijalankan sebelum aksi apa pun (tutup dialog/drawer)
     */
    private LinearLayout buildSessionPanel(final Runnable onDismiss) {
        final List<Sess> snap = new ArrayList<>(sessions);
        final int n = snap.size();

        final int BG_PANEL = Color.rgb(18, 18, 18);
        final int BG_ROW   = Color.rgb(24, 24, 24);
        final int FG_DIM   = Color.rgb(140, 140, 140);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(BG_PANEL);

        // ---- header ----
        TextView title = new TextView(this);
        title.setText("Sessions");
        title.setTextColor(CYAN_COLOR);
        title.setTextSize(14);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setPadding(dp(16), dp(14), dp(16), dp(2));
        panel.addView(title);

        TextView hint = new TextView(this);
        hint.setText(n + (n == 1 ? " session" : " sessions")
                + "  \u00B7  tap to switch  \u00B7  hold to close");
        hint.setTextColor(FG_DIM);
        hint.setTextSize(9);
        hint.setTypeface(Typeface.MONOSPACE);
        hint.setPadding(dp(16), 0, dp(16), dp(10));
        panel.addView(hint);

        // ---- daftar sesi ----
        if (n == 0) {
            TextView empty = new TextView(this);
            empty.setText("No sessions");
            empty.setTextColor(FG_DIM);
            empty.setTextSize(11);
            empty.setTypeface(Typeface.MONOSPACE);
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
            rowBg.setColor(act ? Color.rgb(26, 40, 44) : BG_ROW);
            row.setBackground(rowBg);

            TextView t1 = new TextView(this);
            t1.setText((act ? "\u25B6 " : "  ") + "Session " + sc.id);
            t1.setTextColor(act ? CYAN_COLOR : Color.WHITE);
            t1.setTextSize(12);
            t1.setTypeface(Typeface.MONOSPACE,
                    act ? Typeface.BOLD : Typeface.NORMAL);
            row.addView(t1);

            TextView t2 = new TextView(this);
            t2.setText(st + (pv.isEmpty() ? "" : "   " + pv));
            t2.setTextColor(FG_DIM);
            t2.setTextSize(9);
            t2.setTypeface(Typeface.MONOSPACE);
            t2.setSingleLine(true);
            t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(t2);

            row.setOnClickListener(v -> {
                onDismiss.run();
                if (sessions.contains(sc)) activate(sc);
            });
            row.setOnLongClickListener(v -> {
                onDismiss.run();
                new android.app.AlertDialog.Builder(this,
                        android.R.style.Theme_Material_Dialog_Alert)
                        .setMessage("Close session " + sc.id + "?")
                        .setPositiveButton("Close",
                                (d2, w2) -> closeSession(sc))
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            });
            panel.addView(row);

            View sep = new View(this);
            sep.setBackgroundColor(Color.rgb(38, 38, 38));
            panel.addView(sep, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }

        // ---- baris aksi ----
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(8), dp(6), dp(8), dp(6));
        actions.setBackgroundColor(BG_PANEL);
        panel.addView(actions);

        addSessionAction(actions, "+ New",
                () -> { onDismiss.run(); newSession(); });
        addSessionAction(actions, "Copy log",
                () -> { onDismiss.run(); copyAllLog(); });
        addSessionAction(actions, "Paste",
                () -> { onDismiss.run(); pasteFromClipboard(); });
        addSessionAction(actions, "Reset",
                () -> { onDismiss.run(); resetTerminal(); });
        addSessionAction(actions, "Close", onDismiss);

        return panel;
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
        b.setTypeface(Typeface.MONOSPACE);
        b.setFocusable(false);
        android.graphics.drawable.StateListDrawable bg =
                new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.ColorDrawable(Color.rgb(40, 40, 40)));
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
        int sb = emulator.getScrollbackSize();
        for (int i = 0; i < sb; i++) appendLine(out, emulator.getScrollbackLine(i));
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
            appendOutput("[!] Tidak ada aplikasi untuk membuka tautan.\n");
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
                menu.add(android.view.Menu.NONE, MENU_ID_PASTE, 100, "Tempel");
                // Termux punya "Select all" di menu seleksi.
                menu.add(android.view.Menu.NONE, MENU_ID_SELECT_ALL, 101, "Pilih semua");
                // Termux: tautan bisa langsung dibuka dari menu seleksi.
                if (asUrl(selectedText(view)) != null) {
                    menu.add(android.view.Menu.NONE, MENU_ID_OPEN_URL, 99, "Buka tautan");
                }
                return true;
            }

            @Override
            public boolean onPrepareActionMode(
                    android.view.ActionMode mode,
                    android.view.Menu menu) {
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
                    String url = asUrl(selectedText(view));
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
        copyText(getTranscript(), "full");
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
        float fx = x + scrollLoc[0] - viewLoc[0];
        float fy = y + scrollLoc[1] - viewLoc[1];
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
                    ctrlKeyBtn.setTextColor(CYAN_COLOR);
                    ctrlKeyBtn.setTypeface(null, Typeface.NORMAL);
                }
                if (altKeyBtn != null) {
                    altKeyBtn.setTextColor(CYAN_COLOR);
                    altKeyBtn.setTypeface(null, Typeface.NORMAL);
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
            if (t != null) shellWrite(t.toString());
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

        if (!bash.exists()) {

            installUbuntu();

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

        bootLog(
                "[+] Runtime ready.\n\n"
        );
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
                + "\n"
                + "# riwayat perintah: tulis segera, jangan tunggu shell keluar\n"
                + "# (sesi bisa dimatikan paksa saat app ditutup -> history biasa hilang)\n"
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
                + "    PS1='\\[\\e[1;32m\\]┌──(\\[\\e[1;36m\\]\\u\\[\\e[1;32m\\]@\\[\\e[1;31m\\]\\h\\[\\e[1;32m\\])-[\\[\\e[1;37m\\]\\w] \\[\\e[1;33m\\]\\t\\n\\[\\e[1;32m\\]╰╼ \\[\\e[0m\\]'\n"
                + "  fi\n"
                + "else\n"
                + "  PS1='root@ubuntu:\\w# '\n"
                + "fi\n"
                + "\n"
                + "# portcheck [port]       - siapa yang memakai port (aman, tidak menghentikan apa pun)\n"
                + "# killport  <port> --force - hentikan HANYA proses pendengar di port itu\n"
                + "portbusy() { (exec 3<>\"/dev/tcp/127.0.0.1/${1:-20128}\") 2>/dev/null; }\n"
                + "portcheck() {\n"
                + "  local p=${1:-20128}\n"
                + "  if portbusy \"$p\"; then\n"
                + "    echo \"[!] port $p sudah dipakai - jangan start instance kedua\"\n"
                + "    command -v ss >/dev/null 2>&1 && ss -ltnp 2>/dev/null | grep \":$p \"\n"
                + "    ps -eo pid,cmd 2>/dev/null | grep -E \"bin/9router$\" | grep -v grep | head -3\n"
                + "    echo \"    -> pakai yang sudah jalan (mis. buka http://localhost:$p)\"\n"
                + "    echo \"    -> atau jalankan instance baru di port lain\"\n"
                + "    echo \"    -> paksa hentikan: killport $p --force\"\n"
                + "  else\n"
                + "    echo \"[+] port $p bebas\"\n"
                + "  fi\n"
                + "}\n"
                + "killport() {\n"
                + "  local p=${1:-20128}\n"
                + "  if [ \"${2:-}\" != \"--force\" ]; then\n"
                + "    echo \"[!] killport $p butuh --force\"\n"
                + "    echo \"    (port ini bisa dipakai layanan lain, mis. proxy model - mematikannya bisa merusak sesi lain)\"\n"
                + "    return 1\n"
                + "  fi\n"
                + "  command -v fuser >/dev/null 2>&1 && fuser -k -n tcp \"$p\" 2>/dev/null || echo \"[!] fuser tidak ada (apt install psmisc)\"\n"
                + "  sleep 1\n"
                + "  if portbusy \"$p\"; then echo \"[!] port $p masih dipakai\"; else echo \"[+] port $p dibebaskan\"; fi\n"
                + "}\n";

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

    private void writeShellConfig() {
        try {
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
                appendOutput("\u001b[1;33m[!] /sdcard tidak di-bind: izin penyimpanan belum aktif.\u001b[0m\n");
            }
        }

        command.add(
                "--link2symlink"
        );

        command.add("-b");
        command.add("/proc");
        command.add("-b");
        command.add("/dev");
        command.add("-b");
        command.add("/sys");
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

        pb.environment().put(
                "PROOT_NO_SECCOMP",
                "1"
        );

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
        if (sc == active) shellReady = true;
        if (sc.ts.isPtyMode()) {
            bootLog("[+] PTY active - interactive shell ready.\n\n");
        } else {
            feedTo(sc.emu, "[!] Pipe mode (no PTY) - limited interaction.\n\n");
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
