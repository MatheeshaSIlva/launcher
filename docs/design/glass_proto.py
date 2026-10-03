"""Offline prototype of the launcher's AGSL glass (same maths), to tune the iOS 26 material against reference screenshots.

Render: python proto.py  ->  home.png (home mock on a sharp photo) and library.png (App Library mock).
"""
import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

W, H = 540, 1170              # half the S24's resolution
PT = W / 402.0                # one iOS point in px


def load(path):
    return np.asarray(Image.open(path).convert('RGB')).astype(np.float32) / 255.0


def blur(img, sigma_pt):
    s = sigma_pt * PT
    return np.stack([ndimage.gaussian_filter(img[..., c], s, mode='nearest') for c in range(3)], -1)


def saturate(c, s):
    l = (c * np.array([0.2126, 0.7152, 0.0722])).sum(-1, keepdims=True)
    return np.clip(l + (c - l) * s, 0, 1)


def sd_round_rect(px, py, bx, by, r):
    qx = np.abs(px) - bx + r
    qy = np.abs(py) - by + r
    return np.hypot(np.maximum(qx, 0), np.maximum(qy, 0)) + np.minimum(np.maximum(qx, qy), 0) - r


MATERIAL = dict(
    sigma=20.0,        # backdrop blur (pt): what the glass shows is soft colour
    sat=1.25,          # colour through the glass
    tint=0.14,         # white mixed into the body
    glow_w=9.0,        # inner glow band (pt) and strength: the glass's thickness
    glow=0.10,
    rim_w=1.3,         # rim line width (pt)
    rim_base=0.16, rim_light=0.55, rim_back=0.30,
    bevel=14.0, refr=16.0,  # lens band at the edge (pt)
    shade=0.06,        # slight darkening on the side away from the light, inside the edge
)


def glass(out, src_blurred, rect, radius_pt, m=MATERIAL):
    """Draws a glass rounded rect over `out` (in place). src_blurred = what the glass sees (already blurred)."""
    x0, y0, w, h = rect
    r = radius_pt * PT
    pad = 3
    xs = np.arange(int(x0) - pad, int(x0 + w) + pad)
    ys = np.arange(int(y0) - pad, int(y0 + h) + pad)
    xx, yy = np.meshgrid(xs + 0.5, ys + 0.5)
    px = xx - (x0 + w / 2)
    py = yy - (y0 + h / 2)
    d = sd_round_rect(px, py, w / 2, h / 2, r)
    e = 0.75
    nx = sd_round_rect(px + e, py, w / 2, h / 2, r) - sd_round_rect(px - e, py, w / 2, h / 2, r)
    ny = sd_round_rect(px, py + e, w / 2, h / 2, r) - sd_round_rect(px, py - e, w / 2, h / 2, r)
    ln = np.maximum(np.hypot(nx, ny), 1e-4)
    nx, ny = nx / ln, ny / ln
    inside = -d
    bevel = m['bevel'] * PT
    t = np.clip(inside / bevel, 0, 1)
    bend = 1 - np.sqrt(1 - (1 - t) ** 2)
    ox = nx * bend * m['refr'] * PT
    oy = ny * bend * m['refr'] * PT
    sx = np.clip(xx + ox, 0, W - 1)
    sy = np.clip(yy + oy, 0, H - 1)
    col = np.stack([ndimage.map_coordinates(src_blurred[..., c], [sy, sx], order=1, mode='nearest') for c in range(3)], -1)
    col = saturate(col, m['sat'])
    col = col + (1 - col) * m['tint']
    # Thickness: a soft light band just inside the edge, and a faint shade on the side facing away from the light.
    L = np.array([-0.7071, -0.7071])
    facing = nx * L[0] + ny * L[1]
    band = np.exp(-np.maximum(inside, 0) / (m['glow_w'] * PT))
    col = col + (1 - col) * (m['glow'] * band)[..., None]
    col = col * (1 - (m['shade'] * band * np.maximum(-facing, 0))[..., None])
    rim = 1 - np.clip(inside / (m['rim_w'] * PT), 0, 1)
    rim = rim * rim * (3 - 2 * rim)
    spec = rim * (m['rim_base'] + m['rim_light'] * np.maximum(facing, 0) + m['rim_back'] * np.maximum(-facing, 0))
    col = np.clip(col + spec[..., None], 0, 1)
    a = np.clip(0.5 - d, 0, 1)[..., None]
    region = out[ys[0]:ys[-1] + 1, xs[0]:xs[-1] + 1]
    region[:] = region * (1 - a) + col * a


def icon(out, cx, cy, size_pt, color):
    s = size_pt * PT
    img = Image.fromarray((out * 255).astype(np.uint8))
    dr = ImageDraw.Draw(img)
    dr.rounded_rectangle([cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2], radius=s * 0.23, fill=color)
    out[:] = np.asarray(img).astype(np.float32) / 255.0


def home(wall):
    out = wall.copy()
    soft = blur(wall, MATERIAL['sigma'])
    # medium widget (4x2) at the top, two small widgets below, dock at the bottom (positions from HomeMetrics)
    col_pitch = 92.5 * PT
    first_left = 30.1 * PT
    grid_top = 89.5 * PT
    row = 100.6 * PT
    icon_pt = 64
    wid_l = first_left - 4 * PT
    wid_w = 3 * col_pitch + icon_pt * PT + 8 * PT
    glass(out, soft, (wid_l, grid_top, wid_w, row + icon_pt * PT), 23)
    small_w = col_pitch + icon_pt * PT + 8 * PT
    glass(out, soft, (wid_l, grid_top + 2 * row, small_w, row + icon_pt * PT), 23)
    glass(out, soft, (wid_l + 2 * col_pitch, grid_top + 2 * row, small_w, row + icon_pt * PT), 23)
    dock_h = 101 * PT
    dock_y = H - 17.5 * PT - dock_h
    glass(out, soft, (17.5 * PT, dock_y, W - 35 * PT, dock_h), 39)
    pill_w, pill_h = 78 * PT, 29.6 * PT
    glass(out, soft, ((W - pill_w) / 2, dock_y - 19.4 * PT - pill_h, pill_w, pill_h), 14.8)
    for i, c in enumerate([(52, 199, 89), (10, 132, 255), (52, 199, 89), (255, 45, 85)]):
        icon(out, W / 2 + (i - 1.5) * 89.4 * PT, dock_y + dock_h / 2, icon_pt, c)
    return out


def library(wall):
    bg = saturate(blur(wall, 30), 1.4) * 0.92   # the App Library's background material
    out = bg.copy()
    soft = bg   # tiles see the (already blurred) background
    margin, gap = 23.3 * PT, 18.2 * PT
    tile = (W - 2 * margin - gap) / 2
    top = (44 + 13) * PT
    glass(out, soft, (margin, top, W - 2 * margin, 46.7 * PT), 23.35)
    ty = top + 46.7 * PT + 25.6 * PT
    for r in range(3):
        for c in range(2):
            glass(out, soft, (margin + c * (tile + gap), ty + r * (tile + 35.3 * PT), tile, tile), tile / PT * 0.142)
    return out


if __name__ == '__main__':
    wall = load('wall.png')
    Image.fromarray((home(wall) * 255).astype(np.uint8)).save('home.png')
    Image.fromarray((library(wall) * 255).astype(np.uint8)).save('library.png')
    print('rendered')
