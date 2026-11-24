package com.paynux.compat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the http(s)/ftp URL that a text selection touches, so "Open link" works on a partial selection. */
public final class UrlFinder {
    private UrlFinder() {}

    private static final Pattern URL =
            Pattern.compile("(?i)\\b(?:https?|ftp)://[^\\s<>\"'`\\\\^{|}]+");

    private static final int MAX_REGION = 4000;

    /**
     * Returns the URL on the selected line(s) that overlaps [selStart, selEnd),
     * with trailing punctuation removed, or null.
     */
    public static String urlAround(CharSequence text, int selStart, int selEnd) {
        if (text == null) return null;
        int n = text.length();
        if (selStart < 0 || selEnd <= selStart || selEnd > n) return null;
        int regionStart = selStart;
        while (regionStart > 0 && text.charAt(regionStart - 1) != '\n') regionStart--;
        int regionEnd = selEnd;
        while (regionEnd < n && text.charAt(regionEnd) != '\n') regionEnd++;
        if (regionEnd - regionStart > MAX_REGION) return null;
        String region = text.subSequence(regionStart, regionEnd).toString();
        Matcher m = URL.matcher(region);
        while (m.find()) {
            int s = regionStart + m.start();
            int e = regionStart + trimEnd(region, m.start(), m.end());
            if (e > s && s < selEnd && e > selStart) {
                return text.subSequence(s, e).toString();
            }
        }
        return null;
    }

    /** End index (in region coordinates) after dropping trailing punctuation. */
    private static int trimEnd(String region, int start, int end) {
        while (end > start) {
            char c = region.charAt(end - 1);
            if (".,;:!?)]}>'\"".indexOf(c) >= 0) end--; else break;
        }
        return end;
    }
}
