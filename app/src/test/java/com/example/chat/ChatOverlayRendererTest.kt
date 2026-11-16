package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatOverlayRendererTest {
    private fun msg(seq: Long, at: Long, kind: ChatEventKind = ChatEventKind.COMMENT) =
        ChatMessage(seq, ChatEvent(kind, "u$seq", "t$seq", timestampMs = at), at)
    private val s = ChatSettings(overlayMaxLines = 3, overlayTtlSec = 10)

    @Test fun limitsLinesAndKeepsNewest() {
        val m = (1L..6L).map { msg(it, 0) }
        val v = ChatOverlayRenderer.visibleLines(m, s, 5_000)
        assertEquals(listOf(4L, 5L, 6L), v.map { it.key })
    }

    @Test fun expiresAfterTtl() {
        val m = listOf(msg(1, 0))
        assertEquals(1, ChatOverlayRenderer.visibleLines(m, s, 9_999).size)
        assertEquals(0, ChatOverlayRenderer.visibleLines(m, s, 10_000).size)
    }

    @Test fun enterAndExitAnimationValues() {
        val m = listOf(msg(1, 1_000))
        val start = ChatOverlayRenderer.visibleLines(m, s, 1_000).single()
        assertEquals(0f, start.alpha, 1e-3f); assertEquals(1f, start.slide, 1e-3f)
        val mid = ChatOverlayRenderer.visibleLines(m, s, 5_000).single()
        assertEquals(1f, mid.alpha, 1e-3f); assertEquals(0f, mid.slide, 1e-3f)
        assertFalse(ChatOverlayRenderer.isAnimating(listOf(mid)))
        val fading = ChatOverlayRenderer.visibleLines(m, s, 1_000 + 10_000 - 300).single()
        assertTrue(fading.alpha in 0.3f..0.7f); assertTrue(ChatOverlayRenderer.isAnimating(listOf(fading)))
    }

    @Test fun giftsOnlyWhenEnabledAndOtherKindsNever() {
        val m = listOf(msg(1, 0, ChatEventKind.GIFT), msg(2, 0, ChatEventKind.FOLLOW), msg(3, 0))
        assertEquals(listOf(1L, 3L), ChatOverlayRenderer.visibleLines(m, s.copy(overlayShowGifts = true), 5_000).map { it.key })
        assertEquals(listOf(3L), ChatOverlayRenderer.visibleLines(m, s.copy(overlayShowGifts = false), 5_000).map { it.key })
    }

    @Test fun signatureChangesOnlyWhenOutputChanges() {
        val l = ChatOverlayRenderer.visibleLines(listOf(msg(1, 0)), s, 5_000)
        val a = ChatOverlayRenderer.signature(l, s, 100, 50)
        assertEquals(a, ChatOverlayRenderer.signature(l, s, 100, 50))
        assertTrue(a != ChatOverlayRenderer.signature(l, s.copy(overlayOpacity = 0.3f), 100, 50))
        assertTrue(a != ChatOverlayRenderer.signature(l, s, 120, 50))
    }

    @Test fun bitmapSizeKeepsAspect() {
        val (w, h) = ChatOverlayRenderer.sizeFor(1000, 250)
        assertEquals(1000f / 250f, w.toFloat() / h, 0.05f)
        assertTrue(maxOf(w, h) <= 720)
    }
}
