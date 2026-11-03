#!/usr/bin/env python3
"""Resolve the app's AGSL sources (Kotlin raw strings with templates and `+` joins) into plain .agsl files.

The step-0 glass check (docs/BENCH_STEP0.md) compiles the very text the launcher compiles, on the phone (bench app) and
on the desktop (Skiko). The shaders are assembled in Kotlin, so this script does what the Kotlin compiler does for the
few symbols they use; an unknown template stops it (a new symbol must be added here, never guessed).

Usage: python tools/bench/extract_shaders.py [OUT_DIR]   (default: bench/src/main/assets/shaders)
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "dev", "launcher", "app")


def read(rel):
    with open(os.path.join(SRC, rel), encoding="utf-8") as f:
        return f.read()


def expression(text, name):
    """The parts of `NAME = ...`: raw strings and identifiers joined by `+`."""
    m = re.search(r"\b(?:val|const val)\s+" + re.escape(name) + r"\s*=\s*", text)
    if not m:
        raise SystemExit(f"no {name}")
    i, parts = m.end(), []
    while True:
        while text[i] in " \t\r\n":
            i += 1
        if text.startswith('"""', i):
            end = text.index('"""', i + 3)
            parts.append(("raw", text[i + 3:end]))
            i = end + 3
        else:
            ident = re.match(r"[A-Za-z_][\w.]*", text[i:]).group(0)
            parts.append(("ref", ident))
            i += len(ident)
        j = i
        while text[j] in " \t\r\n":
            j += 1
        if text[j] != "+":
            return parts
        i = j + 1


def raw(rel, name):
    """A constant that is exactly one raw string, its templates left in."""
    parts = expression(read(rel), name)
    assert len(parts) == 1 and parts[0][0] == "raw", name
    return parts[0][1]


NOISE = raw("Reveal.kt", "NOISE")
FRONT = raw("Reveal.kt", "FRONT")
# MaterialPainter's FRONT: the reveal's front with its uniforms named apart (MaterialPainter.FRONT, the same replace chain).
MP_FRONT = (FRONT.replace("origin", "revealOrigin").replace("maxDist", "revealMaxDist").replace("cell", "revealCell")
            .replace("progress", "revealProgress").replace("time", "revealTime"))
NC_PROFILE = raw("shade/nc/NcGlassProfile.kt", "AGSL")

INTS = {"MAX_FILLS": 12, "MAX_SHADOWS": 4, "MAX_STROKES": 2, "MAX_STOPS": 4}
SYMBOLS = {
    "FRONT": MP_FRONT, "dev.launcher.app.Reveal.NOISE": NOISE, "Reveal.NOISE": NOISE, "Reveal.FRONT": FRONT,
    "NcGlassProfile.AGSL": NC_PROFILE, "MAX_FILLS * MAX_STOPS": str(INTS["MAX_FILLS"] * INTS["MAX_STOPS"]),
    **{k: str(v) for k, v in INTS.items()},
}


def check_constants():
    """The constants above must still be the app's."""
    mp, theme = read("design/MaterialPainter.kt"), read("design/Theme.kt")
    for k in ("MAX_FILLS", "MAX_SHADOWS", "MAX_STROKES"):
        v = int(re.search(r"const val " + k + r" = (\d+)", mp).group(1))
        assert v == INTS[k], f"{k} is {v} in the app"
    assert int(re.search(r"const val MAX_STOPS = (\d+)", theme).group(1)) == INTS["MAX_STOPS"]


def resolve(s):
    def sub(m):
        key = (m.group(1) or m.group(2)).strip()
        if key not in SYMBOLS:
            raise SystemExit(f"unknown template ${{{key}}}")
        return SYMBOLS[key]
    return re.sub(r"\$\{([^}]*)\}|\$([A-Za-z_]\w*)", sub, s)


def build(rel, name):
    out = []
    for kind, v in expression(read(rel), name):
        out.append(resolve(v) if kind == "raw" else SYMBOLS[v])
    return "".join(out)


SHADERS = {
    "material": ("design/MaterialPainter.kt", "AGSL"),
    "edge-clamp": ("design/MaterialPainter.kt", "EDGE_CLAMP"),
    "nc-glass-lens": ("shade/nc/NcGlassLens.kt", "AGSL"),
    "front-cover": ("home/FrontCover.kt", "AGSL"),
    "theme-reveal": ("home/ThemeReveal.kt", "AGSL"),
    "wallpaper-transition": ("WallpaperTransition.kt", "AGSL"),
}

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "bench", "src", "main", "assets", "shaders")
    os.makedirs(out, exist_ok=True)
    check_constants()
    for key, (rel, name) in SHADERS.items():
        src = build(rel, name)
        with open(os.path.join(out, key + ".agsl"), "w", encoding="utf-8", newline="\n") as f:
            f.write(src)
        print(f"{key}: {len(src.splitlines())} lines")
