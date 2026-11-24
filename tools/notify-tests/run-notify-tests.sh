#!/usr/bin/env bash
# Compiles the pure-Java notification pieces, runs their tests, and (when python3 + bash exist) tests the
# bash hook in a pseudo-terminal. Private temp dir only.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/java/com/paynux"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null; then JC=(javac); else JC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JC[@]}" -source 8 -target 8 -Xlint:-options -d "$OUT" "$SRC/terminal/TerminalEmulator.java" "$SRC/compat/CommandDone.java" \
    "$HERE/CommandDoneTest.java" "$HERE/PrintHook.java" 2>&1 | grep -v '^Note' || true
timeout 60 java -cp "$OUT" CommandDoneTest
if command -v python3 >/dev/null && command -v bash >/dev/null; then
  java -cp "$OUT" PrintHook > "$OUT/hook.sh"
  bash -n "$OUT/hook.sh"
  timeout 150 python3 "$HERE/hook_test.py" "$OUT/hook.sh"
else
  echo "SKIP hook pty test (python3 or bash missing)"
fi
