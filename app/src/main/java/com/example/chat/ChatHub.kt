package com.example.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Pusat chat: memegang satu provider aktif, membuang duplikat, menyimpan N pesan terakhir, mengelola reconnect
 * dengan backoff, dan menyiarkan status/viewers. TIDAK mengenal RTMP, encoder, ataupun StreamingService:
 * memutus/menyambung chat tidak pernah menyentuh siaran, dan sebaliknya.
 *
 * Semua mutasi dijalankan di [scope]; produksi memakai scope berparalelisme 1 sehingga callback provider
 * dari thread OkHttp diserialkan. Waktu dan backoff disuntikkan agar bisa diuji.
 */
class ChatHub(
    private val scope: CoroutineScope,
    private val providers: List<ChatProvider>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val backoff: ChatBackoff = ChatBackoff(),
    private val maxMessages: Int = 200
) : ChatSink {
    private val deduper = ChatDeduper()
    private var seq = 0L
    private var active: ChatProvider? = null
    private var config: ChatConnectionConfig? = null
    private var wantConnected = false
    private var attempt = 0
    private var reconnectJob: Job? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages
    private val _state = MutableStateFlow(ChatConnectionState.IDLE)
    val state: StateFlow<ChatConnectionState> = _state
    private val _detail = MutableStateFlow<String?>(null)
    val detail: StateFlow<String?> = _detail
    /** null = tidak tersedia (tampil "--"); tidak pernah diisi angka karangan. */
    private val _viewers = MutableStateFlow<Int?>(null)
    val viewers: StateFlow<Int?> = _viewers
    private val _accepted = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    /** Setiap pesan BARU (setelah dedupe), untuk TTS dsb. */
    val accepted: SharedFlow<ChatMessage> = _accepted

    fun provider(id: String): ChatProvider? = providers.firstOrNull { it.id == id }

    fun connect(providerId: String, cfg: ChatConnectionConfig) {
        scope.launch {
            val p = provider(providerId)
            if (p == null) { fail("Provider tidak dikenal"); return@launch }
            stopInternal()
            active = p; config = cfg; wantConnected = true; attempt = 0
            _viewers.value = null
            startProvider()
        }
    }

    fun disconnect() { scope.launch { stopInternal(); _state.value = ChatConnectionState.IDLE; _detail.value = null } }

    /** Komentar uji lokal, ditandai LOCAL_TEST. Tidak lewat provider; untuk memeriksa overlay/TTS. */
    fun injectLocalTest(text: String) {
        scope.launch { accept(ChatEvent(ChatEventKind.COMMENT, "uji-lokal", text.take(ChatEventParser.MAX_TEXT), timestampMs = clock(), source = ChatEvent.Source.LOCAL_TEST), force = true) }
    }

    fun clearMessages() { scope.launch { _messages.value = emptyList(); deduper.clear() } }

    // ---- ChatSink (dipanggil provider; diserialkan lewat scope)
    override fun onEvent(event: ChatEvent) { scope.launch { accept(event) } }
    override fun onViewerCount(count: Int?) { scope.launch { _viewers.value = count } }
    override fun onState(state: ProviderState, detail: String?) {
        scope.launch {
            if (!wantConnected) return@launch
            when (state) {
                ProviderState.CONNECTING -> if (_state.value != ChatConnectionState.RECONNECTING) _state.value = ChatConnectionState.CONNECTING
                ProviderState.CONNECTED -> { attempt = 0; _state.value = ChatConnectionState.CONNECTED; _detail.value = null }
                ProviderState.LOST -> scheduleReconnect(detail)
                ProviderState.FATAL -> { stopInternal(); fail(detail ?: "Koneksi gagal") }
            }
        }
    }

    private fun startProvider() {
        val p = active ?: return
        val c = config ?: return
        if (_state.value != ChatConnectionState.RECONNECTING) _state.value = ChatConnectionState.CONNECTING
        try { p.connect(c, this) } catch (e: Exception) { scheduleReconnect(e.message) }
    }

    private fun scheduleReconnect(reason: String?) {
        if (!wantConnected) return
        _state.value = ChatConnectionState.RECONNECTING
        _viewers.value = null
        val d = backoff.delayFor(attempt++)
        _detail.value = (reason ?: "Terputus") + " · mencoba lagi ${d / 1000}s"
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(d)
            if (!wantConnected) return@launch
            try { active?.disconnect() } catch (_: Exception) {}
            startProvider()
        }
    }

    private fun stopInternal() {
        wantConnected = false
        reconnectJob?.cancel(); reconnectJob = null
        try { active?.disconnect() } catch (_: Exception) {}
        active = null; config = null
        _viewers.value = null
    }

    private fun fail(msg: String) { _state.value = ChatConnectionState.FAILED; _detail.value = msg }

    private suspend fun accept(e: ChatEvent, force: Boolean = false) {
        if (!force && !deduper.isNew(e)) return
        val m = ChatMessage(++seq, e, clock())
        _messages.value = (_messages.value + m).takeLast(maxMessages)
        _accepted.emit(m)
    }
}
