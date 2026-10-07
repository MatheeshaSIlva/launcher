#!/usr/bin/env bash
# Notification Center with a long list, measured from what the screen showed (framestats of the shade's window,
# LauncherStatusBar): opening, the collapsed stack fanning out, scrolling up and down (every platter's glass is drawn where it is, each frame), closing.
# Posts a dozen test notifications of our own text first (removed at the end); starts from home (two swipes up on the
# bar) and checks the focused window before each block of touches. Never taps or presses a platter: on the phone the
# list holds real notifications too, and a tap opens its app.
# PRIVACY: Notification Center shows the phone's own notifications: this script never screenshots or records it.
#   DEVICE=<serial> [N=3] tools/scenario_nc_scroll.sh
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(-s "$DEVICE")
N=${N:-3}
size=$("$ADB" "${D[@]}" shell wm size | tail -1 | awk '{print $NF}' | tr -d '\r')
W=${size%x*}; H=${size#*x}
focus() { "$ADB" "${D[@]}" shell dumpsys window | grep mCurrentFocus; }
stop() { echo "STOP at $1: $(focus | sed 's/.*u0 //')"; cleanup; exit 1; }
need() { focus | grep -q "$1" || stop "$2"; }
reset() { "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app reset >/dev/null; }
measure() {   # $1 = step name
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app framestats > "tools/shots/fs_n_$1.txt"
  python tools/framestats.py "tools/shots/fs_n_$1.txt" LauncherStatusBar | python -c "
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
post() {
  i=40
  for t in Delivery Reminder Maya Sam Calendar Weather News Bank Gym Podcast Alex Photos; do
    "$ADB" "${D[@]}" shell am broadcast -a dev.launcher.app.TEST_NOTIFY -p dev.launcher.app --es title "$t" \
      --es text "'Launcher test notification for the scroll scenario, long enough for two lines on a platter.'" --ei id $i >/dev/null
    i=$((i+1))
  done
}
cleanup() {
  for i in $(seq 40 51); do "$ADB" "${D[@]}" shell am broadcast -a dev.launcher.app.TEST_NOTIFY -p dev.launcher.app --ei id $i --ez cancel true >/dev/null; done
}
bar_up() { tools/device.sh swipe $((W/2)) $((H-12)) $((W/2)) $((H*72/100)) 150; }
home() { need HomeActivity "home"; bar_up; sleep 1; bar_up; sleep 1; need HomeActivity "home after reset"; }
pull_nc() { tools/device.sh swipe $((W*15/100)) 30 $((W*15/100)) $((H*75/100)) 300; }
# Scrolls on the list's lower half (never the top band: that is the clock).
scroll_up() { tools/device.sh swipe $((W/2)) $((H*80/100)) $((W/2)) $((H*45/100)) 400; }
scroll_down() { tools/device.sh swipe $((W/2)) $((H*45/100)) $((W/2)) $((H*80/100)) 400; }

need HomeActivity "start"
post
sleep 10   # the banners pass
for i in $(seq 1 "$N"); do
  echo "== run $i"
  home
  reset; pull_nc; sleep 1.4; measure nc_open
  need LauncherStatusBar "nc open"
  # It opens collapsed: fanned out by the test hook, never by a tap (the front notification may be a real one).
  reset; "$ADB" "${D[@]}" shell am broadcast -a dev.launcher.app.TEST_SHADE -p dev.launcher.app --es do nc_expand >/dev/null
  sleep 1.4; measure nc_expand
  need LauncherStatusBar "nc expanded"
  reset; scroll_up; sleep 1.6; measure scroll_up
  need LauncherStatusBar "after scroll up"
  reset; scroll_down; sleep 1.6; measure scroll_down
  need LauncherStatusBar "after scroll down"
  reset; scroll_up; sleep 0.2; scroll_up; sleep 1.6; measure scroll_up_twice
  need LauncherStatusBar "after scroll up twice"
  reset; bar_up; sleep 1.4; measure nc_close
done
cleanup
