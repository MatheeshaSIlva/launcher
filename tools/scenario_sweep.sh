#!/usr/bin/env bash
# The test run's sweep (docs/TEST_RUN.md): every animation of the launcher once, measured from what the screen showed
# (framestats: frames shown, missed refreshes, GPU p90/max per window). Run it right after an install (what an UPDATE
# leaves: nothing warmed) and again (warm). Every tap waits for a checked state (the focused window, the keyboard, an icon
# found on a fresh screenshot). Nothing is recorded or captured but home and our own screens (templates); the shade and
# the App Switcher are measured by frame stats only.
#   DEVICE=<serial> tools/scenario_sweep.sh [SECTION...]     sections: home launch switcher shade settings (default: all)
# Templates (cut on this phone, tools/shots/): tpl_s_calc.png (Calculator in the library's Utilities cluster, the library
# dragged up ~500 px), tpl_s_util.png (that cluster's small icons: opens the folder), tpl_f_clock.png and tpl_f_set.png
# (Clock and Launcher settings inside the open folder).
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(-s "$DEVICE")
CALC=com.sec.android.app.popupcalculator
CLOCK=com.sec.android.app.clockpackage
OUT="tools/shots/sweep"
mkdir -p "$OUT"
focus() { "$ADB" "${D[@]}" shell dumpsys window | grep mCurrentFocus; }
stop() { echo "STOP at $1: $(focus | sed 's/.*u0 //')"; exit 1; }
need() { focus | grep -q "$1" || stop "$2"; }
reset() { "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app reset >/dev/null; }
measure() {   # $1 = step name
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app framestats > "$OUT/$1.txt"
  for w in LauncherCards HomeActivity LauncherStatusBar SettingsActivity; do
    python tools/framestats.py "$OUT/$1.txt" "$w" | python -c "
import sys, re
t = sys.stdin.read()
n = re.search(r'shown: (\d+) frames.*?missed refreshes ~(\d+)', t)
g = re.search(r'GPU ms: median ([\d.]+)\s+p90 ([\d.]+)\s+max ([\d.]+)', t)
c = re.search(r'CPU ms: median ([\d.]+)\s+p90 ([\d.]+)\s+max ([\d.]+)', t)
if n and int(n.group(1)) > 3:
    print(f'  {\"$1\":24s} {\"$w\":17s} shown {n.group(1):>4s}  missed {n.group(2):>2s}  GPU p90 {g.group(2):>5s} max {g.group(3):>6s}  CPU max {c.group(3):>6s}')"
  done
}
tap() { "$ADB" "${D[@]}" shell input tap "$1" "$2"; }
bar_up() { "$ADB" "${D[@]}" shell input swipe 540 2330 540 1700 150; }
home_p1() { need HomeActivity "home"; bar_up; sleep 1; need HomeActivity "home"; bar_up; sleep 1.2; }
to_library() { need HomeActivity "to library"; "$ADB" "${D[@]}" shell input swipe 900 1200 150 1200 250; sleep 1.2; }
slow_up() {   # drags the library up ~500 px with no fling
  "$ADB" "${D[@]}" shell "input motionevent DOWN 540 1600; for y in 1550 1500 1450 1400 1350 1300 1250 1200 1150 1100; do input motionevent MOVE 540 \$y; done; sleep 0.5; input motionevent UP 540 1100"
}
find_tap() {   # $1 = template, $2 = step, $3 = window that must hold the focus; taps it if found on a fresh screenshot
  need "$3" "$2 (before the shot)"
  "$ADB" "${D[@]}" exec-out screencap -p > tools/shots/gate.png
  local out; out=$(python tools/find_icon.py tools/shots/gate.png "tools/shots/$1" 2>/dev/null) || stop "$2 ($1 not found)"
  local xy; xy=$(echo "$out" | tail -1)
  need "$3" "$2 (before the tap)"
  tap $xy
}
open_utilities() {   # $1 = step: opens the library's Utilities folder (the library dragged up ~500 px; anchored on the
  # Games tile, which keeps its icons; Utilities reorders by use, so its small icons are tapped by place, right beside it)
  need HomeActivity "$1 (before the shot)"
  "$ADB" "${D[@]}" exec-out screencap -p > tools/shots/gate.png
  local out; out=$(python tools/find_icon.py tools/shots/gate.png tools/shots/tpl_s_games.png 2>/dev/null) || stop "$1 (Games tile not found)"
  local xy; xy=$(echo "$out" | tail -1)
  local gx=${xy% *}; local gy=${xy#* }
  # Utilities' small icons: left of the Games tile's first icon by 286 px, 217 px lower (S24, tile pitch).
  need HomeActivity "$1 (before the tap)"
  tap $((gx - 286)) $((gy + 217))
}
hold() {   # a short swipe up from the bar that rests: the App Switcher (checked in the log)
  local before; before=$(tools/device.sh log 400 | grep -c "\[switcher\] open")
  "$ADB" "${D[@]}" shell "input motionevent DOWN 540 2330; for y in 2310 2280 2240 2200 2160 2140 2130; do input motionevent MOVE 540 \$y; done; sleep 0.6; input motionevent UP 540 2130"
  sleep 1.2
  [ "$(tools/device.sh log 400 | grep -c "\[switcher\] open")" -gt "$before" ] || stop "switcher did not open"
}
keyboard() { "$ADB" "${D[@]}" shell dumpsys input_method | grep -q "mInputShown=true"; }
want() { [ $# -eq 0 ] || [[ " $SECTIONS " == *" $1 "* ]]; }
SECTIONS="$*"
[ -z "$SECTIONS" ] && SECTIONS="home launch switcher shade settings"
export DEVICE

if want home; then
  echo "== home's own motion"
  home_p1
  reset; to_library; measure page_to_library
  reset; slow_up; sleep 0.4; need HomeActivity scroll; "$ADB" "${D[@]}" shell input swipe 540 1800 540 700 120; sleep 1.5
  need HomeActivity scroll; "$ADB" "${D[@]}" shell input swipe 540 900 540 1300 120; sleep 1.5; measure library_scroll
  need HomeActivity "library back"; reset; "$ADB" "${D[@]}" shell input swipe 150 1200 900 1200 250; sleep 1.2; measure library_to_page_swipe
  to_library; need HomeActivity "library bar"; reset; bar_up; sleep 1.5; measure library_to_page_bar
  to_library; slow_up; sleep 0.8
  reset; open_utilities folder_open; sleep 1.2; measure folder_open
  need HomeActivity "folder close"; reset; bar_up; sleep 1.2; measure folder_close_bar
  need HomeActivity "library search"; reset; tap 540 216; sleep 1.2; keyboard || stop "library search keyboard"; measure library_search_open
  need HomeActivity "library search close"; reset; bar_up; sleep 1.5; measure library_search_close
  home_p1
  reset; need HomeActivity spotlight; "$ADB" "${D[@]}" shell input swipe 540 700 540 1500 200; sleep 1.2
  keyboard || stop "spotlight keyboard"; measure spotlight_open
  need HomeActivity "spotlight close"; reset; bar_up; sleep 1.5; measure spotlight_close
  home_p1
  reset; need HomeActivity "home pull"
  "$ADB" "${D[@]}" shell "input motionevent DOWN 540 2330; for y in 2300 2250 2200 2150 2100 2050 2000 1950 1900; do input motionevent MOVE 540 \$y; done; input motionevent UP 540 1880"
  sleep 1.2; measure home_pull
  home_p1
  reset; need HomeActivity "edit enter"; "$ADB" "${D[@]}" shell input swipe 540 1300 540 1300 900; sleep 1.5; measure edit_enter
  need HomeActivity "edit exit"; reset; bar_up; sleep 1.5; measure edit_exit
fi

if want launch; then
  echo "== launches and closes"
  home_p1; to_library; slow_up; sleep 0.8
  reset; find_tap tpl_s_calc.png calc_launch HomeActivity; sleep 2.2; need $CALC "Calculator"; measure calc_launch
  reset; bar_up; sleep 2; need HomeActivity "after calc close"; measure calc_close
  open_utilities folder; sleep 1.2
  reset; find_tap tpl_f_clock.png clock_launch HomeActivity; sleep 2.2; need $CLOCK "Clock from folder"; measure clock_launch_folder
  reset; bar_up; sleep 2; need HomeActivity "after clock close"; measure clock_close_folder
  need HomeActivity "folder still open"; bar_up; sleep 1.2
  home_p1; to_library; slow_up; sleep 0.8
  find_tap tpl_s_calc.png calc HomeActivity; sleep 2.2; need $CALC "Calculator again"
  reset; "$ADB" "${D[@]}" shell "input motionevent DOWN 120 2330; for x in 200 300 400 500 600 700 800 900; do input motionevent MOVE \$x 2330; done; input motionevent UP 950 2330"
  sleep 2; need $CLOCK "switch to Clock"; measure switch_sideways
  reset; "$ADB" "${D[@]}" shell "input motionevent DOWN 960 2330; for x in 880 780 680 580 480 380 280 180; do input motionevent MOVE \$x 2330; done; input motionevent UP 130 2330"
  sleep 2; need $CALC "switch back to Calculator"; measure switch_back
  reset; "$ADB" "${D[@]}" shell "input motionevent DOWN 540 2330; for y in 2300 2250 2200 2150 2100 2050 2000; do input motionevent MOVE 540 \$y; done; for y in 2050 2100 2150 2200 2250 2300 2330; do input motionevent MOVE 540 \$y; done; input motionevent UP 540 2330"
  sleep 1.5; need $CALC "after cancelled close"; measure close_cancel
  reset; bar_up; sleep 2; need HomeActivity "after close"; measure calc_close_2
  to_library; slow_up; sleep 0.8
  reset; find_tap tpl_s_calc.png grab HomeActivity; "$ADB" "${D[@]}" shell "input swipe 540 2330 540 1600 160"
  sleep 2; measure launch_grabbed
  focus | grep -q $CALC && { bar_up; sleep 2; }
  need HomeActivity "after grab"
fi

if want switcher; then
  echo "== App Switcher (frame stats only; Calculator and Clock newest)"
  home_p1
  reset; hold; measure switcher_open
  tools/device.sh log 40 | grep "\[switcher\] open from home" | tail -1 | grep -q "popupcalculator\|clockpackage" || stop "newest card is not Calculator or Clock"
  reset; "$ADB" "${D[@]}" shell input swipe 300 1200 900 1200 300; sleep 1; measure switcher_scroll
  reset; tap 540 260; sleep 1.5; need HomeActivity "home from switcher"; measure switcher_home
fi

if want shade; then
  echo "== shade (frame stats only)"
  home_p1
  reset; "$ADB" "${D[@]}" shell input swipe 900 10 900 900 250; sleep 1.2; measure cc_open
  reset; "$ADB" "${D[@]}" shell input swipe 540 2000 540 600 150; sleep 1.2; measure cc_close
  need HomeActivity "after cc"
  reset; "$ADB" "${D[@]}" shell input swipe 300 10 300 900 250; sleep 1.2; measure nc_open
  reset; "$ADB" "${D[@]}" shell input swipe 540 2000 540 600 150; sleep 1.2; measure nc_close
  need HomeActivity "after nc"
fi

if want settings; then
  echo "== settings"
  home_p1; to_library; slow_up; sleep 0.8
  open_utilities folder; sleep 1.2
  reset; find_tap tpl_f_set.png settings_launch HomeActivity; sleep 2.2; need SettingsActivity "settings from folder"; measure settings_launch
  reset; bar_up; sleep 2; need HomeActivity "after settings close"; measure settings_close_gesture
  need HomeActivity "folder still open"; bar_up; sleep 1.2
  home_p1
  reset; need HomeActivity "menu"; "$ADB" "${D[@]}" shell input swipe 540 1300 540 1300 900; sleep 1.5; need HomeActivity "edit"; tap 149 163; sleep 1.3; measure edit_menu_open
  need HomeActivity "home settings"; reset; tap 353 890; sleep 3; need SettingsActivity "home settings"; measure settings_from_home
  reset; "$ADB" "${D[@]}" shell input swipe 3 1300 500 1300 180; sleep 2.5; need HomeActivity "settings back"; measure settings_back_to_home
fi
echo done
