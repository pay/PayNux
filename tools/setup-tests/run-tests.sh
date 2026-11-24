#!/usr/bin/env bash
# Compiles the setup package (without the Android-only AndroidFsOps) and runs the
# JVM tests. Uses only a private temp dir. Usage: run-tests.sh [rootfs.tar.xz]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/java/com/paynux/setup"
ROOTFS="${1:-$HERE/../../app/src/main/assets/ubuntu-24.04-aarch64.tar.xz}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null; then JC=(javac); else JC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
mkdir -p "$OUT/main" "$OUT/test"
"${JC[@]}" -source 8 -target 8 -Xlint:-options -d "$OUT/main" $(ls "$SRC"/*.java "$SRC"/../compat/*.java | grep -v AndroidFsOps)
"${JC[@]}" -cp "$OUT/main" -d "$OUT/test" "$HERE"/*.java
timeout 120 java -cp "$OUT/main:$OUT/test" DownloaderTest
python3 "$HERE/make_fixtures.py" "$OUT/fixtures" >/dev/null
timeout 120 java -cp "$OUT/main:$OUT/test" SetupManagerTest "$OUT/fixtures"
timeout 60 java -cp "$OUT/main:$OUT/test" ProotSelfTestTest
timeout 60 java -cp "$OUT/main:$OUT/test" FakeProcTest
if [ -f "$ROOTFS" ]; then REALARG="$ROOTFS"; else REALARG=""; fi
timeout 200 java -cp "$OUT/main:$OUT/test" OfflineInstallerTest "$OUT/fixtures" $REALARG
if [ -f "$ROOTFS" ]; then
  python3 "$HERE/run_tar_tests.py" "$OUT/main:$OUT/test" "$ROOTFS"
else
  python3 "$HERE/run_tar_tests.py" "$OUT/main:$OUT/test"
fi
