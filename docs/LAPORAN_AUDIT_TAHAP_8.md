# Laporan Tahap 8 — Perombakan jalur kamera (akar masalah gepeng di portrait)

## STATUS VERIFIKASI (jujur)
- Gradle build / APK: BELUM dijalankan (lingkungan tanpa Android SDK dan tanpa akses Google Maven).
- Terverifikasi: 44 file Kotlin lolos parse sintaks; perilaku RootEncoder 2.7.0 dibaca dari SOURCE aslinya (tag 2.7.0);
  pemetaan shader lama vs baru dibuktikan lewat simulasi CPU (`docs/tools/simulasi_shader_kamera.py`).
- Simulasi adalah EMULASI shader, bukan uji di HP. Uji kamera depan/belakang di portrait dan landscape tetap wajib di perangkat.

## Akar masalah (terbukti dari source)
`RtmpStream.prepareVideo(width, height, ..., rotation = 90)` di RootEncoder 2.7.0 memanggil
`glInterface.setEncoderSize(height, width)`: encoder DAN frame filter (FBO) menjadi portrait (cw x ch).
`MediaFormat` encoder juga dibuat (height, width). `GlStreamInterface` tidak memutar isi frame (`streamOrientation` = 0 karena
proyek tidak memanggil `setStreamRotation`), dan preview multi-surface juga menggambar dengan rotasi 0.

Shader `LayerFilterRender` lama menganggap frame filter itu LANDSCAPE dan menukar sumbu untuk canvas portrait
(`n = vec2(vFrame.y, vFrame.x)`). Akibatnya di canvas portrait SEMUA layer (kamera, game, gambar, video, web)
tergambar miring dan teregang. Simulasi (lingkaran uji pada canvas 108x192):

| Kasus | Kode lama | Kode baru |
|---|---|---|
| Kamera belakang (sensor 90), portrait, Utuh | lingkaran 39x123 (rasio 0,32), miring | 69x69 (rasio 1,00), tegak |
| Kamera depan (sensor 270), portrait | (tidak diuji pada kode lama) | 1,00, tegak |
| Mode Penuhi, portrait | — | 1,00, tegak |
| Landscape (sensor 0 dan 90) | sama | 1,00, tegak |

## Perubahan
1. `gl/LayerFilterRender.kt`: pemetaan frame -> canvas kini `n = vec2(vFrame.x, 1.0 - vFrame.y)` untuk semua canvas.
   Properti `portrait`, uniform `uPortrait`, dan penukaran sumbu dihapus.
2. `engine/StudioEngine.kt`: baris `f.portrait = ...` dihapus; komentar konvensi RootEncoder dikoreksi sesuai source.
3. `ui/viewmodel/StudioViewModel.kt`: `switchCamera()` dan `setCameraId()` kini menyelaraskan Flip Horizontal dengan sisi kamera
   baru (depan = cermin, belakang = tidak) hanya bila sisinya berubah. Flip manual tetap bisa diubah di Properties.

## Tidak diubah (sengaja)
- `CameraLayerSource` (Camera2) dan matematika rotasi sensor: arah putaran 90/180/270 dihitung ulang dan benar; tidak ada bukti masalah di sana.
- UI Compose tidak disentuh.

## Yang perlu dicek di perangkat
- Canvas portrait: kamera belakang dan depan tegak, bulat tidak lonjong, preview = hasil stream.
- Canvas landscape dan 1:1 tidak berubah.
- Posisi/skala layer lain (gambar, teks, game) di portrait: sebelumnya ikut salah, sekarang seharusnya mengikuti kotak di editor.
