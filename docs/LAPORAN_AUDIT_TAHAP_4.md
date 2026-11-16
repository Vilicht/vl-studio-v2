# Laporan Tahap 4 — Main Canvas, Viewport Source, Layer

## STATUS VERIFIKASI (jujur)
- **Gradle build / APK: BELUM dijalankan.** Lingkungan kerja tidak punya Android SDK/Gradle dan tidak bisa menjangkau Google Maven. Tidak ada APK yang diklaim.
- **Terverifikasi nyata:** `composition/CompositionMath.kt` (modul murni) dikompilasi dengan `kotlinc 2.1.20` dan seluruh pengecekan lulus (fit/cover/stretch, letterbox, zoom, remap canvas, ukuran encode, quick box, urutan layer). `CompositionMathTest.kt` (JUnit) memuat pengecekan yang sama tetapi BELUM dijalankan oleh Gradle.
- Seluruh file Kotlin lolos parse sintaks (tree-sitter, 0 error). Ini BUKAN bukti kompilasi penuh: kesalahan tipe/impor pada kode Android/Compose baru bisa saja muncul di `assembleDebug`.
- Tidak ada uji perangkat, emulator, kamera, MediaProjection, audio, atau server RTMP nyata.

## Bug yang ditemukan dan diperbaiki
1. **Canvas engine tidak mengikuti scene (akar bug orientasi/gepeng).** Canvas GL+encoder ditentukan sekali saat pipeline dimulai dari `isPortrait` scene saat itu. Mengganti orientasi atau pindah scene hanya mengubah kotak preview editor, sehingga frame engine (rasio lama) diregangkan ke kotak berrasio baru, dan output tidak sama dengan editor. Perbaikan: `StudioEngine.reconfigureCanvas()` membangun ulang GL+encoder **tanpa** melepas MediaProjection/VirtualDisplay (token hanya sekali pakai), dengan rollback bila encoder menolak. ViewModel menyinkronkan canvas engine ke scene aktif + preset. Ditolak saat LIVE (encoder yang mengudara tidak boleh berganti resolusi) dengan pesan jelas.
2. **Mengubah canvas mendistorsi source.** Kotak source bersifat ternormalisasi terhadap canvas; rasio canvas berubah → rasio piksel kotak berubah. Perbaikan: `CompositionMath.remapBox` memetakan ulang semua source di ruang piksel (rasio terjaga, pusat terjaga).
3. **Tidak ada mode fit persisten.** Dulu hanya aksi sekali-jalan yang mengubah kotak; kini setiap source punya `fitMode` (Utuh/Penuhi/Regangkan), `zoom`, `panX/panY` yang dirender di shader (`LayerFilterRender`) dengan rumus identik referensi CPU yang diuji. Preview dan encoder membaca tekstur filter yang sama.
4. **Rasio konten tidak diketahui shader.** Engine kini memberi `contentAspect` nyata (kamera setelah koreksi orientasi, ukuran capture layar, dimensi bitmap gambar).
5. **Preset resolusi/fps tidak pernah diterapkan ke pipeline berjalan.** Kini diterapkan lewat `reconfigureCanvas` (saat tidak LIVE).
6. Canvas hanya portrait/landscape; kini juga 1:1 dan kustom (240–3840 px, genap).
7. Urutan layer: `zIndex` bisa kembar; kini ditulis rapat 0..n-1. Ditambah Bring to Front/Forward, Send Backward/Back, drag-and-drop, Rename, Duplicate (menu).
8. Kamera baru dibuat 16:9 **piksel** pada canvas apa pun (dulu 0.28×0.28 ternormalisasi → tinggi di canvas portrait).

## Migrasi data
Room v3 → v4 (`MIGRATION_3_4`): kolom `fitMode` (DEFAULT 2 = Stretch agar source lama tampil persis sama), `zoom` 1, `panX/panY` 0. Source baru memakai Utuh/Contain.

## File diubah / baru
Baru: `composition/CompositionMath.kt`, `ui/components/CanvasPicker.kt`, `test/.../composition/CompositionMathTest.kt`, laporan ini.
Diubah: `Entities.kt`, `StudioDatabase.kt`, `LayerFilterRender.kt`, `StudioEngine.kt`, `StreamingService.kt`, `StudioViewModel.kt`, `MainActivity.kt`, `LeftPanel.kt`, `PropertiesPanel.kt`, `StudioTopBar.kt`, `SourceDialogs.kt`, `StudioCanvas.kt`.

## Fitur diimplementasikan (belum diuji di perangkat)
Main Canvas 9:16/16:9/1:1/kustom per scene; fit Utuh/Penuhi/Regangkan per source; zoom + pan; Fit/Fill Canvas, Full Width, Full Height, Center, Reset Crop, Reset Transform; layer drag-and-drop + perintah urutan + rename + duplikat; canvas engine mengikuti scene; penerapan resolusi/fps saat tidak LIVE.

## Belum selesai / batasan
- Browser Overlay (WebView via `AndroidViewFilterRender`) tidak mendukung fit/crop/rotasi/mirror; Teks selalu mengisi kotaknya.
- Placeholder editor saat engine belum jalan (`PlaceholderItem`) belum meniru fit/zoom; preview nyata (SurfaceView dari engine) sudah.
- Pindah scene / ganti canvas dengan rasio berbeda **saat LIVE ditolak** (batas encoder RTMP).
- Audio mixer, RTMP, Google Login: tidak diubah tahap ini (Google Login tetap ditunda sesuai keputusan sebelumnya). Audio internal hanya Android 10+ dan tergantung izin capture tiap aplikasi.
- Pindah canvas saat kamera berjalan membuka ulang kamera sebentar (pipeline dibangun ulang).
- Skenario uji 1–18 dari prompt BELUM dijalankan; wajib diuji di perangkat setelah `assembleDebug` lolos.

## Langkah berikutnya
1. Jalankan GitHub Actions `Build Android APK`; perbaiki error kompilasi bila ada.
2. Uji di perangkat: ganti canvas 9:16↔16:9↔1:1, kamera 4:3 tidak gepeng, game landscape di canvas portrait (Utuh/Penuhi), drag layer, pindah scene, tutup-buka aplikasi.

## Tambahan (setelah uji APK pertama di perangkat)
Temuan pengguna: kamera masih gepeng; overlay hanya gambar/teks/URL; green screen tidak ada.
- **Kamera gepeng:** migrasi v3→v4 memberi source lama mode Regangkan (agar tampilan lama tidak berubah), sehingga kotak yang sudah terdistorsi tetap meregangkan konten. Perbaikan: `MIGRATION_4_5` mengembalikan source Layar/Kamera/Gambar ke Utuh (Contain). Konten kini tampil dengan rasio aslinya; "Reset Transform" merapikan bentuk kotak.
- **Chroma key:** field database sudah ada sejak awal tetapi TIDAK dipakai kode mana pun (klaim "GPU Chroma Key" di dialog sebelumnya kosong). Kini diimplementasikan di shader `LayerFilterRender` (ruang Cb/Cr, ambang + kehalusan), berlaku untuk Kamera, Video, Gambar, Layar; kontrol di Properties Panel.
- **Video overlay:** tipe source baru `VIDEO` (`engine/VideoLayerSource.kt`, MediaPlayer → SurfaceTexture), loop, dapat di-crop, fit, zoom, chroma key. File disalin ke penyimpanan app (maks 500 MB).
- Batasan: suara video dibisukan dan TIDAK dicampur ke audio siaran (mixer belum mendukung sumber file). Belum diuji di perangkat; kompilasi belum dipastikan.

## Tahap 5 — Preview Live, chroma lengkap, kontrol video
STATUS VERIFIKASI: kode ditulis dan lolos parse sintaks; BELUM dikompilasi Gradle dan BELUM diuji di perangkat.

Dikerjakan:
- **PREVIEW LIVE** (tombol di toolbar, `PreviewLiveDialog.kt`): layar penuh, hanya hasil akhir tanpa bounding box/handle, rasio = rasio OUTPUT (bukan rasio layar). Tidak memanggil service/RTMP; hanya memasang satu Surface lokal ke compositor yang sama dengan encoder. Pilihan output 9:16 1080×1920, 9:16 720×1280, 16:9 1920×1080, kustom. Memilih output mengubah canvas scene (source dipetakan ulang, rasio terjaga); ditolak saat LIVE. Engine kini mendukung beberapa preview lokal sekaligus (editor + Preview Live).
- **Chroma key**: ditambah spill reduction (shader) dan warna kunci HEX selain 3 preset. Migrasi DB 5→6 (`chromaSpill`, `videoPaused`).
- **Video**: kontrol Putar/Jeda, Ulang, Stop (terhubung ke MediaPlayer).

Catatan jujur:
- Aplikasi ini TIDAK memakai CameraX/PreviewView. Kamera memakai Camera2 → SurfaceTexture → compositor GL RootEncoder. "Rebuild ke CameraX" tidak dilakukan; penyebab gepeng yang ditemukan adalah mode Regangkan pada kotak berrasio salah (sudah dimigrasi ke Utuh). Jika masih gepeng di perangkat, perlu log/tangkapan layar kamera (depan/belakang, orientasi).
- Renderer SUDAH satu: compositor GL engine memberi frame ke preview editor, Preview Live, dan encoder. Handle/bounding box editor adalah overlay Compose di luar GL, jadi tidak ikut render.
- Drag, resize handle, rotate handle, kunci rasio, pemilihan lewat sentuhan, urutan layer, lock/hide/duplicate/delete sudah ada dari tahap sebelumnya (dibaca dari kode, belum diuji di perangkat).
- BELUM dikerjakan: pass responsif tablet (tata letak panel), eyedropper warna kunci, audio video ke mixer, transformasi crop/rotasi/mirror untuk Browser Overlay, peringatan source tertutup source lain, audit memory leak mendalam, video dari URL.

## Tahap 6 — sisa daftar ("kerjakan semua")
STATUS VERIFIKASI: ditulis dan lolos parse sintaks; modul matematika murni (termasuk deteksi source tertutup) lulus uji kotlinc. BELUM dikompilasi Gradle, BELUM diuji di perangkat.

- **Browser Overlay lewat shader yang sama** (`WebLayerSource.kt`): WebView digambar berkala (24 fps) ke SurfaceTexture layer, sehingga crop, rotasi, mirror, opacity, dan chroma key berlaku seperti kamera/video. `AndroidViewFilterRender` tidak dipakai lagi. RISIKO: ini penulisan ulang jalur yang sebelumnya berfungsi; kinerja/kualitas alpha (premultiplied) perlu dicek di perangkat.
- **Video dari URL** (https/http, stream langsung; ukuran asli dilaporkan MediaPlayer).
- **Suara video ikut siaran (eksperimental)**: saklar per source (DB v7). Memakai USAGE_MEDIA + kebijakan capture terbuka agar tertangkap AudioPlaybackCapture mixer. Hanya bekerja bila izin capture layar aktif; terdengar juga di speaker; bisa gema ke mikrofon. Belum terbukti di perangkat.
- **Pipet warna kunci** di Preview Live (PixelCopy, Android 8+): ketuk latar di hasil render; chroma key otomatis aktif.
- **Peringatan source tertutup**: Properties Panel memberi tahu bila source terpilih tertutup seluruhnya oleh source opak di depannya (konservatif; tanpa rotasi/crop/chroma).
- **Tablet**: lebar panel proporsional (kiri 288–340 dp, kanan 260–360 dp); tablet portrait menampilkan panel Sources dan Properties berdampingan (tanpa tab); Preview Live memakai tombol ≥44 dp.
- **Audit lifecycle**: referensi service statis dibersihkan di onDestroy; ImageReader, bitmap, MediaPlayer, WebLayerSource, kamera dilepas sebelum SurfaceTexture. Tidak ada kebocoran jelas yang ditemukan lewat pembacaan kode; TIDAK ada profil memori/LeakCanary yang dijalankan.

Masih belum / batasan: tidak ada build Gradle atau uji perangkat dari lingkungan ini (APK dibangun lewat GitHub Actions); kamera masih Camera2 (bukan CameraX); audio file video tidak dicampur lewat mixer terpisah (hanya jalur capture di atas).
