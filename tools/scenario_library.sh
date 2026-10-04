#!/usr/bin/env bash
# Opens Calculator from its App Library tile and closes it back into that icon, N times, printing frame logs and what the
# screen showed (framestats) for the launch and for the close. Every tap is gated on a checked state (see CLAUDE.md).
#   DEVICE=<serial> tools/scenario_library.sh [N]
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(-s "$DEVICE")
focus() { "$ADB" "${D[@]}" shell dumpsys window | grep mCurrentFocus; }
for i in $(seq 1 "${1:-3}"); do
  focus | grep -q HomeActivity || { echo "home is not in front: stop"; exit 1; }
  tools/device.sh swipe 540 2330 540 1700 150; sleep 1        # page 1
  tools/device.sh swipe 900 1200 150 1200 250; sleep 1         # the App Library
  tools/device.sh shot gate >/dev/null
  # Wherever the library shows Calculator now (it re-sorts by use): template tools/shots/tpl_calc.png, cut once with
  # tools/find_icon.py --cut from a screenshot.
  xy=$(python tools/find_icon.py tools/shots/gate.png tools/shots/tpl_calc.png) || { echo "Calculator icon not found: stop"; exit 1; }
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app reset >/dev/null
  tools/device.sh tap $xy; sleep 2.5
  focus | grep -q popupcalculator || { echo "Calculator not in front: stop"; exit 1; }
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app framestats > tools/shots/fs_launch_$i.txt
  echo "launch $i: $(tools/device.sh log 8 | grep -A1 'nav\] launch' | grep frames | tail -1 | sed 's/.*Launcher: *//')"
  python tools/framestats.py tools/shots/fs_launch_$i.txt LauncherCards | grep -E "GPU ms|shown|frame #"
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app reset >/dev/null
  tools/device.sh swipe 540 2330 540 1500 180; sleep 1.5
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app framestats > tools/shots/fs_iconclose_$i.txt
  echo "close $i: $(tools/device.sh log 8 | grep -A1 'nav\] home' | grep frames | tail -1 | sed 's/.*Launcher: *//')"
  python tools/framestats.py tools/shots/fs_iconclose_$i.txt LauncherCards | grep -E "GPU ms|shown|frame #"
done
