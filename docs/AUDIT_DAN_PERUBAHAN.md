# Audit dan Perubahan — Vilicht Live Studio

**Status verifikasi: kode BELUM dikompilasi.** Lingkungan kerja tidak punya Android SDK/Gradle dan tidak bisa
mengakses Google Maven/JitPack. Yang sudah dilakukan: pembacaan source RootEncoder 2.7.0 untuk memverifikasi setiap API
yang dipakai, dan pemeriksaan sintaks Kotlin (parser) pada semua file. Tidak ada pengujian di emulator/perangkat.
Langkah pertama Anda: jalankan build (Android Studio atau GitHub Actions) dan kirim log error jika ada.

## Temuan audit (kondisi awal)
1. Hanya layer game dan webcam depan (hardcode) yang masuk ke GL. Source Image, Text, Web TIDAK pernah masuk output RTMP; dialog Add Source bahkan tidak menawarkan Browser. Opacity/rotasi/flip/chroma di panel properti tidak berefek pada siaran.
2. Canvas editor tidak pernah menampilkan kamera/game nyata (hanya placeholder), sehingga "kamera tidak ada preview".
3. `onResume` memaksa `setPreviewFrozen(true)` saat LIVE: itu penyebab "freeze" di UI. Selain itu saat Studio di depan, MediaProjection meng-capture Studio sendiri.
4. Audio meter GAME disimulasikan dari volume mic; ada dua AudioRecord mic bersamaan (UI + service).
5. Statistik palsu: bitrate = nilai konfigurasi, totalFrames dijumlahkan dari FPS, dropped = hitungan gagal koneksi, tiga FPS berbeda berisi nilai yang sama.
6. Koordinat canvas bercampur px/dp (pembagi 2.75 hardcoded); ukuran item dan handle berbeda; hanya 1 handle resize; tidak ada snapping.
7. Tombol visibility/lock di daftar source mengubah source yang TERPILIH, bukan source di baris itu.
8. Login Google: TIDAK ADA di ZIP (Firebase Auth dikomentari, tidak ada google-services.json). Tidak saya buat; tidak ada konfigurasi OAuth palsu.
9. Room v1 `exportSchema=false`, tanpa migrasi.

## Diperbaiki / ditambahkan
- Database: versi 2 + `MIGRATION_1_2` (4 kolom baru via ALTER TABLE; tanpa destructive migration). Package name dan identitas tidak berubah.
- Engine baru: kompositor multi-layer (game, kamera, gambar, teks, browser) berurutan z; hanya layer yang berubah yang dibuat ulang.
- Kamera: sumber Camera2 sendiri, pilih ID kamera (depan/belakang/eksternal-USB jika Android melaporkannya), reconnect otomatis, tombol switch camera, lifecycle tertutup rapi.
- Browser Overlay: URL atau HTML lokal (file+resource atau ZIP, aman dari zip-slip). WebView dirender ke GL (AndroidViewFilterRender) sehingga ikut ke RTMP. Crash renderer WebView ditangani.
- Freeze: dihapus. Service tidak bergantung Activity. "Mode editor": saat Studio terlihat, capture game dijeda (layer game membeku di siaran), kamera/overlay/encoder/RTMP tetap jalan; lanjut otomatis saat pindah ke game.
- Preview Studio = hasil komposisi nyata (surface tambahan GL), dipasang/dilepas tanpa menyentuh encoder.
- Canvas: drag, 4 handle resize, kunci rasio, snapping + garis tengah, safe area, fullscreen, undo/redo.
- Screenshot referensi: Photo Picker, hanya editor (`isReference`), tidak pernah ke RTMP.
- Audio: monitor perangkat (kabel/USB/Bluetooth) dengan callback; pilih input mic; perangkat dicabut → kembali otomatis; mic mati dipulihkan. Meter dari PCM nyata; GAME menampilkan status jujur.
- Monitoring: bitrate (`onNewBitrate`), dropped/sent frames dan bytes dari klien RTMP, reconnect count.
- Scene: rename, hapus dengan konfirmasi (minimal 1 scene), duplikasi (sudah ada). UI: tema ungu, target sentuh lebih besar.

## Belum selesai / keterbatasan (jujur)
- Belum dikompilasi/diuji di perangkat (lihat atas). Risiko terbesar: rotasi kamera di portrait, orientasi preview portrait, WebView off-screen tetap menjalankan animasi, frame terakhir game saat masuk mode editor bisa memuat tepi Studio.
- Flip dan chroma key per-source dihapus dari UI karena compositor tidak mendukungnya per layer (kolom DB tetap ada, tidak ada data hilang).
- Opacity tidak berlaku untuk Browser Overlay. Hanya satu sumber kamera per scene.
- Output audio tidak bisa dipindahkan aplikasi biasa (batas Android); hanya ditampilkan. Audio game hanya tertangkap di Android 10+ dan jika game mengizinkan capture.
- Mic Bluetooth: upaya terbaik, bergantung perangkat.
- Login Google: tidak ada di source awal.
- Dialog Settings/profil RTMP tidak diubah.
