#!/usr/bin/env python3
"""Regression checks for animation reads and intro sound selection; no game files needed."""
import os
from pathlib import Path
import struct
import tempfile
import sqlite3
import unittest
from types import SimpleNamespace

import build_data
import check_m2
import m2


def chunk(name, data):
    return name.encode() + struct.pack("<I", len(data)) + data


class AnimationSafety(unittest.TestCase):
    def test_resident_skeleton_flags_do_not_read_model_data_from_an_anim_file(self):
        for flags in (0x10, 0x20, 0x100, 0x120):
            sequence = bytearray(64)
            struct.pack_into("<I", sequence, 12, flags)
            sks = struct.pack("<6I", 0, 0, 1, 24, 0, 0) + sequence
            skeleton = m2.Skeleton(chunk("SKS1", sks) + chunk("SKB1", bytes(16)) + chunk("SKA1", bytes(16)))
            self.assertTrue(skeleton.in_file[0], hex(flags))
            data = bytearray(64)
            struct.pack_into("<hhIIII", data, 0, 1, -1, 1, 20, 1, 28)
            struct.pack_into("<II", data, 20, 1, 36)
            struct.pack_into("<II", data, 28, 1, 40)
            struct.pack_into("<I3f", data, 36, 7, 1, 2, 3)
            track = m2.Track.read(data, 0, 12, lambda i: skeleton.in_file[i])
            self.assertEqual(track.timestamps, [struct.pack("<I", 7)])
            self.assertEqual(track.values, [struct.pack("<3f", 1, 2, 3)])

    def test_split_animation_keeps_and_relocates_every_stream(self):
        source = chunk("AFM2", b"event") + chunk("AFSA", b"attachment") + chunk("AFSB", bytes(range(32)))
        animations = m2.AnimationFiles(lambda fid: source)
        data, parts = animations.get(123)
        for stream, size in (("AFM2", 4), ("AFSA", 1), ("AFSB", 12)):
            entry = animations.relocate(123, ("ext", 1, 1), size, stream)
            base, length = parts[stream]
            self.assertEqual(entry, ("ext", 1, base + 1))
            self.assertEqual(data[entry[2]:entry[2] + size],
                             m2.chunks_of(source)[stream][1:1 + size])
        with self.assertRaises(ValueError):
            animations.relocate(123, ("ext", 3, 1), 12, "AFSB")

    def test_merged_bone_track_uses_its_sequences_stream(self):
        skel = SimpleNamespace(seqs=[bytearray(64)], loops=[], in_file=[False],
                               anim_files={(0, 0): 123}, key=lambda i: (0, 0))
        merged = m2.Merged(skel, None)
        merged.animations = m2.AnimationFiles(lambda fid:
            chunk("AFM2", b"event") + chunk("AFSB", struct.pack("<I3f", 12, 1, 2, 3)))
        track = m2.with_value_size(m2.Track(1, -1, [("ext", 1, 0)], [("ext", 1, 4)]), 12)
        result = merged.merge_tracks(track, None, stream="AFSB")
        data = merged.animations.get(123)[0]
        self.assertEqual(struct.unpack_from("<I", data, result.timestamps[0][2]), (12,))
        self.assertEqual(struct.unpack_from("<3f", data, result.values[0][2]), (1, 2, 3))

    def test_unsplit_and_legacy_animation_offsets_stay_relative_to_payload(self):
        for source in (bytes(range(32)), chunk("AFM2", bytes(range(32)))):
            animations = m2.AnimationFiles(lambda fid: source)
            self.assertEqual(animations.get(1)[0], bytes(range(32)))
            self.assertEqual(animations.relocate(1, ("ext", 2, 4), 12, "AFSB"), ("ext", 2, 4))

    def test_missing_split_bone_stream_is_rejected(self):
        animations = m2.AnimationFiles(lambda fid: chunk("AFM2", bytes(64)) + chunk("AFSA", bytes(64)))
        with self.assertRaises(ValueError):
            animations.relocate(1, ("ext", 1, 0), 12, "AFSB")

    def test_inherited_truncated_rotation_channel_uses_rest_transform(self):
        sequence = bytearray(64)
        struct.pack_into("<HH", sequence, 0, 223, 0)
        parent = SimpleNamespace(seqs=[sequence], loops=[], in_file=[False],
                                 anim_files={(223, 0): 974386}, key=lambda i: (223, 0))
        child = SimpleNamespace(seqs=[], loops=[])
        merged = m2.Merged(child, parent)
        # Exact offset/count/payload size caught in the real parent model by CI.
        merged.animations = m2.AnimationFiles(lambda fid: bytes(34384))
        rotation = m2.with_value_size(m2.Track(1, -1,
            [("ext", 41, 0)], [("ext", 41, 34320)]), 8)
        result = merged.merge_tracks(None, rotation, stream="AFSB")
        self.assertEqual(result.timestamps, [b""])
        self.assertEqual(result.values, [b""])
        self.assertEqual(len(merged.repairs), 1)
        self.assertIn("974386", merged.repairs[0])
        self.assertIn("34320", merged.repairs[0])

    def test_global_track_is_local_even_when_first_animation_is_external(self):
        blob = bytearray(64)
        struct.pack_into("<hhIIII", blob, 0, 1, 0, 1, 20, 1, 28)
        struct.pack_into("<II", blob, 20, 1, 36)
        struct.pack_into("<II", blob, 28, 1, 40)
        struct.pack_into("<If", blob, 36, 7, 0.5)
        track = m2.Track.read(blob, 0, 4, lambda i: False)
        self.assertEqual(track.timestamps, [struct.pack("<I", 7)])
        self.assertEqual(track.values, [struct.pack("<f", 0.5)])
        base = m2.Track.read_base(blob, 0, lambda i: False)
        self.assertEqual(base.timestamps, track.timestamps)
        parent = SimpleNamespace(seqs=[bytearray(64)], loops=[], key=lambda i: (0, 0))
        child = SimpleNamespace(seqs=[], loops=[1000])
        merged = m2.Merged(child, parent)
        result = merged.merge_tracks(track, None)
        self.assertEqual(result.timestamps, track.timestamps)
        self.assertEqual(result.values, track.values)

    def test_validator_checks_external_track_bounds_for_both_races_and_sexes(self):
        with tempfile.TemporaryDirectory() as folder:
            for name in ("NightborneMale", "NightborneFemale", "VoidElfMale", "VoidElfFemale"):
                blob = m2.Blob()
                blob.data[:4] = b"MD20"
                struct.pack_into("<I", blob.data, 4, 264)
                blob.array(8, len(name) + 1, name.encode() + b"\0")
                sequence = bytearray(64)
                struct.pack_into("<HH", sequence, 60, 65535, 65535)
                blob.array(28, 1, sequence)
                blob.array(36, 2, struct.pack("<2h", 0, -1))
                track = m2.with_value_size(m2.Track(1, -1, [("ext", 1, 0)], [("ext", 1, 4)]), 12)
                bone = struct.pack("<iIhHI", -1, 0, -1, 0, 0)
                bone += m2.write_track(blob, track)
                empty = m2.with_value_size(m2.Track(0, -1, [], []), 12)
                bone += m2.write_track(blob, empty) * 2 + bytes(12)
                blob.array(44, 1, bone)
                path = Path(folder) / (name + ".m2")
                path.write_bytes(blob.data)
                (Path(folder) / (name + "00.skin")).write_bytes(b"SKIN" + bytes(44))
                anim = Path(folder) / (name + "0000-00.anim")
                anim.write_bytes(struct.pack("<I3f", 7, 1, 2, 3))
                self.assertEqual(check_m2.check(path), [])
                # The old validator accepted this existing but truncated animation.
                anim.write_bytes(b"x")
                self.assertTrue(any("outside the .anim file" in p for p in check_m2.check(path)))


class LanguageSafety(unittest.TestCase):
    def test_starting_languages_survive_an_earlier_race_module_and_reapplication(self):
        sql = (Path(build_data.MODULE) / "data/sql/db-world/allied_races_world.sql").read_text()
        updates = "\n".join(line for line in sql.splitlines()
                            if line.startswith("UPDATE `playercreateinfo_skills`"))
        for earlier_module in (False, True):
            with self.subTest(earlier_module=earlier_module), sqlite3.connect(":memory:") as db:
                db.execute("CREATE TABLE playercreateinfo_skills (raceMask INTEGER, classMask INTEGER, skill INTEGER)")
                db.executemany("INSERT INTO playercreateinfo_skills VALUES (?, 0, ?)",
                    [(1101 | (4096 if earlier_module else 0), 98),
                     (690 | (8192 if earlier_module else 0), 109)])
                for _ in range(2):
                    db.executescript(updates)
                    masks = dict(db.execute("SELECT skill, raceMask FROM playercreateinfo_skills"))
                    self.assertTrue(masks[98] & (1 << 14), "Void Elves must learn Common")
                    self.assertTrue(masks[109] & (1 << 15), "Nightborne must learn Orcish")
                    self.assertFalse(masks[98] & (1 << 15))
                    self.assertFalse(masks[109] & (1 << 14))


class IntroSafety(unittest.TestCase):
    def test_generator_discards_inherited_sound_alternatives(self):
        template = [0] * 30
        template[3:13] = ["other.mp3"] * 10
        template[13:23] = [1] * 10
        for race in (build_data.VOIDELF, build_data.NIGHTBORNE):
            row = build_data.narration_sound(template, race)
            self.assertEqual(row[3:13], [build_data.NARRATION[race]] + [""] * 9)
            self.assertEqual(row[13:23], [1] + [0] * 9)
            self.assertEqual(row[23], "Sound\\CinematicVoices")
        self.assertEqual(template[3], "other.mp3")

    def test_committed_intro_rows_link_to_only_their_narrations(self):
        dbc = Path(build_data.MODULE) / "data/client/dbc"
        def rows(name):
            return {int(v[1]): v[1:] for line in (dbc / (name + ".txt")).read_text().splitlines()
                    if (v := line.split("\t"))[0] == "put"}
        races, sequences, cameras, sounds = [rows(n) for n in
            ("ChrRaces", "CinematicSequences", "CinematicCamera", "SoundEntries")]
        for race in (build_data.VOIDELF, build_data.NIGHTBORNE):
            sequence = sequences[int(races[race][12])]
            camera = cameras[int(sequence[2])]
            sound = sounds[int(camera[2])]
            self.assertEqual(sound[3], build_data.NARRATION[race])
            self.assertEqual(sound[13:23], ["1"] + ["0"] * 9)
            self.assertEqual(sound[4:13], [""] * 9)


if __name__ == "__main__":
    unittest.main(verbosity=2)
