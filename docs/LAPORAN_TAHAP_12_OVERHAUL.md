# Laporan Tahap 12 — Overhaul (mic, kamera/canvas, game capture, floating, chat, overlay, PNGTuber, permission)

Status verifikasi memakai label ketat. **Tidak ada satu pun item di bawah yang BUILD SUCCESS, UNIT TEST PASSED, atau DEVICE TEST PASSED.**
Lingkungan kerja tidak punya Android SDK, Gradle wrapper, `kotlinc`, maupun akses ke Google Maven/Maven Central.
Satu-satunya verifikasi mekanis: seluruh `.kt` (main + test) lolos parser sintaks tree-sitter-kotlin (0 error) dan simbol baru
dicocokkan dengan definisinya secara manual. Itu BUKAN kompilasi: error tipe/resolusi tetap mungkin.

Langkah wajib pertama di mesin Anda: `./gradlew assembleDebug testDebugUnitTest`, lalu kirim log error bila ada.

## Per bagian
| # | Bagian | Kode | Build | Unit test | Perangkat |
|---|---|---|---|---|---|
| 1 | Mic single source of truth (`StreamAudioParams.micMutedFlow`) | selesai | NOT BUILT | NOT RUN | NOT TESTED |
| 2 | Kamera/canvas: preset 9:16, 16:9, 1:1, 4:3, 3:4, 6:19, 19:6 + kustom (resolusi/rasio); reshape kotak saat rasio kamera berubah; pan dibatasi zoom; default kamera sadar orientasi | selesai | NOT BUILT | tes ditulis, NOT RUN | NOT TESTED |
| 3 | Game: `GameSourceMonitor` (hysteresis + pemulihan bertahap + backoff layar statis), fallback gamepad "GAME PAUSED"/"COMING SOON", MediaProjection berakhir tidak mematikan siaran, reconnect tanpa restart encoder, audio game dibangun ulang | selesai | NOT BUILT | tes ditulis, NOT RUN | NOT TESTED |
| 4 | Floating window: bubble + panel, drag + snap tepi, status/durasi/viewers/mic/koneksi, mute, Studio, jeda, chat, Stop (2 ketukan) | selesai | NOT BUILT | n/a | NOT TESTED |
| 5 | Chat: `ChatProvider`, provider WebSocket skema VILICHT JSON, hub (dedupe, backoff reconnect), TTS, viewers (`--` bila tak ada), dialog kontrol | selesai | NOT BUILT | tes ditulis, NOT RUN | NOT TESTED |
| 6 | Overlay komentar sebagai source `CHAT` di sistem layer yang ada (ikut encoder) | selesai | NOT BUILT | tes ditulis, NOT RUN | NOT TESTED |
| 7 | PNGTuber: source `PNGTUBER`, idle/bicara, level mic dari pipeline yang ada, sensitivitas/threshold/smoothing/preview/enable | selesai | NOT BUILT | tes ditulis, NOT RUN | NOT TESTED |
| 8 | Permission: item sesi capture + storage; warna non-Compose disamakan dengan tema | selesai | NOT BUILT | n/a | NOT TESTED |

## Batasan yang disengaja
- TikTok tidak punya API publik resmi untuk komentar LIVE. Tidak ada endpoint TikTok di kode. Pengguna menyambungkan WebSocket
  pilihannya (contoh jembatan tidak resmi, BELUM DIUJI: `docs/tools/tiktok_bridge/`). Gift dan viewers hanya ada bila jembatan mengirimnya.
- Token chat dienkripsi Android Keystore (AES-GCM) tanpa fallback plaintext; bila Keystore gagal token hanya di memori.
- Capture layar: token MediaProjection tidak bisa disimpan lintas sesi; bila berakhir, siaran jalan dengan fallback dan pengguna menyambung ulang.
- Asumsi belum terbukti di perangkat: memasang ulang Surface ke VirtualDisplay menghasilkan frame baru pada layar statis.
- Suara TTS "ikut ke stream" hanya jalan bila capture audio game aktif.
- Tidak ada perubahan skema Room (CHAT/PNGTUBER = nilai enum baru; config PNGTuber di kolom `textContent`). Tidak perlu migration baru.
- File gambar "bicara" PNGTuber dibersihkan lewat query DAO baru (`countPngTuberTalkUsing`).
