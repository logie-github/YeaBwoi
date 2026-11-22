#!/usr/bin/env python3
"""tools/item_reports.py on hand-made issues: item reports and build reports (schema 2) merged into the three shipped files.

Run: python3 -I tools/test_item_reports.py (standard library only; writes only into a temporary folder).
"""

import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import item_reports as ir  # noqa: E402


def issue(number, body_json, label, title="x"):
    return {"number": number, "title": title, "labels": [{"name": label}],
            "body": "summary\n\n```json\n%s\n```\n" % json.dumps(body_json, indent=1)}


def build_body(dx=0.1, marker_x=1.0, extra_items=True):
    return {
        "schema": 2, "kind": "build-report", "place": {"id": "goldshire_inn", "name": "Inn"},
        "markers": [{"id": "m1", "name": "Sit 1", "type": "sit", "x": marker_x, "y": 2.0, "z": 0.45, "yawDeg": 90, "item": None}],
        "items": [{"key": "goldshire_inn/doodad:12/12345", "name": "Hay bale", "model": 12345, "class": "other",
                   "supports": ["lying", "sitting"], "surface": 0.5, "position": [3, 4, 0], "yawDeg": 30, "scale": 1,
                   "box": {"x": 1.2, "y": 0.8, "z": 0.5}, "anchor": {"x": 0.1, "y": 0, "z": 0.5, "yaw": 90}, "slot": "BED"}] if extra_items else [],
        "pairs": [],
        "raceOffsets": [{"animationId": "Hug", "animationName": "Hug", "role": "A", "race": 7, "sex": "female", "otherRace": -1,
                         "furnitureClass": "floor", "item": None, "scope": "role", "sizeClass": "gnome",
                         "dx": dx, "dy": 0, "dz": 0.05, "dyawDeg": 5}],
    }


class ItemReportsTest(unittest.TestCase):
    def test_build_reports_merge_with_medians_and_outliers(self):
        issues = [issue(n, build_body(dx, mx), ir.BUILD_LABEL, "Build report: Inn")
                  for n, dx, mx in ((1, 0.10, 1.0), (2, 0.12, 1.02), (3, 0.14, 1.04), (4, 1.5, 3.0))]
        issues.append(issue(5, {"schema": 2, "place": {"id": "p"}}, ir.BUILD_LABEL))   # nothing in it
        issues.append(issue(6, {"schema": 1, "place": {"id": "p"}, "item": {"key": "p/CHAIR/1", "object": "CHAIR"}, "class": "seat",
                                "offset": {"x": 0, "y": 0, "z": 0.45, "yaw": 0}, "pose": {"id": "sit"}}, ir.LABEL))
        by_key, problems = ir.read_reports(issues)
        self.assertEqual(["p/CHAIR/1"], list(by_key))
        built, markers, races, bproblems = ir.read_build(issues)
        self.assertEqual([5], [n for n, _ in bproblems])
        self.assertEqual(4, len(built["goldshire_inn/doodad:12/12345"]))
        for k, v in built.items():
            by_key.setdefault(k, []).extend(v)
        items = ir.build(by_key)["items"]
        bale = items["goldshire_inn/doodad:12/12345"]
        self.assertEqual("other", bale["class"])
        self.assertEqual(["lying", "sitting"], bale["supports"])
        self.assertEqual("lie", bale["spots"]["other"]["pose"])
        self.assertEqual(0.5, bale["surface"])
        self.assertEqual("seat", items["p/CHAIR/1"]["class"])
        m = ir.merge_markers(markers)["places"]["goldshire_inn"]
        self.assertEqual(1, len(m))
        self.assertAlmostEqual(1.02, m[0]["x"], places=3)
        self.assertEqual([4], m[0]["outliers"])
        r = ir.merge_races(races)["offsets"]
        self.assertEqual(1, len(r))
        self.assertAlmostEqual(0.12, r[0]["dx"], places=3)
        self.assertEqual([4], r[0]["outliers"])
        self.assertEqual(3, r[0]["reports"])

    def test_bad_build_entries_are_named(self):
        body = build_body()
        body["raceOffsets"][0]["dx"] = 9
        body["markers"].append({"type": "dance", "x": 0, "y": 0, "z": 0})
        _, markers, races, problems = ir.read_build([issue(9, body, ir.BUILD_LABEL)])
        self.assertEqual([], races)
        self.assertEqual(1, len(markers["goldshire_inn"]))
        self.assertEqual(9, problems[0][0])
        self.assertTrue(any("race offset" in p for p in problems[0][1]))

    def test_the_tool_writes_the_three_files(self):
        with tempfile.TemporaryDirectory() as d:
            src = os.path.join(d, "issues.json")
            with open(src, "w") as f:
                json.dump([issue(1, build_body(), ir.BUILD_LABEL)], f)
            outs = [os.path.join(d, n) for n in ("items.json", "markers.json", "race.json")]
            ir.main(["--from-json", src, "--out", outs[0], "--markers-out", outs[1], "--race-out", outs[2],
                     "--spots-out", os.path.join(d, "spots.json")])
            data = []
            for p in outs:
                with open(p) as f:
                    data.append(json.load(f))
            self.assertIn("goldshire_inn/doodad:12/12345", data[0]["items"])
            self.assertEqual("Sit 1", data[1]["places"]["goldshire_inn"][0]["name"])
            self.assertEqual("Hug", data[2]["offsets"][0]["animationId"])

    def test_new_tab_spots_are_read_apart_from_items_and_merged_by_place(self):
        def spot(x, y, label="", kind="chair", cls="seat"):
            return {"user": True, "key": "spot:a", "kind": kind, "label": label, "class": cls, "position": [x, y, 0.0], "yawDeg": 10,
                    "box": {"x": 0.6, "y": 0.6, "z": 0.5}, "surface": 0.5, "sparkle": True}
        bodies = []
        for n, (x, y) in enumerate([(1.0, 1.0), (1.1, 1.0), (1.2, 1.1), (9.0, 9.0)]):
            b = build_body()
            b["items"] = list(b.get("items") or []) + [spot(x, y)]
            bodies.append(issue(20 + n, b, ir.BUILD_LABEL))
        spots = {}
        built, _, _, problems = ir.read_build(bodies, spots)
        self.assertFalse(any("spot" in p for _, ps in problems for p in ps))
        self.assertNotIn("spot:a", built, "a user spot is not a game item")
        merged = ir.merge_spots(spots)["places"]["goldshire_inn"]
        self.assertEqual(2, len(merged), "three near each other are one, the far one another")
        near = [m for m in merged if m["reports"] == 3][0]
        self.assertEqual(1.1, near["position"][0]); self.assertTrue(near["user"]); self.assertEqual("chair", near["kind"])
        bad = build_body()
        bad["items"] = [dict(spot(0, 0), kind="sofa")]
        sp = {}
        _, _, _, problems = ir.read_build([issue(30, bad, ir.BUILD_LABEL)], sp)
        self.assertEqual({}, sp)
        self.assertTrue(any("spot" in p for _, ps in problems for p in ps))


if __name__ == "__main__":
    unittest.main()
