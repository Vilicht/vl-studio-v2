package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.composition.CanvasSpec
import com.example.ui.theme.*

/**
 * PREVIEW LIVE: halaman pemeriksaan hasil akhir. Menampilkan frame dari compositor GL yang SAMA dengan
 * yang diberikan ke encoder (bukan renderer kedua), tanpa bounding box/handle editor.
 * Halaman ini tidak memulai streaming dan tidak mengirim data RTMP: ia hanya memasang satu Surface lokal.
 * Rasio tampilan = rasio canvas keluaran, apa pun orientasi aplikasi/tablet.
 */
@Composable
fun PreviewLiveDialog(
    canvas: CanvasSpec,
    encoder: CanvasSpec,
    fps: Int,
    engineReady: Boolean,
    isLive: Boolean,
    onAttach: (Surface, Int, Int) -> Unit,
    onDetach: (Surface) -> Unit,
    onSetCanvas: (CanvasSpec) -> Unit,
    onClose: () -> Unit,
    /** Nama source terpilih yang warna kuncinya akan dipilih dengan pipet (null = pipet tidak tersedia). */
    pickTargetName: String? = null,
    onPickColor: (Long) -> Unit = {}
) {
    var picking by remember { mutableStateOf(false) }
    var surfaceViewRef by remember { mutableStateOf<SurfaceView?>(null) }
    var pickNote by remember { mutableStateOf<String?>(null) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize().testTag("preview_live_screen")) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.heightIn(min = 44.dp).testTag("preview_live_back")
                    ) {
                        Icon(Icons.Default.ArrowBack, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Kembali ke editor")
                    }
                    Text("PREVIEW LIVE", color = ChampagneGold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    Surface(color = CharcoalPanel, shape = RoundedCornerShape(12.dp)) {
                        Text(
                            if (isLive) "SIARAN BERJALAN (preview lokal)" else "TIDAK MENYIARKAN",
                            color = if (isLive) StatusAmber else TextSecondary,
                            fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val attach by rememberUpdatedState(onAttach)
                    val detach by rememberUpdatedState(onDetach)
                    Box(Modifier.aspectRatio(canvas.aspect.coerceIn(0.1f, 10f)).background(Color.DarkGray)) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                surfaceViewRef = this
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(h: SurfaceHolder) {}
                                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                                        if (w > 0 && hh > 0) attach(h.surface, w, hh)
                                    }
                                    override fun surfaceDestroyed(h: SurfaceHolder) { detach(h.surface) }
                                })
                            }
                        },
                        // Ukuran = rasio OUTPUT (bukan rasio layar/editor)
                        modifier = Modifier.fillMaxSize()
                    )
                    if (picking) {
                        // Lapisan sentuh pipet: satu ketukan mengambil warna piksel hasil render
                        Box(
                            Modifier.fillMaxSize().pointerInput(Unit) {
                                detectTapGestures { off ->
                                    val v = surfaceViewRef
                                    if (v == null) { picking = false } else samplePixel(v, off.x.toInt(), off.y.toInt()) { c ->
                                        picking = false
                                        if (c != null) { onPickColor(c); pickNote = "Warna kunci diambil dan chroma key diaktifkan." }
                                        else pickNote = "Gagal mengambil warna (butuh Android 8+ dan frame aktif)."
                                    }
                                }
                            }
                        )
                    }
                    }
                    if (!engineReady) {
                        Text(
                            "Mesin render belum aktif. Beri izin kamera / mulai preview dari editor, lalu buka Preview Live lagi.",
                            color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(24.dp)
                        )
                    }
                }

                Text(
                    "Canvas ${canvas.width}×${canvas.height} (${canvas.label()}) · Encoder ${encoder.width}×${encoder.height} @ ${fps} fps " +
                        "(mengikuti preset kualitas)",
                    color = TextSecondary, fontSize = 11.sp
                )
                if (pickTargetName != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { picking = !picking; pickNote = null },
                            modifier = Modifier.heightIn(min = 44.dp).testTag("eyedropper_button")
                        ) { Text(if (picking) "Ketuk area latar di preview…" else "Pipet warna kunci: $pickTargetName", fontSize = 11.sp) }
                        Text(
                            pickNote ?: "Ketuk latar hijau saat chroma key belum aktif. Warna diambil dari hasil render.",
                            color = TextSecondary, fontSize = 10.sp, modifier = Modifier.weight(1f)
                        )
                    }
                }
                OutputChoices(canvas, enabled = !isLive, onSetCanvas = onSetCanvas)
                if (isLive) {
                    Text("Output tidak bisa diganti saat LIVE.", color = StatusAmber, fontSize = 10.sp)
                }
            }
        }
    }
}

/** Ambil satu piksel dari hasil render di SurfaceView (PixelCopy, Android 8+). Hasil ARGB buram atau null. */
private fun samplePixel(v: SurfaceView, x: Int, y: Int, done: (Long?) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || v.width <= 0 || v.height <= 0) { done(null); return }
    val px = x.coerceIn(0, v.width - 1)
    val py = y.coerceIn(0, v.height - 1)
    val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(v, Rect(px, py, px + 1, py + 1), bmp, { result ->
            val c = if (result == PixelCopy.SUCCESS) 0xFF000000L or (bmp.getPixel(0, 0).toLong() and 0xFFFFFFL) else null
            bmp.recycle()
            done(c)
        }, Handler(Looper.getMainLooper()))
    } catch (e: Exception) {
        bmp.recycle()
        done(null)
    }
}

@Composable
private fun OutputChoices(canvas: CanvasSpec, enabled: Boolean, onSetCanvas: (CanvasSpec) -> Unit) {
    val options = listOf(
        "9:16 · 1080×1920" to CanvasSpec(1080, 1920),
        "9:16 · 720×1280" to CanvasSpec(720, 1280),
        "16:9 · 1920×1080" to CanvasSpec(1920, 1080)
    )
    var custom by remember { mutableStateOf(options.none { it.second == canvas }) }
    var cw by remember(canvas) { mutableStateOf(canvas.width.toString()) }
    var ch by remember(canvas) { mutableStateOf(canvas.height.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (label, spec) ->
                val selected = !custom && canvas == spec
                OutlinedButton(
                    onClick = { custom = false; onSetCanvas(spec) },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selected) CharcoalSurface else Color.Transparent,
                        contentColor = if (selected) ChampagneGold else TextSecondary
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("output_${spec.width}x${spec.height}")
                ) { Text(label, fontSize = 11.sp, maxLines = 1) }
            }
            OutlinedButton(
                onClick = { custom = true },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (custom) CharcoalSurface else Color.Transparent,
                    contentColor = if (custom) ChampagneGold else TextSecondary
                ),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("output_custom")
            ) { Text("Kustom", fontSize = 11.sp) }
        }
        if (custom) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = cw, onValueChange = { cw = it.filter(Char::isDigit).take(4) }, singleLine = true,
                    label = { Text("Lebar", fontSize = 10.sp) }, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = ch, onValueChange = { ch = it.filter(Char::isDigit).take(4) }, singleLine = true,
                    label = { Text("Tinggi", fontSize = 10.sp) }, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val w = cw.toIntOrNull() ?: return@Button
                        val h = ch.toIntOrNull() ?: return@Button
                        onSetCanvas(CanvasSpec.custom(w, h))
                    },
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(containerColor = ChampagneGold, contentColor = CharcoalMain),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Terapkan") }
            }
        }
    }
}
