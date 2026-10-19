#!/data/data/com.termux/files/usr/bin/bash
# build-paynux-hermes.sh
#
# Menyiapkan Hermes Agent DI DALAM rootfs Ubuntu milik Paynux (saat build,
# lewat proot di Termux), lalu mengemasnya kembali sebagai asset APK.
#
#   bash build-paynux-hermes.sh              # siapkan rootfs + kemas asset
#   bash build-paynux-hermes.sh --build      # ... lalu ./gradlew assembleDebug
#   bash build-paynux-hermes.sh --rebase     # ambil ulang tarball dasar dari asset
#   bash build-paynux-hermes.sh --shell      # masuk shell di rootfs kerja (debug)
#
# Variabel (opsional):
#   PROJ=~/paynux   WORK=~/paynux-hermes-build   CHANNEL=stable|main
#   XZ_LEVEL=6      PRUNE_GIT=0|1   PY_OFFLINE_TEST=1
#
# Aturan yang dipegang skrip ini:
#   - Tarball dasar (Ubuntu murni) disalin SEKALI ke $WORK/base dan tidak
#     pernah diubah; setiap build dimulai dari sana (reprodusibel).
#   - Cadangan asset asli ada di luar folder assets (agar tidak ikut APK).
#   - Kode Hermes ke /opt/hermes (di dalam rootfs, ikut APK). /root adalah
#     files/home di perangkat (di LUAR rootfs), jadi TIDAK dipakai untuk kode.
#   - Tidak ada API key / token yang ditanam.

set -euo pipefail

# Termux mengekspor LD_PRELOAD=libtermux-exec-ld-preload.so. Bila variabel ini
# ikut ke dalam proot, loader guest mencari berkas host itu di dalam rootfs dan
# exec gagal: proot error: execve("/usr/bin/env"): No such file or directory.
# Bukti: dengan LD_PRELOAD terpasang rc=1, dengan 'unset LD_PRELOAD' rc=0.
unset LD_PRELOAD

PROJ="${PROJ:-$HOME/paynux}"
WORK="${WORK:-$HOME/paynux-hermes-build}"
CHANNEL="${CHANNEL:-stable}"
XZ_LEVEL="${XZ_LEVEL:-6}"
PRUNE_GIT="${PRUNE_GIT:-0}"
PY_OFFLINE_TEST="${PY_OFFLINE_TEST:-1}"

ASSET_NAME="ubuntu-24.04-aarch64.tar.xz"
ASSET="$PROJ/app/src/main/assets/$ASSET_NAME"
BACKUP_DIR="$HOME/paynux-backups"
BASE_DIR="$WORK/base"
BASE_TAR="$BASE_DIR/$ASSET_NAME"
ROOT="$WORK/ubuntu/rootfs"
OUT_DIR="$WORK/out"
LOG="$WORK/build.log"

DO_BUILD=0; DO_REBASE=0; DO_SHELL=0
for a in "$@"; do
  case "$a" in
    --build)  DO_BUILD=1 ;;
    --rebase) DO_REBASE=1 ;;
    --shell)  DO_SHELL=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "opsi tidak dikenal: $a" >&2; exit 2 ;;
  esac
done

say()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[!] %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[1;31m[x] %s\033[0m\n' "$*" >&2; exit 1; }

mkdir -p "$WORK" "$BASE_DIR" "$OUT_DIR" "$WORK/tmp" "$BACKUP_DIR"
[ "$DO_SHELL" = 1 ] || exec > >(tee -a "$LOG") 2>&1

# ---------------------------------------------------------------- 0. cek
say "0/8 Pemeriksaan awal"
[ -n "${PREFIX:-}" ] && [[ "$PREFIX" == *com.termux* ]] || die "Jalankan ini di Termux."
for c in proot xz tar curl sha256sum; do
  command -v "$c" >/dev/null 2>&1 || die "perintah '$c' tidak ada. Pasang: pkg install proot xz-utils tar curl coreutils"
done
[ "$(uname -m)" = "aarch64" ] || die "Perangkat harus aarch64 (sekarang: $(uname -m))."
[ -d "$PROJ/app" ] || die "Proyek tidak ditemukan di $PROJ (set PROJ=...)."
[ -f "$PROJ/app/build.gradle" ] || die "$PROJ/app/build.gradle tidak ada."
grep -q "version '9" "$PROJ/build.gradle" 2>/dev/null \
  || warn "build.gradle ROOT tidak berisi 'version ...' pada plugin com.android.application. Jangan salin app/build.gradle ke root."
avail_kb=$(df -Pk "$WORK" | awk 'NR==2{print $4}')
[ "${avail_kb:-0}" -gt 3000000 ] || warn "Ruang kosong < 3 GB di $WORK; build bisa gagal."

# ---------------------------------------------------------------- 1. base
say "1/8 Tarball dasar"
if [ "$DO_REBASE" = 1 ] || [ ! -f "$BASE_TAR" ]; then
  [ -f "$ASSET" ] || die "Asset tidak ada: $ASSET"
  if xz -dc "$ASSET" | tar -tf - 2>/dev/null | grep '^ubuntu/rootfs/opt/hermes/' >/dev/null; then
    [ -f "$BASE_TAR" ] || die "Asset di proyek SUDAH berisi Hermes dan belum ada tarball dasar. Pulihkan Ubuntu murni ke $BASE_TAR lalu jalankan lagi."
    warn "Asset proyek sudah berisi Hermes; --rebase diabaikan (tarball dasar dipertahankan)."
  else
    cp -f "$ASSET" "$BASE_TAR"
    cp -n "$ASSET" "$BACKUP_DIR/$ASSET_NAME.orig" 2>/dev/null || true
    echo "Dasar disalin dari asset proyek."
  fi
fi
BASE_SHA=$(sha256sum "$BASE_TAR" | cut -d' ' -f1)
echo "base sha256: $BASE_SHA"
echo "cadangan asli: $BACKUP_DIR/$ASSET_NAME.orig"

# ------------------------------------------------------------- 2. extract
say "2/8 Ekstrak rootfs ke $ROOT"
if [ "$DO_SHELL" = 1 ] && [ -x "$ROOT/usr/bin/bash" ]; then
  echo "(--shell: memakai rootfs kerja yang sudah ada)"
else
  if [ -e "$WORK/ubuntu" ]; then
    chmod -R u+rwx "$WORK/ubuntu" 2>/dev/null || true
    rm -rf "$WORK/ubuntu"
  fi
  xz -dc "$BASE_TAR" | tar -xpf - -C "$WORK"
fi
[ -x "$ROOT/usr/bin/bash" ] || die "Layout arsip tak terduga: $ROOT/usr/bin/bash tidak ada."
[ -f "$WORK/ubuntu/manifest.json" ] || warn "ubuntu/manifest.json tidak ada di arsip."

# ---------------------------------------------------------------- proot
# env -i: bersihkan TERMUX_VERSION/PREFIX. install.sh Hermes MENOLAK jalan
# bila mendeteksi Termux (check_platform).
PROOT_SECCOMP_OFF=1
PROOT_FLAGS=""

in_root() {
  local -a flags
  read -r -a flags <<<"$PROOT_FLAGS"
  (
    [ "$PROOT_SECCOMP_OFF" = 1 ] && export PROOT_NO_SECCOMP=1
    export PROOT_TMP_DIR="$WORK/tmp"
    exec proot "${flags[@]}" -r "$ROOT" -w /root /usr/bin/env -i \
      HOME=/root TERM=xterm-256color LANG=C.UTF-8 \
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
      DEBIAN_FRONTEND=noninteractive \
      "$@"
  )
}

probe_proot() {
  say "Uji proot (memilih opsi yang berfungsi)"
  local cand seccomp flags
  # format: "<seccomp_off 1|0>|<opsi proot>"
  local -a candidates=(
    "1|--kill-on-exit --link2symlink -0 -b /dev -b /proc -b /sys"
    "1|--kill-on-exit --link2symlink -0 -b /dev -b /proc"
    "1|--kill-on-exit -0 -b /dev -b /proc -b /sys"
    "0|--kill-on-exit --link2symlink -0 -b /dev -b /proc -b /sys"
    "1|--kill-on-exit --link2symlink --sysvipc -0 -b /dev -b /proc -b /sys"
  )
  for cand in "${candidates[@]}"; do
    seccomp="${cand%%|*}"; flags="${cand#*|}"
    PROOT_SECCOMP_OFF="$seccomp"; PROOT_FLAGS="$flags"
    if in_root /usr/bin/env true >/dev/null 2>&1; then
      echo "proot OK  (NO_SECCOMP=$seccomp)  opsi: $flags"
      return 0
    fi
    echo "gagal    (NO_SECCOMP=$seccomp)  opsi: $flags"
  done

  warn "Semua kombinasi proot gagal. Diagnostik:"
  {
    echo "proot: $(command -v proot)  $(proot --version 2>&1 | head -1)"
    echo "kernel: $(uname -r)   android: $(getprop ro.build.version.release 2>/dev/null)"
    echo "--- berkas kunci di rootfs ---"
    ls -l "$ROOT/usr/bin/env" "$ROOT/usr/bin/bash" "$ROOT/lib" 2>&1
    ls -lL "$ROOT/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" "$ROOT/lib/ld-linux-aarch64.so.1" 2>&1
    echo "--- percobaan mentah (stderr proot) ---"
    PROOT_NO_SECCOMP=1 PROOT_TMP_DIR="$WORK/tmp" proot --link2symlink -0 -r "$ROOT" /usr/bin/env true 2>&1 | head -12
    PROOT_NO_SECCOMP=1 PROOT_TMP_DIR="$WORK/tmp" proot -0 -r "$ROOT" /bin/bash -c 'echo bash-ok' 2>&1 | head -12
  } | tee "$WORK/proot-diag.txt"
  die "proot tidak berfungsi dengan rootfs ini. Kirim isi $WORK/proot-diag.txt"
}

printf 'nameserver 8.8.8.8\nnameserver 8.8.4.4\n' > "$ROOT/etc/resolv.conf"
probe_proot

if [ "$DO_SHELL" = 1 ]; then
  say "Shell debug di rootfs kerja (exit untuk keluar)"
  in_root /bin/bash -l
  exit 0
fi

# ------------------------------------------------------------- 3. apt/dasar
say "3/8 Paket dasar (git, curl, libatomic1)"
mkdir -p "$ROOT/etc/apt/apt.conf.d"
# Di PRoot, pengguna _apt tidak bisa drop privilege: matikan sandbox apt.
# (Berguna juga untuk 'apt install' pengguna di dalam aplikasi.)
printf 'APT::Sandbox::User "root";\n' > "$ROOT/etc/apt/apt.conf.d/99paynux-nosandbox"
in_root /bin/bash -lc '
  set -e
  apt-get update
  apt-get install -y --no-install-recommends git curl ca-certificates tar xz-utils libatomic1 procps
'

# --------------------------------------------------------------- 4. hermes
say "4/8 Pasang Hermes Agent (install.sh resmi, channel=$CHANNEL)"
# HOME terpisah untuk build: apa pun yang default-nya di bawah \$HOME (uv python,
# cache, launcher ~/.local/bin) mendarat di /opt/hermes (ikut APK), bukan di
# /root yang di perangkat adalah folder data pengguna di luar rootfs.
in_root /bin/bash -lc "
  set -e
  mkdir -p /opt/hermes/buildhome /opt/hermes/tools /opt/hermes/home
  export HOME=/opt/hermes/buildhome
  export HERMES_HOME=/opt/hermes/home
  export HERMES_RUNTIME_DIR=/opt/hermes/tools
  # PRoot/Android menolak link(2) pada data aplikasi: uv sync gagal
  # (Operation not permitted, os error 1). Salin berkas alih-alih hardlink.
  export UV_LINK_MODE=copy
  export pip_DISABLE_PIP_VERSION_CHECK=1
  # Config sistem uv: berlaku untuk jalur biasa, TIDAK untuk --no-config.
  mkdir -p /etc/uv
  printf 'link-mode = "copy"\n' > /etc/uv/uv.toml
  curl -fsSL https://hermes-agent.nousresearch.com/install.sh -o /tmp/hermes-install.sh
  run_installer() {
    bash /tmp/hermes-install.sh --non-interactive --skip-browser --skip-computer-use \
         --channel '$CHANNEL' --dir /opt/hermes/hermes-agent --hermes-home /opt/hermes/home
  }
  # Percobaan 1 menyiapkan clone + dependensi. pm-runtime dibangun dengan
  # --no-config sehingga /etc/uv/uv.toml diabaikan -> uv memakai hardlink ->
  # EPERM. Tambal PM agar meneruskan UV_LINK_MODE=copy, buang lock, ulangi.
  if ! run_installer; then
    echo '--- percobaan 1 gagal: tambal pm/environment.py lalu ulangi ---'
    PY="$(command -v python3 || true)"
    [ -n "$PY" ] || PY=$(ls /opt/hermes/buildhome/.local/share/uv/python/*/bin/python3.1* 2>/dev/null | head -1)
    "$PY" - <<'PY'
p = '/opt/hermes/hermes-agent/pm/environment.py'
s = open(p).read()
old = 'UV_CACHE_DIR=native(self.cache), UV_PYTHON_DOWNLOADS="never")'
new = ('UV_CACHE_DIR=native(self.cache), UV_PYTHON_DOWNLOADS="never",\n'
       '                   UV_LINK_MODE="copy")')
if 'UV_LINK_MODE' in s:
    print('tambalan sudah ada')
elif old in s:
    open(p, 'w').write(s.replace(old, new, 1))
    print('tambalan UV_LINK_MODE dipasang')
else:
    raise SystemExit('anchor tidak ditemukan: ' + p)
PY
    rm -f /opt/hermes/home/installs/*/pm-runtime/.prepare.lock
    run_installer
  fi
" || die "Instalasi Hermes gagal. Lihat $LOG dan (di rootfs) /opt/hermes/home/logs/install.log. Debug: bash $0 --shell"

# ------------------------------------------------------------- 5. launcher
say "5/8 Launcher /usr/local/bin/hermes"
HERMES_BIN=$(in_root /bin/bash -lc '
  for p in /opt/hermes/buildhome/.local/bin/hermes /opt/hermes/hermes-agent/.hermes/bin/hermes; do
    [ -x "$p" ] && { echo "$p"; exit 0; }
  done
  exit 1
') || die "Launcher hermes tidak ditemukan setelah instalasi. Periksa log install."
HERMES_BIN=$(printf '%s' "$HERMES_BIN" | tr -d '\r')
echo "launcher asli: $HERMES_BIN"

cat > "$ROOT/usr/local/bin/hermes" <<EOF
#!/bin/bash
# Launcher Hermes untuk Paynux. Kode & runtime ada di /opt/hermes (di dalam
# rootfs); data pengguna di \${HERMES_HOME:-/root/.hermes} (folder pengguna).
# TIDAK mengunduh apa pun saat dijalankan.
export HERMES_RUNTIME_DIR=/opt/hermes/tools
export HERMES_HOME="\${HERMES_HOME:-/root/.hermes}"
if [ ! -e "\$HERMES_HOME/.paynux-seeded" ]; then
  mkdir -p "\$HERMES_HOME"
  # Seed sekali dari salinan bawaan (skills/config contoh), tanpa menimpa
  # file pengguna dan tanpa log/telemetri/sesi.
  if [ -d /opt/hermes/home ]; then
    for f in /opt/hermes/home/* /opt/hermes/home/.[!.]*; do
      [ -e "\$f" ] || continue
      case "\${f##*/}" in logs|telemetry|sessions|cache|tmp) continue ;; esac
      cp -an "\$f" "\$HERMES_HOME/" 2>/dev/null || true
    done
  fi
  chmod 600 "\$HERMES_HOME/.env" 2>/dev/null || true
  : > "\$HERMES_HOME/.paynux-seeded"
fi
exec "$HERMES_BIN" "\$@"
EOF
chmod 755 "$ROOT/usr/local/bin/hermes"

# ------------------------------------------------------------- 6. verifikasi
say "6/8 Verifikasi di dalam rootfs"
STATUS_UBUNTU=FAIL; STATUS_PYTHON=FAIL; STATUS_HERMES=FAIL
in_root /bin/bash -lc 'echo ubuntu-ok; . /etc/os-release; echo "$PRETTY_NAME"; uname -m' && STATUS_UBUNTU=PASS

PYV=$(in_root /bin/bash -lc '
  for p in /opt/hermes/hermes-agent/venv/bin/python /opt/hermes/hermes-agent/.venv/bin/python; do
    [ -x "$p" ] && { "$p" --version 2>&1; exit 0; }
  done
  exit 1') && STATUS_PYTHON=PASS || PYV="(tidak ditemukan)"
echo "python venv: $PYV"

if HV=$(in_root /usr/local/bin/hermes --version 2>&1); then STATUS_HERMES=PASS; fi
echo "hermes --version: $HV"

if [ "$PY_OFFLINE_TEST" = 1 ] && [ "$STATUS_HERMES" = PASS ]; then
  # Bukti LEMAH (bukan pengganti uji mode pesawat di HP): proxy ke port mati.
  if in_root /usr/bin/env HTTP_PROXY=http://127.0.0.1:9 HTTPS_PROXY=http://127.0.0.1:9 \
        HERMES_HOME=/tmp/hermes-offline-test /usr/local/bin/hermes --version >/dev/null 2>&1; then
    echo "hermes --version tetap jalan dengan proxy mati: OK"
  else
    warn "hermes --version GAGAL saat jaringan diblok. Kemungkinan butuh jaringan saat runtime."
    STATUS_HERMES="PASS-ONLINE-ONLY"
  fi
fi

# ------------------------------------------------------------- 7. manifest+bersih
say "7/8 Manifest dan pembersihan"
in_root /bin/bash -lc '
  {
    echo "built: $(date -u +%FT%TZ)"
    echo "hermes: $(/usr/local/bin/hermes --version 2>&1 | head -1)"
    echo "python: $(/opt/hermes/hermes-agent/venv/bin/python --version 2>&1 || /opt/hermes/hermes-agent/.venv/bin/python --version 2>&1)"
    echo "git: $(git -C /opt/hermes/hermes-agent rev-parse HEAD 2>/dev/null)"
    echo "--- dpkg ---"; dpkg-query -W -f="\${Package} \${Version}\n" | sort
  } > /opt/hermes/MANIFEST.txt
' || warn "manifest sebagian gagal dibuat"

if [ "$PRUNE_GIT" = 1 ]; then
  warn "PRUNE_GIT=1: .git Hermes dihapus (hermes update tidak akan berfungsi)."
  rm -rf "$ROOT/opt/hermes/hermes-agent/.git"
fi
in_root /bin/bash -lc '
  apt-get clean
  rm -rf /var/lib/apt/lists/* /var/cache/apt/* /tmp/* /var/tmp/*
  rm -rf /opt/hermes/home/logs /opt/hermes/home/telemetry /opt/hermes/buildhome/.cache
' || true
rm -rf "$ROOT/tmp/"* "$ROOT/root/.cache" "$ROOT/root/.hermes" "$ROOT/root/.local/share/uv" 2>/dev/null || true
echo "ukuran rootfs: $(du -sh "$ROOT" | cut -f1)   /opt/hermes: $(du -sh "$ROOT/opt/hermes" | cut -f1)"
du -sh "$ROOT/opt/hermes/"* 2>/dev/null | sort -h | tail -8

# ------------------------------------------------------------- 8. kemas
say "8/8 Kemas ulang asset (xz -$XZ_LEVEL)"
OUT_TAR="$OUT_DIR/$ASSET_NAME"
rm -f "$OUT_TAR"
tar --numeric-owner --owner=0 --group=0 -cpf - -C "$WORK" ubuntu | xz -T0 "-$XZ_LEVEL" > "$OUT_TAR"
OUT_SHA=$(sha256sum "$OUT_TAR" | cut -d' ' -f1)
OUT_SIZE=$(du -h "$OUT_TAR" | cut -f1)

# sanity: arsip baru harus berisi layout yang diharapkan installUbuntu()
xz -dc "$OUT_TAR" | tar -tf - | grep '^ubuntu/rootfs/usr/local/bin/hermes$' >/dev/null || die "arsip baru tidak berisi launcher hermes"
xz -dc "$OUT_TAR" | tar -tf - | grep '^ubuntu/manifest.json$' >/dev/null || warn "arsip baru tanpa ubuntu/manifest.json"

cp -f "$OUT_TAR" "$ASSET"
echo "Asset proyek diperbarui: $ASSET ($OUT_SIZE)"

REPORT="$OUT_DIR/BUILD-REPORT.txt"
{
  echo "Paynux + Hermes rootfs build  $(date -u +%FT%TZ)"
  echo "base asset sha256 : $BASE_SHA"
  echo "new asset  sha256 : $OUT_SHA"
  echo "new asset  size   : $OUT_SIZE"
  echo "channel           : $CHANNEL"
  echo "hermes            : ${HV:-?}"
  echo "python            : ${PYV:-?}"
  echo "UBUNTU $STATUS_UBUNTU | PYTHON $STATUS_PYTHON | HERMES $STATUS_HERMES"
} | tee "$REPORT"

if [ "$DO_BUILD" = 1 ]; then
  say "Gradle assembleDebug"
  ( cd "$PROJ" && ./gradlew assembleDebug )
  APK="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
  [ -f "$APK" ] || die "APK tidak ditemukan setelah build."
  DEST="/sdcard/Download/Paynux-hermes-debug.apk"
  cp -f "$APK" "$DEST"
  echo "APK: $DEST  $(du -h "$DEST" | cut -f1)  sha256 $(sha256sum "$DEST" | cut -d' ' -f1)"
  echo "BUILD PASS"
else
  echo "Belum build APK. Jalankan: cd $PROJ && ./gradlew assembleDebug  (atau ulangi dengan --build)"
fi

cat <<EOT

Langkah uji WAJIB di HP (skrip ini tidak bisa membuktikannya):
  1. Hapus data aplikasi Paynux lama (agar rootfs baru terekstrak), pasang APK, buka.
  2. Pertama kali ekstraksi lebih lama; butuh ruang kosong sekitar 3x ukuran rootfs.
  3. Di terminal Paynux:  hermes --version
  4. Aktifkan mode pesawat, ulangi langkah 3 (harus tetap berhasil).
  5. Tutup & buka lagi aplikasi: isi /root/.hermes harus tetap ada.
EOT
