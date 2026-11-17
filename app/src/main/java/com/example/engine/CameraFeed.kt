package com.example.engine

import android.graphics.SurfaceTexture

/**
 * Kontrak sumber kamera yang menulis ke SurfaceTexture milik layer GL.
 *
 * Dua implementasi:
 *  - [CameraLayerSource]    : kamera bawaan / eksternal yang diekspos Camera2 (depan, belakang, EXTERNAL).
 *  - [UvcCameraLayerSource] : webcam USB UVC lewat library UVCAndroid, untuk perangkat yang TIDAK
 *                             mengekspos kamera USB ke Camera2.
 *
 * Engine hanya bergantung pada kontrak ini, sehingga sumber baru tidak menyentuh pipeline GL/encoder.
 */
interface CameraFeed {
    /** ID kamera yang sedang terbuka (null kalau belum/tidak terbuka). */
    val openedId: String?

    /** Orientasi sensor (derajat). Webcam USB sudah tegak, nilainya 0. */
    val sensorOrientation: Int

    /** true = kamera depan (dipakai engine untuk arah koreksi rotasi layar). */
    val isFront: Boolean

    /** Ukuran buffer kamera yang sebenarnya (sebelum koreksi orientasi). 0 = belum diketahui. */
    val bufferWidth: Int
    val bufferHeight: Int

    /**
     * true  = gambar tergantung orientasi perangkat (sensor kamera internal): engine mengoreksi dengan rotasi layar.
     * false = gambar sudah tegak apa pun posisi perangkat (webcam USB): engine TIDAK memutarnya mengikuti layar.
     */
    val followsDisplayRotation: Boolean

    /**
     * Dipanggil (dari thread mana pun) saat [bufferWidth]/[bufferHeight] berubah setelah start(),
     * misalnya webcam USB yang baru selesai negosiasi mode. Camera2 mengetahui ukuran secara sinkron
     * dan tidak perlu memanggilnya.
     */
    var onFormatChanged: (() -> Unit)?

    /** Mulai menulis ke [st]. Memanggil start() lagi sama dengan stop() lalu start(). */
    fun start(st: SurfaceTexture, cameraId: String?, fps: Int, maxPixels: Int = 1280 * 720)

    /**
     * Tutup SEMUA resource. Aman dipanggil berkali-kali.
     * Wajib dipanggil SEBELUM SurfaceTexture-nya dilepas oleh GL.
     */
    fun stop()
}
