import asyncio
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

import tts_core as core


class NormalizeTests(unittest.TestCase):
    def test_abbreviations(self):
        self.assertEqual(core.normalize("Ini yg penting"), "Ini yang penting.")

    def test_rupiah(self):
        self.assertEqual(core.normalize("Harganya Rp10.000 saja"), "Harganya 10000 rupiah saja.")
        self.assertEqual(core.normalize("Rp. 5 juta"), "5 juta rupiah.")

    def test_percent_and_ampersand(self):
        self.assertEqual(core.normalize("Diskon 50%"), "Diskon 50 persen.")
        self.assertEqual(core.normalize("A & B"), "A dan B.")

    def test_emoji_and_markdown(self):
        self.assertEqual(core.normalize("Halo 😀 dunia"), "Halo dunia.")
        self.assertEqual(core.normalize("**Penting**"), "Penting.")
        self.assertEqual(core.normalize("- Satu item"), "Satu item.")

    def test_symbols_only_is_empty(self):
        self.assertEqual(core.normalize("***"), "")
        self.assertEqual(core.normalize("😀😀"), "")

    def test_keeps_punctuation(self):
        self.assertEqual(core.normalize("Apa kabar?"), "Apa kabar?")


class SplitTests(unittest.TestCase):
    def test_empty(self):
        self.assertEqual(core.split_text(""), [])
        self.assertEqual(core.split_text("  \n \n"), [])

    def test_lines_and_pauses(self):
        chunks = core.split_text("Halo dunia\nIni baris dua")
        self.assertEqual([c.text for c in chunks], ["Halo dunia", "Ini baris dua"])
        self.assertEqual(chunks[0].pause_after_ms, core.LINE_PAUSE_MS)

    def test_blank_line_is_longer_pause(self):
        chunks = core.split_text("Satu\n\nDua")
        self.assertEqual(chunks[0].pause_after_ms, core.PARAGRAPH_PAUSE_MS)

    def test_windows_line_endings(self):
        self.assertEqual([c.text for c in core.split_text("Satu\r\nDua\r\n")], ["Satu", "Dua"])

    def test_long_line_is_split(self):
        source = ("kata " * 400).strip()
        chunks = core.split_text(source)
        self.assertGreater(len(chunks), 1)
        for c in chunks:
            self.assertLessEqual(len(c.text), core.MAX_CHUNK)
        self.assertEqual(sum(len(c.text.split()) for c in chunks), 400)


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "butuh ffmpeg")
class Mp3Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.sample = Path(cls.tmp.name) / "sample.mp3"
        # Meniru format keluaran edge-tts: MPEG-2 L3, 24 kHz, mono, 48 kbps CBR, tanpa header Xing
        subprocess.run(
            ["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
             "-ar", "24000", "-ac", "1", "-b:a", "48k", "-write_xing", "0", "-id3v2_version", "0",
             "-f", "mp3", str(cls.sample)],
            check=True,
        )
        cls.audio = cls.sample.read_bytes()

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def probe(self, data: bytes) -> float:
        path = Path(self.tmp.name) / "out.mp3"
        path.write_bytes(data)
        # decode penuh: gagal jika ada frame rusak
        result = subprocess.run(
            ["ffmpeg", "-v", "error", "-i", str(path), "-f", "null", "-"], capture_output=True, text=True
        )
        self.assertEqual(result.stderr.strip(), "")
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
            capture_output=True, text=True, check=True,
        )
        return float(out.stdout.strip())

    def test_expected_format_detected(self):
        self.assertTrue(core.is_expected_format(self.audio))
        self.assertFalse(core.is_expected_format(b"not mp3"))

    def test_silent_frame_header_matches_real_frame(self):
        self.assertEqual(core._SILENT_FRAME[:3], self.audio[:3])
        self.assertEqual(len(core._SILENT_FRAME), 144)

    def test_silence_duration(self):
        base = self.probe(self.audio)
        gap = self.probe(self.audio + core.silence(1000) + self.audio) - 2 * base
        self.assertAlmostEqual(gap, 1.0, delta=0.06)

    def test_full_pipeline_with_fake_engine(self):
        calls = []

        async def fake(text, voice, rate, volume, pitch):
            calls.append((text, voice, rate, volume, pitch))
            return self.audio

        progress = []
        result = asyncio.run(
            core.synthesize(
                "Baris satu yg pertama\nBaris dua\n\nParagraf baru",
                voice="ardi", rate=10, pitch=-5, volume=0,
                on_progress=lambda d, t: progress.append((d, t)),
                synth_fn=fake,
            )
        )
        self.assertEqual(len(calls), 3)
        self.assertEqual(calls[0][0], "Baris satu yang pertama.")
        self.assertEqual(calls[0][1:], ("id-ID-ArdiNeural", "+10%", "+0%", "-5Hz"))
        self.assertEqual(progress[-1], (3, 3))
        # 3 x durasi sampel + 2 jeda (450 ms dan 900 ms, dibulatkan ke kelipatan 24 ms)
        base = self.probe(self.audio)
        expected = 3 * base + 0.45 + 0.9
        self.assertAlmostEqual(self.probe(result), expected, delta=0.05)

    def test_empty_text_raises(self):
        with self.assertRaises(core.SynthesisError):
            asyncio.run(core.synthesize("  \n ", synth_fn=None))


if __name__ == "__main__":
    unittest.main()
