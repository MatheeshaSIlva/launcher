"""Tests for diff.py. Run: python -m unittest discover -s tools/fidelity   (Pillow only)."""
import json
import os
import random
import subprocess
import sys
import tempfile
import unittest

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
DIFF = os.path.join(HERE, "diff.py")
BG = (20, 24, 32)
W, H = 1080, 2400   # a real S24 / emulator screenshot; grid cells are then 90 x 100 px


def jload(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def run(*args):
    p = subprocess.run([sys.executable, DIFF] + [str(a) for a in args], capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


def tile(path, fill, radius=0, box=(140, 200, 940, 520), bg=BG, shift=0):
    img = Image.new("RGB", (W, H), bg)
    x0, y0, x1, y1 = box
    ImageDraw.Draw(img).rounded_rectangle([x0 + shift, y0, x1 + shift, y1], radius=radius, fill=fill)
    img.save(path)
    return path


class DiffTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.t = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def p(self, name):
        return os.path.join(self.t, name)

    def test_identical_passes(self):
        a = tile(self.p("a.png"), (42, 97, 153), 40)
        b = tile(self.p("b.png"), (42, 97, 153), 40)
        code, out, _ = run(a, b, "--out", self.p("o"))
        self.assertEqual(code, 0, out)
        self.assertTrue(jload(self.p("o/report.json"))["pass"])

    def test_b046_tile_colour_is_caught(self):
        # B-046: ours (42,97,153) against Android 17's (67,96,138) on the emulator; passed review by eye.
        ref = tile(self.p("ref.png"), (67, 96, 138), 40)
        ours = tile(self.p("ours.png"), (42, 97, 153), 40)
        code, out, _ = run(ref, ours, "--out", self.p("o"))
        self.assertEqual(code, 1, out)
        rep = jload(self.p("o/report.json"))
        self.assertGreater(rep["cells_flagged"], 3)
        self.assertGreaterEqual(rep["worst"][0]["mean"], 10)
        self.assertTrue(os.path.isfile(self.p("o/heatmap.png")))
        self.assertTrue(os.path.isfile(self.p("o/worst.png")))

    def test_corner_radius_is_caught_and_same_radius_is_not(self):
        # Stage 8: an active tile's corners were wrong ("called the same"). 24 dp vs 28 dp at 2.625 px/dp.
        ref = tile(self.p("ref.png"), (67, 96, 138), 63)
        ours = tile(self.p("ours.png"), (67, 96, 138), 74)
        self.assertEqual(run(ref, ours)[0], 1)
        same = tile(self.p("same.png"), (67, 96, 138), 63)
        self.assertEqual(run(ref, same)[0], 0)

    def test_small_noise_does_not_alarm(self):
        rnd = random.Random(7)
        a = Image.open(tile(self.p("a.png"), (42, 97, 153), 40)).convert("RGB")
        b = a.copy()
        px = b.load()
        for _ in range(240000):
            x, y = rnd.randrange(W), rnd.randrange(H)
            r, g, bl = px[x, y]
            px[x, y] = (r + rnd.randint(-3, 3), g + rnd.randint(-3, 3), bl + rnd.randint(-3, 3))
        b.save(self.p("b.png"))
        code, out, _ = run(self.p("a.png"), self.p("b.png"))
        self.assertEqual(code, 0, out)

    def test_one_pixel_shift_passes_by_default_and_fails_when_tightened(self):
        ref = tile(self.p("ref.png"), (67, 96, 138), 0)
        ours = tile(self.p("ours.png"), (67, 96, 138), 0, shift=1)
        self.assertEqual(run(ref, ours)[0], 0)
        self.assertEqual(run(ref, ours, "--tol-frac", "0.005")[0], 1)

    def test_size_mismatch_is_not_comparable(self):
        a = tile(self.p("a.png"), (1, 2, 3))
        Image.new("RGB", (W + 10, H), BG).save(self.p("b.png"))
        code, _, err = run(a, self.p("b.png"))
        self.assertEqual(code, 2)
        self.assertIn("size differs", err)

    def test_missing_file_is_not_comparable(self):
        a = tile(self.p("a.png"), (1, 2, 3))
        self.assertEqual(run(a, self.p("nope.png"))[0], 2)

    def test_mask_ignores_a_region(self):
        ref = tile(self.p("ref.png"), (67, 96, 138), 0)
        ours = tile(self.p("ours.png"), (67, 96, 138), 0)
        img = Image.open(ours)
        ImageDraw.Draw(img).rectangle([200, 240, 600, 320], fill=(250, 250, 250))  # "text" in another font
        img.save(ours)
        self.assertEqual(run(ref, ours)[0], 1)
        self.assertEqual(run(ref, ours, "--mask", "180,220,460,120")[0], 0)

    def test_named_regions_get_their_own_line(self):
        ref = tile(self.p("ref.png"), (67, 96, 138), 0)
        ours = tile(self.p("ours.png"), (60, 90, 130), 0)
        with open(self.p("r.json"), "w") as f:
            json.dump({"active tile": [140, 200, 800, 320], "background": [0, 1200, 1080, 1000]}, f)
        code, out, _ = run(ref, ours, "--regions", self.p("r.json"), "--out", self.p("o"))
        self.assertEqual(code, 1, out)
        rep = jload(self.p("o/report.json"))
        self.assertEqual(rep["regions_flagged"], ["active tile"])
        self.assertFalse(rep["regions"]["background"]["flagged"])

    def test_folder_mode_reports_each_file_and_missing_ones(self):
        os.makedirs(self.p("ref")), os.makedirs(self.p("ours"))
        tile(self.p("ref/same.png"), (1, 2, 3))
        tile(self.p("ours/same.png"), (1, 2, 3))
        tile(self.p("ref/off.png"), (67, 96, 138), 40)
        tile(self.p("ours/off.png"), (42, 97, 153), 40)
        tile(self.p("ref/gone.png"), (1, 2, 3))
        code, out, _ = run(self.p("ref"), self.p("ours"), "--out", self.p("o"))
        self.assertEqual(code, 1)
        self.assertIn("PASS same.png", out)
        self.assertIn("FAIL off.png", out)
        self.assertIn("FAIL gone.png: missing in ours", out)
        summary = jload(self.p("o/summary.json"))
        self.assertTrue(summary["same.png"]["pass"])
        self.assertFalse(summary["off.png"]["pass"])


if __name__ == "__main__":
    unittest.main()
