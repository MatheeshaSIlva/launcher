#!/usr/bin/env python3
"""
Turns vector drawables compiled into an APK back into their XML (as `aapt2 dump xmltree` reads them), for
build_icon_set.py's 'apk' sources: Android 17's own Quick Settings icons where they differ from AOSP main's (the emulator's
SystemUIGoogle: `adb pull /system_ext/priv-app/SystemUIGoogle/SystemUIGoogle.apk`). References to strings (path data kept
as string resources) are resolved from the APK's resource table; a colour that names the theme is written white.

    python -I tools/icons/extract_apk_vectors.py APK CACHE_DIR NAME[/OUT[/SKIP+SKIP]] [...]

NAME: a drawable (e.g. vd_wifi); OUT: the file's name in CACHE_DIR/apk/ (NAME's by default); SKIP: elements (by their
android:name) left out with what they hold (an animated icon's base drawn as one of its states: Bluetooth's without the
earbuds' dots). Writes CACHE_DIR/apk/<OUT>.xml. The APK is data: parsed through aapt2, never run.
"""
import os
import re
import subprocess
import sys

A = 'http://schemas.android.com/apk/res/android'
ELEMENT = re.compile(r'(\s*)E: (\S+)')
ATTR = re.compile(r'\s*A: (?:http://schemas\.android\.com/apk/res/android:)?(\w+)(?:\(0x[0-9a-f]+\))?=(.*)$')
NAME = re.compile(r':name(?:\(0x[0-9a-f]+\))?="([^"]*)"')


def aapt2():
    sdk = os.environ.get('ANDROID_HOME') or os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk')
    tools = os.path.join(sdk, 'build-tools')
    best = sorted(os.listdir(tools))[-1]
    return os.path.join(tools, best, 'aapt2.exe' if os.name == 'nt' else 'aapt2')


def strings(exe, apk):
    """Resource id -> string value (path data kept as strings)."""
    out = subprocess.run([exe, 'dump', 'resources', apk], capture_output=True, text=True, errors='replace').stdout
    res = {}
    lines = out.replace('\r', '').split('\n')
    for i, l in enumerate(lines):
        m = re.match(r'\s+resource (0x[0-9a-f]+) string/\S+', l)
        if m and i + 1 < len(lines):
            v = re.match(r'\s+\(\) "(.*)"\s*$', lines[i + 1])
            if v:
                res[m.group(1)] = v.group(1)
    return res


def value(attr, v, strs):
    v = re.sub(r' \(Raw: .*\)$', '', v)
    if v.startswith('"') and v.endswith('"'):
        v = v[1:-1]
    elif v.startswith('@0x'):
        v = strs.get(v[1:], v)
    v = re.sub(r'^(-?[\d.]+)\.000000(dp)?$', r'\1\2', v)
    v = re.sub(r'^(-?[\d.]+e[+-]\d+)$', lambda x: repr(float(x.group(1))), v)
    if attr in ('fillColor', 'strokeColor') and (v.startswith('?') or v.startswith('@')):
        v = '#ffffffff'   # a theme's colour: the symbol is drawn in its own colour anyway
    return v


def convert(exe, apk, name, strs, skip=()):
    out = subprocess.run([exe, 'dump', 'xmltree', apk, '--file', f'res/drawable/{name}.xml'], capture_output=True, text=True,
                         errors='replace').stdout.replace('\r', '')
    lines = out.split('\n')
    stack = []        # (indent, tag) of the elements open
    xml = ['<?xml version="1.0" encoding="utf-8"?>']
    open_tag = [None]
    skipping = None   # the indent of an element left out (with everything under it)

    def flush():
        if open_tag[0] is not None:
            xml.append(open_tag[0] + '>')
            open_tag[0] = None

    def close_to(indent):
        flush()
        while stack and stack[-1][0] >= indent:
            xml.append(f'</{stack.pop()[1]}>')

    for li, l in enumerate(lines):
        m = ELEMENT.match(l)
        if m:
            indent = len(m.group(1))
            if skipping is not None:
                if indent > skipping:
                    continue
                skipping = None
            nm = None
            for l2 in lines[li + 1:]:
                if ELEMENT.match(l2):
                    break
                n2 = NAME.search(l2)
                if n2:
                    nm = n2.group(1)
                    break
            close_to(indent)
            if nm in skip:
                skipping = indent
                continue
            ns = f' xmlns:android="{A}"' if not stack else ''
            open_tag[0] = f'<{m.group(2)}{ns}'
            stack.append((indent, m.group(2)))
            continue
        if skipping is not None:
            continue
        m = ATTR.match(l)
        if m and open_tag[0] is not None:
            attr = m.group(1)
            v = value(attr, m.group(2), strs)
            if attr in ('name', 'tint') or v.startswith('?') or v.startswith('@'):
                continue
            open_tag[0] += f' android:{attr}="{v}"'
    close_to(-1)
    return '\n'.join(xml) + '\n'


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    apk, cache = sys.argv[1], sys.argv[2]
    exe = aapt2()
    strs = strings(exe, apk)
    os.makedirs(os.path.join(cache, 'apk'), exist_ok=True)
    for arg in sys.argv[3:]:
        parts = arg.split('/')
        name = parts[0]
        out = parts[1] if len(parts) > 1 and parts[1] else name
        skip = tuple(parts[2].split('+')) if len(parts) > 2 else ()
        xml = convert(exe, apk, name, strs, skip)
        with open(os.path.join(cache, 'apk', out + '.xml'), 'w', encoding='utf-8', newline='\n') as f:
            f.write(xml)
        print(out, len(xml))


if __name__ == '__main__':
    main()
