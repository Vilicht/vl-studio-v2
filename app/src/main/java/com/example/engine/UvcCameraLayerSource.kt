package com.example.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.utils.UVCUtils

/**
 * Webcam USB (UVC) -> SurfaceTexture milik layer GL, lewat library UVCAndroid (com.herohan.uvcapp).
 *
 * Dipakai untuk perangkat yang TIDAK mengekspos kamera USB ke Camera2. Alur resmi library:
 *   onAttach -> selectDevice (minta izin USB) -> onDeviceOpen -> openCamera -> onCameraOpen
 *   -> startPreview + addSurface(SurfaceTexture).
 * Webcam yang sudah tercolok sebelum aplikasi dibuka juga memicu onAttach (library memindai perangkat),
 * dan webcam yang dicabut lalu dicolok ulang tersambung kembali otomatis.
 *
 * Konkurensi: callback library tiba di thread utama; start()/stop() boleh dipanggil dari thread mana pun.
 * Semua perpindahan sesi dijaga [lock]; callback milik sesi lama diabaikan (cek [isCurrent]).
 *
 * stop() harus dipanggil SEBELUM SurfaceTexture-nya dilepas oleh GL.
 */
class UvcCameraLayerSource(
    context: Context,
    private val onStatus: (String) -> Unit
) : CameraFeed {
    private val tag = "VilichtUvc"
    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** Satu kali start(): semua state yang harus dibuang bersama saat stop(). */
    private class Session(
        val texture: SurfaceTexture,
        val wanted: UsbCameraId,
        val fps: Int,
        val maxPixels: Int
    ) {
        @Volatile var alive = true
        var helper: CameraHelper? = null
        var device: UsbDevice? = null
        var surface: Surface? = null
    }

    private var session: Session? = null

    @Volatile override var openedId: String? = null
        private set
    @Volatile override var bufferWidth: Int = 0
        private set
    @Volatile override var bufferHeight: Int = 0
        private set

    override val sensorOrientation: Int get() = 0
    override val isFront: Boolean get() = false
    override val followsDisplayRotation: Boolean get() = false

    override var onFormatChanged: (() -> Unit)? = null

    override fun start(st: SurfaceTexture, cameraId: String?, fps: Int, maxPixels: Int) {
        stop()
        val wanted = UsbCameraId.parse(cameraId)
        if (wanted == null) {
            onStatus("ID kamera USB tidak valid")
            return
        }
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onStatus("Izin kamera belum diberikan (juga dibutuhkan untuk kamera USB)")
            return
        }
        val s = Session(st, wanted, fps, maxPixels)
        synchronized(lock) { session = s }
        onStatus("Menunggu kamera USB…")
        // Beri waktu helper sebelumnya (restart karena ganti resolusi/fps) menutup perangkat USB-nya.
        main.postDelayed({ openHelper(s) }, SETTLE_MS)
    }

    private fun isCurrent(s: Session): Boolean = synchronized(lock) { s.alive && session === s }

    private fun openHelper(s: Session) {
        if (!isCurrent(s)) return
        try {
            // CameraHelper memakai Application dari UVCUtils; inisialisasi eksplisit, tidak mengandalkan refleksi.
            UVCUtils.init(appContext)
            val helper = CameraHelper()
            s.helper = helper
            helper.setStateCallback(Listener(s))
        } catch (e: Exception) {
            Log.e(tag, "Gagal menyiapkan kamera USB", e)
            onStatus("Kamera USB gagal dimulai: ${e.localizedMessage}")
        } catch (e: LinkageError) {
            // Library native tidak termuat (mis. ABI tidak didukung): jangan matikan aplikasi.
            Log.e(tag, "Library native UVC tidak tersedia", e)
            onStatus("Kamera USB tidak didukung di perangkat ini")
        }
    }

    private inner class Listener(private val s: Session) : ICameraHelper.StateCallback {

        override fun onAttach(device: UsbDevice) {
            if (!isCurrent(s) || s.device != null) return
            // Library melaporkan SEMUA webcam yang tercolok; ambil hanya yang dipilih pengguna.
            if (UsbCameraCatalog.idOf(appContext, device) != s.wanted) return
            s.device = device
            onStatus("Menghubungkan kamera USB…")
            s.helper?.selectDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            if (!isCurrent(s) || device != s.device) return
            s.helper?.openCamera()
        }

        override fun onCameraOpen(device: UsbDevice) {
            if (!isCurrent(s) || device != s.device) return
            val helper = s.helper ?: return
            val chosen = applyBestMode(helper, s)
            helper.startPreview()
            publishFormat(helper, chosen)

            val surface = Surface(s.texture)
            val old = s.surface
            s.surface = surface
            old?.let { it.release() }
            // Ukuran buffer harus ditetapkan SEBELUM produsen (renderer UVC) tersambung ke surface.
            if (bufferWidth > 0 && bufferHeight > 0) s.texture.setDefaultBufferSize(bufferWidth, bufferHeight)
            helper.addSurface(surface, false)
            openedId = s.wanted.format()
            onStatus("")
            // setPreviewSize/startPreview berjalan async di thread library: verifikasi ukuran sebenarnya.
            main.postDelayed({ if (isCurrent(s)) publishFormat(helper, null) }, VERIFY_MS)
        }

        override fun onCameraClose(device: UsbDevice) {
            if (!isCurrent(s) || device != s.device) return
            detachSurface(s)
            openedId = null
        }

        override fun onDeviceClose(device: UsbDevice) = Unit

        override fun onDetach(device: UsbDevice) {
            if (!isCurrent(s) || device != s.device) return
            detachSurface(s)
            s.device = null
            openedId = null
            onStatus("Kamera USB dicabut. Colok kembali untuk melanjutkan.")
        }

        override fun onCancel(device: UsbDevice) {
            if (!isCurrent(s) || device != s.device) return
            s.device = null
            openedId = null
            onStatus("Izin USB ditolak. Cabut dan colok ulang kamera, lalu pilih Izinkan.")
        }

        override fun onError(device: UsbDevice, e: CameraException) {
            if (!isCurrent(s)) return
            Log.e(tag, "Kamera USB error code=${e.code}: ${e.message}")
            // Library meminta cabut-colok ulang untuk kedua jenis error ini.
            s.device = null
            openedId = null
            onStatus(
                if (e.code == CameraException.CAMERA_OPEN_ERROR_BUSY)
                    "Kamera USB sedang dipakai aplikasi lain. Tutup aplikasi itu, cabut dan colok ulang kamera."
                else
                    "Kamera USB bermasalah. Cabut dan colok ulang kamera."
            )
        }
    }

    /**
     * Pilih mode terbaik dari yang diiklankan kamera dan terapkan SEBELUM startPreview().
     * Mengembalikan mode yang diterapkan, atau null bila memakai bawaan library (640x480 MJPEG).
     */
    private fun applyBestMode(helper: CameraHelper, s: Session): UvcSelection? {
        val sizes: List<Size> = try { helper.supportedSizeList.orEmpty() } catch (e: Exception) { emptyList() }
        val selection = UvcModeSelector.select(sizes.map { it.toMode() }, s.maxPixels, s.fps) ?: return null
        val match = sizes.firstOrNull {
            it.type == selection.mode.frameType && it.width == selection.mode.width && it.height == selection.mode.height
        } ?: return null
        return try {
            // Salin lalu isi fps yang VALID untuk mode ini; fps di luar daftar membuat setPreviewSize menolak.
            val copy = match.clone()
            if (selection.fps > 0) copy.fps = selection.fps
            helper.setPreviewSize(copy)
            selection
        } catch (e: Exception) {
            Log.w(tag, "setPreviewSize ditolak, memakai mode bawaan: ${e.message}")
            null
        }
    }

    /**
     * Tetapkan ukuran buffer. [expected] dipakai langsung setelah setPreviewSize (yang berjalan async,
     * sehingga getPreviewSize() belum tentu terbarui); [expected] null = baca ukuran nyata dari library.
     */
    private fun publishFormat(helper: CameraHelper, expected: UvcSelection?) {
        val w: Int
        val h: Int
        if (expected != null) {
            w = expected.mode.width
            h = expected.mode.height
        } else {
            val real = try { helper.previewSize } catch (e: Exception) { null }
            w = real?.width ?: 0
            h = real?.height ?: 0
        }
        if (w <= 0 || h <= 0) return
        if (w == bufferWidth && h == bufferHeight) return
        bufferWidth = w
        bufferHeight = h
        val current = synchronized(lock) { session }
        try { current?.texture?.setDefaultBufferSize(w, h) } catch (e: Exception) { Log.w(tag, "setDefaultBufferSize: ${e.message}") }
        onFormatChanged?.invoke()
    }

    private fun detachSurface(s: Session) {
        val surface = s.surface ?: return
        s.surface = null
        try { s.helper?.removeSurface(surface) } catch (e: Exception) { Log.w(tag, "removeSurface: ${e.message}") }
        // Surface dilepas belakangan: renderer UVC masih bisa menggambar sampai removeSurface selesai di thread-nya.
        main.postDelayed({ try { surface.release() } catch (_: Exception) {} }, RELEASE_SURFACE_MS)
    }

    override fun stop() {
        val s = synchronized(lock) {
            val current = session
            session = null
            current?.alive = false
            current
        }
        openedId = null
        bufferWidth = 0
        bufferHeight = 0
        if (s == null) return
        val helper = s.helper
        if (helper != null) {
            // Urutan penting: lepas callback, lepas surface, baru release (semuanya antre di thread library).
            try { helper.setStateCallback(null) } catch (e: Exception) { Log.w(tag, "setStateCallback(null): ${e.message}") }
            detachSurface(s)
            try { helper.release() } catch (e: Exception) { Log.w(tag, "release: ${e.message}") }
            s.helper = null
        } else {
            s.surface?.let { try { it.release() } catch (_: Exception) {} }
            s.surface = null
        }
        s.device = null
    }

    private fun Size.toMode(): UvcMode = UvcMode(
        frameType = type,
        width = width,
        height = height,
        defaultFps = fps,
        fpsList = fpsList.orEmpty()
    )

    private companion object {
        const val SETTLE_MS = 300L
        const val VERIFY_MS = 600L
        const val RELEASE_SURFACE_MS = 800L
    }
}
