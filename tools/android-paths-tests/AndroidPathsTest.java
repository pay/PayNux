import com.paynux.compat.AndroidPaths;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Sandbox tests for AndroidPaths. */
public final class AndroidPathsTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static AndroidPaths.Probe only(final String... ok) {
        final Set<String> s = new HashSet<String>(Arrays.asList(ok));
        return new AndroidPaths.Probe() { public boolean usable(String p) { return s.contains(p); } };
    }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
    }

    static void run() throws Exception {
        check("candidates are exactly /system /apex /vendor /product, in that order, no duplicates",
                Arrays.asList(AndroidPaths.CANDIDATES).equals(Arrays.asList("/system", "/apex", "/vendor", "/product")));
        check("all usable -> all four, in order",
                AndroidPaths.select(only("/product", "/vendor", "/apex", "/system")).equals(Arrays.asList("/system", "/apex", "/vendor", "/product")));
        check("none usable -> empty list", AndroidPaths.select(only()).isEmpty());
        check("mixed -> only the usable ones, order kept",
                AndroidPaths.select(only("/product", "/system")).equals(Arrays.asList("/system", "/product")));
        check("paths outside the candidate list are never selected",
                AndroidPaths.select(only("/data", "/proc", "/system/bin")).isEmpty());
        check("a probe that throws is treated as unusable (no crash)",
                AndroidPaths.select(new AndroidPaths.Probe() {
                    public boolean usable(String p) { if (p.equals("/apex")) throw new IllegalStateException("boom"); return p.equals("/system"); }
                }).equals(Arrays.asList("/system")));

        File dir = Files.createTempDirectory("ap").toFile();
        File file = new File(dir, "f"); Files.write(file.toPath(), "x".getBytes("UTF-8"));
        check("isUsableDir: real folder -> true", AndroidPaths.isUsableDir(dir));
        check("isUsableDir: regular file -> false", !AndroidPaths.isUsableDir(file));
        check("isUsableDir: missing -> false", !AndroidPaths.isUsableDir(new File(dir, "nope")));
        File noaccess = new File(dir, "noaccess"); noaccess.mkdirs(); noaccess.setReadable(false, false); noaccess.setExecutable(false, false);
        boolean root = "root".equals(System.getProperty("user.name"));
        check(root ? "isUsableDir: unreadable folder (skipped: running as root, permissions do not apply)"
                : "isUsableDir: unreadable folder -> false", root || !AndroidPaths.isUsableDir(noaccess));
        noaccess.setReadable(true, false); noaccess.setExecutable(true, false);
        check("defaultProbe works through the same check", AndroidPaths.defaultProbe().usable(dir.getAbsolutePath())
                && !AndroidPaths.defaultProbe().usable(new File(dir, "nope").getAbsolutePath()));
        List<String> real = AndroidPaths.select(AndroidPaths.defaultProbe());
        check("on this machine select() returns a subset of the candidates, no duplicates",
                Arrays.asList(AndroidPaths.CANDIDATES).containsAll(real) && new HashSet<String>(real).size() == real.size());
        System.out.println(failures == 0 ? "ALL ANDROID PATHS TESTS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
