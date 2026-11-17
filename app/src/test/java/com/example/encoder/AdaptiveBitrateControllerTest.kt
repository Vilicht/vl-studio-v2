package com.example.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBitrateControllerTest {

    private val max = 4_000_000

    /** Aritmetika Float (ceiling * 0.9f) bisa meleset 1 bps dari angka bulat; bandingkan dengan toleransi kecil. */
    private fun assertNear(expected: Int, actual: Int, tolerance: Int = 4) =
        assertTrue("diharapkan ~$expected, didapat $actual", kotlin.math.abs(expected - actual) <= tolerance)

    private class Recorder {
        val calls = ArrayList<Int>()
        val last: Int get() = calls.last()
    }

    private fun controller(
        rec: Recorder,
        maxBitrate: Int = max,
        reserved: Int = 0,
        ttl: Int = AdaptiveBitrateController.DEFAULT_CEILING_TTL
    ) = AdaptiveBitrateController(
        maxBitrate = maxBitrate, ceilingTtlTicks = ttl, reservedBps = reserved
    ) { rec.calls.add(it) }

    // ---------------------------------------------------------------- tanpa kemacetan

    @Test fun healthyNetworkNeverChangesTheBitrate() {
        val rec = Recorder()
        val c = controller(rec)
        repeat(300) { c.onSample(measuredBps = 3_900_000, queueFill = 0f) }
        assertTrue(rec.calls.isEmpty())
        assertEquals(max, c.targetBitrate)
    }

    @Test fun lowMeasuredBitrateAloneIsNotCongestion() {
        // Adegan statis: encoder memang menghasilkan sedikit data. Antrian kosong = tidak ada masalah jaringan.
        val rec = Recorder()
        val c = controller(rec)
        repeat(100) { c.onSample(measuredBps = 300_000, queueFill = 0f) }
        assertTrue(rec.calls.isEmpty())
    }

    @Test fun smallQueueBelowAlertThresholdIsIgnored() {
        val rec = Recorder()
        val c = controller(rec)
        repeat(50) { c.onSample(measuredBps = 2_000_000, queueFill = 0.10f) }
        assertTrue(rec.calls.isEmpty())
    }

    // ---------------------------------------------------------------- kemacetan

    @Test fun congestionTargetsNinetyPercentOfMeasuredCapacity() {
        val rec = Recorder()
        val c = controller(rec)
        c.onSample(measuredBps = 2_000_000, queueFill = 0.5f)
        assertNear(1_800_000, rec.last)
        assertNear(2_000_000, c.estimatedCeiling)
    }

    @Test fun oneCongestionEventCanOnlyHalveTheCapacityEstimate() {
        val rec = Recorder()
        val c = controller(rec)
        c.onSample(measuredBps = 100_000, queueFill = 0.5f)   // putus sesaat, bukan kapasitas sebenarnya
        assertNear(max / 2, c.estimatedCeiling)
        assertNear((max / 2 * 0.9f).toInt(), rec.last)
    }

    @Test fun reservedBandwidthIsSubtractedFromTheVideoCapacity() {
        val rec = Recorder()
        val c = controller(rec, reserved = 200_000)
        c.onSample(measuredBps = 2_200_000, queueFill = 0.5f) // 2.2 Mbps terkirim total, 0.2 untuk audio
        assertNear(2_000_000, c.estimatedCeiling)
        assertNear(1_800_000, rec.last)
    }

    @Test fun congestionWithoutAnyMeasurementDoesNotGuess() {
        val rec = Recorder()
        val c = controller(rec)
        c.onSample(measuredBps = 0, queueFill = 0.9f)
        assertTrue(rec.calls.isEmpty())
        assertEquals(max, c.targetBitrate)
    }

    @Test fun targetNeverDropsBelowTheFloor() {
        val rec = Recorder()
        val c = controller(rec)
        repeat(200) { c.onSample(measuredBps = 50_000, queueFill = 1f) }
        assertEquals(max / 10, c.targetBitrate)
        assertTrue(rec.calls.all { it >= max / 10 })
    }

    @Test fun identicalTargetIsNotReportedTwice() {
        val rec = Recorder()
        val c = controller(rec)
        repeat(3) { c.onSample(measuredBps = 2_000_000, queueFill = 0.5f) }
        assertEquals(rec.calls.distinct().size, rec.calls.size)
    }

    // ---------------------------------------------------------------- pemulihan

    @Test fun climbsOnlyAfterFourConsecutiveHealthyTicks() {
        val rec = Recorder()
        val c = controller(rec)
        c.onSample(2_000_000, 0.5f)
        val afterDrop = rec.calls.size
        val dropped = rec.last
        repeat(AdaptiveBitrateController.HOLD_TICKS - 1) { c.onSample(1_800_000, 0f) }
        assertEquals(afterDrop, rec.calls.size)            // belum waktunya naik
        c.onSample(1_800_000, 0f)
        assertEquals(afterDrop + 1, rec.calls.size)
        assertTrue(rec.last > dropped)
    }

    @Test fun aCongestedTickResetsTheHealthyStreak() {
        val rec = Recorder()
        val c = controller(rec)
        c.onSample(2_000_000, 0.5f)
        repeat(AdaptiveBitrateController.HOLD_TICKS - 1) { c.onSample(1_800_000, 0f) }
        c.onSample(1_800_000, 0.5f)                         // macet lagi tepat sebelum boleh naik
        val n = rec.calls.size
        repeat(AdaptiveBitrateController.HOLD_TICKS - 1) { c.onSample(1_800_000, 0f) }
        assertEquals(n, rec.calls.size)
    }

    @Test fun climbingStopsBelowTheEstimatedCapacityWhileTheEstimateIsFresh() {
        val rec = Recorder()
        val c = controller(rec)
        c.onSample(2_000_000, 0.5f)
        repeat(AdaptiveBitrateController.DEFAULT_CEILING_TTL - 50) { c.onSample(1_900_000, 0f) }
        assertTrue(c.targetBitrate <= (2_000_000 * AdaptiveBitrateController.CEILING_MARGIN).toInt() + 4)
        assertTrue(c.targetBitrate < max)
    }

    @Test fun afterTheTtlItProbesBackUpToTheUserMaximumButNeverBeyond() {
        val rec = Recorder()
        val c = controller(rec)
        c.onSample(2_000_000, 0.5f)
        repeat(AdaptiveBitrateController.DEFAULT_CEILING_TTL + 100) { c.onSample(1_900_000, 0f) }
        assertEquals(max, c.targetBitrate)
        assertTrue(rec.calls.all { it <= max })
    }

    @Test fun capacityEstimateIsForgottenAfterTheTtl() {
        val rec = Recorder()
        val c = controller(rec, ttl = 10)
        c.onSample(2_000_000, 0.5f)
        assertTrue(c.estimatedCeiling < max)
        repeat(12) { c.onSample(1_800_000, 0f) }
        assertEquals(max, c.estimatedCeiling)
    }

    @Test fun resetRestoresTheUserMaximumAndNotifies() {
        val rec = Recorder()
        val c = controller(rec)
        repeat(20) { c.onSample(1_500_000, 0.6f) }
        assertTrue(c.targetBitrate < max)
        c.reset()
        assertEquals(max, c.targetBitrate)
        assertEquals(max, c.estimatedCeiling)
        assertEquals(max, rec.last)
    }

    // ---------------------------------------------------------------- simulasi link

    /**
     * Link 1 detik per langkah: produsen mengirim target+reserved bit, link menyalurkan paling banyak
     * kapasitas, sisanya menumpuk di antrian (4 detik bitrate maksimum = antrian penuh).
     */
    private class Link(val maxBitrate: Int, val reserved: Int, ttl: Int) {
        var target = maxBitrate
        val controller = AdaptiveBitrateController(maxBitrate, ceilingTtlTicks = ttl, reservedBps = reserved) { target = it }
        private var backlog = 0L
        private val cache = 4L * maxBitrate
        var congestedTicks = 0

        fun tick(capacityBps: Int) {
            backlog += target.toLong() + reserved
            val sent = minOf(backlog, capacityBps.toLong())
            backlog = minOf(backlog - sent, cache)
            val fill = backlog.toFloat() / cache
            if (fill > AdaptiveBitrateController.QUEUE_ALERT_FRACTION) congestedTicks++
            controller.onSample(sent, fill)
        }
    }

    @Test fun settlesBelowCapacityOnAConstrainedLink() {
        val link = Link(max, reserved = 128_000, ttl = 120)
        val videoCapacity = 2_500_000 - 128_000
        val targets = ArrayList<Int>()
        repeat(900) { link.tick(2_500_000); targets.add(link.target) }
        // Setelah konvergen, target tidak pernah menembus kapasitas video dan antrian jarang menumpuk.
        val settled = targets.drop(150)
        assertTrue("target maks ${settled.max()}", settled.max() < videoCapacity * 1.35)
        assertTrue("target min ${settled.min()}", settled.min() > videoCapacity * 0.7)
        assertTrue("tick macet ${link.congestedTicks}", link.congestedTicks < 900 * 0.05)
    }

    @Test fun recoversTowardsMaximumAfterABriefDropWithinTwiceTheTtl() {
        val link = Link(5_000_000, reserved = 128_000, ttl = 120)
        repeat(60) { link.tick(6_000_000) }
        repeat(20) { link.tick(800_000) }
        assertTrue(link.target < 2_000_000)                  // benar-benar turun saat link jatuh
        var recovered = -1
        for (t in 0 until 600) {
            link.tick(6_000_000)
            if (link.target >= 4_500_000) { recovered = t; break }
        }
        assertTrue("belum pulih setelah 600 dtk", recovered in 0..(2 * 120 + 60))
    }

    @Test fun targetStaysWithinFloorAndMaxOnArbitraryNetworks() {
        val rnd = java.util.Random(42)
        repeat(100) {
            val link = Link(max, reserved = 128_000, ttl = 120)
            repeat(600) {
                val cap = if (rnd.nextInt(10) == 0) 0 else 200_000 + rnd.nextInt(6_000_000)
                link.tick(cap)
                assertTrue(link.target in (max / 10)..max)
            }
        }
    }
}
