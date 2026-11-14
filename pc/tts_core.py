"""Inti Teks ke Suara (PC): rapikan teks, pecah per baris, sintesis lewat edge-tts, gabung jadi satu MP3.

Suara memakai layanan Microsoft Edge (neural) lewat library edge-tts: tanpa API key, butuh internet.
"""

from __future__ import annotations

import asyncio
import re
from dataclasses import dataclass
from typing import Callable, Optional

VOICES = {
    "gadis": "id-ID-GadisNeural",  # wanita
    "ardi": "id-ID-ArdiNeural",    # pria
}
DEFAULT_VOICE = "gadis"

MAX_CHUNK = 600
LINE_PAUSE_MS = 450
PARAGRAPH_PAUSE_MS = 900
MIN_PIECE = 150
MAX_PARALLEL = 3
RETRIES = 3


# ---------------------------------------------------------------- Normalisasi teks

_ABBREVIATIONS = {
    "yg": "yang", "dgn": "dengan", "dng": "dengan", "utk": "untuk", "untk": "untuk",
    "tdk": "tidak", "krn": "karena", "krna": "karena", "sdh": "sudah", "udh": "sudah",
    "blm": "belum", "jgn": "jangan", "dlm": "dalam", "spt": "seperti", "sbg": "sebagai",
    "tsb": "tersebut", "dsb": "dan sebagainya", "dst": "dan seterusnya",
    "dll": "dan lain-lain", "dkk": "dan kawan-kawan", "thn": "tahun", "bln": "bulan",
    "hrs": "harus", "bgt": "banget", "gmn": "bagaimana", "bgmn": "bagaimana",
    "emg": "memang", "klo": "kalau", "kalo": "kalau",
}
_ABBR_RE = re.compile(r"\b(" + "|".join(_ABBREVIATIONS) + r")\b", re.IGNORECASE)
_BULLET_RE = re.compile(r"^\s*[-•·▪●○]+\s+")
_MARKDOWN_RE = re.compile(r"[*#_~`|^<>\[\]{}]")
_THOUSANDS_RE = re.compile(r"(?<![\d.,])[1-9]\d{0,2}(?:\.\d{3})+(?!\d)")
_RUPIAH_RE = re.compile(r"\bRp\.?\s*(\d+(?:,\d+)?)(\s*(?:ribu|juta|miliar|milyar|triliun))?")
_DASH_AFTER_NUMBER_RE = re.compile(r"(\d),-")
_PERCENT_RE = re.compile(r"(\d)\s*%")
_AMPERSAND_RE = re.compile(r"\s*&\s*")
_NOMOR_RE = re.compile(r"\bNo\.\s*(?=\d)", re.IGNORECASE)
_JALAN_RE = re.compile(r"\bJl\.\s*")
_SPACES_RE = re.compile(r"[ \t]+")
_END_PUNCT_RE = re.compile(r"[.!?…:;,)\"'”’]$")


def _is_emoji_like(cp: int) -> bool:
    return (
        0x1F000 <= cp <= 0x1FAFF
        or 0x2600 <= cp <= 0x27BF
        or 0x2B00 <= cp <= 0x2BFF
        or cp in (0xFE0F, 0x200D)
    )


def normalize(text: str) -> str:
    """Rapikan satu baris agar enak dibaca: singkatan, Rp, persen, emoji, simbol markdown."""
    t = "".join(ch for ch in text if not _is_emoji_like(ord(ch)))
    t = _BULLET_RE.sub("", t)
    t = _MARKDOWN_RE.sub(" ", t)
    t = _THOUSANDS_RE.sub(lambda m: m.group(0).replace(".", ""), t)  # 10.000 -> 10000
    t = _RUPIAH_RE.sub(lambda m: f"{m.group(1)}{m.group(2) or ''} rupiah", t)
    t = _DASH_AFTER_NUMBER_RE.sub(r"\1", t)
    t = _PERCENT_RE.sub(r"\1 persen", t)
    t = _AMPERSAND_RE.sub(" dan ", t)
    t = _NOMOR_RE.sub("nomor ", t)
    t = _JALAN_RE.sub("Jalan ", t)
    t = _ABBR_RE.sub(lambda m: _ABBREVIATIONS[m.group(0).lower()], t)
    t = _SPACES_RE.sub(" ", t).strip()
    if not any(ch.isalnum() for ch in t):
        return ""
    if not _END_PUNCT_RE.search(t):
        t += "."
    return t


# ---------------------------------------------------------------- Pemecahan teks

@dataclass
class Chunk:
    text: str
    pause_after_ms: int


def _find_cut(s: str, start: int, end: int) -> int:
    floor = start + MIN_PIECE
    for marks in (".!?…", ",;:"):
        i = end - 1
        while i > floor:
            if s[i] in marks and s[i + 1].isspace():
                return i + 1
            i -= 1
    i = end - 1
    while i > floor:
        if s[i].isspace():
            return i
        i -= 1
    return end


def split_text(source: str) -> list[Chunk]:
    """Satu baris = satu bagian (baris panjang dipecah di akhir kalimat). Baris kosong = jeda paragraf."""
    chunks: list[Chunk] = []
    for raw in source.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        line = raw.strip()
        if not line:
            if chunks and chunks[-1].pause_after_ms < PARAGRAPH_PAUSE_MS:
                chunks[-1].pause_after_ms = PARAGRAPH_PAUSE_MS
            continue
        pos = 0
        while pos < len(line):
            cut = len(line) if len(line) - pos <= MAX_CHUNK else _find_cut(line, pos, pos + MAX_CHUNK)
            piece = line[pos:cut].strip()
            if piece:
                chunks.append(Chunk(piece, 0))
            pos = cut
        if chunks:
            chunks[-1].pause_after_ms = max(chunks[-1].pause_after_ms, LINE_PAUSE_MS)
    return chunks


# ---------------------------------------------------------------- Jeda (frame MP3 senyap)
# Keluaran edge-tts selalu MPEG-2 Layer III, 24 kHz, mono, 48 kbps CBR. Satu frame = 144 byte = 24 ms.
# Frame berisi nol dibaca semua pemutar sebagai senyap, jadi jeda bisa disisipkan tanpa ffmpeg.

_SILENT_FRAME = bytes([0xFF, 0xF3, 0x64, 0xC4]) + bytes(140)
_FRAME_MS = 24


def _skip_id3(data: bytes) -> int:
    if len(data) >= 10 and data[:3] == b"ID3":
        size = (data[6] << 21) | (data[7] << 14) | (data[8] << 7) | data[9]
        return 10 + size
    return 0


def is_expected_format(mp3: bytes) -> bool:
    """True jika frame pertama MPEG-2 Layer III, 24 kHz, 48 kbps (format keluaran edge-tts)."""
    i = _skip_id3(mp3)
    h = mp3[i:i + 4]
    if len(h) < 4 or h[0] != 0xFF or (h[1] & 0xFE) != 0xF2:
        return False
    bitrate_index = h[2] >> 4
    sample_rate_index = (h[2] >> 2) & 0x3
    return bitrate_index == 6 and sample_rate_index == 1


def silence(ms: int) -> bytes:
    frames = max(1, round(ms / _FRAME_MS))
    # Pad byte pertama bit-reservoir tidak dipakai (CBR tanpa padding), jadi frame bisa diulang langsung.
    return _SILENT_FRAME * frames


# ---------------------------------------------------------------- Sintesis

class SynthesisError(Exception):
    pass


async def _synth_one(text: str, voice: str, rate: str, volume: str, pitch: str) -> bytes:
    import edge_tts  # impor di sini supaya tes logika tidak butuh paket

    last: Optional[Exception] = None
    for attempt in range(RETRIES):
        try:
            data = bytearray()
            communicate = edge_tts.Communicate(text, voice, rate=rate, volume=volume, pitch=pitch)
            async for message in communicate.stream():
                if message["type"] == "audio":
                    data.extend(message["data"])
            if not data:
                raise SynthesisError("Server tidak mengirim audio")
            return bytes(data)
        except asyncio.CancelledError:
            raise
        except Exception as e:  # jaringan putus, timeout, dll
            last = e
            await asyncio.sleep(1.5 * (attempt + 1))
    raise SynthesisError(
        "Gagal membuat suara. Periksa koneksi internet (layanan suara butuh internet). "
        f"Detail: {last}"
    )


def _fmt(value: int, unit: str) -> str:
    return f"{value:+d}{unit}"


async def synthesize(
    source: str,
    voice: str = DEFAULT_VOICE,
    rate: int = 0,
    pitch: int = 0,
    volume: int = 0,
    use_normalize: bool = True,
    on_progress: Optional[Callable[[int, int], None]] = None,
    synth_fn=None,
) -> bytes:
    """Ubah teks multiline menjadi satu file MP3 (bytes).

    rate: persen (-50..100), pitch: Hz (-50..50), volume: persen (-50..50).
    """
    voice_name = VOICES.get(voice.lower(), voice)
    items: list[tuple[str, int]] = []
    for chunk in split_text(source):
        text = normalize(chunk.text) if use_normalize else chunk.text
        if text.strip():
            items.append((text, chunk.pause_after_ms))
    if not items:
        raise SynthesisError("Tidak ada teks yang bisa dibaca")

    fn = synth_fn or _synth_one
    rate_s, pitch_s, volume_s = _fmt(rate, "%"), _fmt(pitch, "Hz"), _fmt(volume, "%")
    semaphore = asyncio.Semaphore(MAX_PARALLEL)
    done = 0

    async def run(text: str) -> bytes:
        nonlocal done
        async with semaphore:
            audio = await fn(text, voice_name, rate_s, volume_s, pitch_s)
        done += 1
        if on_progress:
            on_progress(done, len(items))
        return audio

    results = await asyncio.gather(*(run(text) for text, _ in items))

    out = bytearray()
    for index, ((_, pause), audio) in enumerate(zip(items, results)):
        out.extend(audio)
        if pause > 0 and index < len(items) - 1 and is_expected_format(audio):
            out.extend(silence(pause))
    return bytes(out)


def synthesize_blocking(*args, **kwargs) -> bytes:
    return asyncio.run(synthesize(*args, **kwargs))
