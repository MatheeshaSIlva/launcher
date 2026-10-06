#!/usr/bin/env bash
# Screenshots of every surface in one appearance, for side-by-side checks of light and dark mode:
#   DEVICE=<serial> tools/appearance_tour.sh PREFIX
# Sets nothing itself (choose the appearance first). Leaves home on page 1. Taps are gated on home being in front.
# Shots: PREFIX_home, PREFIX_lib, PREFIX_list, PREFIX_folder, PREFIX_spot, PREFIX_menu, PREFIX_editmenu, PREFIX_gallery,
# and a contact sheet tools/shots/PREFIX_tour.png.
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(-s "$DEVICE")
P=${1:-tour}
home() { tools/device.sh swipe 540 2330 540 1700 150; sleep 1; }
need() { "$ADB" "${D[@]}" shell dumpsys window | grep mCurrentFocus | grep -q HomeActivity || { echo "home not in front"; exit 1; }; }
need; home; home
tools/device.sh shot ${P}_home >/dev/null
tools/device.sh swipe 900 1200 150 1200 250; sleep 1.2; tools/device.sh shot ${P}_lib >/dev/null
tools/device.sh swipe 540 900 540 1700 300; sleep 1.2; tools/device.sh shot ${P}_list >/dev/null
tools/device.sh tap 980 185; sleep 1
"$ADB" "${D[@]}" shell "input motionevent DOWN 540 1600; for y in 1550 1500 1450 1400 1350 1300 1250 1200 1150 1100; do input motionevent MOVE 540 \$y; done; sleep 0.5; input motionevent UP 540 1100"; sleep 1
tools/device.sh shot gate >/dev/null
xy=$(python tools/find_icon.py tools/shots/gate.png tools/shots/tpl_util.png 40 | tail -1) && tools/device.sh tap $xy; sleep 1.3
tools/device.sh shot ${P}_folder >/dev/null
home; home; need
tools/device.sh swipe 540 700 540 1500 200; sleep 1.3; tools/device.sh shot ${P}_spot >/dev/null
home; need
tools/device.sh long 180 2158 900; sleep 1.2; tools/device.sh shot ${P}_menu >/dev/null
tools/device.sh tap 540 300; sleep 1; need
tools/device.sh long 540 1300 900; sleep 1.3; tools/device.sh tap 150 163; sleep 1; tools/device.sh shot ${P}_editmenu >/dev/null
tools/device.sh tap 233 302; sleep 2; tools/device.sh shot ${P}_gallery >/dev/null
tools/device.sh tap 968 252; sleep 1; tools/device.sh tap 980 160; sleep 1; home
python -c "
from PIL import Image
fs=['home','lib','list','folder','spot','menu','editmenu','gallery']
ims=[Image.open('tools/shots/${P}_%s.png'%f).resize((270,585)) for f in fs]
o=Image.new('RGB',(270*4,585*2))
for i,im in enumerate(ims): o.paste(im,(270*(i%4),585*(i//4)))
o.save('tools/shots/${P}_tour.png')"
echo tools/shots/${P}_tour.png
