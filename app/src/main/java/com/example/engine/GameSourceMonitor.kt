package com.example.engine

/** Kesehatan sumber game (capture layar) dari sudut pandang siaran. */
enum class GameSourceState {
    /** Tidak dipantau: tidak ada layer game, atau capture sengaja dijeda (Studio terbuka). */
    INACTIVE,
    /** Frame mengalir (atau masih dalam masa tenggang). */
    LIVE,
    /** Frame berhenti; pemulihan sedang dicoba. Layar game terakhir masih ditampilkan. */
    STALLED,
    /** Pemulihan tidak menghasilkan frame: tampilkan fallback "GAME PAUSED". */
    PAUSED,
    /** Tidak ada sesi capture yang valid (belum ada izin / dihentikan sistem): fallback "COMING SOON". */
    UNAVAILABLE
}

/** Tindakan pemulihan yang diminta monitor kepada engine. */
enum class RecoveryAction {
    NONE,
    /** Lepas lalu pasang kembali Surface ke VirtualDisplay (murah; memicu frame baru). */
    REATTACH_SURFACE,
    /** Buat Surface baru dari SurfaceTexture yang sama lalu pasang ke VirtualDisplay. */
    REBUILD_SURFACE
}

/**
 * Mesin status kesehatan capture game. MURNI (tanpa Android) dan deterministik terhadap waktu yang
 * disuntikkan, sehingga bisa diuji unit.
 *
 * Prinsip:
 *  - VirtualDisplay hanya mengirim frame saat isi layar berubah. Layar statis (menu, game dijeda)
 *    TIDAK berarti capture rusak. Karena itu "tidak ada frame" TIDAK langsung dianggap freeze:
 *      1. Tunggu [baseStaleMs] tanpa frame (bukan satu frame terlambat; ini ratusan frame pada 30 fps).
 *      2. Coba pulihkan bertahap (pasang ulang Surface, lalu bangun Surface baru). Pada layar statis,
 *         pasang ulang Surface menghasilkan frame baru, sehingga status kembali LIVE tanpa fallback.
 *      3. Hanya bila pemulihan tetap tidak menghasilkan frame selama [fallbackAfterMs], status menjadi PAUSED.
 *  - Layar statis yang terbukti sehat memperpanjang ambang tunggu (backoff sampai [maxStaleMs]) supaya
 *    tidak memasang ulang Surface terus-menerus. Gerakan nyata (>= [motionFramesPerTick] frame per tick) mereset ambang.
 *  - Keluar dari fallback butuh minimal 1 frame baru DAN fallback sudah tampil [minFallbackHoldMs]
 *    (mencegah berkedip).
 */
class GameSourceMonitor(
    private val baseStaleMs: Long = 2_500,
    private val maxStaleMs: Long = 30_000,
    private val fallbackAfterMs: Long = 8_000,
    private val graceMs: Long = 3_000,
    private val minFallbackHoldMs: Long = 1_500,
    private val motionFramesPerTick: Int = 5
) {
    data class Result(val state: GameSourceState, val action: RecoveryAction)

    private var monitoring = false
    private var lastFrameAt = 0L
    private var graceUntil = 0L
    private var staleMs = baseStaleMs

    private var stalledSince = -1L
    private var attempts = 0
    private var nextAttemptAt = 0L

    private var inFallback = false
    private var fallbackSince = 0L
    private var framesSinceFallback = 0
    private var recoveredAfterAttempt = false

    /** Ambang tunggu saat ini (ms); naik pada layar statis yang terbukti sehat. Untuk diagnostik/tes. */
    val currentStaleMs: Long get() = staleMs

    /** Catat [count] frame baru yang tiba pada waktu [nowMs]. */
    fun onFrames(nowMs: Long, count: Int) {
        if (count <= 0) return
        lastFrameAt = nowMs
        if (inFallback) framesSinceFallback += count
        if (count >= motionFramesPerTick) staleMs = baseStaleMs
        if (stalledSince >= 0 && attempts > 0) recoveredAfterAttempt = true
    }

    fun evaluate(nowMs: Long, shouldMonitor: Boolean, sessionValid: Boolean): Result {
        if (!shouldMonitor) {
            monitoring = false
            resetStall()
            inFallback = false
            return Result(GameSourceState.INACTIVE, RecoveryAction.NONE)
        }
        if (!monitoring) {
            monitoring = true
            lastFrameAt = nowMs
            graceUntil = nowMs + graceMs
            resetStall()
        }
        if (!sessionValid) {
            if (!inFallback) { inFallback = true; fallbackSince = nowMs; framesSinceFallback = 0 }
            stalledSince = -1L
            return Result(GameSourceState.UNAVAILABLE, RecoveryAction.NONE)
        }
        // Sesi kembali valid setelah UNAVAILABLE: mulai masa tenggang baru
        if (inFallback && stalledSince < 0 && lastFrameAt <= fallbackSince) {
            graceUntil = nowMs + graceMs
            lastFrameAt = nowMs
        }

        val since = nowMs - lastFrameAt
        if (since < staleMs) {
            if (inFallback) {
                val held = nowMs - fallbackSince >= minFallbackHoldMs
                if (framesSinceFallback >= 1 && held) {
                    inFallback = false
                    finishStall()
                } else {
                    return Result(GameSourceState.PAUSED, RecoveryAction.NONE)
                }
            } else if (stalledSince >= 0) {
                finishStall()
            }
            return Result(GameSourceState.LIVE, RecoveryAction.NONE)
        }

        // Tidak ada frame >= staleMs
        if (nowMs < graceUntil) return Result(if (inFallback) GameSourceState.PAUSED else GameSourceState.LIVE, RecoveryAction.NONE)
        if (stalledSince < 0) {
            stalledSince = lastFrameAt
            attempts = 0
            nextAttemptAt = nowMs
            recoveredAfterAttempt = false
        }
        var action = RecoveryAction.NONE
        if (nowMs >= nextAttemptAt) {
            attempts++
            action = if (attempts == 1) RecoveryAction.REATTACH_SURFACE else RecoveryAction.REBUILD_SURFACE
            nextAttemptAt = nowMs + (3_000L * attempts).coerceAtMost(15_000L)
        }
        if (!inFallback && nowMs - stalledSince >= fallbackAfterMs + staleMs - baseStaleMs) {
            inFallback = true
            fallbackSince = nowMs
            framesSinceFallback = 0
        }
        return Result(if (inFallback) GameSourceState.PAUSED else GameSourceState.STALLED, action)
    }

    /** Stall berakhir karena frame kembali. Bila pulih berkat pemasangan ulang, layar kemungkinan statis: backoff. */
    private fun finishStall() {
        if (recoveredAfterAttempt) staleMs = (staleMs * 2).coerceAtMost(maxStaleMs)
        resetStall()
    }

    private fun resetStall() {
        stalledSince = -1L
        attempts = 0
        nextAttemptAt = 0L
        recoveredAfterAttempt = false
    }
}
