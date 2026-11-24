package com.paynux.compat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stand-ins for /proc files that Android denies to normal apps
 * (/proc/stat, /proc/loadavg, /proc/uptime), so tools such as htop, top, ps
 * and uptime can run inside PRoot.
 *
 * Only files that cannot be opened on the host are faked; anything readable
 * (for example /proc/meminfo) stays real. The contents are static snapshots:
 * CPU meters will read as idle because Android does not expose real CPU
 * counters to apps.
 *
 * Pure java.io, no Android classes, so it is unit-testable on a JVM.
 */
public final class FakeProc {
    private FakeProc() {}

    /** Decides whether a host path can be read by this process. */
    public interface Probe {
        boolean readable(String path);
    }

    /** Opens the file and reads a byte: false on EACCES and similar. */
    public static Probe defaultProbe() {
        return new Probe() {
            public boolean readable(String path) {
                try {
                    FileInputStream in = new FileInputStream(path);
                    try {
                        in.read();
                    } finally {
                        in.close();
                    }
                    return true;
                } catch (IOException e) {
                    return false;
                }
            }
        };
    }

    /** One "cpu" aggregate line, one line per CPU, then the usual trailer. */
    public static String stat(int cpus, long uptimeSec, long nowEpochSec) {
        if (cpus < 1) cpus = 1;
        if (uptimeSec < 0) uptimeSec = 0;
        // 100 ticks per second per CPU: ~95% idle, 3% user, 2% system.
        long perCpu = uptimeSec * 100L;
        long idle = perCpu * 95L / 100L;
        long user = perCpu * 3L / 100L;
        long system = perCpu - idle - user;
        StringBuilder sb = new StringBuilder();
        sb.append(cpuLine("cpu ", user * cpus, system * cpus, idle * cpus)).append('\n');
        for (int i = 0; i < cpus; i++) {
            sb.append(cpuLine("cpu" + i, user, system, idle)).append('\n');
        }
        sb.append("intr 0\n");
        sb.append("ctxt 0\n");
        sb.append("btime ").append(nowEpochSec - uptimeSec).append('\n');
        sb.append("processes 1\n");
        sb.append("procs_running 1\n");
        sb.append("procs_blocked 0\n");
        sb.append("softirq 0 0 0 0 0 0 0 0 0 0 0\n");
        return sb.toString();
    }

    private static String cpuLine(String name, long user, long system, long idle) {
        // user nice system idle iowait irq softirq steal guest guest_nice
        return name + " " + user + " 0 " + system + " " + idle + " 0 0 0 0 0 0";
    }

    public static String loadavg() {
        return "0.00 0.00 0.00 1/1 1\n";
    }

    /** "<uptime seconds> <idle seconds summed over CPUs>". */
    public static String uptime(long uptimeSec, int cpus) {
        if (cpus < 1) cpus = 1;
        if (uptimeSec < 0) uptimeSec = 0;
        return String.format(Locale.US, "%d.00 %d.00\n", uptimeSec, uptimeSec * cpus * 95L / 100L);
    }

    /**
     * Writes a fake file into dir for every /proc file that probe reports as
     * unreadable and returns the PRoot bind specs ("hostfile:/proc/name").
     * Existing fake files are overwritten; nothing else is touched.
     */
    public static List<String> prepare(File dir, Probe probe, int cpus,
                                       long uptimeSec, long nowEpochSec) throws IOException {
        List<String> binds = new ArrayList<String>();
        String[] names = {"stat", "loadavg", "uptime"};
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (probe.readable("/proc/" + name)) continue;
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            String content;
            if (name.equals("stat")) content = stat(cpus, uptimeSec, nowEpochSec);
            else if (name.equals("loadavg")) content = loadavg();
            else content = uptime(uptimeSec, cpus);
            File f = new File(dir, name);
            OutputStream out = new FileOutputStream(f, false);
            try {
                out.write(content.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            binds.add(f.getAbsolutePath() + ":/proc/" + name);
        }
        return binds;
    }
}
