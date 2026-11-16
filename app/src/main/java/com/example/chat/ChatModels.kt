package com.example.chat

/** Jenis event LIVE yang dikenali. Provider boleh hanya mendukung sebagian (lihat [ProviderCapabilities]). */
enum class ChatEventKind { COMMENT, GIFT, FOLLOW, LIKE, SHARE, JOIN, SYSTEM }

/**
 * Satu event dari provider. [id] (jika provider memberikannya) dipakai untuk membuang duplikat.
 * [source] membedakan event nyata dari provider dengan komentar uji lokal, supaya tidak pernah tertukar.
 */
data class ChatEvent(
    val kind: ChatEventKind,
    val user: String,
    val text: String = "",
    val count: Int = 1,
    val id: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val source: Source = Source.PROVIDER
) {
    enum class Source { PROVIDER, LOCAL_TEST }
}

/** Event yang sudah diterima hub, dengan nomor urut lokal (kunci daftar UI) dan waktu tiba (animasi overlay). */
data class ChatMessage(val seq: Long, val event: ChatEvent, val arrivedAtMs: Long)

/** Status koneksi chat (TERPISAH sepenuhnya dari status RTMP). */
enum class ChatConnectionState { IDLE, CONNECTING, CONNECTED, RECONNECTING, FAILED }

/** Apa yang bisa diberikan sebuah provider. UI menampilkannya apa adanya; tidak ada klaim palsu. */
data class ProviderCapabilities(
    val comments: Boolean,
    val gifts: Boolean,
    val viewerCount: Boolean,
    val needsEndpoint: Boolean,
    val needsCredential: Boolean,
    /** true hanya bila API resmi platform. Untuk TikTok LIVE tidak ada API resmi publik. */
    val official: Boolean,
    val note: String
)

/** Konfigurasi koneksi. [credential] hanya ada di memori; penyimpanannya terenkripsi (lihat SecretBox). */
data class ChatConnectionConfig(val username: String, val endpoint: String, val credential: String?)

/** Callback provider ke hub. Boleh dipanggil dari thread mana pun. */
interface ChatSink {
    fun onState(state: ProviderState, detail: String? = null)
    fun onEvent(event: ChatEvent)
    /** null = provider tidak (lagi) mengetahui jumlah viewers. */
    fun onViewerCount(count: Int?)
}

/** Status dari sisi provider. Hub yang memutuskan reconnect. */
enum class ProviderState { CONNECTING, CONNECTED, LOST, FATAL }

/** Antarmuka provider chat. Menambah provider baru = implementasi kelas ini + daftarkan di [ChatProviders]. */
interface ChatProvider {
    val id: String
    val displayName: String
    val capabilities: ProviderCapabilities
    /** Mulai koneksi dan segera kembali; hasil lewat [sink]. Memanggil connect lagi tanpa disconnect = tidak didukung. */
    fun connect(config: ChatConnectionConfig, sink: ChatSink)
    /** Tutup koneksi dengan sengaja; TIDAK boleh memicu [ProviderState.LOST]. */
    fun disconnect()
}
