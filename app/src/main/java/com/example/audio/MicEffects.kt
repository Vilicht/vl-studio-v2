package com.example.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/** Efek mikrofon yang disediakan perangkat ini. Tidak semua perangkat menyediakan semuanya. */
data class MicEffectSupport(
    val noiseSuppression: Boolean,
    val echoCancel: Boolean,
    val autoGain: Boolean
)

/**
 * Pemroses mikrofon bawaan platform (NoiseSuppressor, AcousticEchoCanceler, AutomaticGainControl).
 *
 * Efek HANYA dibuat saat diminta lewat [StreamAudioParams] dan dilepas saat dimatikan, sehingga dengan semua
 * toggle mati suara mikrofon tidak tersentuh sama sekali. Hanya memproses trek MIKROFON; audio game
 * (AudioPlaybackCapture) tidak melewati efek ini.
 *
 * Bukan thread-safe: pakai dari satu thread (thread mix audio), sama seperti AudioRecord miliknya.
 */
class MicEffects {
    private var noise: NoiseSuppressor? = null
    private var echo: AcousticEchoCanceler? = null
    private var gain: AutomaticGainControl? = null

    /** Sesuaikan efek aktif dengan [StreamAudioParams] untuk sesi audio [audioSessionId]. */
    fun update(audioSessionId: Int) {
        noise = sync(noise, StreamAudioParams.noiseSuppression, NoiseSuppressor.isAvailable(), "NoiseSuppressor") {
            NoiseSuppressor.create(audioSessionId)
        }
        echo = sync(echo, StreamAudioParams.echoCancel, AcousticEchoCanceler.isAvailable(), "AcousticEchoCanceler") {
            AcousticEchoCanceler.create(audioSessionId)
        }
        gain = sync(gain, StreamAudioParams.autoGain, AutomaticGainControl.isAvailable(), "AutomaticGainControl") {
            AutomaticGainControl.create(audioSessionId)
        }
    }

    /** Lepas semua efek. Wajib dipanggil sebelum AudioRecord-nya dilepas. Aman dipanggil berkali-kali. */
    fun release() {
        noise = releaseQuietly(noise)
        echo = releaseQuietly(echo)
        gain = releaseQuietly(gain)
    }

    private fun <T : AudioEffect> sync(
        current: T?, wanted: Boolean, available: Boolean, name: String, create: () -> T?
    ): T? {
        if (!wanted || !available) return releaseQuietly(current)
        if (current != null) return current
        val effect = try { create() } catch (e: Exception) {
            Log.w(TAG, "$name gagal dibuat: ${e.message}")
            null
        } ?: return null
        val status = try { effect.setEnabled(true) } catch (e: Exception) { AudioEffect.ERROR }
        if (status != AudioEffect.SUCCESS) {
            Log.w(TAG, "$name gagal diaktifkan (status=$status)")
            return releaseQuietly(effect)
        }
        return effect
    }

    private fun <T : AudioEffect> releaseQuietly(effect: T?): T? {
        if (effect != null) try { effect.release() } catch (_: Exception) {}
        return null
    }

    companion object {
        private const val TAG = "VilichtMicFx"

        /** Efek yang didukung perangkat ini; aman dipanggil dari UI kapan saja (tidak membuka mikrofon). */
        fun support(): MicEffectSupport = MicEffectSupport(
            noiseSuppression = try { NoiseSuppressor.isAvailable() } catch (_: Exception) { false },
            echoCancel = try { AcousticEchoCanceler.isAvailable() } catch (_: Exception) { false },
            autoGain = try { AutomaticGainControl.isAvailable() } catch (_: Exception) { false }
        )
    }
}
