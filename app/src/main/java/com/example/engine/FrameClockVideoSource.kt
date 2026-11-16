package com.example.engine

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.pedro.encoder.input.sources.video.VideoSource

/**
 * Sumber video pengganti NoVideoSource.
 *
 * Mesin ini menggambar semua layer sendiri (LayerFilterRender) di atas frame dasar kosong. Dengan NoVideoSource
 * tekstur utama RootEncoder tidak pernah menerima frame, sehingga SurfaceTexture.timestamp selalu 0 dan SEMUA frame
 * yang masuk ke encoder bertimestamp sama. Akibatnya encoder hanya mengeluarkan sekitar 1 frame per detik dan RTMP
 * tidak pernah mengirim video (Frame Terkirim = 0, bitrate hanya audio).
 *
 * Kelas ini memposting frame hitam kecil ke tekstur utama sebanyak [fps] kali per detik. Setiap frame:
 *  - memicu satu putaran render GlStreamInterface (onFrameAvailable), dan
 *  - membawa timestamp monotonik yang terus naik, dipakai sebagai presentation time encoder.
 */
class FrameClockVideoSource : VideoSource() {

    @Volatile private var running = false
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var surface: Surface? = null

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean = true

    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (running) return
        // Frame dasar hanya warna polos: ukuran kecil cukup dan menghemat memori/CPU
        surfaceTexture.setDefaultBufferSize(BASE_SIZE, BASE_SIZE)
        val s = Surface(surfaceTexture)
        surface = s
        val ht = HandlerThread("VilichtFrameClock", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        thread = ht
        val h = Handler(ht.looper)
        handler = h
        running = true

        val periodNs = 1_000_000_000L / fps.coerceIn(1, 120)
        var next = SystemClock.elapsedRealtimeNanos()
        h.post(object : Runnable {
            override fun run() {
                if (!running) return
                postBlankFrame(s)
                next += periodNs
                val now = SystemClock.elapsedRealtimeNanos()
                // Tertinggal jauh (mis. thread tertahan): mulai ulang jadwal, jangan menumpuk frame
                if (next < now - 2 * periodNs) next = now
                h.postDelayed(this, ((next - now).coerceAtLeast(0L)) / 1_000_000L)
            }
        })
    }

    private fun postBlankFrame(s: Surface) {
        try {
            val canvas = s.lockCanvas(null)
            canvas.drawColor(Color.BLACK)
            s.unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            // Surface dilepas saat pipeline dimatikan: normal. Jangan hentikan jam hanya karena satu frame gagal.
            Log.w(TAG, "frame jam gagal: ${e.message}")
        }
    }

    override fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
        try { surface?.release() } catch (_: Exception) {}
        surface = null
    }

    override fun release() {}

    override fun isRunning(): Boolean = running

    private companion object {
        const val TAG = "VilichtFrameClock"
        const val BASE_SIZE = 64
    }
}
