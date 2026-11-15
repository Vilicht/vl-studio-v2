# Laporan Tahap 9 — Go Live langsung, teks polos + opsi gaya, pengaman rotasi kamera

## STATUS VERIFIKASI (jujur)
- Gradle build / APK: BELUM dijalankan (tanpa Android SDK / Google Maven di lingkungan kerja). 44 file Kotlin lolos parse sintaks.
- Tidak ada uji perangkat dan tidak ada uji ke server RTMP sungguhan. Migrasi Room v7→v8 belum dijalankan.

## 1. Go Live
Temuan: tidak ada kode dummy. Siaran memang RTMP asli (`RtmpStream.startStream(serverUrl/streamKey)`). Masalahnya pada alur:
- GO LIVE pertama hanya meminta izin capture lalu masuk "mode siap" (toast "Siap. Buka game, lalu tekan Atur Layout"); siaran baru
  mulai setelah GO LIVE ditekan LAGI. Banyak pengguna mengira ini "tidak live".
- Kegagalan (key salah, server menolak, mesin belum siap) hanya tercatat di state UI; aplikasi sudah dipindah ke latar sehingga tidak terlihat.
- Tanpa izin mikrofon dan tanpa capture audio game, sumber audio melempar exception.

Perubahan:
1. `MainActivity`: Server URL + Stream Key divalidasi DI AWAL (sebelum izin apa pun). Setelah izin capture layar diberikan, siaran
   langsung dimulai (`EXTRA_AUTO_ENDPOINT`); pipeline dibuat sinkron di service lalu `goLive()` dipanggil pada panggilan yang sama.
2. `StreamingService`: toast terlihat di atas game untuk: tersambung (LIVE), koneksi gagal, ditolak server (auth), encoder audio gagal, mesin belum siap.
3. `GameMicAudioSource`: bila tidak ada sumber audio, mengirim audio senyap real-time alih-alih melempar exception.
Server RTMP custom: isi Server URL `rtmp://host[:port]/app` dan Stream Key terpisah; endpoint = `URL/key`.

## 2. Kamera
- Gejala di tangkapan layar (kepala rebah + gepeng, canvas portrait) sama dengan bug sumbu portrait yang diperbaiki di Tahap 8
  (simulasi: lingkaran 39x123). Kemungkinan besar tangkapan layar berasal dari APK sebelum perbaikan itu.
- Pengaman tambahan bila masih miring di perangkat tertentu: (a) `SCALER_ROTATE_AND_CROP` dipaksa NONE (Android 11+, bila didukung)
  agar sistem tidak memutar/memotong gambar sendiri di tablet; (b) Properties kamera: "Putar isi kamera" 0/90/180/270 (kolom `cameraRotation`).

## 3. Teks
- Background hitam tipis berasal dari nilai bawaan `textBgColor = 0x88000000`. Kini bawaan = 0 (polos). Migrasi mengubah teks lama
  yang masih bernilai 0x88000000 menjadi polos (pilihan lain pengguna tidak disentuh).
- Properties teks: Warna teks, Background (Tanpa + 8 warna + slider opasitas), Garis tepi (Tanpa + 6 warna + slider tebal).
  Berlaku sama di editor dan di stream (garis tepi digambar di bawah isi teks; tebal = pecahan ukuran font).
- DB v7→v8: `textStrokeColor`, `textStrokeWidth`, `cameraRotation`.
- Teks bawaan seed ("VILICHT LIVE", "STREAM STARTING SOON") tetap punya background karena memang dirancang sebagai banner; ubah ke "Tanpa" bila perlu.

## Yang perlu diuji di perangkat
GO LIVE ke server RTMP custom (status LIVE + toast), key salah (toast error), kamera depan/belakang di portrait, teks polos + warna/garis tepi,
update dari versi lama (migrasi jalan tanpa hapus data).
