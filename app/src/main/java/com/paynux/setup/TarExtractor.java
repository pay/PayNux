package com.paynux.setup;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal tar extractor that restores exact modes (including setuid, setgid and
 * sticky bits) instead of relying on a blanket chmod.
 *
 * Supports ustar, pax (x/g) and GNU long name/link (L/K) headers, regular
 * files, directories, symlinks and hardlinks. Device nodes, FIFOs and sparse
 * files cannot be created by an app: devices/FIFOs are skipped and counted,
 * sparse files are rejected.
 *
 * Safety: absolute paths and ".." components are rejected, and nothing is ever
 * written through a symlink that points outside the destination.
 *
 * Mode policy: all bits are applied exactly as archived, except that the owner
 * always keeps rwx on directories and r on files, so the app's own uid can
 * traverse, read and later delete everything it extracted.
 */
public final class TarExtractor {

    public interface Progress {
        void onEntries(int count, String lastName);
    }

    public static final class Result {
        public int files, dirs, symlinks, hardlinks, skipped;
        public long bytes;

        @Override
        public String toString() {
            return "files=" + files + " dirs=" + dirs + " symlinks=" + symlinks
                    + " hardlinks=" + hardlinks + " skipped=" + skipped + " bytes=" + bytes;
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int BLOCK = 512;

    private final FsOps fs;
    private final Progress progress;

    public TarExtractor(FsOps fs, Progress progress) {
        this.fs = fs;
        this.progress = progress;
    }

    /** Extracts an already-decompressed tar stream into destRoot (created if needed). */
    public Result extract(InputStream in, File destRoot, AtomicBoolean cancel)
            throws IOException {
        if (!destRoot.isDirectory() && !destRoot.mkdirs()) {
            throw new IOException("cannot create " + destRoot);
        }
        final File root = destRoot.getCanonicalFile();
        final Set<String> safeDirs = new HashSet<String>();
        final Map<File, Integer> dirModes = new LinkedHashMap<File, Integer>();
        final Result result = new Result();
        final byte[] header = new byte[BLOCK];

        Map<String, String> paxGlobal = new HashMap<String, String>();
        Map<String, String> paxNext = new HashMap<String, String>();
        String gnuLongName = null;
        String gnuLongLink = null;
        int entries = 0;

        while (true) {
            if (cancel != null && cancel.get()) throw new Downloader.CancelledException();
            int got = readFully(in, header, BLOCK);
            if (got == 0) break; // clean EOF without trailer
            if (got < BLOCK) throw new IOException("truncated tar header");
            if (isZeroBlock(header)) break; // end-of-archive marker

            verifyChecksum(header);
            char type = (char) (header[156] & 0xFF);
            long size = parseNumeric(header, 124, 12);
            int mode = (int) parseNumeric(header, 100, 8) & 07777;

            if (type == 'x' || type == 'g') {
                Map<String, String> recs = parsePax(readBytes(in, size));
                skipPadding(in, size);
                if (type == 'x') paxNext.putAll(recs); else paxGlobal.putAll(recs);
                continue;
            }
            if (type == 'L' || type == 'K') {
                String v = cString(readBytes(in, size));
                skipPadding(in, size);
                if (type == 'L') gnuLongName = v; else gnuLongLink = v;
                continue;
            }

            String name = headerName(header);
            String linkName = cString(header, 157, 100);
            if (gnuLongName != null) name = gnuLongName;
            if (gnuLongLink != null) linkName = gnuLongLink;
            String v;
            if ((v = lookup(paxNext, paxGlobal, "path")) != null) name = v;
            if ((v = lookup(paxNext, paxGlobal, "linkpath")) != null) linkName = v;
            if ((v = paxNext.get("size")) != null) size = Long.parseLong(v.trim());
            gnuLongName = null;
            gnuLongLink = null;
            paxNext.clear();

            String rel = normalize(name);
            if (rel == null) { // "./" root entry
                skipData(in, size);
                continue;
            }
            File dest = new File(root, rel);
            entries++;

            switch (type) {
                case '0':
                case '\0':
                case '7': {
                    ensureParent(dest, root, safeDirs);
                    removeIfNotDir(dest);
                    long written = writeFile(in, dest, size, cancel);
                    skipPadding(in, size);
                    fs.chmod(dest, mode | 0400);
                    result.files++;
                    result.bytes += written;
                    break;
                }
                case '5': {
                    ensureDir(dest, root, safeDirs);
                    dirModes.put(dest, mode | 0700);
                    skipData(in, size);
                    result.dirs++;
                    break;
                }
                case '2': {
                    ensureParent(dest, root, safeDirs);
                    removeIfNotDir(dest);
                    fs.symlink(linkName, dest);
                    skipData(in, size);
                    result.symlinks++;
                    break;
                }
                case '1': {
                    String targetRel = normalize(linkName);
                    if (targetRel == null) throw new IOException("bad hardlink target: " + linkName);
                    File target = new File(root, targetRel);
                    ensureParent(dest, root, safeDirs);
                    ensureParent(target, root, safeDirs);
                    removeIfNotDir(dest);
                    try {
                        fs.hardlink(target, dest);
                    } catch (IOException e) {
                        copyFile(target, dest); // fallback where link() is denied
                        fs.chmod(dest, mode | 0400);
                    }
                    skipData(in, size);
                    result.hardlinks++;
                    break;
                }
                case '3': // char device
                case '4': // block device
                case '6': // fifo
                    skipData(in, size);
                    result.skipped++;
                    break;
                default:
                    throw new IOException("unsupported tar entry type '" + type + "' for " + name);
            }
            if (progress != null && entries % 200 == 0) progress.onEntries(entries, rel);
        }

        // Directory modes last: restrictive modes (e.g. 0555) must not block children.
        List<Map.Entry<File, Integer>> list = new ArrayList<Map.Entry<File, Integer>>(dirModes.entrySet());
        for (int i = list.size() - 1; i >= 0; i--) {
            fs.chmod(list.get(i).getKey(), list.get(i).getValue());
        }
        if (progress != null) progress.onEntries(entries, "");
        return result;
    }

    // ---- path handling -------------------------------------------------

    /** Returns a clean relative path, or null for the archive root. Throws if unsafe. */
    static String normalize(String name) throws IOException {
        if (name.indexOf('\0') >= 0) throw new IOException("NUL in path");
        if (name.startsWith("/")) throw new IOException("absolute path rejected: " + name);
        StringBuilder sb = new StringBuilder();
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new IOException("path traversal rejected: " + name);
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void ensureParent(File dest, File root, Set<String> safeDirs) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null) ensureDir(parent, root, safeDirs);
    }

    /** Creates dir (and parents) refusing to pass through a symlink. */
    private void ensureDir(File dir, File root, Set<String> safeDirs) throws IOException {
        String path = dir.getPath();
        if (safeDirs.contains(path) || dir.equals(root)) return;
        File parent = dir.getParentFile();
        if (parent != null && !parent.equals(root)) ensureDir(parent, root, safeDirs);
        if (dir.exists() || fs.isSymlink(dir)) {
            if (fs.isSymlink(dir) || !dir.isDirectory()) {
                throw new IOException("refusing to write through non-directory/symlink: " + dir);
            }
        } else if (!dir.mkdir()) {
            throw new IOException("cannot create directory " + dir);
        }
        safeDirs.add(path);
    }

    private void removeIfNotDir(File f) throws IOException {
        if (fs.isSymlink(f) || f.isFile()) {
            if (!f.delete()) throw new IOException("cannot replace " + f);
        } else if (f.isDirectory()) {
            throw new IOException("entry replaces a directory: " + f);
        }
    }

    // ---- data helpers --------------------------------------------------

    private static long writeFile(InputStream in, File dest, long size, AtomicBoolean cancel)
            throws IOException {
        OutputStream out = new FileOutputStream(dest);
        try {
            byte[] buf = new byte[64 * 1024];
            long left = size;
            while (left > 0) {
                if (cancel != null && cancel.get()) throw new Downloader.CancelledException();
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("truncated tar data for " + dest);
                out.write(buf, 0, n);
                left -= n;
            }
        } finally {
            out.close();
        }
        return size;
    }

    private static void copyFile(File from, File to) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(from);
        try {
            OutputStream out = new FileOutputStream(to);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static void skipData(InputStream in, long size) throws IOException {
        long total = padded(size);
        byte[] buf = new byte[8192];
        while (total > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, total));
            if (n < 0) throw new IOException("truncated tar");
            total -= n;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = padded(size) - size;
        while (pad > 0) {
            long n = in.skip(pad);
            if (n <= 0) {
                if (in.read() < 0) throw new IOException("truncated tar");
                n = 1;
            }
            pad -= n;
        }
    }

    private static long padded(long size) {
        return (size + BLOCK - 1) / BLOCK * BLOCK;
    }

    private static byte[] readBytes(InputStream in, long size) throws IOException {
        if (size < 0 || size > 1024 * 1024) throw new IOException("bad extended header size " + size);
        byte[] b = new byte[(int) size];
        if (readFully(in, b, b.length) < b.length) throw new IOException("truncated tar");
        return b;
    }

    private static int readFully(InputStream in, byte[] b, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return off;
    }

    // ---- header parsing ------------------------------------------------

    private static boolean isZeroBlock(byte[] b) {
        for (int i = 0; i < BLOCK; i++) if (b[i] != 0) return false;
        return true;
    }

    private static void verifyChecksum(byte[] h) throws IOException {
        long unsigned = 0, signed = 0;
        for (int i = 0; i < BLOCK; i++) {
            int v = (i >= 148 && i < 156) ? ' ' : h[i];
            unsigned += v & 0xFF;
            signed += v;
        }
        long stored = parseNumeric(h, 148, 8);
        if (stored != unsigned && stored != signed) throw new IOException("bad tar header checksum");
    }

    /** Octal field, or GNU base-256 when the high bit of the first byte is set. */
    static long parseNumeric(byte[] b, int off, int len) throws IOException {
        if ((b[off] & 0x80) != 0) {
            long v = b[off] & 0x7F;
            for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xFF);
            return v;
        }
        long v = 0;
        boolean any = false;
        for (int i = 0; i < len; i++) {
            int c = b[off + i];
            if (c == 0 || c == ' ') { if (any) break; else continue; }
            if (c < '0' || c > '7') throw new IOException("bad octal field");
            v = (v << 3) | (c - '0');
            any = true;
        }
        return v;
    }

    private static String headerName(byte[] h) {
        String name = cString(h, 0, 100);
        boolean ustar = h[257] == 'u' && h[258] == 's' && h[259] == 't'
                && h[260] == 'a' && h[261] == 'r' && h[262] == 0;
        if (ustar) {
            String prefix = cString(h, 345, 155);
            if (!prefix.isEmpty()) return prefix + "/" + name;
        }
        return name;
    }

    private static String cString(byte[] b, int off, int max) {
        int end = off;
        while (end < off + max && b[end] != 0) end++;
        return new String(b, off, end - off, UTF8);
    }

    private static String cString(byte[] b) {
        return cString(b, 0, b.length);
    }

    private static String lookup(Map<String, String> next, Map<String, String> global, String key) {
        String v = next.get(key);
        return v != null ? v : global.get(key);
    }

    /** Pax records: "<len> <key>=<value>\n", len counts the whole record in bytes. */
    static Map<String, String> parsePax(byte[] data) throws IOException {
        Map<String, String> out = new HashMap<String, String>();
        int pos = 0;
        while (pos < data.length) {
            int sp = pos;
            while (sp < data.length && data[sp] != ' ') sp++;
            if (sp >= data.length) throw new IOException("bad pax record");
            int len;
            try {
                len = Integer.parseInt(new String(data, pos, sp - pos, UTF8));
            } catch (NumberFormatException e) {
                throw new IOException("bad pax length");
            }
            if (len <= 0 || pos + len > data.length) throw new IOException("bad pax length");
            int end = pos + len - 1; // strip trailing '\n'
            ByteArrayOutputStream kv = new ByteArrayOutputStream();
            kv.write(data, sp + 1, end - (sp + 1));
            String rec = new String(kv.toByteArray(), UTF8);
            int eq = rec.indexOf('=');
            if (eq > 0) out.put(rec.substring(0, eq), rec.substring(eq + 1));
            pos += len;
        }
        return out;
    }
}
