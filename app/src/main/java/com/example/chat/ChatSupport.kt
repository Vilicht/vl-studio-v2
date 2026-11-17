package com.example.chat

import java.net.URI

/** Pembuang duplikat. Kunci = id provider bila ada; bila tidak, jenis|user|teks|jendela-waktu. LRU berkapasitas tetap. */
class ChatDeduper(private val capacity: Int = 500, private val windowMs: Long = 3_000) {
    private val seen = object : LinkedHashMap<String, Unit>(capacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > capacity
    }

    /** true bila event baru (dan dicatat); false bila duplikat. */
    @Synchronized
    fun isNew(e: ChatEvent): Boolean {
        val key = if (e.id != null) "id:${e.id}" else "k:${e.kind}|${e.user}|${e.text}|${e.timestampMs / windowMs}"
        if (seen.containsKey(key)) return false
        seen[key] = Unit
        return true
    }

    @Synchronized fun clear() = seen.clear()
}

/** Backoff eksponensial untuk reconnect. [jitter] disuntikkan supaya deterministik di tes. */
class ChatBackoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 30_000,
    private val jitter: (Long) -> Long = { bound -> if (bound <= 0) 0 else (Math.random() * bound).toLong() }
) {
    /** [attempt] dimulai dari 0. */
    fun delayFor(attempt: Int): Long {
        val exp = baseMs * (1L shl attempt.coerceIn(0, 20))
        val capped = exp.coerceAtMost(maxMs)
        return (capped + jitter(capped / 4)).coerceAtMost(maxMs)
    }
}

/** Validasi endpoint WebSocket yang diisi pengguna. Tidak ada endpoint bawaan: pengguna memilih provider/jembatannya sendiri. */
object ChatEndpoint {
    sealed interface Result {
        data class Ok(val uri: String) : Result
        data class Invalid(val message: String) : Result
    }

    fun validate(raw: String): Result {
        val s = raw.trim()
        if (s.isEmpty()) return Result.Invalid("Endpoint belum diisi")
        if (s.length > 300) return Result.Invalid("Endpoint terlalu panjang")
        val u = try { URI(s) } catch (e: Exception) { return Result.Invalid("Format endpoint tidak valid") }
        val scheme = u.scheme?.lowercase() ?: return Result.Invalid("Endpoint harus diawali ws:// atau wss://")
        val host = u.host?.lowercase() ?: return Result.Invalid("Endpoint tanpa host")
        if (u.userInfo != null) return Result.Invalid("Jangan menaruh user/password di URL; pakai kolom Token")
        if (u.query?.contains("token", ignoreCase = true) == true || u.query?.contains("key", ignoreCase = true) == true)
            return Result.Invalid("Jangan menaruh token/key di URL (tercatat di log); pakai kolom Token")
        return when (scheme) {
            "wss" -> Result.Ok(s)
            "ws" -> if (isLocalOrPrivate(host)) Result.Ok(s)
                else Result.Invalid("ws:// tanpa enkripsi hanya boleh untuk jaringan lokal; gunakan wss://")
            else -> Result.Invalid("Endpoint harus diawali ws:// atau wss://")
        }
    }

    /** localhost, loopback, dan alamat privat RFC1918 / link-local. */
    fun isLocalOrPrivate(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host == "[::1]") return true
        val p = host.split('.')
        if (p.size != 4) return false
        val n = p.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 127 || n[0] == 10 || (n[0] == 192 && n[1] == 168) ||
            (n[0] == 172 && n[1] in 16..31) || (n[0] == 169 && n[1] == 254)
    }
}
