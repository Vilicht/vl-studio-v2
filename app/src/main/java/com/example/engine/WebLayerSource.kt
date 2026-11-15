package com.example.engine

import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface
import android.view.View

/**
 * Menggambar WebView (yang sudah ter-attach di jendela overlay luar layar) ke SurfaceTexture milik layer GL
 * secara berkala. Dengan jalur ini Browser Overlay memakai shader LayerFilterRender yang SAMA dengan kamera,
 * video, dan gambar: posisi, ukuran, rotasi, mirror, crop, fit, zoom, opacity, dan chroma key berlaku sama.
 *
 * Semua fungsi dipanggil dari Main Thread. [stop] harus dipanggil SEBELUM SurfaceTexture dilepas.
 * Catatan: hasil gambar canvas memakai alpha premultiplied; tepi semi-transparan bisa sedikit gelap.
 */
class WebLayerSource(
    private val main: Handler,
    private val view: View,
    private val fps: Int = 24
) {
    private val tag = "VilichtWebLayer"
    private var surface: Surface? = null
    private var running = false
    private val intervalMs = (1000L / fps.coerceIn(5, 60))

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            drawOnce()
            main.postDelayed(this, intervalMs)
        }
    }

    /** @return lebar/tinggi buffer yang dipakai (ukuran nyata WebView). */
    fun start(st: SurfaceTexture): Pair<Int, Int>? {
        stop()
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        st.setDefaultBufferSize(w, h)
        surface = Surface(st)
        running = true
        main.post(tick)
        return w to h
    }

    private fun drawOnce() {
        val s = surface ?: return
        if (!s.isValid || view.width <= 0 || view.height <= 0) return
        try {
            val c = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) s.lockHardwareCanvas() else s.lockCanvas(null)
            try {
                c.drawColor(0, PorterDuff.Mode.CLEAR)
                view.draw(c)
            } finally {
                s.unlockCanvasAndPost(c)
            }
        } catch (e: Exception) {
            Log.w(tag, "gambar WebView gagal: ${e.message}")
        }
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        try { surface?.release() } catch (_: Exception) {}
        surface = null
    }
}
