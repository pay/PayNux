import com.paynux.compat.CommandDone;
import com.paynux.terminal.TerminalEmulator;

import java.util.ArrayList;
import java.util.List;

/** Sandbox tests for CommandDone (OSC parser, formatting) and the emulator callback. */
public final class CommandDoneTest {
    static int failures = 0;
    static final String ESC = "\u001b", BEL = "\u0007";

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static final class Rec implements TerminalEmulator.CommandListener {
        final List<String> got = new ArrayList<String>();
        public void onCommandDone(int exit, long seconds, String cmd) { got.add(exit + "|" + seconds + "|" + cmd); }
    }

    static TerminalEmulator term(Rec r) {
        TerminalEmulator e = new TerminalEmulator(6, 40, new TerminalEmulator.Listener() {
            public void onScreenChanged() { }
            public void onScrollbackChanged() { }
            public void onBell() { }
            public void onResponse(String d) { }
        });
        if (r != null) e.setCommandListener(r);
        return e;
    }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
    }

    static void run() throws Exception {
        // ---- parse ----
        CommandDone.Event e = CommandDone.parse("777;paynux-done;2;193;hermes");
        check("parse: exit, seconds, command", e != null && e.exit == 2 && e.seconds == 193 && e.cmd.equals("hermes"));
        e = CommandDone.parse("777;paynux-done;0;12;apt upgrade -y");
        check("parse: command with spaces", e != null && e.cmd.equals("apt upgrade -y"));
        e = CommandDone.parse("777;paynux-done;0;12;");
        check("parse: empty command -> 'command'", e != null && e.cmd.equals("command"));
        e = CommandDone.parse("777;paynux-done;0;12");
        check("parse: missing command field -> 'command'", e != null && e.cmd.equals("command"));
        e = CommandDone.parse("777;paynux-done;0;12;a;b;c");
        check("parse: extra ';' stays in the command text", e != null && e.cmd.equals("a;b;c"));
        check("parse: other OSC 777 / OSC 0 are not ours", CommandDone.parse("777;notify;x;y") == null && CommandDone.parse("0;title") == null);
        check("parse: non-numeric / negative / too long / >255 are rejected",
                CommandDone.parse("777;paynux-done;x;12;c") == null && CommandDone.parse("777;paynux-done;-1;12;c") == null
                        && CommandDone.parse("777;paynux-done;0;9999999999;c") == null && CommandDone.parse("777;paynux-done;256;1;c") == null
                        && CommandDone.parse("777;paynux-done;1234;1;c") == null);
        check("parse: null / empty / prefix only", CommandDone.parse(null) == null && CommandDone.parse("") == null && CommandDone.parse("777;paynux-done;") == null);
        e = CommandDone.parse("777;paynux-done;0;5;" + new String(new char[100]).replace('\0', 'x'));
        check("parse: command truncated to 40 chars", e != null && e.cmd.length() == 40);
        e = CommandDone.parse("777;paynux-done;0;5;a\u0001b\u001bc\u0007d");
        check("parse: control characters removed from the command", e != null && e.cmd.equals("abcd"));
        e = CommandDone.parse("777;paynux-done;0;5;1234567890123456789012345678901234567\uD83D\uDE00xyz");
        check("parse: emoji not split by truncation", e != null && !Character.isHighSurrogate(e.cmd.charAt(e.cmd.length() - 1)));
        // ---- formatting ----
        check("duration: seconds / minutes / hours", CommandDone.duration(45).equals("45s") && CommandDone.duration(185).equals("3m05s")
                && CommandDone.duration(3900).equals("1h05m") && CommandDone.duration(-3).equals("0s"));
        check("title: Done / Failed", CommandDone.title(0, "hermes").equals("Done: hermes") && CommandDone.title(2, "hermes").equals("Failed: hermes"));
        check("text line", CommandDone.text(2, 193, "Session 1").equals("exit 2 \u00b7 3m13s \u00b7 Session 1"));

        // ---- emulator callback ----
        Rec r = new Rec(); TerminalEmulator t = term(r);
        t.feed(ESC + "]777;paynux-done;2;193;hermes" + BEL);
        check("emulator: BEL-terminated report reaches the listener once", r.got.size() == 1 && r.got.get(0).equals("2|193|hermes"));
        t.feed(ESC + "]777;paynux-done;0;11;make" + ESC + "\\");
        check("emulator: ST-terminated (ESC \\) report works too", r.got.size() == 2 && r.got.get(1).equals("0|11|make"));
        byte[] b = (ESC + "]777;paynux-done;1;20;split" + BEL).getBytes("UTF-8");
        for (int i = 0; i < b.length; i++) t.feed(b, i, 1);
        check("emulator: report split across chunks (byte by byte)", r.got.size() == 3 && r.got.get(2).equals("1|20|split"));
        t.feed(ESC + "]777;paynux-done;bad;1;x" + BEL + ESC + "]777;other;1;2;3" + BEL + ESC + "]0;title" + BEL);
        check("emulator: invalid/other OSC are ignored", r.got.size() == 3);
        check("emulator: nothing is printed on screen", t.getScreenPlainText().trim().isEmpty());
        t.feed("hello");
        check("emulator: normal text after reports is unaffected", t.getScreenPlainText().split("\n")[0].trim().equals("hello"));
        TerminalEmulator none = term(null);
        none.feed(ESC + "]777;paynux-done;0;11;make" + BEL + "ok");
        check("emulator: no listener set -> no crash, text intact", none.getScreenPlainText().split("\n")[0].trim().equals("ok"));
        System.out.println(failures == 0 ? "ALL COMMANDDONE TESTS PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
