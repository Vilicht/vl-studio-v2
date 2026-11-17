package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/** Status jujur sumber audio game untuk ditampilkan di UI. */
enum class GameAudioStatus { NOT_LIVE, ACTIVE, NO_SIGNAL, UNAVAILABLE_ANDROID, UNAVAILABLE }

/**
 * Meter audio. Semua nilai berasal dari sinyal nyata:
 *  - Saat siaran berjalan (engine audio aktif): level mic dan game dibaca dari StreamAudioParams,
 *    yaitu level PCM yang benar-benar dikirim ke encoder. UI TIDAK membuka AudioRecord kedua.
 *  - Saat belum live: hanya level mic dari AudioRecord sendiri. Level game = 0 dan statusnya
 *    NOT_LIVE, karena audio game hanya bisa ditangkap lewat AudioPlaybackCapture yang butuh MediaProjection
 *    dan dijalankan engine saat live. Level game TIDAK PERNAH ditebak dari volume mic.
 */
class AudioMixerController(private val context: Context) {
    private val TAG = "VilichtAudioMixer"

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()
    private val _systemLevel = MutableStateFlow(0f)
    val systemLevel: StateFlow<Float> = _systemLevel.asStateFlow()
    private val _gameStatus = MutableStateFlow(GameAudioStatus.NOT_LIVE)
    val gameStatus: StateFlow<GameAudioStatus> = _gameStatus.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var micGain: Float = 1.0f
    /** Delegasi ke sumber tunggal; JANGAN simpan salinan lokal. */
    var isMicMuted: Boolean
        get() = StreamAudioParams.micMuted
        set(v) { StreamAudioParams.micMuted = v }
    var systemVolume: Float = 0.8f
    var isSystemMuted: Boolean = false

    private var lastSignalAt = 0L

    /** Mulai/ulangi pemantauan. Aman dipanggil berkali-kali; otomatis memilih sumber yang tidak bentrok. */
    @SuppressLint("MissingPermission")
    fun startMonitoring() {
        job?.cancel()
        job = scope.launch {
            try {
                while (isActive) {
                    if (StreamAudioParams.engineAudioRunning) {
                        releaseOwnRecord()           // jangan ada dua capture mic bersamaan
                        pollEngineLevels()
                        delay(50)
                    } else {
                        monitorMicOnly()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Audio monitoring error: ${e.message}")
            } finally {
                releaseOwnRecord()
                _micLevel.value = 0f
                _systemLevel.value = 0f
            }
        }
    }

    private fun pollEngineLevels() {
        _micLevel.value = if (isMicMuted || !StreamAudioParams.micAvailable) 0f else StreamAudioParams.micLevel
        val game = StreamAudioParams.systemLevel
        _systemLevel.value = if (isSystemMuted) 0f else game
        val now = System.currentTimeMillis()
        if (game > 1f) lastSignalAt = now
        _gameStatus.value = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> GameAudioStatus.UNAVAILABLE_ANDROID
            !StreamAudioParams.gameCaptureAvailable -> GameAudioStatus.UNAVAILABLE
            now - lastSignalAt > 5000 -> GameAudioStatus.NO_SIGNAL
            else -> GameAudioStatus.ACTIVE
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun monitorMicOnly() {
        _systemLevel.value = 0f
        _gameStatus.value = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            GameAudioStatus.UNAVAILABLE_ANDROID else GameAudioStatus.NOT_LIVE
        val rate = 44100
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = (min * 2).coerceAtLeast(2048)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            _micLevel.value = 0f
            delay(1000) // izin mic belum ada / mic dipakai aplikasi lain: coba lagi nanti
            return
        }
        audioRecord = rec
        try {
            rec.startRecording()
            val buf = ShortArray(size / 2)
            while (currentCoroutineContext().isActive && !StreamAudioParams.engineAudioRunning) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    if (isMicMuted) _micLevel.value = 0f else {
                        var sum = 0.0
                        for (i in 0 until n) { val s = buf[i] * micGain; sum += s * s }
                        val norm = ((sqrt(sum / n) / 12000.0) * 100.0).coerceIn(0.0, 100.0).toFloat()
                        _micLevel.value = _micLevel.value * 0.4f + norm * 0.6f
                    }
                } else if (n < 0) break
                // Satu sumber level mic untuk semua konsumen (PNGTuber dsb.); saat engine berjalan ia ditulis GameMicAudioSource
                StreamAudioParams.micLevel = _micLevel.value
                delay(35)
            }
        } finally {
            releaseOwnRecord()
        }
    }

    private fun releaseOwnRecord() {
        if (!StreamAudioParams.engineAudioRunning) StreamAudioParams.micLevel = 0f
        val r = audioRecord ?: return
        audioRecord = null
        try { r.stop() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
    }

    fun stopMonitoring() {
        job?.cancel()
        job = null
        releaseOwnRecord()
        _micLevel.value = 0f
        _systemLevel.value = 0f
    }
}
