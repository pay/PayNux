package com.paynux;

/**
 * Palet warna PayNux: satu sumber untuk seluruh UI.
 * Merah = identitas/peringatan, oranye = semua yang bisa disentuh.
 * Teks redup jangan lebih gelap dari DIM (kontras minimum ~5:1).
 */
final class Palette {
    private Palette() {}

    // Latar berlapis (netral hangat, tanpa semburat biru)
    static final int BG        = 0xFF0A0A0C;
    static final int PANEL     = 0xFF141416;
    static final int ROW       = 0xFF1C1C20;
    static final int LINE      = 0xFF2A2A30;

    // Aksen
    static final int RED       = 0xFFFF4A3D; // teks/strip merah
    static final int RED_FILL  = 0xFFDC2626; // isi tombol/badge (teks putih)
    static final int ORANGE    = 0xFFFF8A1F; // aksi, judul panel, kursor
    static final int AMBER     = 0xFFFFB020; // Ctrl/Alt aktif, warning
    static final int GREEN     = 0xFF3DDC84; // status ok

    // Teks
    static final int TEXT      = 0xFFECECEE;
    static final int TEXT2     = 0xFFB4B4BC;
    static final int DIM       = 0xFF8A8A94;

    // Turunan
    static final int PRESSED   = 0x33FF8A1F; // oranye 20% saat ditekan
    static final int ROW_ACTIVE = 0xFF372920; // oranye ~12% di atas ROW

    // Bubble tombol & panel extra-keys
    static final int KEY         = 0xFF1F1F25; // isi bubble
    static final int KEY_PRESSED = 0xFF3A2C22; // bubble ditekan (oranye gelap)
    static final int PANEL_IN    = 0xFF101014; // isi panel extra-keys
    static final int EDGE        = 0xFF4A2B26; // bingkai panel (merah-cokelat)
    static final int DOT         = 0xFF6E6E78; // titik halaman tak aktif
    static final int SEG         = 0xFF3A3A42; // garis sesi tak aktif
    static final int CHIP_RED    = 0x33FF4A3D; // chip hapus
    static final int CHIP_ORANGE = 0x33FF8A1F; // chip keyboard
}
