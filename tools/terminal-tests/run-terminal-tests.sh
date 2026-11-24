#!/usr/bin/env bash
# Compiles the pure-Java terminal classes and runs their tests. Private temp dir only.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/java/com/paynux/terminal"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null; then JC=(javac); else JC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JC[@]}" -source 8 -target 8 -Xlint:-options -d "$OUT" "$SRC/TerminalEmulator.java" "$SRC/PasteFormatter.java" "$SRC/../compat/CommandDone.java" "$HERE"/*.java 2>&1 | grep -v '^Note' || true
timeout 60 java -cp "$OUT" TerminalEmulatorCsiTest
timeout 60 java -cp "$OUT" PasteTest
