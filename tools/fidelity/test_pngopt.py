"""Tests for pngopt.py. Run: python -m unittest discover -s tools/fidelity   (Pillow only)."""
import hashlib
import os
import random
import subprocess
import sys
import tempfile
import unittest

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PNGOPT = os.path.join(HERE, "pngopt.py")
sys.path.insert(0, HERE)
import pngopt  # noqa: E402


def run(*args):
    p = subprocess.run([sys.executable, PNGOPT] + [str(a) for a in args], capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


def ui_like(w=120, h=240, mode="RGBA"):
    """Flat areas, a few boxes and some noise: like a screenshot of a shade."""
    im = Image.new("RGB", (w, h), (20, 24, 32))
    d = ImageDraw.Draw(im)
    d.rectangle([10, 10, 100, 60], fill=(60, 120, 200))
    d.rectangle([10, 80, 100, 130], fill=(200, 200, 210))
    rnd = random.Random(7)
    for _ in range(300):
        im.putpixel((rnd.randrange(w), rnd.randrange(h)), tuple(rnd.randrange(256) for _ in range(3)))
    return im.convert(mode)


class PngoptTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def path(self, name):
        return os.path.join(self.dir, name)

    def test_reencode_keeps_every_pixel_and_is_never_bigger(self):
        src = self.path("a.png")
        im = ui_like()
        im.save(src)   # RGBA, unoptimised
        code, out, err = run(src, self.path("b.png"))
        self.assertEqual(code, 0, err)
        a, b, md5 = out.split()
        self.assertLessEqual(int(b), int(a))
        self.assertEqual(int(b), os.path.getsize(self.path("b.png")))
        self.assertEqual(md5, hashlib.md5(im.convert("RGB").tobytes()).hexdigest())
        back = Image.open(self.path("b.png"))
        self.assertEqual(back.mode, "RGB")
        self.assertEqual(back.tobytes(), im.convert("RGB").tobytes())
        self.assertFalse(os.path.exists(self.path("b.png.tmp.png")))

    def test_an_input_that_is_already_smallest_is_copied_as_it_is(self):
        src = self.path("a.png")
        ui_like(mode="RGB").save(src, optimize=True, compress_level=9)
        code, _out, err = run(src, self.path("b.png"))
        self.assertEqual(code, 0, err)
        self.assertLessEqual(os.path.getsize(self.path("b.png")), os.path.getsize(src))

    def test_md5_is_of_the_pixels_not_of_the_file(self):
        im = ui_like()
        im.save(self.path("a.png"))
        im.convert("RGB").save(self.path("b.png"), optimize=True)
        with open(self.path("a.png"), "rb") as fa, open(self.path("b.png"), "rb") as fb:
            self.assertNotEqual(fa.read(), fb.read())
        ca, oa, _ = run("--md5", self.path("a.png"))
        cb, ob, _ = run("--md5", self.path("b.png"))
        self.assertEqual((ca, cb), (0, 0))
        self.assertEqual(oa.strip(), ob.strip())

    def test_a_lossy_encoder_is_caught_and_leaves_nothing(self):
        src = self.path("a.png")
        ui_like().save(src)

        def lossy(im, path):   # a quantised copy: not the same pixels
            im.quantize(8).convert("RGB").save(path)

        with self.assertRaises(pngopt.Refused):
            pngopt.reencode(src, self.path("b.png"), encode=lossy)
        self.assertFalse(os.path.exists(self.path("b.png")))
        self.assertFalse(os.path.exists(self.path("b.png.tmp.png")))

    def test_one_changed_pixel_is_caught(self):
        src = self.path("a.png")
        ui_like().save(src)

        def off_by_one(im, path):
            im = im.copy()
            r, g, b = im.getpixel((3, 3))
            im.putpixel((3, 3), (r ^ 1, g, b))
            im.save(path, optimize=True)

        with self.assertRaises(pngopt.Refused):
            pngopt.reencode(src, self.path("b.png"), encode=off_by_one)
        self.assertFalse(os.path.exists(self.path("b.png")))

    def test_transparency_a_palette_and_grey_are_refused_not_converted(self):
        see_through = ui_like()
        see_through.putpixel((5, 5), (1, 2, 3, 200))
        see_through.save(self.path("t.png"))
        ui_like(mode="RGB").quantize(16).save(self.path("p.png"))
        ui_like(mode="L").save(self.path("l.png"))
        for name in ("t.png", "p.png", "l.png"):
            code, _out, err = run(self.path(name), self.path("out_" + name))
            self.assertEqual(code, 1, name)
            self.assertIn("REFUSED", err)
            self.assertFalse(os.path.exists(self.path("out_" + name)), name)

    def test_a_file_that_is_not_a_png_is_refused(self):
        with open(self.path("x.png"), "wb") as f:
            f.write(b"not a picture")
        ui_like(mode="RGB").save(self.path("j.jpg"))
        for name in ("x.png", "j.jpg", "missing.png"):
            code, _out, err = run("--check", self.path(name))
            self.assertEqual(code, 1, name)
            self.assertIn("REFUSED", err)

    def test_check_says_bytes_and_md5_and_the_size_is_enforced(self):
        im = ui_like(30, 40)
        im.save(self.path("a.png"))
        code, out, _ = run("--check", self.path("a.png"), "--size", "30x40")
        self.assertEqual(code, 0)
        n, md5 = out.split()
        self.assertEqual(int(n), os.path.getsize(self.path("a.png")))
        self.assertEqual(md5, hashlib.md5(im.convert("RGB").tobytes()).hexdigest())
        code, _out, err = run("--check", self.path("a.png"), "--size", "1080x2400")
        self.assertEqual(code, 1)
        self.assertIn("30x40", err)

    def test_usage_errors_exit_2(self):
        self.assertEqual(run()[0], 2)
        self.assertEqual(run("--bogus", "x")[0], 2)
        self.assertEqual(run("--check", "a.png", "--size", "nope")[0], 2)


if __name__ == "__main__":
    unittest.main()
