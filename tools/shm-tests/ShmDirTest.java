import com.paynux.compat.ShmDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Sandbox tests for ShmDir (uses the JVM FsOps from tools/setup-tests). */
public final class ShmDirTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static int mode(File f) throws IOException { return ((Integer) Files.getAttribute(f.toPath(), "unix:mode")) & 07777; }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
    }

    static void run() throws Exception {
        File base = Files.createTempDirectory("shmtest").toFile();
        JvmFsOps fs = new JvmFsOps();

        File d = new File(base, "shm");
        String spec = ShmDir.prepare(d, fs);
        check("creates the folder and returns '<abs path>:/dev/shm'", d.isDirectory() && spec.equals(d.getAbsolutePath() + ":/dev/shm"));
        check("mode is 1777 (sticky + world-writable, like a tmpfs /dev/shm)", mode(d) == 01777);

        Files.write(new File(d, "sem.keep").toPath(), "x".getBytes("UTF-8"));
        String again = ShmDir.prepare(d, fs);
        check("idempotent: same spec, existing files untouched (nothing is ever deleted)", again.equals(spec) && new File(d, "sem.keep").isFile());

        File odd = new File(base, "odd"); odd.mkdirs(); fs.chmod(odd, 0700);
        ShmDir.prepare(odd, fs);
        check("an existing folder with the wrong mode is fixed to 1777", mode(odd) == 01777);

        File notDir = new File(base, "file"); Files.write(notDir.toPath(), "data".getBytes("UTF-8"));
        boolean threw = false;
        try { ShmDir.prepare(notDir, fs); } catch (IOException e) { threw = true; }
        check("a regular file in the way -> IOException, file untouched", threw && new String(Files.readAllBytes(notDir.toPath()), "UTF-8").equals("data"));

        threw = false;
        try { ShmDir.prepare(new File(notDir, "sub"), fs); } catch (IOException e) { threw = true; }
        check("parent is a file (cannot create) -> IOException", threw);

        File target = new File(base, "target"); target.mkdirs(); fs.chmod(target, 0755);
        File link = new File(base, "link"); Files.createSymbolicLink(link.toPath(), target.toPath());
        threw = false;
        try { ShmDir.prepare(link, fs); } catch (IOException e) { threw = true; }
        check("a symlink is refused and its target mode is not changed through it", threw && mode(target) == 0755);

        File dangling = new File(base, "dangling"); Files.createSymbolicLink(dangling.toPath(), new File(base, "nowhere").toPath());
        threw = false;
        try { ShmDir.prepare(dangling, fs); } catch (IOException e) { threw = true; }
        check("a dangling symlink is refused too", threw && !new File(base, "nowhere").exists());
        System.out.println(failures == 0 ? "ALL SHM TESTS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
