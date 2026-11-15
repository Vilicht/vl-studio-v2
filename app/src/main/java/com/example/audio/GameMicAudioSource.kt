package com.example.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import kotlin.math.sqrt

/** Parameter audio live. Diubah dari UI (mixer) dan floating overlay, dibaca thread audio. */
object StreamAudioParams {
    @Volatile var micGain: Float = 1.0f
    @Volatile var micMuted: Boolean = false
    @Volatile var systemVolume: Float = 0.8f
    @Volatile var paused: Boolean = false
    /** Level 0..100 untuk meter UI saat live. Dihitung dari sampel PCM yang benar-benar dikirim ke encoder. */
    @Volatile var micLevel: Float = 0f
    @Volatile var systemLevel: Float = 0f

    /** true selama GameMicAudioSource berjalan (live). Selama true, UI tidak boleh membuka AudioRecord mic sendiri. */
    @Volatile var engineAudioRunning: Boolean = false
    /** true kalau AudioPlaybackCapture berhasil dibuat (Android 10+ dan izin MediaProjection). */
    @Volatile var gameCaptureAvailable: Boolean = false
    /** true kalau mic berhasil dibuka. */
    @Volatile var micAvailable: Boolean = false

    /** ID AudioDeviceInfo input pilihan pengguna. 0 = otomatis (sistem yang memilih). */
    @Volatile var preferredMicDeviceId: Int = 0
    /** Dinaikkan setiap routing mic berubah; thread audio memeriksanya dan menerapkan ulang. */
    @Volatile var micRoutingVersion: Int = 0
}

/**
 * Sumber audio buatan sendiri untuk encoder: audio game (AudioPlaybackCapture, Android 10+)
 * dicampur dengan mic, dengan volume dan mute mic terpisah. Output: PCM 16-bit stereo.
 */
class GameMicAudioSource(
    projection: MediaProjection?,
    private val appContext: Context? = null
) : AudioSource() {
    private val tag = "VilichtAudioSource"

    /**
     * Token MediaProjection. Bisa baru tersedia setelah engine berjalan dalam mode kamera-saja
     * (preview offline); harus diset SEBELUM audio live dimulai.
     */
    @Volatile var projection: MediaProjection? = projection

    @Volatile private var running = false
    private var worker: Thread? = null
    private var micRecord: AudioRecord? = null
    private var gameRecord: AudioRecord? = null

    override fun create(
        sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean
    ): Boolean {
        return AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ) > 0
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        this.getMicrophoneData = getMicrophoneData
        if (running) return
        val rate = sampleRate
        micRecord = buildMic(rate)
        gameRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) buildGame(rate) else null
        if (micRecord == null && gameRecord == null) {
            // Jangan gagalkan siaran: kirim audio senyap supaya server tetap menerima trek audio yang valid
            Log.w(tag, "Tidak ada sumber audio (mic ditolak, audio game tidak tersedia): audio senyap")
        }
        applyMicRouting()
        micRecord?.startRecording()
        gameRecord?.startRecording()
        StreamAudioParams.micAvailable = micRecord != null
        StreamAudioParams.gameCaptureAvailable = gameRecord != null
        StreamAudioParams.engineAudioRunning = true
        running = true
        worker = Thread({ mixLoop() }, "VilichtAudioMix").also { it.start() }
    }

    override fun stop() {
        running = false
        try { worker?.join(500) } catch (_: InterruptedException) {}
        worker = null
        listOf(micRecord, gameRecord).forEach {
            try { it?.stop() } catch (_: Exception) {}
            try { it?.release() } catch (_: Exception) {}
        }
        micRecord = null
        gameRecord = null
        getMicrophoneData = null
        StreamAudioParams.micLevel = 0f
        StreamAudioParams.systemLevel = 0f
        StreamAudioParams.engineAudioRunning = false
        StreamAudioParams.gameCaptureAvailable = false
        StreamAudioParams.micAvailable = false
    }

    override fun isRunning(): Boolean = running

    override fun release() {}

    @SuppressLint("MissingPermission")
    private fun buildMic(rate: Int): AudioRecord? = try {
        val min = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 4, 8192)
        )
        if (rec.state == AudioRecord.STATE_INITIALIZED) rec else { rec.release(); null }
    } catch (e: Exception) {
        Log.e(tag, "Mic init failed: ${e.message}")
        null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun buildGame(rate: Int): AudioRecord? {
        val mp = projection ?: return null
        return try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val min = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(min * 4, 16384))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (rec.state == AudioRecord.STATE_INITIALIZED) rec else { rec.release(); null }
        } catch (e: Exception) {
            Log.e(tag, "Game audio capture init failed: ${e.message}")
            null
        }
    }

    /** Terapkan perangkat input pilihan (headset kabel/USB/Bluetooth). Perangkat hilang -> sistem memilih sendiri. */
    private fun applyMicRouting() {
        val rec = micRecord ?: return
        val ctx = appContext ?: return
        try {
            val id = StreamAudioParams.preferredMicDeviceId
            if (id == 0) {
                rec.preferredDevice = null
                return
            }
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val dev: AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
            if (dev == null) {
                // Perangkat sudah dicabut: kembali ke otomatis, siaran jalan terus
                StreamAudioParams.preferredMicDeviceId = 0
                rec.preferredDevice = null
            } else if (!rec.setPreferredDevice(dev)) {
                Log.w(tag, "setPreferredDevice ditolak untuk ${dev.productName}")
            }
        } catch (e: Exception) {
            Log.w(tag, "applyMicRouting gagal: ${e.message}")
        }
    }

    /** AudioRecord mic mati (mis. perangkat USB dicabut mendadak): buat ulang tanpa menghentikan siaran. */
    private fun rebuildMic() {
        try { micRecord?.stop() } catch (_: Exception) {}
        try { micRecord?.release() } catch (_: Exception) {}
        micRecord = buildMic(sampleRate)
        applyMicRouting()
        try { micRecord?.startRecording() } catch (e: Exception) { Log.w(tag, "restart mic gagal: ${e.message}") }
        StreamAudioParams.micAvailable = micRecord != null
    }

    private fun readFully(r: AudioRecord, b: ByteArray, size: Int): Int {
        var off = 0
        while (running && off < size) {
            val n = r.read(b, off, size - off)
            if (n < 0) return if (off > 0) off else n
            if (n == 0) { try { Thread.sleep(2) } catch (_: InterruptedException) { return off }; continue }
            off += n
        }
        return off
    }

    private fun mixLoop() {
        val frames = 1024
        val gameBuf = ByteArray(frames * 4) // stereo 16-bit
        val micBuf = ByteArray(frames * 2)  // mono 16-bit
        val game = gameRecord
        var routingSeen = StreamAudioParams.micRoutingVersion

        while (running) {
            if (micRecord == null && game == null) {
                // Tanpa sumber audio: kirim bingkai senyap dengan kecepatan real-time
                getMicrophoneData?.inputPCMData(Frame(ByteArray(frames * 4), 0, frames * 4, System.nanoTime() / 1000))
                try { Thread.sleep(frames * 1000L / sampleRate.coerceAtLeast(8000)) } catch (_: InterruptedException) { break }
                continue
            }
            if (routingSeen != StreamAudioParams.micRoutingVersion) {
                routingSeen = StreamAudioParams.micRoutingVersion
                applyMicRouting()
            }
            val mic = micRecord
            var gameRead = 0
            var micRead = 0
            if (game != null) gameRead = readFully(game, gameBuf, gameBuf.size)
            if (mic != null) {
                micRead = readFully(mic, micBuf, micBuf.size)
                if (micRead < 0 && running) {
                    // ERROR_DEAD_OBJECT dsb: pulihkan mic, jangan spin dan jangan hentikan siaran
                    try { Thread.sleep(200) } catch (_: InterruptedException) { break }
                    rebuildMic()
                    micRead = 0
                }
            }
            if (!running) break
            if (game != null && gameRead < gameBuf.size) java.util.Arrays.fill(gameBuf, maxOf(gameRead, 0), gameBuf.size, 0)
            if (game == null) java.util.Arrays.fill(gameBuf, 0)
            if (mic != null && micRead < micBuf.size) java.util.Arrays.fill(micBuf, maxOf(micRead, 0), micBuf.size, 0)
            if (game == null && micRead <= 0) { try { Thread.sleep(5) } catch (_: InterruptedException) {}; continue }

            val paused = StreamAudioParams.paused
            val sysVol = StreamAudioParams.systemVolume.coerceIn(0f, 1.5f)
            val micGain = if (StreamAudioParams.micMuted) 0f else StreamAudioParams.micGain.coerceIn(0f, 2f)

            val out = ByteArray(frames * 4)
            var sumMic = 0.0
            var sumGame = 0.0
            for (i in 0 until frames) {
                val gl = readShort(gameBuf, i * 4).toFloat()
                val gr = readShort(gameBuf, i * 4 + 2).toFloat()
                val m = if (mic != null) readShort(micBuf, i * 2).toFloat() else 0f
                sumMic += (m * m).toDouble()
                sumGame += ((gl * gl + gr * gr) / 2f).toDouble()
                if (!paused) {
                    writeShort(out, i * 4, clamp(gl * sysVol + m * micGain))
                    writeShort(out, i * 4 + 2, clamp(gr * sysVol + m * micGain))
                }
            }
            StreamAudioParams.micLevel = level(sumMic, frames)
            StreamAudioParams.systemLevel = level(sumGame, frames)

            getMicrophoneData?.inputPCMData(Frame(out, 0, out.size, System.nanoTime() / 1000))
        }
    }

    private fun level(sumSq: Double, n: Int): Float =
        ((sqrt(sumSq / n) / 12000.0) * 100.0).coerceIn(0.0, 100.0).toFloat()

    private fun readShort(b: ByteArray, i: Int): Int =
        ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()

    private fun writeShort(b: ByteArray, i: Int, v: Int) {
        b[i] = (v and 0xFF).toByte()
        b[i + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun clamp(v: Float): Int = v.toInt().coerceIn(-32768, 32767)
}
