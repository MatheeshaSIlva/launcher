#!/usr/bin/env bash
# Slow motion FACTOR (1 = off) for our springs (the motion.debug.slow token, as an edit of the motion preset PRESET,
# default ios27: files/design/motion-edits-PRESET.json, where motion edits live; theme edits in user.json do not reach
# motion) and the system's animators; applied at once (DESIGN_RELOAD). DEVICE (default emulator-5554). Replaces the
# preset's other motion edits: emulator only. The build is not debuggable (no run-as): the file goes in through the app's
# external folder (DESIGN_RELOAD --es files import, design/DevFiles.kt).
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
D=(-s "${DEVICE:-emulator-5554}")
F=${1:-1}
REL="design/motion-edits-${PRESET:-ios27}.json"
EXT=/sdcard/Android/data/dev.launcher.app/files
if [ "$F" = 1 ]; then
  MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p dev.launcher.app --es files remove --es path "$REL" >/dev/null
else
  tmp=$(mktemp)
  printf '{"format":1,"name":"user","tokens":{"motion.debug.slow":{"factor":%s,"src":"judged:audit"}}}' "$F" > "$tmp"
  MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" push "$(cygpath -m "$tmp")" "$EXT/dev-in/$REL" >/dev/null
  rm -f "$tmp"
  MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p dev.launcher.app --es files import >/dev/null
fi
"$ADB" "${D[@]}" shell settings put global animator_duration_scale $F
