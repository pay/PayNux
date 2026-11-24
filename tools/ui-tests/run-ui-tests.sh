#!/usr/bin/env bash
# Compiles the pure-Java UI helpers and runs their tests. Private temp dir only.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/java/com/paynux/compat"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null; then JC=(javac); else JC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JC[@]}" -source 8 -target 8 -Xlint:-options -d "$OUT" "$SRC/ToolbarConfig.java" "$SRC/UrlFinder.java" "$SRC/SessionName.java" "$SRC/AboutInfo.java" "$HERE/UiHelpersTest.java" "$HERE/AboutRowsTest.java" 2>&1 | grep -v '^Note' || true
timeout 60 java -cp "$OUT" UiHelpersTest
timeout 60 java -cp "$OUT" AboutRowsTest
