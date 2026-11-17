package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.example.ui.theme.BrandColors
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Menggambar layar fallback ketika sumber game tidak tersedia / berhenti: logo gamepad, animasi
 * denyut sederhana, dan teks "GAME PAUSED" atau "COMING SOON". Bitmap dibuat dengan rasio canvas yang
 * SAMA, jadi layer STRETCH tidak pernah menggepengkan ikon. Semua ukuran relatif terhadap sisi terpendek.
 */
object GameFallbackRenderer {
    const val LABEL_PAUSED = "GAME PAUSED"
    const val LABEL_COMING_SOON = "COMING SOON"

    private val GOLD = BrandColors.ACCENT
    private val BG_TOP = BrandColors.BG_DEEP
    private val BG_BOTTOM = BrandColors.PANEL

    /** Ukuran bitmap: sisi terpanjang [maxSide], rasio = rasio canvas. */
    fun sizeFor(canvasW: Int, canvasH: Int, maxSide: Int = 720): Pair<Int, Int> {
        val k = maxSide.toFloat() / max(canvasW, canvasH).coerceAtLeast(1)
        return max(2, (canvasW * k).toInt() and 1.inv()) to max(2, (canvasH * k).toInt() and 1.inv())
    }

    /** [phase] 0..1 berulang (untuk animasi). [subtitle] teks kecil di bawah label. */
    fun render(w: Int, h: Int, label: String, subtitle: String, phase: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = min(w, h).toFloat()
        val cx = w / 2f
        val cy = h * 0.42f

        val bg = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        // Cincin denyut di belakang ikon (dua gelombang berbeda fase)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = u * 0.006f; color = GOLD }
        for (k in 0..1) {
            val p = ((phase + k * 0.5f) % 1f)
            ring.alpha = ((1f - p) * 90).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, u * (0.16f + 0.18f * p), ring)
        }

        drawGamepad(c, cx, cy, u * 0.30f, 0.92f + 0.08f * sin(phase * 2.0 * PI).toFloat())

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BrandColors.TEXT; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER; letterSpacing = 0.12f
            textSize = fit(label, w * 0.86f, u * 0.075f, bold = true)
        }
        c.drawText(label, cx, h * 0.62f, title)

        val dots = ".".repeat(1 + (phase * 3f).toInt().coerceIn(0, 2))
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BrandColors.TEXT_MUTED; textAlign = Paint.Align.CENTER
            textSize = fit(subtitle, w * 0.86f, u * 0.036f, bold = false)
        }
        c.drawText(subtitle + dots, cx, h * 0.62f + u * 0.085f, sub)
        return bmp
    }

    /** Teks diperkecil sampai muat [maxW]; tidak pernah diperbesar melewati [maxSize]. */
    private fun fit(text: String, maxW: Float, maxSize: Float, bold: Boolean): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = maxSize
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = if (bold) 0.12f else 0f
        }
        val wText = p.measureText(text)
        return if (wText <= maxW || wText <= 0f) maxSize else maxSize * maxW / wText
    }

    /** Gamepad vektor: badan membulat + dua pegangan, d-pad, dua tombol. [size] = lebar badan. */
    private fun drawGamepad(c: Canvas, cx: Float, cy: Float, size: Float, scale: Float) {
        val s = size * scale
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GOLD }
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG_TOP }

        val bodyRect = RectF(cx - s * 0.5f, cy - s * 0.22f, cx + s * 0.5f, cy + s * 0.14f)
        val path = Path().apply {
            addRoundRect(bodyRect, s * 0.16f, s * 0.16f, Path.Direction.CW)
            addCircle(cx - s * 0.36f, cy + s * 0.14f, s * 0.17f, Path.Direction.CW)
            addCircle(cx + s * 0.36f, cy + s * 0.14f, s * 0.17f, Path.Direction.CW)
        }
        c.drawPath(path, body)

        // D-pad (kiri)
        val dx = cx - s * 0.27f
        val dy = cy - s * 0.04f
        val t = s * 0.045f
        val l = s * 0.12f
        c.drawRoundRect(RectF(dx - l, dy - t, dx + l, dy + t), t * 0.5f, t * 0.5f, hole)
        c.drawRoundRect(RectF(dx - t, dy - l, dx + t, dy + l), t * 0.5f, t * 0.5f, hole)

        // Tombol aksi (kanan)
        val bx = cx + s * 0.27f
        c.drawCircle(bx + s * 0.075f, dy, s * 0.04f, hole)
        c.drawCircle(bx - s * 0.075f, dy, s * 0.04f, hole)
        c.drawCircle(bx, dy - s * 0.075f, s * 0.04f, hole)
        c.drawCircle(bx, dy + s * 0.075f, s * 0.04f, hole)
    }
}
