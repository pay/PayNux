#!/usr/bin/env bash
# Compiles ShmDir (pure Java) and runs its tests. Private temp dir only.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/java/com/paynux"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null; then JC=(javac); else JC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JC[@]}" -source 8 -target 8 -Xlint:-options -d "$OUT" "$SRC/setup/FsOps.java" "$SRC/compat/ShmDir.java" \
    "$HERE/../setup-tests/JvmFsOps.java" "$HERE/ShmDirTest.java" 2>&1 | grep -v '^Note' || true
timeout 60 java -cp "$OUT" ShmDirTest
