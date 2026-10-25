"""Tests for headroom.py. Run: python -m unittest discover -s tools/fidelity   (Pillow only)."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
HEADROOM = os.path.join(HERE, "headroom.py")
DIFF = os.path.join(HERE, "diff.py")
BG = (20, 24, 32)
W, H = 1080, 2400   # grid cells are 90 x 100 px
REGION = "296,608,233,190"


def run(script, *args):
    p = subprocess.run([sys.executable, script] + [str(a) for a in args], capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


def pic(path, boxes=(), bg=BG):
    """boxes: (x, y, w, h, colour) rectangles on a plain background."""
    img = Image.new("RGB", (W, H), bg)
    d = ImageDraw.Draw(img)
    for x, y, w, h, col in boxes:
        d.rectangle([x, y, x + w - 1, y + h - 1], fill=col)
    img.save(path)
    return path


class HeadroomTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.t = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def p(self, n):
        return os.path.join(self.t, n)

    def head(self, a, b, *extra):
        code, out, err = run(HEADROOM, a, b, *extra)
        self.assertEqual(code, 0, err)
        return json.loads(out)

    def test_identical_is_zero(self):
        a = pic(self.p("a.png"), [(100, 100, 300, 300, (200, 10, 10))])
        b = pic(self.p("b.png"), [(100, 100, 300, 300, (200, 10, 10))])
        r = self.head(a, b)
        self.assertEqual((r["overall_mean"], r["worst_cell_mean"], r["flagged"]), (0.0, 0.0, 0))

    def test_sub_threshold_noise_is_measured_but_not_flagged(self):
        # a whole picture 1 grey step off: mean 1, nothing flagged (diff.py passes it), headroom shows the 1
        a = pic(self.p("a.png"))
        b = pic(self.p("b.png"), bg=(21, 24, 32))
        r = self.head(a, b)
        self.assertAlmostEqual(r["overall_mean"], 1.0, places=2)
        self.assertAlmostEqual(r["worst_cell_mean"], 1.0, places=2)
        self.assertEqual(r["flagged"], 0)
        self.assertEqual(run(DIFF, a, b)[0], 0)

    def test_worst_cell_is_found_whether_flagged_or_not(self):
        # one cell (col 3, row 5: 270..360 x 500..600) is off by 1 (not flagged) or by 2 (reaches diff's tol-mean)
        a = pic(self.p("a.png"))
        b = pic(self.p("b.png"), [(270, 500, 90, 100, (21, 24, 32))])
        c = pic(self.p("c.png"), [(270, 500, 90, 100, (22, 24, 32))])
        r1 = self.head(a, b)
        self.assertEqual((r1["worst_cell"]["col"], r1["worst_cell"]["row"]), (3, 5))
        self.assertAlmostEqual(r1["worst_cell_mean"], 1.0, places=2)
        self.assertEqual(r1["flagged"], 0)
        r2 = self.head(a, c)
        self.assertAlmostEqual(r2["worst_cell_mean"], 2.0, places=2)
        self.assertEqual(r2["flagged"], 1)

    def test_flagged_count_agrees_with_diff_py(self):
        a = pic(self.p("a.png"), [(296, 608, 233, 190, (67, 96, 138))])
        b = pic(self.p("b.png"), [(296, 608, 233, 190, (42, 97, 153))])
        r = self.head(a, b)
        code, _, _ = run(DIFF, a, b, "--out", self.p("o"))
        self.assertEqual(code, 1)
        with open(self.p("o/report.json"), encoding="utf-8") as f:
            rep = json.load(f)
        self.assertEqual(r["flagged"], rep["cells_flagged"])
        self.assertAlmostEqual(r["overall_mean"], rep["overall"]["mean"], places=3)
        self.assertGreater(r["flagged"], 0)

    def test_outside_region_counts_only_cells_beyond_one_cell_margin(self):
        a = pic(self.p("a.png"))
        inside = pic(self.p("in.png"), [(296, 608, 233, 190, (200, 10, 10))])
        r = self.head(a, inside, "--region", REGION)
        self.assertGreater(r["flagged"], 0)
        self.assertEqual(r["flagged_outside"], 0)
        # the same change plus a patch far from the tile: its cells are outside
        far = pic(self.p("far.png"), [(296, 608, 233, 190, (200, 10, 10)), (800, 1500, 150, 150, (200, 10, 10))])
        r2 = self.head(a, far, "--region", REGION)
        self.assertGreater(r2["flagged_outside"], 0)
        self.assertEqual(r2["flagged"], r["flagged"] + r2["flagged_outside"])
        # a patch at the region's edge is inside (its cell lies wholly in the region grown by a cell); one in the next
        # cells over is not (cells are counted whole: the margin never lets a cell stick out)
        near = pic(self.p("near.png"), [(500, 700, 20, 20, (200, 10, 10))])
        self.assertEqual(self.head(a, near, "--region", REGION)["flagged_outside"], 0)
        away = pic(self.p("away.png"), [(560, 700, 60, 60, (200, 10, 10))])
        self.assertGreater(self.head(a, away, "--region", REGION)["flagged_outside"], 0)

    def test_size_mismatch_and_missing_file_exit_2(self):
        a = pic(self.p("a.png"))
        Image.new("RGB", (W + 2, H), BG).save(self.p("b.png"))
        self.assertEqual(run(HEADROOM, a, self.p("b.png"))[0], 2)
        self.assertEqual(run(HEADROOM, a, self.p("nope.png"))[0], 2)


if __name__ == "__main__":
    unittest.main()
