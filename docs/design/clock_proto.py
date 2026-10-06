"""Offline port of the glass clock as the app draws it (GlassDrawable.AGSL_MASK + ClockNumerals.build), at the Galaxy S24's
real widget size with the bundled Inter font, to judge the look without a phone. Same steps: coverage mask, half-size
exact distance transform, bevel from the strokes' half width, the dock's lens law and light (GlassDrawable.LIGHTING), the
FROSTED source (sharp wallpaper and its heavy blur), the soft shadow. Wallpapers are synthetic (smooth colour plus fine
detail, so the lens shows). Needs numpy, Pillow, scipy.  Run: python3 clock_proto.py  ->  clock-glass.png
"""
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from scipy import ndimage

FONT = '../../app/src/main/assets/fonts/InterVariable.ttf'
U = 1080 / 402.0                       # one iOS point in px on the S24
W, H = 940, 374                        # the numerals' box of the 4 x 2 widget (cardW x cardH - 25 pt)

# GlassStyle.IOS_CLOCK (IOS with the clock's changes), in pt where GlassStyle uses pt.
S = dict(frost=0.55, bevel=20, refraction=30, dispersion=0.25, magnify=0.0, saturation=1.2, tint=0.22,
         glowWidth=3, glow=0.06, shade=0.15, rimWidth=1.2, rimBase=0.1, rimLight=0.55, rimBack=0.22,
         edgeDark=0.14, edgeWidth=1.2, specPower=1.8, adapt=0.9, light=225)


def font(size):
    f = ImageFont.truetype(FONT, size)
    f.set_variation_by_axes([32, 640])     # opsz 32 (display), wght 640: Fonts.display(640)
    return f


def numerals_mask(text):
    """Coverage of [text] laid out as ClockNumerals.layout does (narrowed 0.95, letter spacing -0.02 em)."""
    probe = ImageDraw.Draw(Image.new('L', (10, 10)))
    f100 = font(100)
    bb = probe.textbbox((0, 0), '0123456789', font=f100)
    digit_h = (bb[3] - bb[1]) / 100
    widest = probe.textlength('10:08', font=f100) / 100
    ts = min(H * 0.94 / digit_h, W * 0.96 / (widest * 0.95))
    baseline = digit_h * ts + H * 0.03
    f = font(int(round(ts)))
    wide = Image.new('L', (int(W / 0.95) + 40, H), 0)
    d = ImageDraw.Draw(wide)
    adv = [d.textlength(c, font=f) - 0.02 * ts for c in text]
    x = (wide.width - sum(adv)) / 2
    for c, a in zip(text, adv):
        d.text((x, baseline), c, font=f, fill=255, anchor='ls')
        x += a
    m = wide.resize((int(wide.width * 0.95), H), Image.LANCZOS)
    off = (m.width - W) // 2
    return np.asarray(m.crop((off, 0, off + W, H))).astype(np.float32) / 255, ts


def sdf_of(cov):
    """ClockNumerals.build: half-size coverage, exact distances, signed (full px), bevel and range, encoded and smoothed."""
    small = np.asarray(Image.fromarray((cov * 255).astype(np.uint8)).resize((W // 2, H // 2), Image.BOX)).astype(np.float32)
    inside = small >= 128
    d_in = ndimage.distance_transform_edt(inside)
    d_out = ndimage.distance_transform_edt(~inside)
    signed = np.where(inside, d_in - 0.5, -(d_out - 0.5)) * 2.0
    ins = np.sort(signed[signed > 0])
    half = ins[min(len(ins) - 1, int(len(ins) * 0.95))]
    return signed, half


def encode(signed, half, ts):
    rng = max(half, ts * 0.07) + 6
    a = np.clip(0.5 + signed / (2 * rng), 0, 1) * 255
    im = Image.fromarray(np.round(a).astype(np.uint8))
    for _ in range(3):
        im = im.filter(ImageFilter.BoxBlur(1))
    return np.asarray(im).astype(np.float32) / 255, rng


def bilinear(img, x, y):
    return ndimage.map_coordinates(img, [y, x], order=1, mode='nearest')


def wallpaper(kind, seed=3):
    rs = np.random.RandomState(seed)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    if kind == 'dark':
        base = np.stack([0.10 + 0.20 * np.sin(xx / 140) ** 2, 0.08 + 0.15 * np.cos(yy / 90 + xx / 400) ** 2, 0.22 + 0.30 * np.sin((xx + yy) / 220) ** 2], -1)
        blobs = [(160, 90, 90, (0.95, 0.45, 0.25)), (620, 260, 120, (0.25, 0.75, 0.95)), (420, 40, 70, (0.95, 0.3, 0.6))]
    else:
        base = np.stack([0.86 + 0.10 * np.sin(xx / 90), 0.82 + 0.12 * np.cos(yy / 70), 0.78 + 0.16 * np.sin((xx - yy) / 160)], -1)
        blobs = [(220, 200, 120, (0.45, 0.62, 0.95)), (700, 100, 100, (0.98, 0.72, 0.35))]
    img = base
    for cx, cy, r, col in blobs:
        k = np.exp(-((xx - cx) ** 2 + (yy - cy) ** 2) / (2 * r * r))[..., None]
        img = img * (1 - k) + np.array(col) * k
    # Fine detail (leaves, fabric): what a lens visibly bends.
    lines = (np.sin(xx / 7 + 3 * np.sin(yy / 31)) * np.sin(yy / 11 + 2 * np.sin(xx / 47)))[..., None]
    img = img * (1 + 0.10 * lines) + rs.normal(0, 0.012, img.shape)
    return np.clip(img, 0, 1).astype(np.float32)


def blur(img, sigma):
    return np.stack([ndimage.gaussian_filter(img[..., c], sigma, mode='nearest') for c in range(3)], -1)


def saturate(c, s):
    l = (c * np.array([0.2126, 0.7152, 0.0722])).sum(-1, keepdims=True)
    return np.clip(l + (c - l) * s, 0, 1)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def render(wp, text='9:41', s=S):
    sharp = wp
    heavy = blur(wp, 55)                  # Wallpaper.heavy: 1/16 size, box 3 x 3 passes, about 55 px
    cov, ts = numerals_mask(text)
    signed, half = sdf_of(cov)
    sdf, rng = encode(signed, half, ts)
    bevel = half * 0.9
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)

    def sd(x, y):
        return (bilinear(sdf, (x + 0.5) * 0.5 - 0.5, (y + 0.5) * 0.5 - 0.5) - 0.5) * 2 * rng

    def look(ox, oy):
        disp = s['dispersion']
        out = []
        for ch, k in zip(range(3), (1 - disp, 1.0, 1 + disp)):
            a = bilinear(sharp[..., ch], xx + ox * k, yy + oy * k)
            b = bilinear(heavy[..., ch], xx + ox * k, yy + oy * k)
            out.append(a * (1 - s['frost']) + b * s['frost'])
        return np.stack(out, -1)

    d = sd(xx, yy)
    e = 2.0
    gx = sd(xx + e, yy) - sd(xx - e, yy)
    gy = sd(xx, yy + e) - sd(xx, yy - e)
    gl = np.hypot(gx, gy)
    nx = np.where(gl > 1e-4, -gx / np.maximum(gl, 1e-4), 0)
    ny = np.where(gl > 1e-4, -gy / np.maximum(gl, 1e-4), 0)
    inside = np.maximum(d, 0)
    t = np.clip(inside / bevel, 0, 1)
    bend = 1 - np.sqrt(1 - (1 - t) ** 2)
    rf = s['refraction'] * U * min(1, bevel / (s['bevel'] * U))
    col = saturate(look(nx * bend * rf, ny * bend * rf), s['saturation'])
    lum = (look(0 * xx, 0 * yy) * np.array([0.2126, 0.7152, 0.0722])).sum(-1)
    bright = smoothstep(0.45, 0.85, lum) * s['adapt']
    col = col + (1 - col) * (s['tint'] * (1 - bright))[..., None]
    col = col * (1 - 0.3 * bright)[..., None]
    # lightGlass
    a = np.radians(s['light'])
    facing = nx * np.cos(a) + ny * np.sin(a)
    band = np.exp(-inside / (s['glowWidth'] * U))
    col = col + (1 - col) * (s['glow'] * band)[..., None]
    col = col * (1 - s['shade'] * band * np.maximum(-facing, 0))[..., None]
    col = col * (1 - s['edgeDark'] * (1 - smoothstep(0, s['edgeWidth'] * U, inside)))[..., None]
    rim = 1 - smoothstep(0, s['rimWidth'] * U, inside)
    spec = rim * (s['rimBase'] + s['rimLight'] * np.maximum(facing, 0) ** s['specPower'] + s['rimBack'] * np.maximum(-facing, 0) ** s['specPower'])
    col = np.minimum(col + spec[..., None], 1)
    # shadow
    ds = sd(xx, yy - 2 * U)
    sh = 1 - np.clip(-ds / (7 * U), 0, 1)
    shadow_a = 0.16 * (1 + 0.8 * bright) * sh * sh
    under = wp * (1 - shadow_a[..., None])
    return np.clip(col * cov[..., None] + under * (1 - cov[..., None]), 0, 1), bevel, half, ts


if __name__ == '__main__':
    rows = []
    for kind in ('dark', 'light'):
        img, bevel, half, ts = render(wallpaper(kind))
        rows.append(img)
        print(f'{kind}: text size {ts:.0f} px, stroke half width {half:.1f} px, bevel {bevel:.1f} px')
    out = np.concatenate([rows[0], np.ones((8, W, 3)), rows[1]], 0)
    Image.fromarray((out * 255).astype(np.uint8)).save('clock-glass.png')
    print('wrote clock-glass.png')
