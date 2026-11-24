package com.paynux.setup;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Runs a short command inside the rootfs through PRoot and checks the result:
 * the shell starts, /etc/os-release names the expected Ubuntu version, fake
 * root works and /tmp has its sticky bit. Never modifies the rootfs.
 *
 * The caller supplies the process environment that the real shell uses
 * (LD_LIBRARY_PATH, PROOT_LOADER, PROOT_TMP_DIR, PROOT_NO_SECCOMP, ...).
 */
public final class ProotSelfTest {

    public static final class Result {
        /** True when the shell ran and os-release matched (warnings do not fail it). */
        public final boolean ok;
        public final List<String> lines;

        Result(boolean ok, List<String> lines) {
            this.ok = ok;
            this.lines = lines;
        }

        public String report() {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            return sb.toString();
        }
    }

    private final File proot;
    private final File rootfs;
    private final Map<String, String> env;
    private final String versionId;
    private final long timeoutMs;

    public ProotSelfTest(File proot, File rootfs, Map<String, String> env,
                         String versionId, long timeoutMs) {
        this.proot = proot;
        this.rootfs = rootfs;
        this.env = env;
        this.versionId = versionId;
        this.timeoutMs = timeoutMs;
    }

    public Result run() {
        List<String> lines = new ArrayList<String>();
        if (!proot.exists()) {
            lines.add("[!] FAIL: proot not found: " + proot);
            return new Result(false, lines);
        }
        List<String> cmd = new ArrayList<String>();
        cmd.add(proot.getAbsolutePath());
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(rootfs.getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/dev");
        cmd.add("-b"); cmd.add("/sys");
        cmd.add("-w"); cmd.add("/");
        cmd.add("/bin/sh");
        cmd.add("-c");
        cmd.add("/bin/cat /etc/os-release; echo UID=$(/usr/bin/id -u); /usr/bin/ls -ld /tmp");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(env);
        pb.environment().put("HOME", "/root");
        pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        pb.redirectErrorStream(true);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            lines.add("[!] FAIL: cannot start proot: " + e.getMessage());
            return new Result(false, lines);
        }
        final InputStream in = p.getInputStream();
        Thread reader = new Thread(new Runnable() {
            public void run() {
                byte[] buf = new byte[4096];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        synchronized (out) {
                            if (out.size() < 65536) out.write(buf, 0, n);
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        });
        reader.setDaemon(true);
        reader.start();

        // Process.waitFor(timeout) needs API 26; minSdk is 23, so poll.
        long deadline = System.currentTimeMillis() + timeoutMs;
        Integer exit = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                exit = Integer.valueOf(p.exitValue());
                break;
            } catch (IllegalThreadStateException running) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (exit == null) {
            p.destroy();
            lines.add("[!] FAIL: proot self-test timed out after " + (timeoutMs / 1000) + " s");
            return new Result(false, lines);
        }
        try {
            reader.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        String text;
        synchronized (out) {
            text = new String(out.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
        }

        boolean ok = true;
        if (exit.intValue() != 0) {
            ok = false;
            lines.add("[!] FAIL: shell exited with code " + exit);
        } else {
            lines.add("[+] PRoot shell runs.");
        }
        Pattern ver = Pattern.compile("(?m)^VERSION_ID=\"?" + Pattern.quote(versionId) + "\"?\\s*$");
        if (ver.matcher(text).find() && text.contains("ID=ubuntu")) {
            lines.add("[+] os-release: Ubuntu " + versionId);
        } else {
            ok = false;
            lines.add("[!] FAIL: /etc/os-release does not report Ubuntu " + versionId);
        }
        if (Pattern.compile("(?m)^UID=0\\s*$").matcher(text).find()) {
            lines.add("[+] Fake root (uid 0) works.");
        } else {
            lines.add("[!] WARN: uid is not 0 inside proot");
        }
        if (Pattern.compile("(?m)^drwxrwxrwt\\b").matcher(text).find()) {
            lines.add("[+] /tmp is 1777 (sticky).");
        } else {
            lines.add("[!] WARN: /tmp is not drwxrwxrwt");
        }
        if (!ok) {
            String tail = text.trim();
            if (tail.length() > 400) tail = tail.substring(tail.length() - 400);
            if (!tail.isEmpty()) lines.add("    output: " + tail.replace('\n', ' '));
        }
        return new Result(ok, lines);
    }
}
