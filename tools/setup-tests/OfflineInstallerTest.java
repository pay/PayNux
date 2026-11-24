import com.paynux.setup.OfflineInstaller;
import com.paynux.setup.SetupListener;
import com.paynux.setup.SetupManager.SetupException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sandbox tests for OfflineInstaller. Usage: OfflineInstallerTest <fixturesDir> [real bundled .tar.xz] */
public final class OfflineInstallerTest {
    static int failures = 0;
    static String XZ;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static final class Ui implements SetupListener {
        final List<String> stages = new ArrayList<String>();
        final List<String> logs = new ArrayList<String>();
        public void onStage(String s) { stages.add(s); }
        public void onProgress(long d, long t) { }
        public void onLog(String l) { logs.add(l); }
    }

    static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), "UTF-8"); }

    static int mode(File f) throws IOException { return ((Integer) Files.getAttribute(f.toPath(), "unix:mode")) & 07777; }

    static File freshFilesDir() throws IOException {
        File d = Files.createTempDirectory("files").toFile();
        Files.write(new File(d, "sentinel.txt").toPath(), "keep-me".getBytes("UTF-8"));
        return d;
    }

    static OfflineInstaller.AssetSource asset(final File f) {
        return new OfflineInstaller.AssetSource() {
            public InputStream open() throws IOException { return new FileInputStream(f); }
        };
    }

    static OfflineInstaller make(File files, File ub, File xz, File archive, long min, Ui ui) {
        return new OfflineInstaller(files, ub, xz, new HashMap<String, String>(), new JvmFsOps(), ui,
                "bundled.tar.xz", asset(archive), min);
    }

    static SetupException expect(OfflineInstaller i, AtomicBoolean cancel) {
        try { i.install(cancel); return null; } catch (SetupException e) { return e; }
    }

    static File script(String body) throws Exception {
        File f = File.createTempFile("fakexz", ".sh");
        Files.write(f.toPath(), ("#!/bin/sh\n" + body + "\n").getBytes("UTF-8"));
        f.setExecutable(true); f.deleteOnExit();
        return f;
    }

    static String sh(String cmd) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), "UTF-8").trim();
        p.waitFor();
        return out;
    }

    public static void main(String[] args) throws Exception {
        try { run(new File(args[0]), args.length > 1 ? new File(args[1]) : null); }
        catch (Throwable t) { t.printStackTrace(); System.exit(2); }
    }

    static void run(File fx, File real) throws Exception {
        XZ = sh("command -v xz");
        File xz = new File(XZ);
        File good = new File(fx, "good_offline.tar.xz");
        File bad = new File(fx, "badlayout_offline.tar.xz");
        long MIN = 1024L * 1024L;

        // 1. success (small fixture)
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu"); Ui ui = new Ui();
            SetupException e = expect(make(files, ub, xz, good, MIN, ui), null);
            check("fixture: installs", e == null && new File(ub, "bin/bash").exists() && new File(ub, "etc/os-release").isFile());
            check("fixture: /tmp 1777 and passwd 4755 (exact modes, no chmod -R)",
                    mode(new File(ub, "tmp")) == 01777 && mode(new File(ub, "usr/bin/passwd")) == 04755);
            check("fixture: manifest.json not inside rootfs; stage + archive copy removed",
                    !new File(ub, "manifest.json").exists() && !new File(files, "ubuntu-stage").exists()
                            && !new File(files, "setup/bundled.tar.xz").exists() && !new File(files, "setup/bundled.tar.xz.tmp").exists());
            check("fixture: resolv.conf regular file", !Files.isSymbolicLink(new File(ub, "etc/resolv.conf").toPath())
                    && read(new File(ub, "etc/resolv.conf")).contains("nameserver"));
            check("fixture: sentinel untouched", read(new File(files, "sentinel.txt")).equals("keep-me"));
            boolean longLine = false;
            for (String l : ui.logs) if (l.length() > 40) longLine = true;
            check("fixture: no log line longer than 40 columns", !longLine);
            check("fixture: stages Preparing -> Extracting -> Installing",
                    ui.stages.equals(java.util.Arrays.asList("Preparing bundled rootfs", "Extracting", "Installing")));
            check("already installed: no-op", expect(make(files, ub, xz, good, MIN, new Ui()), null) == null);
        }
        // 2. xz fails right away
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu");
            SetupException e = expect(make(files, ub, script("echo 'xz: boom' >&2; exit 1"), good, MIN, new Ui()), null);
            check("xz exit 1 -> EXTRACT with stderr tail", e != null && e.kind == SetupException.Kind.EXTRACT && e.getMessage().contains("boom"));
            check("xz failure: nothing installed, stage cleaned, sentinel kept",
                    !ub.exists() && !new File(files, "ubuntu-stage").exists() && read(new File(files, "sentinel.txt")).equals("keep-me"));
        }
        // 3. truncated stream (xz exits 0 but output cut)
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu");
            SetupException e = expect(make(files, ub, script(XZ + " \"$@\" | head -c 1500"), good, MIN, new Ui()), null);
            check("truncated stream -> EXTRACT, no ubuntu dir", e != null && e.kind == SetupException.Kind.EXTRACT && !ub.exists()
                    && !new File(files, "ubuntu-stage").exists());
        }
        // 4. complete output but non-zero exit
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu");
            SetupException e = expect(make(files, ub, script(XZ + " \"$@\"; exit 2"), good, MIN, new Ui()), null);
            check("full output but exit 2 -> EXTRACT mentions code 2, not installed",
                    e != null && e.kind == SetupException.Kind.EXTRACT && e.getMessage().contains("code 2") && !ub.exists());
        }
        // 5. missing xz binary
        {
            File files = freshFilesDir();
            SetupException e = expect(make(files, new File(files, "ubuntu"), new File("/nonexistent/xz"), good, MIN, new Ui()), null);
            check("missing xz -> INSTALL, clean message", e != null && e.kind == SetupException.Kind.INSTALL && e.getMessage().contains("xz executable missing"));
        }
        // 6. wrong layout
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu");
            SetupException e = expect(make(files, ub, xz, bad, MIN, new Ui()), null);
            check("bad layout -> LAYOUT, nothing installed, stage cleaned",
                    e != null && e.kind == SetupException.Kind.LAYOUT && !ub.exists() && !new File(files, "ubuntu-stage").exists());
        }
        // 7. existing non-empty dir moved aside; empty dir replaced
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu"); ub.mkdirs();
            Files.write(new File(ub, "precious.txt").toPath(), "do-not-delete".getBytes("UTF-8"));
            SetupException e = expect(make(files, ub, xz, good, MIN, new Ui()), null);
            File aside = null;
            for (File f : files.listFiles()) if (f.getName().startsWith("ubuntu.incomplete-")) aside = f;
            check("non-empty dir: installed and old content kept aside",
                    e == null && new File(ub, "bin/bash").exists() && aside != null
                            && read(new File(aside, "precious.txt")).equals("do-not-delete"));
            File files2 = freshFilesDir(); File ub2 = new File(files2, "ubuntu"); ub2.mkdirs();
            check("empty dir replaced, nothing set aside", expect(make(files2, ub2, xz, good, MIN, new Ui()), null) == null
                    && files2.list((d, n) -> n.startsWith("ubuntu.incomplete-")).length == 0);
        }
        // 8. no space
        {
            File files = freshFilesDir();
            SetupException e = expect(make(files, new File(files, "ubuntu"), xz, good, Long.MAX_VALUE / 2, new Ui()), null);
            check("no space -> NO_SPACE", e != null && e.kind == SetupException.Kind.NO_SPACE);
        }
        // 9. cancel
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu");
            SetupException e = expect(make(files, ub, xz, good, MIN, new Ui()), new AtomicBoolean(true));
            check("cancel -> CANCELLED, nothing installed", e != null && e.kind == SetupException.Kind.CANCELLED && !ub.exists()
                    && !new File(files, "ubuntu-stage").exists());
        }
        // 10. stale/corrupt archive copy from an older run is replaced by a fresh copy
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu"); new File(files, "setup").mkdirs();
            Files.write(new File(files, "setup/bundled.tar.xz").toPath(), "garbage".getBytes("UTF-8"));
            check("stale archive copy replaced (always recopied)", expect(make(files, ub, xz, good, MIN, new Ui()), null) == null
                    && new File(ub, "bin/bash").exists());
        }
        // 11. asset cannot be read
        {
            File files = freshFilesDir();
            OfflineInstaller i = new OfflineInstaller(files, new File(files, "ubuntu"), xz, new HashMap<String, String>(),
                    new JvmFsOps(), new Ui(), "bundled.tar.xz", new OfflineInstaller.AssetSource() {
                        public InputStream open() throws IOException { throw new IOException("asset missing"); }
                    }, MIN);
            SetupException e = expect(i, null);
            check("asset unreadable -> INSTALL, no leftovers", e != null && e.kind == SetupException.Kind.INSTALL
                    && !new File(files, "setup/bundled.tar.xz.tmp").exists());
        }
        // 12. empty asset
        {
            File files = freshFilesDir();
            OfflineInstaller i = new OfflineInstaller(files, new File(files, "ubuntu"), xz, new HashMap<String, String>(),
                    new JvmFsOps(), new Ui(), "bundled.tar.xz", new OfflineInstaller.AssetSource() {
                        public InputStream open() { return new ByteArrayInputStream(new byte[0]); }
                    }, MIN);
            SetupException e = expect(i, null);
            check("empty asset -> INSTALL", e != null && e.kind == SetupException.Kind.INSTALL);
        }
        // 13. the REAL bundled rootfs
        if (real != null && real.isFile()) {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu"); Ui ui = new Ui();
            long t0 = System.currentTimeMillis();
            SetupException e = expect(make(files, ub, xz, real, OfflineInstaller.DEFAULT_MIN_FREE_BYTES, ui), null);
            System.out.println("     real rootfs install took " + (System.currentTimeMillis() - t0) + " ms; logs=" + ui.logs);
            check("REAL rootfs: installs", e == null && new File(ub, "bin/bash").exists() && new File(ub, "etc/os-release").isFile());
            check("REAL rootfs: /tmp is 1777", mode(new File(ub, "tmp")) == 01777);
            check("REAL rootfs: passwd keeps setuid 4755", mode(new File(ub, "usr/bin/passwd")) == 04755);
            String expectHash = sh(XZ + " -dc '" + real + "' | tar -xOf - ubuntu/rootfs/usr/bin/bash | sha256sum | cut -d' ' -f1");
            String gotHash = sh("sha256sum '" + new File(ub, "usr/bin/bash") + "' | cut -d' ' -f1");
            check("REAL rootfs: /usr/bin/bash content identical to archive", expectHash.length() == 64 && expectHash.equals(gotHash));
            check("REAL rootfs: stage and archive copy removed, manifest not in rootfs",
                    !new File(files, "ubuntu-stage").exists() && !new File(files, "setup/bundled.tar.xz").exists()
                            && !new File(ub, "manifest.json").exists());
        } else {
            System.out.println("SKIP real bundled rootfs not given");
        }
        System.out.println(failures == 0 ? "ALL OFFLINE INSTALLER TESTS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
