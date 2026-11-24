import com.paynux.compat.FakeProc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Sandbox tests for FakeProc. */
public final class FakeProcTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static FakeProc.Probe only(final String... readable) {
        final Set<String> ok = new HashSet<String>(java.util.Arrays.asList(readable));
        return new FakeProc.Probe() { public boolean readable(String p) { return ok.contains(p); } };
    }

    static String read(File f) throws IOException { return new String(Files.readAllBytes(f.toPath()), "UTF-8"); }

    public static void main(String[] a) throws Exception {
        try { run(); } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
    }

    static void run() throws Exception {
        long now = 1798700000L, up = 5000L;
        // ---- stat format ----
        String st = FakeProc.stat(8, up, now);
        String[] lines = st.split("\n");
        int cpuLines = 0; long sumUser = 0, aggUser = -1; boolean aggOk = false;
        for (String l : lines) {
            String[] f = l.split("\\s+");
            if (l.startsWith("cpu ")) { aggUser = Long.parseLong(f[1]); aggOk = f.length == 11; }
            else if (l.startsWith("cpu")) { cpuLines++; sumUser += Long.parseLong(f[1]); if (f.length != 11) aggOk = false; }
        }
        check("stat: aggregate + 8 cpuN lines, 10 numeric fields each", cpuLines == 8 && aggOk);
        check("stat: aggregate equals the sum of per-CPU lines", aggUser == sumUser);
        check("stat: btime = now - uptime", st.contains("\nbtime " + (now - up) + "\n"));
        check("stat: trailer keys present (intr ctxt processes procs_running procs_blocked softirq)",
                st.contains("\nintr ") && st.contains("\nctxt ") && st.contains("\nprocesses ")
                        && st.contains("\nprocs_running ") && st.contains("\nprocs_blocked ") && st.contains("\nsoftirq "));
        check("stat: 1 CPU and nonsense inputs are clamped (no exception, btime sane)",
                FakeProc.stat(0, -5, now).contains("cpu0 ") && FakeProc.stat(0, -5, now).contains("btime " + now));
        // ---- realism against the real kernel format (sandbox), if readable ----
        File real = new File("/proc/stat");
        if (real.canRead()) {
            Set<String> keys = new HashSet<String>();
            for (String l : read(real).split("\n")) {
                String k = l.split("\\s+")[0];
                if (!k.startsWith("cpu") || k.equals("cpu")) keys.add(k);
            }
            Set<String> mine = new HashSet<String>();
            for (String l : st.split("\n")) {
                String k = l.split("\\s+")[0];
                if (!k.startsWith("cpu") || k.equals("cpu")) mine.add(k);
            }
            check("stat: key set matches the real kernel's /proc/stat keys " + mine, keys.containsAll(mine));
            String realCpu = null;
            for (String l : read(real).split("\n")) if (l.startsWith("cpu ")) realCpu = l;
            check("stat: aggregate line has the same field count as the real one",
                    realCpu != null && realCpu.split("\\s+").length == st.split("\n")[0].split("\\s+").length);
        } else {
            System.out.println("SKIP real /proc/stat not readable here");
        }
        // ---- loadavg / uptime ----
        check("loadavg: kernel format 'a b c running/total lastpid'",
                FakeProc.loadavg().matches("\\d+\\.\\d\\d \\d+\\.\\d\\d \\d+\\.\\d\\d \\d+/\\d+ \\d+\\n"));
        check("uptime: '<sec>.00 <idle>.00'", FakeProc.uptime(5000, 8).equals("5000.00 38000.00\n"));
        // ---- prepare(): only unreadable files are faked ----
        File dir = new File(Files.createTempDirectory("fakeproc").toFile(), "fake_proc");
        List<String> binds = FakeProc.prepare(dir, only(), 8, up, now);
        check("all unreadable -> 3 binds, each host file exists and maps to /proc/<name>",
                binds.size() == 3 && binds.get(0).endsWith("/stat:/proc/stat") && binds.get(1).endsWith("/loadavg:/proc/loadavg")
                        && binds.get(2).endsWith("/uptime:/proc/uptime") && new File(dir, "stat").isFile()
                        && read(new File(dir, "stat")).equals(st));
        File dir2 = new File(Files.createTempDirectory("fakeproc").toFile(), "fp");
        binds = FakeProc.prepare(dir2, only("/proc/uptime"), 4, up, now);
        check("uptime readable -> only stat + loadavg faked, no uptime file",
                binds.size() == 2 && !new File(dir2, "uptime").exists() && new File(dir2, "loadavg").isFile());
        File dir3 = new File(Files.createTempDirectory("fakeproc").toFile(), "none");
        binds = FakeProc.prepare(dir3, only("/proc/stat", "/proc/loadavg", "/proc/uptime"), 4, up, now);
        check("everything readable -> no binds and the directory is not even created", binds.isEmpty() && !dir3.exists());
        // overwrite on next start
        FakeProc.prepare(dir, only(), 8, up + 100, now + 100);
        check("rerun overwrites with fresh content (btime unchanged because up/now moved together)",
                read(new File(dir, "stat")).contains("\nbtime " + (now - up) + "\n"));
        // unwritable location -> IOException, nothing thrown otherwise
        File blocker = File.createTempFile("blocker", ".txt");
        boolean threw = false;
        try { FakeProc.prepare(new File(blocker, "sub"), only(), 4, up, now); } catch (IOException e) { threw = true; }
        check("cannot create dir -> IOException (caller decides)", threw);
        // default probe on this machine
        FakeProc.Probe p = FakeProc.defaultProbe();
        File ro = File.createTempFile("probe", ".txt"); Files.write(ro.toPath(), "x".getBytes("UTF-8"));
        check("defaultProbe: reads a normal file", p.readable(ro.getAbsolutePath()));
        check("defaultProbe: false for a missing file", !p.readable("/proc/does-not-exist"));
        System.out.println(failures == 0 ? "ALL FAKEPROC TESTS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
