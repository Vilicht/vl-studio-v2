package com.example.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatHubTest {
    private class Fake : ChatProvider {
        override val id = "fake"; override val displayName = "Fake"
        override val capabilities = ProviderCapabilities(true, true, true, false, false, false, "")
        var connects = 0; var disconnects = 0
        var sink: ChatSink? = null
        override fun connect(config: ChatConnectionConfig, sink: ChatSink) { connects++; this.sink = sink }
        override fun disconnect() { disconnects++ }
    }

    private val cfg = ChatConnectionConfig("u", "", null)
    private fun setup(): Triple<TestScope, ChatHub, Fake> {
        val scope = TestScope(); val f = Fake()
        return Triple(scope, ChatHub(scope, listOf(f), { scope.currentTime }, ChatBackoff(1000, 30000) { 0 }, maxMessages = 3), f)
    }

    @Test fun connectEventsDedupeAndCap() {
        val (s, hub, f) = setup()
        hub.connect("fake", cfg); s.runCurrent()
        f.sink!!.onState(ProviderState.CONNECTED); s.runCurrent()
        assertEquals(ChatConnectionState.CONNECTED, hub.state.value)
        val e = ChatEvent(ChatEventKind.COMMENT, "a", "x", id = "1")
        f.sink!!.onEvent(e); f.sink!!.onEvent(e); s.runCurrent()
        assertEquals(1, hub.messages.value.size)                       // duplikat dibuang
        for (i in 2..6) f.sink!!.onEvent(e.copy(id = "$i")); s.runCurrent()
        assertEquals(3, hub.messages.value.size)                       // dibatasi
        assertEquals("6", hub.messages.value.last().event.id)
    }

    @Test fun lostReconnectsWithBackoffAndResetsOnConnected() {
        val (s, hub, f) = setup()
        hub.connect("fake", cfg); s.runCurrent(); f.sink!!.onState(ProviderState.CONNECTED); s.runCurrent()
        f.sink!!.onViewerCount(50); s.runCurrent(); assertEquals(50, hub.viewers.value)
        f.sink!!.onState(ProviderState.LOST, "putus"); s.runCurrent()
        assertEquals(ChatConnectionState.RECONNECTING, hub.state.value)
        assertNull("viewers tidak boleh basi", hub.viewers.value)
        s.advanceTimeBy(999); s.runCurrent(); assertEquals(1, f.connects)
        s.advanceTimeBy(2); s.runCurrent(); assertEquals(2, f.connects)
        f.sink!!.onState(ProviderState.LOST); s.runCurrent()           // gagal lagi -> jeda 2 s
        s.advanceTimeBy(1_999); s.runCurrent(); assertEquals(2, f.connects)
        s.advanceTimeBy(2); s.runCurrent(); assertEquals(3, f.connects)
        f.sink!!.onState(ProviderState.CONNECTED); s.runCurrent()
        assertEquals(ChatConnectionState.CONNECTED, hub.state.value)
        f.sink!!.onState(ProviderState.LOST); s.runCurrent()           // attempt direset: kembali 1 s
        s.advanceTimeBy(1_001); s.runCurrent(); assertEquals(4, f.connects)
    }

    @Test fun fatalFailsWithoutRetry() {
        val (s, hub, f) = setup()
        hub.connect("fake", cfg); s.runCurrent()
        f.sink!!.onState(ProviderState.FATAL, "ditolak"); s.runCurrent()
        assertEquals(ChatConnectionState.FAILED, hub.state.value); assertEquals("ditolak", hub.detail.value)
        s.advanceTimeBy(120_000); s.runCurrent(); assertEquals(1, f.connects)
    }

    @Test fun intentionalDisconnectStopsEverything() {
        val (s, hub, f) = setup()
        hub.connect("fake", cfg); s.runCurrent(); f.sink!!.onState(ProviderState.CONNECTED); s.runCurrent()
        hub.disconnect(); s.runCurrent()
        assertEquals(ChatConnectionState.IDLE, hub.state.value)
        f.sink!!.onState(ProviderState.LOST); s.runCurrent()           // callback basi diabaikan
        s.advanceTimeBy(60_000); s.runCurrent()
        assertEquals(1, f.connects); assertEquals(ChatConnectionState.IDLE, hub.state.value)
    }

    @Test fun unknownProviderFails() {
        val (s, hub, _) = setup()
        hub.connect("nope", cfg); s.runCurrent()
        assertEquals(ChatConnectionState.FAILED, hub.state.value)
    }

    @Test fun localTestIsMarkedAndNeverDeduped() {
        val (s, hub, _) = setup()
        hub.injectLocalTest("a"); hub.injectLocalTest("a"); s.runCurrent()
        assertEquals(2, hub.messages.value.size)
        assertTrue(hub.messages.value.all { it.event.source == ChatEvent.Source.LOCAL_TEST })
    }
}
