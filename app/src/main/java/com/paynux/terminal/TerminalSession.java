package com.paynux.terminal;

import com.paynux.pty.PtyNative;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * One Ubuntu/PRoot session. Preferred transport is a real PTY through
 * the bundled libpaynuxpty.so (interactive shell: prompt, echo, colors,
 * Ctrl+C). If the PTY cannot be created (library missing / spawn
 * failure) it transparently falls back to the original pipe transport
 * (ProcessBuilder) so the existing boot path keeps working unchanged.
 */
public final class TerminalSession {

    public interface Callback {
        /** Raw bytes from the shell, already fed into the emulator. */
        void onOutput();
        /** Session terminated; code = exit code (or -1 if unknown). */
        void onExit(int code);
        /** Status/diagnostic line for the UI. */
        void onStatus(String line);
        /** Terminal wants to answer the host (DSR/DA). */
        void onResponse(String data);
        void onBell();
    }

    private final TerminalEmulator emulator;
    private final Callback callback;

    private long handle = -1;          // pty handle (>=0 when pty active)
    private final Object ptyLock = new Object();
    private boolean ptyClosed = false;  // dijaga ptyLock; nativeClose hanya sekali
    private boolean ptyMode;
    private Process pipeProcess;       // fallback transport
    private OutputStream pipeIn;
    private volatile boolean running;
    private volatile int exitCode = -1;
    private Thread readerThread;
    // finish() disengaja (Activity mati / sesi baru): jangan
    // panggil onExit agar tidak menampilkan "process exited"
    // atau mengubah state sesi yang baru.
    private volatile boolean suppressExitCallback;

    public TerminalSession(TerminalEmulator emulator, Callback callback) {
        this.emulator = emulator;
        this.callback = callback;
    }

    public boolean isPtyMode() { return ptyMode; }

    public boolean isRunning() { return running; }

    public int getExitCode() { return exitCode; }

    /**
     * Start the shell through a PTY. argv must be the exact PRoot
     * command line built by the app (unchanged boot command).
     * Returns false if the PTY could not be created.
     */
    public synchronized boolean startPty(List<String> argv, Map<String, String> env,
                                         int rows, int cols) {
        if (running) return false;
        try {
            String[] envPairs = new String[env.size()];
            int i = 0;
            for (Map.Entry<String, String> e : env.entrySet())
                envPairs[i++] = e.getKey() + "=" + e.getValue();
            handle = PtyNative.nativeSpawn(
                    PtyNative.toBlob(argv.toArray(new String[0])),
                    PtyNative.toBlob(envPairs),
                    null, rows, cols);
        } catch (Throwable t) {
            handle = -1;
            callback.onStatus("[!] PTY failed (" + t + ") -> pipe mode");
            return false;
        }
        if (handle < 0) {
            callback.onStatus("[!] PTY failed (spawn<0) -> pipe mode");
            return false;
        }
        ptyMode = true;
        running = true;
        readerThread = new Thread(this::ptyReadLoop, "Paynux-Pty-Reader");
        readerThread.start();
        return true;
    }

    /** Fallback: keep the original ProcessBuilder transport. */
    public synchronized boolean startPipe(ProcessBuilder pb) {
        if (running) return false;
        try {
            pipeProcess = pb.start();
            pipeIn = pipeProcess.getOutputStream();
            ptyMode = false;
            running = true;
            readerThread = new Thread(this::pipeReadLoop, "Paynux-Pipe-Reader");
            readerThread.start();
            return true;
        } catch (Exception e) {
            callback.onStatus("[!] Failed to start process: " + e);
            return false;
        }
    }

    private void ptyReadLoop() {
        byte[] buf = new byte[8192];
        int idle = 0;
        while (running && handle >= 0) {
            int n;
            try {
                n = PtyNative.nativeRead(handle, buf, 0, buf.length, 100);
            } catch (Throwable t) {
                break;
            }
            if (n > 0) {
                idle = 0;
                try {
                    emulator.feed(buf, 0, n);
                } catch (RuntimeException ex) {
                    // bug parser/emulator: jangan matikan aplikasi, lanjut baca
                }
                callback.onOutput();
            } else if (n < 0) {
                break;
            } else {
                if (PtyNative.nativeIsAlive(handle) == 0 && ++idle > 5) break;
            }
        }
        finishPty();
    }

    private void pipeReadLoop() {
        try {
            InputStream in = pipeProcess.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                try {
                    emulator.feed(buf, 0, n);
                } catch (RuntimeException ex) {
                    // lihat ptyReadLoop
                }
                callback.onOutput();
            }
            int rc = pipeProcess.waitFor();
            exitCode = rc;
            running = false;
            if (!suppressExitCallback) callback.onExit(rc);
        } catch (Exception e) {
            running = false;
            if (!suppressExitCallback) callback.onExit(-1);
        }
    }

    private void finishPty() {
        int rc = -1;
        try {
            long deadline = System.currentTimeMillis() + 3000;
            while (PtyNative.nativeIsAlive(handle) != 0 && System.currentTimeMillis() < deadline)
                Thread.sleep(100);
            if (PtyNative.nativeIsAlive(handle) == 0) rc = PtyNative.nativeWait(handle);
        } catch (Throwable ignored) {}
        closePtyOnce();
        exitCode = rc;
        running = false;
        if (!suppressExitCallback) callback.onExit(rc);
    }

    private void closePtyOnce() {
        synchronized (ptyLock) {
            if (ptyClosed || handle < 0) return;
            ptyClosed = true;
            try { PtyNative.nativeClose(handle); } catch (Throwable ignored) {}
        }
    }

    /** Write raw bytes to the active shell (pty master or process stdin). */
    public void write(byte[] data, int off, int len) {
        if (!running || len <= 0) return;
        if (ptyMode) {
            synchronized (ptyLock) {
                if (ptyClosed) return;
                try { PtyNative.nativeWrite(handle, data, off, len); } catch (Throwable ignored) {}
            }
        } else if (pipeIn != null) {
            try {
                pipeIn.write(data, off, len);
                pipeIn.flush();
            } catch (Exception ignored) {}
        }
    }

    public void write(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        write(b, 0, b.length);
    }

    /** Send a single control character (e.g. Ctrl+C = 0x03). */
    public void writeControl(int ctrl) {
        write(new byte[] {(byte) (ctrl & 0x1f)}, 0, 1);
    }

    public void resize(int rows, int cols) {
        synchronized (ptyLock) {
            if (ptyMode && handle >= 0 && !ptyClosed) {
                try { PtyNative.nativeResize(handle, rows, cols); } catch (Throwable ignored) {}
            }
        }
    }

    /** Stop the session (Activity destroyed / restart requested). */
    public synchronized void finish() {
        running = false;
        suppressExitCallback = true;
        // Hanya kill: handle ditutup SEKALI oleh thread pembaca (finishPty)
        // setelah proses mati, agar tidak ada close ganda / use-after-close.
        synchronized (ptyLock) {
            if (ptyMode && handle >= 0 && !ptyClosed) {
                try { PtyNative.nativeKill(handle, 9); } catch (Throwable ignored) {}
            }
        }
        if (pipeProcess != null) {
            try { pipeProcess.getOutputStream().close(); } catch (Exception ignored) {}
            try { pipeProcess.destroy(); } catch (Exception ignored) {}
            try {
                if (pipeProcess.isAlive()) pipeProcess.destroyForcibly();
            } catch (Exception ignored) {}
        }
    }

    /** True when the session is alive but produced no output for a while. */
    public boolean isAlive() {
        if (ptyMode && handle >= 0) {
            synchronized (ptyLock) {
                if (ptyClosed) return false;
                try { return PtyNative.nativeIsAlive(handle) != 0; } catch (Throwable t) { return false; }
            }
        }
        return pipeProcess != null && pipeProcess.isAlive();
    }
}
