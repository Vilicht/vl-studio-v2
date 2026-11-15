package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.util.zip.ZipInputStream

/**
 * WebView untuk Browser Source. WebView TIDAK ditampilkan di atas UI aplikasi: ia dipasang di
 * jendela overlay kecil di luar layar (supaya Chromium tetap menjalankan JS/animasi karena view
 * ter-attach), lalu isinya digambar ke tekstur GL oleh AndroidViewFilterRender milik engine.
 * Dengan begitu isi browser benar-benar masuk ke frame yang dikirim ke RTMP.
 *
 * Semua fungsi harus dipanggil dari Main Thread.
 * Butuh izin "Tampil di atas aplikasi lain" (sudah dipakai tombol live melayang).
 */
class WebOverlayHost(private val context: Context) {
    private val tag = "VilichtWebHost"
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    class Handle internal constructor(val webView: WebView)

    fun canHost(): Boolean = Settings.canDrawOverlays(context)

    /**
     * @param cssW,cssH ukuran viewport dalam piksel CSS (seperti "Width/Height" Browser Source di OBS).
     * @param onLaidOut dipanggil sekali setelah WebView punya ukuran nyata (aman untuk dipasang ke filter GL).
     * @param onProblem dipanggil kalau renderer WebView crash atau gagal.
     */
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun create(
        target: String,
        isLocalFile: Boolean,
        cssW: Int,
        cssH: Int,
        onLaidOut: (WebView) -> Unit,
        onProblem: (String) -> Unit
    ): Handle? {
        if (!canHost()) {
            onProblem("Browser Overlay butuh izin 'Tampil di atas aplikasi lain'.")
            return null
        }
        val url = normalizeTarget(target, isLocalFile)
        if (url == null) {
            onProblem("Alamat Browser Overlay tidak valid (hanya http, https, atau file HTML lokal).")
            return null
        }
        val density = context.resources.displayMetrics.density
        val pxW = (cssW * density).toInt().coerceIn(64, 4096)
        val pxH = (cssH * density).toInt().coerceIn(64, 4096)

        return try {
            val web = WebView(context)
            web.setBackgroundColor(Color.TRANSPARENT)           // dukung background transparan
            web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            web.isVerticalScrollBarEnabled = false
            web.isHorizontalScrollBarEnabled = false
            web.overScrollMode = View.OVER_SCROLL_NEVER
            web.settings.apply {
                javaScriptEnabled = true                        // animasi/alert berbasis JS
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = false
                setSupportZoom(false)
                allowContentAccess = false
                allowFileAccess = isLocalFile                   // hanya untuk HTML lokal hasil impor
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = isLocalFile       // supaya resource relatif (css/js/gambar) terbaca
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
            }
            web.webViewClient = object : WebViewClient() {
                // Jangan biarkan overlay membuka skema aneh
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                    val scheme = request?.url?.scheme?.lowercase()
                    return scheme != "http" && scheme != "https" && scheme != "file"
                }

                // Crash renderer WebView tidak boleh membunuh seluruh app saat live
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    Log.e(tag, "Renderer WebView mati (crash=${detail?.didCrash()})")
                    onProblem("Browser Overlay berhenti (renderer WebView mati). Hapus dan tambahkan lagi.")
                    return true
                }
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(
                pxW, pxH, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = -pxW - 64            // di luar layar: tidak terlihat dan tidak menutupi game
                y = 0
            }
            wm.addView(web, lp)

            var notified = false
            web.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                if (!notified && r - l > 0 && b - t > 0) {
                    notified = true
                    onLaidOut(v as WebView)
                }
            }
            web.loadUrl(url)
            Handle(web)
        } catch (e: Exception) {
            // Mis. WebView tidak terpasang / dinonaktifkan di perangkat
            Log.e(tag, "Gagal membuat WebView: ${e.message}", e)
            onProblem("Browser Overlay gagal dibuat: ${e.localizedMessage}")
            null
        }
    }

    fun destroy(handle: Handle?) {
        val web = handle?.webView ?: return
        try { wm.removeViewImmediate(web) } catch (_: Exception) {}
        try {
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        } catch (_: Exception) {}
    }

    private fun normalizeTarget(raw: String, isLocalFile: Boolean): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (isLocalFile) {
            val f = File(t)
            return if (f.isFile) Uri.fromFile(f).toString() else null
        }
        val withScheme = if (t.contains("://")) t else "https://$t"
        val scheme = Uri.parse(withScheme).scheme?.lowercase()
        return if (scheme == "http" || scheme == "https") withScheme else null
    }
}

/**
 * Impor HTML lokal ke penyimpanan privat aplikasi: satu/beberapa file (html + css/js/gambar)
 * atau satu ZIP. Semua ditaruh di filesDir/overlays/<sourceId>/ supaya resource relatif bekerja.
 */
object LocalOverlayStore {
    private const val TAG = "VilichtLocalOverlay"
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_FILES = 500

    fun dirFor(context: Context, sourceId: String) = File(context.filesDir, "overlays/$sourceId")

    /** @return path file HTML utama, atau null (dengan pesan di [error]) kalau gagal. */
    fun import(context: Context, sourceId: String, uris: List<Uri>, error: (String) -> Unit): String? {
        if (uris.isEmpty()) { error("Tidak ada file dipilih."); return null }
        val dir = dirFor(context, sourceId)
        dir.deleteRecursively()
        if (!dir.mkdirs()) { error("Folder penyimpanan overlay tidak bisa dibuat."); return null }
        return try {
            var total = 0L
            var count = 0
            val names = uris.map { it to displayName(context, it) }
            val zip = names.singleOrNull()?.takeIf { it.second.endsWith(".zip", true) }
            if (zip != null) {
                context.contentResolver.openInputStream(zip.first)?.use { raw ->
                    ZipInputStream(raw.buffered()).use { zin ->
                        var e = zin.nextEntry
                        while (e != null) {
                            val out = File(dir, e.name).canonicalFile
                            // Cegah zip-slip: tujuan harus tetap di dalam folder overlay
                            if (!out.path.startsWith(dir.canonicalPath + File.separator)) {
                                throw SecurityException("ZIP berisi path berbahaya: ${e.name}")
                            }
                            if (e.isDirectory) out.mkdirs() else {
                                if (++count > MAX_FILES) throw IllegalStateException("ZIP berisi terlalu banyak file")
                                out.parentFile?.mkdirs()
                                out.outputStream().use { os ->
                                    val buf = ByteArray(16 * 1024)
                                    while (true) {
                                        val n = zin.read(buf)
                                        if (n < 0) break
                                        total += n
                                        if (total > MAX_TOTAL_BYTES) throw IllegalStateException("Ukuran overlay melebihi 64 MB")
                                        os.write(buf, 0, n)
                                    }
                                }
                            }
                            e = zin.nextEntry
                        }
                    }
                }
            } else {
                for ((uri, name) in names) {
                    val safe = File(name).name.ifBlank { "file_$count" }   // buang komponen path
                    if (++count > MAX_FILES) throw IllegalStateException("Terlalu banyak file")
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        File(dir, safe).outputStream().use { os ->
                            val copied = ins.copyTo(os)
                            total += copied
                            if (total > MAX_TOTAL_BYTES) throw IllegalStateException("Ukuran overlay melebihi 64 MB")
                        }
                    }
                }
            }
            val entry = findEntry(dir)
            if (entry == null) error("Tidak ada file .html pada yang dipilih.")
            entry?.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Impor gagal: ${e.message}", e)
            error("Impor HTML gagal: ${e.localizedMessage}")
            dir.deleteRecursively()
            null
        }
    }

    fun delete(context: Context, sourceId: String) {
        try { dirFor(context, sourceId).deleteRecursively() } catch (_: Exception) {}
    }

    private fun findEntry(dir: File): File? {
        val all = dir.walkTopDown().filter { it.isFile && (it.extension.equals("html", true) || it.extension.equals("htm", true)) }.toList()
        return all.firstOrNull { it.name.equals("index.html", true) } ?: all.minByOrNull { it.path.length }
    }

    private fun displayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment ?: "file"
        } catch (_: Exception) { uri.lastPathSegment ?: "file" }
    }
}
