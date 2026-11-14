# Teks ke Suara (PC)

Ubah teks banyak baris menjadi suara Bahasa Indonesia dalam file **MP3**. Ada versi jendela (`app.py`) dan versi baris perintah (`tts.py`).

## Suara yang dipakai

Mesin: **edge-tts** dengan suara neural Microsoft `id-ID-GadisNeural` (wanita) dan `id-ID-ArdiNeural` (pria). Ini suara Indonesia gratis yang paling natural untuk dipakai dari Python, tanpa API key dan tanpa akun.

Catatan penting:
- Butuh **koneksi internet**. Suaranya dibuat di server Microsoft, bukan di PC.
- Itu model suara neural (bukan chatbot atau LLM). Kalau yang kamu maksud "tanpa AI" adalah benar-benar offline dan tanpa model neural, hasilnya akan jauh lebih robotik (misalnya suara bawaan Windows).
- Layanan ini tidak resmi dan bisa berubah. Kalau tiba-tiba gagal, jalankan `pip install -U edge-tts`.

## Cara pakai di Windows

1. Pasang Python 3.9+ dari python.org (centang "Add python.exe to PATH").
2. Klik dua kali `run.bat`. Pertama kali ia memasang kebutuhan sendiri, lalu membuka aplikasi.
3. Tulis atau tempel teks (atau Buka file .txt), pilih suara, lalu **Dengarkan** atau **Simpan MP3**.

## Baris perintah

```
pip install -r requirements.txt
python tts.py -i naskah.txt -o hasil.mp3
python tts.py --text "Halo semua" --voice ardi --rate 10 -o halo.mp3
```

Opsi: `--voice gadis|ardi`, `--rate` (persen, -50 s/d 100), `--pitch` (Hz, -50 s/d 50), `--volume` (persen), `--no-normalize`.

## Cara kerja multiline

- Setiap baris dibaca sebagai satu bagian, dengan jeda 0,45 detik. Baris kosong (paragraf baru) memberi jeda 0,9 detik.
- Baris yang sangat panjang dipecah di akhir kalimat.
- Perapian teks otomatis: singkatan (yg, dgn, dll), `Rp10.000` jadi "10000 rupiah", `50%` jadi "50 persen", emoji dan simbol markdown dibuang.

## Tes

```
python -m unittest
```
