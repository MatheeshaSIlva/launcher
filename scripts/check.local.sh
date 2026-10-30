#!/usr/bin/env bash
# Project quality gate (replaces the generic scripts/check.sh body).
# Debug variant only: build, lint, unit tests. --continue so a lint error
# does not hide failing tests.
# Gradle 8.9 cannot run on newer JDKs, so JDK 17 is always selected here.

set -u
cd "$(dirname "$0")/.." || exit 1

# Is "$1" a JDK 17? Reads the "version" line only (JAVA_TOOL_OPTIONS adds a "Picked up" line first).
is_jdk17() {
  [ -x "$1/bin/java" ] || return 1
  case "$("$1/bin/java" -version 2>&1 | grep -m1 ' version "')" in
    *' version "17"'* | *' version "17.'*) return 0 ;;
  esac
  return 1
}

# JAVA_HOME_17, else the Adoptium path; an inherited JAVA_HOME only as the last resort and only if it is 17.
home="${JAVA_HOME_17:-/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot}"
if [ -z "${JAVA_HOME_17:-}" ] && [ ! -x "$home/bin/java" ] && [ -n "${JAVA_HOME:-}" ]; then
  home="$JAVA_HOME"
fi
if ! is_jdk17 "$home"; then
  echo "CHECK FAILED: JDK 17 required for Gradle 8.9 (set JAVA_HOME_17, or install the Adoptium JDK 17 path)."
  echo "Looked in: $home"
  exit 1
fi
export JAVA_HOME="$home"

# Every check running says so in the lock folder (one file per check, its pid): the Stop hook skips while one runs (owner,
# 2026-10-30: hook checks that outlived the hook's timeout piled up beside each other).
lockdir="${TMPDIR:-/tmp}/launcher-check.lock"
mkdir -p "$lockdir" && : > "$lockdir/$$"
trap 'rm -f "$lockdir/$$"' EXIT

./gradlew --continue --console=plain assembleDebug lintDebug testDebugUnitTest
rc=$?
[ "$rc" -eq 0 ] || echo "gradle exit $rc"

# CHECK_FAST=1 (the Stop hook): build, lint and unit tests only; the shell tests (~40 min) run in the full check before a push.
if [ "${CHECK_FAST:-0}" = "1" ]; then
  if [ "$rc" -eq 0 ]; then echo "CHECK PASSED (fast: no shell tests)"; else echo "CHECK FAILED (fast: gradle)"; fi
  exit "$rc"
fi

# Shell tests (D-025): every tools/tests/*_test.sh, in name order; each must exit 0.
failed=""
for t in tools/tests/*_test.sh; do
  [ -f "$t" ] || continue
  echo "==> $t"
  if bash "$t"; then
    echo "shell test PASSED: $t"
  else
    echo "shell test FAILED: $t"
    failed="$failed $t"
    [ "$rc" -ne 0 ] || rc=1
  fi
done

if [ "$rc" -eq 0 ]; then
  echo "CHECK PASSED"
else
  echo "CHECK FAILED (gradle exit status or shell tests:${failed:- none failed})"
fi
exit "$rc"
