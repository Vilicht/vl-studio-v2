# Laporan Audit Tahap 2 — VILICHT LIVE STUDIO

## Status verifikasi (jujur)
- Dikompilasi: **BELUM**. Lingkungan kerja tidak punya Android SDK dan tidak dapat menjangkau Google Maven, JitPack, atau distribusi Gradle.
- Sintaks Kotlin (tree-sitter) seluruh file: 0 error. Ini BUKAN bukti kompilasi berhasil.
- Unit test `RtmpEndpointTest` ditulis, belum dijalankan.
- Emulator / perangkat fisik / RTMP server nyata: **tidak ada pengujian**.
- APK debug/release: **belum ada**. Dihasilkan oleh GitHub Actions (lihat bawah).

## Akar masalah yang ditemukan dan diperbaiki
| # | Temuan | Akar penyebab | Perbaikan |
|---|---|---|---|
| 1 | Siaran Custom RTMP gagal tanpa pesan jelas | Profil bawaan memakai stream key dummy `live_stream_key_sample`; endpoint disambung tanpa validasi/trim (spasi/newline hasil paste ikut terkirim) | `encoder/RtmpEndpoint.kt`: validasi rtmp(s)://, host, key kosong/dummy/mengandung spasi; trim hanya di ujung; isi key tidak diubah; error tampil sebagai Toast dan Settings terbuka |
| 2 | Profil bawaan menyimpan key palsu | `StudioRepository` mengenkripsi nilai contoh | Key bawaan kini kosong; profil Custom diawali `rtmp://` |
| 3 | Status LIVE bisa palsu | `togglePause()` menyetel LIVE walau masih CONNECTING (`engine.isLive` sudah true sebelum `onConnectionSuccess`) | Pause/Resume hanya saat status LIVE/PAUSED |
| 4 | State machine tidak lengkap | Enum tidak punya PREPARING/STOPPING | Ditambah dan dipasang di service, top bar, overlay, dan notifikasi |
| 5 | Rotasi kamera depan terbalik di landscape | `quarterTurnsFor` memakai `sensor - rotasi layar` untuk semua kamera; kamera depan harus `sensor + rotasi layar` | Rumus sadar-facing |
| 6 | Tidak ada FILL / Align / Reset Transform | Fitur belum ada | `fillGame`, `fillToScreen`, `alignGameToReference`, `resetTransform` + tombol di Properties Panel |
| 7 | CI: tidak ada SDK setup, tanpa test, tanpa release | Workflow minimal | Workflow baru: setup-android, unit test, debug APK, release APK hanya jika secret signing ada |

## Temuan yang TIDAK saya ubah, dan alasannya
- **Login Google**: tidak ada di source (Firebase Auth dikomentari, tidak ada `google-services.json`). Tidak saya buat karena butuh Firebase project + SHA-1 milik Anda.
- **Orientasi output vs game**: arsitektur sudah memisahkan canvas output dari layer game (game = layer GL di canvas portrait). Tidak ditemukan satu variabel yang memaksa game ikut portrait. Perlu diuji di perangkat.
- Rendering Browser Overlay, audio game, rekoneksi: dibaca, tidak ada bug jelas yang bisa dibuktikan tanpa menjalankan.

## Release signing
Tambahkan secrets repo: `KEYSTORE_BASE64` (`base64 -w0 my-upload-key.jks`), `STORE_PASSWORD`, `KEY_PASSWORD`. Alias key harus `upload`.

## Wajib diuji di perangkat
Rotasi kamera depan/belakang (portrait & landscape), Fill/Align, RTMP ke server nyata, audio game (Android 10+, tergantung izin capture tiap game), Browser Overlay di output, siklus background/foreground.
