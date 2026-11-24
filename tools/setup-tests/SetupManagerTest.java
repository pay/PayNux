import com.paynux.setup.Downloader;
import com.paynux.setup.SetupListener;
import com.paynux.setup.SetupManager;
import com.paynux.setup.SetupManager.SetupException;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Sandbox tests for SetupManager. Usage: SetupManagerTest <fixturesDir> */
public final class SetupManagerTest {
    static int failures = 0;
    static final AtomicInteger hits = new AtomicInteger();

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

    public static void main(String[] args) throws Exception {
        try { run(new File(args[0])); } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
    }

    static File freshFilesDir() throws IOException {
        File d = Files.createTempDirectory("files").toFile();
        Files.write(new File(d, "sentinel.txt").toPath(), "keep-me".getBytes("UTF-8"));
        return d;
    }

    static SetupException expect(SetupManager m, AtomicBoolean cancel) {
        try { m.install(cancel); return null; } catch (SetupException e) { return e; }
    }

    static void run(File fx) throws Exception {
        final byte[] good = Files.readAllBytes(new File(fx, "good.tar.gz").toPath());
        final byte[] bad = Files.readAllBytes(new File(fx, "badlayout.tar.gz").toPath());
        String goodSha = read(new File(fx, "good.sha256"));
        String badSha = read(new File(fx, "badlayout.sha256"));

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            hits.incrementAndGet();
            String path = ex.getRequestURI().getPath();
            byte[] body = path.equals("/good.tar.gz") ? good : path.equals("/badlayout.tar.gz") ? bad : null;
            if (body == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        srv.start();
        String base = "http://127.0.0.1:" + srv.getAddress().getPort() + "/";
        Downloader dl = new Downloader(false, 2, 3000, 3000, 10L);
        long MIN = 1024L * 1024L; // tiny threshold for tests

        // 1. success
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu"); Ui ui = new Ui();
            SetupManager m = new SetupManager(files, ub,
                    new SetupManager.Config(base + "good.tar.gz", "good.tar.gz", goodSha, MIN), dl, new JvmFsOps(), ui);
            SetupException e = expect(m, null);
            check("success: no exception", e == null);
            check("success: bin/bash and os-release present",
                    new File(ub, "bin/bash").exists() && new File(ub, "etc/os-release").isFile());
            int mode = (Integer) Files.getAttribute(new File(ub, "tmp").toPath(), "unix:mode") & 07777;
            check("success: /tmp keeps 1777", mode == 01777);
            check("success: resolv.conf is a regular file with nameserver",
                    !Files.isSymbolicLink(new File(ub, "etc/resolv.conf").toPath())
                            && read(new File(ub, "etc/resolv.conf")).contains("nameserver"));
            check("success: staging and download removed",
                    !new File(files, "ubuntu-stage").exists() && !new File(files, "setup/good.tar.gz").exists()
                            && !new File(files, "setup/good.tar.gz.part").exists());
            check("success: unrelated file untouched", read(new File(files, "sentinel.txt")).equals("keep-me"));
            boolean sawExtracted = false, sawLinks = false, longLine = false;
            for (String l : ui.logs) {
                if (l.startsWith("[+] Extracted ")) sawExtracted = true;
                if (l.contains("symlinks,") && l.contains("hardlinks")) sawLinks = true;
                if (l.length() > 40 && !l.startsWith("[!] Cached")) longLine = true;
            }
            check("success: summary lines present (files/dirs, symlinks/hardlinks)", sawExtracted && sawLinks);
            check("success: no log line longer than 40 columns", !longLine);
            check("success: stages reported in order",
                    ui.stages.get(0).equals("Checking free space") && ui.stages.contains("Extracting")
                            && ui.stages.get(ui.stages.size() - 1).equals("Installing"));
            // 1b. second call is a no-op
            hits.set(0);
            expect(m, null);
            check("already installed: no network, no change", hits.get() == 0);
        }
        // 2. hash mismatch
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu");
            SetupManager m = new SetupManager(files, ub,
                    new SetupManager.Config(base + "good.tar.gz", "good.tar.gz", badSha, MIN), dl, new JvmFsOps(), new Ui());
            SetupException e = expect(m, null);
            check("hash mismatch -> VERIFY", e != null && e.kind == SetupException.Kind.VERIFY);
            check("hash mismatch: nothing installed, .part removed, sentinel kept",
                    !ub.exists() && !new File(files, "setup/good.tar.gz.part").exists()
                            && read(new File(files, "sentinel.txt")).equals("keep-me"));
        }
        // 3. no space -> no download
        {
            File files = freshFilesDir(); hits.set(0);
            SetupManager m = new SetupManager(files, new File(files, "ubuntu"),
                    new SetupManager.Config(base + "good.tar.gz", "good.tar.gz", goodSha, Long.MAX_VALUE / 2), dl, new JvmFsOps(), new Ui());
            SetupException e = expect(m, null);
            check("no space -> NO_SPACE before any request", e != null && e.kind == SetupException.Kind.NO_SPACE && hits.get() == 0);
        }
        // 4. 404
        {
            File files = freshFilesDir(); hits.set(0);
            SetupManager m = new SetupManager(files, new File(files, "ubuntu"),
                    new SetupManager.Config(base + "missing.tar.gz", "missing.tar.gz", goodSha, MIN), dl, new JvmFsOps(), new Ui());
            SetupException e = expect(m, null);
            check("404 -> DOWNLOAD, single request", e != null && e.kind == SetupException.Kind.DOWNLOAD && hits.get() == 1);
            check("404: message has no duplicated \"Download failed\" prefix", e != null && !e.getMessage().startsWith("Download failed"));
            check("404: sentinel kept, no ubuntu dir", read(new File(files, "sentinel.txt")).equals("keep-me")
                    && !new File(files, "ubuntu").exists());
        }
        // 5. non-empty incomplete ubuntu dir is moved aside, not deleted
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu"); ub.mkdirs();
            Files.write(new File(ub, "precious.txt").toPath(), "do-not-delete".getBytes("UTF-8"));
            SetupManager m = new SetupManager(files, ub,
                    new SetupManager.Config(base + "good.tar.gz", "good.tar.gz", goodSha, MIN), dl, new JvmFsOps(), new Ui());
            SetupException e = expect(m, null);
            File aside = null;
            for (File f : files.listFiles()) if (f.getName().startsWith("ubuntu.incomplete-")) aside = f;
            check("non-empty dir: installed", e == null && new File(ub, "bin/bash").exists());
            check("non-empty dir: old content preserved aside",
                    aside != null && read(new File(aside, "precious.txt")).equals("do-not-delete"));
        }
        // 6. empty ubuntu dir is simply replaced
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu"); ub.mkdirs();
            SetupManager m = new SetupManager(files, ub,
                    new SetupManager.Config(base + "good.tar.gz", "good.tar.gz", goodSha, MIN), dl, new JvmFsOps(), new Ui());
            check("empty dir replaced, nothing set aside", expect(m, null) == null && new File(ub, "bin/bash").exists()
                    && files.list((d, n) -> n.startsWith("ubuntu.incomplete-")).length == 0);
        }
        // 7. cached valid download -> no network
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu"); new File(files, "setup").mkdirs();
            Files.write(new File(files, "setup/good.tar.gz").toPath(), good);
            hits.set(0);
            SetupManager m = new SetupManager(files, ub,
                    new SetupManager.Config(base + "good.tar.gz", "good.tar.gz", goodSha, MIN), dl, new JvmFsOps(), new Ui());
            check("cached valid archive: installs with zero requests", expect(m, null) == null && hits.get() == 0);
        }
        // 8. cached corrupt download -> re-downloaded
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu"); new File(files, "setup").mkdirs();
            Files.write(new File(files, "setup/good.tar.gz").toPath(), "garbage".getBytes("UTF-8"));
            hits.set(0);
            SetupManager m = new SetupManager(files, ub,
                    new SetupManager.Config(base + "good.tar.gz", "good.tar.gz", goodSha, MIN), dl, new JvmFsOps(), new Ui());
            check("cached corrupt archive: replaced by fresh download", expect(m, null) == null && hits.get() == 1
                    && new File(ub, "bin/bash").exists());
        }
        // 9. wrong layout
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu");
            SetupManager m = new SetupManager(files, ub,
                    new SetupManager.Config(base + "badlayout.tar.gz", "badlayout.tar.gz", badSha, MIN), dl, new JvmFsOps(), new Ui());
            SetupException e = expect(m, null);
            check("bad layout -> LAYOUT, ubuntu dir not created, staging cleaned",
                    e != null && e.kind == SetupException.Kind.LAYOUT && !ub.exists()
                            && !new File(files, "ubuntu-stage").exists());
        }
        // 10. cancel keeps nothing half-installed
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu");
            SetupManager m = new SetupManager(files, ub,
                    new SetupManager.Config(base + "good.tar.gz", "good.tar.gz", goodSha, MIN), dl, new JvmFsOps(), new Ui());
            SetupException e = expect(m, new AtomicBoolean(true));
            check("cancel -> CANCELLED, no ubuntu dir", e != null && e.kind == SetupException.Kind.CANCELLED && !ub.exists());
        }
        // 11. stale legacy staging dir (with read-only subdir) is cleaned, other dirs untouched
        {
            File files = freshFilesDir(); File ub = new File(files, "ubuntu");
            File stale = new File(files, "ubuntu-stage/old/sub"); stale.mkdirs();
            Files.write(new File(stale, "x").toPath(), "x".getBytes("UTF-8"));
            File other = new File(files, "home"); other.mkdirs();
            Files.write(new File(other, "mine.txt").toPath(), "my-home".getBytes("UTF-8"));
            SetupManager m = new SetupManager(files, ub,
                    new SetupManager.Config(base + "good.tar.gz", "good.tar.gz", goodSha, MIN), dl, new JvmFsOps(), new Ui());
            check("stale staging replaced; sibling 'home' dir untouched", expect(m, null) == null
                    && !new File(files, "ubuntu-stage").exists() && read(new File(other, "mine.txt")).equals("my-home"));
        }
        srv.stop(0);
        System.out.println(failures == 0 ? "ALL SETUPMANAGER TESTS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
