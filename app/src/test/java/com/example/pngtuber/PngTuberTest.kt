package com.example.pngtuber

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PngTuberTest {
    @Test fun configRoundTrip() {
        val c = PngTuberConfig("/data/x.img", enabled = false, threshold = 20f, sensitivity = 2f, smoothing = 0.7f)
        assertEquals(c, PngTuberConfig.decode(c.encode()))
    }

    @Test fun decodeIsTolerant() {
        val d = PngTuberConfig()
        assertEquals(d, PngTuberConfig.decode(null))
        assertEquals(d, PngTuberConfig.decode(""))
        assertEquals(d, PngTuberConfig.decode("Halo teks biasa"))
        assertEquals(d, PngTuberConfig.decode("P1\u001Fa"))
        val bad = PngTuberConfig.decode("P1\u001F\u001F1\u001Fxx\u001F999\u001F-3")
        assertEquals(d.threshold, bad.threshold); assertEquals(4f, bad.sensitivity); assertEquals(0f, bad.smoothing)
    }

    @Test fun talksAboveThresholdAndStopsWithHysteresis() {
        val cfg = PngTuberConfig(threshold = 20f, sensitivity = 1f, smoothing = 0f)
        val v = PngTuberVoice()
        assertFalse(v.update(10f, cfg))
        assertTrue(v.update(25f, cfg))
        assertTrue("di bawah threshold tapi di atas 70%: tetap bicara", v.update(16f, cfg))
        assertFalse(v.update(10f, cfg))
    }

    @Test fun smoothingDelaysResponseButStillConverges() {
        val cfg = PngTuberConfig(threshold = 20f, sensitivity = 1f, smoothing = 0.8f)
        val v = PngTuberVoice()
        assertFalse("satu lonjakan singkat tidak membuat bicara", v.update(40f, cfg))
        var talking = false
        repeat(20) { talking = v.update(40f, cfg) }
        assertTrue(talking)
    }

    @Test fun disabledAlwaysIdle() {
        val v = PngTuberVoice()
        assertFalse(v.update(100f, PngTuberConfig(enabled = false)))
        assertEquals(0f, v.level)
    }

    @Test fun sensitivityScalesLevel() {
        val lo = PngTuberVoice(); val hi = PngTuberVoice()
        assertFalse(lo.update(10f, PngTuberConfig(threshold = 20f, sensitivity = 1f, smoothing = 0f)))
        assertTrue(hi.update(10f, PngTuberConfig(threshold = 20f, sensitivity = 3f, smoothing = 0f)))
    }
}
