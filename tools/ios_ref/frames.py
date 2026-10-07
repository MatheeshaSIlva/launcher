#!/usr/bin/env python3
"""
Every frame of a capture recording with its own presentation time (the Simulator only emits a frame when the screen
changes, and a CI machine may drop some: frame counts mean nothing, times do).

    python tools/ios_ref/frames.py VIDEO OUTDIR [--scale S] [--from T0] [--to T1] [--sheet COLSxROWS]

Writes OUTDIR/fNNNN.png (scaled by S, default 0.5) and OUTDIR/frames.txt ("index time_s" per line); with --sheet also
contact sheets OUTDIR/sheetNN.png with each frame's time printed on it.
"""
import argparse
import os
import re
import subprocess
import sys

import imageio_ffmpeg
from PIL import Image, ImageDraw


def frame_times(video):
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    r = subprocess.run([ff, "-hide_banner", "-i", video, "-fps_mode", "passthrough", "-vf", "showinfo", "-f", "null", "-"],
                       capture_output=True, text=True)
    return [float(m.group(1)) for m in re.finditer(r"pts_time:([0-9.]+)", r.stderr)]


def extract(video, out, scale=0.5, t0=None, t1=None):
    os.makedirs(out, exist_ok=True)
    for f in os.listdir(out):
        if re.match(r"f\d+\.png$", f):
            os.remove(os.path.join(out, f))
    times = frame_times(video)
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    vf = "scale=iw*%s:-2" % scale if scale != 1 else "null"
    subprocess.run([ff, "-hide_banner", "-loglevel", "error", "-i", video, "-fps_mode", "passthrough", "-vf", vf,
                    os.path.join(out, "f%04d.png")], check=True)
    keep = []
    for i, t in enumerate(times):
        p = os.path.join(out, "f%04d.png" % (i + 1))
        if not os.path.exists(p):
            continue
        if (t0 is not None and t < t0) or (t1 is not None and t > t1):
            os.remove(p)
            continue
        keep.append((i + 1, t))
    with open(os.path.join(out, "frames.txt"), "w") as fh:
        for i, t in keep:
            fh.write("%d %.4f\n" % (i, t))
    return keep


def sheets(out, frames, cols, rows):
    if not frames:
        return
    first = Image.open(os.path.join(out, "f%04d.png" % frames[0][0]))
    tw = 180
    th = int(first.height * tw / first.width)
    per = cols * rows
    for s in range(0, len(frames), per):
        sheet = Image.new("RGB", (cols * tw, rows * (th + 16)), "white")
        d = ImageDraw.Draw(sheet)
        for k, (i, t) in enumerate(frames[s:s + per]):
            im = Image.open(os.path.join(out, "f%04d.png" % i)).convert("RGB").resize((tw, th))
            x, y = (k % cols) * tw, (k // cols) * (th + 16)
            sheet.paste(im, (x, y + 16))
            d.text((x + 4, y + 2), "%d  %.3f s" % (i, t), fill="black")
        sheet.save(os.path.join(out, "sheet%02d.png" % (s // per + 1)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("out")
    ap.add_argument("--scale", type=float, default=0.5)
    ap.add_argument("--from", dest="t0", type=float)
    ap.add_argument("--to", dest="t1", type=float)
    ap.add_argument("--sheet", help="COLSxROWS")
    a = ap.parse_args()
    frames = extract(a.video, a.out, a.scale, a.t0, a.t1)
    print("%d frames" % len(frames))
    if len(frames) > 1:
        dts = [b[1] - a_[1] for a_, b in zip(frames, frames[1:])]
        dts.sort()
        print("frame gaps: median %.1f ms, 90%% %.1f ms, max %.1f ms" % (1000 * dts[len(dts) // 2], 1000 * dts[int(len(dts) * 0.9)], 1000 * dts[-1]))
    if a.sheet:
        c, r = map(int, a.sheet.lower().split("x"))
        sheets(a.out, frames, c, r)


if __name__ == "__main__":
    sys.exit(main())
