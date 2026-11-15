package com.example.ui.components

import android.graphics.Bitmap
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.data.model.SourceEntity
import com.example.data.model.SourceType
import com.example.engine.StudioEngine
import com.example.ui.theme.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class Handle { TL, TR, BL, BR, L, T, R, B }

private sealed interface DragMode {
    data object Move : DragMode
    data class Scale(val corner: Handle) : DragMode   // sudut: skala proporsional
    data class Crop(val edge: Handle) : DragMode      // sisi: crop nyata
}

/** Piksel layar minimum sisi terpendek area terlihat saat di-resize. */
private const val MIN_VISIBLE_PX = 24f
private const val MAX_CROP = 0.95f

// ---- Geometri layer dalam piksel canvas. Layer dirotasi di sekitar pusat kotak PENUH (sama dengan shader GL).
private class Geo(val cx: Float, val cy: Float, val cos: Float, val sin: Float)

private fun geoOf(s: SourceEntity, w: Float, h: Float): Geo {
    val r = Math.toRadians(s.rotation.toDouble())
    return Geo((s.x + s.width / 2f) * w, (s.y + s.height / 2f) * h, Math.cos(r).toFloat(), Math.sin(r).toFloat())
}
/** Titik layar -> ruang lokal layer (tanpa rotasi). */
private fun toLocal(g: Geo, p: Offset): Offset {
    val dx = p.x - g.cx; val dy = p.y - g.cy
    return Offset(g.cx + g.cos * dx + g.sin * dy, g.cy - g.sin * dx + g.cos * dy)
}
private fun toScreen(g: Geo, p: Offset): Offset {
    val dx = p.x - g.cx; val dy = p.y - g.cy
    return Offset(g.cx + g.cos * dx - g.sin * dy, g.cy + g.sin * dx + g.cos * dy)
}
/** Vektor layar -> vektor lokal. */
private fun vecToLocal(g: Geo, dx: Float, dy: Float) = Offset(g.cos * dx + g.sin * dy, -g.sin * dx + g.cos * dy)

/** Area TERLIHAT (setelah crop) di ruang lokal, piksel canvas. */
private fun visRect(s: SourceEntity, w: Float, h: Float) = Rect(
    (s.x + s.cropLeft * s.width) * w, (s.y + s.cropTop * s.height) * h,
    (s.x + (1f - s.cropRight) * s.width) * w, (s.y + (1f - s.cropBottom) * s.height) * h
)

private fun handlePoints(r: Rect): List<Pair<Handle, Offset>> = listOf(
    Handle.TL to r.topLeft, Handle.TR to r.topRight, Handle.BL to r.bottomLeft, Handle.BR to r.bottomRight,
    Handle.L to Offset(r.left, r.center.y), Handle.R to Offset(r.right, r.center.y),
    Handle.T to Offset(r.center.x, r.top), Handle.B to Offset(r.center.x, r.bottom)
)

/**
 * Editor kanvas. SATU sistem koordinat: x, y, width, height = kotak layer PENUH sebagai pecahan canvas
 * (boleh negatif / >1: layer bebas keluar canvas, canvas hanya memotong keluaran). Crop disimpan terpisah
 * sebagai pecahan konten, sehingga resize tidak pernah "mensimulasikan" crop dengan scale.
 *
 * Gestur (semua dalam piksel canvas, sadar rotasi):
 *  - isi layer: geser bebas,
 *  - handle SUDUT: skala proporsional, sudut seberangnya diam di layar,
 *  - handle SISI: crop nyata sisi itu.
 */
@Composable
fun StudioCanvas(
    sources: List<SourceEntity>,
    selectedId: String?,
    isPortrait: Boolean,
    gameSnapshot: Bitmap?,
    isEngineRunning: Boolean,
    isEditorMode: Boolean,
    showSafeArea: Boolean,
    snapEnabled: Boolean,
    isFullscreen: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    hasCamera: Boolean,
    /** Pesan masalah kamera (izin/terputus/gagal). null = tidak ada masalah. */
    cameraMessage: String?,
    /** true bila izin kamera belum diberikan: tombol utama menjadi "Beri izin". */
    cameraNeedsPermission: Boolean,
    onCameraAction: () -> Unit,
    onSelect: (String?) -> Unit,
    onBeginEdit: () -> Unit,
    onPreviewTransform: (SourceEntity) -> Unit,
    onCommitTransform: (SourceEntity) -> Unit,
    onSurfaceReady: (Surface, Int, Int) -> Unit,
    onSurfaceGone: (Surface) -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleSafeArea: () -> Unit,
    onToggleSnap: () -> Unit,
    onImportReference: () -> Unit,
    onClearSnapshot: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSwitchCamera: () -> Unit,
    modifier: Modifier = Modifier,
    /** Rasio canvas scene (lebar/tinggi): 9:16, 16:9, 1:1 atau kustom. Rasio preview = rasio output. */
    canvasAspect: Float = if (isPortrait) 9f / 16f else 16f / 9f
) {
    val aspect = canvasAspect.coerceIn(0.1f, 10f)
    val density = LocalDensity.current

    // Versi "hidup" source yang sedang di-drag; dipakai agar gerakan mulus tanpa menunggu database
    var live by remember { mutableStateOf<SourceEntity?>(null) }
    LaunchedEffect(sources) {
        val l = live
        if (l != null && sources.any { it == l }) live = null
    }
    val display = remember(sources, live) { sources.map { if (it.id == live?.id) live!! else it } }

    var guidesX by remember { mutableStateOf<List<Float>>(emptyList()) }
    var guidesY by remember { mutableStateOf<List<Float>>(emptyList()) }
    var dragging by remember { mutableStateOf(false) }

    val latestDisplay by rememberUpdatedState(display)
    val latestSelected by rememberUpdatedState(selectedId)
    val latestSnap by rememberUpdatedState(snapEnabled)
    val latestPreview by rememberUpdatedState(onPreviewTransform)
    val latestCommit by rememberUpdatedState(onCommitTransform)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestBegin by rememberUpdatedState(onBeginEdit)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CharcoalMain)
            .padding(if (isFullscreen) 0.dp else 10.dp)
            .testTag("studio_canvas_container")
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val availW = constraints.maxWidth.toFloat()
            val availH = constraints.maxHeight.toFloat()
            // Canvas = persegi dengan rasio output yang muat di area ini, di tengah
            val cw = if (availW / availH > aspect) availH * aspect else availW
            val ch = cw / aspect
            val ox = (availW - cw) / 2f
            val oy = (availH - ch) / 2f
            val latestCw by rememberUpdatedState(cw)
            val latestCh by rememberUpdatedState(ch)
            val latestOx by rememberUpdatedState(ox)
            val latestOy by rememberUpdatedState(oy)

            // ---- Isi canvas (di-clip ke batas canvas = batas keluaran siaran)
            Box(
                Modifier
                    .offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
                    .size(with(density) { cw.toDp() }, with(density) { ch.toDp() })
                    .border(1.dp, CharcoalBorder)
                    .clipToBounds()
                    .testTag("preview_canvas")
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black))
                if (isEngineRunning) {
                    val ready by rememberUpdatedState(onSurfaceReady)
                    val gone by rememberUpdatedState(onSurfaceGone)
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(h: SurfaceHolder) {}
                                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                                        ready(h.surface, w, hh)
                                    }
                                    override fun surfaceDestroyed(h: SurfaceHolder) { gone(h.surface) }
                                })
                            }
                        }
                    )
                } else {
                    // Engine belum jalan: placeholder tiap source, urut z
                    display.filter { it.isVisible && !it.isReference }.sortedBy { it.zIndex }.forEach { s ->
                        PlaceholderItem(s, cw, ch, gameSnapshot, density.density, cameraMessage)
                    }
                }

                // Screenshot referensi (hanya editor, tidak pernah ke RTMP)
                display.filter { it.isVisible && it.isReference && !it.contentUri.isNullOrEmpty() }.forEach { s ->
                    LayerBox(s, cw, ch, density.density) {
                        AsyncImage(
                            model = s.contentUri, contentDescription = "Referensi",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, alpha = s.opacity
                        )
                    }
                }
            }

            // ---- Overlay seleksi: TIDAK di-clip, supaya layer yang sebagian/seluruhnya di luar canvas tetap bisa diraih
            val sel = display.firstOrNull { it.id == selectedId && it.isVisible }
            Canvas(Modifier.fillMaxSize()) {
                translate(left = ox, top = oy) {
                    if (showSafeArea) {
                        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                        drawRect(
                            Color.White.copy(alpha = 0.45f), topLeft = Offset(cw * 0.05f, ch * 0.05f),
                            size = Size(cw * 0.9f, ch * 0.9f), style = Stroke(width = 1.5f, pathEffect = dash)
                        )
                        drawRect(
                            Color.White.copy(alpha = 0.25f), topLeft = Offset(cw * 0.1f, ch * 0.1f),
                            size = Size(cw * 0.8f, ch * 0.8f), style = Stroke(width = 1f, pathEffect = dash)
                        )
                    }
                    if (dragging) {
                        val faint = Color.White.copy(alpha = 0.18f)
                        drawLine(faint, Offset(cw / 2, 0f), Offset(cw / 2, ch), 1f)
                        drawLine(faint, Offset(0f, ch / 2), Offset(cw, ch / 2), 1f)
                        guidesX.forEach { gx -> drawLine(Color(0xFFF472B6), Offset(gx * cw, 0f), Offset(gx * cw, ch), 2f) }
                        guidesY.forEach { gy -> drawLine(Color(0xFFF472B6), Offset(0f, gy * ch), Offset(cw, gy * ch), 2f) }
                    }
                    if (sel != null) {
                        val g = geoOf(sel, cw, ch)
                        val r = visRect(sel, cw, ch)
                        rotate(sel.rotation, pivot = Offset(g.cx, g.cy)) {
                            drawRect(ChampagneGold, r.topLeft, r.size, style = Stroke(width = 3f))
                            if (!sel.isLocked) {
                                val rad = 7.dp.toPx()
                                handlePoints(r).forEach { (h, c) ->
                                    if (h == Handle.TL || h == Handle.TR || h == Handle.BL || h == Handle.BR) {
                                        // Sudut: lingkaran = skala proporsional
                                        drawCircle(Color.White, rad + 2f, c)
                                        drawCircle(ChampagneGold, rad, c)
                                    } else {
                                        // Sisi: batang = crop
                                        val horizontal = h == Handle.T || h == Handle.B
                                        val bw = if (horizontal) rad * 3.2f else rad * 1.2f
                                        val bh = if (horizontal) rad * 1.2f else rad * 3.2f
                                        drawRoundRect(
                                            Color.White, Offset(c.x - bw / 2 - 1.5f, c.y - bh / 2 - 1.5f),
                                            Size(bw + 3f, bh + 3f), CornerRadius(4f, 4f)
                                        )
                                        drawRoundRect(
                                            ChampagneGoldLight, Offset(c.x - bw / 2, c.y - bh / 2),
                                            Size(bw, bh), CornerRadius(3f, 3f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- Gestur (seluruh area, bukan hanya canvas; koordinat dikonversi ke ruang canvas)
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        val hitCorner = 26.dp.toPx()
                        val hitEdge = 22.dp.toPx()
                        val thr = 8.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val W = latestCw
                            val H = latestCh
                            val offX = latestOx
                            val offY = latestOy
                            val list = latestDisplay
                            val p = Offset(down.position.x - offX, down.position.y - offY)

                            fun hitBody(s: SourceEntity): Boolean {
                                val q = toLocal(geoOf(s, W, H), p)
                                return visRect(s, W, H).contains(q)
                            }

                            // 1) handle pada source terpilih (bekerja di ruang lokal sehingga benar untuk layer yang dirotasi)
                            var target: SourceEntity? = null
                            var mode: DragMode = DragMode.Move
                            val cur = list.firstOrNull { it.id == latestSelected && it.isVisible && !it.isLocked }
                            if (cur != null) {
                                val q = toLocal(geoOf(cur, W, H), p)
                                val pts = handlePoints(visRect(cur, W, H))
                                val corner = pts.take(4).firstOrNull { (_, c) -> (c - q).getDistance() <= hitCorner }
                                val edge = pts.drop(4).firstOrNull { (_, c) -> (c - q).getDistance() <= hitEdge }
                                if (corner != null) { target = cur; mode = DragMode.Scale(corner.first) }
                                else if (edge != null) { target = cur; mode = DragMode.Crop(edge.first) }
                            }
                            // 2) source paling atas di titik sentuh
                            if (target == null) {
                                target = list.filter { it.isVisible && hitBody(it) }
                                    .maxByOrNull { if (it.isReference) -1 else it.zIndex }
                            }
                            if (target == null) {
                                latestSelect(null)
                                return@awaitEachGesture
                            }
                            val start = target
                            latestSelect(start.id)
                            if (start.isLocked) return@awaitEachGesture

                            val slop = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                ?: return@awaitEachGesture
                            latestBegin()
                            dragging = true
                            val origin = down.position
                            var result = start
                            val g0 = geoOf(start, W, H)
                            val vr0 = visRect(start, W, H)

                            // Snap hanya untuk Move, terhadap tepi area TERLIHAT layer lain
                            fun visX(s: SourceEntity) = listOf(s.x + s.cropLeft * s.width, s.x + (1f - s.cropRight) * s.width)
                            fun visY(s: SourceEntity) = listOf(s.y + s.cropTop * s.height, s.y + (1f - s.cropBottom) * s.height)
                            val others = list.filter { it.id != start.id && it.isVisible && !it.isReference }
                            val tx = listOf(0f, 0.05f, 0.5f, 0.95f, 1f) + others.flatMap { o -> visX(o).let { v -> listOf(v[0], (v[0] + v[1]) / 2f, v[1]) } }
                            val ty = listOf(0f, 0.05f, 0.5f, 0.95f, 1f) + others.flatMap { o -> visY(o).let { v -> listOf(v[0], (v[0] + v[1]) / 2f, v[1]) } }
                            fun snap(edges: List<Float>, targets: List<Float>, px: Float): Pair<Float, Float?> {
                                if (!latestSnap) return 0f to null
                                var best = Float.MAX_VALUE
                                var line: Float? = null
                                for (e in edges) for (t in targets) {
                                    val d = t - e
                                    if (abs(d) * px < thr && abs(d) < abs(best)) { best = d; line = t }
                                }
                                return if (line == null) 0f to null else best to line
                            }

                            fun applyDrag(pos: Offset) {
                                val dx = pos.x - origin.x
                                val dy = pos.y - origin.y
                                var gx: Float? = null
                                var gy: Float? = null
                                val updated: SourceEntity = when (val m = mode) {
                                    DragMode.Move -> {
                                        // TIDAK ada coerceIn: layer boleh keluar canvas sebagian/seluruhnya
                                        var nx = start.x + dx / W
                                        var ny = start.y + dy / H
                                        val vx = visX(start).map { it - start.x }
                                        val vy = visY(start).map { it - start.y }
                                        val sx = snap(listOf(nx + vx[0], nx + (vx[0] + vx[1]) / 2f, nx + vx[1]), tx, W)
                                        val sy = snap(listOf(ny + vy[0], ny + (vy[0] + vy[1]) / 2f, ny + vy[1]), ty, H)
                                        nx += sx.first; ny += sy.first; gx = sx.second; gy = sy.second
                                        start.copy(x = nx, y = ny)
                                    }
                                    is DragMode.Scale -> {
                                        // Sudut seberang area terlihat = anchor (ruang lokal). Skala k searah diagonal -> rasio terjaga.
                                        val grab = handlePoints(vr0).first { it.first == m.corner }.second
                                        val anchor = when (m.corner) {
                                            Handle.TL -> vr0.bottomRight; Handle.TR -> vr0.bottomLeft
                                            Handle.BL -> vr0.topRight; else -> vr0.topLeft
                                        }
                                        val dLocal = vecToLocal(g0, dx, dy)
                                        val v = grab - anchor
                                        val v2 = v.x * v.x + v.y * v.y
                                        val wv = (grab + dLocal) - anchor
                                        val minK = MIN_VISIBLE_PX / min(vr0.width, vr0.height).coerceAtLeast(1f)
                                        val k = ((wv.x * v.x + wv.y * v.y) / v2).coerceAtLeast(minK)
                                        val fx = anchor.x + (start.x * W - anchor.x) * k
                                        val fy = anchor.y + (start.y * H - anchor.y) * k
                                        val fw = start.width * W * k
                                        val fh = start.height * H * k
                                        // Rotasi memakai pusat baru: koreksi supaya anchor tetap di titik layar yang sama
                                        val screenAnchor = toScreen(g0, anchor)
                                        val ncx = fx + fw / 2f
                                        val ncy = fy + fh / 2f
                                        val r = Math.toRadians(start.rotation.toDouble())
                                        val c = Math.cos(r).toFloat(); val sn = Math.sin(r).toFloat()
                                        val rx = anchor.x - ncx; val ry = anchor.y - ncy
                                        val newAnchorScreen = Offset(ncx + c * rx - sn * ry, ncy + sn * rx + c * ry)
                                        val cx = screenAnchor.x - newAnchorScreen.x
                                        val cy = screenAnchor.y - newAnchorScreen.y
                                        start.copy(x = (fx + cx) / W, y = (fy + cy) / H, width = fw / W, height = fh / H)
                                    }
                                    is DragMode.Crop -> {
                                        val d = vecToLocal(g0, dx, dy)
                                        val fw = (start.width * W).coerceAtLeast(1f)
                                        val fh = (start.height * H).coerceAtLeast(1f)
                                        when (m.edge) {
                                            Handle.L -> start.copy(cropLeft = (start.cropLeft + d.x / fw).coerceIn(0f, MAX_CROP - start.cropRight))
                                            Handle.R -> start.copy(cropRight = (start.cropRight - d.x / fw).coerceIn(0f, MAX_CROP - start.cropLeft))
                                            Handle.T -> start.copy(cropTop = (start.cropTop + d.y / fh).coerceIn(0f, MAX_CROP - start.cropBottom))
                                            else -> start.copy(cropBottom = (start.cropBottom - d.y / fh).coerceIn(0f, MAX_CROP - start.cropTop))
                                        }
                                    }
                                }
                                guidesX = listOfNotNull(gx)
                                guidesY = listOfNotNull(gy)
                                result = updated
                                live = updated
                                latestPreview(updated)
                            }

                            applyDrag(slop.position)
                            drag(slop.id) { change ->
                                change.consume()
                                applyDrag(change.position)
                            }
                            dragging = false
                            guidesX = emptyList(); guidesY = emptyList()
                            latestCommit(result)
                        }
                    }
            )

            // ---- Lapisan kontrol di atas gestur (banner, toolbar, status kamera), terikat ke kotak canvas
            Box(
                Modifier
                    .offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }
                    .size(with(density) { cw.toDp() }, with(density) { ch.toDp() })
            ) {
                if (isEngineRunning && isEditorMode) {
                    Surface(
                        color = CharcoalPanel.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                    ) {
                        Text(
                            "Mode edit: layar game dibekukan di siaran selama Studio terbuka. Kamera & overlay tetap live.",
                            color = TextPrimary, fontSize = 11.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // Kamera bermasalah: JANGAN layar hitam diam-diam. Tampilkan sebabnya + tombol tindakan.
                if (hasCamera && cameraMessage != null) {
                    Surface(
                        color = CharcoalPanel.copy(alpha = 0.95f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, StatusAmber),
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).testTag("camera_status")
                    ) {
                        Row(Modifier.padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VideocamOff, null, tint = StatusAmber, modifier = Modifier.size(16.dp))
                            Text(
                                cameraMessage, color = TextPrimary, fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp).widthIn(max = 220.dp)
                            )
                            TextButton(onClick = onCameraAction, modifier = Modifier.heightIn(min = 40.dp)) {
                                Text(if (cameraNeedsPermission) "Beri izin" else "Coba lagi", color = ChampagneGoldLight, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .background(CharcoalPanel.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    ToolbarButton(Icons.Default.Undo, "Undo", canUndo, false, onUndo)
                    ToolbarButton(Icons.Default.Redo, "Redo", canRedo, false, onRedo)
                    if (hasCamera) ToolbarButton(Icons.Default.Cameraswitch, "Ganti kamera", true, false, onSwitchCamera)
                    ToolbarButton(Icons.Default.GridOn, "Snapping", true, snapEnabled, onToggleSnap)
                    ToolbarButton(Icons.Default.CropFree, "Safe area", true, showSafeArea, onToggleSafeArea)
                    ToolbarButton(Icons.Default.AddPhotoAlternate, "Impor screenshot referensi", true, false, onImportReference)
                    if (gameSnapshot != null) ToolbarButton(Icons.Default.HideImage, "Hapus snapshot game", true, false, onClearSnapshot)
                    ToolbarButton(
                        if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        if (isFullscreen) "Keluar fullscreen" else "Fullscreen", true, false, onToggleFullscreen
                    )
                }
            }
        }
    }
}

/**
 * Menggambar konten layer pada ukuran PENUH, lalu memotong ke area terlihat (crop), memutar di sekitar
 * pusat kotak penuh, dan menerapkan mirror + opacity. Dipakai placeholder saat engine GL belum jalan;
 * rumusnya sama dengan shader di LayerFilterRender.
 */
@Composable
private fun LayerBox(s: SourceEntity, cw: Float, ch: Float, density: Float, content: @Composable () -> Unit) {
    val fullW = s.width * cw
    val fullH = s.height * ch
    val visL = s.cropLeft * fullW
    val visT = s.cropTop * fullH
    val visW = (fullW * (1f - s.cropLeft - s.cropRight)).coerceAtLeast(1f)
    val visH = (fullH * (1f - s.cropTop - s.cropBottom)).coerceAtLeast(1f)
    Box(
        Modifier
            .offset { IntOffset((s.x * cw + visL).roundToInt(), (s.y * ch + visT).roundToInt()) }
            .size((visW / density).dp, (visH / density).dp)
            .graphicsLayer {
                rotationZ = s.rotation
                transformOrigin = TransformOrigin((fullW / 2f - visL) / visW, (fullH / 2f - visT) / visH)
            }
            .clipToBounds()
    ) {
        Box(
            Modifier
                .requiredSize((fullW / density).dp, (fullH / density).dp)
                .offset { IntOffset(-visL.roundToInt(), -visT.roundToInt()) }
                .graphicsLayer { scaleX = if (s.flipH) -1f else 1f }
        ) { content() }
    }
}

/** Gambaran sementara source saat engine belum jalan. */
@Composable
private fun PlaceholderItem(
    s: SourceEntity, cw: Float, ch: Float, snapshot: Bitmap?, density: Float, cameraMessage: String?
) {
    val fullW = s.width * cw
    val fullH = s.height * ch
    LayerBox(s, cw, ch, density) {
        when (s.type) {
            SourceType.SCREEN -> {
                if (snapshot != null) Image(
                    snapshot.asImageBitmap(), "Snapshot game",
                    Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds
                ) else Placeholder(Icons.Default.ScreenShare, "LAYAR GAME", "Mulai siaran lalu atur layout dari game")
            }
            SourceType.CAMERA -> Placeholder(
                Icons.Default.Videocam, "KAMERA",
                if (cameraMessage != null) "Kamera belum aktif" else "Menyiapkan kamera…"
            )
            SourceType.WEB -> Placeholder(Icons.Default.Language, "BROWSER", s.contentUri?.takeLast(40) ?: "")
            SourceType.VIDEO -> Placeholder(Icons.Default.Movie, "VIDEO", s.name)
            SourceType.IMAGE -> if (!s.contentUri.isNullOrEmpty()) AsyncImage(
                s.contentUri, s.name, Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds, alpha = s.opacity
            ) else Placeholder(Icons.Default.Image, "GAMBAR", "")
            SourceType.TEXT -> {
                val text = s.textContent ?: "TEXT"
                val sizePx = StudioEngine.textSizePx(text, fullW, fullH)
                val strokePx = sizePx * s.textStrokeWidth.coerceIn(0f, 0.3f)
                Box(
                    // textBgColor = 0 (bawaan) = transparan penuh: teks polos
                    Modifier.fillMaxSize().background(Color(s.textBgColor), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (strokePx >= 0.5f) {
                        // Garis tepi di bawah isi teks (sama dengan hasil render ke stream)
                        Text(
                            text, color = Color(s.textStrokeColor), fontWeight = FontWeight.Bold,
                            fontSize = (sizePx / density).sp, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false,
                            style = TextStyle(drawStyle = Stroke(width = strokePx, join = StrokeJoin.Round))
                        )
                    }
                    Text(
                        text, color = Color(s.textColor), fontWeight = FontWeight.Bold,
                        fontSize = (sizePx / density).sp, maxLines = 1, overflow = TextOverflow.Clip, softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    enabled: Boolean,
    active: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(
            icon, contentDescription = desc,
            tint = when { !enabled -> TextMuted; active -> ChampagneGoldLight; else -> TextPrimary },
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun Placeholder(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String) {
    Column(
        Modifier.fillMaxSize().background(CharcoalSurface.copy(alpha = 0.7f)).border(1.dp, CharcoalBorder),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = ChampagneGoldLight, modifier = Modifier.size(24.dp))
        Text(title, color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (sub.isNotEmpty()) Text(sub, color = TextSecondary, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
