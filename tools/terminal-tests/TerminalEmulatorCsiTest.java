import com.paynux.terminal.TerminalEmulator;

import java.util.ArrayList;
import java.util.List;

/** Sandbox tests for the CSI parser (intermediate bytes) and for regressions in common sequences. */
public final class TerminalEmulatorCsiTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static final class Rec implements TerminalEmulator.Listener {
        final List<String> responses = new ArrayList<String>();
        public void onScreenChanged() { }
        public void onScrollbackChanged() { }
        public void onBell() { }
        public void onResponse(String d) { responses.add(d); }
    }

    static TerminalEmulator term(Rec r) { return new TerminalEmulator(6, 40, r); }

    static String line0(String input) {
        TerminalEmulator e = term(new Rec());
        e.feed(input);
        return e.getScreenPlainText().split("\n")[0].trim();
    }

    static String line0Bytewise(String input) throws Exception {
        TerminalEmulator e = term(new Rec());
        byte[] b = input.getBytes("UTF-8");
        for (int i = 0; i < b.length; i++) e.feed(b, i, 1);
        return e.getScreenPlainText().split("\n")[0].trim();
    }

    public static void main(String[] a) throws Exception {
        try { run(); } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
    }

    static void run() throws Exception {
        String ESC = "\u001b";
        // ---- the reported bug: intermediates must not leak the final byte ----
        check("DECSCUSR ESC[2 q  -> no stray 'q'", line0(ESC + "[2 qhello").equals("hello"));
        check("DECSCUSR ESC[6 q  -> no stray 'q'", line0(ESC + "[6 qhello").equals("hello"));
        check("DECSCUSR ESC[0 q  -> no stray 'q'", line0(ESC + "[0 qhello").equals("hello"));
        check("DECSCUSR ESC[ q (no parameter)", line0(ESC + "[ qhello").equals("hello"));
        check("DECSTR   ESC[!p   -> no stray 'p'", line0(ESC + "[!phello").equals("hello"));
        check("DECRQM   ESC[?2004$p -> no stray 'p'", line0(ESC + "[?2004$phello").equals("hello"));
        check("sequence in the middle of text", line0("ab" + ESC + "[2 qcd" + ESC + "[6 qef").equals("abcdef"));
        check("split across chunks (byte by byte)", line0Bytewise("ab" + ESC + "[2 qcd" + ESC + "[?2004$pef").equals("abcdef"));
        check("prompt_toolkit-like stream", line0(ESC + "[?2004h" + ESC + "[6 q> ls" + ESC + "[0 q").equals("> ls"));
        check("two intermediates ESC[ !qX consumed, text intact", line0(ESC + "[ !qX").equals("X"));
        // an intermediate sequence must not execute its final byte as a command
        {
            TerminalEmulator e = term(new Rec());
            e.feed("hello" + ESC + "[2 J"); // 'J' with intermediate must NOT erase the screen
            check("final byte with intermediate is ignored (ESC[2 J does not erase)",
                    e.getScreenPlainText().split("\n")[0].trim().equals("hello"));
        }
        // ---- regressions: ordinary sequences keep working ----
        check("SGR ESC[1;31m", line0(ESC + "[1;31mhello" + ESC + "[0m").equals("hello"));
        {
            Rec r = new Rec(); TerminalEmulator e = term(r);
            e.feed(ESC + "[3;5HX");
            check("CUP ESC[3;5H then X -> row 2, col 5 (0-based after print)", e.getCursorRow() == 2 && e.getCursorCol() == 5);
            e.feed(ESC + "[2A");
            check("CUU ESC[2A moves up two rows", e.getCursorRow() == 0);
            e.feed(ESC + "[?25l");
            check("DECTCEM ESC[?25l hides cursor", !e.isCursorVisible());
            e.feed(ESC + "[?25h");
            check("DECTCEM ESC[?25h shows cursor", e.isCursorVisible());
            e.feed(ESC + "[6n");
            check("DSR ESC[6n answers with a position report", r.responses.size() == 1 && r.responses.get(0).matches("\u001b\\[\\d+;\\d+R"));
            e.feed(ESC + "[c");
            check("DA ESC[c answers", r.responses.size() == 2);
        }
        check("EL ESC[2K clears the line", line0("xx" + ESC + "[2Khello").equals("hello"));
        check("ED ESC[2J clears the screen", line0("zzzz" + ESC + "[2J" + ESC + "[Hhello").equals("hello"));
        check("ICH/DCH ESC[2P deletes chars", line0("abcdef" + ESC + "[1G" + ESC + "[2P").equals("cdef"));
        {
            TerminalEmulator e = term(new Rec());
            e.feed(ESC + "[?1049h");
            check("alt screen ESC[?1049h", e.isAltScreen());
            e.feed(ESC + "[?1049l");
            check("alt screen ESC[?1049l", !e.isAltScreen());
        }
        check("OSC title still swallowed", line0(ESC + "]0;my title\u0007hello").equals("hello"));
        check("charset designation ESC(B still consumed", line0(ESC + "(Bhello").equals("hello"));
        // known limitation, reported not asserted: DEC line drawing is not translated
        System.out.println("INFO ESC(0 qq ESC(B prints as: [" + line0(ESC + "(0qq" + ESC + "(B") + "] (DEC Special Graphics not translated)");
        System.out.println(failures == 0 ? "ALL TERMINAL TESTS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
