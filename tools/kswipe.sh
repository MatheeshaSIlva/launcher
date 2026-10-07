#!/usr/bin/env bash
# Emulator only: a swipe made of touchscreen events sent through the emulator console (adb emu event send), so it reaches
# the kernel's touch device as a finger does (injected `input swipe` events never touch /dev/input): what reads /dev/input
# (the shade's touch stream) sees it too. Each step is one console call (~30-60 ms apart).
#   tools/kswipe.sh X1 Y1 X2 Y2 [STEPS] [HOLD_STEPS]     display px; HOLD_STEPS: steps that stay at the end before lifting
set -e
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
[ -x "$ADB" ] || ADB=adb
D=(-s "${DEVICE:-emulator-5554}")
x1=$1; y1=$2; x2=$3; y2=$4; steps=${5:-16}; hold=${6:-0}
size=$("$ADB" "${D[@]}" shell wm size | tail -1 | awk '{print $NF}' | tr -d '\r')
w=${size%x*}; h=${size#*x}
max=32767
rx() { echo $(( $1 * max / w )); }
ry() { echo $(( $1 * max / h )); }
"$ADB" "${D[@]}" emu event send EV_ABS:ABS_MT_SLOT:0 EV_ABS:ABS_MT_TRACKING_ID:42 EV_ABS:ABS_MT_TOUCH_MAJOR:12 EV_ABS:ABS_MT_PRESSURE:512 EV_ABS:ABS_MT_POSITION_X:$(rx $x1) EV_ABS:ABS_MT_POSITION_Y:$(ry $y1) EV_SYN:0:0 >/dev/null
for i in $(seq 1 "$steps"); do
  x=$(( x1 + (x2 - x1) * i / steps )); y=$(( y1 + (y2 - y1) * i / steps ))
  "$ADB" "${D[@]}" emu event send EV_ABS:ABS_MT_POSITION_X:$(rx $x) EV_ABS:ABS_MT_POSITION_Y:$(ry $y) EV_SYN:0:0 >/dev/null
done
for i in $(seq 1 "$hold"); do
  "$ADB" "${D[@]}" emu event send EV_ABS:ABS_MT_POSITION_X:$(rx $x2) EV_ABS:ABS_MT_POSITION_Y:$(ry $y2) EV_SYN:0:0 >/dev/null
done
"$ADB" "${D[@]}" emu event send EV_ABS:ABS_MT_PRESSURE:0 EV_ABS:ABS_MT_TRACKING_ID:-1 EV_SYN:0:0 >/dev/null
