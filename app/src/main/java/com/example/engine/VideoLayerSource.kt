package com.example.engine

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface

/**
 * Video (file lokal) -> SurfaceTexture milik layer GL, diputar berulang (loop).
 * Suara video SENGAJA dibisukan: audio video belum dicampur ke mixer siaran, dan tanpa dibisukan
 * ia hanya akan terdengar di speaker perangkat, bukan di siaran.
 * Harus dibuat dan dihentikan dari Main Thread; stop() dipanggil SEBELUM SurfaceTexture dilepas.
 */
class VideoLayerSource(
    @Suppress("unused") private val context: Context,
    private val onSize: (Int, Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private val tag = "VilichtVideo"
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var paused = false
    private var sound = false

    /**
     * Suara video. Diputar lewat USAGE_MEDIA dengan kebijakan capture terbuka sehingga bisa ikut tertangkap
     * oleh AudioPlaybackCapture milik mixer siaran (hanya aktif bila izin capture layar diberikan).
     * Suara juga terdengar di speaker perangkat; bisa terambil mikrofon (gema) kecuali memakai headset.
     */
    fun setSound(on: Boolean) {
        sound = on
        try { player?.setVolume(if (on) 1f else 0f, if (on) 1f else 0f) } catch (_: Exception) {}
    }

    /** Jeda / putar. Aman dipanggil kapan saja; dipakai ulang tiap place() sehingga harus idempoten. */
    fun setPaused(p: Boolean) {
        paused = p
        val mp = player ?: return
        if (!prepared) return
        try {
            if (p && mp.isPlaying) mp.pause()
            else if (!p && !mp.isPlaying) mp.start()
        } catch (_: Exception) {}
    }

    /** Kembali ke awal video (status jeda/putar tidak berubah). */
    fun restart() {
        val mp = player ?: return
        if (!prepared) return
        try { mp.seekTo(0) } catch (_: Exception) {}
    }

    fun start(st: SurfaceTexture, path: String, startPaused: Boolean = false, withSound: Boolean = false) {
        stop()
        paused = startPaused
        sound = withSound
        try {
            val s = Surface(st)
            surface = s
            val mp = MediaPlayer()
            player = mp
            mp.setSurface(s)
            mp.setDataSource(path)
            mp.isLooping = true
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .apply {
                    if (android.os.Build.VERSION.SDK_INT >= 29) setAllowedCapturePolicy(android.media.AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                }.build()
            mp.setAudioAttributes(attrs)
            mp.setVolume(if (sound) 1f else 0f, if (sound) 1f else 0f)
            mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) onSize(w, h) }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(tag, "MediaPlayer error $what/$extra")
                onError("Video tidak bisa diputar (kode $what/$extra).")
                true
            }
            mp.setOnPreparedListener {
                prepared = true
                // Tampilkan frame pertama lalu jeda bila diminta; jika tidak langsung putar
                if (paused) { try { it.seekTo(0) } catch (_: Exception) {} } else it.start()
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(tag, "start gagal: ${e.message}", e)
            onError("Video gagal dibuka: ${e.localizedMessage}")
            stop()
        }
    }

    fun stop() {
        val mp = player
        player = null
        prepared = false
        if (mp != null) {
            try { mp.setOnPreparedListener(null) } catch (_: Exception) {}
            try { mp.setOnErrorListener(null) } catch (_: Exception) {}
            try { mp.stop() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
        }
        try { surface?.release() } catch (_: Exception) {}
        surface = null
    }
}
