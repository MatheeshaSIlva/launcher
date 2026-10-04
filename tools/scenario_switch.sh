#!/usr/bin/env bash
# Sideways switches between Calculator and Clock (both opened through Spotlight), printing frame logs and what the screen
# showed for each switch. Frame stats only, no screenshots inside the apps (Clock and Calculator hold nothing private, but the habit stays). Every tap is gated on a checked state.
#   DEVICE=<serial> tools/scenario_switch.sh [N]
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(-s "$DEVICE")
focus() { "$ADB" "${D[@]}" shell dumpsys window | grep mCurrentFocus; }
open_from_spotlight() {   # $1 = text to type, $2 = package expected in front
  focus | grep -q HomeActivity || { echo "home is not in front: stop"; exit 1; }
  tools/device.sh swipe 540 2330 540 1700 150; sleep 1
  tools/device.sh tap 540 1931; sleep 1
  "$ADB" "${D[@]}" shell dumpsys input_method | grep -q "mInputShown=true" || { echo "Spotlight keyboard not up: stop"; exit 1; }
  MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" shell input text "$1"; sleep 0.8
  tools/device.sh tap 170 460; sleep 2.5
  focus | grep -q "$2" || { echo "$2 not in front: stop"; exit 1; }
}
measure() {   # $1 = label
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app framestats > "tools/shots/fs_$1.txt"
  echo "$1: $(tools/device.sh log 8 | grep -A1 'quick switch' | grep frames | tail -1 | sed 's/.*Launcher: *//')"
  python tools/framestats.py "tools/shots/fs_$1.txt" LauncherCards | grep -E "GPU ms|shown|frame #"
}
open_from_spotlight clock clockpackage
tools/device.sh swipe 540 2330 540 1500 180; sleep 1.5
open_from_spotlight calc popupcalculator
for i in $(seq 1 "${1:-2}"); do
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app reset >/dev/null
  tools/device.sh swipe 150 2330 900 2330 200; sleep 1.5          # to the previous app
  focus | grep -q clockpackage || { echo "Clock not in front after the switch: stop"; exit 1; }
  measure switch_back_$i
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app reset >/dev/null
  tools/device.sh swipe 900 2330 150 2330 200; sleep 1.5          # and forward again
  focus | grep -q popupcalculator || { echo "Calculator not in front after the switch: stop"; exit 1; }
  measure switch_fwd_$i
done
tools/device.sh swipe 540 2330 540 1500 180; sleep 1.5
