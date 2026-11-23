#!/usr/bin/env python3
"""Tests for tools/build_pack.py and pack_inventory.py on synthetic Sims 4 packages (never the user's files):
python3 -I tools/test_build_pack.py"""
import hashlib
import json
import os
import struct
import sys
import tempfile
import unittest
import zipfile
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_pack  # noqa: E402
import pack_inventory as inv  # noqa: E402

T_CLIP, T_TUNING, T_OBJD, T_MODL = 0x6B20C4F3, 0x7DF2169C, 0xC0DB5AE7, 0x01661233


def dbpf(resources):
    body, idx = b"", struct.pack("<I", 0)
    for t, g, inst, data in resources:
        raw = zlib.compress(data)
        idx += struct.pack("<IIIIIIIHH", t, g, inst >> 32, inst & 0xFFFFFFFF, 96 + len(body), len(raw) | 0x80000000, len(data), 0x5A42, 1)
        body += raw
    head = b"DBPF" + struct.pack("<II", 2, 1) + b"\0" * 24 + struct.pack("<I", len(resources)) + struct.pack("<II", 0, len(idx))
    head += b"\0" * (64 - len(head)) + struct.pack("<I", 96 + len(body))
    return head + b"\0" * (96 - len(head)) + body + idx


def tuning(stages):
    x = '<I c="WickedWhimsAnimationPackage" i="snippet" n="t" s="1"><L n="animations_list">'
    for name, cat, clips, guids in stages:
        x += '<U><T n="animation_raw_display_name">%s</T><T n="animation_category">%s</T><L n="animation_actors_list">' % (name, cat)
        for i, c in enumerate(clips):
            x += '<U><T n="actor_id">%d</T><T n="animation_clip_name">%s</T></U>' % (i, c)
        x += "</L>"
        if guids:
            x += '<L n="animation_props_list"><U><T n="prop_id">0</T><T n="prop_type">BASIC</T><T n="prop_guids">%s</T><T n="prop_animation_clip_name">p</T></U></L>' % ", ".join(map(str, guids))
        x += "</U>"
    return (x + "</L></I>").encode()


def rcol(chunk):
    return struct.pack("<5I", 3, 1, 0, 0, 1) + struct.pack("<QII", 5, T_MODL, 0) + struct.pack("<II", 40, len(chunk)) + chunk


def object_pkg(guid, model, stages=()):
    objd = b"hdr\0" + struct.pack("<IIIII", 4, model >> 32, model & 0xFFFFFFFF, T_MODL, 0)
    res = [(T_OBJD, 0x80000000, guid, objd), (T_MODL, 0, model, rcol(b"MODL" + b"\0" * 8))]
    if stages:
        res += [(T_CLIP, 0, 1, b"clip"), (T_TUNING, 0, 9, tuning(stages))]
    return dbpf(res)


class BuildPackTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        big = 17078270281272275333  # above 2**63: guids are unsigned
        self.files = {
            "ab12cd34-Anim.package": dbpf([(T_CLIP, 0, 1, b"c1"), (T_CLIP, 0, 2, b"c2"), (T_TUNING, 0, 3, tuning([("Two", "VAGINAL", ["A:x", "A:y"], [big]), ("Three", "TEASING", ["A:a", "A:b", "A:c"], [])]))]),
            "Toy.package": object_pkg(111, 0x1000, [("Toy | Solo", "VAGINAL", ["T:a"], [111])]),
            "Layer.package": object_pkg(big, 0x2000),
            "Bed.package": object_pkg(333, 0x3000),
            "Menu.package": dbpf([(0x220557DA, 0, 1, b"strings")]),
        }
        for n, b in self.files.items():
            open(os.path.join(self.d, n), "wb").write(b)
        zf = zipfile.ZipFile(os.path.join(self.d, "Mod.ts4script"), "w")
        zf.writestr("scripts/__init__.pyc", b"\x00\x01inject_phone_thing")
        zf.close()

    def build(self, name="pack.zip"):
        out = os.path.join(self.d, name)
        m = build_pack.build([self.d], out, "T")
        return out, m

    def test_kinds_are_told_by_content_not_by_name(self):
        _out, m = self.build()
        kinds = {os.path.basename(e["path"]): e["type"] for e in m["entries"]}
        self.assertEqual(kinds["Anim.package"], "animations")       # the upload's 8-hex prefix is dropped from the name
        self.assertEqual(kinds["Toy.package"], "props")
        self.assertEqual(kinds["Layer.package"], "body-layer")      # named only by another package's stage (an id above 2**63)
        self.assertEqual(kinds["Bed.package"], "furniture")
        self.assertEqual(kinds["Menu.package"], "unused")
        self.assertEqual(kinds["Mod.txt"], "script-info")

    def test_manifest_matches_the_files_and_scripts_are_never_included(self):
        out, m = self.build()
        z = zipfile.ZipFile(out)
        names = z.namelist()
        self.assertFalse([n for n in names if n.lower().endswith((".ts4script", ".py", ".pyc"))])
        man = json.loads(z.read("pack/manifest.json"))
        self.assertEqual((man["format"], man["schema"]), ("wifecraft-pack", 1))
        for e in man["entries"]:
            data = z.read(e["path"])
            self.assertEqual(hashlib.sha256(data).hexdigest(), e["sha256"])
            self.assertEqual(len(data), e["size"])
            parts = e["path"].split("/")
            self.assertEqual((len(parts), parts[0]), (3, "pack"))
            self.assertNotIn("..", parts)
        note = next(e for e in man["entries"] if e["type"] == "script-info")
        self.assertFalse(note["summary"]["included"])
        self.assertIn("never run", z.read(note["path"]).decode())

    def test_stage_summary_counts_people(self):
        _out, m = self.build()
        s = next(e for e in m["entries"] if e["type"] == "animations")["summary"]
        self.assertEqual((s["stages"], s["stages_two_people"], s["stages_three_or_more"], s["stages_with_props"]), (2, 1, 1, 1))
        self.assertEqual(s["category"], "A")  # no author in the tuning: the clips' `A:` prefix

    def test_the_zip_is_deterministic(self):
        a, _ = self.build("a.zip")
        b, _ = self.build("b.zip")
        self.assertEqual(open(a, "rb").read(), open(b, "rb").read())

    def test_duplicates_are_dropped_and_names_made_plain(self):
        open(os.path.join(self.d, "Copy of Toy [1].package"), "wb").write(self.files["Toy.package"])
        _out, m = self.build()
        self.assertEqual(sum(1 for e in m["entries"] if e["type"] == "props"), 1)
        for e in m["entries"]:
            self.assertRegex(e["path"].split("/")[-1], r"^[A-Za-z0-9._()+-]+$")

    def test_a_non_package_is_not_taken_as_one(self):
        with self.assertRaises(Exception):
            inv.read_package(os.path.join(self.d, "Mod.ts4script"))


if __name__ == "__main__":
    unittest.main()
