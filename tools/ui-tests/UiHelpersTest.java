import com.paynux.compat.AboutInfo;
import com.paynux.compat.SessionName;
import com.paynux.compat.ToolbarConfig;
import com.paynux.compat.UrlFinder;

import java.util.Arrays;
import java.util.List;

/** Sandbox tests for the pure-Java UI helpers (toolbar config, URL finder, session names, About text). */
public final class UiHelpersTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static List<List<String>> rows(String... r) {
        List<List<String>> out = new java.util.ArrayList<List<String>>();
        for (String s : r) out.add(Arrays.asList(s.split("\\|")));
        return out;
    }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
    }

    static void run() {
        // ---------------- ToolbarConfig ----------------
        check("current user file (unquoted) still works",
                ToolbarConfig.parse("extra-keys = [[ESC,SESS,HOME,UP,END,~,/],[TAB,CTRL,LEFT,DOWN,RIGHT,-,$]]\n")
                        .equals(rows("ESC|SESS|HOME|UP|END|~|/", "TAB|CTRL|LEFT|DOWN|RIGHT|-|$")));
        String termux = "extra-keys = [['ESC','DRAWER','HOME','UP','END','~','/'], \\\n"
                + "              ['TAB','CTRL','LEFT','DOWN','RIGHT','-',\"$\"]]\n";
        check("Termux sample: quotes + backslash continuation + DRAWER alias",
                ToolbarConfig.parse(termux).equals(rows("ESC|SESS|HOME|UP|END|~|/", "TAB|CTRL|LEFT|DOWN|RIGHT|-|$")));
        check("same sample with CRLF line endings", ToolbarConfig.parse(termux.replace("\n", "\r\n"))
                .equals(rows("ESC|SESS|HOME|UP|END|~|/", "TAB|CTRL|LEFT|DOWN|RIGHT|-|$")));
        check("continuation without backslash while brackets are open",
                ToolbarConfig.parse("extra-keys = [[ESC,TAB],\n[CTRL,ALT]]").equals(rows("ESC|TAB", "CTRL|ALT")));
        check("comment lines before and inside are ignored",
                ToolbarConfig.parse("# hello\n# extra-keys = [[X]]\nextra-keys = [[ESC,TAB]]\n").equals(rows("ESC|TAB")));
        check("lowercase drawer alias", ToolbarConfig.parse("extra-keys = [[drawer,ESC]]").equals(rows("SESS|ESC")));
        check("quoted comma and brackets are literal keys",
                ToolbarConfig.parse("extra-keys = [[\",\",'[',']',ESC]]").equals(rows(",|[|]|ESC")));
        check("spaces around keys are trimmed", ToolbarConfig.parse("extra-keys=[[ ESC , TAB ],[ CTRL ]]").equals(rows("ESC|TAB", "CTRL")));
        check("no extra-keys line -> null", ToolbarConfig.parse("# nothing\nfoo = bar\n") == null);
        check("garbage value -> null", ToolbarConfig.parse("extra-keys = hello") == null);
        check("null/empty -> null", ToolbarConfig.parse(null) == null && ToolbarConfig.parse("") == null);
        check("first valid extra-keys line wins", ToolbarConfig.parse("extra-keys = [[A]]\nextra-keys = [[B]]").equals(rows("A")));
        check("trailing backslash at EOF does not hang or crash", ToolbarConfig.parse("extra-keys = [[ESC]] \\") != null);
        check("parseRows keeps the old signature semantics", ToolbarConfig.parseRows("[[A,B],[C]]").equals(rows("A|B", "C")));

        // ---------------- UrlFinder ----------------
        String line = "Open browser manually: http://localhost:20128/dashboard";
        int u = line.indexOf("http");
        String exp = "http://localhost:20128/dashboard";
        check("selecting only 'localhost' returns the whole URL", exp.equals(UrlFinder.urlAround(line, line.indexOf("localhost"), line.indexOf("localhost") + 9)));
        check("selecting 'dashboard' returns the URL", exp.equals(UrlFinder.urlAround(line, line.indexOf("dashboard"), line.length())));
        check("selecting the scheme returns the URL", exp.equals(UrlFinder.urlAround(line, u, u + 4)));
        check("selecting 'browser' (not on the URL) -> null", UrlFinder.urlAround(line, line.indexOf("browser"), line.indexOf("browser") + 7) == null);
        check("selection ending exactly where the URL starts -> null", UrlFinder.urlAround(line, u - 5, u) == null);
        check("trailing punctuation stripped: (https://a.b/c).", "https://a.b/c".equals(UrlFinder.urlAround("see (https://a.b/c).", 8, 12)));
        check("query string and fragment kept", "https://x.io/p?q=1&r=2#top".equals(UrlFinder.urlAround("go https://x.io/p?q=1&r=2#top now", 5, 8)));
        String two = "a http://one.test/x and http://two.test/y z";
        check("picks the URL that overlaps the selection (second)", "http://two.test/y".equals(UrlFinder.urlAround(two, two.indexOf("two"), two.indexOf("two") + 3)));
        check("picks the URL that overlaps the selection (first)", "http://one.test/x".equals(UrlFinder.urlAround(two, two.indexOf("one"), two.indexOf("one") + 3)));
        String multi = "first line\nsee http://m.test/a\nlast";
        check("works on a later line", "http://m.test/a".equals(UrlFinder.urlAround(multi, multi.indexOf("m.test"), multi.indexOf("m.test") + 2)));
        check("URL on another line is not picked", UrlFinder.urlAround(multi, 0, 5) == null);
        check("case-insensitive scheme", "HTTPS://Up.Case/X".equals(UrlFinder.urlAround("HTTPS://Up.Case/X", 2, 5)));
        check("ftp supported", "ftp://f.test/file".equals(UrlFinder.urlAround("ftp://f.test/file", 7, 9)));
        check("javascript: and file: are not URLs here", UrlFinder.urlAround("javascript://x.y/z", 0, 5) == null && UrlFinder.urlAround("file:///etc/passwd", 0, 6) == null);
        check("bad indices / null -> null", UrlFinder.urlAround(null, 0, 1) == null && UrlFinder.urlAround(line, -1, 3) == null
                && UrlFinder.urlAround(line, 5, 5) == null && UrlFinder.urlAround(line, 3, 9999) == null);
        StringBuilder big = new StringBuilder(); for (int i = 0; i < 5000; i++) big.append('x');
        check("huge line is refused, not scanned", UrlFinder.urlAround(big.toString(), 10, 20) == null);

        // ---------------- SessionName ----------------
        check("trim + collapse spaces", "My Build".equals(SessionName.clean("  My   Build \n")));
        check("empty / blank -> null (back to default)", SessionName.clean("") == null && SessionName.clean("   ") == null && SessionName.clean(null) == null);
        check("control chars removed", "ab".equals(SessionName.clean("a\u0000\u001bb")));
        check("truncated to 32 characters", SessionName.clean("123456789012345678901234567890123456").length() == 32);
        check("emoji is not split at the limit", !Character.isHighSurrogate(
                SessionName.clean("1234567890123456789012345678901\uD83D\uDE00").charAt(SessionName.clean("1234567890123456789012345678901\uD83D\uDE00").length() - 1)));
        check("unicode names kept", "Bot \u65e5\u672c".equals(SessionName.clean("Bot \u65e5\u672c")));

        // ---------------- AboutInfo ----------------
        check("PRETTY_NAME parsed (quoted)", "Ubuntu 24.04.5 LTS".equals(AboutInfo.prettyName("NAME=\"Ubuntu\"\nPRETTY_NAME=\"Ubuntu 24.04.5 LTS\"\nID=ubuntu\n")));
        check("PRETTY_NAME missing -> null", AboutInfo.prettyName("NAME=x\n") == null && AboutInfo.prettyName(null) == null);
        check("About text", AboutInfo.format("0.4.5", 17, "Ubuntu 24.04.5 LTS", "13", 33, "arm64-v8a")
                .equals("PayNux\nLinux On Your Pocket\n\nVersion 0.4.5 (17)\nUbuntu 24.04.5 LTS (PRoot)\nAndroid 13 (API 33) \u00b7 arm64-v8a"));
        check("About text without Ubuntu", AboutInfo.format("0.4.5", 17, null, "13", 33, "").contains("Ubuntu: not installed"));
        System.out.println(failures == 0 ? "ALL UI HELPER TESTS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
