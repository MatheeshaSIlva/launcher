#!/usr/bin/env bash
# Phone: an app opened from the dock and closed again in a pair of transition styles (motion/AppStyles), each step's frames
# measured (the card window's framestats: what the screen showed), optionally recorded. The styles go in through the design
# inbox as the preset's user edits, with KEEP (other tokens of the edits file, JSON members) kept in; put the phone's own
# file back afterwards (an export before, an import after). Every tap is gated on a checked state.
#   DEVICE=<serial> tools/s24_style.sh OPEN CLOSE NAME [calc|clock] [rec] [KEEP]
#   e.g. KEEP='"motion.adjust.speed":{"factor":0.85,"src":"user"}'
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(-s "$DEVICE")
open=$1; close=$2; name=$3; app=${4:-calc}; rec=${5:-}; keep=${6:-}
case $app in
  calc) x=180; y=2154; pkg=popupcalculator ;;
  clock) x=900; y=2154; pkg=clockpackage ;;
esac
focus() { "$ADB" "${D[@]}" shell dumpsys window | grep mCurrentFocus; }
keys() { "$ADB" "${D[@]}" shell dumpsys notification 2>/dev/null | grep -oE "^\s+key=[^ ]+" | sort -u | md5sum | cut -c1-12; }
f="$TEMP/motion-edits-ios27.json"
printf '{"format":1,"name":"user","tokens":{%s"motion.style.opening":{"choice":"%s","src":"user"},"motion.style.closing":{"choice":"%s","src":"user"}}}\n' \
  "${keep:+$keep,}" "$open" "$close" > "$f"
MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" shell mkdir -p /sdcard/Android/data/dev.launcher.app/files/dev-in/design
MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" push "$(cygpath -m "$f")" /sdcard/Android/data/dev.launcher.app/files/dev-in/design/motion-edits-ios27.json >/dev/null
"$ADB" "${D[@]}" shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p dev.launcher.app --es files import >/dev/null
sleep 4   # home's reveal of the new look
focus | grep -q HomeActivity || { echo "home is not in front: stop"; exit 1; }
before=$(keys)
[ -n "$rec" ] && tools/device.sh rec "$name" 10
"$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app reset >/dev/null
tools/device.sh tap $x $y; sleep 1.0
"$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app framestats > "tools/shots/fs_${name}_open.txt"
sleep 1.0
focus | grep -q "$pkg" || { echo "$pkg not in front: stop"; exit 1; }
"$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app reset >/dev/null
tools/device.sh swipe 540 2330 540 1500 180; sleep 1.0
"$ADB" "${D[@]}" shell dumpsys gfxinfo dev.launcher.app framestats > "tools/shots/fs_${name}_close.txt"
sleep 1.5
focus | grep -q HomeActivity || echo "home is not in front after the close"
if [ -n "$rec" ]; then
  sleep 2
  if [ "$(keys)" != "$before" ]; then
    # A notification came while recording: a banner may be on it. Deleted unviewed.
    MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" shell rm -f "/sdcard/$name.mp4"
    echo "RECORDING DISCARDED (a notification arrived)"
  else
    tools/device.sh recpull "$name" 1 >/dev/null 2>&1
  fi
fi
for s in open close; do
  echo "$s: $(python tools/framestats.py "tools/shots/fs_${name}_$s.txt" LauncherCards | grep -E "shown|GPU ms" | tr '\n' ' ' | tr -s ' ')"
done
tools/device.sh log 40 | grep -E "\[nav\] (launch|home|back)" | tail -2 | sed 's/.*Launcher: //'
