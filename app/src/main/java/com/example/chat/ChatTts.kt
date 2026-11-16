package com.example.chat

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Pemformat teks TTS (murni, bisa diuji): membersihkan URL/emoji/pengulangan dan memutuskan apa yang dibacakan. */
object TtsTextFormatter {
    private val url = Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
    private val repeat = Regex("""(.)\1{3,}""")
    private val disallowed = Regex("""[^\p{L}\p{N}\s.,!?'\-]""")
    const val MAX_CHARS = 200

    fun sanitize(s: String): String {
        var t = url.replace(s, " tautan ")
        t = disallowed.replace(t, " ")
        t = repeat.replace(t) { it.groupValues[1].repeat(3) }
        t = t.replace(Regex("""\s+"""), " ").trim()
        return if (t.length > MAX_CHARS) t.take(MAX_CHARS).trimEnd() else t
    }

    /** null = tidak dibacakan. */
    fun format(e: ChatEvent, s: ChatSettings): String? = when (e.kind) {
        ChatEventKind.COMMENT -> {
            val text = sanitize(e.text)
            val name = sanitize(e.user)
            if (text.isEmpty()) null else if (s.ttsReadNames && name.isNotEmpty()) "$name berkata: $text" else text
        }
        ChatEventKind.GIFT -> if (!s.ttsReadGifts) null else {
            val name = sanitize(e.user); val gift = sanitize(e.text)
            if (name.isEmpty() || gift.isEmpty()) null else "$name mengirim ${e.count} $gift"
        }
        else -> null
    }
}

/**
 * Pembaca komentar. Antrean dibatasi ([MAX_PENDING]) supaya tidak tertinggal jauh dari chat yang ramai:
 * pesan baru dibuang bila antrean penuh. Kecepatan/volume diterapkan per ucapan. Suara bisa diarahkan agar
 * ikut tertangkap ke stream (USAGE_MEDIA) atau hanya terdengar streamer (USAGE_ASSISTANCE_ACCESSIBILITY, tidak
 * termasuk penangkapan audio game).
 */
class ChatTts(context: Context) {
    private val app = context.applicationContext
    private val pending = AtomicInteger(0)
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var toStream: Boolean? = null
    @Volatile var lastError: String? = null
        private set

    private fun ensure(onReady: () -> Unit = {}) {
        if (tts != null) { if (ready) onReady(); return }
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts
                val loc = Locale("id", "ID")
                val r = t?.setLanguage(loc)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t?.language = Locale.getDefault()
                t?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { pending.updateAndGet { (it - 1).coerceAtLeast(0) } }
                    @Deprecated("API lama") override fun onError(utteranceId: String?) { pending.updateAndGet { (it - 1).coerceAtLeast(0) } }
                })
                ready = true; lastError = null
                onReady()
            } else { lastError = "TTS tidak tersedia di perangkat ini"; Log.w("VilichtTts", lastError!!) }
        }
    }

    /** Siapkan mesin TTS lebih awal (saat TTS diaktifkan). */
    fun prepare() = ensure()

    fun speak(text: String, s: ChatSettings) {
        ensure {
            val t = tts ?: return@ensure
            if (toStream != s.ttsToStream) {
                toStream = s.ttsToStream
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(if (s.ttsToStream) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .build()
                )
            }
            if (pending.get() >= MAX_PENDING) return@ensure
            t.setSpeechRate(s.ttsRate.coerceIn(0.5f, 2f))
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, s.ttsVolume.coerceIn(0f, 1f)) }
            pending.incrementAndGet()
            val res = t.speak(text, TextToSpeech.QUEUE_ADD, params, "vl-${System.nanoTime()}")
            if (res != TextToSpeech.SUCCESS) pending.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
    }

    fun stop() { tts?.stop(); pending.set(0) }

    fun shutdown() { try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}; tts = null; ready = false; pending.set(0); toStream = null }

    companion object {
        const val MAX_PENDING = 5
    }
}
