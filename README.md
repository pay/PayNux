# PayNux

**Terminal Android dengan Ubuntu 24.04 ARM64 di dalamnya.**

PayNux menjalankan rootfs Ubuntu 24.04 lengkap di atas Android lewat PRoot
(*Proot* = **PR**oot + **oot**, emulator `chroot` di user space), lalu
menyajikannya lewat terminal emulator sendiri. Tidak butuh root, tidak butuh
Termux, tidak butuh container runtime.

```
┌─ Android ───────────────────────────────┐
│  PayNux (Java)                          │
│    ├─ TerminalEmulator  (ANSI/VT parser)│
│    ├─ libpaynuxpty.so   (PTY native)    │
│    └─ proot  ──▶  Ubuntu 24.04 ARM64    │
│                     ├─ bash, coreutils  │
│                     ├─ apt / pip / node │
│                     └─ /root ─▶ files/home (persist) │
└─────────────────────────────────────────┘
```

---

## Daftar Isi

- [Fitur](#fitur)
- [Cara Kerja](#cara-kerja)
- [Instalasi](#instalasi)
- [Build dari Sumber](#build-dari-sumber)
- [Konfigurasi](#konfigurasi)
- [Struktur Repository](#struktur-repository)
- [Status & Roadmap](#status--roadmap)
- [Kontribusi](#kontribusi)
- [Lisensi](#lisensi)
- [Acknowledgement](#acknowledgement)

---

## Fitur

### Terminal

- **Emulator terminal sendiri** (murni Java, tanpa Android API) — bisa diuji
  di desktop JVM. Mendukung SGR 16/256/**truecolor** (warna 24-bit), bold, dim,
  italic, underline, reverse, cursor movement, scroll region (`DECSTBM`),
  insert/delete baris & kolom, **alt screen** (`?1049`), bracketed paste
  (`?2004`), OSC title, tab stops, UTF-8, bell.
- **Scrollback 4000 baris**, bisa di-scroll, disalin utuh, dan dibuka tautannya.
- **Zoom font** jepit dua jari.
- **Bell → getar**.
- **Truecolor 38;2:r:g:b** diterima tanpa field colorspace.

### Sesi & proses

- **Multi-sesi** (maks. 8) dengan drawer geser dari tepi kiri, ala Termux.
- **State sesi di level proses**, bukan level Activity. Menggeser aplikasi dari
  Recents **tidak** mematikan shell — persis seperti Termux.
- **Foreground service + notifikasi** dengan tombol:
  - **Acquire / Release wakelock** — CPU & Wi-Fi tetap aktif saat layar mati.
    Wakelock **mati secara default** (hemat baterai), dinyalakan manual.
  - **Exit** — satu-satunya jalan keluar sungguhan. Menulis penanda
    `files/home/.paynux-clean-exit` lalu mematikan semua proses.
- **Transport PTY native** (`libpaynuxpty.so`) untuk prompt, echo, warna, dan
  `Ctrl+C` yang benar. Kalau PTY gagal dibuat, otomatis fallback ke pipe
  (`ProcessBuilder`) sehingga boot tetap berjalan.

### Shell & Ubuntu

- **Ubuntu 24.04 ARM64** ter-bundle sebagai asset APK (`ubuntu-24.04-aarch64.tar.xz`,
  ±17 MB terkompresi), diekstrak saat pertama kali aplikasi dibuka.
- **`HOME=/root` di-bind ke `files/home`** — data pengguna **di luar rootfs**,
  jadi tidak hilang saat aplikasi diperbarui.
- **`/sdcard` ter-bind** ke `/storage/emulated/0` (setelah izin storage
  diberikan), jadi file di HP bisa diakses dari dalam Ubuntu.
- **DNS** diarahkan ke `8.8.8.8` / `8.8.4.4`.
- **Prompt berwarna ala Kali**: `root` hijau, `@` cyan, `ubuntu` merah,
  direktori biru muda, `#` putih tebal — merah bila perintah terakhir gagal.
- **`~/.bashrc` milikmu tetap milikmu** — konfigurasi app injecting
  `/etc/paynux.sh` yang selalu ditulis ulang tiap sesi.
- **Riwayat shell langsung ditulis** ke disk (`history -a`), tidak hilang saat
  app ditutup.
- **Helper bawaan**: `portcheck [port]` (mengecek siapa yang pakai port) dan
  `killport <port> --force` (hanya menghentikan proses yang listen di port itu).

### Antarmuka

- **Toolbar extra-keys** ala Termux, **bisa dikonfigurasi** lewat
  `~/.paynux-keys` (lihat [Konfigurasi](#konfigurasi)).
- **Volume-key modifier**: tahan Volume-Up lalu tekan angka → `F1`–`F10`.
- **Laporan mouse** ke aplikasi full-screen (`htop`, `mc`, …) lewat
  `?1000`/`?1002`/`?1003`.
- **Buka tautan** dari menu seleksi.
- **Font DejaVu Sans Mono** di-bundle, jadi tidak ada masalah font fallback
  (celah baris atau kursor melompat).
- **Menu seleksi** dengan "Pilih semua".
- **Konfigurasi toolbar** dibaca ulang saat app dibuka.

---

## Cara Kerja

PRoot menerjemahkan syscall Linux untuk membuat **chroot di user space** — jadi
rootfs Ubuntu bisa jalan tanpa hak root Android. Yang PayNux tambahkan di atas
PRoot:

| Bagian | Peran |
|---|---|
| `proot` + `libproot.so` | User-space chroot. Dijalankan dengan `-0`, `--link2symlink`, seccomp dimatikan (`PROOT_NO_SECCOMP=1`). |
| `libprootloader.so` | Loader PRoot, dibungkus `PROOT_LOADER` agar aman dari W^X. |
| `libpaynuxpty.so` | Library PTY native (JNI). **Tidak ada source C-nya di repo** — file `.so` hasil *binary patch* adalah satu-satunya sumber kebenaran. |
| `libtalloc`/`libandroid-shmem` | Dependency native PRoot. |
| `xz` + `liblzma` | Runtime dekompresi untuk mengekstrak rootfs dari `.tar.xz`. |
| `TerminalEmulator.java` | Parser ANSI/VT → grid sel. |
| `MainActivity.java` | UI: terminal, toolbar, drawer sesi, lifecycle, ekstraksi rootfs. |

**Ekstraksi rootfs** memakai staging: `asset → xz -dc → tar -xf → ubuntu-stage`
lalu `ubuntu/rootfs` di-`rename` ke tempatnya. Ekstraksi `tar` bawaan Android
versi lama tidak mengenal `--strip-components`, jadi `rename` (atomik) dipakai
sebagai gantinya. Butuh ruang kosong sekitar **3× ukuran rootfs** saat
ekstraksi pertama.

**Crash native `SIGABRT`** pernah terjadi di `libpaynuxpty.so` dan sudah
ditambal (patch 4 byte di offset `0x155c`: `csinc w0,w27,wzr,gt` →
`add w0,w27,#1`). Akar masalahnya `calloc(max(count,1),8)` yang lalu menulis
terminator NULL di indeks `count` — bisa di luar alokasi. Karena source C-nya
tidak ada, `.so` yang terpasang tidak boleh dibangun ulang.

---

## Instalasi

1. Unduh APK dari halaman
   [Releases](https://github.com/pay/PayNux/releases) (saat ini **v0.4.0**,
   ±18,8 MB).
2. Pasang (sideload). Android akan meminta izin storage saat aplikasi
   pertama kali dibuka — **terima**, tanpa itu `/sdcard` tidak bisa di-bind
   dan Ubuntu akan melihat "Permission denied".
3. Buka aplikasi. Ekstraksi rootfs pertama kali butuh beberapa menit dan
   ruang kosong yang lega.
4. Setelah shell siap, coba:

   ```bash
   uname -a
   cat /etc/os-release
   ls --color
   ```

### Uninstall

Hapus aplikasi lewat pengaturan Android. Data di `files/home` (termasuk
`/root`) ikut terhapus.

---

## Build dari Sumber

### Yang dibutuhkan

| Kebutuhan | Versi |
|---|---|
| JDK | 17+ |
| Android SDK | `compileSdk 36` |
| Gradle | 9.7.1 (otomatis via wrapper) |
| Android Gradle Plugin | 9.0.0 |
| `minSdk` / `targetSdk` | 23 / 28 |

### Build APK debug

```bash
git clone https://github.com/pay/PayNux.git
cd PayNux
./gradlew assembleDebug
# hasil: app/build/outputs/apk/debug/app-debug.apk
```

> **Known issue — `gradle.properties`.**
> File `gradle.properties` saat ini berisi
> `android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2`,
> yaitu path **Termux**. Path itu tidak ada di desktop, sehingga build di
> Linux/macOS/Windows akan gagal saat AAPT2 dijalankan.
>
> **Solusi:** hapus (atau beri komentar) baris tersebut saat build di
> desktop. AAPT2 lalu diambil dari Maven Central oleh AGP dan semuanya
> berjalan normal. Baris ini bisa dipertahankan untuk workflow build di
> Termux.
>
> PR untuk memperbaikinya (membuat override bersifat kondisional) sangat
> diterima — lihat [Kontribusi](#kontribusi).

### Membangun ulang rootfs (opsional)

Rootfs bawaan sudah berisi Ubuntu 24.04 yang bersih. Kalau kamu ingin
menambah paket (atau mem-bundle Hermes Agent), tool-nya ada di
`tools/build-paynux-hermes.sh`:

```bash
# HARUS dijalankan di Termux (butuh proot, xz, tar, curl)
pkg install proot xz-utils tar curl coreutils
bash tools/build-paynux-hermes.sh --build
```

Opsi: `--rebase` (ambil ulang tarball dasar), `--shell` (masuk shell rootfs
untuk debug), `--build` (sekalian `./gradlew assembleDebug`).

> Peringatan: skrip ini menulis ke `app/src/main/assets/ubuntu-24.04-aarch64.tar.xz`.
> Ukuran rootfs terkompresi yang wajar ada di bawah **150 MB** dan APK di
> bawah **170 MB**. Jangan menanam API key/token di dalam rootfs — konfigurasi
> privat milik pengguna harus di `/root/.hermes`, yang berada di luar rootfs.

---

## Konfigurasi

### `~/.paynux-keys` — toolbar extra-keys

Letak di dalam Ubuntu: `/root/.paynux-keys` (di host: `files/home/.paynux-keys`).
Format sama dengan `extra-keys` di Termux. **Edit file-nya, lalu tutup &
buka ulang aplikasi.**

```bash
extra-keys = [[ESC,/,-,HOME,UP,END,SESS],[TAB,CTRL,ALT,LEFT,DOWN,RIGHT,KEYBOARD]]
```

Formatnya: `extra-keys = [[baris1],[baris2]]`, tiap item dipisah koma tanpa
spasi. Nilai yang dikenali:

| Nilai | Fungsi |
|---|---|
| `ESC` `TAB` `SPACE` `ENTER` `BACKSPACE` | Tombol biasa |
| `CTRL` `ALT` | Modifier (tombol berikutnya digabung) |
| `HOME` `END` `PGUP` `PGDN` `UP` `DOWN` `LEFT` `RIGHT` | Tombol navigasi |
| `SESS` | Buka panel sesi |
| `KEYBOARD` | Tampilkan keyboard |
| teks lain (≤ 3 huruf) | Dikirim apa adanya |

Hapus file-nya untuk mengembalikan default. Contoh baris yang umum:

```bash
extra-keys = [[ESC,TAB,CTRL,ALT,LEFT,DOWN,UP,RIGHT],[PGUP,PGDN,HOME,END,SPACE,ENTER,SESS,KEYBOARD]]
```

### Prompt

`/etc/paynux.sh` ditulis ulang setiap sesi — **jangan** edit file itu, edit
`~/.bashrc` milikmu sendiri. Matikan prompt berwarna:

```bash
export PAYNUX_PLAIN_PROMPT=1
```

Matikan warna sepenuhnya:

```bash
export NO_COLOR=1
```

### Rootfs & data

| Path di dalam Ubuntu | Letak di host | Keterangan |
|---|---|---|
| `/root` | `files/home` | Persisten, di luar rootfs |
| `/sdcard` | `/storage/emulated/0` | Butuh izin storage |
| `/opt/hermes` | di dalam rootfs | Ikut APK, hilang saat update |

---

## Struktur Repository

```
PayNux/
├── app/
│   ├── build.gradle              # konfigurasi modul (JANGAN disalin ke root)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── ubuntu-24.04-aarch64.tar.xz   # rootfs (17 MB)
│       │   ├── bin/proot
│       │   ├── fonts/paynux-mono.ttf         # DejaVu Sans Mono
│       │   ├── proot-runtime/                # libproot, libtalloc, libshmem
│       │   └── xz-runtime/                   # xz + liblzma
│       ├── java/com/paynux/
│       │   ├── MainActivity.java             # UI + lifecycle + ekstraksi
│       │   ├── KeepAliveService.java         # foreground service + notifikasi
│       │   ├── pty/PtyNative.java            # JNI binding
│       │   └── terminal/
│       │       ├── TerminalEmulator.java     # parser ANSI/VT
│       │       └── TerminalSession.java      # transport PTY / pipe
│       └── jniLibs/arm64-v8a/                # .so native
├── build.gradle                  # HANYA blok plugins
├── tools/
│   ├── build-paynux-hermes.sh    # rebuild rootfs + bundle Hermes
│   ├── repro_pty_spawn.c        # repro minimal untuk PTY
│   └── AGENT-PROMPT.md          # brief agen AI untuk proyek ini
└── gradle/wrapper/
```

> Catatan: **hanya** `arm64-v8a` yang di-ship. Perangkat `armeabi-v7a` dan
> `x86_64` belum didukung.

---

## Status & Roadmap

**Versi sekarang:** 0.4.0 (`versionCode 12`)

Yang sudah ada dan teruji:

- Rootfs Ubuntu 24.04 ARM64 + shell login + prompt berwarna
- Terminal emulator sendiri dengan truecolor, alt screen, scrollback
- PTY native + fallback pipe
- Multi-sesi dengan drawer, keep-alive service, wakelock manual
- Toolbar extra-keys yang bisa dikonfigurasi
- `/sdcard` ter-bind, `HOME` persisten di luar rootfs

Sedang dikerjakan / rencana:

- [ ] Indikator progres + pesan error yang jelas saat ruang penyimpanan kurang
- [ ] Dekompres streaming (`xz -dc | tar`) supaya tidak perlu copy tar sementara
- [ ] Dukungan `armeabi-v7a` / `x86_64`
- [ ] `LICENSE` resmi + terbitan (release) yang ditandatangani
- [ ] Uji di Android 12 (v0.3.7 belum pernah diuji di sana)

---

## Kontribusi

Kontribusi sangat diterima — issue, diskusi, dan pull request.

Baca **[CONTRIBUTING.md](CONTRIBUTING.md)** dulu: ada beberapa aturan keras
(terutama soal `libpaynuxpty.so` yang **tidak boleh** dibangun ulang, dan
soal konfigurasi privat yang tidak boleh di-*commit*).

Area yang paling butuh bantuan:

- **Dokumentasi** — terutama translate README ini ke bahasa Inggris.
- **Pengujian** — laporan yang detail dari perangkat Android 12/13/14,
  chipset berbeda, dan mode pesawat.
- **Emulator terminal** — `TerminalEmulator.java` sengaja dibuat pure Java
  supaya bisa diuji di desktop JVM; test unit-nya masih belum ada.
- **Build & CI** — pipeline build APK otomatis untuk `arm64-v8a`.

---

## Lisensi

> ⚠️ **Repo ini belum memiliki file `LICENSE`.** Sampai owner menetapkan satu,
> secara default semua hak cipta dilindungi. Kalau kamu mau memakai ulang
> kode di sini, buka issue dulu dan minta keputusannya.

Kontribusimu akan berlisensi di bawah lisensi yang sama dengan proyek ini —
detailnya ada di bagian [Lisensi](#lisensi).

---

## Acknowledgement

- **PRoot** — user-space chroot, GPL-2.0, project by Thomas Vincent
- **Termux** — inspiration untuk UX terminal Android
- **DejaVu Fonts** — DejaVu Sans Mono
- **Ubuntu 24.04** — image rootfs ARM64
- **[Hermes Agent](https://github.com/NousResearch/hermes-agent)** — agen AI
  yang bisa di-bundle ke dalam rootfs lewat `tools/build-paynux-hermes.sh`
