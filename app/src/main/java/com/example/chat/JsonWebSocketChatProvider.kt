package com.example.chat

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Provider nyata: terhubung ke WebSocket pilihan pengguna yang mengirim skema "VILICHT Chat JSON v1"
 * (lihat [ChatEventParser]). Tidak ada endpoint bawaan dan TIDAK ada klaim bahwa ini API resmi TikTok:
 * TikTok tidak menyediakan API publik resmi untuk komentar LIVE. Pengguna menyambungkan jembatan/layanan
 * pihak ketiga yang ia pilih sendiri (contoh jembatan: docs/tools/tiktok_bridge/). Token dikirim lewat
 * header Authorization, tidak pernah lewat URL.
 */
class JsonWebSocketChatProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)          // deteksi koneksi mati
        .readTimeout(0, TimeUnit.MILLISECONDS)        // WebSocket: tanpa batas baca
        .build()
) : ChatProvider {
    override val id = "vilicht-json-ws"
    override val displayName = "WebSocket (skema VILICHT JSON)"
    override val capabilities = ProviderCapabilities(
        comments = true, gifts = true, viewerCount = true,
        needsEndpoint = true, needsCredential = false, official = false,
        note = "Bukan API resmi TikTok. TikTok tidak punya API publik resmi untuk komentar LIVE; gunakan jembatan atau " +
            "layanan pihak ketiga yang mengirim skema VILICHT JSON. Pemakaiannya menjadi tanggung jawab Anda terhadap " +
            "ketentuan layanan TikTok. Gift dan viewers hanya muncul bila jembatan mengirimnya."
    )

    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation = 0

    override fun connect(config: ChatConnectionConfig, sink: ChatSink) {
        val ep = ChatEndpoint.validate(config.endpoint)
        if (ep is ChatEndpoint.Result.Invalid) { sink.onState(ProviderState.FATAL, ep.message); return }
        val uri = (ep as ChatEndpoint.Result.Ok).uri
        val gen = ++generation
        sink.onState(ProviderState.CONNECTING)
        val req = try {
            Request.Builder().url(uri.replaceFirst("ws", "http")).apply {
                header("User-Agent", "VILICHT-LiveStudio")
                if (!config.credential.isNullOrBlank()) header("Authorization", "Bearer ${config.credential}")
                if (config.username.isNotBlank()) header("X-Vilicht-Username", config.username.take(60))
            }.build()
        } catch (e: IllegalArgumentException) { sink.onState(ProviderState.FATAL, "URL ditolak: ${e.message}"); return }

        socket = client.newWebSocket(req, object : WebSocketListener() {
            private fun stale() = gen != generation
            override fun onOpen(webSocket: WebSocket, response: Response) { if (!stale()) sink.onState(ProviderState.CONNECTED) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stale()) return
                when (val p = ChatEventParser.parse(text)) {
                    is ChatEventParser.Parsed.Event -> sink.onEvent(p.event)
                    is ChatEventParser.Parsed.Viewers -> sink.onViewerCount(p.count)
                    is ChatEventParser.Parsed.Invalid, ChatEventParser.Parsed.Ignored -> {}
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stale()) sink.onState(ProviderState.LOST, "Koneksi ditutup ($code)")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stale()) return
                val code = response?.code
                // 401/403/404 = konfigurasi salah; mencoba ulang tidak membantu
                if (code == 401 || code == 403 || code == 404) sink.onState(ProviderState.FATAL, "Server menolak (HTTP $code)")
                else sink.onState(ProviderState.LOST, t.message ?: "Koneksi terputus")
            }
        })
    }

    override fun disconnect() {
        generation++               // semua callback lama dianggap basi; tidak memicu LOST
        socket?.close(1000, "client disconnect")
        socket = null
    }
}

/** Daftar provider yang tersedia. Tambah provider baru di sini. */
object ChatProviders {
    fun all(): List<ChatProvider> = listOf(JsonWebSocketChatProvider())
}
