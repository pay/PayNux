package com.paynux.compat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Android system partitions that PRoot can expose inside the guest at the same
 * path. Only paths that exist and are usable on this device are selected;
 * anything else is skipped silently.
 */
public final class AndroidPaths {
    private AndroidPaths() {}

    /** In the order they are bound. */
    public static final String[] CANDIDATES = {"/system", "/apex", "/vendor", "/product"};

    /** Decides whether a host path can be bound. */
    public interface Probe {
        boolean usable(String path);
    }

    /** Exists, is a folder, can be read and searched by this process. Never throws. */
    public static boolean isUsableDir(File f) {
        try {
            return f.isDirectory() && f.canRead() && f.canExecute();
        } catch (SecurityException e) {
            return false;
        }
    }

    public static Probe defaultProbe() {
        return new Probe() {
            public boolean usable(String path) {
                return isUsableDir(new File(path));
            }
        };
    }

    /** The candidate paths the probe accepts, in order, without duplicates. */
    public static List<String> select(Probe probe) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < CANDIDATES.length; i++) {
            String p = CANDIDATES[i];
            boolean ok;
            try {
                ok = probe.usable(p);
            } catch (RuntimeException e) {
                ok = false;
            }
            if (ok && !out.contains(p)) out.add(p);
        }
        return out;
    }
}
