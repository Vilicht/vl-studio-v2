package com.example.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.text.TextPaint
import com.example.ui.theme.BrandColors
import kotlin.math.max
import kotlin.math.min

/**
 * Overlay komentar. Dirender ke Bitmap lalu dipasang sebagai layer BITMAP pada sistem layer StudioEngine
 * (sama seperti Teks/Gambar), sehingga ikut ke encoder DAN preview Studio, mengikuti z-order, posisi, ukuran, crop,
 * opasitas, dan visibilitas source seperti layer lain. Tidak ada jalur render terpisah.
 */
object ChatOverlayRenderer {
    const val ENTER_MS = 250L
    const val EXIT_MS = 600L

    /** Satu baris siap gambar. [alpha] 0..1 (animasi masuk/keluar), [slide] 0..1 = jarak naik yang tersisa saat masuk. */
    data class Line(val user: String, val text: String, val isGift: Boolean, val alpha: Float, val slide: Float, val key: Long)

    /** MURNI: pilih baris yang terlihat pada [nowMs] beserta nilai animasinya. Terlama di atas, terbaru di bawah. */
    fun visibleLines(messages: List<ChatMessage>, s: ChatSettings, nowMs: Long): List<Line> {
        val ttl = s.overlayTtlSec * 1000L
        return messages.asSequence()
            .filter { it.event.kind == ChatEventKind.COMMENT || (s.overlayShowGifts && it.event.kind == ChatEventKind.GIFT) }
            .filter { nowMs - it.arrivedAtMs in 0 until ttl }
            .toList().takeLast(s.overlayMaxLines)
            .map { m ->
                val age = nowMs - m.arrivedAtMs
                val enter = (age.toFloat() / ENTER_MS).coerceIn(0f, 1f)
                val exit = ((ttl - age).toFloat() / EXIT_MS).coerceIn(0f, 1f)
                val e = m.event
                val gift = e.kind == ChatEventKind.GIFT
                Line(e.user, if (gift) "mengirim ${e.count}× ${e.text}" else e.text, gift, min(enter, exit), 1f - enter, m.seq)
            }
    }

    /** true bila ada baris yang sedang beranimasi (perlu render ulang cepat). */
    fun isAnimating(lines: List<Line>) = lines.any { it.alpha < 1f || it.slide > 0f }

    /** Tanda tangan konten: berubah hanya bila hasil gambar berubah (hindari render ulang sia-sia). */
    fun signature(lines: List<Line>, s: ChatSettings, w: Int, h: Int): Int {
        var r = 17
        for (l in lines) { r = r * 31 + l.key.hashCode(); r = r * 31 + (l.alpha * 20).toInt(); r = r * 31 + (l.slide * 20).toInt() }
        return ((r * 31 + (s.overlayOpacity * 100).toInt()) * 31 + (s.overlayTextScale * 100).toInt()) * 31 + w * 7 + h
    }

    /** Gambar ke bitmap transparan [w]x[h] (rasio kotak source, jadi tidak gepeng). */
    fun render(w: Int, h: Int, lines: List<Line>, s: ChatSettings): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (lines.isEmpty()) return bmp
        val c = Canvas(bmp)
        val pad = min(w, h) * 0.02f
        val maxLines = s.overlayMaxLines.coerceAtLeast(1)
        // Ukuran teks: muat maxLines baris di tinggi kotak, dibatasi lebar, lalu dikali skala pengguna
        val base = min(h / (maxLines * 1.55f), w * 0.07f)
        val textSize = (base * s.overlayTextScale).coerceIn(8f, h.toFloat())
        val lineH = textSize * 1.5f
        val gap = textSize * 0.25f

        val userP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize; typeface = Typeface.DEFAULT_BOLD; color = BrandColors.ACCENT_LIGHT }
        val textP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize; color = BrandColors.TEXT }
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        val n = lines.size
        for ((i, l) in lines.withIndex()) {
            val a = (l.alpha * s.overlayOpacity).coerceIn(0f, 1f)
            if (a <= 0.01f) continue
            val bottom = h - pad - (n - 1 - i) * (lineH + gap) + l.slide * lineH * 0.8f
            val top = bottom - lineH
            if (top < -lineH) continue
            val name = if (l.user.isNotEmpty()) l.user + "  " else ""
            val nameW = userP.measureText(name)
            val avail = (w - pad * 2 - textSize * 1.0f - nameW).coerceAtLeast(textSize)
            val body = TextUtils.ellipsize(l.text, textP, avail, TextUtils.TruncateAt.END).toString()
            val chipW = min(w - pad * 2, nameW + textP.measureText(body) + textSize * 1.0f)
            bg.color = BrandColors.BG_DEEP; bg.alpha = (a * 0.55f * 255).toInt()
            c.drawRoundRect(RectF(pad, top, pad + chipW, bottom), lineH * 0.35f, lineH * 0.35f, bg)
            val baseline = top + lineH / 2f - (textP.ascent() + textP.descent()) / 2f
            userP.alpha = (a * 255).toInt(); textP.alpha = (a * 255).toInt()
            c.drawText(name, pad + textSize * 0.5f, baseline, userP)
            c.drawText(body, pad + textSize * 0.5f + nameW, baseline, textP)
        }
        return bmp
    }

    /** Ukuran bitmap untuk kotak [boxW]x[boxH] piksel canvas (sisi terpanjang <= [maxSide]). */
    fun sizeFor(boxW: Int, boxH: Int, maxSide: Int = 720): Pair<Int, Int> {
        val k = min(1f, maxSide.toFloat() / max(boxW, boxH).coerceAtLeast(1))
        return max(2, (boxW * k).toInt()) to max(2, (boxH * k).toInt())
    }
}
