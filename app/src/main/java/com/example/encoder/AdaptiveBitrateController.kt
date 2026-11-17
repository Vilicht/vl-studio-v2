package com.example.encoder

import kotlin.math.max
import kotlin.math.min

/**
 * Bitrate adaptif berbasis ANTRIAN KIRIM, bukan sekadar kecepatan terukur.
 *
 * Dibaca dari perilaku aplikasi referensi: yang menandakan jaringan tidak kuat adalah paket menumpuk di
 * antrian (queue), bukan bitrate yang turun. Bitrate encoder yang turun sendiri biasanya hanya berarti
 * adegannya statis. Karena itu controller:
 *
 *  - Menurunkan bitrate hanya ketika antrian terisi lebih dari [QUEUE_ALERT_FRACTION].
 *  - Mengestimasi KAPASITAS LINK ("ceiling") dari rata-rata kecepatan terkirim selama antrian menumpuk
 *    (jendela [CAPACITY_WINDOW] sampel), lalu menargetkan [BACKOFF] × kapasitas itu.
 *  - Naik kembali pelan-pelan ([PROBE]/[GAP_CLOSE]) hanya setelah [HOLD_TICKS] detik berturut-turut sehat,
 *    dan tidak pernah melewati [CEILING_MARGIN] × kapasitas yang diestimasi.
 *  - Melupakan estimasi kapasitas setelah [ceilingTtlTicks] detik agar jaringan yang pulih bisa dicoba lagi.
 *
 * Kelas ini murni (tanpa Android/RootEncoder) agar bisa diuji sebagai unit test JVM.
 * Panggil [onSample] SEKALI per detik; tidak thread-safe, panggil dari satu thread.
 *
 * @param maxBitrate  bitrate video yang dipilih pengguna (bps); tidak pernah dilampaui.
 * @param minBitrate  lantai (bps); default 10% dari [maxBitrate].
 * @param reservedBps bandwidth yang dipakai trek lain (audio + overhead) dan tidak bisa dikurangi controller;
 *                    dikurangkan dari kecepatan terukur sebelum dipakai sebagai kapasitas video.
 * @param onBitrate   dipanggil hanya saat target berubah, dengan target baru (bps).
 */
class AdaptiveBitrateController(
    private val maxBitrate: Int,
    minBitrate: Int = maxBitrate / 10,
    private val ceilingTtlTicks: Int = DEFAULT_CEILING_TTL,
    private val reservedBps: Int = 0,
    private val onBitrate: (Int) -> Unit
) {
    private val floor: Int = minBitrate.coerceIn(1, maxBitrate.coerceAtLeast(1))
    private val recent = ArrayDeque<Long>()

    private var target: Int = maxBitrate
    private var ceiling: Int = maxBitrate
    private var goodTicks = 0
    private var age = 0
    private var hasMeasured = false

    /** Bitrate video yang sedang ditargetkan (bps). */
    val targetBitrate: Int get() = target

    /** Estimasi kapasitas link saat ini (bps); sama dengan [maxBitrate] bila belum ada kemacetan. */
    val estimatedCeiling: Int get() = ceiling

    /**
     * @param measuredBps kecepatan terkirim selama detik terakhir (bps), dari callback onNewBitrate RootEncoder.
     * @param queueFill   isi antrian kirim 0..1 (item di cache / kapasitas cache).
     */
    fun onSample(measuredBps: Long, queueFill: Float) {
        if (measuredBps > 0) hasMeasured = true

        // Sampel kecepatan hanya berarti sebagai kapasitas ketika antrian sedang menumpuk (link jenuh).
        if (queueFill > 0f && hasMeasured) {
            recent.addLast(measuredBps)
            if (recent.size > CAPACITY_WINDOW) recent.removeFirst()
        }

        if (queueFill > QUEUE_ALERT_FRACTION) {
            onCongestion(measuredBps)
        } else {
            onHealthy()
        }

        if (ceilingTtlTicks > 0) {
            age++
            if (age > ceilingTtlTicks) {
                ceiling = maxBitrate
                age = 0
            }
        }
    }

    /** Kembali ke [maxBitrate] dan hapus semua estimasi (mis. saat sambung ulang atau stream baru). */
    fun reset() {
        target = maxBitrate
        ceiling = maxBitrate
        goodTicks = 0
        age = 0
        recent.clear()
        hasMeasured = false
        onBitrate(target)
    }

    private fun onCongestion(measuredBps: Long) {
        val capacityTotal: Long = when {
            recent.isNotEmpty() -> recent.sum() / recent.size
            measuredBps > 0 -> measuredBps
            else -> return                              // belum ada ukuran: jangan menebak
        }
        val capacityVideo = (capacityTotal - reservedBps).coerceAtLeast(floor.toLong())
        // Kapasitas hanya boleh turun (sampai paling banyak separuh per kejadian), tidak melonjak ke atas.
        val lowered = min(ceiling.toLong(), capacityVideo).toInt()
        ceiling = max(lowered, (ceiling * MAX_CEILING_DROP).toInt())
        val newTarget = max((ceiling * BACKOFF).toInt(), floor)
        goodTicks = 0
        age = 0
        publish(newTarget)
    }

    private fun onHealthy() {
        goodTicks++
        if (goodTicks < HOLD_TICKS) return
        goodTicks = 0
        // Batas naik: sedikit di bawah kapasitas yang diestimasi, atau maksimum bila kapasitas belum pernah dibatasi.
        val cap = if (ceiling < maxBitrate) max((ceiling * CEILING_MARGIN).toInt(), floor) else maxBitrate
        val closeGap = ((cap - target) * GAP_CLOSE).toInt() + target
        val probe = (target * PROBE).toInt()
        publish(min(max(closeGap, probe), cap))
    }

    private fun publish(newTarget: Int) {
        if (newTarget == target) return
        target = newTarget
        onBitrate(newTarget)
    }

    companion object {
        /** Turun ke 90% dari kapasitas yang diestimasi. */
        const val BACKOFF = 0.9f
        /** Jumlah sampel kecepatan (detik) yang dirata-rata untuk estimasi kapasitas. */
        const val CAPACITY_WINDOW = 15
        /** Saat naik, jangan melewati 97% dari kapasitas yang diestimasi. */
        const val CEILING_MARGIN = 0.97f
        /** Tiap langkah naik menutup 25% jarak ke batas atas... */
        const val GAP_CLOSE = 0.25f
        /** ...dan minimal +5%. */
        const val PROBE = 1.05f
        /** Detik sehat berturut-turut sebelum mencoba naik. */
        const val HOLD_TICKS = 4
        /** Kapasitas yang diestimasi tidak boleh jatuh lebih dari separuh dalam satu kejadian. */
        const val MAX_CEILING_DROP = 0.5f
        /** Antrian dianggap menumpuk bila terisi lebih dari 15%. */
        const val QUEUE_ALERT_FRACTION = 0.15f
        /** Estimasi kapasitas dilupakan setelah 300 detik (nilai referensi). */
        const val DEFAULT_CEILING_TTL = 300
    }
}
