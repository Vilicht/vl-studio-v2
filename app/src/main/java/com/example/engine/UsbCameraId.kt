package com.example.engine

/**
 * ID stabil untuk webcam USB (UVC) yang disimpan di kolom `SourceEntity.cameraId`.
 *
 * Format: `uvc:<vendorId hex4>:<productId hex4>` dan `#N` (mulai 2) bila ada beberapa perangkat identik,
 * misalnya `uvc:046d:0825` atau `uvc:046d:0825#2`. Disimpan sebagai teks sehingga TIDAK perlu migrasi Room,
 * dan tetap berlaku setelah kamera dicabut lalu dicolok ulang (nama perangkat /dev/bus/usb/... berubah,
 * vendor/product tidak).
 *
 * File ini sengaja bebas dari tipe android.* agar bisa diuji sebagai unit test JVM biasa.
 */
data class UsbCameraId(val vendorId: Int, val productId: Int, val index: Int = 0) {

    fun format(): String = buildString {
        append(PREFIX)
        append(hex4(vendorId))
        append(':')
        append(hex4(productId))
        if (index > 0) {
            append('#')
            append(index + 1)
        }
    }

    override fun toString(): String = format()

    companion object {
        const val PREFIX = "uvc:"

        fun isUsb(raw: String?): Boolean = raw != null && raw.startsWith(PREFIX)

        /** null bila [raw] bukan ID kamera USB yang valid. */
        fun parse(raw: String?): UsbCameraId? {
            if (raw == null || !raw.startsWith(PREFIX)) return null
            val body = raw.substring(PREFIX.length)
            val hashAt = body.indexOf('#')
            val head = if (hashAt >= 0) body.substring(0, hashAt) else body
            val ordinal = if (hashAt >= 0) (body.substring(hashAt + 1).toIntOrNull() ?: return null) else 1
            if (ordinal < 1) return null
            val parts = head.split(':')
            if (parts.size != 2) return null
            val vendor = parts[0].toIntOrNull(16) ?: return null
            val product = parts[1].toIntOrNull(16) ?: return null
            if (vendor !in 0..0xFFFF || product !in 0..0xFFFF) return null
            return UsbCameraId(vendor, product, ordinal - 1)
        }

        private fun hex4(v: Int): String = Integer.toHexString(v and 0xFFFF).padStart(4, '0')
    }
}

/** Perangkat USB yang terlihat sistem, tanpa ketergantungan pada android.hardware.usb.UsbDevice. */
data class UsbEntry(val vendorId: Int, val productId: Int, val deviceName: String)

/**
 * Beri [UsbCameraId] ke setiap perangkat. Perangkat dengan vendor/product sama diurutkan menurut
 * [UsbEntry.deviceName] lalu dinomori 0, 1, 2... sehingga urutannya deterministik antar pemanggilan.
 * Hasil mengikuti urutan masukan.
 */
fun assignUsbIds(entries: List<UsbEntry>): List<Pair<UsbEntry, UsbCameraId>> {
    val ordinal = HashMap<UsbEntry, Int>()
    entries.groupBy { it.vendorId to it.productId }.values.forEach { group ->
        group.sortedBy { it.deviceName }.forEachIndexed { i, e -> ordinal[e] = i }
    }
    return entries.map { e -> e to UsbCameraId(e.vendorId, e.productId, ordinal[e] ?: 0) }
}

/** Satu mode video yang diiklankan webcam UVC (setara com.serenegiant.usb.Size). */
data class UvcMode(
    val frameType: Int,
    val width: Int,
    val height: Int,
    val defaultFps: Int,
    val fpsList: List<Int> = emptyList()
) {
    val pixels: Int get() = width * height
    val isMjpeg: Boolean get() = frameType == FRAME_MJPEG

    /** Semua fps yang valid untuk mode ini: urut naik, tanpa duplikat, tanpa nilai tak positif. */
    val supportedFps: List<Int> get() = (fpsList + defaultFps).filter { it > 0 }.distinct().sorted()
    val maxFps: Int get() = supportedFps.lastOrNull() ?: 0

    companion object {
        /** UVCCamera.UVC_VS_FRAME_UNCOMPRESSED */
        const val FRAME_UNCOMPRESSED = 0x05
        /** UVCCamera.UVC_VS_FRAME_MJPEG */
        const val FRAME_MJPEG = 0x07
    }
}

data class UvcSelection(val mode: UvcMode, val fps: Int)

/**
 * Memilih mode webcam UVC yang paling cocok dengan batas piksel dan fps yang diminta engine.
 *
 * UVCCamera.setPreviewSize() MENOLAK kombinasi (ukuran, tipe, fps) yang tidak diiklankan kamera
 * (IllegalArgumentException "invalid preview size"), jadi fps HARUS dipilih dari daftar fps mode itu.
 */
object UvcModeSelector {

    /** fps terbesar yang ≤ [targetFps]; bila semua lebih tinggi, yang terendah. 0 bila mode tak punya fps. */
    fun fpsFor(mode: UvcMode, targetFps: Int): Int {
        val all = mode.supportedFps
        if (all.isEmpty()) return 0
        return all.lastOrNull { it <= targetFps } ?: all.first()
    }

    /**
     * Aturan:
     *  1. Hanya mode dengan lebar × tinggi ≤ [maxPixels]; bila tidak ada satu pun, mode terkecil.
     *  2. Utamakan mode yang sanggup memberi [targetFps]; dari situ pilih piksel terbanyak,
     *     lalu MJPEG (hemat bandwidth USB 2.0) di atas mentah, lalu fps maksimum tertinggi.
     *  3. Bila tidak ada mode yang mencapai [targetFps], pilih yang fps maksimumnya tertinggi
     *     (siaran lancar lebih penting daripada beberapa piksel ekstra), lalu piksel terbanyak.
     */
    fun select(modes: List<UvcMode>, maxPixels: Int, targetFps: Int): UvcSelection? {
        val usable = modes.filter { it.width > 0 && it.height > 0 }
        if (usable.isEmpty()) return null
        val fit = usable.filter { it.pixels <= maxPixels }.ifEmpty { listOf(usable.minBy { it.pixels }) }
        val capable = fit.filter { it.maxFps >= targetFps }
        val best = if (capable.isNotEmpty()) {
            capable.maxWithOrNull(compareBy<UvcMode>({ it.pixels }, { it.isMjpeg }, { it.maxFps }))!!
        } else {
            fit.maxWithOrNull(compareBy<UvcMode>({ it.maxFps }, { it.pixels }, { it.isMjpeg }))!!
        }
        return UvcSelection(best, fpsFor(best, targetFps))
    }
}
