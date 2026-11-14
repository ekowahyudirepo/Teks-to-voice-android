"""Teks ke Suara (PC) versi baris perintah.

Contoh:
    python tts.py -i naskah.txt -o hasil.mp3
    python tts.py --text "Halo semua" --voice ardi --rate 10 -o halo.mp3
    type naskah.txt | python tts.py -o hasil.mp3
"""

from __future__ import annotations

import argparse
import sys

from tts_core import DEFAULT_VOICE, VOICES, SynthesisError, synthesize_blocking


def main() -> int:
    p = argparse.ArgumentParser(description="Ubah teks (banyak baris) menjadi suara MP3 Bahasa Indonesia.")
    p.add_argument("-i", "--input", help="file teks (UTF-8). Jika kosong: pakai --text atau stdin")
    p.add_argument("--text", help="teks langsung")
    p.add_argument("-o", "--output", default="hasil.mp3", help="file MP3 keluaran (default: hasil.mp3)")
    p.add_argument("--voice", default=DEFAULT_VOICE,
                   help=f"suara: {', '.join(VOICES)} atau nama lengkap, mis. id-ID-ArdiNeural (default: {DEFAULT_VOICE})")
    p.add_argument("--rate", type=int, default=0, help="kecepatan dalam persen, -50 sampai 100 (default 0)")
    p.add_argument("--pitch", type=int, default=0, help="nada dalam Hz, -50 sampai 50 (default 0)")
    p.add_argument("--volume", type=int, default=0, help="volume dalam persen, -50 sampai 50 (default 0)")
    p.add_argument("--no-normalize", action="store_true", help="matikan perapian teks otomatis")
    args = p.parse_args()

    if args.text is not None:
        source = args.text
    elif args.input:
        with open(args.input, encoding="utf-8-sig") as f:
            source = f.read()
    elif not sys.stdin.isatty():
        source = sys.stdin.buffer.read().decode("utf-8-sig")
    else:
        p.error("beri teks lewat --text, --input, atau stdin")

    def progress(done: int, total: int) -> None:
        print(f"\rMembuat suara… {done}/{total}", end="", file=sys.stderr, flush=True)

    try:
        audio = synthesize_blocking(
            source,
            voice=args.voice,
            rate=args.rate,
            pitch=args.pitch,
            volume=args.volume,
            use_normalize=not args.no_normalize,
            on_progress=progress,
        )
    except SynthesisError as e:
        print(f"\nGagal: {e}", file=sys.stderr)
        return 1

    with open(args.output, "wb") as f:
        f.write(audio)
    print(f"\nTersimpan: {args.output}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
