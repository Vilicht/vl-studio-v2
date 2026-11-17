package com.example.service

import android.graphics.Bitmap
import com.example.engine.GameSourceState

enum class StreamingStatus {
    OFFLINE,
    /** Encoder/audio sedang disiapkan, belum membuka koneksi RTMP. */
    PREPARING,
    CONNECTING,
    LIVE,
    PAUSED,
    RECONNECTING,
    ERROR,
    /** Stop Live sedang membersihkan pipeline (encoder, AudioRecord, RTMP). */
    STOPPING,
    PREVIEW
}

/** Status preview kamera mandiri (editor tanpa siaran). */
enum class CameraPreviewState { OFF, STARTING, ACTIVE, ERROR }

data class DiagnosticStats(
    val status: StreamingStatus = StreamingStatus.OFFLINE,
    val virtualDisplayActive: Boolean = false,
    /** FPS render nyata dari compositor/encoder (FpsListener RootEncoder). */
    val fps: Int = 0,
    /** Bitrate keluaran nyata (kbps), diukur dari byte yang terkirim, bukan nilai konfigurasi. */
    val bitrateKbps: Int = 0,
    /** Frame video yang dibuang klien RTMP karena antrean penuh/jaringan lambat (nilai nyata). */
    val droppedFrames: Long = 0,
    /** Frame video yang sudah terkirim (nilai nyata). */
    val totalFrames: Long = 0,
    val bytesSent: Long = 0,
    val reconnectCount: Int = 0,
    val durationSeconds: Long = 0,
    val thermalStatus: String = "Normal",
    /** Status termal Android mentah (PowerManager.THERMAL_STATUS_*), -1 = tidak tersedia (Android < 10). */
    val thermalLevel: Int = -1,
    /** Status jaringan nyata dari ConnectivityManager. */
    val networkStatus: String = "Unknown",
    /** true saat WakeLock parsial sedang dipegang service. */
    val wakeLockHeld: Boolean = false,
    val destinationUrl: String = "",
    val resolution: String = "1280x720 @ 30fps"
)

data class LiveStudioState(
    val status: StreamingStatus = StreamingStatus.OFFLINE,
    val isMicMuted: Boolean = false,
    val isPreviewFrozen: Boolean = false,
    val frozenSnapshot: Bitmap? = null,
    val errorMessage: String? = null,
    /** true saat Studio terbuka: layer game dibekukan di siaran (kamera & overlay tetap hidup). */
    val isEditorMode: Boolean = false,
    /** Rasio lebar/tinggi layar yang di-capture (0 = belum diketahui). */
    val captureAspect: Float = 0f,
    val stats: DiagnosticStats = DiagnosticStats(),
    /** Preview kamera mandiri: berjalan tanpa Go Live dan tanpa MediaProjection. */
    val cameraPreview: CameraPreviewState = CameraPreviewState.OFF,
    /** Pesan masalah kamera (izin, terputus, tidak ada kamera). null = kamera normal. */
    val cameraStatus: String? = null,
    /** true bila MediaProjection (capture layar) sudah terpasang di engine. */
    val hasProjection: Boolean = false,
    /** true bila service foreground sedang berjalan. */
    val serviceRunning: Boolean = false,
    /** Kesehatan sumber game (frame, sesi capture). Dipakai banner Studio dan floating window. */
    val gameSource: GameSourceState = GameSourceState.INACTIVE
)
