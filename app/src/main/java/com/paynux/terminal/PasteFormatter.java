package com.paynux.terminal;

/**
 * Prepares clipboard text for the shell the way xterm-compatible terminals do.
 *
 * With bracketed paste on (the app sent CSI ? 2004 h), the text is wrapped in
 * ESC[200~ ... ESC[201~ so bash/readline and prompt_toolkit insert it
 * literally: tabs do not complete and newlines do not run commands. With it
 * off, newlines are sent as carriage returns, which is what Enter sends.
 *
 * Both marker sequences are removed from the text first, so pasted content
 * cannot end the bracket early and inject commands.
 */
public final class PasteFormatter {
    private PasteFormatter() {}

    public static final String START = "\u001b[200~";
    public static final String END = "\u001b[201~";

    public static String format(String text, boolean bracketed) {
        if (text == null || text.isEmpty()) return "";
        String t = text;
        // Loop: removing one marker must not be able to assemble another.
        while (t.contains(START) || t.contains(END)) {
            t = t.replace(START, "").replace(END, "");
        }
        if (t.isEmpty()) return "";
        if (bracketed) {
            t = t.replace("\r\n", "\n").replace('\r', '\n');
            return START + t + END;
        }
        return t.replace("\r\n", "\r").replace('\n', '\r');
    }
}
