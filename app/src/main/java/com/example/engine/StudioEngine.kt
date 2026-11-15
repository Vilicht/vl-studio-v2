package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.example.audio.GameMicAudioSource
import com.example.composition.FitMode
import com.example.data.model.SourceEntity
import com.example.data.model.SourceType
import com.example.gl.LayerFilterRender
import com.example.gl.LayerParams
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.rtmp.RtmpStream
import com.pedro.library.view.GlStreamInterface
import com.pedro.library.view.TakePhotoCallback
import com.pedro.library.view.preview.MultiPreviewConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Posisi layer relatif terhadap canvas, 0..1 (x,y = pojok kiri atas). */
data class LayerRect(val x: Float, val y: Float, val w: Float, val h: Float)

/** Penghitung nyata dari klien RTMP (bukan angka konfigurasi). */
data class EngineCounters(val sentVideoFrames: Long, val droppedVideoFrames: Long, val bytesSent: Long)

/**
 * Mesin siaran: satu MediaProjection -> satu VirtualDisplay -> layer "game" di compositor GL,
 * ditambah layer kamera, gambar, teks, dan Browser Source (WebView) di atasnya dengan urutan z
 * yang diatur dari editor. Semua layer ini dikomposisikan di GL SEBELUM encoder, jadi yang dilihat
 * penonton RTMP sama dengan yang ada di editor.
 *
 * Alur: startPreview() -> applyScene() (setiap source berubah) -> goLive() -> stopLive() -> release().
 * Semua fungsi publik dipanggil dari Main Thread (service).
 *
 * Mode editor: saat Studio terbuka, capture layar game dijeda (VirtualDisplay.setSurface(null)) sehingga
 * layer game membeku di frame terakhir. Ini mencegah Studio ikut ter-capture (efek cermin tak berujung)
 * dan TIDAK menghentikan encoder, RTMP, kamera, ataupun overlay.
 */
class StudioEngine(
    private val context: Context,
    private val connectChecker: ConnectChecker
) {
    private val tag = "VilichtEngine"
    private val main = Handler(Looper.getMainLooper())

    private var stream: RtmpStream? = null
    private var projection: MediaProjection? = null
    private var gameSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var vdThread: HandlerThread? = null
    private var previewReader: ImageReader? = null
    private var previewDrainThread: HandlerThread? = null
    /** Semua preview lokal (kanvas editor + Preview Live) yang membaca compositor yang SAMA dengan encoder. */
    private val previewSurfaces = LinkedHashMap<Surface, Pair<Int, Int>>()

    private val webHost = WebOverlayHost(context)
    private val decoder: ExecutorService = Executors.newSingleThreadExecutor()

    private class Layer(val key: String, val sig: String, val filter: BaseFilterRender) {
        var src: SourceEntity? = null
        var camera: CameraLayerSource? = null
        /** Kombinasi (kamera|resolusi|fps) yang sedang berjalan; beda dari yang diminta = kamera dibuka ulang. */
        var camKey: String = ""
        /** Rasio konten nyata yang dilaporkan sumbernya (kamera setelah koreksi orientasi, gambar). 0 = belum ada. */
        @Volatile var liveAspect: Float = 0f
        /** Putaran kamera (kelipatan 90°) yang terakhir dipakai; berubah = rasio konten perlu dilaporkan ulang. */
        var lastQuarter: Int = -1
        var web: WebOverlayHost.Handle? = null
        var video: VideoLayerSource? = null
        // Browser Source: SurfaceTexture layer + WebView harus keduanya siap sebelum menggambar
        var webSt: SurfaceTexture? = null
        var webView: android.webkit.WebView? = null
        var webLayer: WebLayerSource? = null
        @Volatile var dead = false
    }

    private data class Desired(val key: String, val sig: String, val src: SourceEntity)

    private val layers = mutableListOf<Layer>()
    private var desired: List<Desired> = emptyList()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(tag, "MediaProjection stopped")
            main.post { onProjectionStopped?.invoke() }
        }
    }

    var onProjectionStopped: (() -> Unit)? = null
    /** Error fatal (encoder/capture gagal). Service mengubah status menjadi ERROR. */
    var onError: ((String) -> Unit)? = null
    /** Pemberitahuan non-fatal (kamera terputus, izin overlay kurang, dsb). Siaran tetap jalan. */
    var onNotice: ((String) -> Unit)? = null
    /** Status kamera: string kosong = kamera berjalan normal; selain itu = pesan masalah (izin, terputus, dll). */
    var onCameraStatus: ((String) -> Unit)? = null
    /** Rasio aspek (lebar/tinggi) layar yang sedang di-capture. */
    var onCaptureAspect: ((Float) -> Unit)? = null
    /** Rasio asli konten sebuah source (setelah koreksi orientasi), mis. kamera. Dipakai Reset Ratio. */
    var onContentAspect: ((String, Float) -> Unit)? = null

    /** Batas piksel resolusi kamera dan fps target. Berlaku pada pembukaan kamera berikutnya. */
    var cameraMaxPixels: Int = 1280 * 720
    var cameraFps: Int = 30

    private var audioSource: GameMicAudioSource? = null
    val hasProjection: Boolean get() = projection != null

    @Volatile var currentFps: Int = 0
        private set

    var canvasW = 1280
        private set
    var canvasH = 720
        private set
    private var fps = 30
    private var capW = 0
    private var capH = 0
    private var gameCaptureActive = true

    val isPreviewing: Boolean get() = stream?.isOnPreview == true
    /** true bila compositor + encoder siap (dipakai untuk memastikan pemulihan setelah reconfigureCanvas gagal). */
    val isPipelineReady: Boolean get() = stream?.isOnPreview == true
    val isLive: Boolean get() = stream?.isStreaming == true

    /**
     * Kompatibilitas: width/height = ukuran landscape (mis. 1280x720); portrait dihitung dari flag.
     * Untuk canvas bebas (square/custom) pakai [startPreviewCanvas].
     */
    fun startPreview(
        mp: MediaProjection?, portrait: Boolean, width: Int, height: Int, fps: Int, bitrateBps: Int
    ): Boolean {
        val long = maxOf(width, height)
        val short = minOf(width, height)
        return startPreviewCanvas(mp, if (portrait) short else long, if (portrait) long else short, fps, bitrateBps)
    }

    /** Canvas apa pun (landscape, portrait, 1:1, kustom). [cw]x[ch] = ukuran canvas = ukuran frame keluaran. */
    fun startPreviewCanvas(mp: MediaProjection?, cw: Int, ch: Int, fps: Int, bitrateBps: Int): Boolean {
        release()
        projection = mp
        // Android 14+: callback harus terdaftar sebelum createVirtualDisplay.
        // mp == null: mode editor kamera-saja (preview tanpa capture layar); token menyusul lewat attachProjection().
        mp?.registerCallback(projectionCallback, main)
        return buildPipeline(cw, ch, fps, bitrateBps)
    }

    /**
     * Ganti ukuran/rasio canvas TANPA kehilangan izin capture: GL + encoder dibangun ulang, sedangkan
     * MediaProjection dan VirtualDisplay (token hanya boleh dipakai sekali) dipertahankan.
     * Ditolak saat siaran berjalan: encoder RTMP yang sedang mengudara tidak boleh berganti resolusi/rasio.
     */
    fun reconfigureCanvas(cw: Int, ch: Int, fps: Int, bitrateBps: Int): Boolean {
        if (stream == null) return false
        if (isLive) return false
        if (cw == canvasW && ch == canvasH && fps == this.fps) return true
        val keep = desired.map { it.src }
        val studios = previewSurfaces.toMap()
        val oldW = canvasW
        val oldH = canvasH
        val oldFps = this.fps
        teardownStream()
        var ok = buildPipeline(cw, ch, fps, bitrateBps)
        var applied = ok
        if (!ok) {
            // Encoder menolak konfigurasi baru: pulihkan konfigurasi sebelumnya agar sesi tidak mati
            Log.w(tag, "reconfigureCanvas ${cw}x$ch@$fps ditolak, memulihkan ${oldW}x$oldH@$oldFps")
            teardownStream()
            applied = buildPipeline(oldW, oldH, oldFps, bitrateBps)
        }
        if (applied && keep.isNotEmpty()) applyScene(keep)
        // Preview Studio ikut dipasang ke compositor baru (SurfaceView-nya sendiri tidak berubah)
        if (applied) studios.forEach { (surf, wh) -> if (surf.isValid) attachStudioPreview(surf, wh.first, wh.second) }
        return ok
    }

    private fun buildPipeline(cw: Int, ch: Int, fps: Int, bitrateBps: Int): Boolean {
        this.fps = fps
        cameraFps = fps
        canvasW = cw
        canvasH = ch
        val portrait = ch > cw

        val audio = GameMicAudioSource(projection, context)
        audioSource = audio
        // FrameClockVideoSource (bukan NoVideoSource): memberi tekstur utama frame ber-timestamp naik pada fps target.
        // Tanpanya timestamp frame encoder selalu 0 dan video tidak pernah terkirim ke server.
        val s = RtmpStream(context, connectChecker, FrameClockVideoSource(), audio)
        stream = s
        s.setFpsListener { f -> currentFps = f }

        // Konvensi RootEncoder 2.7.0: prepareVideo menerima ukuran LANDSCAPE; untuk portrait ia menukar ukuran
        // di dalam (setEncoderSize(height, width)) sehingga encoder DAN frame filter berukuran canvas portrait
        // (cw x ch), tanpa rotasi tambahan pada isinya. Landscape dan 1:1 = rotation 0.
        val rotation = if (portrait) 90 else 0
        val encW = if (portrait) ch else cw
        val encH = if (portrait) cw else ch
        if (!s.prepareVideo(encW, encH, bitrateBps, fps, 2, rotation)) {
            Log.e(tag, "prepareVideo gagal")
            return false
        }
        val gl = s.getGlInterface()
        // Jam frame di atas yang menggerakkan render. Render paksa hanya jaring pengaman (5 fps) bila jam terhenti.
        gl.setForceRender(true, 5)

        // Preview utama "kosong" yang terus dikuras. Ia menjaga thread GL tetap hidup selama engine
        // aktif; preview Studio ditambahkan/dilepas sebagai surface tambahan (multi-preview) tanpa
        // pernah menyentuh encoder atau RTMP.
        val pw = maxOf(canvasW / 8, 16)
        val ph = maxOf(canvasH / 8, 16)
        val reader = ImageReader.newInstance(pw, ph, PixelFormat.RGBA_8888, 2)
        // Dikuras di thread sendiri (bukan thread utama): bila UI sedang berat, thread GL/encoder tidak ikut tertahan
        val drain = HandlerThread("VilichtPreviewDrain").also { it.start() }
        previewDrainThread = drain
        reader.setOnImageAvailableListener({ r ->
            try { r.acquireLatestImage()?.close() } catch (_: Exception) {}
        }, Handler(drain.looper))
        previewReader = reader
        s.startPreview(reader.surface, pw, ph)

        // Sebelum editor mengirim scene: satu layer game memenuhi canvas
        applyScene(emptyList(), defaultGame = true)
        return true
    }

    /**
     * Mode kamera-saja -> mode game: token MediaProjection baru tiba. Pipeline GL, encoder, dan kamera
     * yang sudah berjalan TIDAK disentuh; hanya produsen frame untuk layer game yang dipasang.
     * Pemanggil wajib sudah memanggil startForeground() dengan tipe mediaProjection (Android 14+).
     */
    fun attachProjection(mp: MediaProjection) {
        if (stream == null) { try { mp.stop() } catch (_: Exception) {}; return }
        projection?.let { old ->
            try { old.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            if (old !== mp) try { old.stop() } catch (_: Exception) {}
        }
        projection = mp
        mp.registerCallback(projectionCallback, main)
        audioSource?.projection = mp
        (layers.firstOrNull { it.key == "SCREEN" }?.filter as? LayerFilterRender)
            ?.getSurfaceTexture()?.let { attachGame(it) }
    }

    // ---------------------------------------------------------------- scene / layer

    /**
     * Sinkronkan layer GL dengan daftar source scene. Perubahan posisi/ukuran/opacity diterapkan
     * seketika; perubahan struktur (tambah/hapus/urutan/konten) diterapkan dengan debounce singkat
     * karena layer di atasnya harus dibuat ulang.
     */
    fun applyScene(sources: List<SourceEntity>, defaultGame: Boolean = false) {
        if (stream == null) return
        val d = if (defaultGame) {
            listOf(Desired("SCREEN", "S", SourceEntity(sceneId = "", name = "game", type = SourceType.SCREEN)))
        } else computeDesired(sources)
        desired = d

        val sameStructure = d.size == layers.size &&
            d.indices.all { d[it].key == layers[it].key && d[it].sig == layers[it].sig }
        main.removeCallbacks(reconcileRunnable)
        if (sameStructure) {
            d.forEachIndexed { i, x -> layers[i].src = x.src; place(layers[i]) }
        } else {
            // Layer yang tidak berubah tetap digerakkan seketika selagi menunggu debounce
            for (l in layers) desired.firstOrNull { it.key == l.key && it.sig == l.sig }?.let { l.src = it.src; place(l) }
            if (layers.isEmpty()) reconcile() else main.postDelayed(reconcileRunnable, 120)
        }
    }

    private val reconcileRunnable = Runnable { reconcile() }

    private fun computeDesired(sources: List<SourceEntity>): List<Desired> {
        var screen = false
        var cam = false
        val out = mutableListOf<Desired>()
        // Layer yang disembunyikan TETAP hidup (alpha 0) agar kamera tidak dimatikan/dinyalakan ulang
        // setiap kali visibility diubah. Hanya Browser Source yang dibuang saat disembunyikan.
        for (s in sources.sortedBy { it.zIndex }) {
            when (s.type) {
                SourceType.SCREEN -> if (!screen) { screen = true; out += Desired("SCREEN", "S", s) }
                // Kunci kamera tidak memuat cameraId: ganti kamera = buka ulang perangkat, bukan layer GL baru
                SourceType.CAMERA -> if (!cam) { cam = true; out += Desired("CAMERA", "C", s) }
                SourceType.IMAGE ->
                    // Screenshot referensi hanya untuk editor: TIDAK PERNAH ikut ke output RTMP
                    if (!s.isReference && !s.contentUri.isNullOrEmpty()) out += Desired(s.id, "I:${s.contentUri}", s)
                SourceType.VIDEO ->
                    if (!s.contentUri.isNullOrEmpty()) out += Desired(s.id, "V:${s.contentUri}", s)
                SourceType.TEXT ->
                    out += Desired(
                        s.id,
                        "T:${s.textContent}|${s.textColor}|${s.textBgColor}|${s.textStrokeColor}|${(s.textStrokeWidth * 1000).roundToInt()}|" +
                            "${(s.width * 100).roundToInt()}|${(s.height * 100).roundToInt()}",
                        s
                    )
                SourceType.WEB ->
                    if (s.isVisible && !s.contentUri.isNullOrBlank()) out += Desired(
                        s.id,
                        "W:${s.webMode}|${s.contentUri}|${cssDim(s.width, canvasW)}x${cssDim(s.height, canvasH)}",
                        s
                    )
            }
        }
        return out
    }

    private fun reconcile() {
        val s = stream ?: return
        val gl = s.getGlInterface()
        var i = 0
        while (i < layers.size && i < desired.size &&
            layers[i].key == desired[i].key && layers[i].sig == desired[i].sig
        ) {
            layers[i].src = desired[i].src
            place(layers[i])
            i++
        }
        // Buang dari atas ke bawah, lalu bangun ulang sisanya sesuai urutan z
        for (j in layers.size - 1 downTo i) removeLayer(layers[j], gl)
        for (j in i until desired.size) addLayer(desired[j], gl)
    }

    private fun addLayer(d: Desired, gl: GlStreamInterface) {
        val src = d.src
        val holder = arrayOfNulls<Layer>(1)
        val layer: Layer = when (src.type) {
            SourceType.SCREEN -> {
                val f = LayerFilterRender(LayerFilterRender.Kind.EXTERNAL) { st ->
                    if (holder[0]?.dead == false) attachGame(st)
                }
                Layer(d.key, d.sig, f)
            }
            SourceType.CAMERA -> {
                val f = LayerFilterRender(LayerFilterRender.Kind.EXTERNAL) { st ->
                    holder[0]?.let { if (!it.dead) startCamera(it, st) }
                }
                Layer(d.key, d.sig, f)
            }
            SourceType.VIDEO -> {
                val f = LayerFilterRender(LayerFilterRender.Kind.EXTERNAL) { st ->
                    holder[0]?.let { if (!it.dead) startVideo(it, st) }
                }
                Layer(d.key, d.sig, f)
            }
            SourceType.IMAGE -> {
                val f = LayerFilterRender(LayerFilterRender.Kind.BITMAP)
                val l = Layer(d.key, d.sig, f)
                loadImageAsync(l, f, src.contentUri.orEmpty())
                l
            }
            SourceType.TEXT -> {
                val f = LayerFilterRender(LayerFilterRender.Kind.BITMAP)
                f.setBitmap(renderText(src))
                Layer(d.key, d.sig, f)
            }
            SourceType.WEB -> {
                val f = LayerFilterRender(LayerFilterRender.Kind.EXTERNAL) { st ->
                    holder[0]?.let { if (!it.dead) { it.webSt = st; startWebLayer(it) } }
                }
                val l = Layer(d.key, d.sig, f)
                l.web = webHost.create(
                    target = src.contentUri.orEmpty(),
                    isLocalFile = src.webMode == 1,
                    cssW = cssDim(src.width, canvasW),
                    cssH = cssDim(src.height, canvasH),
                    onLaidOut = { wv -> if (!l.dead) { l.webView = wv; startWebLayer(l) } },
                    onProblem = { msg -> onNotice?.invoke(msg) }
                )
                l
            }
        }
        holder[0] = layer
        layer.src = src
        layers.add(layer)
        gl.addFilter(layer.filter)
        place(layer)
    }

    private fun removeLayer(l: Layer, gl: GlStreamInterface) {
        l.dead = true
        // Urutan penting: hentikan produsen frame dulu, baru lepas filter (yang melepas SurfaceTexture)
        l.camera?.stop()
        l.camera = null
        l.video?.stop()
        l.video = null
        l.webLayer?.stop()
        l.webLayer = null
        webHost.destroy(l.web)
        l.web = null
        if (l.key == "SCREEN") {
            try { virtualDisplay?.setSurface(null) } catch (_: Exception) {}
            try { gameSurface?.release() } catch (_: Exception) {}
            gameSurface = null
        }
        try { gl.removeFilter(l.filter) } catch (e: Exception) { Log.w(tag, "removeFilter: ${e.message}") }
        layers.remove(l)
    }

    private fun place(l: Layer) {
        val src = l.src ?: return
        l.video?.setPaused(src.videoPaused)
        l.video?.setSound(src.videoSound)
        when (val f = l.filter) {
            is LayerFilterRender -> {
                f.canvasW = canvasW.toFloat()
                f.canvasH = canvasH.toFloat()
                // SATU set parameter untuk preview Studio dan encoder (keduanya membaca tekstur filter yang sama)
                f.params = LayerParams(
                    x = src.x, y = src.y, w = src.width, h = src.height,
                    cropL = src.cropLeft, cropT = src.cropTop, cropR = src.cropRight, cropB = src.cropBottom,
                    rotationDeg = src.rotation,
                    flipH = src.flipH, flipV = src.flipV,
                    contentQuarterTurns = quarterTurnsFor(l),
                    alpha = src.opacity, visible = src.isVisible,
                    // Teks dirender ke bitmap seukuran kotaknya, jadi selalu STRETCH (tanpa letterbox)
                    fitMode = if (src.type == SourceType.TEXT || src.type == SourceType.WEB) FitMode.STRETCH else FitMode.sanitize(src.fitMode),
                    contentAspect = contentAspectOf(l, src),
                    zoom = src.zoom, panX = src.panX, panY = src.panY,
                    chromaOn = src.chromaKeyEnabled && src.type != SourceType.TEXT,
                    keyR = ((src.chromaKeyColor shr 16) and 0xFF) / 255f,
                    keyG = ((src.chromaKeyColor shr 8) and 0xFF) / 255f,
                    keyB = (src.chromaKeyColor and 0xFF) / 255f,
                    chromaSim = src.chromaSimilarity, chromaSmooth = src.chromaSmoothness,
                    chromaSpill = src.chromaSpill
                )
                if (l.key == "CAMERA") {
                    syncCamera(l)
                    // Putaran berubah (rotasi layar / koreksi manual): rasio konten ikut berubah
                    val q = quarterTurnsFor(l)
                    if (l.camera != null && q != l.lastQuarter) {
                        l.lastQuarter = q
                        reportCameraAspect(l)
                    }
                }
            }
        }
    }

    /**
     * Rasio asli konten sebuah layer: nilai NYATA dari sumbernya bila ada (kamera, ukuran capture layar,
     * gambar yang sudah di-decode), kalau tidak nilai tersimpan di database.
     */
    private fun contentAspectOf(l: Layer, src: SourceEntity): Float = when {
        l.key == "SCREEN" && capW > 0 && capH > 0 -> capW.toFloat() / capH
        l.liveAspect > 0f -> l.liveAspect
        else -> src.contentAspect
    }

    /**
     * Koreksi orientasi sensor kamera dalam putaran 90° searah jarum jam. Sensor memberi gambar yang harus
     * diputar SENSOR_ORIENTATION derajat agar tegak pada orientasi natural perangkat; rotasi layar
     * (Surface.ROTATION_x) dikurangkan (belakang) atau ditambahkan (depan);
     * tampilan cermin kamera depan adalah properti Flip Horizontal di layer.
     */
    private fun quarterTurnsFor(l: Layer): Int {
        val cam = l.camera
        if (l.key != "CAMERA" || cam == null) return 0
        // Rumus Camera2 standar: kamera belakang = sensor - rotasi layar; kamera DEPAN = sensor + rotasi layar
        // (arah putar layar berkebalikan dengan sensor depan). Pada rotasi layar 0 hasilnya sama; bedanya
        // muncul di landscape (90/270), yaitu kasus game landscape pada tablet.
        val display = displayRotationDegrees()
        val deg = if (cam.isFront) (cam.sensorOrientation + display) % 360
                  else ((cam.sensorOrientation - display) % 360 + 360) % 360
        // Koreksi manual dari pengguna (Properties > Putar kamera) ditambahkan di atas hasil otomatis
        val manual = ((l.src?.cameraRotation ?: 0) % 4 + 4) % 4
        return ((deg / 90) + manual) % 4
    }

    /** Buka ulang kamera HANYA bila kamera/resolusi/fps yang diminta berubah. Layer GL dan transformasi tidak berubah. */
    private fun syncCamera(l: Layer) {
        val cam = l.camera ?: return                       // belum dimulai: startCamera() lewat callback surface
        val f = l.filter as? LayerFilterRender ?: return
        val st = f.getSurfaceTexture() ?: return
        val src = l.src ?: return
        val key = "${src.cameraId}|$cameraMaxPixels|$cameraFps"
        if (key == l.camKey) return
        l.camKey = key
        cam.start(st, src.cameraId, cameraFps, cameraMaxPixels)
        reportCameraAspect(l)
    }

    /** Paksa kamera dibuka ulang (tombol "Coba lagi"): kunci dikosongkan sehingga syncCamera() membukanya lagi. */
    fun restartCamera() {
        layers.firstOrNull { it.key == "CAMERA" }?.let { it.camKey = ""; place(it) }
    }

    private fun reportCameraAspect(l: Layer) {
        val cam = l.camera ?: return
        val src = l.src ?: return
        if (cam.bufferWidth <= 0 || cam.bufferHeight <= 0) return
        val q = quarterTurnsFor(l)
        val a = if (q % 2 == 1) cam.bufferHeight.toFloat() / cam.bufferWidth else cam.bufferWidth.toFloat() / cam.bufferHeight
        l.liveAspect = a
        (l.filter as? LayerFilterRender)?.let { f -> f.params = f.params.copy(contentAspect = a) }
        onContentAspect?.invoke(src.id, a)
    }

    @Suppress("DEPRECATION")
    private fun displayRotationDegrees(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return wm.defaultDisplay.rotation * 90
    }

    // ---------------------------------------------------------------- game capture

    private fun attachGame(st: SurfaceTexture) {
        val mp = projection ?: return
        val (w, h) = captureSize()
        capW = w
        capH = h
        onCaptureAspect?.invoke(w.toFloat() / h)
        layers.firstOrNull { it.key == "SCREEN" }?.let { place(it) }
        val surface = try {
            st.setDefaultBufferSize(w, h)
            Surface(st)
        } catch (e: Exception) {
            Log.w(tag, "SurfaceTexture game sudah dilepas: ${e.message}")
            return
        }
        val old = gameSurface
        gameSurface = surface
        val vd = virtualDisplay
        try {
            if (vd == null) {
                // createVirtualDisplay hanya boleh SEKALI per token MediaProjection (Android 14+)
                val thread = HandlerThread("VilichtVD").also { it.start() }
                vdThread = thread
                virtualDisplay = mp.createVirtualDisplay(
                    "VilichtGame", w, h, context.resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    if (gameCaptureActive) surface else null, null, Handler(thread.looper)
                )
            } else {
                // Layer game dibuat ulang (urutan z berubah): pindahkan output ke surface baru
                vd.setSurface(if (gameCaptureActive) surface else null)
            }
        } catch (e: Exception) {
            Log.e(tag, "createVirtualDisplay gagal: ${e.message}", e)
            onError?.invoke("Capture layar gagal: ${e.localizedMessage}")
            return
        }
        try { old?.release() } catch (_: Exception) {}
    }

    /**
     * Mode editor. active=false: jeda capture layar (layer game membeku di frame terakhir).
     * active=true: lanjutkan capture. Tidak menyentuh encoder, RTMP, kamera, maupun overlay.
     */
    fun setGameCaptureActive(active: Boolean) {
        gameCaptureActive = active
        val vd = virtualDisplay ?: return
        try {
            vd.setSurface(if (active) gameSurface else null)
        } catch (e: Exception) {
            Log.w(tag, "setSurface($active) gagal: ${e.message}")
        }
    }

    /** Dipanggil saat rotasi layar berubah: sesuaikan ukuran capture dan rotasi kamera. */
    fun onDisplayChanged() {
        layers.firstOrNull { it.key == "CAMERA" }?.let { place(it); reportCameraAspect(it) }
        val vd = virtualDisplay ?: return
        val (w, h) = captureSize()
        if (w == capW && h == capH) return
        capW = w
        capH = h
        onCaptureAspect?.invoke(w.toFloat() / h)
        layers.firstOrNull { it.key == "SCREEN" }?.let { place(it) }
        try {
            vd.resize(w, h, context.resources.displayMetrics.densityDpi)
            (layers.firstOrNull { it.key == "SCREEN" }?.filter as? LayerFilterRender)
                ?.getSurfaceTexture()?.setDefaultBufferSize(w, h)
        } catch (e: Exception) {
            Log.w(tag, "resize gagal: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- kamera

    private fun startCamera(l: Layer, st: SurfaceTexture) {
        val cam = CameraLayerSource(context) { msg -> main.post { onCameraStatus?.invoke(msg) } }
        l.camera = cam
        l.camKey = "${l.src?.cameraId}|$cameraMaxPixels|$cameraFps"
        cam.start(st, l.src?.cameraId, cameraFps, cameraMaxPixels)
        place(l) // rotasi kamera bergantung pada sensor kamera yang baru dibuka
        reportCameraAspect(l)
    }

    // ---------------------------------------------------------------- browser source

    private fun startWebLayer(l: Layer) {
        val st = l.webSt ?: return
        val wv = l.webView ?: return
        if (l.dead || l.webLayer != null) return
        val w = WebLayerSource(main, wv)
        val size = w.start(st) ?: return
        l.webLayer = w
        l.liveAspect = size.first.toFloat() / size.second
        place(l)
    }

    // ---------------------------------------------------------------- video

    private fun startVideo(l: Layer, st: SurfaceTexture) {
        val path = l.src?.contentUri ?: return
        val v = VideoLayerSource(
            context,
            onSize = { w, h -> main.post { if (!l.dead) { l.liveAspect = w.toFloat() / h; place(l) } } },
            onError = { msg -> main.post { onNotice?.invoke(msg) } }
        )
        l.video = v
        v.start(st, path, l.src?.videoPaused ?: false, l.src?.videoSound ?: false)
    }

    // ---------------------------------------------------------------- gambar & teks

    private fun loadImageAsync(l: Layer, f: LayerFilterRender, path: String) {
        decoder.execute {
            val bmp = try { decodeBitmap(path, 1280) } catch (e: Throwable) {
                Log.e(tag, "decode gambar gagal: ${e.message}"); null
            }
            main.post {
                if (bmp == null) { onNotice?.invoke("Gambar overlay tidak bisa dibuka."); return@post }
                if (l.dead) { bmp.recycle(); return@post }
                if (bmp.height > 0) l.liveAspect = bmp.width.toFloat() / bmp.height
                f.setBitmap(bmp)   // bitmap diserahkan ke GL; jangan dibaca setelah baris ini
                place(l)
            }
        }
    }

    private fun decodeBitmap(path: String, maxSide: Int): Bitmap? {
        fun openStream() = if (path.startsWith("content:") || path.startsWith("file:"))
            context.contentResolver.openInputStream(Uri.parse(path)) else java.io.File(path).inputStream()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return openStream()?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Teks dirender ke bitmap sekali; rumus ukuran font sama dengan editor supaya hasilnya konsisten. */
    private fun renderText(src: SourceEntity): Bitmap {
        val w = (src.width * canvasW).roundToInt().coerceIn(32, 1280)
        val h = (src.height * canvasH).roundToInt().coerceIn(16, 1280)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Background hanya digambar bila pengguna memilihnya (alpha > 0); bawaan = teks polos
        if (((src.textBgColor shr 24) and 0xFF) > 0L) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = src.textBgColor.toInt() }
            c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 8f, 8f, bg)
        }
        val text = src.textContent ?: "TEXT"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = src.textColor.toInt()
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = textSizePx(text, w.toFloat(), h.toFloat())
        }
        val y = h / 2f - (paint.ascent() + paint.descent()) / 2f
        // Garis tepi digambar DI BAWAH isi teks; lebarnya pecahan ukuran font (sama dengan editor)
        val strokePx = paint.textSize * src.textStrokeWidth.coerceIn(0f, 0.3f)
        if (strokePx >= 0.5f) {
            val outline = Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                strokeJoin = Paint.Join.ROUND
                color = src.textStrokeColor.toInt()
            }
            c.drawText(text, w / 2f, y, outline)
        }
        c.drawText(text, w / 2f, y, paint)
        return bmp
    }

    // ---------------------------------------------------------------- preview Studio

    /** Tampilkan hasil komposisi (yang sama dengan yang dikirim ke RTMP) di SurfaceView Studio. */
    fun attachStudioPreview(surface: Surface, width: Int, height: Int) {
        val s = stream ?: return
        if (!surface.isValid || !s.isOnPreview) return
        try {
            if (previewSurfaces.containsKey(surface)) { try { s.removeMultiPreviewSurface(surface) } catch (_: Exception) {} }
            s.addPreviewSurface(
                surface,
                MultiPreviewConfig(
                    width = width, height = height,
                    aspectRatioMode = AspectRatioMode.Adjust,
                    isPortrait = canvasH > canvasW
                )
            )
            previewSurfaces[surface] = width to height
        } catch (e: Exception) {
            Log.w(tag, "attachStudioPreview gagal: ${e.message}")
        }
    }

    /** [surface] null = lepas semua preview lokal. Encoder/RTMP tidak tersentuh. */
    fun detachStudioPreview(surface: Surface?) {
        val s = stream
        val targets = if (surface != null) listOf(surface) else previewSurfaces.keys.toList()
        for (t in targets) {
            if (s != null) { try { s.removeMultiPreviewSurface(t) } catch (_: Exception) {} }
            previewSurfaces.remove(t)
        }
    }

    /** Video overlay kembali ke awal. */
    fun restartVideo(sourceId: String) {
        layers.firstOrNull { it.src?.id == sourceId }?.video?.restart()
    }

    // ---------------------------------------------------------------- snapshot referensi

    /** Game dengan rasio aslinya, muat penuh di canvas dan di tengah. */
    private fun fitRect(): LayerRect {
        if (capW <= 0 || capH <= 0) return LayerRect(0f, 0f, 1f, 1f)
        val canvasAspect = canvasW.toFloat() / canvasH
        val gameAspect = capW.toFloat() / capH
        val w: Float
        val h: Float
        if (gameAspect > canvasAspect) { w = 1f; h = canvasAspect / gameAspect }
        else { h = 1f; w = gameAspect / canvasAspect }
        return LayerRect((1f - w) / 2f, (1f - h) / 2f, w, h)
    }

    /** Rect game (0..1) yang mempertahankan rasio layar asli. Dipakai tombol "Pas rasio" di editor. */
    fun fitGameRect(): LayerRect = fitRect()

    /**
     * Potret layar game saja (tanpa kamera/overlay), rasio asli, untuk referensi visual editor.
     * Ini hanya bitmap untuk editor; tidak pernah menjadi sumber video siaran.
     */
    fun captureGameSnapshot(onResult: (Bitmap?) -> Unit) {
        val s = stream
        val game = layers.firstOrNull { it.key == "SCREEN" }
        if (s == null || game == null || capW <= 0) { onResult(null); return }
        onDisplayChanged()
        val fit = fitRect()
        (game.filter as? LayerFilterRender)?.params = LayerParams(x = fit.x, y = fit.y, w = fit.w, h = fit.h)
        hideOverlays(true)
        main.postDelayed({
            s.getGlInterface().takePhoto(TakePhotoCallback { bmp ->
                val out = try { cropTo(bmp, fit) } catch (e: Exception) {
                    Log.e(tag, "crop snapshot gagal: ${e.message}"); null
                }
                main.post {
                    hideOverlays(false)
                    place(game)
                    onResult(out)
                }
            })
        }, 250)
    }

    private fun hideOverlays(hide: Boolean) {
        for (l in layers) {
            if (l.key == "SCREEN") continue
            when (val f = l.filter) {
                is LayerFilterRender -> if (hide) f.params = f.params.copy(visible = false) else place(l)
            }
        }
    }

    private fun cropTo(src: Bitmap, r: LayerRect): Bitmap {
        val x = (r.x * src.width).toInt().coerceIn(0, src.width - 1)
        val y = (r.y * src.height).toInt().coerceIn(0, src.height - 1)
        val w = (r.w * src.width).toInt().coerceIn(1, src.width - x)
        val h = (r.h * src.height).toInt().coerceIn(1, src.height - y)
        return Bitmap.createBitmap(src, x, y, w, h)
    }

    // ---------------------------------------------------------------- siaran

    fun goLive(endpoint: String, audioBitrateBps: Int, maxRetries: Int = 10): Boolean {
        val s = stream ?: return false
        s.getStreamClient().setReTries(maxRetries)
        if (!s.prepareAudio(44100, true, audioBitrateBps)) return false
        s.startStream(endpoint)
        return true
    }

    fun retry(delayMs: Long, reason: String): Boolean =
        stream?.getStreamClient()?.reTry(delayMs, reason) == true

    fun stopLive() {
        val s = stream ?: return
        if (s.isStreaming) s.stopStream()
    }

    /** Statistik nyata dari klien RTMP; null kalau belum ada stream. */
    fun counters(): EngineCounters? {
        val c = stream?.getStreamClient() ?: return null
        return EngineCounters(c.getSentVideoFrames(), c.getDroppedVideoFrames(), c.getBytesSend())
    }

    /** Jeda = layar hitam + audio senyap, siaran tetap tersambung. */
    fun setPaused(paused: Boolean) {
        com.example.audio.StreamAudioParams.paused = paused
        val gl = stream?.getGlInterface() ?: return
        if (paused) gl.muteVideo() else gl.unMuteVideo()
    }

    /** Bongkar compositor + encoder, tetapi biarkan MediaProjection dan VirtualDisplay tetap hidup. */
    private fun teardownStream() {
        main.removeCallbacks(reconcileRunnable)
        for (l in layers.toList()) {
            l.dead = true
            l.camera?.stop(); l.camera = null
            l.video?.stop(); l.video = null
            l.webLayer?.stop(); l.webLayer = null
            webHost.destroy(l.web); l.web = null
        }
        layers.clear()
        desired = emptyList()
        detachStudioPreview(null)
        // Hentikan produsen frame game sebelum surface-nya dilepas
        try { virtualDisplay?.setSurface(null) } catch (_: Exception) {}
        try { stream?.release() } catch (e: Exception) { Log.w(tag, "release stream: ${e.message}") }
        stream = null
        try { gameSurface?.release() } catch (_: Exception) {}
        gameSurface = null
        try { previewReader?.close() } catch (_: Exception) {}
        previewReader = null
        previewDrainThread?.quitSafely()
        previewDrainThread = null
        currentFps = 0
    }

    fun release() {
        teardownStream()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        vdThread?.quitSafely()
        vdThread = null
        projection?.let {
            try { it.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            try { it.stop() } catch (_: Exception) {}
        }
        projection = null
        capW = 0
        capH = 0
        gameCaptureActive = true
    }

    /** Matikan pool decoder gambar; dipanggil sekali saat service dihancurkan. */
    fun shutdown() {
        decoder.shutdownNow()
    }

    private fun captureSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var rw: Int
        var rh: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            rw = b.width(); rh = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            rw = dm.widthPixels; rh = dm.heightPixels
        }
        // Batasi sisi terpanjang supaya ringan, jaga rasio, dan pastikan genap
        val maxSide = 1280f
        val scale = minOf(1f, maxSide / maxOf(rw, rh))
        rw = (rw * scale).toInt() and 1.inv()
        rh = (rh * scale).toInt() and 1.inv()
        return maxOf(rw, 2) to maxOf(rh, 2)
    }

    companion object {
        /** Ukuran viewport CSS Browser Source (kelipatan 16) dari fraksi canvas. */
        fun cssDim(fraction: Float, canvasPx: Int): Int =
            ((fraction * canvasPx / 16f).roundToInt() * 16).coerceIn(64, 3840)

        /** Rumus ukuran font teks overlay; dipakai bersama oleh GL dan editor agar konsisten. */
        fun textSizePx(text: String, w: Float, h: Float): Float =
            min(h * 0.55f, w * 0.92f / (max(1, text.length) * 0.58f))
    }
}
