package com.paynux.setup;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Streaming SHA-256 helpers. */
public final class Sha256Verifier {
    private Sha256Verifier() {}

    public static String sha256Hex(File file, AtomicBoolean cancel) throws IOException {
        MessageDigest md = newDigest();
        byte[] buf = new byte[64 * 1024];
        InputStream in = new FileInputStream(file);
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel != null && cancel.get()) {
                    throw new Downloader.CancelledException();
                }
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return toHex(md.digest());
    }

    public static boolean matches(File file, String expectedHex, AtomicBoolean cancel)
            throws IOException {
        return sha256Hex(file, cancel).equalsIgnoreCase(expectedHex.trim());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static String toHex(byte[] b) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = hex[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = hex[b[i] & 0xF];
        }
        return new String(out);
    }
}
