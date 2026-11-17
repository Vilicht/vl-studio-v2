# Laporan Integrasi — Fitur Referensi Streamingin (webcam USB, bitrate adaptif, efek mikrofon)

## STATUS VERIFIKASI (jujur)
- **Gradle build / APK: BELUM dijalankan.** Lingkungan kerja tidak bisa menjangkau Maven Central, JitPack, maupun Google Maven.
  Tidak ada uji di perangkat, tidak ada uji dengan webcam USB sungguhan, tidak ada uji ke server RTMP.
- Yang SUDAH dijalankan dengan kompiler Kotlin 2.2.10 asli (versi yang sama dengan project):
  - `UsbCameraId.kt` dan `AdaptiveBitrateController.kt` dikompilasi, dan **34 unit test baru lulus**
    (dijalankan dengan shim JUnit di lingkungan kerja; di CI memakai JUnit asli).
  - 5 file kamera (`CameraFeed`, `UsbCameraId`, `UsbCameraCatalog`, `UvcCameraLayerSource`, `CameraLayerSource`) diperiksa tipenya
    terhadap `android.jar` API 34 + source Java UVCAndroid asli: lulus. Uji negatif (kode sengaja salah) ditolak, jadi pemeriksanya bekerja.
  - Seluruh pohon project dikompilasi sebelum dan sesudah perubahan dan diagnostiknya dibandingkan: tidak ada error sintaks atau tipe baru.
    Selisih hanya error lingkungan (Compose/coroutines/lifecycle tidak tersedia di lingkungan kerja), polanya sama dengan file yang tidak diubah.
- Yang TIDAK terverifikasi dan harus dicek oleh build/perangkat Anda:
  - Kompilasi Compose (`ToggleRow`, `AudioDevicesDialog`, `SettingsDialog`) dan baris yang memakai tipe coroutines.
  - Manifest merge dan resource.
  - Koordinat `com.herohan:UVCAndroid:1.0.13`: diambil dari README upstream, tidak bisa di-resolve dari lingkungan kerja.
  - Perilaku di perangkat nyata (negosiasi mode webcam, izin USB, cabut-colok saat siaran).

## 1. Temuan audit APK referensi
| Paket di hasil dekompilasi | Isi sebenarnya | Penanganan |
|---|---|---|
| `com.pedro.*` (476 kelas) | RootEncoder, **lebih baru dari 2.7.0** (memuat `StreamingStatsReport`, `Throughput`, `QueueAwareBitrateAdapter`, parser AV1/H265 yang tidak ada di 2.7.0) | Tidak disalin. Project sudah memakai RootEncoder 2.7.0 resmi (JitPack). Versi tidak dinaikkan: API yang dipakai engine sudah diverifikasi terhadap source 2.7.0. |
| `com.herohan.uvcapp.*`, `com.serenegiant.*` | UVCAndroid (shiyinghan), **identik dengan upstream** (5 kelas "tambahan" hanya lambda `Runnable` sintesis JADX) | Tidak disalin. Ditambahkan sebagai dependency resmi Maven Central `com.herohan:UVCAndroid:1.0.13`. |
| `app.streamingin.device.*` (`LiveService`, `StreamingApp`) | Logika aplikasi referensi: akun, YouTube/Facebook, cast iOS, song request, TTS, Firebase, overlay | Dipetakan di bagian 2. Perilaku yang relevan ditulis ulang sendiri, kode aplikasi referensi tidak disalin. |
| `p000.*` (9.282 file) | Library pihak ketiga dan kode aplikasi yang diobfuskasi | Tidak dipakai langsung. |

Alasan memakai artefak resmi dan bukan salinan hasil dekompilasi: kode JADX penuh nama `r0/r1` dan tidak sama dengan yang dirilis,
sedangkan artefak resmi bersih, bisa diperbarui, dan sudah terbukti setara untuk pustaka UVC.

## 2. Peta fitur referensi → project Anda
| Fitur | Status | Bukti / catatan |
|---|---|---|
| Streaming RTMP/RTMPS | **Sudah ada** | `StudioEngine.goLive`, RootEncoder `RtmpStream` |
| Kamera depan/belakang + tombol ganti | **Sudah ada** | `CameraLayerSource` (Camera2); `switchCamera()` berputar melalui semua kamera, **kini termasuk webcam USB** |
| Kamera eksternal USB/UVC | **DIINTEGRASIKAN** | Bagian 3.1. Sebelumnya hanya kamera eksternal yang diekspos Camera2 |
| Screen capture / MediaProjection | **Sudah ada** | layer `SCREEN`, game capture |
| Resolusi / FPS / bitrate video | **Sudah ada** | preset + slider bitrate (800–9000 kbps) + bitrate audio per profil |
| Bitrate adaptif (QueueAware) | **DIINTEGRASIKAN** | Bagian 3.2. Default mati |
| Overlay teks / gambar / video / web / chat / PNGTuber / chroma key / filter GL | **Sudah ada** | `SourceType`, `LayerFilterRender`, `StudioFilters`; piksel transparan tidak menghasilkan kotak hitam/putih (blend `mix(base, layer, a·alpha)`) |
| Mic: mute, gain, volume sistem | **Sudah ada** | `StreamAudioParams`, mixer audio game + mic |
| Peredam noise / echo / gain otomatis mic | **DIINTEGRASIKAN** | Bagian 3.3. Default mati |
| Auto-reconnect | **Sudah ada, lebih lengkap dari referensi** | callback jaringan, hitung percobaan, status RECONNECTING; tidak ditulis ulang |
| Foreground Service | **Sudah ada** | `mediaProjection\|microphone\|camera` |
| Tawaran "buka aplikasi" saat webcam dicolok | **DIINTEGRASIKAN** | `UsbAttachActivity` (Bagian 3.1) |

## 3. Yang diubah
### 3.1 Webcam USB (UVC)
- `engine/CameraFeed.kt`: kontrak umum sumber kamera. `CameraLayerSource` (Camera2) dan `UvcCameraLayerSource` (USB) sama-sama mengimplementasikannya,
  sehingga engine tidak tahu bedanya.
- `engine/UvcCameraLayerSource.kt`: alur resmi library (`onAttach → selectDevice → openCamera → startPreview + addSurface`) menulis ke `SurfaceTexture`
  layer GL yang sama dengan kamera biasa. Webcam yang sudah tercolok sebelum aplikasi dibuka dan webcam yang dicolok ulang tersambung otomatis.
  Library native yang gagal dimuat dilaporkan sebagai status, tidak membuat aplikasi crash.
- `engine/UsbCameraId.kt` (logika murni, diuji): ID `uvc:<vendor>:<product>` (`#2` untuk perangkat kembar) disimpan di kolom `cameraId` yang sudah ada,
  **tanpa migrasi Room**. `UvcModeSelector` memilih mode: fps **harus** dari daftar fps mode itu (library melempar `IllegalArgumentException` untuk fps di luar daftar).
- `StudioEngine`: memilih sumber menurut ID, mengganti objek sumber saat berpindah Camera2 ↔ USB, dan **tidak** memutar gambar webcam mengikuti rotasi layar
  (webcam sudah tegak; kamera internal tetap dikoreksi seperti sebelumnya).
- `UsbAttachActivity` + `res/xml/usb_camera_filter.xml`: menerima tawaran sistem saat webcam dicolok sehingga izin USB bisa diingat. **Tidak** menarik layar Studio ke depan bila
  service sudah berjalan, agar tidak mengganggu game yang sedang dimainkan.
- Manifest: izin `MANAGE_EXTERNAL_STORAGE`, `READ/WRITE_EXTERNAL_STORAGE` yang dibawa manifest library dihapus (`tools:node="remove"`); aplikasi tidak memakainya.
- Daftar kamera di picker diperbarui saat USB dicolok/dicabut (receiver di ViewModel, ditambah refresh di `onResume` karena sebagian perangkat tidak mengirim broadcast ATTACHED).

### 3.2 Bitrate adaptif (`encoder/AdaptiveBitrateController.kt`, `StreamingService`, `Pengaturan`)
Ditulis ulang dari logika `QueueAwareBitrateAdapter` referensi, dengan sinyal yang tersedia di RootEncoder 2.7.0
(kecepatan kirim dari selisih `bytesSent` per detik + isi antrian `getItemsInCache()/getCacheSize()`; `StreamingStatsReport` tidak ada di 2.7.0).
- Turun hanya bila antrian > 15%: target 90% dari kapasitas yang diestimasi (rata-rata kecepatan kirim saat antrian menumpuk, dikurangi bitrate audio).
- Naik pelan setelah 4 detik sehat; selama estimasi kapasitas masih berlaku tidak melewati 97%-nya, dan tidak pernah melewati slider bitrate Anda.
  Estimasi dilupakan setelah TTL sehingga jaringan yang pulih dicoba lagi.
- Sampel diambil dari ticker 1 Hz service, bukan callback `onNewBitrate`, karena callback itu berhenti total saat link macet.
- Default **mati**; saklar ada di dialog Pengaturan. Mati di tengah siaran mengembalikan bitrate ke nilai slider.

**Data simulasi** (link model 1 detik/tick, diminta 4 Mbps, link tetap 2,5 Mbps; pulih = jatuh ke 0,8 Mbps selama 20 detik lalu normal):
| TTL estimasi kapasitas | tick macet / 1800 | waktu pulih ≥ 90% |
|---|---|---|
| nonaktif | 0 | tidak pernah |
| 60 dtk | 48 | 91 dtk |
| **120 dtk (dipakai di service)** | 26 | 151 dtk |
| 300 dtk (nilai referensi, default kelas) | 10 | 331 dtk |

Saya sempat menambahkan "creep" bertahap agar pemulihan lebih cepat, lalu **membuangnya**: pemulihan hanya membaik ke sekitar 175 dtk, tetapi pada link yang tetap terbatas
tick macet naik dari 8 menjadi 26 dan target melonjak melewati kapasitas video. Pilihan TTL 120 adalah keputusan saya berdasarkan simulasi ideal, bukan jaringan nyata;
ubah `ABR_CEILING_TTL_S` di `StreamingService` bila perlu.

### 3.3 Efek mikrofon (`audio/MicEffects.kt`, `GameMicAudioSource`, dialog Perangkat Audio)
`NoiseSuppressor`, `AcousticEchoCanceler`, `AutomaticGainControl` bawaan platform. Sebelumnya `GameMicAudioSource.create()` menerima flag `echoCanceler/noiseSuppressor` dari RootEncoder
tetapi mengabaikannya. Efek hanya dibuat saat diaktifkan dan dilepas saat dimatikan; berlaku langsung saat live; **hanya trek mikrofon** (audio game tidak diproses).
Default **semua mati**: suara mikrofon Anda tidak berubah sampai Anda menyalakannya. Toggle yang tidak didukung perangkat dinonaktifkan dan diberi label.

Pengaturan disimpan di `StreamPrefs` (SharedPreferences) dan dimuat juga oleh service, sehingga tetap berlaku walau service dihidupkan tanpa UI.

## 4. Tidak diintegrasikan (dan alasannya)
| Fitur referensi | Alasan |
|---|---|
| Peredam noise native (RNNoise, DeepFilterNet), AEC3 WebRTC (`DfNative`, `Aec3Native`, `RnNoiseNative`) | Hanya pembungkus JNI yang ada di hasil dekompilasi; berkas `.so`-nya tidak ada di arsip analisis (hanya manifest dan `apktool.yml`). Diganti efek platform di 3.3, yang lebih lemah dan bergantung perangkat. |
| Akun, login Google, YouTube/Facebook Live, Firebase | Bergantung pada backend dan akun layanan referensi; tidak relevan untuk menyalin. |
| Cast iOS / MirrorActivity, song request, VoiceService, NetKeepAlive | Fitur produk referensi di luar daftar fitur yang Anda minta; bergantung pada layanannya. Project Anda sudah punya chat + TTS sendiri. |
| Pilihan codec H.265, `EncoderEngine` LIGHT/STANDARD, `AudioProfile`, `MicMode` (CLEAN/RAW/AEC3/ORIGINAL), `GamePath` GPU/COMPAT | Tidak diintegrasikan. H.265 lewat RTMP butuh server yang mendukung enhanced-RTMP; dukungan tujuan siaran Anda tidak bisa saya verifikasi dari sini. |
| Sample rate audio / mono-stereo yang bisa dipilih | Saat ini tetap 44,1 kHz stereo di `goLive`; mengubahnya menyentuh `AudioRecord` dan encoder bersama-sama dan butuh uji perangkat. |
| Rekam lokal | Tidak termasuk daftar fitur yang Anda minta. |

## 5. Risiko dan hal yang perlu dicek setelah build
1. **Native libs UVCAndroid dan 16 KB page size.** Project Anda `targetSdk = 36`. Library membawa `.so` untuk 4 ABI dan saya tidak bisa memeriksa alignment-nya. Cek hasil build:
   `zipalign -c -P 16 4 app-debug.apk`. Bila tidak selaras, aplikasi tetap jalan di perangkat 4 KB tetapi bermasalah untuk distribusi Play di perangkat 16 KB.
2. **Dependency transitif.** UVCAndroid membawa `androidx.appcompat:1.6.1`, `legacy-support-v4`, dan `core:1.13.1`. Gradle memakai versi tertinggi yang sudah ada; ukuran APK bertambah sedikit.
3. **Alpha premultiplied di overlay.** Bitmap diunggah premultiplied sementara shader memakai rumus straight-alpha, sehingga tepi semi-transparan bisa sedikit gelap
   (sudah dicatat di komentar `WebLayerSource.kt`). Bukan kotak hitam/putih. Tidak saya ubah karena shader GLSL tidak bisa saya uji di perangkat; perbaikannya satu baris
   (`col.rgb = min(col.rgb / max(col.a, 1e-4), 1.0)` setelah `texture2D(uLayer, …)`) bila Anda ingin.
4. **Cabut webcam saat live**: gambar webcam berhenti diperbarui dan status ditampilkan; siaran tidak dihentikan. Apakah frame terakhir tertahan atau layer kosong belum diuji di perangkat.
5. Pada Android 9+, membuka webcam UVC membutuhkan izin `CAMERA` (sudah diminta aplikasi). Tanpa izin itu sumber menampilkan status, bukan crash.

## 6. File
Baru: `engine/CameraFeed.kt`, `engine/UsbCameraId.kt`, `engine/UsbCameraCatalog.kt`, `engine/UvcCameraLayerSource.kt`, `encoder/AdaptiveBitrateController.kt`,
`audio/MicEffects.kt`, `service/StreamPrefs.kt`, `UsbAttachActivity.kt`, `ui/components/ToggleRow.kt`, `res/xml/usb_camera_filter.xml`,
tes `UsbCameraIdTest.kt` dan `AdaptiveBitrateControllerTest.kt`.
Diubah: `CameraLayerSource`, `StudioEngine`, `GameMicAudioSource`, `StreamingService`, `StudioViewModel`, `MainActivity`, `SettingsDialog`, `AudioDevicesDialog`,
`AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`.
Tidak diubah: `settings.gradle.kts`, `.github/workflows/*`, skema Room (versi database tetap).
