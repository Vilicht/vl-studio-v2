package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.chat.ChatConnectionState
import com.example.chat.ChatEventKind
import com.example.chat.ChatRuntime
import com.example.ui.theme.*

/**
 * Kontrol chat LIVE: koneksi provider, chat/TTS (aktif, volume, kecepatan), panel chat, dan overlay komentar.
 * Koneksi chat TERPISAH dari RTMP: menutup dialog, memutus chat, atau mematikan TTS tidak menyentuh siaran.
 */
@Composable
fun ChatDialog(
    isOpen: Boolean,
    runtime: ChatRuntime,
    /** null = scene belum punya overlay komentar; true/false = terlihat/tersembunyi. */
    overlayVisible: Boolean?,
    onToggleOverlay: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!isOpen) return
    val s by runtime.store.settings.collectAsStateWithLifecycle()
    val state by runtime.hub.state.collectAsStateWithLifecycle()
    val detail by runtime.hub.detail.collectAsStateWithLifecycle()
    val viewers by runtime.hub.viewers.collectAsStateWithLifecycle()
    val messages by runtime.hub.messages.collectAsStateWithLifecycle()
    val provider = runtime.hub.provider(s.providerId)

    var username by remember { mutableStateOf(s.username) }
    var endpoint by remember { mutableStateOf(s.endpoint) }
    var token by remember { mutableStateOf("") }
    var tokenNote by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }

    fun saveFields() {
        runtime.store.update { it.copy(username = username, endpoint = endpoint) }
        if (token.isNotEmpty()) {
            tokenNote = if (runtime.store.setToken(token)) null
            else "Keystore perangkat gagal: token HANYA disimpan di memori sesi ini, tidak ke disk."
            token = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat LIVE") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("chat_dialog"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Status (terpisah dari status RTMP)
                val label = when (state) {
                    ChatConnectionState.IDLE -> "Tidak terhubung"
                    ChatConnectionState.CONNECTING -> "Menghubungkan…"
                    ChatConnectionState.CONNECTED -> "Terhubung"
                    ChatConnectionState.RECONNECTING -> "Menyambung ulang…"
                    ChatConnectionState.FAILED -> "Gagal"
                }
                Text("Koneksi chat: $label", fontSize = 13.sp, color = ChampagneGold)
                detail?.let { Text(it, fontSize = 11.sp, color = TextMuted) }
                Text("Viewers: ${viewers?.toString() ?: "--"}" + if (viewers == null) "  (tidak tersedia dari provider)" else "", fontSize = 12.sp)
                provider?.let { Text(it.capabilities.note, fontSize = 10.sp, color = TextMuted) }

                OutlinedTextField(username, { username = it }, label = { Text("Username TikTok (tanpa @)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("chat_username"))
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Endpoint WebSocket (wss://…)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("chat_endpoint"))
                OutlinedTextField(token, { token = it },
                    label = { Text(if (runtime.store.hasToken()) "Token (tersimpan terenkripsi; isi untuk mengganti)" else "Token / API key (opsional)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("chat_token"))
                tokenNote?.let { Text(it, fontSize = 10.sp, color = StatusAmber) }
                formError?.let { Text(it, fontSize = 11.sp, color = StatusAmber) }
                Text("Jangan masukkan password TikTok. Aplikasi tidak pernah memintanya.", fontSize = 10.sp, color = TextMuted)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { saveFields(); formError = runtime.connectFromSettings() },
                        enabled = state != ChatConnectionState.CONNECTING,
                        colors = ButtonDefaults.buttonColors(containerColor = ChampagneGold, contentColor = CharcoalMain),
                        modifier = Modifier.testTag("chat_connect")
                    ) { Text(if (state == ChatConnectionState.CONNECTED) "Sambung ulang" else "Hubungkan", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { runtime.disconnect(); formError = null },
                        enabled = state != ChatConnectionState.IDLE, modifier = Modifier.testTag("chat_disconnect")
                    ) { Text("Putuskan", fontSize = 12.sp) }
                }
                SwitchRow("Sambung otomatis saat aplikasi dibuka", s.autoConnect) { v -> runtime.store.update { it.copy(autoConnect = v) } }

                HorizontalDivider()
                SwitchRow("Chat aktif (mati = diputus & diabaikan)", s.chatEnabled, "chat_enabled") { v -> runtime.store.update { it.copy(chatEnabled = v) } }
                SwitchRow("Tampilkan panel chat", s.panelOpen, "chat_panel_switch") { v -> runtime.store.update { it.copy(panelOpen = v) } }

                HorizontalDivider()
                SwitchRow("TTS: bacakan komentar", s.ttsEnabled, "tts_enabled") { v -> runtime.store.update { it.copy(ttsEnabled = v) } }
                SwitchRow("Baca nama pengirim", s.ttsReadNames) { v -> runtime.store.update { it.copy(ttsReadNames = v) } }
                SwitchRow("Baca gift", s.ttsReadGifts) { v -> runtime.store.update { it.copy(ttsReadGifts = v) } }
                SwitchRow("Suara TTS ikut ke stream", s.ttsToStream) { v -> runtime.store.update { it.copy(ttsToStream = v) } }
                Text("Ikut ke stream hanya berfungsi bila capture audio game aktif.", fontSize = 10.sp, color = TextMuted)
                SliderRow("Volume TTS", s.ttsVolume, 0f..1f, "${(s.ttsVolume * 100).toInt()}%") { v -> runtime.store.update { it.copy(ttsVolume = v) } }
                SliderRow("Kecepatan baca", s.ttsRate, 0.5f..2f, "${"%.1f".format(s.ttsRate)}x") { v -> runtime.store.update { it.copy(ttsRate = v) } }

                HorizontalDivider()
                Text("Overlay komentar (masuk ke output siaran)", fontSize = 13.sp, color = ChampagneGold)
                OutlinedButton(onClick = onToggleOverlay, modifier = Modifier.testTag("chat_overlay_toggle")) {
                    Text(when (overlayVisible) { null -> "Tambahkan overlay komentar ke scene"; true -> "Sembunyikan overlay"; false -> "Tampilkan overlay" }, fontSize = 12.sp)
                }
                Text("Posisi dan ukuran: geser/ubah ukuran layer \"Chat Overlay\" di canvas seperti source lain.", fontSize = 10.sp, color = TextMuted)
                SliderRow("Jumlah baris maks", s.overlayMaxLines.toFloat(), 1f..12f, "${s.overlayMaxLines}") { v -> runtime.store.update { it.copy(overlayMaxLines = v.toInt()) } }
                SliderRow("Ukuran teks", s.overlayTextScale, 0.6f..2f, "${(s.overlayTextScale * 100).toInt()}%") { v -> runtime.store.update { it.copy(overlayTextScale = v) } }
                SliderRow("Transparansi (opasitas)", s.overlayOpacity, 0.1f..1f, "${(s.overlayOpacity * 100).toInt()}%") { v -> runtime.store.update { it.copy(overlayOpacity = v) } }
                SliderRow("Komentar hilang setelah", s.overlayTtlSec.toFloat(), 5f..120f, "${s.overlayTtlSec} dtk") { v -> runtime.store.update { it.copy(overlayTtlSec = v.toInt()) } }
                SwitchRow("Tampilkan gift di overlay", s.overlayShowGifts) { v -> runtime.store.update { it.copy(overlayShowGifts = v) } }

                HorizontalDivider()
                OutlinedButton(onClick = { runtime.hub.injectLocalTest("Komentar uji lokal") }, modifier = Modifier.testTag("chat_test")) {
                    Text("Kirim komentar uji (lokal, bukan dari TikTok)", fontSize = 12.sp)
                }
                if (s.panelOpen) {
                    Text("Pesan terbaru", fontSize = 12.sp, color = ChampagneGold)
                    if (messages.isEmpty()) Text("Belum ada pesan.", fontSize = 11.sp, color = TextMuted)
                    messages.takeLast(12).reversed().forEach { m ->
                        val e = m.event
                        val line = when (e.kind) {
                            ChatEventKind.COMMENT -> "${e.user}: ${e.text}"
                            ChatEventKind.GIFT -> "${e.user} mengirim ${e.count}× ${e.text}"
                            ChatEventKind.SYSTEM -> e.text
                            else -> "${e.user} (${e.kind.name.lowercase()})"
                        }
                        Text((if (e.source == com.example.chat.ChatEvent.Source.LOCAL_TEST) "[uji] " else "") + line, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { saveFields(); onDismiss() }) { Text("Tutup") } }
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, tag: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(display, fontSize = 12.sp, color = TextMuted)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}
