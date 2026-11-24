package com.paynux.setup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resumable HTTP(S) downloader. Writes to a ".part" file the caller owns and
 * never touches any other file. A partial file is kept on cancel/failure so a
 * later call can resume with a Range request.
 */
public final class Downloader {

    public interface Progress {
        void onProgress(long done, long total);
    }

    public static final class CancelledException extends IOException {
        public CancelledException() { super("cancelled"); }
    }

    /** Non-retryable failure (e.g. HTTP 404). */
    public static final class FatalException extends IOException {
        public FatalException(String m) { super(m); }
    }

    private final boolean requireHttps;
    private final int maxAttempts;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final long backoffBaseMs;

    public Downloader(boolean requireHttps, int maxAttempts, int connectTimeoutMs,
                      int readTimeoutMs, long backoffBaseMs) {
        this.requireHttps = requireHttps;
        this.maxAttempts = maxAttempts;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.backoffBaseMs = backoffBaseMs;
    }

    /** Production defaults: HTTPS only, 3 attempts, 15 s connect, 30 s read. */
    public static Downloader defaults() {
        return new Downloader(true, 3, 15000, 30000, 1000L);
    }

    public void download(String url, File part, Progress progress, AtomicBoolean cancel)
            throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            checkCancel(cancel);
            try {
                attemptOnce(url, part, progress, cancel);
                return;
            } catch (CancelledException e) {
                throw e;
            } catch (FatalException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                if (attempt < maxAttempts) {
                    sleep(backoffBaseMs * (1L << (attempt - 1)), cancel);
                }
            }
        }
        throw new IOException("download failed after " + maxAttempts + " attempts: "
                + (last == null ? "unknown" : last.getMessage()), last);
    }

    private void attemptOnce(String url, File part, Progress progress, AtomicBoolean cancel)
            throws IOException {
        long existing = part.exists() ? part.length() : 0L;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(connectTimeoutMs);
            c.setReadTimeout(readTimeoutMs);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("User-Agent", "Paynux-Setup");
            if (existing > 0) {
                c.setRequestProperty("Range", "bytes=" + existing + "-");
            }
            int code = c.getResponseCode();
            if (requireHttps && !"https".equalsIgnoreCase(c.getURL().getProtocol())) {
                throw new FatalException("refusing non-HTTPS URL: " + c.getURL());
            }

            boolean append;
            long total;
            if (code == 206) {
                append = true;
                total = parseRangeTotal(c.getHeaderField("Content-Range"));
                if (total < 0) {
                    long len = contentLength(c);
                    total = len >= 0 ? existing + len : -1;
                }
            } else if (code == 200) {
                append = false; // server ignored Range: restart from zero
                existing = 0;
                total = contentLength(c);
            } else if (code == 416) {
                long t = parseRangeTotal(c.getHeaderField("Content-Range"));
                if (t >= 0 && t == existing) {
                    return; // already complete; caller verifies the hash
                }
                // Our own stale .part does not match the server: drop it, retry fresh.
                if (!part.delete()) {
                    throw new FatalException("cannot reset partial file " + part);
                }
                throw new IOException("HTTP 416, restarting from zero");
            } else if (code >= 400 && code < 500) {
                throw new FatalException("HTTP " + code);
            } else {
                throw new IOException("HTTP " + code);
            }

            File parent = part.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new FatalException("cannot create " + parent);
            }
            long done = existing;
            long lastReport = 0;
            byte[] buf = new byte[64 * 1024];
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(part, append);
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    checkCancel(cancel);
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (progress != null && now - lastReport >= 250) {
                        lastReport = now;
                        progress.onProgress(done, total);
                    }
                }
            } finally {
                out.close();
                in.close();
            }
            if (progress != null) progress.onProgress(done, total);
            if (total >= 0 && done != total) {
                throw new IOException("incomplete download: " + done + " of " + total);
            }
        } finally {
            c.disconnect();
        }
    }

    /** getContentLengthLong() needs API 24; minSdk is 23, so parse the header. */
    private static long contentLength(HttpURLConnection c) {
        String v = c.getHeaderField("Content-Length");
        if (v == null) return -1;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Parses "bytes a-b/total" or "bytes * / total"; returns total or -1. */
    static long parseRangeTotal(String header) {
        if (header == null) return -1;
        int slash = header.lastIndexOf('/');
        if (slash < 0) return -1;
        try {
            return Long.parseLong(header.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void checkCancel(AtomicBoolean cancel) throws CancelledException {
        if (cancel != null && cancel.get()) throw new CancelledException();
    }

    private static void sleep(long ms, AtomicBoolean cancel) throws IOException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            checkCancel(cancel);
            try {
                Thread.sleep(Math.min(100, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
        }
    }
}
