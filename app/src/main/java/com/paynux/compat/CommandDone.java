package com.paynux.compat;

import java.util.Locale;

/**
 * "Command finished" notifications.
 *
 * A small bash hook (HOOK, appended to /etc/paynux.sh) prints an invisible
 * OSC 777 sequence when a command that ran longer than a threshold ends:
 *
 *   ESC ] 777 ; paynux-done ; <exit code> ; <seconds> ; <command> BEL
 *
 * The terminal emulator hands that string to parse(); the app then shows a
 * notification built with title()/text().
 */
public final class CommandDone {
    private CommandDone() {}

    /** Appended to /etc/paynux.sh (the file is rewritten at every session start). */
    public static final String HOOK =
            "# Paynux: notify when a long command finishes (bash >= 4.4).\n"
            + "# PAYNUX_NOTIFY_AFTER=<seconds> in ~/.bashrc changes the threshold (default 10); 0 turns it off.\n"
            + "if [ -n \"${BASH_VERSION:-}\" ] && [ -z \"${__paynux_notify:-}\" ]; then\n"
            + "  __paynux_notify=1\n"
            + "  __paynux_run=0\n"
            + "  __paynux_t0=0\n"
            + "  __paynux_x=x   # must be non-empty: PS0's offset trick below is skipped for unset/empty variables\n"
            + "  __paynux_precmd() {\n"
            + "    local rc=$?\n"
            + "    printf '\\033]777;paynux-busy;0\\007'   # idle: app boleh melepas wakelock otomatis\n"
            + "    if [ \"${__paynux_run:-0}\" = 1 ]; then\n"
            + "      __paynux_run=0\n"
            + "      local thr=${PAYNUX_NOTIFY_AFTER:-10}\n"
            + "      local dur=$((SECONDS - __paynux_t0))\n"
            + "      if [ \"$thr\" -gt 0 ] 2>/dev/null && [ \"$dur\" -ge \"$thr\" ]; then\n"
            + "        local c\n"
            + "        c=$(fc -ln -1 2>/dev/null | head -n 1 | tr -d '\\000-\\037\\177;' | sed 's/^ *//' | cut -c1-40)\n"
            + "        printf '\\033]777;paynux-done;%s;%s;%s\\007' \"$rc\" \"$dur\" \"${c:-command}\"\n"
            + "      fi\n"
            + "    fi\n"
            + "    return $rc\n"
            + "  }\n"
            + "  PROMPT_COMMAND=\"__paynux_precmd${PROMPT_COMMAND:+;$PROMPT_COMMAND}\"\n"
            + "  PS0=\"${PS0:-}\"'${__paynux_x:$((__paynux_t0=SECONDS,__paynux_run=1,0)):0}\\e]777;paynux-busy;1\\a'\n"
            + "fi\n";

    public static final class Event {
        public final int exit;
        public final long seconds;
        public final String cmd;

        Event(int exit, long seconds, String cmd) {
            this.exit = exit;
            this.seconds = seconds;
            this.cmd = cmd;
        }
    }

    private static final String PREFIX = "777;paynux-done;";
    private static final int MAX_CMD = 40;

    /** Parses the OSC string (without ESC ] and BEL); null if it is not a valid paynux-done report. */
    public static Event parse(String osc) {
        if (osc == null || !osc.startsWith(PREFIX)) return null;
        String[] p = osc.substring(PREFIX.length()).split(";", 3);
        if (p.length < 2) return null;
        int exit;
        long secs;
        try {
            if (!p[0].matches("\\d{1,3}") || !p[1].matches("\\d{1,9}")) return null;
            exit = Integer.parseInt(p[0]);
            secs = Long.parseLong(p[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (exit > 255) return null;
        return new Event(exit, secs, cleanCmd(p.length > 2 ? p[2] : ""));
    }

    /** Printable characters only, trimmed, at most 40 chars; "command" when nothing is left. */
    static String cleanCmd(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isISOControl(c)) sb.append(c);
        }
        String t = sb.toString().trim();
        if (t.length() > MAX_CMD) {
            int cut = MAX_CMD;
            if (Character.isHighSurrogate(t.charAt(cut - 1))) cut--;
            t = t.substring(0, cut).trim();
        }
        return t.isEmpty() ? "command" : t;
    }

    public static String title(int exit, String cmd) {
        return (exit == 0 ? "Done: " : "Failed: ") + cmd;
    }

    public static String text(int exit, long seconds, String sessionTitle) {
        return "exit " + exit + " \u00b7 " + duration(seconds) + " \u00b7 " + sessionTitle;
    }

    /** "45s", "3m05s", "1h05m". */
    public static String duration(long s) {
        if (s < 0) s = 0;
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m" + String.format(Locale.US, "%02d", s % 60) + "s";
        return (s / 3600) + "h" + String.format(Locale.US, "%02d", (s % 3600) / 60) + "m";
    }
}
