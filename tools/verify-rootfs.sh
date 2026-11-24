#!/usr/bin/env bash
# Verifies that the SHA-256 pinned in RootfsSource.java matches the official,
# GPG-signed SHA256SUMS from Canonical. Only writes inside its own temp dir.
#
# Usage:  tools/verify-rootfs.sh                  (online: download + gpg + compare)
#         tools/verify-rootfs.sh --compare FILE   (offline: compare against a SHA256SUMS file)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java/com/paynux/setup/RootfsSource.java"
FPR="843938DF228D22F7B3742BC0D94AA3F0EFE21092"

file_name="$(sed -n 's/.*FILE_NAME = "\(.*\)";/\1/p' "$SRC")"
pinned="$(grep -A1 'SHA256 =' "$SRC" | grep -o '"[0-9a-f]\{64\}"' | tr -d '"')"
base="$(sed -n 's/.*BASE_URL =//p' "$SRC" >/dev/null; grep -A1 'BASE_URL =' "$SRC" | grep -o 'https://[^"]*')"
[ -n "$file_name" ] && [ -n "$pinned" ] && [ -n "$base" ] || { echo "cannot parse $SRC"; exit 2; }

if [ "${1:-}" = "--compare" ]; then
  sums="${2:?SHA256SUMS file required}"
else
  work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
  curl -fL --silent --show-error -o "$work/SHA256SUMS" "${base}SHA256SUMS"
  curl -fL --silent --show-error -o "$work/SHA256SUMS.gpg" "${base}SHA256SUMS.gpg"
  gpg --keyserver hkp://keyserver.ubuntu.com --recv-keys "0x${FPR:24}" >/dev/null 2>&1 || true
  status="$(gpg --status-fd 1 --verify "$work/SHA256SUMS.gpg" "$work/SHA256SUMS" 2>/dev/null || true)"
  echo "$status" | grep -q "^\[GNUPG:\] GOODSIG" || { echo "FAIL: signature is not good"; exit 1; }
  echo "$status" | grep -q "^\[GNUPG:\] VALIDSIG .*$FPR" || { echo "FAIL: signer fingerprint != $FPR"; exit 1; }
  echo "OK: SHA256SUMS signed by $FPR"
  sums="$work/SHA256SUMS"
fi

official="$(grep -F "$file_name" "$sums" | awk '{print $1}')"
[ -n "$official" ] || { echo "FAIL: $file_name not listed in SHA256SUMS"; exit 1; }
if [ "$official" = "$pinned" ]; then
  echo "OK: pinned SHA-256 matches official ($file_name)"
else
  echo "FAIL: pinned $pinned != official $official"; exit 1
fi
