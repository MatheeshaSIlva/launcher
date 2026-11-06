#!/usr/bin/env bash
# Two stamped builds compared on the S24, interleaved (ref, new, ref, new, ...), from what the screen showed (framestats).
# The generalised form of tools/e1_s24.sh (E1's exit): any two builds, chosen scenarios, every pass of every scenario kept
# (e1_s24's summary held only the shade scenario's last pass: it overwrote its framestats file per pass).
#
#   DEVICE=<the S24's serial> NAME=<run name> [ROUNDS=2] [SCENARIOS="switch shade settings"] tools/compare_s24.sh
#
# Builds: tools/shots/<NAME>/apks/{ref,new}/app-debug.apk + app-debug.dm, built with -PbuildSha=<full sha> (so each logs
# "BUILD <sha7>" and one build's Shizuku service is never reused by the other: its version is the stamp's hash), their
# md5s in apks/md5.txt and their stamps in apks/{ref,new}/sha, all checked before the first touch.
# Scenarios: switch = tools/scenario_switch.sh 2 (window LauncherCards); shade = tools/scenario_shade.sh, one pass at a time
# (N=1, PASSES times; window LauncherStatusBar); settings = tools/scenario_settings.sh (window SettingsActivity).
#
# Before every install and every scenario (stops at the first that fails, says why, touches nothing more):
#   - the device is online, awake and not locked;
#   - nobody is using it: the last user activity is >= 120 s ago, or it is our own last scripted touch: a touch of the
#     owner's after that stops the run;
#   - the focused window is dev.launcher.app's.
# Each install: tools/device.sh install with the staged APK and .dm (our home set as the default), then the HOME intent;
# never a force-stop.
# Then it waits for "BUILD <sha7>", Shizuku connected and the gesture strip, and checks speed-profile from install-dm.
# Known limit (T-002, Codex's cross-check): without root an `adb shell input` counts as user activity like a person's touch,
# so a touch of the owner's during a scenario cannot be told from ours by the idle check; the focus check before every
# gesture is what stops a scenario when someone takes the phone. Run it only with the owner's go and while he is away.
# Temperatures and CPU limits are logged before each run. Output: tools/shots/<NAME>/run-<time>/ (run.log, each
# scenario's output and framestats files, summary.txt: per animation and run the missed refreshes summed over its passes
# and the worst GPU/CPU p90 of its passes, and each build's mean). No screenshot or recording is taken.
set -u
cd "$(dirname "$0")/.." || exit 2
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"; [ -x "$ADB" ] || ADB=adb
[ -n "${DEVICE:-}" ] && [ -n "${NAME:-}" ] || { echo "REFUSED: DEVICE=<serial> NAME=<run name> tools/compare_s24.sh"; exit 2; }
case "$DEVICE" in emulator-*) echo "REFUSED: this run is for the S24 (DEVICE=$DEVICE)"; exit 2 ;; esac
export DEVICE ADB
PKG=dev.launcher.app
APKS=tools/shots/$NAME/apks
RUN=tools/shots/$NAME/run-$(date +%Y%m%d-%H%M%S)
ROUNDS=${ROUNDS:-2}
SCENARIOS=${SCENARIOS:-"switch shade settings"}
PASSES=${PASSES:-3}
IDLE_MS=120000
ORDER=""; for _ in $(seq 1 "$ROUNDS"); do ORDER="$ORDER ref new"; done
mkdir -p "$RUN"
OURS_END=0   # device uptime (ms) when our last scripted touch ended; 0: none yet

a() { "$ADB" -s "$DEVICE" "$@"; }
say() { echo "$(date +%T) $*" | tee -a "$RUN/run.log"; }
sha_of() { tr -d '\r\n ' < "$APKS/$1/sha" | cut -c1-7; }
installed() { a shell dumpsys package $PKG | tr -d '\r' | sed -n 's/.*versionName=//p' | head -1; }
stop() { say "STOP: $*"; say "the phone is left on BUILD $(installed)"; exit 1; }
uptime_ms() { a shell cat /proc/uptime | tr -d '\r' | awk '{printf "%d", $1 * 1000}'; }

ready() {   # $1 = what comes next
  [ "$(a get-state 2>/dev/null | tr -d '\r')" = device ] || stop "before $1: $DEVICE is not online"
  a shell dumpsys power | tr -d '\r' | grep -q "mWakefulness=Awake" || stop "before $1: the screen is off"
  a shell dumpsys window | tr -d '\r' | grep -q "isKeyguardShowing=false" || stop "before $1: the phone is locked"
  local line at ago
  line=$(a shell dumpsys power | tr -d '\r' | grep -m1 -E "^ *lastUserActivityTime=")
  at=$(echo "$line" | sed -n 's/.*lastUserActivityTime=\([0-9]*\).*/\1/p')
  ago=$(echo "$line" | sed -n 's/.*(\([0-9]*\) ms ago).*/\1/p')
  [ -n "$at" ] && [ -n "$ago" ] || stop "before $1: could not read the last user activity ($line)"
  if [ "$ago" -lt "$IDLE_MS" ] && [ "$at" -gt $((OURS_END + 1000)) ]; then
    stop "before $1: someone used the phone $((ago / 1000)) s ago (needs ${IDLE_MS} ms untouched, or no touch since ours)"
  fi
  a shell dumpsys window | tr -d '\r' | grep mCurrentFocus | grep -q "$PKG/" \
    || stop "before $1: the focused window is not ours ($(a shell dumpsys window | tr -d '\r' | grep mCurrentFocus | sed 's/.*u0 //'))"
}

conditions() {
  say "temperatures (dumpsys thermalservice, current from HAL):"
  a shell dumpsys thermalservice | tr -d '\r' | sed -n '/Current temperatures from HAL/,/Current cooling devices/p' \
    | grep "Temperature{" | sed 's/^[[:space:]]*/    /' | tee -a "$RUN/run.log"
  say "CPU max frequencies (kHz): $(a shell 'cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq' | tr -d '\r' | tr '\n' ' ')"
}

install_build() {   # $1 = ref | new
  local b=$1 sha i pid log
  sha=$(sha_of "$b")
  ready "installing $b ($sha)"
  say "install $b: $APKS/$b/app-debug.apk + .dm ($(du -k "$APKS/$b/app-debug.apk" | cut -f1) KB)"
  # The required install route (AGENTS.md): tools/device.sh install, given the staged APK and .dm.
  tools/device.sh install "$APKS/$b/app-debug.apk" "$APKS/$b/app-debug.dm" 2>&1 | tail -1 | tee -a "$RUN/run.log" \
    | grep -q Success || stop "the install of $b failed"
  a shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -p $PKG >/dev/null
  [ "$(installed)" = "$sha" ] || stop "after installing $b: the phone reports BUILD $(installed), not $sha"
  for i in $(seq 1 90); do
    pid=$(a shell pidof $PKG | tr -d '\r' | awk '{print $1}')
    if [ -n "$pid" ]; then
      log=$(a logcat -d --pid="$pid" | tr -d '\r')
      if echo "$log" | grep -q "FATAL EXCEPTION"; then
        sleep 2; a shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -p $PKG >/dev/null; continue
      fi
      if echo "$log" | grep -q "Launcher: BUILD $sha" && echo "$log" | grep -q "\[shizuku\] service connected: true" \
        && echo "$log" | grep -q "\[nav\] gesture strip on"; then
        echo "$log" | grep -E "Launcher: (BUILD|\[shizuku\] service connected|\[nav\] gesture strip on)" | tee -a "$RUN/run.log"
        break
      fi
    fi
    sleep 1
  done
  [ "$i" -lt 90 ] || stop "after installing $b: no BUILD $sha + Shizuku connected + gesture strip in 90 s"
  a shell pm art dump $PKG | tr -d '\r' | grep -E "status=" | tee -a "$RUN/run.log" \
    | grep -q "\[status=speed-profile\] \[reason=install-dm\]" || stop "after installing $b: not speed-profile from install-dm"
  a shell am start -a android.intent.action.MAIN -c android.intent.category.HOME -p $PKG >/dev/null
  sleep 3
}

one() {   # $1 = run dir, $2 = scenario, $3 = pass label (suffix for the copied framestats files)
  local dir=$1 sc=$2 pass=$3 rc
  ready "scenario $sc ($(basename "$dir"), pass $pass)"
  rm -f tools/shots/fs_switch_*.txt tools/shots/fs_s_*.txt tools/shots/fs_set_*.txt
  say "scenario $sc ($(basename "$dir"), pass $pass)"
  case "$sc" in
    switch) tools/scenario_switch.sh 2 > "$dir/$sc-$pass.txt" 2>&1; rc=$? ;;
    shade) N=1 tools/scenario_shade.sh > "$dir/$sc-$pass.txt" 2>&1; rc=$? ;;
    settings) tools/scenario_settings.sh > "$dir/$sc-$pass.txt" 2>&1; rc=$? ;;
    *) stop "unknown scenario $sc" ;;
  esac
  OURS_END=$(uptime_ms)
  for f in tools/shots/fs_switch_*.txt tools/shots/fs_s_*.txt tools/shots/fs_set_*.txt; do
    [ -f "$f" ] && cp "$f" "$dir/$(basename "${f%.txt}")@$pass.txt"
  done
  [ "$rc" -eq 0 ] || stop "scenario $sc ($(basename "$dir"), pass $pass) ended with exit $rc (see $dir/$sc-$pass.txt)"
  sleep 3
}

# ------------------------------------------------------------------------------------------------- before any touch
(cd "$APKS" && md5sum -c md5.txt >/dev/null 2>&1) || { echo "REFUSED: the APKs do not match $APKS/md5.txt"; exit 2; }
[ -n "$(sha_of ref)" ] && [ -n "$(sha_of new)" ] || { echo "REFUSED: $APKS/{ref,new}/sha missing"; exit 2; }
say "$NAME on $DEVICE: ref $(sha_of ref), new $(sha_of new); order$ORDER; scenarios $SCENARIOS; results in $RUN"
say "phone now on BUILD $(installed)"
n=0
for b in $ORDER; do
  n=$((n + 1)); label="$n-$b"; dir="$RUN/$label"; mkdir -p "$dir"
  say "== run $label ($(sha_of "$b"))"
  conditions
  install_build "$b"
  for sc in $SCENARIOS; do
    if [ "$sc" = shade ]; then for p in $(seq 1 "$PASSES"); do one "$dir" shade "$p"; done; else one "$dir" "$sc" 1; fi
  done
done
say "done: the phone is on BUILD $(installed)"

# ------------------------------------------------------------------------------------------------- summary
python - "$RUN" "$ORDER" <<'PY' | tee "$RUN/summary.txt"
import os, re, subprocess, sys
run, order = sys.argv[1], sys.argv[2].split()
labels = [f"{i + 1}-{b}" for i, b in enumerate(order)]
def window(name):
    return "LauncherCards" if name.startswith("switch") else "SettingsActivity" if name.startswith("set_") else "LauncherStatusBar"
def stats(path, w):
    t = subprocess.run([sys.executable, "tools/framestats.py", path, w], capture_output=True, text=True).stdout
    n = re.search(r"shown: (\d+) frames.*?missed refreshes ~(\d+)", t, re.S)
    g = re.search(r"GPU ms: median ([\d.]+)\s+p90 ([\d.]+)", t)
    c = re.search(r"CPU ms: median ([\d.]+)\s+p90 ([\d.]+)", t)
    if not n or not g or not c: return None
    return (int(n.group(2)), float(g.group(2)), float(c.group(2)))
steps = {}
gaps = []
for lab in labels:
    d = os.path.join(run, lab)
    for f in sorted(os.listdir(d)) if os.path.isdir(d) else []:
        if not f.startswith("fs_"): continue
        step = f[3:-4].split("@")[0]
        s = stats(os.path.join(d, f), window(step))
        steps.setdefault(step, {}).setdefault(lab, [])
        if s: steps[step][lab].append(s)
        else: gaps.append(f"{lab}: {f} (no frames, or no GPU/CPU values, for {window(step)})")
print("missed refreshes (summed over passes) / worst GPU p90 ms / worst CPU p90 ms per animation, from framestats")
print("runs: " + ", ".join(labels))
print(f"{'animation':24s}" + "".join(f"{l:>20s}" for l in labels) + f"{'ref miss/pass':>15s}{'new miss/pass':>15s}")
def cell(v): return "-" if not v else f"{sum(x[0] for x in v)} / {max(x[1] for x in v):.1f} / {max(x[2] for x in v):.1f}"
def mean(row, b):
    v = [x[0] for l in labels if l.endswith(b) for x in row.get(l, [])]
    return f"{sum(v) / len(v):.2f}" if v else "-"
tot = {"ref": [0, 0], "new": [0, 0]}
for step in sorted(steps):
    row = steps[step]
    for l in labels:
        b = l.split("-")[1]; tot[b][0] += sum(x[0] for x in row.get(l, [])); tot[b][1] += len(row.get(l, []))
    print(f"{step:24s}" + "".join(f"{cell(row.get(l)):>20s}" for l in labels) + f"{mean(row, 'ref'):>15s}{mean(row, 'new'):>15s}")
for b in ("ref", "new"):
    print(f"total {b}: {tot[b][0]} missed over {tot[b][1]} measured animations")
want = {step: max(len(v) for v in row.values()) for step, row in steps.items()}
for step, row in steps.items():
    for l in labels:
        if len(row.get(l, [])) < want[step]:
            gaps.append(f"{l}: {step} measured {len(row.get(l, []))} of {want[step]} passes")
if gaps:
    print("INCOMPLETE: these measurements are missing, so the totals above do not compare like with like:")
    for g in sorted(set(gaps)): print("  " + g)
    sys.exit(3)
print("complete: every animation measured in every pass of every run")
PY
exit ${PIPESTATUS[0]}
