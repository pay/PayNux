package com.paynux.setup;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;

/** FsOps backed by android.system.Os (API 21+). */
public final class AndroidFsOps implements FsOps {

    @Override
    public boolean isSymlink(File file) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(file.getPath()).st_mode);
        } catch (ErrnoException e) {
            return false; // does not exist (or unreadable): not a symlink we can follow
        }
    }

    @Override
    public void symlink(String target, File link) throws IOException {
        try {
            Os.symlink(target, link.getPath());
        } catch (ErrnoException e) {
            throw new IOException("symlink " + link + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void hardlink(File existing, File link) throws IOException {
        try {
            Os.link(existing.getPath(), link.getPath());
        } catch (ErrnoException e) {
            throw new IOException("link " + link + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void chmod(File file, int mode) throws IOException {
        try {
            Os.chmod(file.getPath(), mode & 07777);
        } catch (ErrnoException e) {
            throw new IOException("chmod " + file + ": " + e.getMessage(), e);
        }
    }
}
