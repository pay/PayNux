package com.paynux.setup;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.paynux.setup.SetupManager.SetupException;

/**
 * Installs the rootfs bundled in the APK (ubuntu/rootfs inside a .tar.xz).
 *
 * The bundled xz binary decompresses to a pipe that feeds TarExtractor
 * directly: no 100 MB temporary .tar, no toybox tar and no blanket chmod, so
 * modes (setuid, sticky /tmp, ...) are restored exactly as archived.
 *
 * Non-destructive: it only deletes files it created itself (its archive copy
 * and the ubuntu-stage directory). An existing non-empty rootfs directory is
 * renamed aside. Call install() from a background thread.
 */
public final class OfflineInstaller {

    /** Source of the bundled archive bytes (APK asset). */
    public interface AssetSource {
        InputStream open() throws IOException;
    }

    /** Archive copy (~17 MB) + extracted rootfs (~105 MB) with headroom. */
    public static final long DEFAULT_MIN_FREE_BYTES = 300L * 1024L * 1024L;

    private final File filesDir;
    private final File ubuntuDir;
    private final File xzBinary;
    private final Map<String, String> env;
    private final SetupListener ui;
    private final FsOps fs;
    private final String archiveName;
    private final AssetSource asset;
    private final long minFreeBytes;
    private final RootfsPlacer placer;

    public OfflineInstaller(File filesDir, File ubuntuDir, File xzBinary,
                            Map<String, String> env, FsOps fs, SetupListener ui,
                            String archiveName, AssetSource asset, long minFreeBytes) {
        this.filesDir = filesDir;
        this.ubuntuDir = ubuntuDir;
        this.xzBinary = xzBinary;
        this.env = env;
        this.fs = fs;
        this.ui = ui;
        this.archiveName = archiveName;
        this.asset = asset;
        this.minFreeBytes = minFreeBytes;
        this.placer = new RootfsPlacer(filesDir, ubuntuDir, fs, ui);
    }

    public void install(AtomicBoolean cancel) throws SetupException {
        try {
            doInstall(cancel);
        } catch (SetupException e) {
            throw e;
        } catch (Downloader.CancelledException e) {
            throw new SetupException(SetupException.Kind.CANCELLED, "Setup cancelled", e);
        } catch (IOException e) {
            throw new SetupException(SetupException.Kind.INSTALL, String.valueOf(e.getMessage()), e);
        }
    }

    private void doInstall(AtomicBoolean cancel) throws IOException {
        if (new File(ubuntuDir, "bin/bash").exists()) {
            ui.onLog("[*] Ubuntu rootfs already installed.");
            return;
        }
        // ---- 1. preconditions ----------------------------------------------
        ui.onStage("Preparing bundled rootfs");
        if (!xzBinary.exists()) {
            throw new SetupException(SetupException.Kind.INSTALL, "xz executable missing: " + xzBinary);
        }
        long free = filesDir.getUsableSpace();
        if (free < minFreeBytes) {
            throw new SetupException(SetupException.Kind.NO_SPACE,
                    "Not enough free space: need " + (minFreeBytes >> 20) + " MB, have "
                            + (free >> 20) + " MB");
        }

        // ---- 2. fresh copy of the asset (never reuse an old copy) ----------
        File setupDir = new File(filesDir, "setup");
        if (!setupDir.isDirectory() && !setupDir.mkdirs()) {
            throw new SetupException(SetupException.Kind.INSTALL, "Cannot create " + setupDir);
        }
        File archive = new File(setupDir, archiveName);
        File tmp = new File(setupDir, archiveName + ".tmp");
        try {
            copyAsset(tmp, cancel);
            if (archive.exists() && !archive.delete()) {
                throw new SetupException(SetupException.Kind.INSTALL, "Cannot replace " + archive);
            }
            if (!tmp.renameTo(archive)) {
                throw new SetupException(SetupException.Kind.INSTALL, "Cannot rename " + tmp);
            }
        } catch (SetupException e) {
            throw e;
        } catch (Downloader.CancelledException e) {
            tmp.delete();
            throw e;
        } catch (IOException e) {
            tmp.delete();
            throw new SetupException(SetupException.Kind.INSTALL,
                    "Cannot read bundled archive: " + e.getMessage(), e);
        }

        // ---- 3. xz -> TarExtractor into staging ------------------------------
        File stage = placer.stageDir();
        placer.deleteStage(stage);
        ui.onStage("Extracting");
        try {
            extractThroughXz(archive, stage, cancel);
        } catch (Downloader.CancelledException e) {
            placer.deleteStage(stage);
            throw e;
        } catch (SetupException e) {
            placer.deleteStage(stage);
            throw e;
        } catch (IOException e) {
            placer.deleteStage(stage);
            throw new SetupException(SetupException.Kind.EXTRACT,
                    "Extraction failed: " + e.getMessage(), e);
        }

        // ---- 4. layout check -------------------------------------------------
        File stagedRoot = new File(stage, "ubuntu/rootfs");
        if (!stagedRoot.isDirectory() || !new File(stagedRoot, "bin/bash").exists()
                || !new File(stagedRoot, "etc/os-release").isFile()) {
            placer.deleteStage(stage);
            throw new SetupException(SetupException.Kind.LAYOUT,
                    "Archive layout unexpected: ubuntu/rootfs, bin/bash or os-release missing");
        }

        // ---- 5. place (atomic rename), cleanup of our own files ------------
        ui.onStage("Installing");
        placer.place(stagedRoot);
        placer.deleteStage(stage); // leftover: ubuntu/manifest.json + empty dirs
        if (!archive.delete()) {
            ui.onLog("[!] Could not delete archive copy: " + archive);
        }
        ui.onLog("[+] Ubuntu installed (bundled).");
    }

    private void copyAsset(File target, AtomicBoolean cancel) throws IOException {
        InputStream in = asset.open();
        try {
            OutputStream out = new FileOutputStream(target);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancel != null && cancel.get()) throw new Downloader.CancelledException();
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        if (target.length() == 0) throw new IOException("bundled archive is empty");
    }

    private void extractThroughXz(File archive, File stage, AtomicBoolean cancel)
            throws IOException {
        List<String> cmd = new ArrayList<String>();
        cmd.add(xzBinary.getAbsolutePath());
        cmd.add("-d");
        cmd.add("-c");
        cmd.add(archive.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(env);
        final Process proc = pb.start();

        // Drain stderr so xz can never block on a full pipe; keep a short tail for errors.
        final ByteArrayOutputStream errTail = new ByteArrayOutputStream();
        final InputStream err = proc.getErrorStream();
        Thread drain = new Thread(new Runnable() {
            public void run() {
                byte[] b = new byte[1024];
                try {
                    int n;
                    while ((n = err.read(b)) > 0) {
                        synchronized (errTail) {
                            if (errTail.size() < 2048) errTail.write(b, 0, n);
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        });
        drain.setDaemon(true);
        drain.start();

        InputStream tarStream = new BufferedInputStream(proc.getInputStream(), 1 << 16);
        try {
            TarExtractor.Result r = new TarExtractor(fs, new TarExtractor.Progress() {
                public void onEntries(int count, String lastName) {
                    ui.onProgress(count, -1);
                }
            }).extract(tarStream, stage, cancel);
            // Let xz finish: consume any trailing padding after the tar end marker.
            byte[] sink = new byte[8192];
            while (tarStream.read(sink) > 0) { /* discard */ }
            int exit = waitFor(proc);
            if (exit != 0) {
                throw new SetupException(SetupException.Kind.EXTRACT,
                        "xz exited with code " + exit + tail(errTail));
            }
            ui.onLog("[+] Extracted " + r.files + " files, " + r.dirs + " dirs");
            ui.onLog("    " + r.symlinks + " symlinks, " + r.hardlinks + " hardlinks");
            if (r.skipped > 0) ui.onLog("    " + r.skipped + " special files skipped");
        } catch (IOException e) {
            proc.destroy();
            if (e instanceof Downloader.CancelledException || e instanceof SetupException) throw e;
            throw new IOException(e.getMessage() + tail(errTail), e);
        } finally {
            try {
                tarStream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static int waitFor(Process p) throws IOException {
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroy();
            throw new Downloader.CancelledException();
        }
    }

    private static String tail(ByteArrayOutputStream errTail) {
        String s;
        synchronized (errTail) {
            s = new String(errTail.toByteArray(), java.nio.charset.Charset.forName("UTF-8")).trim();
        }
        return s.isEmpty() ? "" : " (" + (s.length() > 200 ? s.substring(s.length() - 200) : s).replace('\n', ' ') + ")";
    }
}
