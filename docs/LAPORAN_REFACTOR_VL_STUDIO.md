# Laporan Refactor VL Studio

> Status build: **TIDAK dikompilasi.** Lingkungan kerja tidak punya Android SDK dan tidak bisa mengakses Google Maven.
> Yang sudah dicek: sintaks seluruh file Kotlin (tree-sitter, 0 error) dan referensi simbol/import secara manual.
> Yang BELUM dicek: kompilasi Gradle/KSP, tipe, dan semua perilaku runtime di perangkat. Jalankan
> `./gradlew assembleDebug` lebih dulu dan perbaiki error kompilasi yang mungkin muncul.

## Dikerjakan
| Area | Perubahan | File utama |
|---|---|---|
| Canvas drag | Tanpa `coerceIn`; layer bebas keluar canvas, overlay seleksi tidak di-clip | `StudioCanvas.kt` |
| Resize sudut | Skala proporsional searah diagonal, sudut seberang tetap diam (kompensasi rotasi) | `StudioCanvas.kt` |
| Crop | 4 handle sisi = crop UV nyata (bukan scale); disimpan `cropLeft/Top/Right/Bottom` | `StudioCanvas.kt`, `LayerFilterRender.kt`, `Entities.kt` |
| Renderer | Filter GL baru: crop, mirror, rotasi di ruang piksel (tanpa distorsi), posisi bebas | `gl/LayerFilterRender.kt`, `StudioEngine.kt` |
| Database | Migrasi 2→3 (hanya ADD COLUMN, data lama aman) | `StudioDatabase.kt` |
| Properties Panel | Position, Width, Height dihapus total; Quick Actions, satu slider Scale, Rotation 0–360, Appearance, Crop | `PropertiesPanel.kt`, `StudioViewModel.kt` |
| Preview kamera | Engine bisa jalan tanpa MediaProjection (mode kamera-saja); Go Live memakai pipeline yang sama | `StudioEngine.kt`, `StreamingService.kt`, `MainActivity.kt` |
| Kamera | Ganti kamera/resolusi/FPS tanpa membuat ulang layer GL; error kamera tampil + tombol Coba lagi/Beri izin | `StudioEngine.kt`, `StudioCanvas.kt` |
| Service | WakeLock parsial (timeout 30 mnt, diperpanjang tiap 10 mnt, dilepas saat stop/gagal), notifikasi dengan durasi, status, Stop, Return to Studio, monitor jaringan & termal, reconnect sadar jaringan | `StreamingService.kt` |
| Izin | Halaman "Permissions & Device Protection", wizard optimasi, status nyata, OEM guide, izin kontekstual (tidak dipaksa) | `permissions/PermissionCenter.kt`, `PermissionsScreen.kt` |
| Manifest | `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | `AndroidManifest.xml` |

Konfigurasi wajib tidak diubah: namespace `com.example`, applicationId `com.vilicht.livestudio`, compileSdk 36, minSdk 24, RootEncoder 2.7.0.

## TIDAK dikerjakan
Scene transition (Cut/Fade); Video Source; opsi Text lanjutan (font, shadow, scrolling, animasi); audio mixer baru
(meter/peak/clipping per sumber); rekaman lokal; halaman Device Health & Streaming Protection (CPU/RAM; hanya
termal/jaringan/WakeLock yang masuk ke stats service); reorganisasi Settings; UI untuk interval/jumlah reconnect
(nilainya ada di `StreamingService.reconnectMaxRetries/reconnectIntervalMs` tapi belum ada kontrolnya); blend mode
(renderer hanya alpha normal); crop/rotasi/mirror untuk Browser Source (filter WebView tidak mendukung).

## Asumsi yang HARUS diuji di perangkat
1. Orientasi kamera: `quarterTurns = (sensorOrientation − displayRotation)`. Jika gambar miring/terbalik, ubah `quarterTurnsFor()` di `StudioEngine.kt`.
2. Canvas portrait: shader memetakan koordinat portrait ke frame landscape yang diputar 90° searah jarum jam (dari pembacaan kode RootEncoder). Belum diverifikasi; cek posisi layer pada scene portrait.
3. Kamera depan otomatis diberi Mirror saat ditambahkan; bisa dimatikan lewat tombol Mirror.
4. Rotasi layer berpusat pada kotak layer penuh (sebelum crop), sama di editor dan siaran.
5. Go Live tanpa layar game (scene tanpa Screen source) memakai mikrofon saja, tanpa dialog capture layar.
6. Intent langsung ke layar OEM adalah upaya terbaik; bila gagal, panduan manual tampil.
