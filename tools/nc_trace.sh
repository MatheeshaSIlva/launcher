#!/usr/bin/env bash
# Notification Center's choreography as numbers (docs/PLAN_PANEL_TRANSITIONS.md, step 2b): runs scripted pulls in the app
# (TEST_SHADE nc-scenario: every step counted in frames, motion on a fixed step a frame) and pulls each frame-by-frame trace
# into tools/shots/nc-trace-NAME-OVER-SCENARIO.csv. Two runs of one build read the same to the last digit; compare two
# builds' traces with tools/nc_trace_compare.py.
#
#   [DEVICE=emulator-5554] tools/nc_trace.sh NAME [home|app] [scenario...]
#
# Scenarios: open-close (pulled to 0.7, held 1 s, released: it opens; 2 s later closed), fallback (pulled to 0.3, released:
# it falls back), interrupt (released, closed 6 frames later: the close takes over mid-reveal). Over "app" it opens Clock
# first and checks it is in front.
set -u
cd "$(dirname "$0")/.." || exit 1
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB=adb
DEVICE="${DEVICE:-emulator-5554}"
a() { MSYS_NO_PATHCONV=1 "$ADB" -s "$DEVICE" "$@"; }
name="${1:?name}"; over="${2:-home}"; shift 2 2>/dev/null || shift $#
scenarios="${*:-open-close fallback interrupt}"
PKG=dev.launcher.app
DIR=/sdcard/Android/data/$PKG/files/dev-out

if [ "$over" = app ]; then
  a shell monkey -p com.google.android.deskclock -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  want=deskclock
else
  a shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -p $PKG >/dev/null 2>&1
  want=HomeActivity
fi
sleep 3
if ! a shell dumpsys window | grep -m1 mCurrentFocus | grep -q "$want"; then
  echo "REFUSED: $want is not in front ($(a shell dumpsys window | grep -m1 mCurrentFocus))"; exit 2
fi
mkdir -p tools/shots
for s in $scenarios; do
  a shell rm -f $DIR/nc-trace.done
  a shell am broadcast -a $PKG.TEST_SHADE -p $PKG --es do nc-scenario --es name "$s" >/dev/null
  ok=0
  for _ in $(seq 1 300); do a shell test -f $DIR/nc-trace.done && { ok=1; break; }; sleep 1; done
  # (A scenario still running when the next starts would mix the two: stop it, and say so.)
  [ $ok = 1 ] || { echo "$s: no trace after 300 s (stopped)"; a shell am broadcast -a $PKG.TEST_SHADE -p $PKG --es do nc-trace --ez on false >/dev/null; sleep 5; continue; }
  out="tools/shots/nc-trace-$name-$over-$s.csv"
  a pull $DIR/nc-trace.csv "$out" >/dev/null && echo "$out ($(wc -l < "$out") lines)"
done
