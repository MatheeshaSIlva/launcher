#!/usr/bin/env bash
# The shade's animations on home, measured from what the screen showed (framestats of its window, LauncherStatusBar):
# Control Center pulled open and closed (tap on empty space, swipe up from the bar), edit mode in and out, Notification
# Center pulled open and closed, and a banner (an adb test notification, our own text) coming in and leaving.
# One line per step: frames shown, missed refreshes, GPU p90/max. Starts from home (two swipes up on the bar) and checks
# the focused window before each block of touches.
# PRIVACY: Notification Center and banners show the phone's own notifications: this script never screenshots or records
# them (SHOT=1 only takes Control Center, which shows no messages).
#   DEVICE=<serial> [N=3] [SHOT=1] tools/scenario_shade.sh
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(-s "$DEVICE")
N=${N:-3}
size=$("$ADB" "${D[@]}" shell wm size | tail -1 | awk '{print $NF}' | tr -d '\r')
W=${size%x*}; H=${size#*x}
focus() { "$ADB" "${D[@]}" shell dumpsys window | grep mCurrentFocus; }
stop() { echo "STOP at $1: $(focus | sed 's/.*u0 //')"; exit 1; }
need() { focus | grep -q "$1" || stop "$2"; }
reset() { "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app reset >/dev/null; }
measure() {   # $1 = step name
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app framestats > "tools/shots/fs_s_$1.txt"
  python tools/framestats.py "tools/shots/fs_s_$1.txt" LauncherStatusBar | python -c "
import sys, re
t = sys.stdin.read()
n = re.search(r'shown: (\d+) frames.*?missed refreshes ~(\d+)', t)
g = re.search(r'GPU ms: median ([\d.]+)\s+p90 ([\d.]+)\s+max ([\d.]+)', t)
c = re.search(r'CPU ms: median ([\d.]+)\s+p90 ([\d.]+)\s+max ([\d.]+)', t)
if n:
    print(f'  {\"$1\":20s} shown {n.group(1):>4s}  missed {n.group(2):>2s}  GPU med {g.group(1) if g else \"?\"} p90 {g.group(2) if g else \"?\"} max {g.group(3) if g else \"?\"}  CPU p90 {c.group(2) if c else \"?\"}')
else:
    print('  $1: no frames')"
}
bar_up() { tools/device.sh swipe $((W/2)) $((H-12)) $((W/2)) $((H*72/100)) 150; }
home() { need HomeActivity "home"; bar_up; sleep 1; bar_up; sleep 1; need HomeActivity "home after reset"; }
pull_cc() { tools/device.sh swipe $((W*85/100)) 30 $((W*85/100)) $((H*45/100)) 260; }
pull_nc() { tools/device.sh swipe $((W*15/100)) 30 $((W*15/100)) $((H*75/100)) 300; }
empty_tap() { tools/device.sh tap $((W/2)) $((H*85/100)); }

for i in $(seq 1 "$N"); do
  echo "== run $i"
  home
  reset; pull_cc; sleep 1.2; measure cc_open
  [ -n "$SHOT" ] && [ "$i" = 1 ] && tools/device.sh shot shade_cc >/dev/null
  reset; empty_tap; sleep 1.2; measure cc_close_tap
  need HomeActivity "after cc"
  reset; pull_cc; sleep 1.2; tools/device.sh tap $((W*128/1000)) $((H*38/1000)); sleep 1.2; measure cc_edit_in
  [ -n "$SHOT" ] && [ "$i" = 1 ] && tools/device.sh shot shade_cc_edit >/dev/null
  reset; empty_tap; sleep 1.2; measure cc_edit_out
  reset; bar_up; sleep 1.2; measure cc_close_bar
  need HomeActivity "after edit"
  reset; pull_nc; sleep 1.4; measure nc_open
  reset; bar_up; sleep 1.4; measure nc_close_bar
  need HomeActivity "after nc"
  reset
  "$ADB" "${D[@]}" shell am broadcast -a dev.launcher.app.TEST_NOTIFY -p dev.launcher.app --es title "Launcher test" --es text "'A test banner from the shade scenario.'" --ei id 9 >/dev/null
  sleep 7; measure banner
done
"$ADB" "${D[@]}" shell am broadcast -a dev.launcher.app.TEST_NOTIFY -p dev.launcher.app --ei id 9 --ez cancel true >/dev/null
