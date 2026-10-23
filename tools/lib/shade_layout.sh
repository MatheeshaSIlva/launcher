#!/usr/bin/env bash
# Sourced by the scenario scripts whose touches are written for one shade layout (B-030, D-024).
#   require_shade_layout LAYOUT PLACEMENT      e.g. require_shade_layout ios split-top
# Reads the phone's shade layout and placement from the app's own files (a read-only export: DESIGN_RELOAD --es files
# export reloads nothing) and, unless they are exactly LAYOUT and PLACEMENT, or cannot be read, prints one line saying
# what was found, what the script needs and the command that switches it, and exits 2. It never switches the layout.
# Needs $ADB and the D=(-s "$DEVICE") array of the calling script. Call it before anything that touches the phone.
# (The export copies files on the launcher's main thread: a possible hitch just before a run; every measured step of the
# scripts resets gfxinfo first.)
SHADE_FILES=/sdcard/Android/data/dev.launcher.app/files/dev-out

# Reads a file of the export. `adb exec-out` has no shell protocol: the device command's stderr comes back on stdout (a
# missing file prints "cat: ...: No such file or directory", exit code 0), so the redirect is inside the device command.
shade_cat() {   # $1 = file name under dev-out
  MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" exec-out "cat $SHADE_FILES/$1 2>/dev/null"
}

# Succeeds when the export's marker is there: DevFiles writes its file count and a newline, nothing else counts.
shade_export_done() {
  [[ "$(shade_cat .done | tr -d '\r\n ')" =~ ^[0-9]+$ ]]
}

require_shade_layout() {   # $1 = layout, $2 = placement
  local want_layout="$1" want_placement="$2" found="" why="" i fresh=""
  local fix="adb shell am broadcast -a dev.launcher.app.SETUP -p dev.launcher.app --es element shade --es layout $want_layout --es placement $want_placement"
  # (MSYS_NO_PATHCONV per adb call: Git Bash would turn the /sdcard path into a Windows one, and not for the whole script)
  # An export from an earlier run does not count: remove its marker, then wait for a fresh one.
  MSYS_NO_PATHCONV=1 "$ADB" "${D[@]}" shell rm -f "$SHADE_FILES/.done" >/dev/null 2>&1
  if shade_export_done; then
    why="an old export could not be removed"
  else
    "$ADB" "${D[@]}" shell am broadcast -a dev.launcher.app.DESIGN_RELOAD -p dev.launcher.app --es files export >/dev/null 2>&1
    for i in $(seq 1 20); do
      shade_export_done && { fresh=1; break; }
      sleep 0.5
    done
    if [ -z "$fresh" ]; then
      why="the app did not finish exporting its files in 10 s (not running, or not answering)"
    else
      found=$(shade_cat setup.json | python -c "
import sys, json
try:
    s = json.loads(sys.stdin.read())['elements']['shade']
    print(s['layout'], s['placement'])
except Exception:
    pass" | tr -d '\r')
      [ -n "$found" ] || why="the shade's layout could not be read (the export holds no readable setup.json)"
    fi
  fi
  if [ -z "$why" ] && [ "$found" != "$want_layout $want_placement" ]; then
    why="the phone's shade is ${found% *} / ${found#* }"
  fi
  if [ -n "$why" ]; then
    local hint=""
    [ -n "$found" ] && hint=" To switch (changes the phone's setup): $fix"   # switching helps only when a layout was read
    echo "REFUSED (shade layout): $why; this script's touches need the shade layout $want_layout / $want_placement. Nothing was touched.$hint"
    exit 2
  fi
}
