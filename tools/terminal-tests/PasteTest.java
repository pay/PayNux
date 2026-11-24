import com.paynux.terminal.PasteFormatter;
import com.paynux.terminal.TerminalEmulator;

/** Sandbox tests for bracketed-paste tracking and PasteFormatter. */
public final class PasteTest {
    static int failures = 0;
    static final String ESC = "\u001b";

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static TerminalEmulator term() {
        return new TerminalEmulator(6, 40, new TerminalEmulator.Listener() {
            public void onScreenChanged() { }
            public void onScrollbackChanged() { }
            public void onBell() { }
            public void onResponse(String d) { }
        });
    }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
    }

    static void run() {
        String S = PasteFormatter.START, E = PasteFormatter.END;
        // ---- formatter, bracketed on ----
        check("bracketed: wraps text", PasteFormatter.format("echo hi", true).equals(S + "echo hi" + E));
        check("bracketed: multi-line kept as LF (no execution, no CR)", PasteFormatter.format("a\nb\nc", true).equals(S + "a\nb\nc" + E));
        check("bracketed: CRLF and lone CR normalised to LF", PasteFormatter.format("a\r\nb\rc", true).equals(S + "a\nb\nc" + E));
        check("bracketed: tabs untouched", PasteFormatter.format("a\tb", true).equals(S + "a\tb" + E));
        // ---- formatter, bracketed off ----
        check("plain: LF -> CR", PasteFormatter.format("a\nb", false).equals("a\rb"));
        check("plain: CRLF -> CR (no doubled Enter)", PasteFormatter.format("a\r\nb", false).equals("a\rb"));
        check("plain: single line unchanged", PasteFormatter.format("echo hi", false).equals("echo hi"));
        // ---- injection / edge cases ----
        check("end marker inside the text is removed (cannot close the bracket early)",
                PasteFormatter.format("x" + E + "rm -rf ~", true).equals(S + "xrm -rf ~" + E));
        check("start marker inside the text is removed", PasteFormatter.format("x" + S + "y", true).equals(S + "xy" + E));
        check("markers removed in plain mode too", PasteFormatter.format("x" + E + "y", false).equals("xy"));
        // Removing one marker must not be able to assemble another: ESC[20 + ESC[201~ + 1~ leaves ESC[201~.
        check("reassembled marker is removed repeatedly (all of it vanishes -> nothing sent)",
                PasteFormatter.format(ESC + "[20" + E + "1~", true).isEmpty());
        check("reassembled marker with surrounding text keeps only the text",
                PasteFormatter.format("a" + ESC + "[20" + E + "1~" + "b", true).equals(S + "ab" + E));
        check("empty / null -> nothing is sent", PasteFormatter.format("", true).isEmpty() && PasteFormatter.format(null, false).isEmpty());
        check("only markers -> nothing is sent", PasteFormatter.format(S + E, true).isEmpty());
        check("unicode preserved", PasteFormatter.format("caf\u00e9 \u65e5\u672c", true).equals(S + "caf\u00e9 \u65e5\u672c" + E));
        // ---- emulator mode tracking ----
        TerminalEmulator e = term();
        check("default: bracketed paste off", !e.isBracketedPaste());
        e.feed(ESC + "[?2004h");
        check("ESC[?2004h turns it on", e.isBracketedPaste());
        e.feed(ESC + "[?2004l");
        check("ESC[?2004l turns it off", !e.isBracketedPaste());
        e.feed(ESC + "[?1000;2004h");
        check("combined ESC[?1000;2004h sets it", e.isBracketedPaste());
        e.feed(ESC + "[?25l" + ESC + "[?1049h");
        check("other private modes do not disturb it", e.isBracketedPaste());
        e.feed(ESC + "c");
        check("ESC c (full reset) clears it", !e.isBracketedPaste());
        e.feed(ESC + "[?2004h");
        e.reset();
        check("reset() for a new session clears it", !e.isBracketedPaste());
        e.feed(ESC + "[?2004hhello");
        check("mode sequence prints nothing", e.getScreenPlainText().split("\n")[0].trim().equals("hello"));
        System.out.println(failures == 0 ? "ALL PASTE TESTS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
