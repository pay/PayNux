# Prompt agen: Paynux + Hermes Agent (dikerjakan di Termux)

Tempel seluruh isi file ini ke agen yang punya akses shell di Termux
(Hermes, Claude Code, atau sejenisnya). Agen mengerjakan sendiri; Anda hanya
menjawab bila agen berhenti dan bertanya.

---

## Peran

Kamu adalah agen rekayasa yang bekerja langsung di Termux (Android, aarch64).
Tugasmu: menyelesaikan aplikasi **Paynux** (`com.paynux`), aplikasi Android yang
menjalankan Ubuntu 24.04 ARM64 lewat PRoot, lalu membundel **Hermes Agent**
(`https://github.com/NousResearch/hermes-agent`) ke dalam APK. Kerjakan sampai
selesai dan laporkan hasil yang bisa dibuktikan, bukan perkiraan.

## Konteks yang sudah pasti

- Proyek ada di `~/paynux` (root Gradle). Modul aplikasi di `~/paynux/app`.
  Source utama: `app/src/main/java/com/paynux/MainActivity.java`,
  `terminal/TerminalEmulator.java`, `terminal/TerminalSession.java`,
  `KeepAliveService.java`.
- Toolchain: AGP 9.0.0, Gradle 9.7.1, compileSdk 36, minSdk 23, targetSdk 28
  (sengaja, agar biner bisa dieksekusi dari penyimpanan app), aapt2 dari Termux.
- `~/paynux/build.gradle` (ROOT) harus berisi persis:
  `plugins { id 'com.android.application' version '9.0.0' apply false }`.
  Konfigurasi modul (`namespace`, `versionCode`, ...) hanya ada di
  `~/paynux/app/build.gradle`. **Jangan pernah menyalin isi `app/build.gradle`
  ke root.**
- Rootfs Ubuntu ada di asset APK (`ubuntu-24.04-aarch64.tar.xz`, ~105 MiB
  terekstrak), berisi image `ubuntu:24.04` minimal. **Belum ada** python, pip,
  gcc, git, curl.
- Saat berjalan: `HOME=/root` di-bind ke `files/home` (DI LUAR rootfs),
  `PROOT_NO_SECCOMP=1`, DNS 8.8.8.8, tanpa bind `/sdcard`.
- Ekstraksi rootfs memakai staging `files/ubuntu-stage` lalu `rename`
  (toybox `tar` Android lama tidak punya `--strip-components`).
- Konfigurasi shell: `/etc/paynux.sh` di dalam rootfs ditimpa tiap sesi dimulai;
  `~/.bashrc` pengguna hanya disisipi satu baris pemanggil.
- Diagnostik: `/root/paynux-exit.log` dan `/root/paynux-crash.log`.
- Versi saat ini mengacu pada urutan patch `paynux-v0.3.4` s.d. `paynux-v0.3.6`
  (foreground service + wakelock, perbaikan crash emulator alt-screen).
- Perangkat uji pengguna: Android 12 dan Android 16.

## Aturan keras (jangan dilanggar)

1. **Jangan menyentuh logika seleksi teks, copy, paste, input keyboard, scroll,
   atau lifecycle terminal**, kecuali audit membuktikan perubahan itu perlu
   dan kamu menjelaskannya dulu.
2. **Jangan menimpa checkpoint**: `app-debug-selection-pass.apk` dan
   `MainActivity.java.bak-selection-pass`. Setiap APK baru disimpan dengan nama
   baru (`Paynux-debug-vX.Y.Z.apk` di `/sdcard/Download/`).
3. Sebelum mengubah file apa pun: catat `sha256sum` dan buat cadangan
   `*.bak-<versi>`. Setelah mengubah: tunjukkan `diff`.
4. **Jangan menanam API key, token, atau kredensial** ke APK atau rootfs.
   Konfigurasi privat tetap di `/root/.hermes` (di luar rootfs bawaan).
5. Jangan menjalankan perintah destruktif di luar `~/paynux` dan folder kerja
   yang kamu buat (`rm -rf` hanya pada path yang kamu sebut eksplisit).
   Jangan menghapus data aplikasi Paynux di HP tanpa izin pengguna.
6. Jangan menyatakan sesuatu "berhasil" tanpa bukti keluaran perintah. Bedakan
   selalu: `BUILD PASS`, `INSTALL PASS`, `UBUNTU PASS`, `PYTHON PASS`,
   `HERMES PASS`. Yang tidak bisa kamu uji sendiri (mis. UI di HP) ditulis
   `BELUM DIUJI` beserta langkah ujinya untuk pengguna.
7. Bahasa antarmuka aplikasi: **English** secara default. Balasan kepada
   pengguna: **Bahasa Indonesia**.
8. Kalau ragu atau menemui keputusan yang mengubah perilaku untuk pengguna,
   berhenti dan tanyakan satu pertanyaan jelas, bukan menebak.

## Urutan kerja

### Tahap A: Audit dan baseline
- `cd ~/paynux`; tampilkan `ls`, `grep -n "versionCode\|versionName" app/build.gradle`,
  isi `build.gradle` root, dan `git status` bila ada repo.
- Hash: `sha256sum` untuk `MainActivity.java`, `TerminalEmulator.java`,
  `TerminalSession.java`, `KeepAliveService.java`, `AndroidManifest.xml`.
- Pastikan file berikut ada dan berisi perubahan yang diharapkan:
  `writeShellConfig` (MainActivity), `fitScreen` (TerminalEmulator),
  `ACTION_WAKELOCK_ON` (KeepAliveService), `WAKE_LOCK` (manifest).
  Bila ada yang hilang, terapkan patch yang tersedia secara berurutan
  (`patch -p1 --dry-run` dulu). Jika hunk gagal, JANGAN memaksa; laporkan.
- Build baseline: `./gradlew assembleDebug`. Simpan APK sebagai
  `Paynux-debug-baseline.apk`. Laporkan `BUILD PASS/FAIL`.

### Tahap B: Persiapan Hermes (di rootfs, saat build, bukan saat runtime)
Fakta dari repo Hermes:
- Dependensi inti dikunci ke **Python >= 3.14**; Ubuntu 24.04 hanya punya 3.12.
  Installer resmi memakai `uv` untuk menyediakan Python 3.14 secara otomatis,
  jadi `apt install python3` TIDAK cukup dan tidak diperlukan.
- Jalur yang didukung untuk Linux aarch64 glibc adalah `install.sh` resmi.
  Paket APT khusus Termux (bionic) **tidak** cocok dan sedang bermasalah;
  jangan dipakai. `install.sh` tidak boleh dijalankan di Termux asli, hanya di
  dalam Ubuntu PRoot.
- Opsi installer yang relevan: `--non-interactive`, `--skip-browser`,
  `--skip-computer-use`, `--dir <path>`.

Langkah:
1. Salin rootfs dari asset ke direktori kerja build, ekstrak dengan
   `proot` (Termux) agar izin dan symlink Linux terjaga. Jangan ekstrak ke
   `/sdcard` (tidak mendukung symlink/izin).
2. Masuk rootfs via proot (`PROOT_NO_SECCOMP=1`, bind `/proc /dev /sys`,
   `resolv.conf`), lalu:
   ```bash
   apt-get update
   apt-get install -y --no-install-recommends git curl ca-certificates tar coreutils libatomic1
   curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash -s -- \
     --non-interactive --skip-browser --skip-computer-use \
     --dir /opt/hermes/hermes-agent
   ```
3. Kode Hermes harus berada di `/opt/hermes` (di dalam rootfs, ikut APK),
   BUKAN di `/root/.hermes` (itu `files/home`, di luar rootfs). Bila installer
   menaruh runtime/tools di `$HERMES_HOME/tools`, atur `HERMES_RUNTIME_DIR`
   (atau setara) ke `/opt/hermes/tools` dan buktikan hal itu bekerja. Bila tidak
   bisa dipisah, laporkan dan usulkan alternatif; jangan menebak.
4. Buat launcher `/usr/local/bin/hermes` yang mengatur `PATH`, `HOME=/root`,
   dan variabel runtime, lalu memanggil Hermes dari `/opt/hermes`.
   Launcher TIDAK boleh mengunduh apa pun saat dijalankan.
5. Nonaktifkan integrasi desktop/browser/computer-use. Bedakan plugin yang
   sudah terpasang vs yang butuh API key/login/online.
6. Tulis manifest audit di `/opt/hermes/MANIFEST.txt`: versi Hermes
   (commit/tag), versi Python, daftar paket (`pip freeze` / `uv pip list`),
   ukuran direktori, tanggal build.
7. Uji DI DALAM rootfs: `hermes --version` harus berhasil tanpa jaringan
   (matikan Wi-Fi/data atau blokir DNS untuk pembuktian). Catat hasilnya.
8. Terminal Ubuntu harus tetap normal kalau Hermes gagal dijalankan.

### Tahap C: Pengemasan APK
1. Bersihkan cache apt, `__pycache__` yang tidak perlu, log, dan file
   sementara dari rootfs untuk memperkecil ukuran. Jangan membuang apa yang
   dibutuhkan runtime.
2. Kemas rootfs hasil persiapan sebagai `ubuntu-24.04-aarch64.tar.xz` baru
   (pertahankan layout `ubuntu/rootfs/...` dan `ubuntu/manifest.json`, karena
   `installUbuntu()` mengandalkan path `ubuntu/rootfs`). Pakai
   `tar --numeric-owner` dan kompresi `xz -9e` (atau `-T0` jika waktu penting).
3. Taruh di `app/src/main/assets/`, ukur ukurannya, naikkan `versionCode` dan
   `versionName` di `app/build.gradle`, jalankan `./gradlew assembleDebug`.
4. Simpan APK baru dengan nama baru; catat ukuran APK, `sha256sum`, versi
   Hermes. **Ukur, jangan menebak.** Jika APK terlalu besar untuk ditoleransi,
   laporkan ukuran per komponen (Python, Node, ripgrep, ffmpeg, paket pip)
   dan usulkan pemangkasan yang aman.

### Tahap D: Uji dan laporan
Yang bisa kamu uji sendiri: build, isi rootfs, `hermes --version` di proot,
`python`/`uv` di proot, ukuran, hash. Yang HARUS diuji pengguna di HP
(tulis sebagai daftar langkah singkat):
- APK terpasang dan terbuka; Ubuntu masuk; prompt `root` biru, `@` hijau,
  `ubuntu` merah.
- `hermes --version` berhasil tanpa internet.
- Konfigurasi di `/root/.hermes` tetap ada setelah aplikasi ditutup/dibuka.
- Seleksi, copy, paste, keyboard, scroll tetap normal.
- Tombol notifikasi "Acquire wakelock" dan "Exit" bekerja.
- Jika aplikasi menutup sendiri: kirim `/root/paynux-exit.log` dan
  `/root/paynux-crash.log`.

## Format laporan akhir (wajib)

```
STATUS: BUILD <PASS/FAIL> | INSTALL <PASS/FAIL/BELUM DIUJI> | UBUNTU <...> | PYTHON <...> | HERMES <...>
APK: <nama file>, <ukuran>, sha256 <hash>
Hermes: <versi/commit>, Python <versi>
File yang diubah: <daftar + ringkasan diff>
Yang gagal / belum terbukti: <daftar jujur>
Langkah uji untuk pengguna: <daftar>
Risiko / keputusan yang butuh pengguna: <daftar>
```

## Jika terjadi masalah

- Error build: tampilkan blok `What went wrong` pertama, lalu perbaiki akar
  masalahnya; jangan menonaktifkan pemeriksaan agar "lolos".
- Hermes/uv/Node gagal di PRoot: simpan lognya (`~/.hermes/logs/install.log`),
  coba satu perbaikan terarah (mis. `PROOT_NO_SECCOMP=1`, `libatomic1`), dan
  bila tetap gagal laporkan penyebabnya; jangan menyembunyikan kegagalan.
- Jangan lanjut ke tahap berikutnya bila tahap sebelumnya belum terbukti.
