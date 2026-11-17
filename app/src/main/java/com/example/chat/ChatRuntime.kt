package com.example.chat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Satu-satunya instans chat per proses: hub + pengaturan + TTS. Hidup mandiri dari StreamingService, jadi membuka
 * atau menutup panel/jendela melayang dan memulai/menghentikan LIVE tidak memutus chat (dan sebaliknya).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRuntime private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    val store = ChatSettingsStore(app)
    val hub = ChatHub(scope, ChatProviders.all())
    val tts = ChatTts(app)

    init {
        // TTS: baca setiap pesan baru bila diaktifkan
        scope.launch {
            hub.accepted.collect { m ->
                val s = store.settings.value
                if (!s.chatEnabled || !s.ttsEnabled || m.event.source != ChatEvent.Source.PROVIDER && m.event.source != ChatEvent.Source.LOCAL_TEST) return@collect
                TtsTextFormatter.format(m.event, s)?.let { tts.speak(it, s) }
            }
        }
        // Menyalakan TTS menyiapkan mesin; mematikannya menghentikan ucapan yang antre
        scope.launch {
            store.settings.map { it.ttsEnabled }.distinctUntilChanged().collectLatest { on -> if (on) tts.prepare() else tts.stop() }
        }
        // Chat dimatikan = putuskan koneksi (siaran tidak tersentuh)
        scope.launch {
            store.settings.map { it.chatEnabled }.distinctUntilChanged().collectLatest { on -> if (!on) hub.disconnect() }
        }
        if (store.settings.value.autoConnect && store.settings.value.chatEnabled) connectFromSettings()
    }

    /** Sambungkan memakai pengaturan tersimpan. Mengembalikan pesan galat bila tidak bisa dimulai, null bila dimulai. */
    fun connectFromSettings(): String? {
        val s = store.settings.value
        if (!s.chatEnabled) return "Chat sedang dinonaktifkan"
        val p = hub.provider(s.providerId) ?: return "Provider tidak dikenal"
        if (p.capabilities.needsEndpoint && ChatEndpoint.validate(s.endpoint) is ChatEndpoint.Result.Invalid)
            return (ChatEndpoint.validate(s.endpoint) as ChatEndpoint.Result.Invalid).message
        hub.connect(p.id, ChatConnectionConfig(s.username, s.endpoint, store.getToken()))
        return null
    }

    fun disconnect() = hub.disconnect()

    companion object {
        @Volatile private var instance: ChatRuntime? = null
        fun get(context: Context): ChatRuntime =
            instance ?: synchronized(this) { instance ?: ChatRuntime(context).also { instance = it } }
    }
}
