#!/usr/bin/env bash
# Opens Calculator from Spotlight and closes it to the centre of page 1 (no home icon), N times, printing frame logs and
# GPU/CPU per frame of the card window and home for each close. Every tap is gated on a checked state (see CLAUDE.md).
#   DEVICE=<serial> tools/scenario_spotlight_close.sh [N]
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(-s "$DEVICE")
focus() { "$ADB" "${D[@]}" shell dumpsys window | grep mCurrentFocus; }
for i in $(seq 1 "${1:-3}"); do
  focus | grep -q HomeActivity || { echo "home is not in front: stop"; exit 1; }
  tools/device.sh swipe 540 2330 540 1700 150; sleep 1          # page 1 (a swipe up on home)
  tools/device.sh tap 540 1931; sleep 1                          # the Search pill: Spotlight with the keyboard
  "$ADB" "${D[@]}" shell dumpsys input_method | grep -q "mInputShown=true" || { echo "Spotlight keyboard not up: stop"; exit 1; }
  MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" shell input text calc; sleep 0.8
  tools/device.sh tap 170 460; sleep 2.5                         # Top Hit
  focus | grep -q popupcalculator || { echo "Calculator not in front: stop"; exit 1; }
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app reset >/dev/null
  tools/device.sh swipe 540 2330 540 1500 180; sleep 1.5
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app framestats > tools/shots/fs_close_$i.txt
  tools/device.sh log 8 | grep -A1 "nav\] home" | grep frames | sed 's/.*Launcher: *//'
  python tools/framestats.py tools/shots/fs_close_$i.txt LauncherCards
  python tools/framestats.py tools/shots/fs_close_$i.txt HomeActivity
done
