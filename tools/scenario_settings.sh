#!/usr/bin/env bash
# The settings app's own motion, from what the screen showed (framestats of the window SettingsActivity): opened, a tile's
# page grown out of its card and closed again (twice, two tiles), the tile list scrolled down and back, closed into home
# with the home gesture. Written for T-002 (the Compose upgrade), run by tools/compare_s24.sh.
#   DEVICE=<serial> tools/scenario_settings.sh
# Our own screens only, nothing captured. The only taps are on two tiles (Home at y 1350, App Library at y 1674: measured
# on a 1080-wide screen, 2026-11-06; the settings are laid out in points, 402 across, so the same on the S24): the chips
# above them (light and dark, colours, animations: y < 1060) would change the phone's look and are never touched. Before
# every gesture the focused window must be the one expected, else it stops (exit 1) and touches nothing more.
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(-s "$DEVICE")
focus() { "$ADB" "${D[@]}" shell dumpsys window | grep mCurrentFocus; }
need() { focus | grep -q "$1" || { echo "STOP at $2: $(focus | sed 's/.*u0 //')"; exit 1; }; }
reset() { "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app reset >/dev/null; }
measure() {   # $1 = step name
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app framestats > "tools/shots/fs_set_$1.txt"
  echo "$1: $(python tools/framestats.py "tools/shots/fs_set_$1.txt" SettingsActivity | grep -E 'shown|GPU ms' | tr '\n' ' ')"
}
tap() { "$ADB" "${D[@]}" shell input tap "$1" "$2"; }
swipe() { "$ADB" "${D[@]}" shell input swipe "$@"; }
back() { swipe 3 1300 500 1300 180; }

need HomeActivity "start (home must be in front)"
reset; "$ADB" "${D[@]}" shell am start -n dev.launcher.app/.settings.SettingsActivity >/dev/null; sleep 2.5
need SettingsActivity "settings open"; measure open
reset; tap 540 1350; sleep 1.6; need SettingsActivity "home tile"; measure page_open
reset; back; sleep 1.6; need SettingsActivity "home page back"; measure page_close
reset; swipe 540 1900 540 700 250; sleep 1.2; need SettingsActivity "scroll"; swipe 540 700 540 1900 250; sleep 1.6; measure scroll
need SettingsActivity "library tile"
reset; tap 540 1674; sleep 1.6; need SettingsActivity "library tile"; measure page2_open
reset; back; sleep 1.6; need SettingsActivity "library page back"; measure page2_close
reset; swipe 540 2330 540 1700 150; sleep 2.2; need HomeActivity "closed into home"; measure close
echo done
