# Kontribusi ke PayNux

Terima kasih sudah mau membantu. Repo ini kecil dan dikerjakan sendiri, jadi
beberapa aturan di bawah bukan sekadar formalitas — kalau dilanggar, hasil
build bisa rusak secara halus dan sulit dilacak.

> **Bahasa:** issue, pull request, dan balas-balasan dalam PR sebaiknya
> Bahasa Indonesia. Dokumentasi di dalam kode (komentar, pesan log) juga
> Bahasa Indonesia. **UI aplikasi tetap English** — jangan diubah ke
> Bahasa Indonesia.

---

## Isi

- [Alur Kerja](#alur-kerja)
- [Aturan Keras](#aturan-keras)
- [Apa yang Paling Dibutuhkan](#apa-yang-paling-dibutuhkan)
- [Gaya Kode](#gaya-kode)
- [Commit & Pull Request](#commit--pull-request)
- [Cara Melaporkan Bug](#cara-melaporkan-bug)
- [Build & Uji](#build--uji)
- [Tentang Proyek](#tentang-proyek)

---

## Alur Kerja

1. **Cari issue dulu** (atau buat baru) sebelum nulis kode. Kalau belum ada
   issue, buat issue-nya dengan deskripsi singkat — ini supaya tidak ada dua
   orang ngerjain hal yang sama.
2. **Fork** repo ini, lalu buat branch bercabang dari `main`:
   ```bash
   git clone https://github.com/<kamu>/PayNux.git
   cd PayNux
   git remote add upstream https://github.com/pay/PayNux.git
   git checkout -b feat/nama-kamu-atau-fix/nama-kamu
   ```
3. **Kerjakan** per aturan keras di bawah.
4. **Uji** — minimal `./gradlew assembleDebug` lolos. Kalau menyangkut terminal
   atau shell, uji juga di perangkat nyata (lihat [Build & Uji](#build--uji)).
5. **Commit & push** ke fork kamu.
6. **Buka Pull Request** ke `pay/PayNux`, dan jelaskan *apa* yang berubah dan
   *kenapa*.

---

## Aturan Keras

### 1. `libpaynuxpty.so` tidak boleh dibangun ulang

Library PTY native ini **tidak punya source C-nya** di repo. File `.so` yang
terpasang adalah satu-satunya sumber kebenaran, dan sudah berisi **binary
patch** untuk menfix crash `SIGABRT` (4 byte di offset `0x155c`:
`csinc w0,w27,wzr,gt` → `add w0,w27,#1`).

> **Kalau `.so` dibangun ulang, patch itu hilang** dan crash `SIGABRT` akan
> kembali. Build ulang `.so` = bug fatal.

Sebelum build, selalu bandingkan hash-nya dengan checkpoint:

```bash
md5sum app/src/main/jniLibs/arm64-v8a/libpaynuxpty.so
```

Kalau hash-nya berubah, **berhenti** dan laporkan. Jangan diam-diam memperbaiki.

### 2. `build.gradle` root hanya berisi blok `plugins`

File `build.gradle` di root **harus persis**:

```groovy
plugins {
    id 'com.android.application' version '9.0.0' apply false
}
```

Konfigurasi modul hanya di `app/build.gradle`.

> **Jangan pernah menyalin `app/build.gradle` ke root.** Kesalahan ini
> pernah terjadi dan merusak build.

### 3. Jangan sentuh area ini tanpa izin

Logika berikut harus tetap perilakunya:

- seleksi / salin / tempel
- input keyboard (termasuk toolbar extra-keys)
- scroll & scrollback
- lifecycle terminal dan sesi

Kalau audit membuktikan ada yang benar-benar perlu diubah, **jelaskan dulu
di issue**, lalu tunggu persetujuan. Jangan langsung menambal.

### 4. Jangan pernah commit konfigurasi privat

- Tidak ada **API key / token** di APK maupun rootfs.
- Konfigurasi privat Hermes milik pengguna ada di `/root/.hermes` — folder itu
  **di luar rootfs** (berkas `files/home`), jadi tidak ikut ter-*commit*.
- Kalau kamu butuh konfigurasi contoh, buat file `.example` yang isinya
  placeholder.

### 5. Jangan commit checkpoint & artefak lokal

Yang **tidak boleh** masuk repo:

- `checkpoints/` (APK & `.so` versi lama)
- `*.bak`, `*.orig`, `*.diff`, `*.patch`, `*.log`
- `scratch/`, `crash-evidence/`
- file sementara apa pun yang isinya bukti debug pribadi

`.gitignore` sudah Mostly menutup ini — tapi tetap periksa `git status` sebelum
commit.

### 6. Rootfs: incremental, bukan rebuild dari nol

Membangun ulang `ubuntu-24.04-aarch64.tar.xz` dari nol itu berat (butuh >3 GB
ruang kosong dan PRoot di Termux). Kalau memang perlu mengubah rootfs:

- Pakai `tools/build-paynux-hermes.sh` — skripnya sudah menjaga tarball dasar
  tetap murni di `~/paynux-hermes-build/base`.
- Rootfs hasil build **ikut ter-commit** (17 MB). Karena itu, perubahan
  rootfs sebaiknya jadi PR terpisah dari perubahan kode Java — biar review
  enak dan diff tidak bercampur.

### 7. Batasan ukuran

| Batas | Nilai |
|---|---|
| Rootfs terkompresi | < 150 MB |
| APK | < 170 MB |

Kalau lewat batas, berhenti dan tanyakan ke maintainer dulu.

### 8. Jangan(datang) destruktif di luar folder yang diizinkan

Perintah destruktif (`rm -rf`, `reset`, dll.) hanya boleh di:

- `~/paynux`
- `~/paynux-hermes-build`
- `~/paynux-backups`

Jangan menghapus data aplikasi PayNux di HP orang lain.

### 9. Klaim "sukses" harus ada buktinya

Jangan menyatakan berhasil tanpa output perintah yang jelas. Kalau kamu tidak
bisa mengujinya sendiri (misalnya UI di HP), tulis **`BELUM DIUJI`** plus
langkah uji untuk pengguna. Laporan akhir wajib memisahkan:

```
BUILD | INSTALL | UBUNTU | PYTHON | HERMES
```

### 10. Bahasa

| Artefak | Bahasa |
|---|---|
| Issue & PR | Bahasa Indonesia |
| Komentar kode, pesan log | Bahasa Indonesia |
| README, CONTRIBUTING | Bahasa Indonesia |
| **UI aplikasi** | **English** |

---

## Apa yang Paling Dibutuhkan

Prioritasnya, dari yang paling berguna:

### 1. Test unit untuk `TerminalEmulator`

`app/src/main/java/com/paynux/terminal/TerminalEmulator.java` **sengaja**
ditulis tanpa import Android, supaya bisa diuji di desktop JVM. Test-nya belum
ada sama sekali — ini kekosongan yang paling jelas.

Butuh bikin `app/src/test/java/...` dengan JUnit dan menambahkan dependensi
test ke `app/build.gradle`. Cakupannya:

- parsing SGR (16/256/truecolor, bold/dim/italic/underline/reverse)
- cursor movement (CUU/CUD/CUF/CUB/CUP/CHA/VPA)
- IL/DL/DCH/ICH/ECH
- scroll region `DECSTBM` + `DECSC`/`DECRC`
- alt screen `?1049`
- bracketed paste `?2004`
- autowrap `?7`, cursor visibility `?25`
- OSC title, DCS/APC/PM string
- UTF-8 + karakter lebar (CJK) — lebar karakter di-*approximate*, jadi test-nya
  perlu realistis soal itu

### 2. Pipeline CI

Build APK otomatis untuk `arm64-v8a` setiap push/PR. Worker CI-nya belum ada
sekarang, jadi kamu tinggal bikin `.github/workflows/`-nya.

> ⚠️ **Perhatikan `gradle.properties`.** File itu sekarang berisi
> `android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2`
> — path Termux yang **tidak ada** di runner CI. Build akan gagal di AAPT2
> sampai baris itu dihapus atau dibuat bersyarat. PR yang menyelesaikan ini
> sangat diterima.

### 3. Dukungan `armeabi-v7a` / `x86_64`

Sekarang hanya `arm64-v8a` yang di-ship. Butuh binary PRoot + PTY untuk
ABI lain.

### 4. Error handling saat ekstraksi rootfs

Ekstraksi pertama butuh ruang kosong ~3× ukuran rootfs, tapi saat ini
kegagalan ruang disk kemungkinan muncul sebagai pesan yang membingungkan.
Tambahkan:

- cek ruang kosong sebelum ekstraksi, dengan pesan yang jelas
- indikator progres (prosesnya memang lama; user butuh feedback)

> Ini menyentuh `installUbuntu()` — **buka issue dulu** sebelum mengubahnya.

### 5. Dekompres streaming

Sekarang: `xz -dc asset → file tar sementara → tar -xf`. Streaming
(`xz -dc | tar -x`) menghindari copy 17 MB dan hemat ruang.

> Ini juga menyentuh `installUbuntu()` — **buka issue dulu**.

### 6. Dokumentasi

- Translate README ke English (issue terpisah, jangan dicampur dengan kode).
- Dokumentasi tiap perintah bawaan (`portcheck`, `killport`).
- Penjelasan lengkap `~/.paynux-keys` dengan screenshot.

### 7. Laporan pengujian di perangkat nyata

PayNux sangat bergantung pada perilaku spesifik perangkat. Yang dibutuhkan:

- Android 12, 13, 14 — hasil uji
- chipset diverse (Qualcomm, MediaTek, Exynos)
- mode pesawat (offline) — pastikan `apt`/`pip` gagal dengan pesan jelas
- perilaku `/sdcard` dengan izin storage dicabut
- baterai: berapa lama sesi bertahan dengan wakelock aktif

---

## Gaya Kode

Tidak ada style guide formal, jadi **ikut kode yang ada**:

- **Indentasi 4 spasi**, bukan tab.
- Bahasa Indonesia untuk komentar dan pesan log; **English untuk identifier**
  (`namaVariabel`, `namaMethod`).
- Format yang dipakai sekarang untuk yang panjang:
  ```java
  command.add(
          "-0"
  );
  ```
 Boleh satu baris untuk yang pendek — jangan memaksakan format ini ke
  seluruh file.
- Prefer **`final`**, dan field non-`final` hanya kalau memang perlu.
- Android API: hati-hati dengan `targetSdk 28` (legacy storage) — fitur storage modern
  (`MANAGE_EXTERNAL_STORAGE`) tidak tersedia. Jangan menaikkan `targetSdk`
  tanpa Consekuensi yang dipikirkan.
- Jangan menambah dependensi eksternal tanpa alasan kuat — APK sudah 19 MB.

---

## Commit & Pull Request

### Format commit

Bahasa Indonesia, gaya `conventional commits` yang longgar:

```
<type>(<scope>): <deskripsi singkat>

<body: kenapa, bukan cuma apa>
```

`type` yang dipakai di repo ini: `feat`, `fix`, `chore`, `refactor`, `docs`.

Contoh yang sudah ada:

```
feat(ui): drawer sesi geser dari tepi kiri (ala Termux)
fix(term): dukung truecolor 38:2:r:g:b tanpa field colorspace
chore(repo): bersihkan file mati & kunci artefak lokal di .gitignore
```

### Judul PR

Singkat dan jelas. Contoh:

```
docs: README lengkap + panduan kontribusi
```

### Isi PR

1. **Ringkasan** — apa yang berubah.
2. **Mengapa** — masalah apa yang diselesaikan.
3. **Cara menguji** — langkah yang bisa diulang maintainer.
4. **Status pengujian** — `BELUM DIUJI` kalau memang belum diuji di perangkat.
5. **Screenshot** — kalau ada perubahan UI.

Satu PR = satu topik. Jangan gabungkan perbaikan terminal + rewrite README
+ bump versi dalam satu PR.

---

## Cara Melaporkan Bug

Beri info sebanyak mungkin:

- **Perangkat**: merk, model, SoC, Android version
- **Versi PayNux**: dari About / notification
- **Build**: debug atau release
- **Langkah reproduksi** — numbered, jelas
- **Hasil yang diharapkan** vs **hasil yang sebenarnya**
- **Log**: `paynux-crash.log` (crash Java) dan `paynux-exit.log` (exit/crash
  native). Isinya sensitif — cek dulu tidak ada token/API key sebelum
  menempelkannya.
- **Screenshot / video**

Kalau involves terminal, tambahkan output dari:

```bash
uname -a
cat /etc/os-release
echo "$LD_LIBRARY_PATH"
```

---

## Build & Uji

### Build di desktop

```bash
./gradlew assembleDebug
```

> **Penting:** hapus/comment baris `android.aapt2FromMavenOverride=...` di
> `gradle.properties` dulu — path-nya menunjuk Termux dan tidak ada di desktop.

### Build di Termux (aarch64)

```bash
pkg install gradle aapt2
./gradlew assembleDebug
```

### Uji di perangkat

Yang **tidak bisa** diuji di desktop dan wajib dicoba di HP:

- prompt, echo, `Ctrl+C`, warna (`ls --color`)
- scrollback & seleksi & salin/tempel
- `/sdcard` terbaca & bisa ditulis
- multi-sesi & drawer
- swipe dari recents → shell tetap hidup
- tombol **Exit** di notifikasi → semua mati
- mode pesawat

Laporkan hasilnya di PR sebagai tabel:

| Aspek | Status | Catatan |
|---|---|---|
| Boot rootfs | ✅ / ❌ | |
| Prompt + warna | ✅ / ❌ | |
| `/sdcard` | ✅ / ❌ | |
| Keep-alive | ✅ / ❌ | |
| Multi-sesi | ✅ / ❌ | |

---

## Tentang Proyek

PayNux dimulai sebagai percobaan pribadi untuk menjalankan lingkungan Linux
sepenuhnya di Android tanpa root, dan berkembang menjadi terminal emulator
dengan mesin parser sendiri. Sebagian besar decision log-nya — termasuk
keputusan untuk *tidak* memakai Termux — ada di `tools/AGENT-PROMPT.md`, dan
memang ditulis sebagai *brief* untuk agen AI yang ikut mengerjakan proyek ini.

Project ini **bukan** produk resmi dari PT Pay atau afiliasi mana pun. Nama
"PayNux" merujuk pada PT Pay (Toll Indonesia) sebagai *(pay)* + *Linux*,
seperti aplikasi *Termux*.

Kalau kamu punya ide besar — misalnya mengganti PRoot dengan UserLAnd,
mengganti emulator dengan `libvterm`, atau menambah dukungan iOS — buka issue
dulu. Diskusikan arsitekturnya sebelum menulis code.
