package com.example.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Pengaturan chat (tanpa rahasia). Token disimpan terpisah dan terenkripsi oleh [ChatSettingsStore]. */
data class ChatSettings(
    val providerId: String = "vilicht-json-ws",
    val username: String = "",
    val endpoint: String = "",
    val autoConnect: Boolean = false,
    /** Chat aktif: mati = koneksi diputus dan event diabaikan. */
    val chatEnabled: Boolean = true,
    /** Hanya tampilan panel chat; tidak memengaruhi koneksi maupun LIVE. */
    val panelOpen: Boolean = true,
    val ttsEnabled: Boolean = false,
    val ttsVolume: Float = 1f,
    val ttsRate: Float = 1f,
    val ttsReadNames: Boolean = true,
    val ttsReadGifts: Boolean = false,
    /** true = suara TTS ikut ke stream (tertangkap AudioPlaybackCapture); false = hanya terdengar streamer. */
    val ttsToStream: Boolean = false,
    // Overlay komentar (dirender ke output encoder lewat sistem layer yang ada)
    val overlayMaxLines: Int = 6,
    val overlayTextScale: Float = 1f,
    val overlayOpacity: Float = 0.85f,
    val overlayTtlSec: Int = 20,
    val overlayShowGifts: Boolean = true
) {
    fun sanitized() = copy(
        ttsVolume = ttsVolume.coerceIn(0f, 1f), ttsRate = ttsRate.coerceIn(0.5f, 2f),
        overlayMaxLines = overlayMaxLines.coerceIn(1, 12), overlayTextScale = overlayTextScale.coerceIn(0.6f, 2f),
        overlayOpacity = overlayOpacity.coerceIn(0.1f, 1f), overlayTtlSec = overlayTtlSec.coerceIn(5, 120),
        username = username.trim().take(60), endpoint = endpoint.trim().take(300)
    )
}

class ChatSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vl_chat", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ChatSettings> = _settings

    /** Token di memori (fallback bila Keystore gagal; tidak pernah ditulis plaintext ke disk). */
    @Volatile private var memoryToken: String? = null

    fun update(block: (ChatSettings) -> ChatSettings) {
        val n = block(_settings.value).sanitized()
        _settings.value = n
        prefs.edit()
            .putString("providerId", n.providerId).putString("username", n.username).putString("endpoint", n.endpoint)
            .putBoolean("autoConnect", n.autoConnect).putBoolean("chatEnabled", n.chatEnabled).putBoolean("panelOpen", n.panelOpen)
            .putBoolean("ttsEnabled", n.ttsEnabled).putFloat("ttsVolume", n.ttsVolume).putFloat("ttsRate", n.ttsRate)
            .putBoolean("ttsReadNames", n.ttsReadNames).putBoolean("ttsReadGifts", n.ttsReadGifts).putBoolean("ttsToStream", n.ttsToStream)
            .putInt("ovMax", n.overlayMaxLines).putFloat("ovScale", n.overlayTextScale).putFloat("ovOpacity", n.overlayOpacity)
            .putInt("ovTtl", n.overlayTtlSec).putBoolean("ovGifts", n.overlayShowGifts)
            .apply()
    }

    private fun load(): ChatSettings {
        val d = ChatSettings()
        return ChatSettings(
            providerId = prefs.getString("providerId", d.providerId) ?: d.providerId,
            username = prefs.getString("username", "") ?: "", endpoint = prefs.getString("endpoint", "") ?: "",
            autoConnect = prefs.getBoolean("autoConnect", d.autoConnect), chatEnabled = prefs.getBoolean("chatEnabled", d.chatEnabled),
            panelOpen = prefs.getBoolean("panelOpen", d.panelOpen), ttsEnabled = prefs.getBoolean("ttsEnabled", d.ttsEnabled),
            ttsVolume = prefs.getFloat("ttsVolume", d.ttsVolume), ttsRate = prefs.getFloat("ttsRate", d.ttsRate),
            ttsReadNames = prefs.getBoolean("ttsReadNames", d.ttsReadNames), ttsReadGifts = prefs.getBoolean("ttsReadGifts", d.ttsReadGifts),
            ttsToStream = prefs.getBoolean("ttsToStream", d.ttsToStream),
            overlayMaxLines = prefs.getInt("ovMax", d.overlayMaxLines), overlayTextScale = prefs.getFloat("ovScale", d.overlayTextScale),
            overlayOpacity = prefs.getFloat("ovOpacity", d.overlayOpacity), overlayTtlSec = prefs.getInt("ovTtl", d.overlayTtlSec),
            overlayShowGifts = prefs.getBoolean("ovGifts", d.overlayShowGifts)
        ).sanitized()
    }

    /** Simpan token terenkripsi. Mengembalikan false bila Keystore gagal (token tetap ada di memori sesi ini saja). */
    fun setToken(token: String): Boolean {
        memoryToken = token.ifBlank { null }
        if (token.isBlank()) { prefs.edit().remove("tokenEnc").apply(); return true }
        val enc = SecretBox.encrypt(token)
        if (enc == null) { prefs.edit().remove("tokenEnc").apply(); return false }
        prefs.edit().putString("tokenEnc", enc).apply()
        return true
    }

    fun getToken(): String? = memoryToken ?: prefs.getString("tokenEnc", null)?.let { SecretBox.decrypt(it) }?.also { memoryToken = it }
    fun hasToken(): Boolean = getToken() != null
}
