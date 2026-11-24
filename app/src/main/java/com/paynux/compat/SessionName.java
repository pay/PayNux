package com.paynux.compat;

/** Cleans user-entered session names. */
public final class SessionName {
    private SessionName() {}

    public static final int MAX_LENGTH = 32;

    /** Trimmed, single-spaced, control characters removed, at most 32 chars; null means "use the default name". */
    public static String clean(String input) {
        if (input == null) return null;
        StringBuilder sb = new StringBuilder();
        boolean lastSpace = true; // drops leading whitespace
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastSpace) { sb.append(' '); lastSpace = true; }
            } else if (!Character.isISOControl(c)) {
                sb.append(c);
                lastSpace = false;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        sb.setLength(end);
        if (sb.length() > MAX_LENGTH) {
            int cut = MAX_LENGTH;
            if (Character.isHighSurrogate(sb.charAt(cut - 1))) cut--; // do not split an emoji
            sb.setLength(cut);
            while (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') sb.setLength(sb.length() - 1);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
