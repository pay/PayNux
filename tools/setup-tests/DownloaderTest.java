import com.sun.net.httpserver.HttpServer;
import com.paynux.setup.Downloader;
import com.paynux.setup.Sha256Verifier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Sandbox tests for Downloader/Sha256Verifier using a local HTTP server. */
public final class DownloaderTest {
    static byte[] data = new byte[3 * 1024 * 1024 + 123];
    static volatile String mode = "normal";
    static final AtomicInteger hits = new AtomicInteger();
    static volatile String lastRange = null;
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        try { run(); } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
    }

    static void run() throws Exception {
        new Random(42).nextBytes(data);
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/f", ex -> {
            hits.incrementAndGet();
            String range = ex.getRequestHeaders().getFirst("Range");
            lastRange = range;
            if (mode.equals("404")) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            long start = 0;
            boolean partial = false;
            if (range != null && !mode.equals("ignore-range")) {
                start = Long.parseLong(range.substring(6, range.indexOf('-')));
                if (start >= data.length) {
                    ex.getResponseHeaders().add("Content-Range", "bytes */" + data.length);
                    ex.sendResponseHeaders(416, -1); ex.close(); return;
                }
                partial = true;
            }
            long len = data.length - start;
            if (partial) ex.getResponseHeaders().add("Content-Range",
                    "bytes " + start + "-" + (data.length - 1) + "/" + data.length);
            ex.sendResponseHeaders(partial ? 206 : 200, len);
            OutputStream o = ex.getResponseBody();
            if (mode.equals("cut-once")) {
                mode = "normal"; // send only 1 MiB then drop the connection
                o.write(data, (int) start, 1024 * 1024);
                o.flush();
                ex.close();
                return;
            }
            o.write(data, (int) start, (int) len);
            o.close();
        });
        srv.start();
        String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/f";
        String want = Sha256Verifier.sha256Hex(writeTmp(data), null);
        File dir = Files.createTempDirectory("dl").toFile();
        Downloader dl = new Downloader(false, 3, 3000, 3000, 20L);

        // SHA vector
        File abc = writeTmp("abc".getBytes("UTF-8"));
        check("sha256 known vector",
                Sha256Verifier.sha256Hex(abc, null).equals(
                        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));

        // 1. full download
        File p1 = new File(dir, "a.part");
        dl.download(url, p1, null, null);
        check("full download hash", Sha256Verifier.sha256Hex(p1, null).equals(want));

        // 2. resume from partial
        File p2 = new File(dir, "b.part");
        try (FileOutputStream o = new FileOutputStream(p2)) { o.write(data, 0, 1000000); }
        hits.set(0);
        dl.download(url, p2, null, null);
        check("resume sends Range 1000000-", "bytes=1000000-".equals(lastRange));
        check("resume final hash", Sha256Verifier.sha256Hex(p2, null).equals(want));

        // 3. server ignores Range -> restart from zero, no corruption
        mode = "ignore-range";
        File p3 = new File(dir, "c.part");
        try (FileOutputStream o = new FileOutputStream(p3)) { o.write(data, 0, 500000); }
        dl.download(url, p3, null, null);
        check("ignored Range -> clean restart", Sha256Verifier.sha256Hex(p3, null).equals(want));
        mode = "normal";

        // 4. connection cut mid-stream then auto-resume
        mode = "cut-once";
        File p4 = new File(dir, "d.part");
        dl.download(url, p4, null, null);
        check("cut mid-stream -> auto resume hash", Sha256Verifier.sha256Hex(p4, null).equals(want));

        // 5. 404 is fatal, not retried
        mode = "404";
        hits.set(0);
        boolean fatal = false;
        try { dl.download(url, new File(dir, "e.part"), null, null); }
        catch (Downloader.FatalException e) { fatal = true; }
        check("404 fatal, single request", fatal && hits.get() == 1);
        mode = "normal";

        // 6. HTTPS enforced
        boolean refused = false;
        try { Downloader.defaults().download(url, new File(dir, "f.part"), null, null); }
        catch (Downloader.FatalException e) { refused = true; }
        catch (IOException e) { refused = false; }
        check("http URL refused by defaults()", refused);

        // 7. cancel keeps .part, throws CancelledException
        AtomicBoolean cancel = new AtomicBoolean(true);
        boolean cancelled = false;
        try { dl.download(url, new File(dir, "g.part"), null, cancel); }
        catch (Downloader.CancelledException e) { cancelled = true; }
        check("cancel throws CancelledException", cancelled);

        // 8. already-complete .part -> 416 handled, hash still right
        File p8 = new File(dir, "h.part");
        Files.write(p8.toPath(), data);
        dl.download(url, p8, null, null);
        check("complete .part (416) keeps file", Sha256Verifier.sha256Hex(p8, null).equals(want));

        // 9. stale oversize .part -> reset and re-download
        File p9 = new File(dir, "i.part");
        byte[] big = new byte[data.length + 10];
        Files.write(p9.toPath(), big);
        dl.download(url, p9, null, null);
        check("oversize stale .part recovers", Sha256Verifier.sha256Hex(p9, null).equals(want));

        srv.stop(0);
        System.out.println(failures == 0 ? "ALL DOWNLOADER TESTS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static File writeTmp(byte[] b) throws IOException {
        File f = File.createTempFile("tmpdl", ".bin");
        f.deleteOnExit();
        Files.write(f.toPath(), b);
        return f;
    }
}
