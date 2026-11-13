# Teks ke Suara (Android)

Aplikasi Android untuk mengubah teks menjadi suara Bahasa Indonesia. Memakai mesin suara (TextToSpeech) bawaan perangkat, tanpa layanan AI dan tanpa API key.

## Fitur

- Input teks banyak baris, ada jeda otomatis antar baris dan antar paragraf
- Suara Bahasa Indonesia, bisa pilih suara (offline didahulukan)
- Atur kecepatan dan nada
- Baca, jeda, lanjut, berhenti; bagian yang sedang dibaca disorot
- Rapikan teks otomatis: singkatan umum (yg, dgn, dll), angka ribuan, Rp, persen, emoji, simbol markdown
- Terima teks dari aplikasi lain lewat menu Bagikan
- Tombol tempel dari clipboard, teks terakhir tersimpan

## Agar suara terdengar natural

Kualitas suara ditentukan mesin suara di HP. Pastikan **Layanan Ucapan oleh Google** terpasang dan data suara Bahasa Indonesia sudah diunduh (Pengaturan > Output text-to-speech). Aplikasi akan menawarkan jalan pintas jika suara Indonesia belum ada.

## Build APK

Setiap push ke branch `main` menjalankan GitHub Actions (`.github/workflows/build-apk.yml`): unit test, build APK debug, lalu mengunggahnya ke tab **Releases** dan ke artifact workflow. Bisa juga dijalankan manual lewat tab Actions (Run workflow).

Build lokal (butuh Android SDK, JDK 17, Gradle 8.9):

```
gradle testDebugUnitTest assembleDebug
```

APK ada di `app/build/outputs/apk/debug/app-debug.apk`.
