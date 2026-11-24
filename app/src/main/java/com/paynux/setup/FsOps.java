package com.paynux.setup;

import java.io.File;
import java.io.IOException;

/** Filesystem primitives java.io lacks. Android and JVM test implementations. */
public interface FsOps {
    /** True if the path itself is a symlink, including a dangling one. Never follows it. */
    boolean isSymlink(File file);

    void symlink(String target, File link) throws IOException;

    void hardlink(File existing, File link) throws IOException;

    /** Sets the full mode, including setuid/setgid/sticky bits (mode & 07777). */
    void chmod(File file, int mode) throws IOException;
}
