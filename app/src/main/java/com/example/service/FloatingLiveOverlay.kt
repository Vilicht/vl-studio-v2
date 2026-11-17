package com.example.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.ui.theme.BrandColors
import kotlin.math.abs
import kotlin.math.min

/**
 * Kontrol melayang VILICHT di atas game. Dua bentuk:
 *  - Bubble (diminimalkan): lingkaran kecil berisi status + indikator mic; bisa di-drag dan menempel ke tepi layar.
 *  - Panel (diperluas): status LIVE, durasi, viewers, mic, koneksi, serta kontrol mute, Studio, jeda, chat, dan Stop LIVE.
 *
 * Murni View (WindowManager) milik StreamingService: membuka/menutup/meminimalkan jendela ini TIDAK menyentuh
 * encoder, RTMP, atau audio. Sumber status adalah StreamState melalui service; mic membaca StreamAudioParams.
 */
class FloatingLiveOverlay(
    private val context: Context,
    private val onEndLive: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onToggleMic: () -> Unit,
    private val onEditLayout: () -> Unit = {},
    private val onNextScene: () -> Unit = {},
    /** Null = buka MainActivity (bawaan). */
    private val onOpenStudio: (() -> Unit)? = null,
    /** Null = tombol Chat disembunyikan. */
    private val onToggleChat: (() -> Unit)? = null
) {
    private val tag = "VilichtFloatingOverlay"
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("vl_floating", Context.MODE_PRIVATE)

    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var root: FrameLayout? = null
    private var bubble: BubbleView? = null
    private var panel: LinearLayout? = null
    private var expanded = false

    private var statusText: TextView? = null
    private var durationText: TextView? = null
    private var viewersValue: TextView? = null
    private var micValue: TextView? = null
    private var connValue: TextView? = null
    private var micBtn: ActionBtn? = null
    private var pauseBtn: ActionBtn? = null
    private var chatBtn: ActionBtn? = null
    private var stopBtn: ActionBtn? = null
    private var moreRow: LinearLayout? = null
    private var chatBox: LinearLayout? = null
    private var chatActive = false
    private var chatLines: List<String> = emptyList()

    private var pulse: ValueAnimator? = null
    private var stopArmed = false
    private val disarmStop = Runnable { setStopArmed(false) }

    // Status terakhir (dipakai ulang saat salah satu bagian diperbarui sendiri, mis. viewers / mic)
    private var lastStatus = StreamingStatus.OFFLINE
    private var lastPaused = false
    private var lastMuted = false

    val isShown: Boolean get() = root != null

    // ------------------------------------------------------------------ build

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(tag, "Izin overlay belum diberikan; kontrol melayang tidak ditampilkan.")
            return
        }
        if (root != null) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", dp(12))
            y = prefs.getInt("y", dp(120))
        }
        params = lp

        val container = FrameLayout(context)
        val b = BubbleView(context).also { bubble = it }
        val p = buildPanel().also { panel = it }
        container.addView(b, FrameLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP)))
        container.addView(p, FrameLayout.LayoutParams(dp(PANEL_DP), ViewGroup.LayoutParams.WRAP_CONTENT))
        root = container

        // Drag di bubble dan di header panel; ketuk bubble = buka panel
        attachDrag(b) { toggleExpanded() }
        attachDrag(p.getChildAt(0)) { }

        expanded = prefs.getBoolean("expanded", false)
        applyExpanded(animate = false)
        try {
            wm.addView(container, lp)
            clampToScreen()
        } catch (e: Exception) {
            Log.e(tag, "Gagal menambah jendela melayang: ${e.message}")
            root = null
            return
        }
        startPulse()
        render()
        renderChat()
    }

    fun hide() {
        pulse?.cancel(); pulse = null
        main.removeCallbacks(disarmStop)
        stopArmed = false
        val r = root ?: return
        savePosition()
        try { windowManager?.removeView(r) } catch (e: Exception) { Log.w(tag, "removeView: ${e.message}") }
        root = null; bubble = null; panel = null
    }

    private fun buildPanel(): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(PANEL_BG); cornerRadius = dp(20).toFloat(); setStroke(dp(1), GOLD_DIM)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            elevation = dp(10).toFloat()
        }

        // Header: status + durasi + minimalkan (area drag)
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusText = TextView(context).apply { setTextColor(TEXT); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f }
        durationText = TextView(context).apply { setTextColor(TEXT_MUTED); textSize = 12f; setPadding(dp(8), 0, 0, 0) }
        val spacer = View(context)
        val minimize = TextView(context).apply {
            text = "—"; setTextColor(GOLD); textSize = 16f; gravity = Gravity.CENTER
            minimumWidth = dp(40); minimumHeight = dp(40)
            contentDescription = "Minimalkan"
            setOnClickListener { setExpanded(false) }
        }
        header.addView(statusText); header.addView(durationText)
        header.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(minimize)
        card.addView(header)

        // Statistik: Viewers / Mic / Koneksi
        val stats = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(10)) }
        viewersValue = statCell(stats, "VIEWERS")
        micValue = statCell(stats, "MIC")
        connValue = statCell(stats, "KONEKSI")
        card.addView(stats)

        // Kontrol utama (target sentuh >= 44dp)
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        micBtn = ActionBtn(context, IconView.Kind.MIC, "Mic") { onToggleMic() }
        val studio = ActionBtn(context, IconView.Kind.STUDIO, "Studio") { openStudio() }
        pauseBtn = ActionBtn(context, IconView.Kind.PAUSE, "Jeda") { onTogglePause() }
        stopBtn = ActionBtn(context, IconView.Kind.STOP, "Stop") { onStopTapped() }
        val cell = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        actions.addView(micBtn, cell); actions.addView(studio, cell); actions.addView(pauseBtn, cell)
        if (onToggleChat != null) {
            chatBtn = ActionBtn(context, IconView.Kind.CHAT, "Chat") { onToggleChat.invoke() }
            actions.addView(chatBtn, cell)
        }
        actions.addView(stopBtn, cell)
        card.addView(actions)

        // Aksi sekunder (fitur lama dipertahankan): Atur Layout, Ganti Scene
        moreRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        moreRow?.addView(textChip("Atur Layout", filled = true) { onEditLayout() })
        moreRow?.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        moreRow?.addView(textChip("Ganti Scene", filled = false) { onNextScene() })
        card.addView(moreRow)

        // Feed chat mini (5 baris terakhir); tampil bila panel chat dibuka
        chatBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(10), 0, 0)
        }
        card.addView(chatBox)
        return card
    }

    private fun statCell(parent: LinearLayout, caption: String): TextView {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val v = TextView(context).apply { setTextColor(TEXT); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; text = "--"; maxLines = 1 }
        val c = TextView(context).apply { setTextColor(TEXT_MUTED); textSize = 9f; text = caption; letterSpacing = 0.1f }
        col.addView(v); col.addView(c)
        parent.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return v
    }

    private fun textChip(label: String, filled: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 11f; gravity = Gravity.CENTER
        setTextColor(if (filled) 0xFFFFFFFF.toInt() else TEXT)
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            if (filled) setColor(GOLD) else { setColor(0x00000000); setStroke(dp(1), GOLD_DIM) }
        }
        minimumHeight = dp(40); setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------ drag / expand

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(target: View, onTap: () -> Unit) {
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f; var dragging = false
        val slop = dp(8)
        target.setOnTouchListener { _, e ->
            val lp = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startX = lp.x; startY = lp.y; downX = e.rawX; downY = e.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        lp.x = startX + dx; lp.y = startY + dy
                        clampToScreen(update = true)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) { if (!expanded) snapToEdge() else savePosition() } else onTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleExpanded() = setExpanded(!expanded)

    private fun setExpanded(v: Boolean) {
        if (expanded == v) return
        expanded = v
        prefs.edit().putBoolean("expanded", v).apply()
        applyExpanded(animate = true)
    }

    private fun applyExpanded(animate: Boolean) {
        val b = bubble ?: return
        val p = panel ?: return
        val show = if (expanded) p else b
        val hideV = if (expanded) b else p
        hideV.visibility = View.GONE
        show.visibility = View.VISIBLE
        if (animate) {
            show.alpha = 0f; show.scaleX = 0.9f; show.scaleY = 0.9f
            show.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
        } else { show.alpha = 1f; show.scaleX = 1f; show.scaleY = 1f }
        root?.post { clampToScreen(update = true) }
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /** Jaga seluruh jendela tetap terlihat di layar. */
    private fun clampToScreen(update: Boolean = false) {
        val lp = params ?: return
        val r = root ?: return
        val (sw, sh) = screenSize()
        val w = if (r.width > 0) r.width else dp(if (expanded) PANEL_DP else BUBBLE_DP)
        val h = if (r.height > 0) r.height else dp(BUBBLE_DP)
        lp.x = lp.x.coerceIn(0, (sw - w).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (sh - h).coerceAtLeast(0))
        if (update) try { windowManager?.updateViewLayout(r, lp) } catch (_: Exception) {}
    }

    /** Bubble menempel ke tepi kiri/kanan terdekat. */
    private fun snapToEdge() {
        val lp = params ?: return
        val r = root ?: return
        val (sw, _) = screenSize()
        val w = if (r.width > 0) r.width else dp(BUBBLE_DP)
        val centerX = lp.x + w / 2
        val onLeftHalf = centerX < sw / 2
        val target = if (onLeftHalf) dp(4) else (sw - w - dp(4)).coerceAtLeast(0)
        ValueAnimator.ofInt(lp.x, target).apply {
            duration = 180; interpolator = DecelerateInterpolator()
            addUpdateListener {
                lp.x = it.animatedValue as Int
                try { windowManager?.updateViewLayout(r, lp) } catch (_: Exception) {}
            }
            doOnEnd { savePosition() }
        }.start()
    }

    private fun savePosition() {
        val lp = params ?: return
        prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
    }

    // ------------------------------------------------------------------ aksi

    private fun openStudio() {
        val custom = onOpenStudio
        if (custom != null) { custom(); return }
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    /** Dua ketukan untuk Stop LIVE: ketukan pertama meminta konfirmasi (3 detik), kedua menghentikan. */
    private fun onStopTapped() {
        if (!stopArmed) { setStopArmed(true); return }
        setStopArmed(false)
        onEndLive()
    }

    private fun setStopArmed(armed: Boolean) {
        stopArmed = armed
        main.removeCallbacks(disarmStop)
        if (armed) main.postDelayed(disarmStop, 3000)
        stopBtn?.setCaption(if (armed) "Yakin?" else "Stop")
        stopBtn?.setActiveColor(if (armed) RED else null)
    }

    // ------------------------------------------------------------------ state -> UI

    /** Dipanggil service tiap detik dan saat status berubah. Parameter lama dipertahankan. */
    fun updateStatus(
        status: StreamingStatus, durationStr: String, fps: Int, bitrateKbps: Int, isPaused: Boolean, isMicMuted: Boolean,
        viewers: Int? = lastViewers, connectionNote: String? = null
    ) {
        main.post {
            lastStatus = status; lastPaused = isPaused; lastMuted = isMicMuted; lastViewers = viewers
            val (label, color) = when {
                isPaused -> "JEDA" to AMBER
                status == StreamingStatus.LIVE -> "LIVE" to GREEN
                status == StreamingStatus.RECONNECTING -> "MENYAMBUNG" to AMBER
                status == StreamingStatus.CONNECTING -> "MENGHUBUNGKAN" to AMBER
                status == StreamingStatus.PREPARING -> "MENYIAPKAN" to AMBER
                status == StreamingStatus.STOPPING -> "MENGHENTIKAN" to AMBER
                status == StreamingStatus.PREVIEW -> "SIAP" to GOLD
                else -> "OFFLINE" to RED
            }
            statusText?.text = "● $label"
            statusText?.setTextColor(color)
            durationText?.text = if (status == StreamingStatus.LIVE || isPaused) durationStr else ""
            viewersValue?.text = viewers?.let { formatCount(it) } ?: "--"
            micValue?.text = if (isMicMuted) "MUTED" else "ON"
            micValue?.setTextColor(if (isMicMuted) RED else GREEN)
            connValue?.text = connectionNote ?: when (status) {
                StreamingStatus.LIVE -> if (bitrateKbps > 0) "$bitrateKbps kbps" else "Terhubung"
                StreamingStatus.RECONNECTING -> "Menyambung…"
                StreamingStatus.CONNECTING -> "Menyambung…"
                StreamingStatus.PREVIEW -> "Belum siaran"
                else -> "Offline"
            }
            micBtn?.setIcon(if (isMicMuted) IconView.Kind.MIC_OFF else IconView.Kind.MIC)
            micBtn?.setCaption(if (isMicMuted) "Unmute" else "Mute")
            micBtn?.setActiveColor(if (isMicMuted) RED else null)
            pauseBtn?.setIcon(if (isPaused) IconView.Kind.PLAY else IconView.Kind.PAUSE)
            pauseBtn?.setCaption(if (isPaused) "Lanjut" else "Jeda")
            // Jeda dan Stop hanya berarti saat siaran berjalan
            val onAir = status == StreamingStatus.LIVE || isPaused || status == StreamingStatus.RECONNECTING
            pauseBtn?.setEnabledLook(status == StreamingStatus.LIVE || isPaused)
            stopBtn?.setEnabledLook(onAir)
            moreRow?.visibility = if (status == StreamingStatus.PREVIEW || status == StreamingStatus.LIVE) View.VISIBLE else View.GONE
            bubble?.set(label = if (isPaused) "BRB" else if (status == StreamingStatus.LIVE) "LIVE" else if (status == StreamingStatus.PREVIEW) "SIAP" else "···",
                dot = color, muted = isMicMuted)
        }
    }

    private var lastViewers: Int? = null

    /** Jumlah viewers; null = tidak tersedia (ditampilkan "--", tidak pernah angka palsu). */
    fun setViewers(count: Int?) {
        main.post { lastViewers = count; viewersValue?.text = count?.let { formatCount(it) } ?: "--" }
    }

    /** Label mic langsung (tanpa menunggu tick 1 detik). */
    fun setMicMuted(isMicMuted: Boolean) {
        main.post {
            lastMuted = isMicMuted
            micValue?.text = if (isMicMuted) "MUTED" else "ON"
            micValue?.setTextColor(if (isMicMuted) RED else GREEN)
            micBtn?.setIcon(if (isMicMuted) IconView.Kind.MIC_OFF else IconView.Kind.MIC)
            micBtn?.setCaption(if (isMicMuted) "Unmute" else "Mute")
            micBtn?.setActiveColor(if (isMicMuted) RED else null)
            bubble?.setMuted(isMicMuted)
        }
    }

    /** Panel chat sedang terbuka/tertutup (menyorot tombol Chat dan menampilkan feed mini). */
    fun setChatActive(active: Boolean) {
        main.post { chatActive = active; chatBtn?.setActiveColor(if (active) GOLD else null); renderChat() }
    }

    /** Baris chat terbaru (sudah diformat oleh pemanggil). */
    fun setChatLines(lines: List<String>) { main.post { chatLines = lines; renderChat() } }

    private fun renderChat() {
        val box = chatBox ?: return
        box.removeAllViews()
        box.visibility = if (chatActive) View.VISIBLE else View.GONE
        if (!chatActive) return
        if (chatLines.isEmpty()) {
            box.addView(TextView(context).apply { text = "Belum ada komentar"; setTextColor(TEXT_MUTED); textSize = 11f })
        } else chatLines.forEach { line ->
            box.addView(TextView(context).apply {
                text = line; setTextColor(TEXT); textSize = 11f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(1), 0, dp(1))
            })
        }
    }

    private fun render() = updateStatus(lastStatus, "00:00", 0, 0, lastPaused, lastMuted)

    private fun startPulse() {
        pulse?.cancel()
        pulse = ValueAnimator.ofFloat(0.45f, 1f).apply {
            duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { bubble?.setPulse(it.animatedValue as Float) }
            start()
        }
    }

    private fun formatCount(n: Int): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f)
        n >= 10_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1000f)
        else -> n.toString()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ view kustom

    /** Bubble: lingkaran gelap, cincin aksen, titik status berdenyut, label, dan lencana mic-mati. */
    private inner class BubbleView(ctx: Context) : View(ctx) {
        private var label = "···"
        private var dot = GOLD
        private var muted = false
        private var pulseV = 1f
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = GOLD_DIM }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL_BG }
        private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f }
        private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }

        private fun dp(v: Float) = v * context.resources.displayMetrics.density

        fun set(label: String, dot: Int, muted: Boolean) { this.label = label; this.dot = dot; this.muted = muted; invalidate() }
        fun setMuted(m: Boolean) { muted = m; invalidate() }
        fun setPulse(p: Float) { pulseV = p; invalidate() }

        override fun onDraw(c: Canvas) {
            val s = min(width, height).toFloat()
            val cx = width / 2f; val cy = height / 2f; val r = s / 2f - dp(2f)
            c.drawCircle(cx, cy, r, fill)
            c.drawCircle(cx, cy, r, ring)
            dotP.color = dot; dotP.alpha = (pulseV * 255).toInt()
            c.drawCircle(cx, cy - s * 0.17f, s * 0.07f, dotP)
            txt.textSize = s * 0.20f
            c.drawText(label, cx, cy + s * 0.20f, txt)
            if (muted) {
                val br = s * 0.12f
                c.drawCircle(cx + r * 0.72f, cy - r * 0.72f, br, badge)
                val ip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeWidth = dp(1.5f); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
                c.drawLine(cx + r * 0.72f - br * 0.5f, cy - r * 0.72f + br * 0.5f, cx + r * 0.72f + br * 0.5f, cy - r * 0.72f - br * 0.5f, ip)
            }
        }
    }

    /** Tombol bulat 44dp + keterangan kecil. */
    private inner class ActionBtn(ctx: Context, kind: IconView.Kind, caption: String, private val click: () -> Unit) : LinearLayout(ctx) {
        private val icon = IconView(ctx, kind)
        private val circle = FrameLayout(ctx)
        private val cap = TextView(ctx).apply { text = caption; setTextColor(TEXT_MUTED); textSize = 9.5f; gravity = Gravity.CENTER }
        private val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BTN_BG); setStroke(dp(1), GOLD_DIM) }

        init {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            circle.background = bg
            circle.addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            addView(circle, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(cap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            setOnClickListener { if (isEnabled) click() }
            contentDescription = caption
        }

        fun setIcon(k: IconView.Kind) { icon.kind = k; icon.invalidate() }
        fun setCaption(t: String) { cap.text = t; contentDescription = t }
        /** [color] null = tampilan normal. */
        fun setActiveColor(color: Int?) {
            if (color == null) { bg.setColor(BTN_BG); bg.setStroke(dp(1), GOLD_DIM); icon.tint = TEXT }
            else { bg.setColor(color and 0x00FFFFFF or 0x33000000); bg.setStroke(dp(1), color); icon.tint = color }
            icon.invalidate()
        }
        fun setEnabledLook(on: Boolean) { isEnabled = on; alpha = if (on) 1f else 0.4f }
    }

    /** Ikon vektor sederhana (kotak 24 unit), digambar dengan Canvas supaya konsisten dan tanpa aset tambahan. */
    private class IconView(ctx: Context, var kind: Kind) : View(ctx) {
        enum class Kind { MIC, MIC_OFF, STUDIO, PAUSE, PLAY, CHAT, STOP }
        var tint: Int = TEXT
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        override fun onDraw(c: Canvas) {
            val k = min(width, height) / 24f
            stroke.color = tint; fillP.color = tint
            c.save(); c.scale(k, k)
            when (kind) {
                Kind.MIC, Kind.MIC_OFF -> {
                    c.drawRoundRect(RectF(9f, 3f, 15f, 14f), 3f, 3f, stroke)
                    c.drawArc(RectF(6f, 7f, 18f, 18f), 0f, 180f, false, stroke)
                    c.drawLine(12f, 18f, 12f, 21f, stroke)
                    c.drawLine(9f, 21f, 15f, 21f, stroke)
                    if (kind == Kind.MIC_OFF) c.drawLine(4f, 4f, 20f, 20f, stroke)
                }
                Kind.STUDIO -> {
                    c.drawRoundRect(RectF(4f, 4f, 20f, 20f), 4f, 4f, stroke)
                    c.drawLine(9f, 15f, 15f, 9f, stroke)
                    c.drawLine(10f, 9f, 15f, 9f, stroke)
                    c.drawLine(15f, 9f, 15f, 14f, stroke)
                }
                Kind.PAUSE -> { c.drawRoundRect(RectF(6.5f, 5f, 10.5f, 19f), 1f, 1f, fillP); c.drawRoundRect(RectF(13.5f, 5f, 17.5f, 19f), 1f, 1f, fillP) }
                Kind.PLAY -> c.drawPath(Path().apply { moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }, fillP)
                Kind.CHAT -> {
                    c.drawRoundRect(RectF(3f, 4f, 21f, 16f), 4f, 4f, stroke)
                    c.drawPath(Path().apply { moveTo(8f, 16f); lineTo(8f, 20.5f); lineTo(12.5f, 16f) }, stroke)
                }
                Kind.STOP -> c.drawRoundRect(RectF(6f, 6f, 18f, 18f), 2.5f, 2.5f, fillP)
            }
            c.restore()
        }
    }

    private companion object {
        const val BUBBLE_DP = 56
        const val PANEL_DP = 300
        val PANEL_BG = (BrandColors.PANEL and 0x00FFFFFF) or 0xF2000000.toInt()
        val BTN_BG = BrandColors.SURFACE
        val GOLD = BrandColors.ACCENT                         // nama lama dipertahankan; nilainya aksen merek (ungu)
        val GOLD_DIM = (BrandColors.ACCENT and 0x00FFFFFF) or 0x66000000
        val TEXT = BrandColors.TEXT
        val TEXT_MUTED = BrandColors.TEXT_MUTED
        val GREEN = BrandColors.GREEN
        val AMBER = BrandColors.AMBER
        val RED = BrandColors.RED
    }
}
