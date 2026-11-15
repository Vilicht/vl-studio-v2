# Laporan Audit Tahap 3 — VILICHT LIVE STUDIO

## Keputusan pengembangan
- Google Login ditunda sesuai keputusan pemilik project.
- Prioritas: menyelesaikan fitur yang ada secara menyeluruh.

## Perbaikan yang diterapkan pada source tahap 2
1. **Launcher icon:** seluruh ikon mipmap (mdpi sampai xxxhdpi), ikon round, dan adaptive foreground dibuat ulang dari `app_icon_master.png`. Adaptive icon sebelumnya menunjuk drawable foreground transparan; sekarang menunjuk artwork ikon.
2. **Callback RTMP terlambat:** `onConnectionStarted()` dan `onConnectionSuccess()` sekarang mengabaikan callback jika pengguna sudah meminta Stop atau service sedang STOPPING/OFFLINE, agar status LIVE tidak hidup kembali setelah siaran dihentikan.
3. **Release signing CI:** workflow memeriksa ketiga secret (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`). Jika tidak ada sama sekali, hanya debug APK dibuat; jika sebagian saja terisi, workflow gagal dengan pesan yang jelas daripada mencoba release signing yang pasti tidak lengkap.

## Temuan yang masih harus diverifikasi
- `RtmpEndpoint` melakukan validasi sintaks dasar; koneksi tetap perlu diuji ke server RTMP/RTMPS nyata.
- `StudioEngine.goLive()` menandakan proses mulai setelah `startStream()` dipanggil; keberhasilan koneksi tetap hanya boleh ditentukan oleh callback RTMP.
- `alignGameToReference()` saat ini memakai contain di dalam bounding box screenshot. Ini mempertahankan rasio, tetapi belum memberi pilihan align-fill yang memenuhi kotak referensi.
- Orientasi portrait-output/landscape-source, rendering browser overlay, audio, kamera, dan siklus background/foreground belum diuji pada perangkat fisik.
- Google Login sengaja tidak diimplementasikan pada tahap ini.

## Batas verifikasi lingkungan
- Tidak tersedia Android SDK / Gradle executable pada lingkungan audit ini.
- Project tidak menyertakan `gradlew` dan `gradle-wrapper.jar`; GitHub Actions menggunakan Gradle terpasang melalui setup-gradle.
- Tidak ada APK yang diklaim berhasil dibangun pada tahap ini.
- Tidak ada pengujian emulator, perangkat fisik, atau RTMP server nyata yang diklaim berhasil.

## Langkah verifikasi berikutnya
1. Jalankan workflow `Build Android APK` pada GitHub.
2. Pastikan unit test `RtmpEndpointTest` dan `assembleDebug` lulus.
3. Instal APK debug di Redmi Pad Pro.
4. Uji kamera depan/belakang dalam portrait dan landscape.
5. Uji game landscape dengan output portrait, FIT/FILL, dan screenshot reference.
6. Uji browser overlay dan audio.
7. Uji Custom RTMP dengan endpoint dan stream key milik pengguna; pastikan video/audio terlihat di penerima streaming.
