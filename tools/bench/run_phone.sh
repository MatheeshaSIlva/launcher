#!/usr/bin/env bash
# Step-0 benchmarks on the phone (docs/BENCH_STEP0.md). Installs nothing: build and install first with
#   ./gradlew -Pbench :bench:assembleLuajDebug :bench:assembleLuakDebug, then adb install -r each APK.
# Each suite runs in a fresh process of the bench app (am start -S stops dev.launcher.bench only, never the launcher), the
# results land in tools/shots/bench/. No taps: before each suite the phone must be unlocked, untouched for 60 s, and showing
# home or the bench app (otherwise the script stops and says why).
#   DEVICE=<serial> tools/bench/run_phone.sh [SUITE ...]     suites: lua lua-luak kdl glass deck (default: all)
set -u
cd "$(dirname "$0")/../.." || exit 1
ADB=${ADB:-adb}
command -v "$ADB" >/dev/null 2>&1 || ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
A() { MSYS_NO_PATHCONV=1 "$ADB" ${DEVICE:+-s "$DEVICE"} "$@"; }
OUT=tools/shots/bench
mkdir -p "$OUT"

ready() {
  local idle focus
  if A shell dumpsys window | grep -q "isKeyguardShowing=true"; then echo "phone locked: stop"; return 1; fi
  idle=$(A shell dumpsys power | sed -n 's/.*lastUserActivityTime=.*(\([0-9]*\) ms ago).*/\1/p' | head -1)
  if [ -n "$idle" ] && [ "$idle" -lt 60000 ]; then echo "phone touched ${idle} ms ago: stop"; return 1; fi
  focus=$(A shell dumpsys window | grep mCurrentFocus | head -1)
  case "$focus" in
    *dev.launcher.app/*|*dev.launcher.bench*) return 0 ;;
    *) echo "focus is not home or the bench ($focus): stop"; return 1 ;;
  esac
}

run() {
  local suite=$1 pkg=dev.launcher.bench flavour=luaj extra=$1
  if [ "$suite" = lua-luak ]; then pkg=dev.launcher.bench.luak; flavour=luak; extra=lua; fi
  local dir=/sdcard/Android/data/$pkg/files/results
  ready || exit 3
  echo "== $suite ($pkg)"
  A shell rm -f "$dir/$extra-$flavour.done"
  A shell am start -S -n "$pkg/dev.launcher.bench.BenchActivity" --es suite "$extra" >/dev/null
  for _ in $(seq 1 240); do
    if A shell ls "$dir/$extra-$flavour.done" >/dev/null 2>&1; then break; fi
    sleep 2
  done
  A pull "$dir/$extra-$flavour.txt" "$OUT/$extra-$flavour.txt" >/dev/null && cat "$OUT/$extra-$flavour.txt"
  if [ "$suite" = glass ]; then rm -rf "$OUT/glass"; A pull "/sdcard/Android/data/$pkg/files/glass" "$OUT/" >/dev/null; fi
  sleep 5
}

A shell dumpsys thermalservice | grep -E "mName=(AP|SKIN)" | head -2
for s in "${@:-lua lua-luak kdl glass deck}"; do for t in $s; do run "$t"; done; done
# Back to home (its intent; the Home key is blocked while our gestures run).
A shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -p dev.launcher.app >/dev/null
A shell dumpsys thermalservice | grep -E "mName=(AP|SKIN)" | head -2
