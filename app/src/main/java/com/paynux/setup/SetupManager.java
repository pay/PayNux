package com.paynux.setup;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * Installs the Ubuntu rootfs from the pinned official download:
 * free-space check -> resumable download -> SHA-256 -> extract into a staging
 * directory -> layout check -> atomic rename into place.
 *
 * Non-destructive by design: it only ever deletes files it created itself
 * (the download, its ".part", and the "ubuntu-stage" staging directory). An
 * existing, non-empty rootfs directory is renamed aside, never deleted.
 *
 * Call install() from a background thread. It is safe to call again after a
 * failure: a partial download resumes, staging is rebuilt from scratch.
 */
public final class SetupManager {

    /** What to install. */
    public static final class Config {
        public final String url;
        public final String fileName;
        public final String sha256;
        public final long minFreeBytes;

        public Config(String url, String fileName, String sha256, long minFreeBytes) {
            this.url = url;
            this.fileName = fileName;
            this.sha256 = sha256;
            this.minFreeBytes = minFreeBytes;
        }

        public static Config official() {
            return new Config(RootfsSource.URL, RootfsSource.FILE_NAME,
                    RootfsSource.SHA256, RootfsSource.MIN_FREE_BYTES);
        }
    }

    /** Failure with a category, so the caller can decide whether to fall back. */
    public static final class SetupException extends IOException {
        public enum Kind { NO_SPACE, DOWNLOAD, VERIFY, EXTRACT, LAYOUT, INSTALL, CANCELLED }

        public final Kind kind;

        public SetupException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public SetupException(Kind kind, String message) {
            this(kind, message, null);
        }
    }

    private final File filesDir;
    private final File ubuntuDir;
    private final Config cfg;
    private final Downloader downloader;
    private final FsOps fs;
    private final SetupListener ui;
    private final RootfsPlacer placer;

    public SetupManager(File filesDir, File ubuntuDir, Config cfg, Downloader downloader,
                        FsOps fs, SetupListener ui) {
        this.filesDir = filesDir;
        this.ubuntuDir = ubuntuDir;
        this.cfg = cfg;
        this.downloader = downloader;
        this.fs = fs;
        this.ui = ui;
        this.placer = new RootfsPlacer(filesDir, ubuntuDir, fs, ui);
    }

    /** True when the rootfs already has a shell, i.e. nothing to install. */
    public boolean isInstalled() {
        return new File(ubuntuDir, "bin/bash").exists();
    }

    public void install(AtomicBoolean cancel) throws SetupException {
        if (isInstalled()) {
            ui.onLog("[*] Ubuntu rootfs already installed.");
            return;
        }
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
        // ---- 1. free space ------------------------------------------------
        ui.onStage("Checking free space");
        long free = filesDir.getUsableSpace();
        if (free < cfg.minFreeBytes) {
            throw new SetupException(SetupException.Kind.NO_SPACE,
                    "Not enough free space: need " + mb(cfg.minFreeBytes) + " MB, have "
                            + mb(free) + " MB");
        }

        File setupDir = new File(filesDir, "setup");
        if (!setupDir.isDirectory() && !setupDir.mkdirs()) {
            throw new SetupException(SetupException.Kind.INSTALL, "Cannot create " + setupDir);
        }
        File archive = new File(setupDir, cfg.fileName);
        File part = new File(setupDir, cfg.fileName + ".part");

        // ---- 2. cached download? -----------------------------------------
        if (archive.exists()) {
            ui.onStage("Verifying cached download");
            if (Sha256Verifier.matches(archive, cfg.sha256, cancel)) {
                ui.onLog("[+] Cached download is valid.");
            } else {
                ui.onLog("[!] Cached download is corrupt; downloading again.");
                if (!archive.delete()) {
                    throw new SetupException(SetupException.Kind.INSTALL, "Cannot remove " + archive);
                }
            }
        }

        // ---- 3. download + verify ----------------------------------------
        if (!archive.exists()) {
            ui.onStage("Downloading Ubuntu " + RootfsSource.VERSION + " (arm64)");
            try {
                downloader.download(cfg.url, part, new Downloader.Progress() {
                    public void onProgress(long done, long total) {
                        ui.onProgress(done, total);
                    }
                }, cancel);
            } catch (Downloader.CancelledException e) {
                throw e; // keeps the .part so a later run can resume
            } catch (IOException e) {
                throw new SetupException(SetupException.Kind.DOWNLOAD,
                        String.valueOf(e.getMessage()), e);
            }

            ui.onStage("Verifying SHA-256");
            String actual = Sha256Verifier.sha256Hex(part, cancel);
            if (!actual.equalsIgnoreCase(cfg.sha256)) {
                part.delete(); // our own corrupt download; a retry must start clean
                throw new SetupException(SetupException.Kind.VERIFY,
                        "SHA-256 mismatch (expected " + cfg.sha256 + ", got " + actual + ")");
            }
            ui.onLog("[+] SHA-256 verified.");
            if (!part.renameTo(archive)) {
                throw new SetupException(SetupException.Kind.INSTALL, "Cannot rename " + part);
            }
        }

        // ---- 4. extract into staging -------------------------------------
        File stage = placer.stageDir();
        deleteStage(stage);
        ui.onStage("Extracting");
        try {
            GZIPInputStream in = new GZIPInputStream(
                    new BufferedInputStream(new FileInputStream(archive), 1 << 16));
            try {
                TarExtractor.Result r = new TarExtractor(fs, new TarExtractor.Progress() {
                    public void onEntries(int count, String lastName) {
                        ui.onProgress(count, -1);
                    }
                }).extract(in, stage, cancel);
                // Short lines: the terminal can be as narrow as ~40 columns.
                ui.onLog("[+] Extracted " + r.files + " files, " + r.dirs + " dirs");
                ui.onLog("    " + r.symlinks + " symlinks, " + r.hardlinks + " hardlinks");
                if (r.skipped > 0) {
                    ui.onLog("    " + r.skipped + " special files skipped");
                }
            } finally {
                in.close();
            }
        } catch (Downloader.CancelledException e) {
            deleteStage(stage);
            throw e;
        } catch (IOException e) {
            deleteStage(stage);
            throw new SetupException(SetupException.Kind.EXTRACT,
                    "Extraction failed: " + e.getMessage(), e);
        }

        // ---- 5. layout check ---------------------------------------------
        if (!new File(stage, "bin/bash").exists() || !new File(stage, "etc/os-release").isFile()) {
            deleteStage(stage);
            throw new SetupException(SetupException.Kind.LAYOUT,
                    "Archive layout unexpected: /bin/bash or /etc/os-release missing");
        }

        // ---- 6. move into place (atomic rename) --------------------------
        ui.onStage("Installing");
        placer.place(stage);

        // ---- 7. cleanup of our own download ------------------------------
        if (!archive.delete()) {
            ui.onLog("[!] Could not delete downloaded archive: " + archive);
        }
        ui.onLog("[+] Ubuntu " + RootfsSource.VERSION + " installed.");
    }

    private void deleteStage(File stage) {
        placer.deleteStage(stage);
    }

    private static long mb(long bytes) {
        return bytes / (1024L * 1024L);
    }
}
