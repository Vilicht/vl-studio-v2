package com.example.pngtuber

/**
 * Konfigurasi avatar PNGTuber. Disimpan di kolom SourceEntity.textContent (tanpa perubahan skema Room),
 * sehingga ikut terduplikasi bersama scene dan ikut undo/redo. Gambar idle ada di SourceEntity.contentUri.
 *
 * [threshold] dan level mic memakai skala 0..100 yang sama dengan meter mixer.
 */
data class PngTuberConfig(
    /** Path gambar "bicara" (penyimpanan internal). Kosong = memakai gambar idle. */
    val talkUri: String = "",
    /** false = avatar selalu idle (tidak bereaksi pada suara). */
    val enabled: Boolean = true,
    /** Level (0..100) di atas ini dianggap bicara. */
    val threshold: Float = 12f,
    /** Pengali level mic sebelum dibandingkan dengan threshold. */
    val sensitivity: Float = 1.5f,
    /** 0 = langsung mengikuti; mendekati 0.95 = sangat halus/lambat. */
    val smoothing: Float = 0.5f
) {
    fun sanitized() = copy(
        threshold = threshold.coerceIn(1f, 80f), sensitivity = sensitivity.coerceIn(0.5f, 4f), smoothing = smoothing.coerceIn(0f, 0.95f)
    )

    fun encode(): String = listOf(VERSION, talkUri, if (enabled) "1" else "0", threshold, sensitivity, smoothing).joinToString(SEP.toString())

    companion object {
        private const val SEP = '\u001F'
        private const val VERSION = "P1"

        /** Toleran: nilai rusak/kosong = bawaan. */
        fun decode(raw: String?): PngTuberConfig {
            if (raw.isNullOrEmpty()) return PngTuberConfig()
            val p = raw.split(SEP)
            if (p.size < 6 || p[0] != VERSION) return PngTuberConfig()
            val d = PngTuberConfig()
            return PngTuberConfig(
                talkUri = p[1], enabled = p[2] != "0",
                threshold = p[3].toFloatOrNull() ?: d.threshold,
                sensitivity = p[4].toFloatOrNull() ?: d.sensitivity,
                smoothing = p[5].toFloatOrNull() ?: d.smoothing
            ).sanitized()
        }
    }
}

/**
 * Deteksi bicara dari level mic NYATA (bukan timer). Penghalusan eksponensial + histeresis: mulai bicara saat level
 * halus > threshold, berhenti saat < 70% threshold, sehingga tidak berkedip di sekitar ambang.
 */
class PngTuberVoice {
    private var smooth = 0f
    var talking = false
        private set
    /** Level halus terakhir (0..100) untuk indikator preview. */
    val level: Float get() = smooth

    fun update(rawLevel: Float, cfg: PngTuberConfig): Boolean {
        val c = cfg.sanitized()
        if (!c.enabled) { smooth = 0f; talking = false; return false }
        val x = (rawLevel * c.sensitivity).coerceIn(0f, 100f)
        smooth += (x - smooth) * (1f - c.smoothing)
        talking = if (talking) smooth > c.threshold * 0.7f else smooth > c.threshold
        return talking
    }

    fun reset() { smooth = 0f; talking = false }
}
