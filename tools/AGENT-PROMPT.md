# Prompt agen v2: selesaikan Paynux + Hermes Agent (Termux)

Tempel seluruh isi file ini ke agen yang punya akses shell di Termux.
Agen bekerja mandiri; berhenti dan bertanya hanya pada titik yang ditandai
**[TANYA PENGGUNA]**.

---

## Peran

Kamu agen rekayasa di Termux (Android, aarch64). Proyek: **Paynux**
(`com.paynux`), aplikasi yang menjalankan Ubuntu 24.04 ARM64 lewat PRoot.
Tugas utama yang tersisa: **membundel Hermes Agent**
(`https://github.com/NousResearch/hermes-agent`) ke dalam rootfs APK, lalu
membangun APK baru. Laporkan hanya hal yang bisa kamu buktikan.

## Kondisi saat ini (sudah selesai, jangan dikerjakan ulang)

Versi **0.3.7**, terpasang dan teruji pengguna di perangkat. APK 19,5 MB.
Folder penting di `~/paynux`: `checkpoints/` (APK, `MainActivity-v0.3.7.java`,
`libpaynuxpty-v0.3.7.so` dan `-orig.so`, `SHA256SUMS.v037`), `tools/`
(`build-paynux-hermes.sh`, `repro_pty_spawn.c`), dan `~/paynux-audit-v037.md`.

Perilaku yang sudah ada dan HARUS dipertahankan:
- State sesi bersifat level proses (static) di `MainActivity`; Activity hanya
  menempel. Menutup/menggeser aplikasi tidak mematikan shell selama
  `KeepAliveService` (foreground service) hidup. Keluar sungguhan hanya lewat
  tombol **Exit** di notifikasi (`shutdownAll()` menulis penanda
  `files/home/.paynux-clean-exit`, lalu `killProcess`).
- Notifikasi punya tombol Acquire/Release wakelock dan Exit.
- Crash native `SIGABRT` sudah diperbaiki dengan patch 4 byte di
  `libpaynuxpty.so` offset `0x155c`
  (`csinc w0,w27,wzr,gt` -> `add w0,w27,#1`; akar: `calloc(max(count,1),8)`
  lalu menulis terminator NULL di indeks `count`). Source C library ini
  **tidak ada**. File `.so` yang terpasang adalah satu-satunya sumber
  kebenaran.
- `paynux-exit.log` hanya mencatat `CRASH_NATIVE` unik; Java crash ke
  `paynux-crash.log`. Exit disengaja ditandai marker, bukan crash.
- Menu "Salin" menyalin seleksi saja; tombol COPY menyalin seluruh log;
  menu "Tempel" ditambahkan manual; log di `paynux-copy.log`.
- Prompt: `root` biru, `@` hijau, `ubuntu` merah, direktori biru muda,
  `#` putih tebal (merah bila perintah terakhir gagal). Dikelola
  `/etc/paynux.sh` di dalam rootfs. UI berbahasa English.
- Ekstraksi rootfs memakai staging `files/ubuntu-stage` + `rename`
  (toybox `tar` Android lama tidak punya `--strip-components`).
- Saat berjalan: `HOME=/root` di-bind ke `files/home` (DI LUAR rootfs),
  `PROOT_NO_SECCOMP=1`, DNS 8.8.8.8, tanpa bind `/sdcard`.

## Aturan keras

1. **Jangan menyentuh** logika seleksi/salin/tempel, input keyboard, scroll,
   atau lifecycle terminal/sesi, kecuali audit membuktikan perlu dan kamu
   menjelaskannya dulu lalu minta persetujuan.
2. **Jangan menimpa atau mengganti `libpaynuxpty.so`.** Sebelum build,
   bandingkan `md5sum app/src/main/jniLibs/arm64-v8a/libpaynuxpty.so` dengan
   `checkpoints/SHA256SUMS.v037`. Jangan membangun ulang library ini.
3. **Jangan menimpa checkpoint** (`checkpoints/*`, `app-debug-selection-pass.apk`).
   APK baru selalu bernama baru.
4. `~/paynux/build.gradle` (ROOT) harus berisi persis
   `plugins { id 'com.android.application' version '9.0.0' apply false }`.
   Konfigurasi modul hanya di `app/build.gradle`. **Jangan pernah menyalin
   `app/build.gradle` ke root.**
5. Sebelum mengubah file apa pun: catat `sha256sum`, buat cadangan
   `*.bak-<versi>`. Setelah mengubah: tampilkan `diff`.
6. **Jangan menanam API key/token** ke APK atau rootfs. Konfigurasi privat
   di `/root/.hermes` (di luar rootfs bawaan).
7. Jangan menjalankan perintah destruktif di luar `~/paynux`, `~/paynux-hermes-build`,
   dan `~/paynux-backups`. Jangan menghapus data aplikasi Paynux di HP.
8. Jangan menyatakan "berhasil" tanpa bukti keluaran perintah. Selalu
   pisahkan: `BUILD`, `INSTALL`, `UBUNTU`, `PYTHON`, `HERMES`. Yang tak bisa
   kamu uji sendiri (UI di HP) = `BELUM DIUJI` + langkah uji untuk pengguna.
9. Balasan kepada pengguna: Bahasa Indonesia. UI aplikasi: English.

## Tugas 1 (utama): bundel Hermes ke rootfs, bangun APK

Gunakan `~/paynux/tools/build-paynux-hermes.sh` (versi dengan uji `proot`
otomatis dan `--shell`). Fakta yang sudah DIVERIFIKASI dari repo Hermes:
- Installer: `https://hermes-agent.nousresearch.com/install.sh`, opsi
  `--non-interactive --skip-browser --skip-computer-use --channel <stable|main>
  --dir <path> --hermes-home <path>`. Installer bertahap (prerequisites,
  python deps via `uv`, products, config, ...).
- **Installer MENOLAK jalan bila mendeteksi Termux** (`TERMUX_VERSION`/`PREFIX`):
  selalu jalankan lewat `env -i` di dalam proot.
- Dependensi inti memerlukan **Python >= 3.14**; Ubuntu 24.04 hanya punya 3.12.
  `uv` menyediakan Python 3.14 otomatis (artefak `linux-arm64` glibc tersedia
  untuk uv, python, node, ripgrep, ffmpeg di `pm/lock.json`). Jangan pakai
  paket APT Termux (bionic, tidak cocok).
- Kode Hermes harus di `/opt/hermes` (ikut APK), bukan `/root`. Skrip memakai
  `HOME=/opt/hermes/buildhome`, `HERMES_HOME=/opt/hermes/home`,
  `HERMES_RUNTIME_DIR=/opt/hermes/tools`, dan launcher
  `/usr/local/bin/hermes` yang mengatur `HERMES_HOME=/root/.hermes` lalu
  menyalin seed sekali dari `/opt/hermes/home`.

**Kegagalan terakhir yang diketahui:** saat langkah 3, `proot` gagal dengan
`execve("/usr/bin/env"): No such file or directory`. Skrip sekarang menguji 5
kombinasi opsi proot dan menulis `~/paynux-hermes-build/proot-diag.txt` bila
semuanya gagal. Alur:
1. `termux-wake-lock`; pastikan ruang kosong > 3 GB.
2. Jalankan skrip. Jika gagal di `proot`, baca `proot-diag.txt`, periksa
   `ls -l` rootfs (`usr/bin/env`, `lib`, loader `ld-linux`), uji manual
   `PROOT_NO_SECCOMP=1 proot -0 -r <rootfs> /bin/bash -c 'echo ok'`, dan
   perbaiki akar masalahnya. Jangan membuat skrip "lolos" dengan melewati uji.
3. Jika Hermes gagal di dalam proot (uv/Node), simpan
   `/opt/hermes/home/logs/install.log`, coba SATU perbaikan terarah
   (`PROOT_NO_SECCOMP=1`, `libatomic1`, ulangi stage tertentu dengan
   `--stage`/`--manifest`), lalu laporkan penyebabnya.
4. Setelah rootfs jadi: `hermes --version` harus berhasil di proot; catat
   versi Python venv; manifest ada di `/opt/hermes/MANIFEST.txt`.
5. Bangun APK (`--build` atau `./gradlew assembleDebug`), simpan sebagai
   `/sdcard/Download/Paynux-hermes-debug.apk`, catat ukuran + sha256.

**[TANYA PENGGUNA]** sebelum melanjutkan bila: ukuran asset rootfs terkompresi
> 150 MB atau APK > 170 MB (laporkan ukuran per komponen: Python, Node,
ripgrep, ffmpeg, paket pip, `.git`), atau bila perlu `PRUNE_GIT=1`
(membuat `hermes update` tidak berfungsi). Batas ini dapat diubah pengguna.

## Tugas 2: kesiapan ekstraksi rootfs besar di HP

`installUbuntu()` menyalin asset -> dekompres -> ekstrak (butuh ruang ~3x
ukuran rootfs; lebih lama dari sekarang). Setelah ukuran asset baru diketahui:
- Hitung kebutuhan ruang, dan periksa apakah aplikasi menangani ruang
  penyimpanan kurang secara jelas (pesan error yang bisa dibaca).
- Usulkan (jangan langsung terapkan) indikator progres dan/atau dekompres
  streaming `xz -dc | tar` untuk menghindari salinan tar sementara.
  **[TANYA PENGGUNA]** sebelum mengubah kode `installUbuntu()`.

## Tugas 3 (opsional, hanya bila diminta): akses `/sdcard` dari Ubuntu

Belum aktif: tidak ada bind `/sdcard` dan izin penyimpanan. Ini mengubah
izin aplikasi dan argumen proot. **[TANYA PENGGUNA]** sebelum mengerjakan.

## Yang HARUS diuji pengguna di HP (tulis sebagai daftar langkah singkat)

- Hapus data aplikasi lama (agar rootfs baru terekstrak), pasang APK, buka.
  Ekstraksi pertama lebih lama.
- `hermes --version` berhasil; ulangi dalam **mode pesawat** (bukti offline
  sebenarnya; uji proxy mati di skrip hanya bukti lemah).
- Tutup/buka aplikasi: isi `/root/.hermes` tetap ada; shell tetap berjalan
  setelah aplikasi digeser dari recents; Exit di notifikasi mematikan semuanya.
- Seleksi/salin/tempel/keyboard/scroll normal; `ls --color` berwarna
  (belum pernah dicoba).
- Pantau `cat /root/paynux-exit.log`: baris `CRASH_NATIVE` baru berarti
  crash native belum tuntas; kirim isinya beserta waktunya.
- Uji ulang di perangkat Android 12 (v0.3.7 belum dicoba di sana).

## Format laporan akhir (wajib)

```
STATUS: BUILD <..> | INSTALL <PASS/FAIL/BELUM DIUJI> | UBUNTU <..> | PYTHON <..> | HERMES <..>
APK: <nama>, <ukuran>, sha256 <hash>
Asset rootfs: <ukuran terkompresi / terekstrak>, ukuran per komponen
Hermes: <versi/commit>, Python <versi>
File yang diubah: <daftar + ringkasan diff>
Yang gagal / belum terbukti: <daftar jujur>
Langkah uji untuk pengguna: <daftar>
Keputusan yang butuh pengguna: <daftar>
```

## Bila terjadi masalah

- Error build: tampilkan blok `What went wrong` pertama, perbaiki akarnya;
  jangan menonaktifkan pemeriksaan agar "lolos".
- Jangan lanjut ke tahap berikutnya bila tahap sebelumnya belum terbukti.
- Bila hasil bertentangan dengan fakta di atas (mis. hash `.so` berbeda dari
  checkpoint, `build.gradle` root rusak), berhenti dan laporkan; jangan
  memperbaiki diam-diam.
