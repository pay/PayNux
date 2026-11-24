import com.paynux.setup.ProotSelfTest;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;

/** Tests ProotSelfTest against fake "proot" shell scripts (real proot is ARM-only). */
public final class ProotSelfTestTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static File fake(String body) throws Exception {
        File f = File.createTempFile("fakeproot", ".sh");
        Files.write(f.toPath(), ("#!/bin/sh\n" + body + "\n").getBytes("UTF-8"));
        f.setExecutable(true);
        f.deleteOnExit();
        return f;
    }

    static ProotSelfTest.Result run(File proot, long timeout) {
        return new ProotSelfTest(proot, new File("/tmp"), new HashMap<String, String>(), "24.04", timeout).run();
    }

    public static void main(String[] a) throws Exception {
        String goodOut = "printf 'PRETTY_NAME=\"Ubuntu 24.04.5 LTS\"\\nVERSION_ID=\"24.04\"\\nID=ubuntu\\nUID=0\\ndrwxrwxrwt 2 root root 40 Jan 1 /tmp\\n'";
        ProotSelfTest.Result r = run(fake(goodOut), 5000);
        check("good output -> ok, no warnings", r.ok && !r.report().contains("WARN") && !r.report().contains("FAIL"));

        r = run(fake("printf 'VERSION_ID=\"22.04\"\\nID=ubuntu\\nUID=0\\ndrwxrwxrwt /tmp\\n'"), 5000);
        check("wrong Ubuntu version -> FAIL", !r.ok && r.report().contains("FAIL"));

        r = run(fake("printf 'VERSION_ID=\"24.04\"\\nID=ubuntu\\nUID=1000\\ndrwxr-xr-x /tmp\\n'"), 5000);
        check("uid/tmp problems are WARN only (ok stays true)", r.ok && r.report().contains("WARN: uid") && r.report().contains("WARN: /tmp"));

        r = run(fake("echo 'proot error: boom' >&2; exit 3"), 5000);
        check("non-zero exit -> FAIL with output tail", !r.ok && r.report().contains("code 3") && r.report().contains("boom"));

        long t0 = System.currentTimeMillis();
        r = run(fake("sleep 30"), 700);
        check("hang -> killed by timeout quickly", !r.ok && r.report().contains("timed out")
                && System.currentTimeMillis() - t0 < 5000);

        r = run(new File("/nonexistent/proot"), 1000);
        check("missing proot -> FAIL, no exception", !r.ok && r.report().contains("not found"));

        File noexec = File.createTempFile("noexec", ".sh"); noexec.deleteOnExit();
        r = run(noexec, 1000);
        check("non-executable proot -> FAIL, no exception", !r.ok && r.report().contains("cannot start"));

        // environment is passed through and PATH/HOME forced
        HashMap<String, String> env = new HashMap<String, String>();
        env.put("PROOT_LOADER", "/x/loader");
        File envCheck = fake("[ \"$PROOT_LOADER\" = /x/loader ] && [ \"$HOME\" = /root ] && case \"$PATH\" in /usr/local/sbin*) exit 0;; esac; exit 9");
        r = new ProotSelfTest(envCheck, new File("/tmp"), env, "24.04", 3000).run();
        check("env passed, HOME and PATH forced (exit 0 reached os-release check)",
                r.report().contains("shell runs"));
        System.out.println(failures == 0 ? "ALL PROOT SELFTEST TESTS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
