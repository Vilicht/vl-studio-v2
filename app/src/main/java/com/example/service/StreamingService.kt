package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.audio.StreamAudioParams
import com.example.engine.StudioEngine
import com.pedro.common.ConnectChecker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.view.Surface
import com.example.data.model.SourceEntity
import com.example.engine.LayerRect

class StreamingService : Service(), ConnectChecker {
    private val TAG = "VilichtStreamingService"

    companion object {
        /** Mulai capture + engine dalam mode PREVIEW (belum siaran). */
        const val ACTION_START_PREVIEW = "com.example.service.START_PREVIEW"
        /** Mulai siaran RTMP dari mode PREVIEW. */
        const val ACTION_GO_LIVE = "com.example.service.GO_LIVE"
        /** Preview kamera mandiri (tanpa MediaProjection, tanpa siaran). Hanya boleh dikirim saat Activity terlihat. */
        const val ACTION_START_CAMERA_PREVIEW = "com.example.service.START_CAMERA_PREVIEW"
        const val ACTION_STOP_CAMERA_PREVIEW = "com.example.service.STOP_CAMERA_PREVIEW"
        /** Ubah resolusi/fps kamera saat preview berjalan. */
        const val ACTION_CAMERA_SETTINGS = "com.example.service.CAMERA_SETTINGS"
        const val EXTRA_CAMERA_MAX_PIXELS = "extra_camera_max_pixels"
        const val ACTION_SNAPSHOT = "com.example.service.SNAPSHOT"
        const val ACTION_STOP = "com.example.service.STOP_STREAM"
        const val ACTION_PAUSE = "com.example.service.PAUSE_STREAM"
        const val ACTION_RESUME = "com.example.service.RESUME_STREAM"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_ENDPOINT = "extra_endpoint"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_AUDIO_BITRATE = "extra_audio_bitrate"
        /** Dipasang pada ACTION_START_PREVIEW: begitu pipeline siap, langsung mulai siaran ke endpoint ini. */
        const val EXTRA_AUTO_ENDPOINT = "extra_auto_endpoint"
        const val EXTRA_AUTO_AUDIO_BITRATE = "extra_auto_audio_bitrate"
        const val EXTRA_PORTRAIT = "extra_portrait"
        /** Ukuran canvas keluaran (piksel) dari scene aktif; 0 = turunkan dari preset + EXTRA_PORTRAIT. */
        const val EXTRA_CANVAS_W = "extra_canvas_w"
        const val EXTRA_CANVAS_H = "extra_canvas_h"

        private const val NOTIFICATION_CHANNEL_ID = "vilicht_live_channel"
        private const val NOTIFICATION_ID = 1001

        /** WakeLock punya timeout pendek dan diperpanjang ticker selama siaran; jika service mati tanpa melepas, lock kedaluwarsa sendiri. */
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60 * 1000
        private const val WAKELOCK_RENEW_EVERY_S = 10L * 60
        private const val MAX_OFFLINE_WAIT_S = 10 * 60

        /** Pengaturan reconnect (bisa diubah dari UI). Dibaca saat koneksi gagal. */
        @Volatile var reconnectMaxRetries: Int = 10
        @Volatile var reconnectIntervalMs: Long = 3000L

        // Singleton observable state for Compose UI
        private val _studioState = MutableStateFlow(LiveStudioState())
        val studioState: StateFlow<LiveStudioState> = _studioState.asStateFlow()

        /** Dipanggil UI saat pengguna menekan "Coba lagi" setelah preview kamera gagal total (engine tidak berjalan). */
        fun resetCameraPreviewState() {
            if (activeServiceInstance?.engine == null) {
                _studioState.value = _studioState.value.copy(
                    cameraPreview = CameraPreviewState.OFF, cameraStatus = null, errorMessage = null
                )
            }
        }

        var activeServiceInstance: StreamingService? = null

        /** Diset ViewModel: menerima rasio asli konten (kamera) untuk Reset Ratio. Dipanggil di main thread. */
        @Volatile var contentAspectListener: ((String, Float) -> Unit)? = null

        /** Diset ViewModel: tombol "Ganti Scene" di bubble melayang meminta pindah ke scene berikutnya. */
        @Volatile var nextSceneListener: (() -> Unit)? = null
    }

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }

    private var engine: StudioEngine? = null

    private var floatingOverlay: FloatingLiveOverlay? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tickerJob: Job? = null
    /** Peringatan "video belum terkirim" hanya ditampilkan sekali per siaran. */
    private var videoStallWarned = false

    private var currentEndpoint: String = ""
    private var streamStartTime: Long = 0
    private var isStreamPaused = false
    private var stopping = false
    private var currentBitrate: Int = 3000
    // Bitrate nyata (bps) dari callback RootEncoder, dan byte terkirim untuk fallback hitung manual
    @Volatile private var measuredBitrateBps: Long = 0
    private var reconnectCount = 0
    /** true saat Studio terlihat: layer game dibekukan, bukan encoder/RTMP. */
    private var editorVisible = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivity: ConnectivityManager? = null
    @Volatile private var networkOnline = true
    @Volatile private var networkLabel = "Unknown"
    @Volatile private var thermalLevel = -1
    private var thermalListener: Any? = null
    private var userRequestedStop = false
    private var retriesUsed = 0
    private var offlineSeconds = 0

    private var targetWidth = 1280
    private var targetHeight = 720
    private var targetFps = 30
    /** Ukuran canvas keluaran dari scene aktif (0 = belum diketahui). */
    private var canvasW = 0
    private var canvasH = 0

    private fun readCanvasExtras(intent: Intent) {
        canvasW = intent.getIntExtra(EXTRA_CANVAS_W, 0)
        canvasH = intent.getIntExtra(EXTRA_CANVAS_H, 0)
        if (canvasW > 0 && canvasH > 0) { targetWidth = canvasW; targetHeight = canvasH }
    }

    /** Canvas keluaran: dari scene bila ada, kalau tidak dari preset (kompatibel dengan pemanggil lama). */
    private fun canvasFor(portrait: Boolean): Pair<Int, Int> {
        if (canvasW > 0 && canvasH > 0) return canvasW to canvasH
        val long = maxOf(targetWidth, targetHeight)
        val short = minOf(targetWidth, targetHeight)
        return if (portrait) short to long else long to short
    }

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        createNotificationChannel()
        startNetworkMonitor()
        startThermalMonitor()
        floatingOverlay = FloatingLiveOverlay(
            context = this,
            onEndLive = { stopStreaming() },
            onTogglePause = { togglePause() },
            onToggleMic = { toggleMic() },
            onEditLayout = { captureLayoutSnapshot() },
            onNextScene = { nextSceneListener?.invoke() }
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Game diputar landscape/portrait: sesuaikan ukuran capture
        engine?.onDisplayChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAMERA_PREVIEW -> {
                targetWidth = intent.getIntExtra(EXTRA_WIDTH, 1280)
                targetHeight = intent.getIntExtra(EXTRA_HEIGHT, 720)
                targetFps = intent.getIntExtra(EXTRA_FPS, 30)
                currentBitrate = intent.getIntExtra(EXTRA_BITRATE, 3000)
                val portrait = intent.getBooleanExtra(EXTRA_PORTRAIT, false)
                readCanvasExtras(intent)
                val maxPx = intent.getIntExtra(EXTRA_CAMERA_MAX_PIXELS, 1280 * 720)
                if (engine == null) {
                    stopping = false
                    userRequestedStop = false
                    if (!startForegroundServiceWithNotification(withProjection = false)) {
                        _studioState.value = _studioState.value.copy(
                            cameraPreview = CameraPreviewState.ERROR,
                            cameraStatus = _studioState.value.errorMessage
                        )
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    startCameraOnlyPipeline(portrait, maxPx)
                } else {
                    // Sudah berjalan: jangan buka sesi kamera kedua
                    engine?.cameraMaxPixels = maxPx
                    engine?.cameraFps = targetFps
                    engine?.applyScene(pendingScene)
                }
            }
            ACTION_CAMERA_SETTINGS -> {
                engine?.restartCamera()   // juga dipakai tombol "Coba lagi": buka ulang kamera yang sama
                engine?.cameraMaxPixels = intent.getIntExtra(EXTRA_CAMERA_MAX_PIXELS, 1280 * 720)
                engine?.cameraFps = intent.getIntExtra(EXTRA_FPS, targetFps)
                engine?.applyScene(pendingScene)
            }
            ACTION_STOP_CAMERA_PREVIEW -> {
                // Hanya matikan bila tidak sedang ada siaran / capture layar
                if (_studioState.value.status == StreamingStatus.OFFLINE) stopStreaming()
            }
            ACTION_START_PREVIEW -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                targetWidth = intent.getIntExtra(EXTRA_WIDTH, 1280)
                targetHeight = intent.getIntExtra(EXTRA_HEIGHT, 720)
                targetFps = intent.getIntExtra(EXTRA_FPS, 30)
                currentBitrate = intent.getIntExtra(EXTRA_BITRATE, 3000)
                val portrait = intent.getBooleanExtra(EXTRA_PORTRAIT, false)
                readCanvasExtras(intent)

                if (resultCode == Activity.RESULT_OK && data != null) {
                    stopping = false
                    userRequestedStop = false
                    if (!startForegroundServiceWithNotification(withProjection = true)) {
                        if (engine == null) stopSelf()
                        return START_NOT_STICKY
                    }
                    if (engine != null && engine?.hasProjection == false) {
                        // Preview kamera sudah berjalan: pasang capture layar ke pipeline yang SAMA
                        attachProjectionToRunningEngine(resultCode, data)
                    } else {
                        startPreviewPipeline(resultCode, data, portrait)
                    }
                    // GO LIVE ditekan: mulai siaran sekarang juga (pipeline dibuat sinkron di atas, jadi sudah siap)
                    val autoEndpoint = intent.getStringExtra(EXTRA_AUTO_ENDPOINT)
                    if (!autoEndpoint.isNullOrBlank() && _studioState.value.status == StreamingStatus.PREVIEW) {
                        goLive(autoEndpoint, intent.getIntExtra(EXTRA_AUTO_AUDIO_BITRATE, 128))
                    }
                } else {
                    Log.e(TAG, "Invalid start parameters for StreamingService")
                    stopSelf()
                }
            }
            ACTION_GO_LIVE -> {
                val endpoint = intent.getStringExtra(EXTRA_ENDPOINT) ?: ""
                val audioBitrate = intent.getIntExtra(EXTRA_AUDIO_BITRATE, 128)
                goLive(endpoint, audioBitrate)
            }
            ACTION_SNAPSHOT -> captureLayoutSnapshot()
            ACTION_STOP -> stopStreaming()
            ACTION_PAUSE, ACTION_RESUME -> togglePause()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "VILICHT Live Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live broadcast status and background control"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    /**
     * Tipe foreground service: camera (+ microphone bila izin ada) SELALU; mediaProjection hanya saat
     * capture layar dipakai. Android 14+ mewajibkan tipe sesuai izin runtime dan hanya mengizinkan
     * service bertipe camera/microphone DIMULAI saat aplikasi terlihat. Karena itu service dimulai dari
     * Activity yang terlihat, dan tipe yang dibutuhkan diminta sekaligus di awal.
     */
    @SuppressLint("ForegroundServiceType")
    private fun startForegroundServiceWithNotification(withProjection: Boolean): Boolean {
        val notification = buildNotification(initialStatusText(withProjection))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = 0
            if (withProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            val micGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val camGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (micGranted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (camGranted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            // Tanpa izin apa pun dan tanpa projection tidak ada tipe yang sah untuk service ini
            if (type == 0) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: Exception) {
                // Android melarang: mis. dimulai dari background. Jangan crash; beri tahu pengguna.
                Log.e(TAG, "startForeground ditolak: ${e.message}", e)
                _studioState.value = _studioState.value.copy(
                    errorMessage = "Android menolak menjalankan service streaming: ${e.localizedMessage}"
                )
                return false
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        _studioState.value = _studioState.value.copy(serviceRunning = true)
        return true
    }

    private fun initialStatusText(withProjection: Boolean) =
        if (withProjection) "Starting live broadcast…" else "Camera preview active"

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, StreamingService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val live = streamStartTime > 0
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(if (live) "VL Studio · LIVE" else "VL Studio")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Return to Studio", openPendingIntent)
        if (live) {
            // Durasi dihitung sistem dari waktu mulai nyata, jadi tetap akurat walau layar mati
            builder.setUsesChronometer(true).setWhen(streamStartTime).setShowWhen(true)
            builder.addAction(0, "Stop Streaming", stopPendingIntent)
        } else {
            builder.addAction(0, "Stop", stopPendingIntent)
        }
        return builder.build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    // ---------------------------------------------------------------- WakeLock

    /** PARTIAL_WAKE_LOCK hanya selama siaran. Layar TIDAK dipaksa menyala. */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VLStudio:streaming").also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            wl.acquire(WAKELOCK_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock gagal: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) {
            Log.w(TAG, "Release WakeLock: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- jaringan & termal

    private fun startNetworkMonitor() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivity = cm
        updateNetworkLabel(cm.activeNetwork)
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "registerDefaultNetworkCallback: ${e.message}")
        }
    }

    private fun updateNetworkLabel(n: Network?) {
        val caps = n?.let { connectivity?.getNetworkCapabilities(it) }
        networkOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        networkLabel = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val wasOffline = !networkOnline
            updateNetworkLabel(network)
            serviceScope.launch {
                // Jaringan kembali / berpindah (Wi-Fi <-> data) saat siaran sedang mencoba menyambung ulang
                if (wasOffline && !userRequestedStop && _studioState.value.status == StreamingStatus.RECONNECTING) {
                    engine?.retry(1000, "network restored")
                }
            }
        }

        override fun onLost(network: Network) {
            updateNetworkLabel(null)
            networkOnline = false
            networkLabel = "Offline"
        }
    }

    private fun startThermalMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            thermalLevel = pm.currentThermalStatus
            val l = PowerManager.OnThermalStatusChangedListener { st -> thermalLevel = st }
            thermalListener = l
            pm.addThermalStatusListener(l)
        } catch (e: Exception) {
            Log.w(TAG, "thermal listener: ${e.message}")
        }
    }

    private fun stopMonitors() {
        try { connectivity?.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let {
                    (getSystemService(Context.POWER_SERVICE) as PowerManager).removeThermalStatusListener(it)
                }
            } catch (_: Exception) {}
        }
        thermalListener = null
    }

    private fun wireEngine(eng: StudioEngine) {
        eng.onProjectionStopped = {
            if (!stopping) {
                _studioState.value = _studioState.value.copy(
                    errorMessage = "Screen capture dihentikan oleh sistem atau pengguna."
                )
                stopStreaming()
            }
        }
        eng.onError = { msg ->
            _studioState.value = _studioState.value.copy(status = StreamingStatus.ERROR, errorMessage = msg)
        }
        // Masalah non-fatal (izin overlay kurang, dsb): tampilkan pesan, siaran tetap jalan
        eng.onNotice = { msg ->
            _studioState.value = _studioState.value.copy(errorMessage = msg)
        }
        eng.onCameraStatus = { msg ->
            _studioState.value = _studioState.value.copy(
                cameraStatus = msg.ifEmpty { null },
                cameraPreview = if (msg.isEmpty()) CameraPreviewState.ACTIVE else CameraPreviewState.ERROR
            )
        }
        eng.onCaptureAspect = { a ->
            _studioState.value = _studioState.value.copy(captureAspect = a)
        }
        eng.onContentAspect = { id, a -> contentAspectListener?.invoke(id, a) }
        eng.cameraFps = targetFps
    }

    /** Scene terakhir dari editor; dipakai ulang saat pengaturan kamera berubah. */
    @Volatile private var pendingScene: List<SourceEntity> = emptyList()


    /** Preview kamera mandiri: pipeline GL + encoder disiapkan, TANPA MediaProjection dan TANPA siaran. */
    private fun startCameraOnlyPipeline(portrait: Boolean, maxPixels: Int) {
        _studioState.value = _studioState.value.copy(cameraPreview = CameraPreviewState.STARTING, cameraStatus = null)
        try {
            val eng = StudioEngine(this, this)
            wireEngine(eng)
            eng.cameraMaxPixels = maxPixels
            engine = eng
            val (cw, ch) = canvasFor(portrait)
            if (eng.startPreviewCanvas(null, cw, ch, targetFps, currentBitrate * 1000)) {
                eng.applyScene(pendingScene)
                _studioState.value = _studioState.value.copy(
                    cameraPreview = CameraPreviewState.ACTIVE, errorMessage = null, hasProjection = false
                )
            } else {
                _studioState.value = _studioState.value.copy(
                    cameraPreview = CameraPreviewState.ERROR,
                    cameraStatus = "Encoder ${targetWidth}x${targetHeight} gagal disiapkan untuk preview."
                )
                stopStreaming()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera-only pipeline gagal: ${e.message}", e)
            _studioState.value = _studioState.value.copy(
                cameraPreview = CameraPreviewState.ERROR,
                cameraStatus = "Preview kamera gagal dimulai: ${e.localizedMessage}"
            )
            stopStreaming()
        }
    }

    private fun attachProjectionToRunningEngine(resultCode: Int, data: Intent) {
        val eng = engine ?: return
        try {
            // startForeground(mediaProjection) sudah dipanggil sebelum ini (wajib di Android 14+)
            val mp: MediaProjection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection token tidak valid atau null")
            eng.attachProjection(mp)
            _studioState.value = _studioState.value.copy(
                status = StreamingStatus.PREVIEW, errorMessage = null, hasProjection = true
            )
            eng.setGameCaptureActive(!editorVisible)
            _studioState.value = _studioState.value.copy(isEditorMode = editorVisible)
            updateNotification("Ready. Open your game, then tap Atur Layout.")
            floatingOverlay?.show()
            floatingOverlay?.updateStatus(StreamingStatus.PREVIEW, "00:00", 0, 0, false, false)
        } catch (e: Exception) {
            Log.e(TAG, "attachProjection gagal: ${e.message}", e)
            _studioState.value = _studioState.value.copy(
                errorMessage = "Gagal memasang capture layar: ${e.localizedMessage}"
            )
        }
    }

    /** Izin capture sudah diberikan: nyalakan engine (capture + compositor) tanpa siaran. */
    private fun startPreviewPipeline(resultCode: Int, data: Intent, portrait: Boolean) {
        try {
            // Token MediaProjection hanya boleh dipakai SEKALI; engine yang memegangnya.
            val mp = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("MediaProjection token tidak valid atau null")
            val eng = StudioEngine(this, this)
            wireEngine(eng)
            engine = eng
            val (cw, ch) = canvasFor(portrait)
            if (eng.startPreviewCanvas(mp, cw, ch, targetFps, currentBitrate * 1000)) {
                _studioState.value = _studioState.value.copy(
                    status = StreamingStatus.PREVIEW,
                    errorMessage = null,
                    hasProjection = true,
                    cameraPreview = CameraPreviewState.ACTIVE
                )
                eng.applyScene(pendingScene)
                eng.setGameCaptureActive(!editorVisible)
                _studioState.value = _studioState.value.copy(isEditorMode = editorVisible)
                updateNotification("Siap. Buka game lalu tekan Atur Layout.")
                floatingOverlay?.show()
                floatingOverlay?.updateStatus(StreamingStatus.PREVIEW, "00:00", 0, 0, false, false)
            } else {
                _studioState.value = _studioState.value.copy(
                    status = StreamingStatus.ERROR,
                    errorMessage = "Encoder video ${targetWidth}x${targetHeight} gagal disiapkan. Coba preset lebih rendah."
                )
                stopStreaming()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting preview pipeline: ${e.message}", e)
            _studioState.value = _studioState.value.copy(
                status = StreamingStatus.ERROR,
                errorMessage = "Gagal memulai capture: ${e.localizedMessage}"
            )
            stopStreaming()
        }
    }

    fun goLive(endpoint: String, audioBitrateKbps: Int) {
        val eng = engine
        if (eng == null || !eng.isPreviewing) {
            Log.e(TAG, "goLive dipanggil tanpa preview aktif")
            _studioState.value = _studioState.value.copy(errorMessage = "Mesin siaran belum siap. Tekan GO LIVE lagi.")
            showToast("Mesin siaran belum siap. Tekan GO LIVE lagi.")
            return
        }
        videoStallWarned = false
        if (endpoint.isBlank()) {
            _studioState.value = _studioState.value.copy(errorMessage = "Server URL / Stream Key kosong.")
            showToast("Server URL / Stream Key kosong.")
            return
        }
        currentEndpoint = endpoint
        userRequestedStop = false
        retriesUsed = 0
        _studioState.value = _studioState.value.copy(status = StreamingStatus.PREPARING, errorMessage = null)
        try {
            if (eng.goLive(endpoint, audioBitrateKbps * 1000, reconnectMaxRetries)) {
                streamStartTime = System.currentTimeMillis()
                acquireWakeLock()
                startMetricsTicker()
            } else {
                _studioState.value = _studioState.value.copy(
                    status = StreamingStatus.PREVIEW,
                    errorMessage = "Encoder audio gagal disiapkan. Cek izin mikrofon."
                )
                showToast("Encoder audio gagal disiapkan. Cek izin mikrofon.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "goLive failed: ${e.message}", e)
            releaseWakeLock()
            _studioState.value = _studioState.value.copy(
                status = StreamingStatus.PREVIEW,
                errorMessage = "Gagal mulai siaran: ${e.localizedMessage}"
            )
            showToast("Gagal mulai siaran: ${e.localizedMessage}")
        }
    }

    /** Pesan singkat yang tetap terlihat di atas game saat aplikasi sedang di latar. */
    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            try { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        }
    }

    /** Ambil snapshot layar game (boleh freeze), lalu buka studio untuk atur posisi. */
    fun captureLayoutSnapshot() {
        val eng = engine ?: return
        eng.captureGameSnapshot { bmp ->
            if (bmp != null) {
                setFrozenSnapshot(bmp)
                bringStudioToFront()
            } else {
                _studioState.value = _studioState.value.copy(errorMessage = "Snapshot gagal. Coba lagi saat game tampil.")
            }
        }
    }

    /** Sinkronkan seluruh source scene aktif ke compositor (posisi, ukuran, urutan z, konten). */
    fun applyScene(sources: List<SourceEntity>) {
        pendingScene = sources
        engine?.applyScene(sources)
    }

    /**
     * Samakan canvas engine dengan scene aktif / preset (rasio, resolusi, fps, bitrate).
     * Hanya bisa saat TIDAK sedang siaran: encoder yang mengudara tidak boleh berganti resolusi.
     * Mengembalikan false (dan engine tidak diubah) bila ditolak atau gagal; [reason] berisi alasannya.
     */
    fun reconfigureCanvas(cw: Int, ch: Int, fps: Int, bitrateKbps: Int, reason: (String) -> Unit = {}): Boolean {
        val eng = engine
        if (eng == null) {
            // Belum ada pipeline: nilai ini dipakai saat pipeline berikutnya dimulai
            canvasW = cw; canvasH = ch; targetWidth = cw; targetHeight = ch; targetFps = fps; currentBitrate = bitrateKbps
            return true
        }
        if (eng.isLive) { reason("Rasio/resolusi tidak bisa diubah saat LIVE. Hentikan siaran dulu."); return false }
        val ok = eng.reconfigureCanvas(cw, ch, fps, bitrateKbps * 1000)
        if (ok) {
            canvasW = cw; canvasH = ch; targetWidth = cw; targetHeight = ch; targetFps = fps; currentBitrate = bitrateKbps
            eng.applyScene(pendingScene)
        } else {
            reason("Encoder ${cw}x$ch @ ${fps}fps gagal disiapkan. Coba resolusi lebih rendah.")
            // Engine sudah mencoba memulihkan konfigurasi lama; bila itu pun gagal, hentikan sesi dengan bersih
            if (!eng.isPipelineReady) stopStreaming()
        }
        return ok
    }

    /** Ukuran canvas yang sedang dipakai compositor/encoder (null bila belum ada pipeline). */
    fun engineCanvasSize(): Pair<Int, Int>? = engine?.takeIf { it.isPipelineReady }?.let { it.canvasW to it.canvasH }

    /** Rect game yang mempertahankan rasio layar asli (untuk tombol "Pas rasio"). */
    fun fitGameRect(): LayerRect? = engine?.fitGameRect()

    /**
     * Studio terlihat/tidak. Terlihat: capture game dijeda (layer game membeku) supaya Studio tidak
     * ikut ter-capture. Tidak terlihat (pengguna kembali ke game): capture lanjut. Encoder, RTMP,
     * kamera, dan overlay TIDAK disentuh sama sekali.
     */
    fun setEditorVisible(visible: Boolean) {
        editorVisible = visible
        engine?.setGameCaptureActive(!visible)
        if (engine != null) _studioState.value = _studioState.value.copy(isEditorMode = visible)
    }

    fun attachStudioPreview(surface: Surface, w: Int, h: Int) { engine?.attachStudioPreview(surface, w, h) }
    fun detachStudioPreview(surface: Surface?) { engine?.detachStudioPreview(surface) }
    fun restartVideo(sourceId: String) { engine?.restartVideo(sourceId) }

    private fun bringStudioToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun startMetricsTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                if (streamStartTime > 0) {
                    val durationSec = (System.currentTimeMillis() - streamStartTime) / 1000
                    val minutes = durationSec / 60
                    val seconds = durationSec % 60
                    val durationStr = String.format("%02d:%02d", minutes, seconds)

                    // Perpanjang WakeLock (timeout pendek supaya tidak menggantung bila service mati mendadak)
                    if (durationSec % WAKELOCK_RENEW_EVERY_S == 0L) acquireWakeLock()
                    // Offline terlalu lama: berhenti dengan pesan jelas, jangan menahan WakeLock selamanya
                    if (_studioState.value.status == StreamingStatus.RECONNECTING && !networkOnline) {
                        offlineSeconds++
                        if (offlineSeconds > MAX_OFFLINE_WAIT_S) {
                            _studioState.value = _studioState.value.copy(
                                errorMessage = "Tidak ada internet lebih dari ${MAX_OFFLINE_WAIT_S / 60} menit. Siaran dihentikan."
                            )
                            stopStreaming()
                            return@launch
                        }
                    } else offlineSeconds = 0
                    val fps = if (engine?.isLive == true && !isStreamPaused) (engine?.currentFps ?: 0) else 0
                    val counters = engine?.counters()
                    val bitrateKbps = (measuredBitrateBps / 1000).toInt()
                    val currentStats = DiagnosticStats(
                        status = _studioState.value.status,
                        virtualDisplayActive = engine?.isLive == true,
                        fps = fps,
                        bitrateKbps = bitrateKbps,
                        droppedFrames = counters?.droppedVideoFrames ?: 0,
                        totalFrames = counters?.sentVideoFrames ?: 0,
                        bytesSent = counters?.bytesSent ?: 0,
                        reconnectCount = reconnectCount,
                        durationSeconds = durationSec,
                        thermalStatus = getThermalStatusString(),
                        thermalLevel = thermalLevel,
                        networkStatus = networkLabel,
                        wakeLockHeld = wakeLock?.isHeld == true,
                        destinationUrl = sanitizeUrl(currentEndpoint),
                        resolution = "${targetWidth}x${targetHeight} @ ${targetFps}fps"
                    )

                    _studioState.value = _studioState.value.copy(stats = currentStats)
                    // Tersambung tapi 0 frame video setelah 10 detik: beri tahu pengguna (bukan diam saja)
                    if (!videoStallWarned && _studioState.value.status == StreamingStatus.LIVE &&
                        !isStreamPaused && durationSec >= 10 && (counters?.sentVideoFrames ?: 0L) == 0L
                    ) {
                        videoStallWarned = true
                        showToast("Tersambung, tetapi video belum terkirim ke server. Coba End Live lalu GO LIVE lagi.")
                    }
                    updateNotification(
                        "${statusLabel(_studioState.value.status)} · $durationStr · ${measuredBitrateBps / 1000} kbps · $networkLabel"
                    )
                    floatingOverlay?.updateStatus(
                        status = _studioState.value.status,
                        durationStr = durationStr,
                        fps = fps,
                        bitrateKbps = (measuredBitrateBps / 1000).toInt(),
                        isPaused = isStreamPaused,
                        isMicMuted = _studioState.value.isMicMuted
                    )
                }
            }
        }
    }

    /** Pause = layar hitam + audio senyap (siaran tetap tersambung, seperti "BRB"). */
    fun togglePause() {
        val eng = engine ?: return
        // Hanya saat benar-benar LIVE/PAUSED. engine.isLive sudah true sebelum onConnectionSuccess,
        // jadi tanpa guard ini Pause/Resume bisa memalsukan status LIVE saat masih CONNECTING.
        val st = _studioState.value.status
        if (!eng.isLive || (st != StreamingStatus.LIVE && st != StreamingStatus.PAUSED)) return
        isStreamPaused = !isStreamPaused
        eng.setPaused(isStreamPaused)
        _studioState.value = _studioState.value.copy(
            status = if (isStreamPaused) StreamingStatus.PAUSED else StreamingStatus.LIVE
        )
    }

    fun toggleMic() = setMicMuted(!_studioState.value.isMicMuted)

    fun setMicMuted(muted: Boolean) {
        StreamAudioParams.micMuted = muted
        _studioState.value = _studioState.value.copy(isMicMuted = muted)
    }

    // Freeze-Frame Editing (§4.5.2)
    fun setPreviewFrozen(frozen: Boolean) {
        _studioState.value = _studioState.value.copy(isPreviewFrozen = frozen)
    }

    fun setFrozenSnapshot(bitmap: Bitmap?) {
        _studioState.value = _studioState.value.copy(
            isPreviewFrozen = true,
            frozenSnapshot = bitmap
        )
    }

    fun resumePreview() {
        _studioState.value = _studioState.value.copy(
            isPreviewFrozen = false,
            frozenSnapshot = null
        )
    }

    fun stopStreaming() {
        if (stopping) return
        stopping = true
        userRequestedStop = true   // berhenti disengaja: tidak boleh ada reconnect otomatis
        _studioState.value = _studioState.value.copy(status = StreamingStatus.STOPPING)
        releaseWakeLock()
        tickerJob?.cancel()
        tickerJob = null
        floatingOverlay?.hide()

        try {
            engine?.stopLive()
            engine?.release()
            engine?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping engine: ${e.message}")
        }
        engine = null

        StreamAudioParams.paused = false
        streamStartTime = 0
        isStreamPaused = false
        measuredBitrateBps = 0
        reconnectCount = 0
        _studioState.value = _studioState.value.copy(
            status = StreamingStatus.OFFLINE,
            stats = DiagnosticStats(),
            isPreviewFrozen = false,
            frozenSnapshot = null,
            isEditorMode = false,
            captureAspect = 0f,
            // Kegagalan preview kamera dipertahankan agar UI menampilkan sebabnya (dan tidak mencoba ulang tanpa henti)
            cameraPreview = if (_studioState.value.cameraPreview == CameraPreviewState.ERROR) CameraPreviewState.ERROR else CameraPreviewState.OFF,
            cameraStatus = if (_studioState.value.cameraPreview == CameraPreviewState.ERROR) _studioState.value.cameraStatus else null,
            hasProjection = false,
            serviceRunning = false
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun statusLabel(st: StreamingStatus) = when (st) {
        StreamingStatus.LIVE -> "LIVE"
        StreamingStatus.CONNECTING -> "Connecting"
        StreamingStatus.RECONNECTING -> "Reconnecting"
        StreamingStatus.PAUSED -> "Paused (BRB)"
        StreamingStatus.ERROR -> "Error"
        StreamingStatus.PREVIEW -> "Ready"
        StreamingStatus.PREPARING -> "Preparing"
        StreamingStatus.STOPPING -> "Stopping"
        StreamingStatus.OFFLINE -> "Offline"
    }

    private fun getThermalStatusString(): String {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (pm?.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Cool (Optimal)"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light Throttle"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate Throttle"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe Throttle"
                else -> "Normal"
            }
        } else "Normal"
    }

    private fun sanitizeUrl(url: String): String {
        // Obfuscate stream key from URL
        val lastSlash = url.lastIndexOf('/')
        return if (lastSlash > 0 && lastSlash < url.length - 1) {
            url.substring(0, lastSlash + 1) + "********"
        } else url
    }

    // ConnectChecker Implementation
    override fun onConnectionStarted(url: String) {
        Log.i(TAG, "RTMP connection started to ${sanitizeUrl(url)}")
        // Callback dapat tiba terlambat ketika pengguna sudah menekan Stop.
        if (userRequestedStop || stopping || _studioState.value.status == StreamingStatus.STOPPING) return
        _studioState.value = _studioState.value.copy(status = StreamingStatus.CONNECTING)
    }

    override fun onConnectionSuccess() {
        // Jangan menghidupkan kembali status LIVE akibat callback yang datang setelah Stop.
        if (userRequestedStop || stopping || _studioState.value.status == StreamingStatus.STOPPING ||
            _studioState.value.status == StreamingStatus.OFFLINE) {
            Log.i(TAG, "Ignoring late RTMP success callback after stop")
            return
        }
        Log.i(TAG, "RTMP connection successfully established")
        _studioState.value = _studioState.value.copy(
            status = StreamingStatus.LIVE,
            errorMessage = null
        )
        showToast("LIVE: tersambung ke server RTMP")
    }

    override fun onNewBitrate(bitrate: Long) {
        measuredBitrateBps = bitrate
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "RTMP connection failed: $reason")
        if (userRequestedStop || stopping) return
        if (!networkOnline) {
            // Tidak ada internet: jangan menghabiskan jatah percobaan. Callback jaringan memicu retry saat kembali.
            _studioState.value = _studioState.value.copy(
                status = StreamingStatus.RECONNECTING,
                errorMessage = "Internet terputus. Menunggu jaringan kembali…"
            )
            return
        }
        // reTry() menjadwalkan sambung ulang; false kalau jatah percobaan habis
        retriesUsed++
        val willRetry = retriesUsed <= reconnectMaxRetries && engine?.retry(reconnectIntervalMs, reason) == true
        if (willRetry) {
            reconnectCount++
            _studioState.value = _studioState.value.copy(
                status = StreamingStatus.RECONNECTING,
                errorMessage = "Koneksi terputus: $reason. Mencoba menyambung ulang..."
            )
        } else {
            _studioState.value = _studioState.value.copy(
                status = StreamingStatus.ERROR,
                errorMessage = "Koneksi gagal: $reason"
            )
            showToast("Koneksi RTMP gagal: $reason")
            serviceScope.launch { stopStreaming() }
        }
    }

    override fun onDisconnect() {
        Log.i(TAG, "RTMP disconnected")
        if (_studioState.value.status == StreamingStatus.LIVE) {
            _studioState.value = _studioState.value.copy(status = StreamingStatus.RECONNECTING)
        }
    }

    override fun onAuthError() {
        Log.e(TAG, "RTMP authentication failed - invalid stream key or credentials")
        _studioState.value = _studioState.value.copy(
            status = StreamingStatus.ERROR,
            errorMessage = "RTMP Authentication failed. Check your Server URL & Stream Key."
        )
        showToast("RTMP ditolak server. Periksa Server URL dan Stream Key.")
        serviceScope.launch { stopStreaming() }
    }

    override fun onAuthSuccess() {
        Log.i(TAG, "RTMP auth successful")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
        stopMonitors()
        releaseWakeLock()
        activeServiceInstance = null
    }
}
