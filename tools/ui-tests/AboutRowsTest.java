import com.paynux.compat.AboutInfo;

import java.util.List;

/** Sandbox tests for the device-info rows shown in About. */
public final class AboutRowsTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static AboutInfo.Data sample() {
        AboutInfo.Data d = new AboutInfo.Data();
        d.appVersionName = "0.4.5"; d.appVersionCode = 17; d.distro = "Ubuntu 24.04.5 LTS";
        d.manufacturer = "samsung"; d.model = "SM-S918B"; d.androidRelease = "13"; d.sdkInt = 33;
        d.securityPatch = "2026-09-01"; d.kernel = "5.15.153-android13"; d.cpuCores = 8; d.abi = "arm64-v8a";
        d.ramTotalBytes = 10854452L * 1024; d.ramAvailBytes = 6500000L * 1024;
        d.storageTotalBytes = 464L << 30; d.storageFreeBytes = 344L << 30;
        d.screenW = 1080; d.screenH = 2316; d.dpi = 420; d.batteryPercent = 24; d.charging = Boolean.TRUE;
        d.uptimeMs = ((3L * 24 + 1) * 3600 + 55 * 60) * 1000; d.termRows = 36; d.termCols = 74;
        return d;
    }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
    }

    static void run() {
        check("bytes: 0 / small / MB / GB", AboutInfo.bytes(0).equals("0 B") && AboutInfo.bytes(1536).equals("1.5 KB")
                && AboutInfo.bytes(512L << 20).equals("512.0 MB") && AboutInfo.bytes(10L << 30).equals("10.0 GB"));
        check("bytes: negative -> '-'", AboutInfo.bytes(-1).equals("-"));
        check("used: same unit prints once ('4.0 / 10.0 GB used')", AboutInfo.used(10L << 30, 6L << 30).equals("4.0 / 10.0 GB used"));
        check("used: different units keep both", AboutInfo.used(10L << 30, (10L << 30) - (512L << 20)).equals("512.0 MB / 10.0 GB used"));
        check("used: unknown/invalid -> null", AboutInfo.used(-1, -1) == null && AboutInfo.used(0, 0) == null && AboutInfo.used(100, 200) == null);
        check("uptime: days", AboutInfo.uptime(((3L * 24 + 1) * 3600 + 55 * 60) * 1000).equals("3d 1h 55m"));
        check("uptime: hours pad minutes", AboutInfo.uptime((3600 + 5 * 60) * 1000L).equals("1h 05m"));
        check("uptime: minutes + seconds", AboutInfo.uptime((12 * 60 + 3) * 1000L).equals("12m 03s"));
        check("uptime: under a minute / negative", AboutInfo.uptime(20000).equals("<1m") && AboutInfo.uptime(-1) == null);
        check("battery: charging / discharging / unknown", AboutInfo.battery(24, Boolean.TRUE).equals("24% \u00b7 charging")
                && AboutInfo.battery(80, Boolean.FALSE).equals("80%") && AboutInfo.battery(50, null).equals("50%") && AboutInfo.battery(-1, null) == null);

        List<AboutInfo.Row> rows = AboutInfo.rows(sample());
        String txt = AboutInfo.toPlainText(rows);
        String expected = "PayNux\nLinux On Your Pocket\n\nVERSION\n"
                + "  App      0.4.5 (17)\n  Ubuntu   Ubuntu 24.04.5 LTS (PRoot)\n\nDEVICE\n"
                + "  Model    samsung SM-S918B\n  Android  13 (API 33)\n  Patch    2026-09-01\n  Kernel   5.15.153-android13\n"
                + "  CPU      8 cores \u00b7 arm64-v8a\n  RAM      4.2 / 10.4 GB used\n  Storage  120.0 / 464.0 GB used\n"
                + "  Screen   1080\u00d72316 \u00b7 420 dpi\n  Battery  24% \u00b7 charging\n  Uptime   3d 1h 55m\n\nTERMINAL\n  Size     74\u00d736";
        check("full About text matches the design", txt.equals(expected));
        if (!txt.equals(expected)) { System.out.println("---- got ----\n" + txt + "\n---- expected ----\n" + expected); }
        check("label column width is longest label (7) + 2 = 9", AboutInfo.labelWidth(rows) == 9);

        AboutInfo.Data blank = new AboutInfo.Data();
        String empty = AboutInfo.toPlainText(AboutInfo.rows(blank));
        check("all-unknown device: exact aligned dash line, no crash", empty.contains("\n  Model    -\n") && empty.contains("\n  Uptime   -\n"));
        check("Ubuntu not installed", empty.contains("not installed"));
        int dashes = 0; for (AboutInfo.Row r : AboutInfo.rows(blank)) if (r.kind == AboutInfo.Row.Kind.KV && r.value.equals("-")) dashes++;
        check("every unknown field renders as '-' (App, Model, Android, Patch, Kernel, CPU, RAM, Storage, Screen, Battery, Uptime, Size = 12)", dashes == 12);

        AboutInfo.Data m = sample(); m.model = "Samsung Galaxy S23"; m.manufacturer = "Samsung";
        check("manufacturer not repeated when the model already starts with it",
                AboutInfo.toPlainText(AboutInfo.rows(m)).contains("Model    Samsung Galaxy S23\n"));
        AboutInfo.Data g = sample(); g.manufacturer = "Google"; g.model = "Pixel 8";
        check("manufacturer prepended otherwise", AboutInfo.toPlainText(AboutInfo.rows(g)).contains("Model    Google Pixel 8\n"));
        check("rows keep their kinds (TITLE first, sections present)", rows.get(0).kind == AboutInfo.Row.Kind.TITLE
                && txt.contains("\nVERSION\n") && txt.contains("\nDEVICE\n") && txt.contains("\nTERMINAL\n"));
        System.out.println(failures == 0 ? "ALL ABOUT ROWS TESTS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
