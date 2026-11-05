#!/usr/bin/env bash
# E1's no-behaviour-change check (docs/E1_INVENTORY.md): every class in the APK's dex with its direct and virtual method
# counts, diffed against a reference listing of the build at $REF_SHA (the last commit before the engine module).
#
#   tools/dexlist.sh [SLICE] [APK]   compare; SLICE names tools/dexlist/SLICE.expect, the diff that slice declares
#                                    (no SLICE: the diff must be empty, as for every pure-move slice)
#   tools/dexlist.sh --ref [APK]     (re)write the reference from a build of $REF_SHA itself (refused otherwise)
#
# A diff line is "+ CLASS DIRECT VIRTUAL" (in this build, not in the reference) or "- CLASS DIRECT VIRTUAL" (the other
# way); a class whose method counts changed shows both. The .expect file holds exactly those lines, one per line ('#'
# starts a comment). Exit 0 only when the diff equals the declaration: an undeclared change fails, and so does a
# declared one that did not happen. The APK defaults to the local debug build; build it first (tools/build.sh).
set -u
here="$(cd "$(dirname "$0")" && pwd)"
root="$here/.."
REF_SHA=9984034
refdir="$here/shots/dexlist"          # git-ignored (tools/shots/)
ref="$refdir/ref-$REF_SHA.txt"

DEXDUMP="${DEXDUMP:-}"
if [ -z "$DEXDUMP" ]; then
  bt="$LOCALAPPDATA/Android/Sdk/build-tools"
  newest=$(ls "$bt" 2>/dev/null | sort -V | tail -1)
  DEXDUMP="$bt/$newest/dexdump.exe"
  [ -x "$DEXDUMP" ] || DEXDUMP="$bt/$newest/dexdump"
fi
[ -x "$DEXDUMP" ] || { echo "dexdump not found (set DEXDUMP)"; exit 2; }

mode=compare; slice=""
if [ "${1:-}" = "--ref" ]; then mode=ref; shift
elif [ -n "${1:-}" ] && [ "${1%.apk}" = "$1" ]; then slice=$1; shift; fi
apk="${1:-$root/app/build/outputs/apk/debug/app-debug.apk}"
[ -f "$apk" ] || { echo "no APK at $apk (tools/build.sh first)"; exit 2; }

# One line per class: descriptor, direct methods, virtual methods; sorted, so the dex file a class lands in never counts.
listing() {
  local tmp; tmp=$(mktemp -d)
  unzip -q -o "$1" 'classes*.dex' -d "$tmp" || { rm -rf "$tmp"; return 1; }
  for d in "$tmp"/classes*.dex; do "$DEXDUMP" "$d"; done | awk '
    function flush() { if (cls != "") print cls, dm, vm }
    /^  Class descriptor  : / { flush(); cls = $4; gsub("\047", "", cls); dm = 0; vm = 0; sec = ""; next }
    /^  (Static fields|Instance fields|Direct methods|Virtual methods)/ { sec = $1; next }
    /^    #[0-9]+ +: \(in / { if (sec == "Direct") dm++; else if (sec == "Virtual") vm++ }
    END { flush() }' | sort
  rm -rf "$tmp"
}

if [ "$mode" = ref ]; then
  head=$(git -C "$root" rev-parse --short=7 HEAD)
  dirty=$(git -C "$root" status --porcelain -- app engine build.gradle.kts settings.gradle.kts gradle gradle.properties)
  if [ "$head" != "$REF_SHA" ] || [ -n "$dirty" ]; then
    echo "refused: the reference must come from a clean build of $REF_SHA (HEAD $head${dirty:+, build files changed})"; exit 2
  fi
  mkdir -p "$refdir"
  listing "$apk" > "$ref" || exit 2
  echo "reference: $ref ($(wc -l < "$ref") classes)"; exit 0
fi

[ -f "$ref" ] || { echo "no reference $ref: check out $REF_SHA, build, run tools/dexlist.sh --ref"; exit 2; }
cur=$(mktemp); listing "$apk" > "$cur" || exit 2
got=$(diff "$ref" "$cur" | sed -n 's/^> /+ /p; s/^< /- /p' | sort)
want=""
if [ -n "$slice" ]; then
  exp="$here/dexlist/$slice.expect"
  [ -f "$exp" ] || { echo "no declaration $exp"; rm -f "$cur"; exit 2; }
  want=$(sed 's/#.*//; s/[[:space:]]*$//' "$exp" | grep -v '^$' | sort)
fi
echo "classes: reference $(wc -l < "$ref"), this build $(wc -l < "$cur")"
rm -f "$cur"
undeclared=$(comm -13 <(printf '%s\n' "$want" | grep -v '^$') <(printf '%s\n' "$got" | grep -v '^$'))
missing=$(comm -23 <(printf '%s\n' "$want" | grep -v '^$') <(printf '%s\n' "$got" | grep -v '^$'))
echo "diff against $REF_SHA:"; if [ -n "$got" ]; then printf '%s\n' "$got" | sed 's/^/  /'; else echo "  (none)"; fi
if [ -z "$undeclared" ] && [ -z "$missing" ]; then echo "PASS${slice:+ ($slice as declared)}"; exit 0; fi
[ -n "$undeclared" ] && { echo "UNDECLARED:"; printf '%s\n' "$undeclared" | sed 's/^/  /'; }
[ -n "$missing" ] && { echo "DECLARED BUT NOT SEEN:"; printf '%s\n' "$missing" | sed 's/^/  /'; }
echo FAIL; exit 1
