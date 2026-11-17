package com.example.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCoreTest {
    private fun ev(r: ChatEventParser.Parsed) = (r as ChatEventParser.Parsed.Event).event

    @Test fun parsesComment() {
        val e = ev(ChatEventParser.parse("""{"type":"comment","id":"a1","user":"  Budi ","text":"halo   dunia"}""", 5))
        assertEquals(ChatEventKind.COMMENT, e.kind); assertEquals("Budi", e.user); assertEquals("halo dunia", e.text); assertEquals("a1", e.id)
    }

    @Test fun parsesGiftLikeFollowAndViewers() {
        val g = ev(ChatEventParser.parse("""{"type":"gift","user":"A","gift":"Rose","count":3}"""))
        assertEquals(ChatEventKind.GIFT, g.kind); assertEquals(3, g.count); assertEquals("Rose", g.text)
        assertEquals(ChatEventKind.FOLLOW, ev(ChatEventParser.parse("""{"type":"follow","user":"A"}""")).kind)
        assertEquals(ChatEventKind.LIKE, ev(ChatEventParser.parse("""{"type":"like","user":"A","count":9}""")).kind)
        assertEquals(123, (ChatEventParser.parse("""{"type":"viewers","count":123}""") as ChatEventParser.Parsed.Viewers).count)
    }

    @Test fun rejectsBadInputWithoutThrowing() {
        for (s in listOf("not json", "[]", """{"user":"a"}""", """{"type":"comment","user":"","text":"x"}""",
            """{"type":"viewers","count":-5}""", """{"type":"viewers","count":"x"}"""))
            assertTrue(s, ChatEventParser.parse(s) is ChatEventParser.Parsed.Invalid)
        assertTrue(ChatEventParser.parse("""{"type":"mystery"}""") === ChatEventParser.Parsed.Ignored)
        assertTrue(ChatEventParser.parse("x".repeat(ChatEventParser.MAX_PAYLOAD + 1)) is ChatEventParser.Parsed.Invalid)
    }

    @Test fun cleansControlCharsAndLimitsLength() {
        assertEquals("a b", ChatEventParser.clean("a\u0000\n\t b", 50))
        assertEquals(ChatEventParser.MAX_TEXT, ChatEventParser.clean("z".repeat(1000), ChatEventParser.MAX_TEXT).length)
    }

    @Test fun deduperUsesIdThenContentWindow() {
        val d = ChatDeduper(capacity = 3)
        val a = ChatEvent(ChatEventKind.COMMENT, "u", "t", id = "1", timestampMs = 0)
        assertTrue(d.isNew(a)); assertFalse(d.isNew(a.copy(text = "beda")))   // id sama = duplikat
        val b = ChatEvent(ChatEventKind.COMMENT, "u", "hi", timestampMs = 1_000)
        assertTrue(d.isNew(b)); assertFalse(d.isNew(b.copy(timestampMs = 2_000)))   // jendela 3 s sama
        assertTrue(d.isNew(b.copy(timestampMs = 10_000)))                            // jendela lain = baru
    }

    @Test fun deduperEvictsOldest() {
        val d = ChatDeduper(capacity = 2)
        val e = { i: Int -> ChatEvent(ChatEventKind.COMMENT, "u", "t", id = "$i") }
        d.isNew(e(1)); d.isNew(e(2)); d.isNew(e(3))
        assertTrue("1 sudah terbuang", d.isNew(e(1)))
    }

    @Test fun backoffDoublesAndCaps() {
        val b = ChatBackoff(1_000, 30_000) { 0 }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (0..6).map { b.delayFor(it) })
        val j = ChatBackoff(1_000, 30_000) { it }
        assertTrue((0..30).all { j.delayFor(it) <= 30_000 })
    }

    @Test fun endpointRules() {
        assertTrue(ChatEndpoint.validate("wss://bridge.example.com/chat") is ChatEndpoint.Result.Ok)
        assertTrue(ChatEndpoint.validate("ws://192.168.1.20:8080") is ChatEndpoint.Result.Ok)
        assertTrue(ChatEndpoint.validate("ws://localhost:8080") is ChatEndpoint.Result.Ok)
        assertTrue(ChatEndpoint.validate("ws://example.com/chat") is ChatEndpoint.Result.Invalid)
        assertTrue(ChatEndpoint.validate("http://example.com") is ChatEndpoint.Result.Invalid)
        assertTrue(ChatEndpoint.validate("wss://user:pw@example.com") is ChatEndpoint.Result.Invalid)
        assertTrue(ChatEndpoint.validate("wss://example.com/?token=abc") is ChatEndpoint.Result.Invalid)
        assertTrue(ChatEndpoint.validate("") is ChatEndpoint.Result.Invalid)
        assertFalse(ChatEndpoint.isLocalOrPrivate("8.8.8.8")); assertTrue(ChatEndpoint.isLocalOrPrivate("172.20.0.1"))
        assertFalse(ChatEndpoint.isLocalOrPrivate("172.32.0.1"))
    }

    @Test fun ttsFormatter() {
        val s = ChatSettings(ttsReadNames = true, ttsReadGifts = false)
        val c = ChatEvent(ChatEventKind.COMMENT, "Ani", "halooooooo cek https://x.com/a 😀")
        assertEquals("Ani berkata: halooo cek tautan", TtsTextFormatter.format(c, s))
        assertEquals("hai", TtsTextFormatter.format(ChatEvent(ChatEventKind.COMMENT, "Ani", "hai"), s.copy(ttsReadNames = false)))
        assertNull(TtsTextFormatter.format(ChatEvent(ChatEventKind.COMMENT, "Ani", "😀😀"), s))
        val g = ChatEvent(ChatEventKind.GIFT, "Ani", "Rose", count = 2)
        assertNull(TtsTextFormatter.format(g, s))
        assertEquals("Ani mengirim 2 Rose", TtsTextFormatter.format(g, s.copy(ttsReadGifts = true)))
        assertNull(TtsTextFormatter.format(ChatEvent(ChatEventKind.FOLLOW, "Ani"), s))
        assertTrue(TtsTextFormatter.sanitize("a".repeat(10) + " b".repeat(300)).length <= TtsTextFormatter.MAX_CHARS)
    }

    @Test fun settingsSanitizeClampsRanges() {
        val s = ChatSettings(ttsVolume = 5f, ttsRate = 0f, overlayMaxLines = 99, overlayOpacity = 0f, overlayTtlSec = 1).sanitized()
        assertEquals(1f, s.ttsVolume); assertEquals(0.5f, s.ttsRate); assertEquals(12, s.overlayMaxLines)
        assertEquals(0.1f, s.overlayOpacity); assertEquals(5, s.overlayTtlSec)
    }
}
