package com.example.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Parameter satu layer, SEMUANYA dalam koordinat canvas yang dilihat pengguna (0..1, origin kiri-atas).
 * Objek ini immutable; engine mengganti seluruh objek sekaligus sehingga thread GL selalu membaca
 * satu set nilai yang konsisten (tidak ada x dari frame lama dan y dari frame baru).
 *
 * rect  = kotak layer PENUH sebelum crop (sama dengan x,y,width,height di SourceEntity).
 * crop* = pecahan 0..1 dari kotak itu yang dibuang per sisi (dalam ruang tampilan, setelah rotasi konten).
 */
data class LayerParams(
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 1f,
    val h: Float = 1f,
    val cropL: Float = 0f,
    val cropT: Float = 0f,
    val cropR: Float = 0f,
    val cropB: Float = 0f,
    /** Rotasi layer, derajat searah jarum jam di layar. */
    val rotationDeg: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** Putaran konten searah jarum jam (0..3 x 90°), mis. koreksi orientasi sensor kamera. */
    val contentQuarterTurns: Int = 0,
    val alpha: Float = 1f,
    val visible: Boolean = true,
    /** FitMode.CONTAIN / COVER / STRETCH: cara konten mengisi viewport (kotak layer). Lihat CompositionMath. */
    val fitMode: Int = 2,
    /** Rasio asli konten (lebar/tinggi) SETELAH koreksi orientasi. <= 0 = belum diketahui, dianggap tanpa koreksi. */
    val contentAspect: Float = 0f,
    /** Zoom konten di dalam viewport (>= 1) dan geser (pecahan viewport). */
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    /** Chroma key (green screen): piksel berwarna mirip [keyR],[keyG],[keyB] dibuat transparan. */
    val chromaOn: Boolean = false,
    val keyR: Float = 0f,
    val keyG: Float = 1f,
    val keyB: Float = 0f,
    /** 0..1: seberapa jauh warna dari kunci yang masih dibuang. */
    val chromaSim: Float = 0.4f,
    /** 0..1: kelembutan tepi. */
    val chromaSmooth: Float = 0.1f,
    /** 0..1: pengurangan spill (pantulan warna kunci) pada tepi. */
    val chromaSpill: Float = 0.3f
)

/**
 * Layer compositor milik VILICHT. Menggantikan SurfaceFilterRender/ImageObjectFilterRender bawaan
 * RootEncoder untuk game, kamera, gambar, dan teks karena filter bawaan:
 *  - tidak punya crop,
 *  - memutar di ruang yang terdistorsi pada layer non-persegi,
 *  - tidak mendukung mirror.
 *
 * Satu shader dipakai preview Studio dan encoder RTMP/rekam (keduanya menampilkan tekstur hasil
 * filter yang sama), jadi preview dan siaran selalu identik.
 *
 * Kind.EXTERNAL: konten dari SurfaceTexture (kamera / VirtualDisplay).
 * Kind.BITMAP  : konten dari Bitmap (gambar / teks yang sudah dirender).
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class LayerFilterRender(
    private val kind: Kind,
    private val surfaceReady: ((SurfaceTexture) -> Unit)? = null
) : BaseFilterRender() {

    enum class Kind { EXTERNAL, BITMAP }

    @Volatile var params: LayerParams = LayerParams()

    /** Ukuran canvas yang dilihat pengguna (piksel). Penting agar rotasi benar pada layer non-persegi. */
    @Volatile var canvasW: Float = 1280f
    @Volatile var canvasH: Float = 720f

    // Frame filter RootEncoder 2.7.0 SELALU berukuran sama dengan canvas (portrait = encoder dan frame filter
    // sama-sama portrait; prepareVideo(landscape, rotation = 90) hanya menukar ukuran, tanpa memutar isi).
    // Karena itu koordinat frame dipetakan ke canvas apa adanya, tanpa penukaran sumbu untuk portrait.

    private var program = 0
    private var aPosition = -1
    private var uSampler = -1
    private var uLayer = -1
    private var uST = -1
    private var uCanvas = -1
    private var uRect = -1
    private var uCrop = -1
    private var uRot = -1
    private var uFlip = -1
    private var uQuarter = -1
    private var uAlpha = -1
    private var uFit = -1
    private var uContentAspect = -1
    private var uZoomPan = -1
    private var uChroma = -1
    private var uKeyCbCr = -1

    private val layerTex = IntArray(1) { -1 }
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private val st = FloatArray(16)

    /** true setelah produsen (kamera / VirtualDisplay) mengirim minimal satu frame. Sebelum itu layer tidak digambar. */
    @Volatile private var frameSeen = false

    /** Jumlah frame nyata yang dikirim produsen (kamera / VirtualDisplay). Dipakai watchdog capture game. */
    private val frames = java.util.concurrent.atomic.AtomicLong(0)
    val frameCount: Long get() = frames.get()

    @Volatile private var pendingBitmap: Bitmap? = null
    private var hasBitmap = false

    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        }

    /** Bitmap baru untuk layer BITMAP. Diunggah ke GL pada frame berikutnya; bitmap di-recycle setelahnya. */
    fun setBitmap(bmp: Bitmap) {
        val old = pendingBitmap
        pendingBitmap = bmp
        if (old != null && old !== bmp && !old.isRecycled) old.recycle()
    }

    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture

    override fun initGlFilter(context: Context?) {
        program = GlUtil.createProgram(VERTEX, fragment(kind))
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        uSampler = GLES20.glGetUniformLocation(program, "uSampler")
        uLayer = GLES20.glGetUniformLocation(program, "uLayer")
        uST = GLES20.glGetUniformLocation(program, "uST")
        uCanvas = GLES20.glGetUniformLocation(program, "uCanvas")
        uRect = GLES20.glGetUniformLocation(program, "uRect")
        uCrop = GLES20.glGetUniformLocation(program, "uCrop")
        uRot = GLES20.glGetUniformLocation(program, "uRot")
        uFlip = GLES20.glGetUniformLocation(program, "uFlip")
        uQuarter = GLES20.glGetUniformLocation(program, "uQuarter")
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
        uFit = GLES20.glGetUniformLocation(program, "uFit")
        uContentAspect = GLES20.glGetUniformLocation(program, "uContentAspect")
        uZoomPan = GLES20.glGetUniformLocation(program, "uZoomPan")
        uChroma = GLES20.glGetUniformLocation(program, "uChroma")
        uKeyCbCr = GLES20.glGetUniformLocation(program, "uKeyCbCr")
        android.opengl.Matrix.setIdentityM(st, 0)

        if (kind == Kind.EXTERNAL) {
            GlUtil.createExternalTextures(1, layerTex, 0)
            val t = SurfaceTexture(layerTex[0])
            surfaceTexture = t
            surface = Surface(t)
            t.setOnFrameAvailableListener { frameSeen = true; frames.incrementAndGet() }
            val cb = surfaceReady
            if (cb != null) Handler(Looper.getMainLooper()).post { cb(t) }
        } else {
            GLES20.glGenTextures(1, layerTex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layerTex[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
    }

    override fun drawFilter() {
        var ready = true
        if (kind == Kind.EXTERNAL) {
            ready = frameSeen
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(st)
            } catch (e: Exception) {
                Log.w(TAG, "updateTexImage: ${e.message}")
            }
        } else {
            val bmp = pendingBitmap
            if (bmp != null) {
                pendingBitmap = null
                if (!bmp.isRecycled) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, layerTex[0])
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                    hasBitmap = true
                }
                bmp.recycle()
            }
            ready = hasBitmap
            // Bitmap: baris 0 = atas gambar. Matriks ini membalik Y sehingga rumus shader sama dengan OES.
            st[0] = 1f; st[1] = 0f; st[2] = 0f; st[3] = 0f
            st[4] = 0f; st[5] = -1f; st[6] = 0f; st[7] = 0f
            st[8] = 0f; st[9] = 0f; st[10] = 1f; st[11] = 0f
            st[12] = 0f; st[13] = 1f; st[14] = 0f; st[15] = 1f
        }

        val p = params
        GLES20.glUseProgram(program)

        vertices.position(0)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 8, vertices)
        GLES20.glEnableVertexAttribArray(aPosition)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexId)
        GLES20.glUniform1i(uSampler, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(
            if (kind == Kind.EXTERNAL) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D,
            layerTex[0]
        )
        GLES20.glUniform1i(uLayer, 1)

        GLES20.glUniformMatrix4fv(uST, 1, false, st, 0)
        GLES20.glUniform2f(uCanvas, canvasW, canvasH)
        GLES20.glUniform4f(uRect, p.x, p.y, p.w, p.h)
        GLES20.glUniform4f(uCrop, p.cropL, p.cropT, p.cropR, p.cropB)
        GLES20.glUniform1f(uRot, Math.toRadians(p.rotationDeg.toDouble()).toFloat())
        GLES20.glUniform2f(uFlip, if (p.flipH) 1f else 0f, if (p.flipV) 1f else 0f)
        GLES20.glUniform1f(uQuarter, (((p.contentQuarterTurns % 4) + 4) % 4).toFloat())
        GLES20.glUniform1f(uFit, p.fitMode.toFloat())
        GLES20.glUniform1f(uContentAspect, p.contentAspect)
        GLES20.glUniform3f(uZoomPan, p.zoom.coerceIn(1f, 8f), p.panX, p.panY)
        // Chroma key dihitung di ruang Cb/Cr (mengabaikan kecerahan) sehingga bayangan pada layar hijau tetap terkunci
        val keyCb = -0.168736f * p.keyR - 0.331264f * p.keyG + 0.5f * p.keyB
        val keyCr = 0.5f * p.keyR - 0.418688f * p.keyG - 0.081312f * p.keyB
        GLES20.glUniform4f(
            uChroma, if (p.chromaOn) 1f else 0f,
            p.chromaSim.coerceIn(0f, 1f) * 0.5f, maxOf(p.chromaSmooth.coerceIn(0f, 1f) * 0.5f, 0.001f),
            p.chromaSpill.coerceIn(0f, 1f)
        )
        GLES20.glUniform2f(uKeyCbCr, keyCb, keyCr)
        GLES20.glUniform1f(uAlpha, if (p.visible && ready) p.alpha.coerceIn(0f, 1f) else 0f)
    }

    // Wajib ada (lihat ketentuan proyek): melepas atribut vertex setelah draw.
    override fun disableResources() {
        if (aPosition >= 0) GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    override fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        program = 0
        if (layerTex[0] != -1) GLES20.glDeleteTextures(1, layerTex, 0)
        layerTex[0] = -1
        try { surface?.release() } catch (_: Exception) {}
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surface = null
        surfaceTexture = null
        val b = pendingBitmap
        pendingBitmap = null
        if (b != null && !b.isRecycled) b.recycle()
    }

    private companion object {
        const val TAG = "LayerFilterRender"

        const val VERTEX = """
            attribute vec4 aPosition;
            varying vec2 vFrame;
            void main() {
                gl_Position = aPosition;
                vFrame = aPosition.xy * 0.5 + 0.5;
            }
        """

        fun fragment(kind: Kind): String {
            val ext = if (kind == Kind.EXTERNAL) "#extension GL_OES_EGL_image_external : require\n" else ""
            val sampler = if (kind == Kind.EXTERNAL) "samplerExternalOES" else "sampler2D"
            return ext + """
                precision highp float;
                varying vec2 vFrame;
                uniform sampler2D uSampler;
                uniform $sampler uLayer;
                uniform mat4 uST;
                uniform vec2 uCanvas;
                uniform vec4 uRect;
                uniform vec4 uCrop;
                uniform float uRot;
                uniform vec2 uFlip;
                uniform float uQuarter;
                uniform float uAlpha;
                uniform float uFit;           // 0 contain, 1 cover, 2 stretch
                uniform float uContentAspect; // lebar/tinggi konten (setelah koreksi orientasi); <= 0 = tidak diketahui
                uniform vec3 uZoomPan;        // zoom, panX, panY
                uniform vec4 uChroma;         // aktif, ambang kemiripan, kelembutan, spill
                uniform vec2 uKeyCbCr;        // warna kunci dalam ruang Cb/Cr

                void main() {
                    vec4 base = texture2D(uSampler, vFrame);
                    if (uAlpha <= 0.0) { gl_FragColor = base; return; }

                    // Frame GL (origin kiri-bawah) -> canvas ternormalisasi (origin kiri-atas)
                    vec2 n = vec2(vFrame.x, 1.0 - vFrame.y);

                    // Ruang piksel canvas, supaya rotasi tidak terdistorsi pada layer non-persegi
                    vec2 pxPos = n * uCanvas;
                    vec2 center = (uRect.xy + uRect.zw * 0.5) * uCanvas;
                    vec2 size = max(uRect.zw * uCanvas, vec2(1.0));
                    vec2 d = pxPos - center;
                    float c = cos(uRot);
                    float s = sin(uRot);
                    vec2 q = vec2(c * d.x + s * d.y, -s * d.x + c * d.y);
                    vec2 l = q / size + 0.5;

                    // Crop: area di luar kotak crop tidak digambar sama sekali
                    if (l.x < uCrop.x || l.x > 1.0 - uCrop.z || l.y < uCrop.y || l.y > 1.0 - uCrop.w) {
                        gl_FragColor = base;
                        return;
                    }

                    // Viewport source -> ruang konten: zoom & pan, lalu mode fit.
                    // Rumus sama dengan CompositionMath.viewportToContent (diuji unit).
                    vec2 t = (l - 0.5) / max(uZoomPan.x, 1.0) + 0.5 - uZoomPan.yz;
                    if (uFit < 1.5 && uContentAspect > 0.0) {
                        float ratio = uContentAspect / (size.x / size.y);
                        vec2 cs;
                        if (uFit < 0.5) { cs = ratio >= 1.0 ? vec2(1.0, 1.0 / ratio) : vec2(ratio, 1.0); }
                        else            { cs = ratio >= 1.0 ? vec2(ratio, 1.0) : vec2(1.0, 1.0 / ratio); }
                        t = (t - (1.0 - cs) * 0.5) / cs;
                    }
                    // Di luar konten (letterbox / pan berlebih): tidak digambar
                    if (t.x < 0.0 || t.x > 1.0 || t.y < 0.0 || t.y > 1.0) {
                        gl_FragColor = base;
                        return;
                    }
                    if (uFlip.x > 0.5) t.x = 1.0 - t.x;
                    if (uFlip.y > 0.5) t.y = 1.0 - t.y;
                    // Batalkan putaran konten (koreksi sensor kamera)
                    if (uQuarter > 0.5 && uQuarter < 1.5) { t = vec2(t.y, 1.0 - t.x); }
                    else if (uQuarter > 1.5 && uQuarter < 2.5) { t = vec2(1.0 - t.x, 1.0 - t.y); }
                    else if (uQuarter > 2.5) { t = vec2(1.0 - t.y, t.x); }

                    vec4 tc = uST * vec4(t.x, 1.0 - t.y, 0.0, 1.0);
                    vec4 col = texture2D(uLayer, tc.xy);
                    if (uChroma.x > 0.5) {
                        vec2 cc = vec2(
                            -0.168736 * col.r - 0.331264 * col.g + 0.5 * col.b,
                            0.5 * col.r - 0.418688 * col.g - 0.081312 * col.b
                        );
                        float dist = distance(cc, uKeyCbCr);
                        col.a = col.a * smoothstep(uChroma.y, uChroma.y + uChroma.z, dist);
                        // Spill reduction: piksel yang lolos tetapi masih dekat warna kunci (tepi) didesaturasi
                        float nearKey = 1.0 - smoothstep(uChroma.y, uChroma.y + 0.25, dist);
                        float luma = dot(col.rgb, vec3(0.299, 0.587, 0.114));
                        col.rgb = mix(col.rgb, vec3(luma), uChroma.w * nearKey * 0.85);
                    }
                    gl_FragColor = mix(base, vec4(col.rgb, 1.0), col.a * uAlpha);
                }
            """.trimIndent()
        }
    }
}
