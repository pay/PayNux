package com.paynux.compat;

import com.paynux.setup.FsOps;

import java.io.File;
import java.io.IOException;

/**
 * Android has no /dev/shm, but POSIX shared memory (Python multiprocessing,
 * Chromium, PostgreSQL, ...) needs one. This prepares an app-private folder
 * with the usual mode (1777, like a tmpfs /dev/shm) and returns the PRoot bind
 * spec "<folder>:/dev/shm".
 *
 * It never deletes anything. Leftover files from crashed programs are tiny;
 * clear them with "rm -f /dev/shm/*" inside PayNux if you ever want to.
 */
public final class ShmDir {
    private ShmDir() {}

    public static final int MODE = 01777;

    public static String prepare(File dir, FsOps fs) throws IOException {
        if (fs.isSymlink(dir)) {
            throw new IOException("refusing to use a symlink as /dev/shm: " + dir);
        }
        if (dir.exists() && !dir.isDirectory()) {
            throw new IOException(dir + " exists and is not a directory");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        fs.chmod(dir, MODE);
        return dir.getAbsolutePath() + ":/dev/shm";
    }
}
