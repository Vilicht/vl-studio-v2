package com.example.composition

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Matematika komposisi MURNI (tanpa dependensi Android) yang menjadi satu-satunya sumber kebenaran
 * untuk: ukuran canvas, mode fit konten di dalam viewport source, dan pemetaan ulang kotak source
 * saat rasio canvas berubah. Shader GL (LayerFilterRender) mengimplementasikan rumus fit yang SAMA;
 * fungsi di sini dipakai editor/ViewModel dan diuji unit, sehingga preview dan output tidak punya
 * dua logika berbeda.
 */

/** Cara konten ditampilkan di dalam viewport (kotak) source. Nilai Int disimpan di database. */
object FitMode {
    /** UTUH: seluruh konten terlihat, rasio asli dipertahankan, area kosong boleh. */
    const val CONTAIN = 0
    /** PENUHI: viewport terisi penuh, rasio asli dipertahankan, kelebihan terpotong. */
    const val COVER = 1
    /** REGANGKAN: konten memenuhi viewport, rasio boleh berubah (disengaja). */
    const val STRETCH = 2

    fun sanitize(v: Int): Int = if (v == COVER || v == STRETCH) v else CONTAIN
    fun label(v: Int): String = when (v) {
        COVER -> "Penuhi"
        STRETCH -> "Regangkan"
        else -> "Utuh"
    }
}

/**
 * Ukuran canvas (piksel). Independen dari semua source. [aspect] = lebar/tinggi.
 */
data class CanvasSpec(val width: Int, val height: Int) {
    val aspect: Float get() = width.toFloat() / height
    val isPortrait: Boolean get() = height > width
    val isSquare: Boolean get() = width == height

    /** Ukuran yang dikirim ke encoder: rasio canvas dipertahankan, sisi terpanjang = [longSide] (genap). */
    fun encodeSize(longSide: Int, squareSide: Int = longSide): CanvasSpec {
        if (isSquare) { val s = even(squareSide); return CanvasSpec(s, s) }
        val a = aspect
        return if (a >= 1f) CanvasSpec(even(longSide), even((longSide / a).roundToInt()))
        else CanvasSpec(even((longSide * a).roundToInt()), even(longSide))
    }

    fun label(): String = CanvasPresets.match(this)?.label ?: "${width}x$height"

    companion object {
        val PORTRAIT = CanvasSpec(1080, 1920)
        val LANDSCAPE = CanvasSpec(1920, 1080)
        val SQUARE = CanvasSpec(1080, 1080)

        const val MIN_SIDE = 240
        const val MAX_SIDE = 3840

        private fun even(v: Int): Int = max(2, v and 1.inv())

        /**
         * Canvas dari rasio [rw]:[rh]. Sisi terpanjang = [longSide] (1:1 memakai 1080 agar sama dengan [SQUARE]).
         * Hasil melewati [custom], jadi selalu genap dan di dalam batas.
         */
        fun fromRatio(rw: Int, rh: Int, longSide: Int = 1920): CanvasSpec {
            require(rw > 0 && rh > 0)
            if (rw == rh) return SQUARE
            return if (rw > rh) custom(longSide, (longSide.toLong() * rh / rw).toInt())
            else custom((longSide.toLong() * rw / rh).toInt(), longSide)
        }

        /** Resolusi kustom: dibatasi 240..3840 dan dibulatkan ke genap (syarat encoder H.264). */
        fun custom(w: Int, h: Int): CanvasSpec =
            CanvasSpec(even(w.coerceIn(MIN_SIDE, MAX_SIDE)), even(h.coerceIn(MIN_SIDE, MAX_SIDE)))
    }
}

/** Satu preset rasio canvas (mis. 9:16). */
data class CanvasPreset(val label: String, val rw: Int, val rh: Int) {
    val aspect: Float get() = rw.toFloat() / rh
    fun spec(): CanvasSpec = CanvasSpec.fromRatio(rw, rh)
}

/** Semua rasio canvas bawaan. Urutan = urutan tampil di CanvasPicker. */
object CanvasPresets {
    val all: List<CanvasPreset> = listOf(
        CanvasPreset("9:16", 9, 16), CanvasPreset("16:9", 16, 9), CanvasPreset("1:1", 1, 1),
        CanvasPreset("4:3", 4, 3), CanvasPreset("3:4", 3, 4),
        CanvasPreset("6:19", 6, 19), CanvasPreset("19:6", 19, 6)
    )

    /** Preset yang rasionya sama dengan [spec] (toleransi 0.5%), atau null bila kustom. */
    fun match(spec: CanvasSpec): CanvasPreset? =
        all.firstOrNull { abs(spec.aspect - it.aspect) / it.aspect < 0.005f }
}

/** Persegi dalam koordinat ternormalisasi canvas (0..1, origin kiri-atas). */
data class NRect(val x: Float, val y: Float, val w: Float, val h: Float)

object CompositionMath {

    /**
     * Area yang ditempati konten di dalam viewport, dinyatakan 0..1 terhadap viewport.
     * [contentAspect] = lebar/tinggi konten (setelah koreksi orientasi). <= 0 (belum diketahui)
     * diperlakukan sebagai rasio viewport, yaitu tidak ada distorsi yang bisa dikoreksi.
     * [viewportAspect] = lebar/tinggi viewport dalam PIKSEL (bukan dalam satuan ternormalisasi).
     * Hasil boleh > 1 (COVER) sehingga bagian konten keluar dari viewport.
     */
    fun contentRectInViewport(mode: Int, contentAspect: Float, viewportAspect: Float): NRect {
        if (mode == FitMode.STRETCH || contentAspect <= 0f || viewportAspect <= 0f) return NRect(0f, 0f, 1f, 1f)
        val ratio = contentAspect / viewportAspect          // >1: konten lebih lebar dari viewport
        val w: Float
        val h: Float
        if (mode == FitMode.COVER) {
            if (ratio >= 1f) { h = 1f; w = ratio } else { w = 1f; h = 1f / ratio }
        } else {
            if (ratio >= 1f) { w = 1f; h = 1f / ratio } else { h = 1f; w = ratio }
        }
        return NRect((1f - w) / 2f, (1f - h) / 2f, w, h)
    }

    /**
     * Pemetaan titik viewport (0..1) -> koordinat tekstur konten (0..1) termasuk zoom dan pan.
     * Mengembalikan null bila titik jatuh di area kosong (letterbox) sehingga tidak digambar.
     * Ini referensi CPU untuk rumus yang sama di shader (dipakai pengujian).
     */
    fun viewportToContent(
        px: Float, py: Float, mode: Int, contentAspect: Float, viewportAspect: Float,
        zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f
    ): Pair<Float, Float>? {
        val z = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        // zoom di sekitar pusat viewport, pan dalam pecahan viewport
        val zx = (px - 0.5f) / z + 0.5f - panX
        val zy = (py - 0.5f) / z + 0.5f - panY
        val r = contentRectInViewport(mode, contentAspect, viewportAspect)
        val u = (zx - r.x) / r.w
        val v = (zy - r.y) / r.h
        if (mode != FitMode.COVER && mode != FitMode.STRETCH && (u < 0f || u > 1f || v < 0f || v > 1f)) return null
        if (mode == FitMode.COVER && (u < 0f || u > 1f || v < 0f || v > 1f)) return null
        return u to v
    }

    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 8f

    /**
     * Pindahkan kotak source ketika canvas berubah dari [old] ke [new] tanpa mengubah rasio pikselnya.
     * Ukuran piksel di-skala seragam sebesar k = min(newW/oldW, newH/oldH) (muat di canvas baru, setara
     * Contain), pusat source dipertahankan secara proporsional. Rotasi tidak berubah (ruang piksel).
     */
    fun remapBox(box: NRect, old: CanvasSpec, new: CanvasSpec): NRect {
        if (old == new) return box
        val k = min(new.width.toFloat() / old.width, new.height.toFloat() / old.height)
        val pw = box.w * old.width * k
        val ph = box.h * old.height * k
        val cx = box.x + box.w / 2f
        val cy = box.y + box.h / 2f
        val nw = pw / new.width
        val nh = ph / new.height
        return NRect(cx - nw / 2f, cy - nh / 2f, nw, nh)
    }

    /**
     * Bentuk ulang [box] agar rasio PIKSEL-nya menjadi [newAspect] (lebar/tinggi), pusat dipertahankan.
     * Lebar dipertahankan; bila hasilnya lebih besar dari canvas, diperkecil seragam (rasio tetap) lalu
     * digeser masuk ke dalam canvas. Dipakai saat rasio nyata kamera berubah (mis. 4:3 -> 3:4 setelah rotasi sensor).
     */
    fun reshapeBoxForAspect(box: NRect, newAspect: Float, canvas: CanvasSpec): NRect {
        if (newAspect <= 0f || box.w <= 0f) return box
        var nw = box.w
        var nh = nw * canvas.aspect / newAspect
        val k = min(1f, min(1f / nw, 1f / nh))
        nw *= k; nh *= k
        val cx = box.x + box.w / 2f
        val cy = box.y + box.h / 2f
        val x = (cx - nw / 2f).coerceIn(0f, max(0f, 1f - nw))
        val y = (cy - nh / 2f).coerceIn(0f, max(0f, 1f - nh))
        return NRect(x, y, nw, nh)
    }

    /** Batas |pan| (pecahan viewport) agar konten yang di-zoom tidak keluar dari viewport. 0 saat zoom = 1. */
    fun panLimit(zoom: Float): Float {
        val z = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        return (0.5f - 0.5f / z).coerceAtLeast(0f)
    }

    /** Kotak (ternormalisasi) yang memuat konten berrasio [contentAspect] di dalam canvas, di tengah. */
    fun fitBoxToCanvas(contentAspect: Float, canvas: CanvasSpec, fill: Boolean): NRect {
        val a = if (contentAspect > 0f) contentAspect else canvas.aspect
        val ratio = a / canvas.aspect
        val w: Float
        val h: Float
        if (fill) { if (ratio >= 1f) { h = 1f; w = ratio } else { w = 1f; h = 1f / ratio } }
        else { if (ratio >= 1f) { w = 1f; h = 1f / ratio } else { h = 1f; w = ratio } }
        return NRect((1f - w) / 2f, (1f - h) / 2f, w, h)
    }

    /** Kotak "Full Width": lebar = canvas, tinggi mengikuti rasio konten, di tengah vertikal. */
    fun fullWidthBox(contentAspect: Float, canvas: CanvasSpec): NRect {
        val a = if (contentAspect > 0f) contentAspect else canvas.aspect
        val h = canvas.aspect / a
        return NRect(0f, (1f - h) / 2f, 1f, h)
    }

    /** Kotak "Full Height": tinggi = canvas, lebar mengikuti rasio konten, di tengah horizontal. */
    fun fullHeightBox(contentAspect: Float, canvas: CanvasSpec): NRect {
        val a = if (contentAspect > 0f) contentAspect else canvas.aspect
        val w = a / canvas.aspect
        return NRect((1f - w) / 2f, 0f, w, 1f)
    }

    /**
     * Urutkan ulang daftar id (indeks 0 = paling bawah/background) dan kembalikan zIndex 0..n-1 yang rapat.
     * Dipakai Bring to Front/Send to Back/drag-drop agar zIndex tidak pernah bentrok (data lama bisa sama).
     */
    fun <T> moveInOrder(ordered: List<T>, item: T, toIndex: Int): List<T> {
        val from = ordered.indexOf(item)
        if (from < 0) return ordered
        val list = ordered.toMutableList()
        list.removeAt(from)
        list.add(toIndex.coerceIn(0, list.size), item)
        return list
    }

    /** true bila [box] seluruhnya tertutup oleh salah satu kotak di [covers] (kotak sumber opak yang lebih depan). */
    fun isFullyCovered(box: NRect, covers: List<NRect>, eps: Float = 1e-3f): Boolean = covers.any {
        it.x <= box.x + eps && it.y <= box.y + eps &&
            it.x + it.w >= box.x + box.w - eps && it.y + it.h >= box.y + box.h - eps
    }

    fun approx(a: Float, b: Float, eps: Float = 1e-3f) = abs(a - b) <= eps
}
