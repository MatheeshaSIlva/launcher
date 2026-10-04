"""Finds an app icon on a screenshot by template matching and prints its centre as "X Y" (device pixels).

    python tools/find_icon.py SCREENSHOT TEMPLATE [MAX_SCORE]
    python tools/find_icon.py --cut SCREENSHOT X Y SIZE TEMPLATE      saves a SIZE x SIZE template centred on X Y

Exits 1 when the best match is worse than MAX_SCORE (mean absolute difference per channel, default 18), so a script never taps
something that only looks a bit like the icon. Templates live in tools/shots/ (git-ignored: they come from the phone's icons).
"""
import sys

import numpy as np
from PIL import Image

STEP = 4   # search on a quarter-size image first, then refine at full size


def load(path):
    return np.asarray(Image.open(path).convert('RGB'), dtype=np.float32)


def best(img, tpl, step):
    h, w = tpl.shape[:2]
    H, W = img.shape[:2]
    win = np.lib.stride_tricks.sliding_window_view(img, (h, w, 3))[::step, ::step, 0]
    diff = np.abs(win - tpl).mean(axis=(2, 3, 4))
    y, x = np.unravel_index(np.argmin(diff), diff.shape)
    return diff[y, x], x * step, y * step


def main():
    if sys.argv[1] == '--cut':
        _, _, shot, x, y, size, out = sys.argv
        x, y, size = int(x), int(y), int(size)
        Image.open(shot).convert('RGB').crop((x - size // 2, y - size // 2, x + size // 2, y + size // 2)).save(out)
        return
    shot, tpl_path = sys.argv[1], sys.argv[2]
    limit = float(sys.argv[3]) if len(sys.argv) > 3 else 18.0
    img, tpl = load(shot), load(tpl_path)
    small = img[::STEP, ::STEP]
    ts = tpl[::STEP, ::STEP]
    score, x, y = best(small, ts, 1)
    x, y = x * STEP, y * STEP
    # Refine around the coarse match at full size.
    h, w = tpl.shape[:2]
    x0, y0 = max(0, x - STEP * 2), max(0, y - STEP * 2)
    region = img[y0:y0 + h + STEP * 4, x0:x0 + w + STEP * 4]
    score, dx, dy = best(region, tpl, 1)
    cx, cy = x0 + dx + w // 2, y0 + dy + h // 2
    print(f'{cx} {cy}')
    print(f'score {score:.1f}', file=sys.stderr)
    sys.exit(0 if score <= limit else 1)


if __name__ == '__main__':
    main()
