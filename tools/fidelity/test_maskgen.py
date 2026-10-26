"""Tests for maskgen.py. Run: python -m unittest discover -s tools/fidelity   (standard library only; diff.py's own test reads a mask file)."""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
MASKGEN = os.path.join(HERE, "maskgen.py")
sys.path.insert(0, HERE)
import maskgen  # noqa: E402


def jr(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def node(cls, rid, text, desc, bounds, kids=""):
    return '<node class="android.widget.%s" resource-id="%s" text="%s" content-desc="%s" bounds="%s">%s</node>' % (
        cls, ("com.android.systemui:id/" + rid) if rid else "", text, desc, bounds, kids)


def tree(inner):
    return '<?xml version="1.0"?><hierarchy rotation="0">%s</hierarchy>' % node("FrameLayout", "root", "", "", "[0,0][1080,2400]", inner)


# stock's quick state in miniature: a header, two tiles, a card (a title inside), a Silent row, the footer buttons
QUICK = tree(
    node("View", "shade_header_root", "", "", "[0,0][1080,63]") +
    node("View", "tile_expandable", "", "", "[42,63][530,252]", node("TextView", "", "Wi-Fi", "", "[226,118][509,161]")) +
    node("View", "tile_expandable", "", "", "[551,63][1038,252]", node("View", "", "", "Bluetooth.", "[572,84][719,231]")) +
    node("ScrollView", "notification_stack_scroller", "", "", "[0,0][1080,2316]",
         node("FrameLayout", "expandableNotificationRow", "", "", "[42,546][1038,813]",
              node("TextView", "title", "Fidelity fixture", "", "[231,646][996,697]")) +
         node("FrameLayout", "expandableNotificationRow", "", "", "[42,991][1038,1181]",
              node("TextView", "title", "Silent row", "", "[231,1034][758,1085]")) +
         node("Button", "dismiss_text", "Clear all", "", "[220,1413][860,1539]") +
         node("Button", "history_button", "", "", "[42,1413][199,1539]")))
# a notification row as stock draws a title with a time label: top line (title, divider, time), body text under it
ROW = tree(
    node("View", "shade_header_root", "", "", "[0,0][1080,63]") +
    node("FrameLayout", "expandableNotificationRow", "", "", "[42,991][1038,1181]",
         node("LinearLayout", "notification_top_line", "", "", "[231,1034][902,1085]",
              node("TextView", "title", "A notification with a long title", "", "[231,1034][758,1085]") +
              node("TextView", "time_divider", "", "", "[768,1040][779,1083]") +
              node("TextView", "time", "now", "", "[789,1040][849,1083]")) +
         node("LinearLayout", "notification_main_column", "", "", "[231,1085][1038,1149]",
              node("TextView", "text", "Body", "", "[231,1085][996,1138]"))))
EXPANDED = tree(
    node("View", "shade_header_root", "", "", "[42,0][1038,315]") +
    node("View", "tile_expandable", "", "", "[42,610][530,799]", node("TextView", "", "Wi-Fi", "", "[226,665][509,708]")) +
    node("View", "qs_footer_actions", "", "", "[0,2179][1080,2337]",
         node("View", "multi_user_switch", "", "", "[672,2201][798,2327]")))

RULES = {
    "format": 1,
    "max_share": 0.06,
    "rules": [
        {"id": "title", "kind": "node", "match": {"resource_id": "^title$"}, "pad": [40, 6], "text_node": True, "required": True,
         "states": ["quick"], "why": "family-named weight", "evidence": "fixture"},
        {"id": "clear", "kind": "node", "match": {"resource_id": "^dismiss_text$"}, "center": [300, 72], "text_node": True,
         "states": ["quick"], "why": "family-named weight", "evidence": "fixture"},
        {"id": "pill", "kind": "before", "anchor": {"resource_id": "^qs_footer_actions$"}, "until": {"resource_id": "^multi_user_switch$"},
         "pad": [0, -21], "states": ["expanded"], "why": "ours lists a pill", "evidence": "fixture"},
        {"id": "tail", "kind": "below", "enabled": False, "anchor": {"text": "^Fidelity fixture$"}, "climb": "^expandableNotificationRow$",
         "until": {"resource_id": "^notification_stack_scroller$"}, "why": "weighed, not used", "evidence": "fixture"},
    ],
    "regions": [
        {"id": "tiles", "name": "tile-{n}-{label}", "match": {"resource_id": "^tile_expandable$"}, "protect": True},
        {"id": "header", "name": "header", "match": {"resource_id": "^shade_header_root$"}, "first": True, "protect": True},
        {"id": "card", "name": "first-card", "match": {"resource_id": "^expandableNotificationRow$"}, "first": True, "protect": True},
        {"id": "f1", "name": "footer", "match": {"resource_id": "^(history_button|dismiss_text)$"}, "union": True},
        {"id": "f2", "name": "footer", "match": {"resource_id": "^qs_footer_actions$"}, "first": True},
    ],
}


class MaskgenTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.t = self.tmp.name
        for theme in ("light", "dark"):
            os.makedirs(os.path.join(self.t, "stock", theme))
            for state, xml in (("quick", QUICK), ("expanded", EXPANDED)):
                with open(os.path.join(self.t, "stock", theme, state + ".xml"), "w", encoding="utf-8") as f:
                    f.write(xml)

    def tearDown(self):
        self.tmp.cleanup()

    def rules_file(self, rules=None):
        p = os.path.join(self.t, "rules.json")
        with open(p, "w", encoding="utf-8") as f:
            json.dump(rules or RULES, f)
        return p

    def cli(self, *args):
        p = subprocess.run([sys.executable, MASKGEN] + [str(a) for a in args], capture_output=True, text=True)
        return p.returncode, p.stdout, p.stderr

    def generate(self, rules=None, tag="a"):
        m, g, pr = [os.path.join(self.t, "%s-%s.json" % (n, tag)) for n in ("m", "g", "p")]
        rc, out, err = self.cli("generate", "--rules", self.rules_file(rules), "--dump", self.t, "--out-masks", m, "--out-regions", g,
                                "--out-provenance", pr)
        return rc, out, err, m, g, pr

    def test_node_rule_grows_each_title_and_the_center_rule_keeps_the_button(self):
        rc, out, err, m, g, _ = self.generate()
        self.assertEqual(rc, 0, err)
        masks = jr(m)
        # the title node [231,646][996,697] grown by 40 sideways and 6 up and down; the 640 px button's 300 x 72 centre
        self.assertIn([191, 640, 845, 63], masks["quick.png"])
        self.assertIn([191, 1028, 607, 63], masks["quick.png"])
        self.assertIn([390, 1440, 300, 72], masks["quick.png"])
        self.assertEqual(len(masks["quick.png"]), 3)

    def test_a_limit_stops_the_growth_at_the_time_label_and_the_body_text(self):
        rules = json.loads(json.dumps(RULES))
        rules["rules"] = [{"id": "title", "kind": "node", "match": {"resource_id": "^title$"}, "pad": [40, 6], "text_node": True,
                           "required": True, "states": ["quick"], "why": "w", "evidence": "e",
                           "limit": [{"match": {"resource_id": "^(time_divider|time)$"}, "scope": "^expandableNotificationRow$", "from": "left"},
                                     {"match": {"resource_id": "^text$"}, "scope": "^expandableNotificationRow$", "from": "top"}]}]
        for theme in ("light", "dark"):
            with open(os.path.join(self.t, "stock", theme, "quick.xml"), "w", encoding="utf-8") as f:
                f.write(ROW)
        rc, out, err, m, g, _ = self.generate(rules)
        self.assertEqual(rc, 0, err)
        masks = jr(m)["quick.png"]
        # no limit would give [191,1028,607,63] (x 191..798, y 1028..1091); it stops at the divider (x 768) and at the body (y 1085)
        self.assertEqual(masks, [[191, 1028, 577, 57]])
        x, y, w, h = masks[0]
        for other in ((768, 1040, 779, 1083), (789, 1040, 849, 1083), (231, 1085, 996, 1138)):
            self.assertFalse(maskgen.intersects((x, y, x + w, y + h), other), other)
        self.assertTrue(x <= 231 and x + w >= 758 and y <= 1034 and y + h >= 1085)   # the title's own bounds stay whole
        # without the limit the mask does cover them (what the review found)
        del rules["rules"][0]["limit"]
        rc, out, err, m, g, _ = self.generate(rules, tag="b")
        self.assertEqual(jr(m)["quick.png"], [[191, 1028, 607, 63]])
        # a limit whose scope is no ancestor fails loudly
        rules["rules"][0]["limit"] = [{"match": {"resource_id": "^text$"}, "scope": "^no_such_scope$", "from": "top"}]
        rc, out, err, m, g, _ = self.generate(rules, tag="c")
        self.assertEqual(rc, 1)
        self.assertIn("scope", err)

    def test_a_limit_does_not_cut_a_neighbour_that_is_not_beyond_the_node(self):
        rules = json.loads(json.dumps(RULES))
        rules["rules"] = [{"id": "title", "kind": "node", "match": {"resource_id": "^title$"}, "pad": [40, 6], "text_node": True,
                           "states": ["quick"], "why": "w", "evidence": "e",
                           "limit": [{"match": {"resource_id": "^(title|dismiss_text)$"}, "scope": "^notification_stack_scroller$", "from": "top"}]}]
        # in QUICK the other titles and the Clear all button are below card 1's title but their top is far under the grown box
        # (y 703): not within it, so nothing is cut; and card 2's title is at the same height as nothing above it
        rc, out, err, m, g, _ = self.generate(rules)
        self.assertEqual(rc, 0, err)
        self.assertIn([191, 640, 845, 63], jr(m)["quick.png"])   # a node beyond the grown box cuts nothing

    def test_before_rule_shrunk_by_its_negative_pad_and_state_limits(self):
        rc, out, err, m, g, _ = self.generate()
        masks = jr(m)
        self.assertEqual(masks["expanded.png"], [[0, 2200, 672, 116]])   # the footer row [0,2179][1080,2337] up to the user button at x 672, 21 px in
        self.assertNotIn([0, 2200, 672, 116], masks["quick.png"])        # the rule is for expanded states only

    def test_below_rule_when_enabled_runs_from_the_cards_bottom_to_the_list_bottom(self):
        rules = json.loads(json.dumps(RULES))
        for r in rules["rules"]:
            if r["id"] == "tail":
                r["enabled"] = True
        rules["max_share"] = 1.0   # (the whole list below a card is most of the picture: this checks the box, not the limit)
        rc, out, err, m, g, _ = self.generate(rules)
        self.assertEqual(rc, 0, err)
        masks = jr(m)
        # from the bottom of the card that holds the fixture's title (813) to the list's bottom (2316), across the list (0..1080)
        self.assertIn([0, 813, 1080, 1503], masks["quick.png"])

    def test_disabled_rule_makes_nothing(self):
        rc, out, err, m, g, pr = self.generate()
        self.assertNotIn("tail", jr(pr)["states"]["quick.png"]["rules"])
        self.assertFalse(jr(pr)["rules"]["tail"]["enabled"])

    def test_regions_one_per_tile_the_header_the_first_card_and_the_footer(self):
        rc, out, err, m, g, _ = self.generate()
        reg = jr(g)
        self.assertEqual(reg["quick.png"]["tile-1-wi-fi"], [42, 63, 488, 189])
        self.assertEqual(reg["quick.png"]["tile-2-bluetooth"], [551, 63, 487, 189])   # named from the content description when no text
        self.assertEqual(reg["quick.png"]["header"], [0, 0, 1080, 63])
        self.assertEqual(reg["quick.png"]["first-card"], [42, 546, 996, 267])
        self.assertEqual(reg["quick.png"]["footer"], [42, 1413, 818, 126])             # the union of the two buttons (42..860)
        self.assertEqual(reg["expanded.png"]["footer"], [0, 2179, 1080, 158])
        self.assertNotIn("first-card", reg["expanded.png"])

    def test_share_is_the_union_area_over_the_picture(self):
        rc, out, err, m, g, pr = self.generate()
        s = jr(pr)["states"]["quick.png"]["mask_share"]
        want = (845 * 63 + 607 * 63 + 300 * 72) / float(1080 * 2400)
        self.assertAlmostEqual(s, want, places=4)
        self.assertEqual(maskgen.union_area([(0, 0, 10, 10), (5, 5, 15, 15)]), 175)   # overlaps counted once
        self.assertEqual(maskgen.union_area([]), 0)

    def test_over_the_limit_fails_and_names_the_largest_rule(self):
        rules = json.loads(json.dumps(RULES))
        rules["max_share"] = 0.04
        rc, out, err, m, g, _ = self.generate(rules)
        self.assertEqual(rc, 1)
        self.assertIn("quick: the masks cover 4.", err)
        self.assertIn("over the 4 % limit", err)
        self.assertIn("rule title", err)
        self.assertFalse(os.path.exists(m))   # nothing is written when a limit is broken

    def test_a_rule_that_covers_a_tile_fails_unless_it_is_a_family_named_text_node(self):
        rules = json.loads(json.dumps(RULES))
        rules["rules"].append({"id": "bad", "kind": "node", "match": {"text": "^Wi-Fi$"}, "states": ["quick"],
                               "why": "w", "evidence": "e"})
        rc, out, err, m, g, _ = self.generate(rules)
        self.assertEqual(rc, 1)
        self.assertIn("rule bad", err)
        self.assertIn("protected region", err)
        # the same box on a text node is allowed
        for r in rules["rules"]:
            if r["id"] == "bad":
                r["text_node"] = True
        rc, out, err, m, g, _ = self.generate(rules, tag="b")
        self.assertEqual(rc, 0, err)

    def test_a_required_rule_that_matches_nothing_fails(self):
        rules = json.loads(json.dumps(RULES))
        rules["rules"][0]["match"] = {"resource_id": "^no_such_id$"}
        rc, out, err, m, g, _ = self.generate(rules)
        self.assertEqual(rc, 1)
        self.assertIn("rule title matched nothing", err)

    def test_every_rule_needs_a_why_and_an_evidence(self):
        rules = json.loads(json.dumps(RULES))
        del rules["rules"][1]["why"]
        rc, out, err, m, g, _ = self.generate(rules)
        self.assertEqual(rc, 1)
        self.assertIn("no 'why'", err)

    def test_deterministic_bytes_and_the_same_in_both_themes(self):
        rc1, _, _, m1, g1, p1 = self.generate(tag="a")
        rc2, _, _, m2, g2, p2 = self.generate(tag="b")
        self.assertEqual((rc1, rc2), (0, 0))
        for a, b in ((m1, m2), (g1, g2), (p1, p2)):
            with open(a, "rb") as fa, open(b, "rb") as fb:
                self.assertEqual(fa.read(), fb.read())
        # a theme whose tree puts a node elsewhere grows the mask to cover both
        with open(os.path.join(self.t, "stock", "dark", "quick.xml"), "w", encoding="utf-8") as f:
            f.write(QUICK.replace("[231,646][996,697]", "[231,656][996,707]"))
        rc3, _, err, m3, _, _ = self.generate(tag="c")
        self.assertEqual(rc3, 0, err)
        self.assertEqual(len(jr(m3)["quick.png"]), 4)   # both title boxes are kept

    def test_pick_gives_a_state_s_masks_with_the_star_entry_or_its_regions(self):
        rc, out, err, m, g, _ = self.generate()
        mf = os.path.join(self.t, "star.json")
        with open(mf, "w", encoding="utf-8") as f:
            json.dump({"*": [[1, 2, 3, 4]], "quick.png": [[5, 6, 7, 8]]}, f)
        rc, out, err = self.cli("pick", mf, "quick.png")
        self.assertEqual((rc, json.loads(out)), (0, [[1, 2, 3, 4], [5, 6, 7, 8]]))
        rc, out, err = self.cli("pick", g, "expanded.png")
        self.assertEqual(rc, 0)
        self.assertIn("tile-1-wi-fi", json.loads(out))
        self.assertEqual(self.cli("pick", mf, "nope.png")[0], 0)   # the "*" entry alone is a list too
        self.assertEqual(self.cli("pick", g, "nope.png")[0], 2)

    def test_the_mask_file_is_what_diff_py_reads(self):
        from PIL import Image
        rc, out, err, m, g, _ = self.generate()
        a, b = os.path.join(self.t, "a"), os.path.join(self.t, "b")
        os.makedirs(a); os.makedirs(b)
        Image.new("RGB", (1080, 2400), (10, 10, 10)).save(os.path.join(a, "quick.png"))
        img = Image.new("RGB", (1080, 2400), (10, 10, 10))
        for x in range(200, 400):
            for y in range(650, 690):
                img.putpixel((x, y), (250, 250, 250))   # a difference inside the first title's mask only
        img.save(os.path.join(b, "quick.png"))
        diff = os.path.join(HERE, "diff.py")
        p = subprocess.run([sys.executable, diff, a, b], capture_output=True, text=True)
        self.assertEqual(p.returncode, 1)
        p = subprocess.run([sys.executable, diff, a, b, "--mask-file", m], capture_output=True, text=True)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)

    def test_usage_and_unreadable_input_exit_2(self):
        self.assertEqual(self.cli()[0], 2)
        rc, out, err = self.cli("generate", "--rules", os.path.join(self.t, "none.json"), "--dump", self.t, "--out-masks", "x", "--out-regions", "y")
        self.assertEqual(rc, 2)
        os.makedirs(os.path.join(self.t, "empty"))
        rc, out, err = self.cli("generate", "--rules", self.rules_file(), "--dump", os.path.join(self.t, "empty"), "--out-masks", "x", "--out-regions", "y")
        self.assertEqual(rc, 1)   # no dumps: a failed run, not a usage error


if __name__ == "__main__":
    unittest.main()
