package com.paynux.pty;

import java.nio.charset.StandardCharsets;

public final class PtyNative {
    static { System.loadLibrary("paynuxpty"); }
    public static void ensureLoaded() {}
    public static native void nativeClose(long h);
    public static native int nativeIsAlive(long h);
    public static native int nativeKill(long h, int sig);
    public static native int nativePid(long h);
    public static native int nativeRead(long h, byte[] buf, int off, int len, int timeoutMs);
    public static native int nativeResize(long h, int rows, int cols);
    public static native long nativeSpawn(byte[] argvBlob, byte[] envpBlob, String cwd, int rows, int cols);
    public static native int nativeWait(long h);
    public static native int nativeWrite(long h, byte[] buf, int off, int len);

    public static byte[] toBlob(String[] values) {
        if (values == null || values.length == 0) return new byte[0];
        int size = 0;
        for (String v : values) size += v.getBytes(StandardCharsets.UTF_8).length + 1;
        byte[] blob = new byte[size];
        int pos = 0;
        for (String v : values) {
            byte[] raw = v.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(raw, 0, blob, pos, raw.length);
            pos += raw.length;
            blob[pos] = 0;
            pos++;
        }
        return blob;
    }
}
