package com.paynux.terminal;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * Terminal emulator engine: parses ANSI/VT escape sequences into a
 * cell grid (screen) plus scrollback lines. Pure Java (no Android
 * imports) so the parsing logic can be unit-tested on a desktop JVM.
 *
 * Supported: SGR (16/256/truecolor, bold, dim, italic, underline,
 * reverse), cursor movement, CUU/CUD/CUF/CUB/CUP/CHA/VPA, EL, ED,
 * IL/DL/DCH/ICH/ECH, SU/SD, DECSTBM scroll regions, DECSC/DECRC,
 * alt screen (?1049), cursor visibility (?25), autowrap (?7),
 * bracketed paste (?2004), OSC title, DCS/APC/PM strings, tab stops,
 * UTF-8 (wide chars approximated), bell.
 */
public final class TerminalEmulator {

    public static final int ATTR_BOLD = 1;
    public static final int ATTR_DIM = 2;
    public static final int ATTR_ITALIC = 4;
    public static final int ATTR_UNDERLINE = 8;
    public static final int ATTR_REVERSE = 16;

    /** fg/bg sentinel: use the terminal default color. */
    public static final int DEFAULT_COLOR = -1;

    public static final int MAX_SCROLLBACK = 4000;

    /** One row of styled cells. text.length == fg.length == bg.length == attrs.length. */
    public static final class Line {
        public final char[] text;
        public final int[] fg;
        public final int[] bg;
        public final int[] attrs;

        public Line(int cols) {
            text = new char[cols];
            fg = new int[cols];
            bg = new int[cols];
            attrs = new int[cols];
            for (int i = 0; i < cols; i++) {
                text[i] = ' ';
                fg[i] = DEFAULT_COLOR;
                bg[i] = DEFAULT_COLOR;
            }
        }

        Line(int cols, char[] t, int[] f, int[] b, int[] a) {
            text = t; fg = f; bg = b; attrs = a;
        }

        void clear(int from, int to) {
            for (int i = from; i <= to && i < text.length; i++) {
                text[i] = ' ';
                fg[i] = DEFAULT_COLOR;
                bg[i] = DEFAULT_COLOR;
                attrs[i] = 0;
            }
        }

        void copyFrom(Line o) {
            System.arraycopy(o.text, 0, text, 0, Math.min(text.length, o.text.length));
            System.arraycopy(o.fg, 0, fg, 0, Math.min(fg.length, o.fg.length));
            System.arraycopy(o.bg, 0, bg, 0, Math.min(bg.length, o.bg.length));
            System.arraycopy(o.attrs, 0, attrs, 0, Math.min(attrs.length, o.attrs.length));
        }
    }

    public interface Listener {
        void onScreenChanged();
        void onScrollbackChanged();
        void onBell();
        /** Data the emulator wants sent back to the terminal (DSR/DA answers). */
        void onResponse(String data);
    }

    private final Listener listener;
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private byte[] pending = new byte[4];
    private int pendingBytes = 0;

    private Line[] screen;
    private final ArrayDeque<Line> scrollback = new ArrayDeque<>();
    private long scrollTotal = 0;   // jumlah baris yang pernah masuk scrollback
    private Line[] altScreen;
    private boolean altActive = false;

    private int rows, cols;
    private int curRow = 0, curCol = 0;
    private int savedRow = 0, savedCol = 0, savedAttrs = 0;
    private int savedFg = DEFAULT_COLOR, savedBg = DEFAULT_COLOR;
    private int curFg = DEFAULT_COLOR, curBg = DEFAULT_COLOR, curAttrs = 0;
    private int scrollTop = 0, scrollBottom = 0; // inclusive
    private boolean wrapPending = false;
    private boolean cursorVisible = true;
    private boolean autowrap = true;
    private boolean dirty = false;
    private boolean scrollDirty = false;

    // parser state
    private static final int ST_GROUND = 0, ST_ESC = 1, ST_CSI = 2,
            ST_OSC = 3, ST_STR = 4, ST_CHARSET = 5;
    private int state = ST_GROUND;
    private final int[] params = new int[32];
    private final boolean[] paramColon = new boolean[32];
    private int nParams;
    private boolean privateMode;
    private int strKind; // for OSC/STR termination distinction
    private StringBuilder osc = new StringBuilder();

    private int[] tabStops;

    public TerminalEmulator(int rows, int cols, Listener listener) {
        this.listener = listener;
        this.rows = Math.max(rows, 2);
        this.cols = Math.max(cols, 4);
        screen = new Line[this.rows];
        for (int i = 0; i < this.rows; i++) screen[i] = new Line(this.cols);
        scrollBottom = this.rows - 1;
        tabStops = new int[this.cols];
        resetTabs();
    }

    private void resetTabs() {
        for (int i = 8; i < tabStops.length; i += 8) tabStops[i] = 1;
    }

    public int getRows() { return rows; }
    public int getCols() { return cols; }
    public boolean isAltScreen() { return altActive; }
    public synchronized int getScrollbackSize() { return scrollback.size(); }

    /** Nomor global baris terakhir + 1 (monotonik, tidak pernah mundur). */
    public synchronized long getScrollbackTotal() { return scrollTotal; }

    /**
     * Salin seluruh scrollback dalam SATU lock. Dipakai UI thread: thread PTY
     * menambah baris terus-menerus, jadi iterasi langsung atas deque bisa
     * melempar ConcurrentModificationException (crash di syncScrollback).
     * @return nomor global baris pertama yang masih tersedia
     */
    public synchronized long snapshotScrollback(java.util.List<Line> out) {
        out.clear();
        out.addAll(scrollback);
        return scrollTotal - scrollback.size();
    }

    public synchronized Line getScrollbackLine(int i) {
        if (i < 0 || i >= scrollback.size()) return null;
        int idx = 0;
        for (Line l : scrollback) { if (idx++ == i) return l; }
        return null;
    }
    public Line getScreenLine(int r) { return (r >= 0 && r < rows) ? screen[r] : null; }
    public int getCursorRow() { return curRow; }
    public int getCursorCol() { return curCol; }
    public boolean isCursorVisible() { return cursorVisible; }

    public String getScreenPlainText() {
        StringBuilder sb = new StringBuilder(rows * (cols + 1));
        for (int r = 0; r < rows; r++) {
            char[] t = screen[r].text;
            int end = t.length;
            while (end > 0 && (t[end - 1] == ' ' || t[end - 1] == 0)) end--;
            sb.append(t, 0, end).append('\n');
        }
        return sb.toString();
    }

    public String getScrollbackPlainText() {
        StringBuilder sb = new StringBuilder();
        for (Line l : scrollback) {
            int end = l.text.length;
            while (end > 0 && (l.text[end - 1] == ' ' || l.text[end - 1] == 0)) end--;
            sb.append(l.text, 0, end).append('\n');
        }
        return sb.toString();
    }

    /** Feed raw bytes from the terminal session. */
    public synchronized void feed(byte[] buf, int off, int len) {
        if (len <= 0) return;
        char[] chars;
        synchronized (decoder) {
            // JDK CharsetDecoder TIDAK men-buffer byte UTF-8 parsial;
            // sisa byte tetap di ByteBuffer dan harus dibawa ke feed
            // berikutnya (oleh karena itu pendingBytes di sini).
            byte[] data;
            int pend = pendingBytes;
            if (pend > 0) {
                data = new byte[pend + len];
                System.arraycopy(pending, 0, data, 0, pend);
                System.arraycopy(buf, off, data, pend, len);
                pendingBytes = 0;
            } else if (off == 0 && len == buf.length) {
                data = buf;
            } else {
                data = new byte[len];
                System.arraycopy(buf, off, data, 0, len);
            }
            ByteBuffer in = ByteBuffer.wrap(data);
            CharBuffer out = CharBuffer.allocate(data.length + 32);
            try {
                while (true) {
                    java.nio.charset.CoderResult cr = decoder.decode(in, out, false);
                    if (cr.isUnderflow() || cr.isOverflow()) break;
                    cr.throwException();
                }
            } catch (Exception e) {
                decoder.reset();
                chars = new String(data, StandardCharsets.UTF_8).toCharArray();
                feedChars(chars, 0, chars.length);
                return;
            }
            int rem = in.remaining();
            if (rem > 0 && rem <= 4) {
                if (pending.length < rem) pending = new byte[4];
                System.arraycopy(data, in.position(), pending, 0, rem);
                pendingBytes = rem;
            }
            out.flip();
            chars = new char[out.remaining()];
            out.get(chars);
        }
        feedChars(chars, 0, chars.length);
    }

    /** Feed already-decoded characters (used by tests too). */
    public synchronized void feed(String s) {
        feedChars(s.toCharArray(), 0, s.length());
    }

    private void feedChars(char[] cs, int off, int len) {
        for (int i = 0; i < len; i++) {
            char c = cs[off + i];
            switch (state) {
                case ST_GROUND:
                    handleGround(c);
                    break;
                case ST_ESC:
                    handleEsc(c);
                    break;
                case ST_CSI:
                    handleCsi(c);
                    break;
                case ST_OSC:
                    if (c == 0x07) { finishOsc(); state = ST_GROUND; }
                    else if (c == 0x1b) { state = ST_ESC; strKind = 2; } // expect '\\' terminator
                    else osc.append(c);
                    break;
                case ST_STR:
                    if (c == 0x1b) { state = ST_ESC; strKind = 3; }
                    else if (c == 0x07) { state = ST_GROUND; }
                    break;
                case ST_CHARSET:
                    state = ST_GROUND; // consume G0/G1 designation target
                    break;
                default:
                    state = ST_GROUND;
            }
        }
        flushDirty();
    }

    private void handleGround(char c) {
        switch (c) {
            case '\n': case 0x0b: case 0x0c: lineFeed(); break;
            case '\r': curCol = 0; wrapPending = false; break;
            case '\b': if (curCol > 0) curCol--; wrapPending = false; break;
            case '\t': tabForward(); break;
            case 0x07: if (listener != null) listener.onBell(); break;
            case 0x1b: state = ST_ESC; strKind = 0; break;
            case 0x9b: state = ST_CSI; beginCsi(); break;
            default:
                if (c < 0x20 || c == 0x7f) break; // ignore other controls
                putChar(c);
        }
    }

    private void handleEsc(char c) {
        if (strKind == 2) { // OSC or string terminator: ESC \
            state = (c == '\\') ? ST_GROUND : ST_GROUND;
            if (strKind == 2) { /* osc handled */ }
            strKind = 0;
            if (c != '\\') handleGround(c);
            return;
        }
        if (strKind == 3) { // string sequence terminated by ESC \
            state = ST_GROUND; strKind = 0;
            if (c != '\\') handleGround(c);
            return;
        }
        strKind = 0;
        switch (c) {
            case '[': state = ST_CSI; beginCsi(); break;
            case ']': state = ST_OSC; osc.setLength(0); break;
            case 'P': case '^': case '_': case 'X': state = ST_STR; break;
            case '(': case ')': case '*': case '+': state = ST_CHARSET; break;
            case '7': savedRow = curRow; savedCol = curCol; savedAttrs = curAttrs;
                      savedFg = curFg; savedBg = curBg; state = ST_GROUND; break;
            case '8': curRow = Math.min(savedRow, rows - 1); curCol = Math.min(savedCol, cols - 1); curAttrs = savedAttrs;
                      curFg = savedFg; curBg = savedBg; state = ST_GROUND; break;
            case 'D': lineFeed(); state = ST_GROUND; break;
            case 'E': curCol = 0; lineFeed(); state = ST_GROUND; break;
            case 'M': reverseIndex(); state = ST_GROUND; break;
            case 'c': fullReset(); state = ST_GROUND; break;
            case '=': case '>': state = ST_GROUND; break;
            default: state = ST_GROUND;
        }
        if (state != ST_ESC) markScreen();
    }

    private void finishOsc() {
        String s = osc.toString();
        if (s.startsWith("0;") || s.startsWith("2;")) {
            if (listener != null) { /* title: ignore for now */ }
        }
        osc.setLength(0);
    }

    private void beginCsi() {
        nParams = 0;
        privateMode = false;
        params[0] = 0;
        for (int i = 0; i < 32; i++) { params[i] = 0; paramColon[i] = false; }
    }

    private void handleCsi(char c) {
        if (c >= '0' && c <= '9') {
            if (nParams == 0) nParams = 1;
            if (nParams < params.length) {
                params[nParams - 1] = Math.min(params[nParams - 1] * 10 + (c - '0'), 65535);
            }
            return;
        }
        switch (c) {
            case ';':
                if (nParams < params.length) { nParams++; params[nParams - 1] = 0; paramColon[nParams - 1] = false; }
                return;
            case ':':
                if (nParams < params.length) { nParams++; params[nParams - 1] = 0; paramColon[nParams - 1] = true; }
                return;
            case '?': privateMode = true; return;
            case '<': case '>': case '=': return;
            default: break;
        }
        state = ST_GROUND;
        if (nParams == 0) params[0] = 0;
        executeCsi(c);
        markScreen();
    }

    private int p(int i, int def) {
        return (i < nParams && params[i] > 0) ? params[i] : def;
    }

    private void executeCsi(char fin) {
        if (privateMode) {
            switch (fin) {
                case 'h': setPrivateModes(true); break;
                case 'l': setPrivateModes(false); break;
                default: break;
            }
            return;
        }
        switch (fin) {
            case 'A': moveCursor(curRow - p(0, 1), curCol); break;
            case 'B': case 'e': moveCursor(curRow + p(0, 1), curCol); break;
            case 'C': case 'a': moveCursor(curRow, curCol + p(0, 1)); break;
            case 'D': moveCursor(curRow, curCol - p(0, 1)); break;
            case 'E': moveCursor(curRow + p(0, 1), 0); break;
            case 'F': moveCursor(curRow - p(0, 1), 0); break;
            case 'G': case '`': moveCursor(curRow, p(0, 1) - 1); break;
            case 'd': moveCursor(p(0, 1) - 1, curCol); break;
            case 'H': case 'f': moveCursor(p(0, 1) - 1, p(1, 1) - 1); break;
            case 'J': eraseDisplay(p(0, 0)); break;
            case 'K': eraseLine(p(0, 0)); break;
            case 'L': insertLines(p(0, 1)); break;
            case 'M': deleteLines(p(0, 1)); break;
            case 'P': deleteChars(p(0, 1)); break;
            case '@': insertChars(p(0, 1)); break;
            case 'X': eraseChars(p(0, 1)); break;
            case 'S': scrollUp(p(0, 1)); break;
            case 'T': scrollDown(p(0, 1)); break;
            case 'r':
                if (nParams >= 1) {
                    int t = Math.max(0, p(0, 1) - 1);
                    int b = (nParams >= 2 ? p(1, 1) : rows) - 1;
                    if (t < b && b < rows) { scrollTop = t; scrollBottom = b; curRow = 0; curCol = 0; wrapPending = false; }
                }
                break;
            case 'm': sgr(); break;
            case 'h': setAnsiModes(true); break;
            case 'l': setAnsiModes(false); break;
            case 'n':
                if (p(0, 0) == 6 && listener != null)
                    listener.onResponse("\u001b[" + (curRow + 1) + ";" + (curCol + 1) + "R");
                break;
            case 'c':
                if (listener != null) listener.onResponse("\u001b[?6c");
                break;
            case 's': savedRow = curRow; savedCol = curCol; break;
            case 'u': moveCursor(savedRow, savedCol); break;
            default: break;
        }
    }

    private void setPrivateModes(boolean on) {
        for (int i = 0; i < Math.max(nParams, 1); i++) {
            int m = params[i];
            switch (m) {
                case 25: cursorVisible = on; break;
                case 7: autowrap = on; break;
                case 1049: case 1047: case 47: setAltScreen(on, m != 1047 && m != 47); break;
                default: break; // ?2004 bracketed paste etc: accepted, no-op
            }
        }
    }

    private void setAnsiModes(boolean on) {
        // insert mode (4) etc: accepted as no-op
    }

    private void setAltScreen(boolean on, boolean savePrimary) {
        if (on == altActive) return;
        if (on) {
            altScreen = screen;
            screen = new Line[rows];
            for (int i = 0; i < rows; i++) screen[i] = new Line(cols);
            if (!savePrimary) {
                // 1047/47: keep contents of primary; on exit we restore it anyway
            }
            altActive = true;
            curRow = 0; curCol = 0;
        } else {
            if (altScreen != null) screen = fitScreen(altScreen);
            altScreen = null;
            altActive = false;
            clampCursor();
        }
        scrollBottom = rows - 1;
        markScreen();
    }

    /**
     * Buffer primer yang disimpan saat masuk alt screen TIDAK ikut
     * di-resize (mis. keyboard muncul / rotasi saat vim, nano, htop
     * terbuka). Saat keluar, sesuaikan lebar & jumlah barisnya dengan
     * ukuran terminal sekarang; kelebihan baris teratas masuk scrollback.
     */
    private Line[] fitScreen(Line[] src) {
        Line[] out = new Line[rows];
        int drop = Math.max(0, src.length - rows);
        for (int r = 0; r < drop; r++) {
            scrollback.addLast(src[r]);
            scrollTotal++;
            if (scrollback.size() > MAX_SCROLLBACK) scrollback.removeFirst();
        }
        if (drop > 0) scrollDirty = true;
        for (int i = 0; i < rows; i++) {
            int si = i + drop;
            out[i] = si < src.length ? resizeLine(src[si], cols) : new Line(cols);
        }
        curRow = Math.max(0, curRow - drop);
        savedRow = Math.max(0, savedRow - drop);
        return out;
    }

    private void clampCursor() {
        curRow = Math.max(0, Math.min(curRow, rows - 1));
        curCol = Math.max(0, Math.min(curCol, cols - 1));
        savedRow = Math.max(0, Math.min(savedRow, rows - 1));
        savedCol = Math.max(0, Math.min(savedCol, cols - 1));
        scrollTop = 0;
        scrollBottom = rows - 1;
        wrapPending = false;
    }

    private void sgr() {
        int n = Math.max(nParams, 1);
        for (int i = 0; i < n; i++) {
            int v = params[i];
            switch (v) {
                case 0: curFg = DEFAULT_COLOR; curBg = DEFAULT_COLOR; curAttrs = 0; break;
                case 1: curAttrs |= ATTR_BOLD; break;
                case 2: curAttrs |= ATTR_DIM; break;
                case 3: curAttrs |= ATTR_ITALIC; break;
                case 4: curAttrs |= ATTR_UNDERLINE; break;
                case 7: curAttrs |= ATTR_REVERSE; break;
                case 22: curAttrs &= ~(ATTR_BOLD | ATTR_DIM); break;
                case 23: curAttrs &= ~ATTR_ITALIC; break;
                case 24: curAttrs &= ~ATTR_UNDERLINE; break;
                case 27: curAttrs &= ~ATTR_REVERSE; break;
                case 39: curFg = DEFAULT_COLOR; break;
                case 49: curBg = DEFAULT_COLOR; break;
                default:
                    if (v >= 30 && v <= 37) curFg = ansiColor(v - 30);
                    else if (v >= 40 && v <= 47) curBg = ansiColor(v - 40);
                    else if (v >= 90 && v <= 97) curFg = ansiColor(v - 90 + 8);
                    else if (v >= 100 && v <= 107) curBg = ansiColor(v - 100 + 8);
                    else if (v == 38 || v == 48) {
                        if (i + 1 < n && params[i + 1] == 5 && i + 2 < n) {
                            int c = xterm256(params[i + 2]);
                            if (v == 38) curFg = c; else curBg = c;
                            i += 2;
                        } else if (i + 1 < n && params[i + 1] == 2) {
                            // ';' style: 38;2;r;g;b   ':' style: 38:2::r:g:b
                            boolean colonStyle = paramColon[i + 1];
                            int base = i + 1;
                            int ro = colonStyle ? 2 : 1; // offset of r from params[base]
                            // '38:2:r:g:b' (tanpa field colorspace) juga lazim dipakai;
                            // kalau bentuk 3-field tidak cukup, coba geser satu.
                            if (colonStyle && base + ro + 2 >= nParams && base + 1 + 2 < nParams) ro = 1;
                            if (base + ro + 2 < nParams || (base + ro + 2 == nParams - 1)) {
                                if (base + ro + 2 < params.length) {
                                    int r = params[base + ro], g = params[base + ro + 1], b = params[base + ro + 2];
                                    int c = ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff);
                                    if (v == 38) curFg = c; else curBg = c;
                                    i = base + ro + 2;
                                }
                            }
                        }
                    }
                    break;
            }
        }
    }

    public static int ansiColor(int idx) {
        switch (idx) {
            case 0: return 0x000000; case 1: return 0xcd0000; case 2: return 0x00cd00;
            case 3: return 0xcdcd00; case 4: return 0x0000ee; case 5: return 0xcd00cd;
            case 6: return 0x00cdcd; case 7: return 0xe5e5e5;
            case 8: return 0x7f7f7f; case 9: return 0xff0000; case 10: return 0x00ff00;
            case 11: return 0xffff00; case 12: return 0x5c5cff; case 13: return 0xff00ff;
            case 14: return 0x00ffff; default: return 0xffffff;
        }
    }

    public static int xterm256(int n) {
        if (n < 0) return 0xffffff;
        if (n < 16) return ansiColor(n);
        if (n < 232) {
            int v = n - 16;
            int r = v / 36, g = (v / 6) % 6, b = v % 6;
            int[] ramp = {0, 95, 135, 175, 215, 255};
            return (ramp[r] << 16) | (ramp[g] << 8) | ramp[b];
        }
        if (n < 256) {
            int g = 8 + (n - 232) * 10;
            return (g << 16) | (g << 8) | g;
        }
        return 0xffffff;
    }

    private void putChar(char c) {
        if (wrapPending && autowrap) {
            wrapPending = false;
            curCol = 0;
            lineFeed();
        }
        int w = charWidth(c);
        if (curCol + w > cols) {
            if (autowrap) { curCol = 0; lineFeed(); }
            else curCol = Math.max(0, cols - w);
        }
        if (curRow < 0) curRow = 0;
        if (curRow >= screen.length) curRow = screen.length - 1;
        Line l = screen[curRow];
        if (l.text.length != cols) {            // baris lama beda lebar: samakan
            l = resizeLine(l, cols);
            screen[curRow] = l;
        }
        if (curCol < 0) curCol = 0;
        if (curCol >= cols) curCol = cols - 1;
        l.text[curCol] = c;
        l.fg[curCol] = curFg;
        l.bg[curCol] = curBg;
        l.attrs[curCol] = curAttrs;
        if (w == 2 && curCol + 1 < cols) {
            l.text[curCol + 1] = 0; // zero-width marker for the wide char's 2nd cell
            l.fg[curCol + 1] = curFg;
            l.bg[curCol + 1] = curBg;
            l.attrs[curCol + 1] = curAttrs;
        }
        curCol += w;
        if (curCol >= cols) { curCol = cols - 1; wrapPending = true; }
        markScreen();
    }

    static int charWidth(char c) {
        if (c >= 0x1100 && (c <= 0x115f
                || (c >= 0x2e80 && c <= 0xa4cf && c != 0x303f)
                || (c >= 0xac00 && c <= 0xd7a3)
                || (c >= 0xf900 && c <= 0xfaff)
                || (c >= 0xfe30 && c <= 0xfe6f)
                || (c >= 0xff00 && c <= 0xff60)
                || (c >= 0xffe0 && c <= 0xffe6))) return 2;
        return 1;
    }

    private void tabForward() {
        int c = curCol;
        while (c < cols - 1) { c++; if (tabStops[c] == 1) break; }
        curCol = c;
        wrapPending = false;
    }

    private void lineFeed() {
        wrapPending = false;
        if (curRow == scrollBottom) scrollUp(1);
        else if (curRow < rows - 1) curRow++;
    }

    private void reverseIndex() {
        wrapPending = false;
        if (curRow == scrollTop) scrollDown(1);
        else if (curRow > 0) curRow--;
    }

    private void moveCursor(int r, int c) {
        curRow = Math.max(0, Math.min(r, rows - 1));
        curCol = Math.max(0, Math.min(c, cols - 1));
        wrapPending = false;
    }

    private void scrollUp(int n) {
        for (int k = 0; k < n; k++) {
            boolean full = (scrollTop == 0 && scrollBottom == rows - 1);
            if (full && !altActive) {
                scrollback.addLast(screen[0]);
                scrollTotal++;
                if (scrollback.size() > MAX_SCROLLBACK) scrollback.removeFirst();
                scrollDirty = true;
            }
            for (int r = scrollTop; r < scrollBottom; r++) screen[r] = screen[r + 1];
            screen[scrollBottom] = new Line(cols);
        }
    }

    private void scrollDown(int n) {
        for (int k = 0; k < n; k++) {
            for (int r = scrollBottom; r > scrollTop; r--) screen[r] = screen[r - 1];
            screen[scrollTop] = new Line(cols);
        }
    }

    private void eraseDisplay(int mode) {
        switch (mode) {
            case 0:
                screen[curRow].clear(curCol, cols - 1);
                for (int r = curRow + 1; r < rows; r++) screen[r].clear(0, cols - 1);
                break;
            case 1:
                for (int r = 0; r < curRow; r++) screen[r].clear(0, cols - 1);
                screen[curRow].clear(0, curCol);
                break;
            default:
                for (int r = 0; r < rows; r++) screen[r].clear(0, cols - 1);
                if (mode == 3) scrollback.clear();
                break;
        }
        wrapPending = false;
    }

    private void eraseLine(int mode) {
        switch (mode) {
            case 0: screen[curRow].clear(curCol, cols - 1); break;
            case 1: screen[curRow].clear(0, curCol); break;
            default: screen[curRow].clear(0, cols - 1); break;
        }
        wrapPending = false;
    }

    private void insertLines(int n) {
        if (curRow < scrollTop || curRow > scrollBottom) return;
        for (int k = 0; k < n; k++) {
            for (int r = scrollBottom; r > curRow; r--) screen[r] = screen[r - 1];
            screen[curRow] = new Line(cols);
        }
    }

    private void deleteLines(int n) {
        if (curRow < scrollTop || curRow > scrollBottom) return;
        for (int k = 0; k < n; k++) {
            for (int r = curRow; r < scrollBottom; r++) screen[r] = screen[r + 1];
            screen[scrollBottom] = new Line(cols);
        }
    }

    private void deleteChars(int n) {
        Line l = screen[curRow];
        int nn = Math.min(n, cols - curCol);
        for (int i = curCol; i < cols - nn; i++) {
            l.text[i] = l.text[i + nn]; l.fg[i] = l.fg[i + nn];
            l.bg[i] = l.bg[i + nn]; l.attrs[i] = l.attrs[i + nn];
        }
        l.clear(cols - nn, cols - 1);
    }

    private void insertChars(int n) {
        Line l = screen[curRow];
        int nn = Math.min(n, cols - curCol);
        for (int i = cols - 1; i >= curCol + nn; i--) {
            l.text[i] = l.text[i - nn]; l.fg[i] = l.fg[i - nn];
            l.bg[i] = l.bg[i - nn]; l.attrs[i] = l.attrs[i - nn];
        }
        l.clear(curCol, curCol + nn - 1);
    }

    private void eraseChars(int n) {
        screen[curRow].clear(curCol, Math.min(cols - 1, curCol + n - 1));
    }

    private void fullReset() {
        curFg = DEFAULT_COLOR; curBg = DEFAULT_COLOR; curAttrs = 0;
        curRow = 0; curCol = 0; wrapPending = false;
        cursorVisible = true; autowrap = true;
        scrollTop = 0; scrollBottom = rows - 1;
        for (int r = 0; r < rows; r++) screen[r].clear(0, cols - 1);
        if (altActive) setAltScreen(false, false);
        resetTabs();
    }

    /** Kosongkan seluruh state (untuk sesi baru). */
    public synchronized void reset() {
        for (int i = 0; i < rows; i++) screen[i] = new Line(cols);
        scrollback.clear();
        curRow = 0; curCol = 0;
        curFg = DEFAULT_COLOR; curBg = DEFAULT_COLOR; curAttrs = 0;
        savedRow = 0; savedCol = 0; savedAttrs = 0;
        savedFg = DEFAULT_COLOR; savedBg = DEFAULT_COLOR;
        altActive = false; cursorVisible = true; wrapPending = false;
        pendingBytes = 0;
        scrollTop = 0; scrollBottom = rows - 1;
        resetTabs();
        scrollDirty = true;
        markScreen();
        flushDirty();
    }

    /** Resize the terminal (e.g. rotation or font change). */
    public synchronized void resize(int newRows, int newCols) {
        newRows = Math.max(newRows, 2);
        newCols = Math.max(newCols, 4);
        if (newRows == rows && newCols == cols) return;

        // Kolom berubah (zoom font / rotasi): bungkus ulang isi agar tidak
        // terpotong. Di alt-screen biarkan aplikasi menggambar ulang sendiri.
        if (newCols != cols && !altActive) rewrapAll(newCols);

        Line[] ns = new Line[newRows];
        if (newRows <= rows) {
            // Mengecil (keyboard muncul): buang dulu baris KOSONG di
            // bawah kursor; baru bila masih kurang, geser baris teratas
            // ke scrollback. Dengan begitu prompt tidak ikut terbuang
            // dan tidak terjadi baris kosong/prompt ganda.
            int excess = rows - newRows;
            int bottomBlank = 0;
            for (int r = rows - 1; r > curRow && bottomBlank < excess; r--) {
                if (isBlankLine(screen[r])) bottomBlank++; else break;
            }
            int drop = excess - bottomBlank;
            for (int r = 0; r < drop; r++) {
                if (!altActive) {
                    scrollback.addLast(screen[r]);
                    scrollTotal++;
                    if (scrollback.size() > MAX_SCROLLBACK) scrollback.removeFirst();
                }
            }
            curRow = Math.max(0, curRow - drop);
            for (int i = 0; i < newRows; i++) ns[i] = resizeLine(screen[i + drop], newCols);
            if (drop > 0) scrollDirty = true;
        } else {
            // Membesar (keyboard tutup): tarik baris dari EKOR
            // scrollback HANYA bila layar sudah penuh (kursor/isi di
            // baris terbawah). Kalau masih ada ruang kosong di bawah,
            // cukup tambah baris kosong — isi tidak bergeser.
            int grow = newRows - rows;
            boolean full = curRow >= rows - 1 || !isBlankLine(screen[rows - 1]);
            int take = (altActive || !full) ? 0 : Math.min(grow, scrollback.size());
            for (int i = take - 1; i >= 0; i--)
                ns[i] = resizeLine(scrollback.pollLast(), newCols);
            if (take > 0) scrollDirty = true;
            for (int i = 0; i < rows; i++)
                ns[take + i] = resizeLine(screen[i], newCols);
            for (int i = take + rows; i < newRows; i++)
                ns[i] = new Line(newCols);
            curRow = Math.min(curRow + take, newRows - 1);
        }
        screen = ns;
        rows = newRows;
        cols = newCols;
        curRow = Math.min(curRow, rows - 1);
        curCol = Math.min(curCol, cols - 1);
        wrapPending = false;
        scrollTop = 0;
        scrollBottom = rows - 1;
        tabStops = new int[cols];
        resetTabs();
        markScreen();
    }

    private static boolean isBlankLine(Line l) {
        if (l == null) return true;
        for (int c = 0; c < l.text.length; c++) {
            if (l.text[c] != ' ' && l.text[c] != 0) return false;
        }
        return true;
    }

    /** Panjang teks efektif: tanpa spasi/nol di kanan. */
    private static int effectiveLen(Line l) {
        int n = l.text.length;
        while (n > 0 && (l.text[n - 1] == ' ' || l.text[n - 1] == 0)) n--;
        return n;
    }

    private static Line sliceTo(Line acc, int len, int newCols) {
        if (len >= newCols) return acc;
        Line n = new Line(newCols);
        System.arraycopy(acc.text, 0, n.text, 0, len);
        System.arraycopy(acc.fg, 0, n.fg, 0, len);
        System.arraycopy(acc.bg, 0, n.bg, 0, len);
        System.arraycopy(acc.attrs, 0, n.attrs, 0, len);
        return n;
    }

    /**
     * Ulang-bungkus SELURUH isi (scrollback + layar) ke jumlah kolom baru.
     * Dipakai saat font di-zoom: tanpa ini baris lama hanya dipotong/di-padding
     * sehingga log tidak lagi pas dengan lebar layar.
     * Baris yang terisi sampai kolom terakhir dianggap menyambung ke baris
     * berikutnya (heuristik yang sama dipakai terminal saat menyalin teks).
     */
    private void rewrapAll(int newCols) {
        ArrayList<Line> src = new ArrayList<>(scrollback.size() + rows);
        src.addAll(scrollback);
        for (int r = 0; r < rows; r++) src.add(screen[r]);

        // Kursor dilacak per baris-logis (bukan indeks sel datar): baris
        // pendek tidak memakai lebar penuh, jadi posisi kursor harus ikut
        // baris sumbernya, bukan dihitung ulang dari jumlah karakter.
        final int cursorSrc = scrollback.size() + curRow;
        int cursorOutBase = -1;   // baris output tempat baris sumber kursor mulai
        int cursorColBase = 0;    // kolom dalam baris output itu

        ArrayList<Line> out = new ArrayList<>(src.size() + 8);
        Line acc = new Line(newCols);
        int len = 0;
        boolean pending = false;
        int accIndex = 0;         // indeks di 'out' tempat acc ini akan diletakkan

        for (int i = 0; i < src.size(); i++) {
            Line l = src.get(i);
            if (l == null) l = new Line(cols);
            if (i == cursorSrc) { cursorOutBase = accIndex; cursorColBase = len; }
            int end = effectiveLen(l);
            boolean joined = end >= l.text.length;   // penuh -> menyambung
            if (joined) end = l.text.length;
            for (int c = 0; c < end; c++) {
                if (len == newCols) {
                    out.add(acc);
                    accIndex = out.size();
                    acc = new Line(newCols);
                    len = 0;
                }
                acc.text[len] = l.text[c];
                acc.fg[len] = l.fg[c];
                acc.bg[len] = l.bg[c];
                acc.attrs[len] = l.attrs[c];
                len++;
            }
            if (!joined) {
                if (len > 0 || pending) {
                    out.add(sliceTo(acc, len, newCols));
                } else {
                    out.add(new Line(newCols));
                }
                accIndex = out.size();
                acc = new Line(newCols);
                len = 0;
                pending = false;
            } else {
                pending = true;
            }
        }
        if (len > 0 || pending) {
            out.add(sliceTo(acc, len, newCols));
            accIndex = out.size();
        }

        int screenStart = Math.max(0, out.size() - rows);
        int oldSize = scrollback.size();
        scrollback.clear();
        for (int i = 0; i < screenStart; i++) scrollback.addLast(out.get(i));
        while (scrollback.size() > MAX_SCROLLBACK) scrollback.removeFirst();
        screen = new Line[rows];
        for (int r = 0; r < rows; r++) {
            int i = screenStart + r;
            screen[r] = (i < out.size()) ? out.get(i) : new Line(newCols);
        }
        if (scrollback.size() > oldSize) scrollTotal += scrollback.size() - oldSize;

        cols = newCols;
        if (cursorOutBase >= 0) {
            int cells = cursorColBase + Math.max(0, curCol);
            curRow = Math.max(0, Math.min(rows - 1, cursorOutBase + cells / newCols));
            curCol = Math.max(0, Math.min(cols - 1, cells % newCols));
        } else {
            curRow = 0;
            curCol = 0;
        }
        wrapPending = false;
        scrollTop = 0;
        scrollBottom = rows - 1;
        tabStops = new int[cols];
        resetTabs();
        scrollDirty = true;
        markScreen();
    }

    private static Line resizeLine(Line l, int newCols) {
        if (l == null) return new Line(newCols);
        if (l.text.length == newCols) return l;
        Line n = new Line(newCols);
        int m = Math.min(newCols, l.text.length);
        System.arraycopy(l.text, 0, n.text, 0, m);
        System.arraycopy(l.fg, 0, n.fg, 0, m);
        System.arraycopy(l.bg, 0, n.bg, 0, m);
        System.arraycopy(l.attrs, 0, n.attrs, 0, m);
        return n;
    }

    private void markScreen() { dirty = true; }

    private void flushDirty() {
        if (scrollDirty) { scrollDirty = false; if (listener != null) listener.onScrollbackChanged(); }
        if (dirty) { dirty = false; if (listener != null) listener.onScreenChanged(); }
    }
}
