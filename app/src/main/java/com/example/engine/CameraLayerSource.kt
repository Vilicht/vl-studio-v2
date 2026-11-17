package com.example.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat

/** Info satu kamera yang dilaporkan Android (termasuk USB/eksternal kalau perangkat mendukung). */
data class CameraInfo(
    val id: String,
    val label: String,
    /** CameraCharacteristics.LENS_FACING_* */
    val facing: Int,
    val sensorOrientation: Int
)

/**
 * Satu kamera -> SurfaceTexture milik layer GL. Memakai Camera2 langsung supaya:
 *  - bisa memilih kamera berdasarkan ID (depan, belakang, atau eksternal/USB),
 *  - tidak berasumsi kamera mulai dari belakang atau depan,
 *  - lifecycle jelas: start() membuka, stop() menutup SEMUA resource (device, session, thread).
 *
 * stop() harus dipanggil SEBELUM SurfaceTexture-nya dilepas oleh GL.
 */
class CameraLayerSource(
    private val context: Context,
    private val onStatus: (String) -> Unit
) : CameraFeed {
    private val tag = "VilichtCamera"
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val main = Handler(Looper.getMainLooper())

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var texture: SurfaceTexture? = null

    private var wantedId: String? = null
    private var targetFps = 30
    private var reconnectTries = 0
    @Volatile private var active = false

    /** ID kamera yang sedang dipakai (null kalau belum terbuka). */
    @Volatile override var openedId: String? = null
        private set

    /** Orientasi sensor kamera yang sedang dipakai; dipakai engine untuk menghitung rotasi layer. */
    @Volatile override var sensorOrientation: Int = 0
        private set

    @Volatile override var isFront: Boolean = false
        private set

    /** Ukuran buffer kamera yang sebenarnya dipilih (sebelum koreksi orientasi). 0 = belum terbuka. */
    @Volatile override var bufferWidth: Int = 0
        private set
    @Volatile override var bufferHeight: Int = 0
        private set

    /** Sensor kamera internal: gambar bergantung pada orientasi perangkat, engine mengoreksinya dengan rotasi layar. */
    override val followsDisplayRotation: Boolean get() = true

    /** Camera2 mengetahui ukuran buffer secara sinkron saat start(); tidak pernah dipanggil. */
    override var onFormatChanged: (() -> Unit)? = null

    /** Batas piksel resolusi kamera (lebar x tinggi). Default 1280x720. */
    private var maxPixels = 1280 * 720

    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            // Kamera yang kita tunggu (mis. USB baru dicolok / dilepas aplikasi lain) kembali tersedia
            if (active && device == null && cameraId == (wantedId ?: pickDefaultId(context))) {
                main.post { reopen() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(st: SurfaceTexture, cameraId: String?, fps: Int, maxPixels: Int) {
        stop()
        this.maxPixels = maxPixels
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onStatus("Izin kamera belum diberikan")
            return
        }
        texture = st
        wantedId = cameraId
        targetFps = fps
        reconnectTries = 0
        active = true
        val t = HandlerThread("VilichtCam").also { it.start() }
        thread = t
        handler = Handler(t.looper)
        try { manager.registerAvailabilityCallback(availability, handler) } catch (_: Exception) {}
        tryOpen()
    }

    @SuppressLint("MissingPermission")
    private fun tryOpen() {
        val h = handler ?: return
        val st = texture ?: return
        val id = wantedId?.takeIf { it in manager.cameraIdList } ?: pickDefaultId(context)
        if (id == null) {
            onStatus("Tidak ada kamera yang terdeteksi")
            scheduleReconnect()
            return
        }
        try {
            val chars = manager.getCameraCharacteristics(id)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            isFront = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val size = chooseSize(chars)
            bufferWidth = size.width
            bufferHeight = size.height
            st.setDefaultBufferSize(size.width, size.height)
            val s = Surface(st)
            surface = s
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!active) { camera.close(); return }
                    device = camera
                    openedId = id
                    createSession(camera, s, chars)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(tag, "Kamera $id terputus")
                    camera.close()
                    if (device === camera) device = null
                    openedId = null
                    closeSession()
                    if (active) { onStatus("Kamera terputus, mencoba menyambung ulang…"); scheduleReconnect() }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(tag, "Kamera $id error $error")
                    camera.close()
                    if (device === camera) device = null
                    openedId = null
                    closeSession()
                    if (active) { onStatus("Kamera error ($error)"); scheduleReconnect() }
                }
            }, h)
        } catch (e: CameraAccessException) {
            Log.e(tag, "openCamera gagal: ${e.message}")
            onStatus("Kamera tidak bisa dibuka: ${e.message}")
            scheduleReconnect()
        } catch (e: SecurityException) {
            onStatus("Izin kamera ditolak")
        } catch (e: Exception) {
            Log.e(tag, "open gagal", e)
            onStatus("Kamera gagal: ${e.localizedMessage}")
        }
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice, s: Surface, chars: CameraCharacteristics) {
        val h = handler ?: return
        try {
            camera.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cs: CameraCaptureSession) {
                    if (!active || device !== camera) { cs.close(); return }
                    session = cs
                    try {
                        val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        b.addTarget(s)
                        val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                        if (modes?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) == true) {
                            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                        fpsRange(chars)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        // Android 11+: mode AUTO bisa membuat sistem memutar + memotong gambar sendiri (terutama di tablet),
                        // sehingga hitungan orientasi kita meleset 90°. Orientasi dihitung di shader, jadi matikan.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val rc = chars.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                            if (rc != null && rc.contains(CameraMetadata.SCALER_ROTATE_AND_CROP_NONE)) {
                                b.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CameraMetadata.SCALER_ROTATE_AND_CROP_NONE)
                            }
                        }
                        cs.setRepeatingRequest(b.build(), null, h)
                        reconnectTries = 0
                        onStatus("")
                    } catch (e: Exception) {
                        Log.e(tag, "setRepeatingRequest gagal", e)
                        onStatus("Kamera gagal menampilkan gambar: ${e.localizedMessage}")
                    }
                }

                override fun onConfigureFailed(cs: CameraCaptureSession) {
                    Log.e(tag, "createCaptureSession gagal")
                    cs.close()
                    onStatus("Kamera gagal dikonfigurasi")
                }
            }, h)
        } catch (e: Exception) {
            Log.e(tag, "createCaptureSession exception", e)
            onStatus("Kamera gagal: ${e.localizedMessage}")
        }
    }

    private fun fpsRange(chars: CameraCharacteristics): android.util.Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.filter { it.upper <= targetFps && it.upper >= 15 }
            .maxWithOrNull(compareBy({ it.upper }, { it.lower }))
    }

    /** Ukuran terbesar yang ≤ batas piksel (default 1280x720, ringan untuk encoder), diutamakan rasio 16:9. */
    private fun chooseSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(640, 480)
        val limit = maxPixels
        val fit = sizes.filter { it.width * it.height <= limit }
        if (fit.isEmpty()) return sizes.minByOrNull { it.width * it.height }!!
        // Utamakan 4:3: memakai seluruh sensor (sudut pandang lebih lebar, tidak terpotong atas-bawah seperti 16:9).
        val standard = fit.filter { Math.abs(it.width * 3 - it.height * 4) <= 12 }
        if (standard.isNotEmpty()) return standard.maxByOrNull { it.width * it.height }!!
        val wide = fit.filter { Math.abs(it.width * 9 - it.height * 16) <= 16 }
        return (wide.ifEmpty { fit }).maxByOrNull { it.width * it.height }!!
    }

    private fun scheduleReconnect() {
        if (!active) return
        if (reconnectTries >= 15) { onStatus("Kamera tidak kembali. Cek koneksi kamera."); return }
        reconnectTries++
        handler?.postDelayed({ if (active && device == null) tryOpen() }, 2000)
    }

    private fun reopen() {
        if (!active || device != null) return
        handler?.post { if (active && device == null) tryOpen() }
    }

    private fun closeSession() {
        try { session?.close() } catch (_: Exception) {}
        session = null
    }

    /** Tutup semua. Aman dipanggil berkali-kali. */
    override fun stop() {
        active = false
        try { manager.unregisterAvailabilityCallback(availability) } catch (_: Exception) {}
        handler?.removeCallbacksAndMessages(null)
        closeSession()
        try { device?.close() } catch (_: Exception) {}
        device = null
        openedId = null
        try { surface?.release() } catch (_: Exception) {}
        surface = null
        texture = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    companion object {
        /**
         * Semua kamera yang bisa dipilih: yang dilaporkan Camera2 (depan, belakang, eksternal) DITAMBAH webcam USB
         * (UVC) yang tercolok, yang dibuka lewat [UvcCameraLayerSource] bila perangkat tidak mengekspos USB ke Camera2.
         */
        fun listCameras(context: Context): List<CameraInfo> = listCamera2(context) + UsbCameraCatalog.list(context)

        /** Hanya kamera yang dilaporkan Camera2, berlabel jelas. */
        fun listCamera2(context: Context): List<CameraInfo> {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return try {
                var back = 0; var front = 0; var ext = 0
                mgr.cameraIdList.mapNotNull { id ->
                    val c = mgr.getCameraCharacteristics(id)
                    // Lewati kamera logis tanpa kemampuan output warna normal (mis. depth-only)
                    val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    val backwardCompatible = caps?.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE
                    ) ?: true
                    if (!backwardCompatible) return@mapNotNull null
                    val facing = c.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                    val label = when (facing) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "Kamera depan" + if (++front > 1) " $front" else ""
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "Kamera eksternal (USB)" + if (++ext > 1) " $ext" else ""
                        else -> "Kamera belakang" + if (++back > 1) " $back" else ""
                    }
                    CameraInfo(id, "$label  [id $id]", facing, c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                }
            } catch (e: Exception) {
                Log.e("VilichtCamera", "listCameras gagal: ${e.message}")
                emptyList()
            }
        }

        /**
         * Default: kamera depan kalau ada, kalau tidak kamera pertama yang ada. Hanya Camera2: ID default dipakai
         * [CameraLayerSource], yang tidak bisa membuka webcam USB; webcam USB dipilih eksplisit lewat ID `uvc:...`.
         */
        fun pickDefaultId(context: Context): String? {
            val all = listCamera2(context)
            return (all.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
                ?: all.firstOrNull())?.id
        }
    }
}
