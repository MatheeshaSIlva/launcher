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

./gradlew --continue --console=plain assembleDebug lintDebug testDebugUnitTest
rc=$?
if [ "$rc" -eq 0 ]; then
  echo "CHECK PASSED"
else
  echo "CHECK FAILED (gradle exit $rc)"
fi
exit "$rc"
