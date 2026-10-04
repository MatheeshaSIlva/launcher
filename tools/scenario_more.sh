#!/usr/bin/env bash
# Every animation the other scenarios do not cover, measured from what the screen showed (framestats): home's own motion
# (page <-> App Library, library scroll, folder open/close, Spotlight, the pull on home), launches and closes from a library
# folder and from the dock, a launch grabbed midway, a cancelled close, the sideways switch from home, a long App Switcher
# deck and a card flicked away. One line per step and window: frames shown, missed refreshes, GPU p90/max.
# Every tap is gated on a checked state (focused window, keyboard, or an icon found on a screenshot). REC=1 also records
# the steps that show only our UI and Calculator/Clock (never the switcher: it shows other apps' pictures).
#   DEVICE=<serial> [REC=1] tools/scenario_more.sh
# Needs templates cut on this phone: tools/shots/tpl_calc.png (Calculator tile), tpl_util.png (Utilities cluster in the
# library, scrolled as below), tpl_fclock.png (Clock inside the open Utilities folder), tpl_dev.png (DevActivity in the dock).
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(-s "$DEVICE")
CALC=com.sec.android.app.popupcalculator
CLOCK=com.sec.android.app.clockpackage
focus() { "$ADB" "${D[@]}" shell dumpsys window | grep mCurrentFocus; }
stop() { echo "STOP at $1: $(focus | sed 's/.*u0 //')"; exit 1; }
need() { focus | grep -q "$1" || stop "$2"; }
reset() { "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app reset >/dev/null; }
rec() { [ -n "$REC" ] && tools/device.sh rec "m_$1" "${2:-4}"; true; }
recpull() { [ -n "$REC" ] && tools/device.sh recpull "m_$1" >/dev/null; true; }
measure() {   # $1 = step name
  "$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app framestats > "tools/shots/fs_m_$1.txt"
  for w in LauncherCards HomeActivity; do
    python tools/framestats.py "tools/shots/fs_m_$1.txt" "$w" | python -c "
import sys, re
t = sys.stdin.read()
n = re.search(r'shown: (\d+) frames.*?missed refreshes ~(\d+)', t)
g = re.search(r'GPU ms: median ([\d.]+)\s+p90 ([\d.]+)\s+max ([\d.]+)', t)
if n and int(n.group(1)) > 3:
    print(f'  {\"$1\":22s} {\"$w\":14s} shown {n.group(1):>4s}  missed {n.group(2):>2s}  GPU med {g.group(1)} p90 {g.group(2)} max {g.group(3)}')"
  done
}
bar_up() { tools/device.sh swipe 540 2330 540 1700 150; }
home_p1() { need HomeActivity "home"; bar_up; sleep 1; bar_up; sleep 1; }
to_library() { tools/device.sh swipe 900 1200 150 1200 250; sleep 1; }
slow_up() {   # drags the library up ~500 px with no fling
  "$ADB" "${D[@]}" shell "input motionevent DOWN 540 1600; for y in 1550 1500 1450 1400 1350 1300 1250 1200 1150 1100; do input motionevent MOVE 540 \$y; done; sleep 0.5; input motionevent UP 540 1100"
}
find_tap() {   # $1 = template, $2 = step; taps it if found on a fresh screenshot
  tools/device.sh shot gate >/dev/null
  local xy; xy=$(python tools/find_icon.py tools/shots/gate.png "tools/shots/$1" 2>/dev/null) || stop "$2 ($1 not found)"
  tools/device.sh tap $xy
}
hold() {   # a short swipe up from the bar that rests: the App Switcher (checked in the log)
  local before; before=$(tools/device.sh log 400 | grep -c "\[switcher\] open")
  "$ADB" "${D[@]}" shell "input motionevent DOWN 540 2330; for y in 2310 2280 2240 2200 2160 2140 2130; do input motionevent MOVE 540 \$y; done; sleep 0.6; input motionevent UP 540 2130"
  sleep 1.2
  [ "$(tools/device.sh log 400 | grep -c "\[switcher\] open")" -gt "$before" ] || stop "switcher did not open"
}

echo "== home's own motion"
home_p1
rec page_lib 3; reset; to_library; sleep 0.5; measure page_to_library; recpull page_lib
reset; slow_up; sleep 0.4; tools/device.sh swipe 540 1800 540 700 120; sleep 1.5; tools/device.sh swipe 540 900 540 1300 120; sleep 1.5; measure library_scroll
bar_up; sleep 1.2; need HomeActivity "after scroll"; to_library
rec list 4; reset; tools/device.sh swipe 540 900 540 1700 300; sleep 1.2; measure library_list_open
reset; bar_up; sleep 1.5; measure library_list_close; recpull list
need HomeActivity "after list"; bar_up; sleep 1; to_library
slow_up; sleep 0.8
rec folder 3; reset; find_tap tpl_util.png folder_open; sleep 1.2; measure folder_open; recpull folder
reset; bar_up; sleep 1.2; measure folder_close_bar
rec lib_p1 3; reset; bar_up; sleep 1.5; measure library_to_page1_bar; recpull lib_p1
need HomeActivity "after library"; to_library
reset; tools/device.sh swipe 150 1200 900 1200 250; sleep 1.2; measure library_to_page1_swipe
need HomeActivity "page 1"
rec spot 4; reset; tools/device.sh swipe 540 700 540 1500 200; sleep 1.2
"$ADB" "${D[@]}" shell dumpsys input_method | grep -q "mInputShown=true" || stop "spotlight keyboard"
measure spotlight_open
reset; bar_up; sleep 1.5; measure spotlight_close; recpull spot
need HomeActivity "after spotlight"
rec pull 3; reset
"$ADB" "${D[@]}" shell "input motionevent DOWN 540 2330; for y in 2300 2250 2200 2150 2100 2050 2000 1950 1900; do input motionevent MOVE 540 \$y; done; input motionevent UP 540 1880"
sleep 1.2; measure home_pull; recpull pull

echo "== launches and closes"
home_p1; to_library; slow_up; sleep 0.8; find_tap tpl_util.png folder; sleep 1.2
rec fapp 6; reset; find_tap tpl_fclock.png folder_launch; sleep 2.2
need $CLOCK "Clock from folder"; measure folder_launch
reset; bar_up; sleep 2; measure folder_close; recpull fapp
need HomeActivity "after folder close"; bar_up; sleep 1   # closes the folder
home_p1
rec dock 6; reset; find_tap tpl_dev.png dock; sleep 2
need DevActivity "dock launch"; measure dock_launch
reset; bar_up; sleep 2; measure dock_close; recpull dock
need HomeActivity "after dock close"
to_library
rec grab 4; reset; find_tap tpl_calc.png grab
"$ADB" "${D[@]}" shell "input swipe 540 2330 540 1600 160"
sleep 2; measure grab_launch; recpull grab
focus | grep -q $CALC && { bar_up; sleep 2; }
need HomeActivity "after grab"
home_p1; to_library; find_tap tpl_calc.png calc; sleep 2.2; need $CALC "Calculator"
rec cancel 4; reset
"$ADB" "${D[@]}" shell "input motionevent DOWN 540 2330; for y in 2300 2250 2200 2150 2100 2050 2000; do input motionevent MOVE 540 \$y; done; for y in 2050 2100 2150 2200 2250 2300 2330; do input motionevent MOVE 540 \$y; done; input motionevent UP 540 2330"
sleep 1.5; need $CALC "after cancelled close"; measure close_cancel; recpull cancel
bar_up; sleep 2; need HomeActivity "after close"
rec hside 5; reset
"$ADB" "${D[@]}" shell "input motionevent DOWN 120 2330; for x in 200 300 400 500 600 700 800 900; do input motionevent MOVE \$x 2330; done; input motionevent UP 950 2330"
sleep 2; need $CALC "sideways from home"; measure home_sideways; recpull hside
bar_up; sleep 2; need HomeActivity "after sideways close"

echo "== App Switcher (frame stats only)"
home_p1
hold; reset; for k in 1 2 3 4; do tools/device.sh swipe 200 1200 1000 1200 300; sleep 0.4; done; sleep 1; measure long_deck_scroll
reset; tools/device.sh tap 540 260; sleep 1.5; need HomeActivity "home from long deck"; measure long_deck_home
hold
tools/device.sh log 30 | grep "\[switcher\] open from home" | tail -1 | grep -q "(popupcalculator" || stop "newest card is not Calculator"
reset; "$ADB" "${D[@]}" shell "input motionevent DOWN 610 1300; input motionevent MOVE 610 1200; input motionevent MOVE 610 1000; input motionevent MOVE 610 700; input motionevent UP 610 500"
sleep 1.5; measure flick_away
reset; tools/device.sh tap 540 260; sleep 1.5; need HomeActivity "home after flick"; measure flick_then_home
echo done
