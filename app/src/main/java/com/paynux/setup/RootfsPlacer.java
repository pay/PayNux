package com.paynux.setup;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Shared, non-destructive placement of a staged rootfs into its final
 * directory. Used by SetupManager (online) and OfflineInstaller (bundled).
 *
 * Deletes only the staging directory (filesDir/ubuntu-stage, created by the
 * installers themselves). An existing non-empty target is renamed aside,
 * never deleted.
 */
final class RootfsPlacer {

    static final String STAGE_DIR_NAME = "ubuntu-stage";

    private final File filesDir;
    private final File ubuntuDir;
    private final FsOps fs;
    private final SetupListener ui;

    RootfsPlacer(File filesDir, File ubuntuDir, FsOps fs, SetupListener ui) {
        this.filesDir = filesDir;
        this.ubuntuDir = ubuntuDir;
        this.fs = fs;
        this.ui = ui;
    }

    File stageDir() {
        return new File(filesDir, STAGE_DIR_NAME);
    }

    /**
     * Moves stagedRoot to ubuntuDir with an atomic rename, then makes
     * /etc/resolv.conf a regular file (best effort).
     */
    void place(File stagedRoot) throws SetupManager.SetupException {
        moveAsideIfNeeded();
        if (!stagedRoot.renameTo(ubuntuDir)) {
            throw new SetupManager.SetupException(SetupManager.SetupException.Kind.INSTALL,
                    "Cannot move rootfs into place: " + ubuntuDir
                            + " (staging kept at " + stagedRoot + ")");
        }
        writeResolvConf();
    }

    /** An empty target dir is removed; a non-empty one is renamed aside, never deleted. */
    private void moveAsideIfNeeded() throws SetupManager.SetupException {
        if (!ubuntuDir.exists()) return;
        String[] kids = ubuntuDir.list();
        if (kids == null || kids.length == 0) {
            if (!ubuntuDir.delete()) {
                throw new SetupManager.SetupException(SetupManager.SetupException.Kind.INSTALL,
                        "Cannot replace empty " + ubuntuDir);
            }
            return;
        }
        File aside = new File(filesDir, "ubuntu.incomplete-" + System.currentTimeMillis());
        if (!ubuntuDir.renameTo(aside)) {
            throw new SetupManager.SetupException(SetupManager.SetupException.Kind.INSTALL,
                    "Cannot move existing " + ubuntuDir + " aside");
        }
        ui.onLog("[!] Old incomplete rootfs kept as:");
        ui.onLog("    " + aside.getName());
    }

    /** Best effort: make /etc/resolv.conf a regular file (Ubuntu ships a dangling symlink). */
    private void writeResolvConf() {
        try {
            File etc = new File(ubuntuDir, "etc");
            File rc = new File(etc, "resolv.conf");
            if (!etc.isDirectory()) return;
            if (fs.isSymlink(rc) && !rc.delete()) return;
            FileWriter w = new FileWriter(rc, false);
            try {
                w.write("nameserver 8.8.8.8\nnameserver 8.8.4.4\n");
            } finally {
                w.close();
            }
        } catch (IOException e) {
            ui.onLog("[!] resolv.conf not written: " + e.getMessage());
        }
    }

    /** Removes the staging directory. Refuses to touch anything but filesDir/ubuntu-stage. */
    void deleteStage(File stage) {
        try {
            if (!stage.getParentFile().getCanonicalFile().equals(filesDir.getCanonicalFile())
                    || !STAGE_DIR_NAME.equals(stage.getName())) {
                return;
            }
            deleteTree(stage);
        } catch (IOException ignored) {
            // best effort: the next run starts by deleting it again
        }
    }

    private void deleteTree(File f) throws IOException {
        if (fs.isSymlink(f) || f.isFile()) {
            f.delete();
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        f.delete();
    }
}
