"""Tests for stems.py. Run: python -m unittest discover -s tools/fidelity   (Pillow only)."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
STEMS = os.path.join(HERE, "stems.py")
sys.path.insert(0, HERE)
import stems  # noqa: E402

LIGHT, DARK = (250, 250, 250), (20, 20, 20)
BOX = (10, 10, 110, 60)   # x0, y0, x1, y1


def run(*args):
    p = subprocess.run([sys.executable, STEMS] + [str(a) for a in args], capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


def bars(path, width, bg, ink, edge=None):
    """Vertical bars of `width` whole px inside BOX (background along its top and bottom rows); `edge`: one more column
    of that grey on each bar's right (an antialiased edge)."""
    img = Image.new("RGB", (200, 100), bg)
    d = ImageDraw.Draw(img)
    for x in range(20, 100, 20):
        d.rectangle([x, 20, x + width - 1, 49], fill=ink)
        if edge is not None:
            d.line([x + width, 20, x + width, 49], fill=edge)
    img.save(path)
    return path


class StemTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.t = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def p(self, name):
        return os.path.join(self.t, name)

    def stem_of(self, path):
        return stems.stem(Image.open(path), BOX)

    def test_whole_bars_dark_on_light(self):
        for w in (2, 3, 5):
            r = self.stem_of(bars(self.p("d%d.png" % w), w, LIGHT, DARK))
            self.assertEqual(r["stem"], w)
            self.assertEqual(r["runs"], 4 * 30)

    def test_whole_bars_light_on_dark(self):
        for w in (2, 3, 5):
            self.assertEqual(self.stem_of(bars(self.p("l%d.png" % w), w, DARK, LIGHT))["stem"], w)

    def test_antialiased_edge_counts_in_part(self):
        # an edge 90 grey levels from the background is half a pixel of ink, both polarities
        self.assertEqual(self.stem_of(bars(self.p("ad.png"), 3, LIGHT, DARK, edge=(160, 160, 160)))["stem"], 3.5)
        self.assertEqual(self.stem_of(bars(self.p("al.png"), 3, DARK, LIGHT, edge=(110, 110, 110)))["stem"], 3.5)

    def test_a_heavier_weight_reads_wider(self):
        a = self.stem_of(bars(self.p("a.png"), 3, LIGHT, DARK, edge=(200, 200, 200)))["stem"]
        b = self.stem_of(bars(self.p("b.png"), 4, LIGHT, DARK, edge=(200, 200, 200)))["stem"]
        self.assertAlmostEqual(b - a, 1.0, places=2)

    def test_faint_fringe_is_not_a_stroke(self):
        img = Image.new("RGB", (200, 100), LIGHT)
        ImageDraw.Draw(img).line([50, 20, 50, 49], fill=(200, 200, 200))   # 0.28 px of ink: under MIN_RUN
        self.assertEqual(stems.stem(img, BOX), {"stem": None, "runs": 0})

    def test_cli_boxes_and_delta(self):
        s = bars(self.p("s.png"), 3, LIGHT, DARK)
        o = bars(self.p("o.png"), 4, LIGHT, DARK)
        rc, out, err = run(s, o, "--box", "text=10,10,110,60")
        self.assertEqual(rc, 0, err)
        r = json.loads(out)["boxes"][0]
        self.assertEqual((r["name"], r["box"], r["stock"]["stem"], r["ours"]["stem"], r["delta"]),
                         ("text", [10, 10, 110, 60], 3, 4, 1.0))
        f = self.p("boxes.json")
        with open(f, "w") as fh:
            json.dump({"a": [10, 10, 110, 60], "empty": [120, 10, 190, 60]}, fh)
        rc, out, err = run(s, o, "--boxes", f)
        self.assertEqual(rc, 0, err)
        got = {b["name"]: b for b in json.loads(out)["boxes"]}
        self.assertEqual(got["a"]["delta"], 1.0)
        self.assertEqual((got["empty"]["stock"]["stem"], got["empty"]["delta"]), (None, None))

    def test_cli_refuses_what_it_cannot_measure(self):
        s = bars(self.p("s.png"), 3, LIGHT, DARK)
        for args in (["--box", "x=0,0,300,50"], ["--box", "x=1,2,3"], ["--box", "1,2,3,4"], []):
            rc, _, err = run(s, s, *args)
            self.assertEqual(rc, 2, args)
            self.assertTrue(err.startswith("stems.py: "), err)
        rc, _, _ = run(self.p("missing.png"), s, "--box", "x=10,10,110,60")
        self.assertEqual(rc, 2)


if __name__ == "__main__":
    unittest.main()
