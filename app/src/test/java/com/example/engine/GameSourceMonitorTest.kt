package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSourceMonitorTest {

    private fun GameSourceMonitor.at(t: Long, monitor: Boolean = true, valid: Boolean = true) = evaluate(t, monitor, valid)

    @Test fun steadyFramesStayLiveForever() {
        val m = GameSourceMonitor()
        var t = 0L
        while (t <= 120_000) {
            m.onFrames(t, 15)
            val r = m.at(t)
            assertEquals(GameSourceState.LIVE, r.state)
            assertEquals(RecoveryAction.NONE, r.action)
            t += 500
        }
    }

    @Test fun singleLateFrameNeverTriggersAnything() {
        val m = GameSourceMonitor()
        m.onFrames(0, 15); m.at(0)
        // celah 2.0 s tanpa frame, lebih kecil dari ambang 2.5 s
        for (t in listOf(500L, 1000L, 1500L, 2000L)) {
            val r = m.at(t)
            assertEquals(GameSourceState.LIVE, r.state)
            assertEquals(RecoveryAction.NONE, r.action)
        }
    }

    @Test fun noFramesEscalatesReattachThenRebuildThenFallback() {
        val m = GameSourceMonitor()
        assertEquals(GameSourceState.LIVE, m.at(0).state)
        assertEquals(GameSourceState.LIVE, m.at(2_500).state)          // masa tenggang 3 s
        val a = m.at(3_000)
        assertEquals(GameSourceState.STALLED, a.state)
        assertEquals(RecoveryAction.REATTACH_SURFACE, a.action)
        assertEquals(RecoveryAction.NONE, m.at(4_000).action)           // belum waktunya percobaan berikut
        val b = m.at(6_000)
        assertEquals(RecoveryAction.REBUILD_SURFACE, b.action)
        assertEquals(GameSourceState.STALLED, b.state)
        assertEquals(GameSourceState.PAUSED, m.at(8_000).state)
    }

    @Test fun staticScreenRecoversAfterReattachWithoutFallbackAndBacksOff() {
        val m = GameSourceMonitor()
        m.at(0)
        assertEquals(RecoveryAction.REATTACH_SURFACE, m.at(3_000).action)
        m.onFrames(3_100, 1)                                            // pasang ulang -> 1 frame (layar statis)
        val r = m.at(3_500)
        assertEquals(GameSourceState.LIVE, r.state)
        assertTrue("ambang naik setelah layar statis terbukti sehat", m.currentStaleMs > 2_500)
        // Gerakan nyata mereset ambang
        m.onFrames(4_000, 15)
        assertEquals(2_500L, m.currentStaleMs)
    }

    @Test fun leavingFallbackNeedsFrameAndMinimumHold() {
        val m = GameSourceMonitor()
        m.at(0); m.at(3_000); m.at(6_000)
        assertEquals(GameSourceState.PAUSED, m.at(8_000).state)
        m.onFrames(8_100, 1)
        assertEquals("fallback minimal tampil 1.5 s", GameSourceState.PAUSED, m.at(8_200).state)
        assertEquals(GameSourceState.LIVE, m.at(9_600).state)
    }

    @Test fun invalidSessionShowsUnavailableAndRecoversWhenValidAgain() {
        val m = GameSourceMonitor()
        assertEquals(GameSourceState.UNAVAILABLE, m.at(0, valid = false).state)
        assertEquals(GameSourceState.UNAVAILABLE, m.at(10_000, valid = false).state)
        assertEquals(GameSourceState.PAUSED, m.at(11_000).state)        // sesi kembali, menunggu frame pertama
        m.onFrames(11_200, 5)
        assertEquals(GameSourceState.LIVE, m.at(13_000).state)
    }

    @Test fun notMonitoringIsInactiveAndResetsFallback() {
        val m = GameSourceMonitor()
        m.at(0); m.at(3_000); m.at(6_000); m.at(8_000)
        val r = m.at(9_000, monitor = false)
        assertEquals(GameSourceState.INACTIVE, r.state)
        // Studio ditutup lagi: masa tenggang baru, bukan langsung fallback
        assertEquals(GameSourceState.LIVE, m.at(10_000).state)
        assertFalse(m.at(10_500).state == GameSourceState.PAUSED)
    }
}
