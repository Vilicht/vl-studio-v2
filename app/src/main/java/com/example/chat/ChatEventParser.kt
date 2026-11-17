package com.example.chat

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.IOException

/**
 * Parser skema "VILICHT Chat JSON v1" (satu objek JSON per pesan WebSocket). Skema ini DIDEFINISIKAN aplikasi ini,
 * bukan format TikTok; jembatan/provider apa pun yang mengirim skema ini bisa dipakai.
 *
 *   {"type":"comment","id":"..","user":"nama","text":"isi"}
 *   {"type":"gift","id":"..","user":"nama","gift":"Rose","count":3}
 *   {"type":"follow"|"share"|"join","user":"nama"}      {"type":"like","user":"nama","count":5}
 *   {"type":"viewers","count":123}                        {"type":"status","message":"teks"}
 *
 * Murni (tanpa Android) dan toleran: pesan yang tidak dikenal diabaikan, pesan rusak ditandai Invalid.
 */
object ChatEventParser {
    sealed interface Parsed {
        data class Event(val event: ChatEvent) : Parsed
        data class Viewers(val count: Int) : Parsed
        object Ignored : Parsed
        data class Invalid(val reason: String) : Parsed
    }

    const val MAX_USER = 40
    const val MAX_TEXT = 300
    const val MAX_PAYLOAD = 16_384

    private val adapter = Moshi.Builder().build()
        .adapter<Map<String, Any?>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    fun parse(json: String, nowMs: Long = System.currentTimeMillis()): Parsed {
        if (json.length > MAX_PAYLOAD) return Parsed.Invalid("pesan terlalu besar")
        val m = try { adapter.fromJson(json) } catch (e: IOException) { null } catch (e: JsonDataException) { null }
            ?: return Parsed.Invalid("bukan objek JSON")
        val type = (m["type"] as? String)?.lowercase() ?: return Parsed.Invalid("tanpa 'type'")
        val id = (m["id"] as? String)?.take(80)?.takeIf { it.isNotBlank() }
        val user = clean(m["user"] as? String, MAX_USER)
        return when (type) {
            "comment" -> {
                val text = clean(m["text"] as? String, MAX_TEXT)
                if (user.isEmpty() || text.isEmpty()) Parsed.Invalid("komentar tanpa user/teks")
                else Parsed.Event(ChatEvent(ChatEventKind.COMMENT, user, text, id = id, timestampMs = nowMs))
            }
            "gift" -> {
                val gift = clean(m["gift"] as? String, 60)
                if (user.isEmpty() || gift.isEmpty()) Parsed.Invalid("gift tanpa user/nama")
                else Parsed.Event(ChatEvent(ChatEventKind.GIFT, user, gift, count = intOf(m["count"], 1), id = id, timestampMs = nowMs))
            }
            "follow", "share", "join" -> if (user.isEmpty()) Parsed.Invalid("event tanpa user") else Parsed.Event(
                ChatEvent(when (type) { "follow" -> ChatEventKind.FOLLOW; "share" -> ChatEventKind.SHARE; else -> ChatEventKind.JOIN },
                    user, id = id, timestampMs = nowMs)
            )
            "like" -> if (user.isEmpty()) Parsed.Invalid("like tanpa user")
                else Parsed.Event(ChatEvent(ChatEventKind.LIKE, user, count = intOf(m["count"], 1), id = id, timestampMs = nowMs))
            "viewers" -> {
                val c = m["count"]
                if (c is Number && c.toDouble() >= 0.0 && c.toDouble() <= 1_000_000_000.0) Parsed.Viewers(c.toInt())
                else Parsed.Invalid("jumlah viewers tidak valid")
            }
            "status" -> {
                val msg = clean(m["message"] as? String, MAX_TEXT)
                if (msg.isEmpty()) Parsed.Ignored else Parsed.Event(ChatEvent(ChatEventKind.SYSTEM, "", msg, timestampMs = nowMs))
            }
            else -> Parsed.Ignored
        }
    }

    private fun intOf(v: Any?, default: Int): Int =
        ((v as? Number)?.toInt() ?: default).coerceIn(1, 100_000)

    /** Hapus karakter kontrol, rapikan spasi, batasi panjang. */
    fun clean(s: String?, max: Int): String {
        if (s == null) return ""
        val sb = StringBuilder(minOf(s.length, max))
        var lastSpace = false
        for (ch in s) {
            if (ch == '\n' || ch == '\r' || ch == '\t' || ch == ' ') {
                if (!lastSpace && sb.isNotEmpty()) sb.append(' ')
                lastSpace = true
            } else if (!ch.isISOControl()) { sb.append(ch); lastSpace = false }
            if (sb.length >= max) break
        }
        return sb.toString().trim()
    }
}
