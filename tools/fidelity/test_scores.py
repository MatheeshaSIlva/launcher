"""Tests for scores.py. Run: python -m unittest discover -s tools/fidelity   (Pillow only)."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SCORES = os.path.join(HERE, "scores.py")
DIFF = os.path.join(HERE, "diff.py")
W, H = 1080, 2400
BG = (200, 210, 230)

TREE = ('<?xml version="1.0"?><hierarchy rotation="0">'
        '<node class="android.widget.FrameLayout" resource-id="android:id/root" text="" content-desc="" bounds="[0,0][1080,2400]">'
        '<node class="android.view.View" resource-id="x:id/card" text="" content-desc="" bounds="[40,546][1040,813]">'
        '<node class="android.widget.TextView" resource-id="x:id/title" text="Fidelity fixture" content-desc="" bounds="[200,640][800,700]"/>'
        '<node class="android.view.View" resource-id="" text="" content-desc="" bounds="[210,645][430,695]"/>'
        '</node></node></hierarchy>')

REGIONS = {"title-row": [200, 640, 600, 60], "tint": [900, 1500, 90, 100]}
MASKS = {"quick.png": [[0, 2200, 600, 100]]}


def jw(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f)


def jr(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def run(script, *args):
    p = subprocess.run([sys.executable, script] + [str(a) for a in args], capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


class ScoresTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.t = self.tmp.name
        self.g, self.o, self.r, self.trees = [os.path.join(self.t, n) for n in ("golden", "ours", "reports", "trees")]
        os.makedirs(os.path.join(self.trees, "light"))
        with open(os.path.join(self.trees, "light", "quick.xml"), "w", encoding="utf-8") as f:
            f.write(TREE)
        self.masks = os.path.join(self.t, "masks.json")
        jw(self.masks, MASKS)
        self.regions = os.path.join(self.t, "regions.json")
        jw(self.regions, REGIONS)
        for theme in ("light", "dark"):
            os.makedirs(os.path.join(self.g, theme))
            os.makedirs(os.path.join(self.o, theme))
            Image.new("RGB", (W, H), BG).save(os.path.join(self.g, theme, "quick.png"))
            ours = Image.new("RGB", (W, H), BG)
            d = ImageDraw.Draw(ours)
            d.rectangle([220, 650, 419, 689], fill=(20, 20, 20))      # a shape where the title is
            d.rectangle([900, 1500, 989, 1599], fill=(204, 214, 234))  # a tint of +4: a tone cell, no shape
            d.rectangle([100, 2220, 300, 2260], fill=(0, 0, 0))        # inside the mask: not a difference
            ours.save(os.path.join(self.o, theme, "quick.png"))

    def tearDown(self):
        self.tmp.cleanup()

    def diff_all(self, masks=None):
        flat = os.path.join(self.t, "flat.json")
        jw(flat, MASKS["quick.png"] if masks is None else masks)
        for theme in ("light", "dark"):
            rc, out, err = run(DIFF, os.path.join(self.g, theme, "quick.png"), os.path.join(self.o, theme, "quick.png"),
                               "--mask-file", flat, "--regions", self.regions, "--out", os.path.join(self.r, theme, "quick"))
            self.assertEqual(rc, 1, out + err)   # over tolerance: the expected result of a baseline

    def scores(self, *extra):
        return run(SCORES, self.r, "--golden", self.g, "--ours", self.o, "--masks", self.masks, "--trees", self.trees, *extra)

    def test_row_numbers_are_the_reports_and_the_mask_share_is_computed(self):
        self.diff_all()
        rc, out, err = self.scores("--json", os.path.join(self.t, "s.json"), "--md", os.path.join(self.t, "s.md"))
        self.assertEqual(rc, 0, err)
        rows = jr(os.path.join(self.t, "s.json"))
        self.assertEqual([(r["state"], r["theme"]) for r in rows], [("quick", "light"), ("quick", "dark")])
        rep = jr(os.path.join(self.r, "light", "quick", "report.json"))
        r = rows[0]
        self.assertEqual((r["cells_flagged"], r["cells_total"]), (rep["cells_flagged"], rep["cells_total"]))
        self.assertEqual(r["overall_mean"], rep["overall"]["mean"])
        self.assertEqual(r["pixels_differing_pct"], round(rep["overall"]["frac"] * 100, 2))
        self.assertEqual(r["worst"]["mean"], rep["worst"][0]["mean"])
        self.assertAlmostEqual(r["mask_share_pct"], 600 * 100 * 100.0 / (W * H), places=2)
        self.assertEqual(r["regions_flagged"], rep["regions_flagged"])
        with open(os.path.join(self.t, "s.md"), encoding="utf-8") as f:
            table = f.read().splitlines()[2]
        self.assertIn("| quick | light | %d / %d | %s | " % (rep["cells_flagged"], rep["cells_total"], rep["overall"]["mean"]), table)
        self.assertIn("2.31 %", table)   # 600 x 100 over 1080 x 2400

    def test_a_cluster_is_named_by_the_stock_node_it_overlaps_most(self):
        self.diff_all()
        rc, out, err = self.scores("--json", os.path.join(self.t, "s.json"))
        r = jr(os.path.join(self.t, "s.json"))[0]
        self.assertEqual(len(r["clusters"]), 1)   # the masked rectangle is no cluster, the tint has no pixel over the tolerance
        c = r["clusters"][0]
        self.assertIn("title 'Fidelity fixture'", c["node"])   # not the card around it, not the root
        self.assertEqual(c["node_box"], [200, 640, 600, 60])
        self.assertEqual(c["regions"], ["title-row"])   # (the anonymous view [210,645][430,695] is smaller and around it, but has no name)
        x, y, w, h = c["box"]
        self.assertTrue(x <= 220 and y <= 650 and x + w >= 420 and y + h >= 690)
        self.assertIn("title 'Fidelity fixture'", out)

    def test_a_container_is_named_with_the_words_the_cluster_touches(self):
        sys.path.insert(0, HERE)
        import maskgen
        import scores
        nodes = maskgen.load_tree(os.path.join(self.trees, "light", "quick.xml"))[0]
        card = [n for n in nodes if n.id == "card"][0]
        self.assertEqual(scores.node_label(card, (216, 648, 424, 696)), "card containing 'Fidelity fixture'")
        self.assertEqual(scores.node_label(card, (50, 560, 100, 600)), "card")   # touches no words
        self.assertEqual(scores.name_box(nodes, (216, 648, 424, 696), (1080, 2400)).id, "title")

    def test_the_report_says_where_its_files_are_with_forward_slashes(self):
        self.diff_all()
        self.scores("--json", os.path.join(self.t, "s.json"))
        r = jr(os.path.join(self.t, "s.json"))[0]
        self.assertEqual((r["report"], r["worst_png"]), ("light/quick/report.json", "light/quick/worst.png"))

    def test_clusters_over_the_cap_are_counted(self):
        for theme in ("light", "dark"):
            ours = Image.new("RGB", (W, H), BG)
            d = ImageDraw.Draw(ours)
            for i in range(16):   # sixteen shapes far apart, none under the mask
                x, y = 60 + (i % 4) * 250, 300 + (i // 4) * 250
                d.rectangle([x, y, x + 39, y + 39], fill=(20, 20, 20))
            ours.save(os.path.join(self.o, theme, "quick.png"))
        self.diff_all()
        rc, out, err = self.scores("--json", os.path.join(self.t, "s.json"))
        self.assertEqual(rc, 0, err)
        r = jr(os.path.join(self.t, "s.json"))[0]
        self.assertEqual((len(r["clusters"]), r["clusters_more"]), (14, 2))
        self.assertIn("2 more not listed", out)

    def test_a_tint_is_a_tone_cell_not_a_cluster(self):
        self.diff_all()
        self.scores("--json", os.path.join(self.t, "s.json"))
        r = jr(os.path.join(self.t, "s.json"))[0]
        self.assertEqual([(c["col"], c["row"]) for c in r["tone_cells"]], [(10, 15)])
        self.assertIn("tint", r["regions_flagged"])

    def test_masks_other_than_the_ones_diff_ran_with_are_refused(self):
        self.diff_all(masks=[])   # diff.py ran without the mask: its count and ours with the mask differ
        rc, out, err = self.scores()
        self.assertEqual(rc, 2)
        self.assertIn("the report says", err)

    def test_an_overall_mean_or_worst_cell_that_the_pictures_do_not_give_is_refused(self):
        self.diff_all()
        path = os.path.join(self.r, "light", "quick", "report.json")
        rep = jr(path)
        rep["overall"]["mean"] += 0.001
        jw(path, rep)
        rc, out, err = self.scores()
        self.assertEqual(rc, 2)
        self.assertIn("overall mean", err)
        rep["overall"]["mean"] -= 0.001
        rep["worst"][0]["mean"] += 0.5
        jw(path, rep)
        rc, out, err = self.scores()
        self.assertEqual(rc, 2)
        self.assertIn("worst cell", err)

    def test_missing_reports_pictures_or_trees_exit_2(self):
        rc, out, err = self.scores()
        self.assertEqual(rc, 2)   # nothing was diffed
        self.diff_all()
        os.remove(os.path.join(self.o, "dark", "quick.png"))
        self.assertEqual(self.scores()[0], 2)
        Image.new("RGB", (W, H), BG).save(os.path.join(self.o, "dark", "quick.png"))
        os.remove(os.path.join(self.trees, "light", "quick.xml"))
        self.assertEqual(self.scores()[0], 2)

    def test_a_passing_picture_has_no_clusters_and_no_worst_cell(self):
        Image.new("RGB", (W, H), BG).save(os.path.join(self.o, "light", "quick.png"))
        flat = os.path.join(self.t, "flat.json")
        jw(flat, [])
        rc, out, err = run(DIFF, os.path.join(self.g, "light", "quick.png"), os.path.join(self.o, "light", "quick.png"),
                           "--mask-file", flat, "--out", os.path.join(self.r, "light", "quick"))
        self.assertEqual(rc, 0)
        jw(self.masks, {})
        rc, out, err = self.scores("--json", os.path.join(self.t, "s.json"))
        self.assertEqual(rc, 0, err)
        r = jr(os.path.join(self.t, "s.json"))[0]
        self.assertEqual((r["cells_flagged"], r["clusters"], r["worst"], r["pass"]), (0, [], None, True))


if __name__ == "__main__":
    unittest.main()
