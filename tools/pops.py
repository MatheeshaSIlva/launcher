"""Finds pops in a slow-motion recording (tools/slowmo.sh 8, then tools/device.sh rec/recpull): for a 24 x 12 grid of
tiles, frames whose change in one tile is much larger than that tile's change in the frames around it (something that
appeared or went at once). Finger-driven motion (pages, a pull) is flagged too: judge those by eye.
    python tools/pops.py NAME [X Y W H]   (tools/shots/NAME.mp4; optional region)
Prints suspects (frame index, tile, change, neighbours' median, gap to the previous frame in ms) and writes
tools/shots/NAME_pops.png (each suspect: the frame before, the frame, the frame after)."""
import os, subprocess, sys, statistics
from PIL import Image
D = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'shots') + '/'
name = sys.argv[1]
box = tuple(int(v) for v in sys.argv[2:6]) if len(sys.argv) >= 6 else None
out = D + name + '_frames'
ff = subprocess.check_output(['python', '-c', 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())']).decode().strip()
if not os.path.isdir(out):
    os.makedirs(out)
    subprocess.run([ff, '-loglevel', 'quiet', '-i', D + name + '.mp4', '-fps_mode', 'passthrough', out + '/f%04d.png'])
fs = sorted(os.listdir(out))
times = []
try:
    for line in open(D + name + '/frames.txt'):
        p = line.split()
        if len(p) >= 2: times.append(float(p[1]))
except Exception:
    pass
small = []
for f in fs:
    im = Image.open(out + '/' + f).convert('L')
    if box: im = im.crop((box[0], box[1], box[0] + box[2], box[1] + box[3]))
    small.append(im.resize((im.width // 6, im.height // 6)))
GX, GY = 12, 24
def tiles(im):
    w, h = im.size
    px = im.get_flattened_data()
    t = [[0.0] * GX for _ in range(GY)]
    n = [[0] * GX for _ in range(GY)]
    return px, w, h
grid = []
for a, b in zip(small, small[1:]):
    pa, pb = a.get_flattened_data(), b.get_flattened_data()
    w, h = a.size
    acc = [0.0] * (GX * GY); cnt = [0] * (GX * GY)
    for y in range(h):
        gy = min(GY - 1, y * GY // h)
        for x in range(w):
            k = gy * GX + min(GX - 1, x * GX // w)
            acc[k] += abs(pa[y * w + x] - pb[y * w + x]); cnt[k] += 1
    grid.append([acc[k] / max(1, cnt[k]) for k in range(GX * GY)])
grid.insert(0, [0.0] * (GX * GY))
sus = []
for i in range(1, len(grid)):
    best = None
    for k in range(GX * GY):
        nb = [grid[j][k] for j in list(range(max(1, i - 4), i)) + list(range(i + 1, min(len(grid), i + 5)))]
        med = statistics.median(nb) if nb else 0
        v = grid[i][k]
        if v > 7 and v > 4 * max(med, 1.0):
            if best is None or v > best[1]: best = (k, v, med)
    if best:
        gap = (times[i] - times[i - 1]) if i < len(times) else 0
        sus.append((i, 'tile %d,%d' % (best[0] % GX, best[0] // GX), round(best[1], 1), round(best[2], 1), round(gap * 1000)))
print('frames', len(fs), 'suspects (index, change, neighbours, gap ms):', sus)
if sus:
    w = 270; h = int(w * small[0].height / small[0].width) if not box else int(w * box[3] / box[2])
    sheet = Image.new('RGB', (3 * (w + 6), len(sus[:8]) * (h + 6)), 'white')
    for r, (i, *_ ) in enumerate(sus[:8]):
        for c, j in enumerate((i - 1, i, min(i + 1, len(fs) - 1))):
            im = Image.open(out + '/' + fs[j]).convert('RGB')
            if box: im = im.crop((box[0], box[1], box[0] + box[2], box[1] + box[3]))
            sheet.paste(im.resize((w, h)), (c * (w + 6), r * (h + 6)))
    sheet.save(D + name + '_pops.png')
