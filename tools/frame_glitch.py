"""Finds one-frame glitches in a region of a screen recording: frames that differ from BOTH neighbours much more than the
neighbours differ from each other (a flicker), and prints them with the frame times.

    python tools/frame_glitch.py RECORDING.mp4 X Y W H [THRESHOLD] [OUTDIR]

The region is in device pixels. THRESHOLD (default 6) is the mean absolute difference per channel a flicker must exceed.
With OUTDIR, each glitch is saved as a strip: previous | glitch | next (full resolution of the region).
"""
import subprocess
import sys

import numpy as np
from PIL import Image


def frames(path, x, y, w, h):
    import imageio_ffmpeg
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    cmd = [ff, '-loglevel', 'error', '-i', path, '-fps_mode', 'passthrough', '-vf', f'crop={w}:{h}:{x}:{y}',
           '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-']
    out = subprocess.run(cmd, capture_output=True, check=True).stdout
    n = len(out) // (w * h * 3)
    return np.frombuffer(out, dtype=np.uint8)[: n * w * h * 3].reshape(n, h, w, 3).astype(np.int16)


def main():
    path, x, y, w, h = sys.argv[1], *map(int, sys.argv[2:6])
    thr = float(sys.argv[6]) if len(sys.argv) > 6 else 6.0
    outdir = sys.argv[7] if len(sys.argv) > 7 else None
    f = frames(path, x, y, w, h)
    print(f'{len(f)} frames')
    found = 0
    # A burst of k frames (1..4) that differs from the frames just before and after it, while those two agree.
    i = 1
    while i < len(f) - 1:
        hit = False
        for k in range(1, 5):
            j = i + k
            if j >= len(f):
                break
            before, after = f[i - 1], f[j]
            c = np.abs(after - before).mean()
            inner = [min(np.abs(f[x] - before).mean(), np.abs(f[x] - after).mean()) for x in range(i, j)]
            if min(inner) > thr and min(inner) > 2.5 * c:
                found += 1
                print(f'frames {i}..{j - 1}: differ {min(inner):.1f} from the frames around them, which differ {c:.1f}')
                if outdir:
                    strip = np.concatenate([before, f[i], after], axis=1).astype(np.uint8)
                    Image.fromarray(strip).save(f'{outdir}/glitch_{i}.png')
                i = j
                hit = True
                break
        if not hit:
            i += 1
    print(f'{found} glitches')


if __name__ == '__main__':
    main()
