# Laporan Tahap 10 — Video tidak terkirim saat LIVE (Frame Terkirim = 0, 1 FPS)

## STATUS VERIFIKASI (jujur)
- Gradle build / APK: BELUM dijalankan. 45 file Kotlin lolos parse sintaks. Tidak diuji ke server RTMP maupun di perangkat.
- Diagnosis berdasarkan (1) tangkapan layar diagnostik pengguna dan (2) pembacaan source RootEncoder 2.7.0 (tag asli dari GitHub).

## Gejala (dari layar Diagnostics saat LIVE 02:46)
Status LIVE dan tersambung ke server, tetapi Render FPS 1, Frame Terkirim 0, bitrate 131 kbps (= hanya audio AAC 128 kbps).
Artinya koneksi RTMP dan audio berjalan, video tidak.

## Akar masalah (terbukti dari source)
Mesin memakai `NoVideoSource` sebagai sumber utama. `NoVideoSource` tidak pernah mengisi SurfaceTexture utama, sedangkan
`GlStreamInterface.draw()` memberi encoder presentation time dari `mainRender.getSurfaceTexture().timestamp` (surface mode).
Karena tekstur utama tidak pernah menerima frame, timestamp itu SELALU 0: semua frame yang masuk encoder bertimestamp sama.
Encoder lalu hanya mengeluarkan sekitar 1 frame per detik (angka "Render FPS" dihitung per frame keluaran encoder:
`fpsListener.calculateFps()` di `getVideoData`), dan timestamp FLV/RTMP tidak maju sehingga video tidak dikirim.
Ini juga menjelaskan keluhan sebelumnya "live tidak benar-benar jalan": jalur video tidak pernah bekerja.

## Perbaikan
1. `engine/FrameClockVideoSource.kt` (baru): VideoSource yang memposting frame hitam kecil (64x64) ke tekstur utama pada fps target lewat
   thread sendiri. Tiap frame memicu satu putaran render dan membawa timestamp monotonik yang terus naik.
2. `StudioEngine.buildPipeline`: memakai `FrameClockVideoSource` menggantikan `NoVideoSource`. Render paksa diturunkan menjadi jaring pengaman 5 fps
   (hanya aktif bila jam frame berhenti). ImageReader preview kini dikuras di thread sendiri (bukan thread utama), sehingga UI yang sibuk
   tidak menahan thread GL/encoder.
3. `StreamingService`: bila status LIVE tetapi 0 frame video terkirim setelah 10 detik, tampil toast peringatan sekali per siaran.

## Yang perlu diuji di perangkat
Setelah GO LIVE: Render FPS mendekati 30, Frame Terkirim terus bertambah, bitrate jauh di atas 131 kbps, gambar muncul di server RTMP.
Bila masih 0 frame: kirim tangkapan layar Diagnostics + logcat (filter: VilichtFrameClock, RtmpClient, VideoEncoder).
