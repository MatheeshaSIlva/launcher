"""Tests the serialized paths as well as the analytic construction; standard library only."""
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

import build_statusbar_set as builder


def cubic_segments(path):
    """Decode serialized M/L/C/Z independently of the generator's circle maths."""
    tokens = re.findall(r"[MLCZ]|-?\d+(?:\.\d+)?", path)
    assert tokens.pop(0) == "M"
    current = (float(tokens.pop(0)), float(tokens.pop(0)))
    segments = []
    while tokens[0] in ("L", "C"):
        command = tokens.pop(0)
        end = (float(tokens.pop(0)), float(tokens.pop(0)))
        if command == "C":
            control2 = (float(tokens.pop(0)), float(tokens.pop(0)))
            last = (float(tokens.pop(0)), float(tokens.pop(0)))
            segments.append((current, end, control2, last))
            end = last
        current = end
    assert tokens == ["Z"]
    return segments


def sample_cubic(segment):
    return [tuple(sum(math.comb(3, j) * (1-t)**(3-j) * t**j * p[axis]
                      for j, p in enumerate(segment)) for axis in (0, 1))
            for t in (i/100 for i in range(101))]


def moon_contour(path):
    return [p for segment in cubic_segments(path) for p in sample_cubic(segment)]


def inside(points, x, y):
    hit = False
    for (ax, ay), (bx, by) in zip(points, points[1:] + points[:1]):
        if (ay > y) != (by > y) and x < (bx-ax)*(y-ay)/(by-ay) + ax:
            hit = not hit
    return hit


class StatusbarSetTest(unittest.TestCase):
    def setUp(self):
        self.data = json.loads(builder.OUTPUT.read_bytes())
        self.icons = self.data["icons"]

    def test_regeneration_is_byte_identical_from_another_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "set.json"
            command = [sys.executable, str(Path(builder.__file__).resolve()), str(output)]
            subprocess.run(command, cwd=directory, check=True)
            first = output.read_bytes()
            subprocess.run(command, cwd=directory, check=True)
            self.assertEqual(first, output.read_bytes())
            self.assertEqual(first, builder.OUTPUT.read_bytes())
            self.assertNotIn(b"\r", first)

    def test_wifi_uses_explicit_fine_cubics(self):
        for layer in self.icons["wifi.3"]["layers"]:
            self.assertNotIn("A", layer["d"])
            self.assertGreaterEqual(layer["d"].count("C"), 6)

    def test_emitted_arcs_stay_on_circles_within_001_point(self):
        cases = []
        for layer, outer, inner in zip(self.icons["wifi.3"]["layers"],
                                       (3.4, 7.4, 11.2), (0, 4.8, 8.6)):
            segments = cubic_segments(layer["d"])
            self.assertEqual(len(segments), 6 if inner == 0 else 12)
            cases.extend((segment, 8.5, 5.6, outer if i < 6 else inner)
                         for i, segment in enumerate(segments))
        for segment in cubic_segments(self.icons["moon"]["layers"][0]["d"]):
            # Identify which reference circle owns this segment from its midpoint.
            mx, my = sample_cubic(segment)[50]
            cx, cy, radius = min(((0, 0, 6.2), (3.41, -2.79, 5.27)),
                                key=lambda c: abs(math.hypot(mx-c[0], my-c[1])-c[2]))
            cases.append((segment, cx, cy, radius))
        for segment, cx, cy, radius in cases:
            start, end = segment[0], segment[-1]
            angle = math.atan2(end[1]-cy, end[0]-cx) - math.atan2(start[1]-cy, start[0]-cx)
            self.assertLessEqual(abs((angle + math.pi) % math.tau - math.pi), math.radians(15) + 1e-6)
            for x, y in sample_cubic(segment):
                self.assertLess(abs(math.hypot(x-cx, y-cy) - radius), .01)

    def test_schema_names_sources_and_boxes(self):
        self.assertEqual(set(self.data), {"name", "listed", "icons"})
        self.assertIs(self.data["listed"], False)
        expected = {f"wifi.{i}" for i in range(4)} | {f"cell.{i}" for i in range(5)} | {"moon", "bolt", "airplane"}
        self.assertEqual(set(self.icons), expected)
        boxes = {"wifi": [0, -5.6, 17, 11.2], "cell": [0, -5.85, 18.6, 11.7],
                 "moon": [-6.2, -6.2, 12.4, 12.4], "bolt": [0, -4.5, 7.6, 9],
                 "airplane": [-8.4, -6.72, 16.8, 13.44]}
        for name, icon in self.icons.items():
            self.assertEqual(set(icon), {"src", "box", "layers"})
            self.assertRegex(icon["src"], r"StatusBarView.kt:\d+-\d+ draw\w+")
            self.assertEqual(icon["box"], boxes[name.split(".")[0]])
            for layer in icon["layers"]:
                self.assertEqual(set(layer), {"d", "a"})
                self.assertTrue(layer["d"].startswith("M") and layer["d"].endswith("Z"))
                self.assertFalse(re.search(r"[^MLACZ0-9 .-]", layer["d"]))
                self.assertIn(layer["a"], (1, 77/255))

    def test_levels_change_only_alpha_and_in_order(self):
        for kind, count in (("wifi", 3), ("cell", 4)):
            base = self.icons[f"{kind}.0"]["layers"]
            for level in range(count + 1):
                layers = self.icons[f"{kind}.{level}"]["layers"]
                self.assertEqual(len(layers), count)
                self.assertEqual([p["d"] for p in layers], [p["d"] for p in base])
                self.assertEqual([p["a"] for p in layers], [1]*level + [77/255]*(count-level))
        self.assertEqual(self.icons["wifi.0"]["layers"][0]["d"].count("C"), 6)  # inner wedge
        self.assertEqual(self.icons["wifi.0"]["layers"][1]["d"].count("C"), 12)

    def test_polygon_coordinates_and_bolt_slot_offset(self):
        bolt = self.icons["bolt"]
        coords = [float(n) for n in re.findall(r"-?\d+(?:\.\d+)?", bolt["layers"][0]["d"])]
        self.assertEqual(coords, [4.68, -4.5, .9, .72, 3.42, .72, 2.52, 4.5, 6.3, -.72, 3.78, -.72])
        self.assertAlmostEqual((min(coords[::2]) + max(coords[::2]))/2 - bolt["box"][2]/2, -.2)
        plane = self.icons["airplane"]["layers"][0]["d"]
        self.assertNotIn("A", plane)
        self.assertEqual(plane.count("L"), 17)

    def test_moon_intersections_lie_on_both_circles(self):
        radius, (cx, cy, cut_radius), start, end = builder.moon_geometry()
        self.assertGreater(math.dist(start, end), 1)
        for x, y in (start, end):
            self.assertAlmostEqual(math.hypot(x, y), radius, places=12)
            self.assertAlmostEqual(math.hypot(x-cx, y-cy), cut_radius, places=12)

    def test_emitted_moon_retains_outer_minus_cut_circle(self):
        contour = moon_contour(self.icons["moon"]["layers"][0]["d"])
        for x, y, retained in ((-5, 0, True), (0, 5, True), (0, 0, False), (3, -3, False), (7, 0, False)):
            self.assertEqual(inside(contour, x, y), retained)
        checked = 0
        for ix in range(-26, 27):
            for iy in range(-26, 27):
                x, y = ix/4, iy/4
                outer, cut = math.hypot(x, y), math.hypot(x-3.41, y+2.79)
                if min(abs(outer-6.2), abs(cut-5.27)) < .01:
                    continue  # avoid numerical boundary ambiguity, not an antialiasing test
                self.assertEqual(inside(contour, x, y), outer < 6.2 and cut > 5.27, (x, y))
                checked += 1
        self.assertGreater(checked, 2700)


if __name__ == "__main__":
    unittest.main()
