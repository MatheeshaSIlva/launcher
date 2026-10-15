#!/usr/bin/env bash
# Phone: a styled flight interrupted (motion/AppStyles), recorded. Styles as in s24_style.sh (KEEP: other tokens kept).
#   DEVICE=<serial> tools/s24_interrupt.sh OPEN CLOSE NAME MODE [KEEP]
#   MODE: grab    open Calculator, swipe up during the launch and let go (home)
#         reverse close Calculator, tap its icon during the close
#         other   close Calculator, open Clock during the close
cd "$(dirname "$0")/.."
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
D=(-s "$DEVICE")
open=$1; close=$2; name=$3; mode=$4; keep=${5:-}
focus() { "$ADB" "${D[@]}" shell dumpsys window | grep mCurrentFocus; }
keys() { "$ADB" "${D[@]}" shell dumpsys notification 2>/dev/null | grep -oE "^\s+key=[^ ]+" | sort -u | md5sum | cut -c1-12; }
f="$TEMP/motion-edits-ios27.json"
printf '{"format":1,"name":"user","tokens":{%s"motion.style.opening":{"choice":"%s","src":"user"},"motion.style.closing":{"choice":"%s","src":"user"}}}\n' \
  "${keep:+$keep,}" "$open" "$close" > "$f"
MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" push "$(cygpath -m "$f")" /sdcard/Android/data/dev.launcher.app/files/dev-in/design/motion-edits-ios27.json >/dev/null
"$ADB" "${D[@]}" shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p dev.launcher.app --es files import >/dev/null
sleep 4
focus | grep -q HomeActivity || { echo "home is not in front: stop"; exit 1; }
before=$(keys)
tools/device.sh rec "$name" 10
case $mode in
  grab)
    # One shell call: the swipe starts while the card still grows.
    "$ADB" "${D[@]}" shell "input tap 180 2154; sleep 0.12; input swipe 540 2330 540 1500 220"
    sleep 2.5 ;;
  reverse|other)
    tools/device.sh tap 180 2154; sleep 2.2
    focus | grep -q popupcalculator || { echo "Calculator not in front: stop"; exit 1; }
    tx=180; [ "$mode" = other ] && tx=900
    "$ADB" "${D[@]}" shell "input swipe 540 2330 540 1500 180; sleep 0.15; input tap $tx 2154"
    sleep 2.5 ;;
esac
sleep 4
if [ "$(keys)" != "$before" ]; then
  MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" shell rm -f "/sdcard/$name.mp4"
  echo "RECORDING DISCARDED (a notification arrived)"
else
  tools/device.sh recpull "$name" 1 >/dev/null 2>&1
fi
tools/device.sh log 60 | grep -E "\[nav\] (launch|home|back|took|still)|keeps landing|fades where|touched" | tail -5 | sed 's/.*Launcher: //'
echo "focus now: $(focus | sed 's/.*u0 //')"
