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

An animated vector (Android 17's Quick Settings icons: qs_flashlight_icon_off...) is compiled into pieces: its vector and
each target's animator are files of their own, named by resource id. They are put back inline (aapt:attr), as the source
had them, so build_icon_set.py can take the animation's first or last frame, or its whole play (`android:interpolator`
too: the app's anim/overshoot, ease_out ... or a framework one, put in as the interpolator's own element; a reference
that cannot be resolved stops the run).
"""
import os
import re
import subprocess
import sys

A = 'http://schemas.android.com/apk/res/android'
ELEMENT = re.compile(r'(\s*)E: (\S+)')
ATTR = re.compile(r'\s*A: (?:http://schemas\.android\.com/apk/res/android:)?(\w+)(?:\(0x[0-9a-f]+\))?=(.*)$')
NAME = re.compile(r':name(?:\(0x[0-9a-f]+\))?="([^"]*)"')


# The framework's interpolators by resource id (android.R.interpolator, checked against android-37's android.jar): the
# ones that are one cubic Bezier as a pathInterpolator, the rest as their own element (build_icon_set.py refuses them).
def _path(x1, y1, x2, y2):
    return f'<pathInterpolator xmlns:android="{A}" android:controlX1="{x1}" android:controlY1="{y1}" android:controlX2="{x2}" android:controlY2="{y2}"/>'


FRAMEWORK_INTERPOLATORS = {
    '0x010c000b': f'<linearInterpolator xmlns:android="{A}"/>',
    '0x010c000d': _path(0.4, 0.0, 0.2, 1.0),    # fast_out_slow_in
    '0x010c000e': _path(0.0, 0.0, 0.2, 1.0),    # linear_out_slow_in
    **{f'0x010c{i:04x}': f'<{n}Interpolator xmlns:android="{A}"/>' for i, n in enumerate(
        ['accelerate', 'decelerate', 'accelerate', 'decelerate', 'accelerate', 'decelerate', 'accelerateDecelerate',
         'anticipate', 'overshoot', 'anticipateOvershoot', 'bounce'])},
}


def aapt2():
    sdk = os.environ.get('ANDROID_HOME') or os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk')
    tools = os.path.join(sdk, 'build-tools')
    best = sorted(os.listdir(tools))[-1]
    return os.path.join(tools, best, 'aapt2.exe' if os.name == 'nt' else 'aapt2')


def files(exe, apk):
    """Resource id -> the file it is (res/drawable/$qs_flashlight_icon_off__0.xml ...)."""
    out = subprocess.run([exe, 'dump', 'resources', apk], capture_output=True, text=True, errors='replace').stdout
    res = {}
    lines = out.replace('\r', '').split('\n')
    for i, l in enumerate(lines):
        m = re.match(r'\s+resource (0x[0-9a-f]+) \S+', l)
        if m and i + 1 < len(lines):
            f = re.match(r'\s+\(\S*\) \(file\) (\S+)', lines[i + 1])
            if f:
                res[m.group(1)] = f.group(1)
    return res


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


def convert(exe, apk, name, strs, skip=(), path=None):
    out = subprocess.run([exe, 'dump', 'xmltree', apk, '--file', path or f'res/drawable/{name}.xml'], capture_output=True, text=True,
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
            # (An animated vector's vector and animators are kept as references: put back inline after.)
            if attr in ('drawable', 'animation', 'interpolator') and v.startswith('@0x'):
                open_tag[0] += f' android:{attr}="{v}"'
                continue
            # (Names kept: an animated vector's targets name its groups and paths.)
            if attr == 'name':
                open_tag[0] += f' android:name="{v}"'
                continue
            if attr == 'tint' or v.startswith('?') or v.startswith('@'):
                continue
            open_tag[0] += f' android:{attr}="{v}"'
    close_to(-1)
    return '\n'.join(xml) + '\n'


def inline_refs(exe, apk, xml, strs, fls, depth=0):
    """[xml] with each reference to another compiled file (an animated vector's vector, a target's animator) put inline."""
    import xml.etree.ElementTree as ET
    ET.register_namespace('android', A)
    ET.register_namespace('aapt', 'http://schemas.android.com/aapt')
    root = ET.fromstring(xml.split('\n', 1)[1] if xml.startswith('<?xml') else xml)
    for el in root.iter():
        for attr in ('drawable', 'animation', 'interpolator'):
            key = '{%s}%s' % (A, attr)
            ref = el.get(key)
            if not ref or not ref.startswith('@0x'):
                continue
            path = fls.get(ref[1:])
            if attr == 'interpolator' and not path and ref[1:] not in FRAMEWORK_INTERPOLATORS:
                raise SystemExit(f'interpolator {ref} is in neither the APK nor the framework table')
            if (not path and ref[1:] not in FRAMEWORK_INTERPOLATORS) or depth > 3:
                continue
            sub = FRAMEWORK_INTERPOLATORS.get(ref[1:]) if not path else inline_refs(
                exe, apk, convert(exe, apk, None, strs, path=path), strs, fls, depth + 1)
            del el.attrib[key]
            holder = ET.SubElement(el, '{http://schemas.android.com/aapt}attr', {'name': 'android:' + attr})
            holder.append(ET.fromstring(sub.split('\n', 1)[1] if sub.startswith('<?xml') else sub))
    return '<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(root, encoding='unicode') + '\n'


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    apk, cache = sys.argv[1], sys.argv[2]
    exe = aapt2()
    strs = strings(exe, apk)
    fls = files(exe, apk)
    os.makedirs(os.path.join(cache, 'apk'), exist_ok=True)
    for arg in sys.argv[3:]:
        parts = arg.split('/')
        name = parts[0]
        out = parts[1] if len(parts) > 1 and parts[1] else name
        skip = tuple(parts[2].split('+')) if len(parts) > 2 else ()
        xml = convert(exe, apk, name, strs, skip)
        if '@0x' in xml:
            xml = inline_refs(exe, apk, xml, strs, fls)
        with open(os.path.join(cache, 'apk', out + '.xml'), 'w', encoding='utf-8', newline='\n') as f:
            f.write(xml)
        print(out, len(xml))


if __name__ == '__main__':
    main()
