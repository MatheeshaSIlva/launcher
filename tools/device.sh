#!/usr/bin/env bash
# Drive a device or emulator for visual checks (Claude uses this to look at a build before Matheesha does).
#   tools/device.sh install                 build is assumed done: installs app-debug.apk and makes it the home app
#   tools/device.sh home                    go home
#   tools/device.sh shot NAME               screenshot to tools/shots/NAME.png
#   tools/device.sh tap X Y | long X Y [MS] | swipe X1 Y1 X2 Y2 [MS] | drag X1 Y1 X2 Y2 [HOLD_MS]
#   tools/device.sh log [N]                 last N lines of our log (default 60)
#   tools/device.sh gfx                     frame stats of the launcher since the last reset (then resets)
# Coordinates are device pixels. DEVICE selects the target (default: the first one adb lists).
set -e
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB=adb
DEV=${DEVICE:+-s $DEVICE}
PKG=dev.launcher.app
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$here/shots"
cmd=$1; shift || true
case "$cmd" in
  install)
    "$ADB" $DEV install -r -d "$here/../app/build/outputs/apk/debug/app-debug.apk" | tail -1
    "$ADB" $DEV shell cmd package set-home-activity $PKG/.HomeActivity >/dev/null
    "$ADB" $DEV shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null ;;
  home) "$ADB" $DEV shell input keyevent KEYCODE_HOME ;;
  shot) "$ADB" $DEV exec-out screencap -p > "$here/shots/$1.png"; echo "$here/shots/$1.png" ;;
  tap) "$ADB" $DEV shell input tap "$1" "$2" ;;
  long) "$ADB" $DEV shell input swipe "$1" "$2" "$1" "$2" "${3:-900}" ;;
  swipe) "$ADB" $DEV shell input swipe "$1" "$2" "$3" "$4" "${5:-300}" ;;
  drag)
    # Press at X1 Y1, hold HOLD ms (default 700: past a long press), move to X2 Y2 in steps, rest, lift.
    x1=$1; y1=$2; x2=$3; y2=$4; hold=${5:-700}
    "$ADB" $DEV shell "input motionevent DOWN $x1 $y1; sleep $(awk "BEGIN{print $hold/1000}");       for i in 1 2 3 4 5 6 7 8; do input motionevent MOVE \$(( $x1 + ($x2 - $x1) * i / 8 )) \$(( $y1 + ($y2 - $y1) * i / 8 )); done;       sleep 0.5; input motionevent UP $x2 $y2" ;;
  log)
    pid=$("$ADB" $DEV shell pidof $PKG | tr -d '\r')
    "$ADB" $DEV logcat -d --pid="$pid" | grep -E " Launcher|AndroidRuntime|FATAL" | tail -"${1:-60}" ;;
  gfx) "$ADB" $DEV shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|percentile" ; "$ADB" $DEV shell dumpsys gfxinfo $PKG reset >/dev/null ;;
  *) sed -n 2,10p "$0" ;;
esac
