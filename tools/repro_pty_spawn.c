/*
 * Repro logika nativeSpawn (Java_com_paynux_pty_PtyNative_nativeSpawn),
 * direkonstruksi dari disassembly libpaynuxpty.so @0x132c-0x1664:
 *
 *   w27 = jumlah string NUL-terminated di blob
 *   p   = calloc(max(w27,1), 8)            <-- alokasi
 *   untuk setiap string: p[w28++] = strdup(...)
 *   p[w28] = NULL                          <-- w28 == w27  => 8 byte lewat batas
 *
 * Bug: penulis terminator NULL memakai indeks w27 padahal array hanya
 * berisi w27 elemen. Tulis 8 byte NOL tepat setelah alokasi itulah yang
 * memunculkan "Scudo ERROR: corrupted chunk header ... chunk header is
 * zero" di tombstone.
 *
 * Bangun: clang -fsanitize=address -g -O0 repro.c -o repro_asan
 * Jalankan: ./repro_asan orig      (harus melaporkan heap-buffer-overflow)
 *           ./repro_asan fixed     (harus bersih)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* blob gaya PtyNative.toBlob(): "satu\0dua\0tiga\0" */
static unsigned char blob[] =
    "libproot.so\0-0\0-r\0/data/user/0/com.paynux/files/ubuntu\0"
    "-b\0/data/user/0:/data/data\0--link2symlink\0-w\0/root\0"
    "/bin/bash\0--login\0";

static int count_strings(const unsigned char *b, int len) {
    int n = 0;
    for (int i = 0; i < len; i++) if (b[i] == 0) n++;
    if (len > 0 && b[len - 1] != 0) n++;
    return n;
}

static char **build(const unsigned char *b, int len, int fixed) {
    int w27 = count_strings(b, len);

    /* asli: calloc(max(w27,1), 8)   |   perbaikan: calloc(w27+1, 8) */
    int slots = fixed ? (w27 + 1) : (w27 > 1 ? w27 : 1);
    char **p = (char **) calloc((size_t) slots, 8);
    if (!p) return NULL;

    int w28 = 0, i = 0;
    while (i < len && w28 < w27) {
        int start = i;
        while (i < len && b[i] != 0) i++;
        int n = i - start;
        if (n > 0) {
            char *s = (char *) malloc((size_t) n + 1);
            if (!s) return p;
            memcpy(s, b + start, (size_t) n);
            s[n] = 0;
            p[w28++] = s;              /* str x26, [x24, w28, sxtw #3] */
        }
        i++;                            /* lewati NUL */
    }
    p[w28] = NULL;                      /* str xzr, [x24, w28, sxtw #3]  <-- LEAK */
    return p;
}

int main(int argc, char **argv) {
    int fixed = (argc > 1 && strcmp(argv[1], "fixed") == 0);
    /* panjang blob asli dari JNI = jumlah byte yang dikirim toBlob(),
       TANPA NUL tambahan dari literal C (itu artefak harness: kelebihan
       satu slot akan menyembunyikan overflow). */
    int len = (int) sizeof(blob) - 1;
    printf("mode=%s  len=%d  zeros=%d  strings=%d\n",
           fixed ? "fixed" : "orig", len,
           count_strings(blob, len), count_strings(blob, len));

    char **p = build(blob, len, fixed);
    int n = 0;
    while (p[n]) n++;
    printf("argc=%d  argv[0]=%s  argv[last]=%s\n", n, p[0], p[n - 1]);

    for (int k = 0; k < n; k++) free(p[k]);
    free(p);
    puts("selesai tanpa error");
    return 0;
}
