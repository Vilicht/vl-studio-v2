# Laporan Tahap 11 — Kamera 4:3 dan tanpa auto-mirror

## STATUS VERIFIKASI (jujur)
- Gradle build / APK: BELUM dijalankan. 45 file Kotlin lolos parse sintaks. Tidak diuji di perangkat. Migrasi Room v8→v9 belum dijalankan.

## Perubahan
1. Auto-mirror dihapus: kamera baru tidak lagi otomatis Flip Horizontal, dan ganti kamera depan/belakang tidak lagi mengubah Flip.
   Mirror kini murni pilihan pengguna lewat tombol Mirror di Properties.
2. Migrasi v8→v9: semua source kamera lama dikembalikan ke Flip = mati (nilai lama hampir pasti hasil auto-mirror). Bila ingin cermin, tekan Mirror lagi.
3. Kamera memakai 4:3 (`CameraLayerSource.chooseSize`): memilih ukuran 4:3 terbesar dalam batas piksel (mis. 1024x768 / 960x720). 4:3 memakai seluruh sensor
   (sudut pandang lebih lebar, tidak terpotong atas-bawah seperti 16:9), sama seperti app referensi. Bila perangkat tidak punya 4:3, kembali ke 16:9.
4. Kotak kamera baru dan `contentAspect` bawaan kini 4:3. Source kamera lama yang belum diubah ukurannya otomatis menyesuaikan tingginya ke 4:3 begitu kamera aktif.

## Catatan
- Rasio 4:3 TIDAK sendirinya memperbaiki "gepeng". Gepeng berasal dari bug sumbu portrait di shader (Tahap 8). 4:3 hanya membuat tampilan sama dengan referensi.
- Jika gambar terbalik ATAS-BAWAH (bukan kiri-kanan), pakai "Putar isi kamera" 180° di Properties kamera.
