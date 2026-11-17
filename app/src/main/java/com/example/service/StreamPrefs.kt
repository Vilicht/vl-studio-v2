package com.example.service

import android.content.Context
import com.example.audio.StreamAudioParams

/**
 * Pengaturan siaran tambahan: bitrate adaptif dan efek mikrofon.
 *
 * Sumber kebenaran = SharedPreferences. Nilai didorong ke objek runtime ([StreamAudioParams],
 * [StreamingService.adaptiveBitrateEnabled]) oleh [applyAll], yang dipanggil ViewModel DAN service saat
 * dibuat, sehingga pengaturan tetap berlaku walau service dihidupkan tanpa UI. Semua MATI secara default:
 * perilaku siaran dan suara mikrofon tidak berubah sampai pengguna menyalakannya sendiri.
 */
object StreamPrefs {
    private const val FILE = "vl_stream_extras"
    private const val KEY_ABR = "adaptive_bitrate"
    private const val KEY_NS = "mic_noise_suppression"
    private const val KEY_AEC = "mic_echo_cancel"
    private const val KEY_AGC = "mic_auto_gain"

    data class Snapshot(
        val adaptiveBitrate: Boolean,
        val noiseSuppression: Boolean,
        val echoCancel: Boolean,
        val autoGain: Boolean
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            adaptiveBitrate = p.getBoolean(KEY_ABR, false),
            noiseSuppression = p.getBoolean(KEY_NS, false),
            echoCancel = p.getBoolean(KEY_AEC, false),
            autoGain = p.getBoolean(KEY_AGC, false)
        )
    }

    /** Dorong pengaturan tersimpan ke objek runtime. */
    fun applyAll(context: Context): Snapshot {
        val s = read(context)
        StreamingService.adaptiveBitrateEnabled = s.adaptiveBitrate
        StreamAudioParams.noiseSuppression = s.noiseSuppression
        StreamAudioParams.echoCancel = s.echoCancel
        StreamAudioParams.autoGain = s.autoGain
        StreamAudioParams.micEffectsVersion++
        return s
    }

    fun setAdaptiveBitrate(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ABR, enabled).apply()
        StreamingService.adaptiveBitrateEnabled = enabled
    }

    fun setMicEffects(context: Context, noiseSuppression: Boolean, echoCancel: Boolean, autoGain: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_NS, noiseSuppression)
            .putBoolean(KEY_AEC, echoCancel)
            .putBoolean(KEY_AGC, autoGain)
            .apply()
        StreamAudioParams.noiseSuppression = noiseSuppression
        StreamAudioParams.echoCancel = echoCancel
        StreamAudioParams.autoGain = autoGain
        StreamAudioParams.micEffectsVersion++
    }
}
