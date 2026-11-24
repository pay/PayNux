package com.paynux.compat;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the toolbar config (~/.paynux-keys), compatible with the Termux
 * "extra-keys" syntax:
 *
 *   extra-keys = [['ESC','DRAWER','HOME'], \
 *                 ['TAB','CTRL',"$"]]
 *
 * Supports single/double quoted keys, "\" line continuation, unbalanced
 * brackets continued on the next line, "#" comment lines, and DRAWER as an
 * alias of SESS. Unquoted keys keep working as before.
 */
public final class ToolbarConfig {
    private ToolbarConfig() {}

    /** Finds the extra-keys line in a config file text and parses it; null if none. */
    public static List<List<String>> parse(String fileText) {
        if (fileText == null) return null;
        String[] lines = fileText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("#") || !l.startsWith("extra-keys")) continue;
            StringBuilder joined = new StringBuilder(l);
            // "\" continuation, or brackets not yet balanced: pull in the next lines.
            while (i + 1 < lines.length && (endsWithBackslash(joined) || unbalanced(joined))) {
                if (endsWithBackslash(joined)) joined.setLength(lastNonSpace(joined));
                i++;
                String next = lines[i].trim();
                if (next.startsWith("#")) continue;
                joined.append(' ').append(next);
            }
            if (endsWithBackslash(joined)) joined.setLength(lastNonSpace(joined));
            int eq = joined.indexOf("=");
            if (eq < 0) continue;
            List<List<String>> rows = parseRows(joined.substring(eq + 1));
            if (rows != null) return rows;
        }
        return null;
    }

    /** Parses "[[A,'B'],[C,\"D\"]]" into [[A,B],[C,D]]; null when there is no row. */
    public static List<List<String>> parseRows(String v) {
        List<List<String>> rows = new ArrayList<List<String>>();
        List<String> row = null;
        StringBuilder cur = new StringBuilder();
        boolean tokenWasQuoted = false;
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (quote != 0) {                       // inside a quoted key: everything is literal
                if (c == quote) quote = 0; else cur.append(c);
                continue;
            }
            if (depth == 2 && (c == '\'' || c == '"')) {
                quote = c;
                tokenWasQuoted = true;
                continue;
            }
            if (c == '[') {
                depth++;
                if (depth == 2) row = new ArrayList<String>();
                continue;
            }
            if (c == ']') {
                if (depth == 2 && row != null) {
                    addToken(row, cur, tokenWasQuoted);
                    cur.setLength(0);
                    tokenWasQuoted = false;
                    if (!row.isEmpty()) rows.add(row);
                    row = null;
                }
                depth--;
                continue;
            }
            if (depth == 2 && row != null) {
                if (c == ',') {
                    addToken(row, cur, tokenWasQuoted);
                    cur.setLength(0);
                    tokenWasQuoted = false;
                } else if (!(c == '\\' && isLineBreakAhead(v, i))) {
                    cur.append(c);
                }
            }
        }
        return rows.isEmpty() ? null : rows;
    }

    private static void addToken(List<String> row, StringBuilder cur, boolean quoted) {
        String t = quoted ? cur.toString() : cur.toString().trim();
        if (t.isEmpty()) return;
        if (t.equalsIgnoreCase("DRAWER")) t = "SESS";
        row.add(t);
    }

    private static boolean isLineBreakAhead(String v, int i) {
        int j = i + 1;
        return j >= v.length() || v.charAt(j) == '\n' || v.charAt(j) == '\r' || v.charAt(j) == ' ';
    }

    private static boolean endsWithBackslash(CharSequence s) {
        int n = lastNonSpace(s);
        return n > 0 && s.charAt(n - 1) == '\\';
    }

    /** Index just after the last non-space character. */
    private static int lastNonSpace(CharSequence s) {
        int n = s.length();
        while (n > 0 && Character.isWhitespace(s.charAt(n - 1))) n--;
        return n;
    }

    /** True while "[" outnumber "]" outside quotes. */
    private static boolean unbalanced(CharSequence s) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '[') depth++;
            else if (c == ']') depth--;
        }
        return depth > 0;
    }
}
