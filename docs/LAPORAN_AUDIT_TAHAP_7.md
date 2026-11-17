# Laporan Tahap 7 — Audit terhadap spesifikasi "rombak total" dan perbaikan celah

## STATUS VERIFIKASI (jujur)
- **Gradle build / APK: BELUM dijalankan.** Lingkungan kerja tidak punya Android SDK dan tidak bisa menjangkau Google Maven / JitPack.
- Terverifikasi: seluruh 44 file Kotlin lolos parse sintaks (tree-sitter, 0 error). Ini BUKAN bukti kompilasi penuh.
- Tidak ada uji perangkat (kamera, MediaProjection, audio, RTMP).
- Jalankan GitHub Actions `Build Android APK` (atau `./gradlew assembleDebug`) dan kirim log bila ada error.

## Hasil audit terhadap spesifikasi
Mayoritas isi spesifikasi SUDAH ada di kode (tahap 4–6). Proyek TIDAK ditulis ulang dari nol karena akan membuang
fitur yang sudah ada dan menambah risiko regresi tanpa bisa dikompilasi di sini. UI (tema, warna, tata letak) tidak diubah.

| Butir spesifikasi | Status di kode | Tindakan |
|---|---|---|
| 1. Canvas tetap + preview identik encoder | Sudah: compositor GL RootEncoder satu untuk editor, Preview Live, dan encoder; canvas per scene (`StudioEngine.reconfigureCanvas`), fit/zoom/crop di shader (`LayerFilterRender`), koordinat source ternormalisasi terhadap canvas | Tidak diubah |
| 2. Video overlay langsung tampil + loop | Ada (`VideoLayerSource`, MediaPlayer→SurfaceTexture, `isLooping`, `updateTexImage` di `LayerFilterRender`) | **Perbaikan penyebab: lihat A dan B di bawah** |
| 3a. Browser Source transparan | Sudah (`WebOverlayHost` + `WebLayerSource` → SurfaceTexture, 24 fps) | Tidak diubah |
| 3b. Dual audio mixer | Sudah (`GameMicAudioSource`: AudioPlaybackCapture + mic, gain dan mute terpisah) | Tidak diubah |
| 3c. Bubble kontrol melayang | Ada (`FloatingLiveOverlay`, TYPE_APPLICATION_OVERLAY) tetapi TANPA Ganti Scene | **Ditambah: lihat C** |
| 3d. Aset logo | Dua file besar di `res/drawable/` (diskalakan per kepadatan) | **Dipindah ke `drawable-nodpi/`: lihat D** |
| 4. Titik kompilasi | `disableResources`, null-safety `getMediaProjection`, import `rememberSaveable`, import `items` sudah ada. Slider di PropertiesPanel BELUM aman | **Diperbaiki: lihat E** |

## Perubahan
- **A. Overlay video/web tidak muncul tanpa kamera.** Pipeline GL (engine) hanya dinyalakan otomatis bila scene punya
  source Kamera. Scene berisi video/web saja tidak punya engine yang berjalan, jadi hanya placeholder yang tampil.
  `MainActivity` kini juga menyalakan pipeline untuk scene dengan source Video/Browser yang terlihat
  (`needsEnginePreview`). Pipeline tidak membuka kamera bila scene tidak punya source Kamera.
  Batasan: jalur ini memakai service bertipe kamera/mikrofon, jadi tetap butuh izin kamera (sama seperti sebelumnya).
- **B. `takePersistableUriPermission`** dipanggil segera setelah video/gambar dipilih (`StudioViewModel.persistReadPermission`),
  dibungkus try/catch karena URI photo picker hanya memberi izin sementara. File tetap disalin ke penyimpanan app.
- **C. Tombol "Ganti Scene"** di bubble: `FloatingLiveOverlay` → `StreamingService.nextSceneListener` → `StudioViewModel.cycleScene()`.
  Pindah scene dengan canvas berbeda saat LIVE tetap ditolak (batas encoder) dengan pesan seperti sebelumnya.
- **D. Aset**: `ic_vilicht_logo.png` dan `vilicht_icon_1794323042231.jpg` dipindah ke `res/drawable-nodpi/` (nama resource sama).
  Tidak ada kode yang memakai keduanya saat ini.
- **E. Error kompilasi nyata di `PropertiesPanel.kt`**: tiga `GoldSlider` (chroma kemiripan/kehalusan/spill) memakai trailing lambda yang
  terikat ke parameter terakhir `onFinished`, sehingga `onChange` tidak terisi. Semua pemanggilan `GoldSlider`/`CropRow` kini memakai
  `onChange = { ... }` eksplisit. `LeftPanel.kt`: `items(items = ..., key = ...)` dan `itemsIndexed(items = ...)`.

## Sengaja TIDAK dilakukan
- **Media3 ExoPlayer**: tidak diganti. `MediaPlayer` dengan `isLooping = true` sudah berjalan dan terhubung ke kebijakan capture audio.
  Mengganti menambah dependensi baru yang tidak bisa diverifikasi di sini. Bisa dikerjakan terpisah bila pemutar terbukti bermasalah.
- **Penulisan ulang pipeline GL/FBO**: tidak dilakukan. Compositor RootEncoder sudah menjadi FBO tunggal; tahap 4 sudah memperbaiki akar masalah gepeng.
- **Aset logo**: tidak ada `Image(...)` logo di UI saat ini, jadi `ContentScale.Fit` belum relevan.

## Belum diuji di perangkat
Video muncul < 1 detik di scene tanpa kamera, tombol Ganti Scene di bubble, Browser Source transparan, audio game + mic.
