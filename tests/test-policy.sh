#!/usr/bin/env bash
set -euo pipefail
base=$(cd "$(dirname "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
src="$base/app/src/main/java/dev/pagetrim/app"
javac -encoding UTF-8 -d "$tmp" "$src/Diagnostics.java" "$src/Root.java" "$src/RamController.java" "$base/tests/DiagnosticsPolicyTest.java"
java -cp "$tmp" dev.pagetrim.app.DiagnosticsPolicyTest
javac -encoding UTF-8 -d "$tmp" "$src/WatchTiming.java" "$base/tests/WatchTimingTest.java"
java -cp "$tmp" dev.pagetrim.app.WatchTimingTest
