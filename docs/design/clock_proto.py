"""Offline render of the clock's masked glass (the AGSL_MASK maths of GlassDrawable, ported to numpy) with the old and
the new IOS_CLOCK values side by side, over two wallpapers (dark and light), so the look can be judged without a phone.
Writes clock_preview.png."""
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 540, 300          # the numerals' box, half the S24's 4x2 widget at half resolution
PT = 540 / 402.0 * 2     # one iOS point in px of the widget (the widget spans the full width: 402 pt -> 1080 px; here half)
PT = 1080 / 402.0 / 2


def wallpaper(kind):
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    if kind == 'dark':
        img = np.stack([0.08 + 0.25 * np.sin(x / 90) ** 2, 0.10 + 0.2 * np.cos(y / 70 + x / 300) ** 2, 0.25 + 0.35 * np.sin((x + y) / 160) ** 2], -1)
        # a few bright blobs
        for (cx, cy, r, col) in [(120, 80, 70, (0.9, 0.5, 0.2)), (400, 220, 90, (0.3, 0.8, 0.9)), (300, 60, 50, (0.95, 0.3, 0.6))]:
            d = np.exp(-((x - cx) ** 2 + (y - cy) ** 2) / (2 * r * r))
            img = img * (1 - d[..., None]) + np.array(col)[None, None] * d[..., None]
    else:
        img = np.stack([0.85 + 0.12 * np.sin(x / 60), 0.80 + 0.15 * np.cos(y / 50), 0.75 + 0.2 * np.sin((x - y) / 120)], -1)
        for (cx, cy, r, col) in [(150, 150, 90, (0.4, 0.6, 0.95)), (420, 90, 70, (0.98, 0.7, 0.3))]:
            d = np.exp(-((x - cx) ** 2 + (y - cy) ** 2) / (2 * r * r))
            img = img * (1 - d[..., None]) + np.array(col)[None, None] * d[..., None]
    return np.clip(img, 0, 1).astype(np.float32)


def blur(img, sigma):
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8))
    return np.asarray(im.filter(ImageFilter.GaussianBlur(sigma))).astype(np.float32) / 255


def box_blur_alpha(a, r):
    im = Image.fromarray((a * 255).astype(np.uint8))
    for _ in range(3):
        im = im.filter(ImageFilter.BoxBlur(r))
    return np.asarray(im).astype(np.float32) / 255


def text_mask(w, h, text, size):
    im = Image.new('L', (w, h), 0)
    d = ImageDraw.Draw(im)
    try:
        f = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', size)
    except Exception:
        f = ImageFont.load_default()
    bbox = d.textbbox((0, 0), text, font=f)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    d.text(((w - tw) / 2 - bbox[0], (h - th) / 2 - bbox[1]), text, fill=255, font=f)
    return np.asarray(im).astype(np.float32) / 255


def sample(img, sx, sy):
    h, w = img.shape[:2]
    xi = np.clip(sx, 0, w - 1).astype(np.int32)
    yi = np.clip(sy, 0, h - 1).astype(np.int32)
    return img[yi, xi]


def saturate(c, s):
    l = (c * np.array([0.2126, 0.7152, 0.0722])).sum(-1, keepdims=True)
    return np.clip(l + (c - l) * s, 0, 1)


def render(wp, style, text='9:41'):
    sharp = wp
    frost = blur(wp, 1.5 * PT)          # Wallpaper.blurred: about 1.5 pt
    size = int(H * 0.94 * 1.05)
    mask = text_mask(W, H, text, size)
    blur_px = size * 0.07
    hs = 0.5
    small = np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).resize((int(W * hs), int(H * hs)))).astype(np.float32) / 255
    height = box_blur_alpha(small, max(1, int(round(blur_px * hs))))
    hfull = np.asarray(Image.fromarray((height * 255).astype(np.uint8)).resize((W, H), Image.BILINEAR)).astype(np.float32) / 255
    # gradient of the height field at the half-size grid, in the shader's units (per half-size pixel)
    hx = np.roll(hfull, -2, 1) - np.roll(hfull, 2, 1)
    hy = np.roll(hfull, -2, 0) - np.roll(hfull, 2, 0)
    gl = np.hypot(hx, hy)
    nx = np.where(gl > 1e-5, -hx / np.maximum(gl, 1e-5), 0)
    ny = np.where(gl > 1e-5, -hy / np.maximum(gl, 1e-5), 0)
    steep = np.clip(gl * blur_px * hs, 0, 1)
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    bright_src = saturate(frost, style['saturation'])
    lum = (bright_src * np.array([0.2126, 0.7152, 0.0722])).sum(-1)
    bright = np.clip((lum - 0.45) / 0.4, 0, 1)
    bright = bright * bright * (3 - 2 * bright) * style['adapt']
    refr = style['refraction'] * PT
    offx = nx * steep * refr
    offy = ny * steep * refr
    disp = style['dispersion']
    frost_amt = style['frost']

    def look(ox, oy):
        cols = []
        for ch, k in zip(range(3), [1 - disp, 1.0, 1 + disp]):
            c_s = sample(sharp, x + ox * k, y + oy * k)[..., ch]
            c_f = sample(frost, x + ox * k, y + oy * k)[..., ch]
            cols.append(c_s * (1 - frost_amt) + c_f * frost_amt)
        return np.stack(cols, -1)

    col = saturate(look(offx, offy), style['saturation'])
    col = col + (1 - col) * (style['tint'] * (1 - bright))[..., None]
    col = col * (1 - 0.22 * bright)[..., None]
    inside = np.maximum(hfull - 0.5, 0) * 2 * blur_px
    # lightGlass
    ldir = np.array([np.cos(np.radians(225)), np.sin(np.radians(225))])
    facing = nx * ldir[0] + ny * ldir[1]
    band = np.exp(-inside / (style['glowWidth'] * PT))
    col = col + (1 - col) * (style['glow'] * band)[..., None]
    col = col * (1 - style['shade'] * band * np.maximum(-facing, 0))[..., None]
    ew = style['edgeWidth'] * PT
    col = col * (1 - style['edgeDark'] * (1 - np.clip(inside / ew, 0, 1) ** 2 * (3 - 2 * np.clip(inside / ew, 0, 1))))[..., None]
    rim = 1 - np.clip(inside / (style['rimWidth'] * PT), 0, 1)
    spec = rim * (style['rimBase'] + style['rimLight'] * np.maximum(facing, 0) ** style['specPower'] + style['rimBack'] * np.maximum(-facing, 0) ** style['specPower'])
    col = np.minimum(col + spec[..., None], 1)
    col = col * (1 - 0.28 * bright * (1 - np.clip(inside / (blur_px * 0.6), 0, 1)))[..., None]
    shadow_a = 0.14 * (1 + 2.5 * bright) * hfull * hfull
    out = wp * (1 - shadow_a[..., None]) * (1 - mask[..., None]) + col * mask[..., None]
    return np.clip(out, 0, 1)


OLD = dict(refraction=20, dispersion=0.12, frost=1.0, saturation=1.15, tint=0.12, glowWidth=8, glow=0.22, shade=0,
           rimWidth=1.6, rimBase=0.25, rimLight=0.45, rimBack=0.26, edgeDark=0, edgeWidth=1, specPower=1.2, adapt=1)
NEW = dict(refraction=26, dispersion=0.3, frost=1.0, saturation=1.22, tint=0, glowWidth=6, glow=0, shade=0.22,
           rimWidth=1.3, rimBase=0.06, rimLight=0.5, rimBack=0.14, edgeDark=0.2, edgeWidth=1.4, specPower=1.8, adapt=0.7)

rows = []
for kind in ['dark', 'light']:
    wp = wallpaper(kind)
    rows.append(np.concatenate([render(wp, OLD), np.ones((H, 8, 3)), render(wp, NEW)], 1))
img = np.concatenate(rows, 0)
Image.fromarray((img * 255).astype(np.uint8)).save('clock_preview.png')
print('wrote clock_preview.png', img.shape)
