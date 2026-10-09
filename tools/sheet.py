"""Contact sheet of chosen frames of a recording's frames folder (made by tools/pops.py), with frame index and time.
    python tools/sheet.py NAME OUT FIRST LAST [STEP] [X Y W H]   writes tools/shots/OUT.png (7 frames a row)"""
import os, sys
from PIL import Image, ImageDraw
D = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'shots') + '/'
name, out, a, b = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
step = int(sys.argv[5]) if len(sys.argv) > 5 else 1
box = tuple(int(v) for v in sys.argv[6:10]) if len(sys.argv) >= 10 else None
fd = D + name + '_frames/'
fs = sorted(os.listdir(fd))
times = []
try:
    times = [float(l.split()[1]) for l in open(D + name + '/frames.txt') if len(l.split()) >= 2]
except Exception:
    pass
sel = [j for j in range(a, min(b, len(fs) - 1) + 1, step)]
first = Image.open(fd + fs[0])
bw, bh = (box[2], box[3]) if box else first.size
w = 216 if not box else min(250, bw)
h = int(w * bh / bw)
rows = (len(sel) + 6) // 7
s = Image.new('RGB', (7 * (w + 4), rows * (h + 18)), 'white')
d = ImageDraw.Draw(s)
for n, j in enumerate(sel):
    r, c = divmod(n, 7)
    im = Image.open(fd + fs[j]).convert('RGB')
    if box: im = im.crop((box[0], box[1], box[0] + box[2], box[1] + box[3]))
    s.paste(im.resize((w, h)), (c * (w + 4), r * (h + 18) + 18))
    d.text((c * (w + 4) + 2, r * (h + 18) + 2), '%d %.2f' % (j, times[j] if j < len(times) else 0), fill='black')
s.save(D + out + '.png')
