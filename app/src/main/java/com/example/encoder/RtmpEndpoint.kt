package com.example.encoder

/**
 * Pembentuk endpoint RTMP dari Server URL dan Stream Key yang disimpan TERPISAH.
 *
 * Aturan:
 * - Isi Stream Key tidak pernah diubah (tanpa encode/decode, tanpa menghapus karakter apa pun).
 *   Hanya spasi/newline di UJUNG yang dibuang, karena hasil copy-paste sering membawanya dan
 *   key RTMP yang valid tidak pernah mengandung whitespace.
 * - Server URL hanya dibuang whitespace dan slash di ujung belakang; tidak ada slash ganda.
 * - Skema harus rtmp:// atau rtmps:// dan host tidak boleh kosong.
 * - Nilai bawaan lama "live_stream_key_sample" (dummy) ditolak.
 */
object RtmpEndpoint {
    sealed class Result {
        data class Ok(val endpoint: String) : Result()
        data class Invalid(val message: String) : Result()
    }

    private const val LEGACY_DUMMY_KEY = "live_stream_key_sample"

    fun build(serverUrl: String, streamKey: String): Result {
        val server = serverUrl.trim().trimEnd('/')
        val key = streamKey.trim()

        val lower = server.lowercase()
        val hostPart = when {
            lower.startsWith("rtmp://") -> server.substring(7)
            lower.startsWith("rtmps://") -> server.substring(8)
            else -> return Result.Invalid("Server URL harus diawali rtmp:// atau rtmps://")
        }
        if (hostPart.isEmpty() || hostPart.startsWith("/") || hostPart.any { it.isWhitespace() }) {
            return Result.Invalid("Server URL tidak valid (host kosong atau mengandung spasi).")
        }
        if (key.isEmpty()) return Result.Invalid("Stream Key masih kosong. Isi di Settings.")
        if (key == LEGACY_DUMMY_KEY) {
            return Result.Invalid("Stream Key masih nilai contoh bawaan. Isi stream key asli di Settings.")
        }
        if (key.any { it.isWhitespace() }) {
            return Result.Invalid("Stream Key mengandung spasi/baris baru di tengah. Salin ulang dari penyedia.")
        }
        return Result.Ok("$server/$key")
    }
}
