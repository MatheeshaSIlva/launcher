#!/usr/bin/env python3
"""Lossless PNG re-encoder for the golden pictures (MF slice 2, D-041). Pillow only. ASCII output.

    python tools/fidelity/pngopt.py IN OUT          re-encode IN as OUT (smaller or equal), print "IN_BYTES OUT_BYTES MD5"
    python tools/fidelity/pngopt.py --md5 FILE      print the md5 of the decoded RGB pixels
    python tools/fidelity/pngopt.py --check FILE [--size WxH]
                                                    FILE is a plain 8-bit RGB(A) PNG with no transparency (and WxH big): print
                                                    "BYTES MD5", else exit 1

A picture is identified by the md5 of its decoded RGB pixels, never by its file bytes (two encoders give two files of one
picture). Only a lossless path exists here: the input must be 8-bit RGB or RGBA with every alpha at 255 (what screencap
writes; anything else, a palette, a grey image, 16 bits or a see-through pixel, is refused rather than converted), the output is
written as RGB, read back and compared with the input pixel by pixel before it is kept (a mismatch deletes it and exits 1: it
can never leave a lossy file behind), and a re-encode that would not be smaller keeps the input's bytes.
Exit 0 done; 1 refused (not lossless or not a picture of the kind); 2 usage.
"""
import hashlib
import os
import shutil
import sys

from PIL import Image


class Refused(Exception):
    """The picture cannot be stored losslessly by this tool (or the re-encode was not exact)."""


def rgb_of(path):
    """The picture as an opaque RGB image; Refused for anything this tool will not convert."""
    try:
        im = Image.open(path)
        im.load()
    except Exception as e:  # noqa: BLE001 (any decode failure means: not a usable picture)
        raise Refused("%s is not a readable PNG (%s)" % (path, e))
    if im.format != "PNG":
        raise Refused("%s is a %s, not a PNG" % (path, im.format))
    if im.mode not in ("RGB", "RGBA"):
        raise Refused("%s is mode %s: only plain 8-bit RGB or RGBA is stored (no palette, grey or 16-bit)" % (path, im.mode))
    if im.mode == "RGBA":
        lo, _hi = im.getchannel("A").getextrema()
        if lo != 255:
            raise Refused("%s has transparent pixels (alpha down to %d): dropping alpha would not be lossless" % (path, lo))
        im = im.convert("RGB")
    return im


def md5_of(im):
    return hashlib.md5(im.tobytes()).hexdigest()


def reencode(src, dst, encode=None):
    """Writes dst, the same picture (decoded pixels equal), never bigger than src. Returns (src bytes, dst bytes, md5).
    `encode(im, path)` is the encoder (a test puts a lossy one here): its output is checked all the same."""
    im = rgb_of(src)
    want = md5_of(im)
    tmp = dst + ".tmp.png"
    try:
        (encode or _encode)(im, tmp)
        back = rgb_of(tmp)
        if back.size != im.size or md5_of(back) != want:
            raise Refused("the re-encoded %s does not decode to the same pixels: not kept" % src)
        if os.path.getsize(tmp) < os.path.getsize(src):
            os.replace(tmp, dst)
        else:
            os.remove(tmp)
            if os.path.abspath(src) != os.path.abspath(dst):
                shutil.copyfile(src, dst)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    return os.path.getsize(src), os.path.getsize(dst), want


def _encode(im, path):
    im.save(path, format="PNG", optimize=True, compress_level=9)


def main(argv):
    try:
        if len(argv) == 2 and argv[0] == "--md5":
            print(md5_of(rgb_of(argv[1])))
            return 0
        if len(argv) in (2, 4) and argv[0] == "--check":
            size = None
            if len(argv) == 4:
                if argv[2] != "--size" or argv[3].lower().count("x") != 1:
                    raise SystemExit(2)
                size = tuple(int(v) for v in argv[3].lower().split("x"))
            im = rgb_of(argv[1])
            if size is not None and im.size != size:
                raise Refused("%s is %dx%d, not %dx%d" % (argv[1], im.size[0], im.size[1], size[0], size[1]))
            print("%d %s" % (os.path.getsize(argv[1]), md5_of(im)))
            return 0
        if len(argv) == 2 and not argv[0].startswith("--"):
            a, b, md5 = reencode(argv[0], argv[1])
            print("%d %d %s" % (a, b, md5))
            return 0
    except Refused as e:
        print("pngopt: REFUSED: %s" % e, file=sys.stderr)
        return 1
    except (ValueError, SystemExit):
        pass
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
