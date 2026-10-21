#!/usr/bin/env python3
"""Real ffmpeg regressions for complete voice clips and failed package rebuilds."""
import io
import json
import math
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import wave

import package


def tone():
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(22050)
        audio.writeframes(b"".join(struct.pack("<h", int(10000 * math.sin(2 * math.pi * 440 * i / 22050)))
                                   for i in range(11025)))
    return buffer.getvalue()


RACE = {"name": "VoiceTest", "models": {},
        "voices": {"vocal": [{"kits": [{"id": 123, "files": [456]}]}], "emotes": []}}
VOICE = Path("Sound/Character/VoiceTest/VoiceTest_123_1.mp3")


class VoicePackaging(unittest.TestCase):
    def assert_audible(self, path):
        decoded = subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-xerror", "-i", str(path),
                                  "-map", "0:a:0", "-ac", "1", "-ar", "22050", "-f", "s16le", "-"],
                                 capture_output=True, check=True).stdout
        samples = struct.unpack("<%dh" % (len(decoded) // 2), decoded)
        self.assertGreater(len(samples), 10000)
        self.assertGreater(max(abs(sample) for sample in samples), 5000)

    def test_real_voice_conversion_keeps_audible_audio(self):
        with tempfile.TemporaryDirectory() as folder:
            written = package.voice_files(RACE, lambda fid: tone(), folder, lambda _: None)
            self.assertEqual(written, [str(VOICE).replace("/", "\\")])
            self.assert_audible(Path(folder) / VOICE)

    def test_zero_and_truncated_cached_mp3s_are_rebuilt_from_source(self):
        with tempfile.TemporaryDirectory() as folder:
            package.voice_files(RACE, lambda fid: tone(), folder, lambda _: None)
            path = Path(folder) / VOICE
            complete = path.read_bytes()
            for damaged in (b"", complete[:len(complete) // 2]):
                path.write_bytes(damaged)
                package.voice_files(RACE, lambda fid: tone(), folder, lambda _: None)
                self.assertEqual(path.read_bytes(), complete)
                self.assert_audible(path)

    def test_invalid_source_fails_without_partial_voice_files(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(RuntimeError, r"voice 456 \(kit 123\)"):
                package.voice_files(RACE, lambda fid: b"not an audio file", folder, lambda _: None)
            self.assertFalse(any(path.is_file() for path in Path(folder).rglob("*")))

    def test_truncated_source_audio_fails_conversion(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(RuntimeError):
                package.voice_files(RACE, lambda fid: tone()[:-101], folder, lambda _: None)
            self.assertFalse((Path(folder) / VOICE).exists())

    def test_failed_voice_rebuild_cannot_leave_a_complete_manifest(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            races = root / "races.json"
            races.write_text(json.dumps({"voice_test": RACE}))
            output = root / "package"
            output.mkdir()
            manifest = output / package.MANIFEST
            manifest.write_text("previous-complete-package\n")
            with patch.object(package, "RACES", str(races)):
                with self.assertRaises(RuntimeError):
                    package.build(lambda fid: b"not an audio file", str(output), lambda _: None)
            self.assertFalse(manifest.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
