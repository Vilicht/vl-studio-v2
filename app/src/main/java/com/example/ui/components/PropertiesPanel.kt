package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.SourceEntity
import com.example.data.model.SourceType
import com.example.ui.theme.*

@Composable
fun PropertiesPanel(
    selectedSource: SourceEntity?,
    onUpdateSource: (SourceEntity) -> Unit,
    /** Persen skala saat ini (100% = area terlihat persis muat di canvas). */
    scalePercent: Float = 100f,
    onScalePercent: (Float) -> Unit = {},
    onFit: () -> Unit = {},
    onCenter: () -> Unit = {},
    onResetCropAndRatio: () -> Unit = {},
    onToggleFlip: () -> Unit = {},
    onRotate90: () -> Unit = {},
    onSetRotation: (Float) -> Unit = {},
    onSetCrop: (left: Float?, top: Float?, right: Float?, bottom: Float?) -> Unit = { _, _, _, _ -> },
    cameras: List<com.example.engine.CameraInfo> = emptyList(),
    defaultCameraId: String? = null,
    onSetCamera: (String?) -> Unit = {},
    cameraMaxPixels: Int = 1280 * 720,
    cameraFps: Int = 30,
    onSetCameraQuality: (maxPixels: Int, fps: Int) -> Unit = { _, _ -> },
    onFitGame: () -> Unit = {},
    onFill: () -> Unit = {},
    onResetTransform: () -> Unit = {},
    hasReference: Boolean = false,
    onAlignToReference: () -> Unit = {},
    /** true untuk Screen / Kamera / Gambar: source yang punya mode fit (Utuh/Penuhi/Regangkan). */
    supportsFit: Boolean = false,
    onSetFitMode: (Int) -> Unit = {},
    /** Level mic 0..100 (meter mixer) untuk preview PNGTuber. */
    micLevel: Float = 0f,
    onPngTuberConfig: (com.example.pngtuber.PngTuberConfig) -> Unit = {},
    onPngTuberReplaceImage: (android.net.Uri, Boolean) -> Unit = { _, _ -> },
    onSetZoom: (Float) -> Unit = {},
    onSetPan: (x: Float?, y: Float?) -> Unit = { _, _ -> },
    onFullWidth: () -> Unit = {},
    onFullHeight: () -> Unit = {},
    onResetCrop: () -> Unit = {},
    /** Nama source di depan yang menutup seluruh source terpilih (null = tidak tertutup). */
    coveredByName: String? = null,
    onVideoRestart: () -> Unit = {},
    onVideoStop: () -> Unit = {},
    panelWidth: androidx.compose.ui.unit.Dp = 260.dp,
    modifier: Modifier = Modifier
) {
    Surface(
        color = CharcoalPanel,
        modifier = modifier
            .width(panelWidth)
            .fillMaxHeight()
            .border(1.dp, CharcoalBorder)
            .testTag("studio_properties_panel")
    ) {
        if (selectedSource == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Pilih source lalu atur\nposisi & ukuran langsung di canvas",
                    color = TextMuted,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "TRANSFORM",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Badge(containerColor = CharcoalSurface) {
                        Text(
                            text = selectedSource.type.name,
                            color = ChampagneGold,
                            fontSize = 10.sp
                        )
                    }
                }

                Text(
                    text = selectedSource.name,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )

                Divider(color = CharcoalBorder, thickness = 1.dp)

                if (selectedSource.type == SourceType.PNGTUBER) {
                    PngTuberPanel(selectedSource, micLevel, onPngTuberConfig, onPngTuberReplaceImage)
                    Divider(color = CharcoalBorder, thickness = 1.dp)
                }

                // TEXT OVERLAY CONTENT EDITOR
                if (selectedSource.type == SourceType.TEXT) {
                    Text(
                        text = "Text Content",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = selectedSource.textContent ?: "",
                        onValueChange = { onUpdateSource(selectedSource.copy(textContent = it)) },
                        modifier = Modifier.fillMaxWidth().testTag("text_content_input"),
                        singleLine = false,
                        maxLines = 2,
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ChampagneGold,
                            unfocusedBorderColor = CharcoalBorder,
                            focusedContainerColor = CharcoalSurface,
                            unfocusedContainerColor = CharcoalSurface
                        )
                    )
                    // Gaya teks: polos secara bawaan; background dan garis tepi opsional
                    val t = selectedSource
                    val bgAlpha = ((t.textBgColor shr 24) and 0xFF).toInt()
                    val strokeOn = t.textStrokeWidth > 0f
                    SectionTitle("Warna teks")
                    SwatchRow(
                        colors = TextColorChoices, selectedRgb = t.textColor and 0xFFFFFFL, tag = "text_color",
                        onNone = null, noneSelected = false,
                        onPick = { onUpdateSource(t.copy(textColor = 0xFF000000L or (it and 0xFFFFFFL))) }
                    )
                    SectionTitle("Background teks")
                    SwatchRow(
                        colors = BgColorChoices, selectedRgb = t.textBgColor and 0xFFFFFFL, tag = "text_bg",
                        onNone = { onUpdateSource(t.copy(textBgColor = 0L)) }, noneSelected = bgAlpha == 0,
                        onPick = { rgb ->
                            val a = if (bgAlpha == 0) 0xFFL else bgAlpha.toLong()
                            onUpdateSource(t.copy(textBgColor = (a shl 24) or (rgb and 0xFFFFFFL)))
                        }
                    )
                    if (bgAlpha > 0) {
                        LabeledValue("Opasitas background", "${bgAlpha * 100 / 255}%")
                        GoldSlider(
                            value = (bgAlpha / 255f).coerceIn(0.1f, 1f), range = 0.1f..1f, enabled = true,
                            tag = "text_bg_opacity",
                            onChange = {
                                val a = (it * 255f + 0.5f).toInt().toLong().coerceIn(1L, 255L)
                                onUpdateSource(t.copy(textBgColor = (a shl 24) or (t.textBgColor and 0xFFFFFFL)))
                            }
                        )
                    }
                    SectionTitle("Garis tepi")
                    SwatchRow(
                        colors = StrokeColorChoices, selectedRgb = t.textStrokeColor and 0xFFFFFFL, tag = "text_stroke",
                        onNone = { onUpdateSource(t.copy(textStrokeWidth = 0f)) }, noneSelected = !strokeOn,
                        onPick = { rgb ->
                            onUpdateSource(
                                t.copy(
                                    textStrokeColor = 0xFF000000L or (rgb and 0xFFFFFFL),
                                    textStrokeWidth = if (strokeOn) t.textStrokeWidth else 0.08f
                                )
                            )
                        }
                    )
                    if (strokeOn) {
                        LabeledValue("Tebal garis tepi", "${(t.textStrokeWidth * 100).toInt()}%")
                        GoldSlider(
                            value = t.textStrokeWidth.coerceIn(0.02f, 0.3f), range = 0.02f..0.3f, enabled = true,
                            tag = "text_stroke_width",
                            onChange = { onUpdateSource(t.copy(textStrokeWidth = it)) }
                        )
                    }
                    Divider(color = CharcoalBorder, thickness = 1.dp)
                }

                val locked = selectedSource.isLocked
                val src = selectedSource

                if (coveredByName != null) {
                    Surface(color = StatusAmber.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Source ini tertutup sepenuhnya oleh \"$coveredByName\". Ubah urutan layer atau sembunyikan source di depannya.",
                            color = StatusAmber, fontSize = 10.sp, modifier = Modifier.padding(8.dp).testTag("covered_warning")
                        )
                    }
                }

                // A. TRANSFORM QUICK ACTIONS. Posisi & ukuran diatur langsung di canvas (drag, handle sudut, handle sisi).
                SectionTitle("Transform")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickAction(Icons.Default.FitScreen, "Fit", !locked, Modifier.weight(1f), "action_fit", onFit)
                    QuickAction(Icons.Default.CenterFocusStrong, "Center", !locked, Modifier.weight(1f), "action_center", onCenter)
                    QuickAction(Icons.Default.RestartAlt, "Reset crop & ratio", !locked, Modifier.weight(1f), "action_reset", onResetCropAndRatio)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickAction(
                        Icons.Default.Flip, "Mirror", !locked, Modifier.weight(1f), "action_mirror",
                        onToggleFlip, active = src.flipH
                    )
                    QuickAction(
                        if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
                        if (locked) "Unlock" else "Lock", true, Modifier.weight(1f), "action_lock",
                        { onUpdateSource(src.copy(isLocked = !locked)) }, active = locked
                    )
                }
                if (locked) Text("Layer terkunci. Buka kunci untuk mengubahnya.", color = TextMuted, fontSize = 10.sp)

                // B. SCALE: SATU slider proporsional. Lebar & tinggi berubah bersama, pusat layer tetap.
                var scaleDraft by remember(src.id) { mutableStateOf<Float?>(null) }
                val scaleShown = (scaleDraft ?: scalePercent).coerceIn(10f, 300f)
                LabeledValue("Scale", "${scaleShown.toInt()}%")
                GoldSlider(
                    value = scaleShown, range = 10f..300f, enabled = !locked, tag = "scale_slider",
                    onChange = { scaleDraft = it; onScalePercent(it) },
                    onFinished = { scaleDraft = null }
                )

                // C. ROTATION 0..360
                var rotDraft by remember(src.id) { mutableStateOf<Float?>(null) }
                val rotNorm = (((src.rotation % 360f) + 360f) % 360f)
                val rotShown = rotDraft ?: rotNorm
                LabeledValue("Rotation", "${rotShown.toInt()}°")
                GoldSlider(
                    value = rotShown, range = 0f..360f, enabled = !locked, tag = "rotation_slider",
                    onChange = { rotDraft = it; onSetRotation(it) },
                    onFinished = { rotDraft = null }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickAction(Icons.Default.Rotate90DegreesCw, "Rotate 90°", !locked, Modifier.weight(1f), "action_rotate90", onRotate90)
                    QuickAction(Icons.Default.RestartAlt, "Reset rotation", !locked && rotNorm != 0f, Modifier.weight(1f), "action_reset_rotation", onClick = { onSetRotation(0f) })
                }

                Divider(color = CharcoalBorder, thickness = 1.dp)

                // D. APPEARANCE
                SectionTitle("Appearance")
                LabeledValue("Opacity", "${(src.opacity * 100).toInt()}%")
                GoldSlider(
                    value = src.opacity, range = 0.1f..1f, enabled = true, tag = "opacity_slider",
                    onChange = { onUpdateSource(src.copy(opacity = it)) }
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (src.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, null,
                            tint = TextSecondary, modifier = Modifier.size(16.dp)
                        )
                        Text("  Visible", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = src.isVisible,
                        onCheckedChange = { onUpdateSource(src.copy(isVisible = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = ChampagneGold),
                        modifier = Modifier.testTag("visible_switch")
                    )
                }
                Text(
                    "Blend mode tidak tersedia: compositor saat ini hanya mendukung alpha normal.",
                    color = TextMuted, fontSize = 9.sp
                )

                Divider(color = CharcoalBorder, thickness = 1.dp)

                // E. CROP: crop nyata per sisi (UV), terpisah dari Scale. Bisa juga ditarik dari handle sisi di canvas.
                SectionTitle("Crop")
                CropRow("Left", src.cropLeft, !locked, "crop_left", onChange = { onSetCrop(it, null, null, null) })
                CropRow("Top", src.cropTop, !locked, "crop_top", onChange = { onSetCrop(null, it, null, null) })
                CropRow("Right", src.cropRight, !locked, "crop_right", onChange = { onSetCrop(null, null, it, null) })
                CropRow("Bottom", src.cropBottom, !locked, "crop_bottom", onChange = { onSetCrop(null, null, null, it) })

                Divider(color = CharcoalBorder, thickness = 1.dp)

                // KONTROL VIDEO: putar / jeda / ulang / stop
                if (src.type == SourceType.VIDEO) {
                    SectionTitle("Kontrol video")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { onUpdateSource(src.copy(videoPaused = !src.videoPaused)) },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("video_play_pause")
                        ) { Text(if (src.videoPaused) "Putar" else "Jeda", fontSize = 11.sp) }
                        OutlinedButton(
                            onClick = onVideoRestart,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("video_restart")
                        ) { Text("Ulang", fontSize = 11.sp) }
                        OutlinedButton(
                            onClick = onVideoStop,
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp).testTag("video_stop")
                        ) { Text("Stop", fontSize = 11.sp) }
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Suara video ikut siaran (eksperimental)", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = src.videoSound,
                            onCheckedChange = { onUpdateSource(src.copy(videoSound = it)) },
                            colors = SwitchDefaults.colors(checkedTrackColor = ChampagneGold),
                            modifier = Modifier.testTag("video_sound_switch")
                        )
                    }
                    Text(
                        "Video diputar berulang. Suara masuk siaran lewat capture audio internal Android: hanya jika izin " +
                            "capture layar aktif, terdengar juga di speaker, dan bisa menimbulkan gema ke mikrofon.",
                        color = TextMuted, fontSize = 9.sp
                    )
                    Divider(color = CharcoalBorder, thickness = 1.dp)
                }

                // CHROMA KEY (green screen): dihitung di GPU, ikut ke preview DAN output RTMP
                if (src.type == SourceType.CAMERA || src.type == SourceType.VIDEO ||
                    src.type == SourceType.IMAGE || src.type == SourceType.SCREEN
                ) {
                    SectionTitle("Chroma key (green screen)")
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Aktifkan", color = TextSecondary, fontSize = 11.sp)
                        Switch(
                            checked = src.chromaKeyEnabled,
                            onCheckedChange = { onUpdateSource(src.copy(chromaKeyEnabled = it)) },
                            colors = SwitchDefaults.colors(checkedTrackColor = ChampagneGold),
                            modifier = Modifier.testTag("chroma_switch")
                        )
                    }
                    if (src.chromaKeyEnabled) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Hijau" to 0xFF00FF00L, "Biru" to 0xFF0047BBL, "Magenta" to 0xFFFF00FFL).forEach { (label, color) ->
                                Surface(
                                    color = Color(color.toInt()),
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        if (src.chromaKeyColor == color) 2.dp else 1.dp,
                                        if (src.chromaKeyColor == color) ChampagneGold else CharcoalBorder
                                    ),
                                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                                        .clickable { onUpdateSource(src.copy(chromaKeyColor = color)) }
                                        .testTag("chroma_color_$label")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(label, color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        var hex by remember(src.id, src.chromaKeyColor) {
                            mutableStateOf(String.format("%06X", src.chromaKeyColor and 0xFFFFFFL))
                        }
                        OutlinedTextField(
                            value = hex,
                            onValueChange = { v ->
                                val c = v.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(6)
                                hex = c
                                if (c.length == 6) onUpdateSource(src.copy(chromaKeyColor = 0xFF000000L or c.toLong(16)))
                            },
                            label = { Text("Warna kunci (HEX RRGGBB)", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("chroma_hex")
                        )
                        LabeledValue("Kemiripan warna", "${(src.chromaSimilarity * 100).toInt()}%")
                        GoldSlider(
                            value = src.chromaSimilarity, range = 0.05f..1f, enabled = true, tag = "chroma_similarity",
                            onChange = { onUpdateSource(src.copy(chromaSimilarity = it)) }
                        )
                        LabeledValue("Kehalusan tepi", "${(src.chromaSmoothness * 100).toInt()}%")
                        GoldSlider(
                            value = src.chromaSmoothness, range = 0f..1f, enabled = true, tag = "chroma_smoothness",
                            onChange = { onUpdateSource(src.copy(chromaSmoothness = it)) }
                        )
                        LabeledValue("Spill reduction", "${(src.chromaSpill * 100).toInt()}%")
                        GoldSlider(
                            value = src.chromaSpill, range = 0f..1f, enabled = true, tag = "chroma_spill",
                            onChange = { onUpdateSource(src.copy(chromaSpill = it)) }
                        )
                        Text(
                            "Naikkan Kemiripan sampai latar hilang, lalu atur Kehalusan agar tepi tidak bergerigi.",
                            color = TextMuted, fontSize = 9.sp
                        )
                    }
                    Divider(color = CharcoalBorder, thickness = 1.dp)
                }

                // VIEWPORT SOURCE: kotak layer = viewport. Mode fit menentukan cara konten mengisinya;
                // rasio asli konten tidak pernah berubah kecuali pengguna memilih Regangkan.
                SectionTitle("Viewport & fitting")
                if (supportsFit) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChoiceChip("Utuh", src.fitMode == com.example.composition.FitMode.CONTAIN, Modifier.weight(1f).testTag("fit_contain")) {
                            if (!locked) onSetFitMode(com.example.composition.FitMode.CONTAIN)
                        }
                        ChoiceChip("Penuhi", src.fitMode == com.example.composition.FitMode.COVER, Modifier.weight(1f).testTag("fit_cover")) {
                            if (!locked) onSetFitMode(com.example.composition.FitMode.COVER)
                        }
                        ChoiceChip("Regangkan", src.fitMode == com.example.composition.FitMode.STRETCH, Modifier.weight(1f).testTag("fit_stretch")) {
                            if (!locked) onSetFitMode(com.example.composition.FitMode.STRETCH)
                        }
                    }
                    Text(
                        "Utuh: seluruh konten terlihat (area kosong boleh). Penuhi: viewport terisi, kelebihan terpotong. " +
                            "Regangkan: rasio boleh berubah.",
                        color = TextMuted, fontSize = 9.sp
                    )
                    LabeledValue("Zoom konten", String.format("%.1fx", src.zoom))
                    GoldSlider(
                        value = src.zoom, range = 1f..8f, enabled = !locked, tag = "zoom_slider",
                        onChange = { onSetZoom(it) }
                    )
                    if (src.zoom > 1.001f) {
                        LabeledValue("Geser X", "${(src.panX * 100).toInt()}%")
                        GoldSlider(src.panX, -0.5f..0.5f, !locked, "pan_x_slider", onChange = { onSetPan(it, null) })
                        LabeledValue("Geser Y", "${(src.panY * 100).toInt()}%")
                        GoldSlider(src.panY, -0.5f..0.5f, !locked, "pan_y_slider", onChange = { onSetPan(null, it) })
                    }
                } else {
                    Text(
                        if (src.type == SourceType.WEB) "Browser Source selalu mengisi kotaknya (tanpa mode fit)."
                        else "Teks selalu mengisi kotaknya.",
                        color = TextMuted, fontSize = 10.sp
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickAction(Icons.Default.FitScreen, "Fit to Canvas", !locked, Modifier.weight(1f), "mode_fit", onFit)
                    QuickAction(Icons.Default.Fullscreen, "Fill Canvas", !locked, Modifier.weight(1f), "mode_fill", onFill)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickAction(Icons.Default.Landscape, "Full Width 16:9", !locked, Modifier.weight(1f), "action_full_width", onFullWidth)
                    QuickAction(Icons.Default.Portrait, "Full Height 9:16", !locked, Modifier.weight(1f), "action_full_height", onFullHeight)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickAction(Icons.Default.CenterFocusStrong, "Center", !locked, Modifier.weight(1f), "action_center2", onCenter)
                    QuickAction(Icons.Default.Crop, "Reset Crop", !locked, Modifier.weight(1f), "action_reset_crop", onResetCrop)
                    QuickAction(Icons.Default.RestartAlt, "Reset Transform", !locked, Modifier.weight(1f), "action_reset_transform", onResetTransform)
                }
                if (selectedSource.type == SourceType.SCREEN) {
                    OutlinedButton(
                        onClick = onAlignToReference,
                        enabled = hasReference && !locked,
                        modifier = Modifier.fillMaxWidth().height(44.dp)
                    ) { Text("Align Game to Reference", fontSize = 11.sp, color = ChampagneGoldLight) }
                    if (!hasReference) Text("Impor Screenshot Referensi dulu untuk memakai fitur ini.", color = TextMuted, fontSize = 10.sp)
                }

                // KAMERA: pilih kamera yang terdeteksi (depan, belakang, eksternal/USB)
                if (selectedSource.type == SourceType.CAMERA) {
                    Text("Kamera", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    if (cameras.isEmpty()) {
                        Text("Tidak ada kamera terdeteksi.", color = TextMuted, fontSize = 10.sp)
                    }
                    cameras.forEach { cam ->
                        val chosen = (selectedSource.cameraId ?: defaultCameraId) == cam.id
                        Surface(
                            color = if (chosen) CharcoalSurface else Color.Transparent,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (chosen) ChampagneGold else CharcoalBorder),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onSetCamera(cam.id) }
                        ) {
                            Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                                Text(cam.label, color = if (chosen) TextPrimary else TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                    // Resolusi & FPS: berlaku tanpa mengubah posisi/ukuran/crop layer
                    Text("Resolusi", color = TextSecondary, fontSize = 11.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("480p" to 640 * 480, "720p" to 1280 * 720, "1080p" to 1920 * 1080).forEach { (label, px) ->
                            ChoiceChip(label, cameraMaxPixels == px, Modifier.weight(1f)) { onSetCameraQuality(px, cameraFps) }
                        }
                    }
                    Text("FPS", color = TextSecondary, fontSize = 11.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 30, 60).forEach { f ->
                            ChoiceChip("$f", cameraFps == f, Modifier.weight(1f)) { onSetCameraQuality(cameraMaxPixels, f) }
                        }
                    }
                    Text("Putar isi kamera", color = TextSecondary, fontSize = 11.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (0..3).forEach { q ->
                            ChoiceChip("${q * 90}°", selectedSource.cameraRotation == q, Modifier.weight(1f).testTag("camera_rot_$q")) {
                                onUpdateSource(selectedSource.copy(cameraRotation = q))
                            }
                        }
                    }
                    Text(
                        "Orientasi mengikuti sensor dan rotasi layar secara otomatis. Jika kepala masih miring, " +
                            "pilih putaran yang membuatnya tegak.",
                        color = TextMuted, fontSize = 9.sp
                    )
                    Text(
                        "Mirror kamera: tombol Mirror di atas. Resolusi/FPS memakai nilai terdekat yang didukung kamera.",
                        color = TextMuted, fontSize = 9.sp
                    )
                }

                // BROWSER OVERLAY
                if (selectedSource.type == SourceType.WEB) {
                    if (selectedSource.webMode == 0) {
                        var urlDraft by remember(selectedSource.id) { mutableStateOf(selectedSource.contentUri ?: "") }
                        Text("URL Browser Source", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = urlDraft,
                            onValueChange = { urlDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TextPrimary),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ChampagneGold, unfocusedBorderColor = CharcoalBorder,
                                focusedContainerColor = CharcoalSurface, unfocusedContainerColor = CharcoalSurface
                            )
                        )
                        Button(
                            onClick = { onUpdateSource(selectedSource.copy(contentUri = urlDraft.trim())) },
                            enabled = urlDraft.trim() != (selectedSource.contentUri ?: ""),
                            colors = ButtonDefaults.buttonColors(containerColor = ChampagneGold, contentColor = CharcoalMain),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) { Text("Terapkan URL", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    } else {
                        Text("Sumber: file HTML lokal yang diimpor", color = TextMuted, fontSize = 10.sp)
                    }
                    Text(
                        "Background transparan didukung. Opacity tidak berlaku untuk Browser Overlay. " +
                            "Ukuran viewport mengikuti ukuran source di canvas.",
                        color = TextMuted, fontSize = 10.sp
                    )
                }

                if (selectedSource.isReference) {
                    Text(
                        "Screenshot referensi: hanya tampil di editor, tidak ikut ke siaran.",
                        color = ChampagneGoldLight, fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = 11.sp)
        Text(value, color = TextPrimary, fontSize = 11.sp)
    }
}

@Composable
private fun GoldSlider(
    value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean, tag: String,
    onChange: (Float) -> Unit, onFinished: () -> Unit = {}
) {
    Slider(
        value = value, onValueChange = onChange, onValueChangeFinished = onFinished,
        valueRange = range, enabled = enabled,
        modifier = Modifier.testTag(tag),
        colors = SliderDefaults.colors(
            thumbColor = ChampagneGold, activeTrackColor = ChampagneGold, inactiveTrackColor = CharcoalBorder
        )
    )
}

@Composable
private fun CropRow(label: String, value: Float, enabled: Boolean, tag: String, onChange: (Float) -> Unit) {
    Column {
        LabeledValue(label, "${(value * 100).toInt()}%")
        GoldSlider(value, 0f..0.9f, enabled, tag, onChange = onChange)
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    tag: String,
    onClick: () -> Unit,
    active: Boolean = false
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (active) ChampagneGold else CharcoalBorder),
        modifier = modifier.heightIn(min = 52.dp).testTag(tag)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = if (active) ChampagneGoldLight else TextPrimary, modifier = Modifier.size(18.dp))
            Text(
                label, fontSize = 9.sp, color = if (active) ChampagneGoldLight else TextSecondary,
                maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

private val TextColorChoices = listOf(
    0xFFF2F0E9L, 0xFFFFFFFFL, 0xFFFFD54FL, 0xFFFF5252L, 0xFF69F0AEL, 0xFF40C4FFL, 0xFFB388FFL, 0xFF000000L
)
private val BgColorChoices = listOf(
    0xFF000000L, 0xFF20201EL, 0xFFFFFFFFL, 0xFF7C4DFFL, 0xFFD32F2FL, 0xFF2E7D32L, 0xFF1565C0L, 0xFFF9A825L
)
private val StrokeColorChoices = listOf(
    0xFF000000L, 0xFFFFFFFFL, 0xFF7C4DFFL, 0xFFD32F2FL, 0xFFF9A825L, 0xFF1565C0L
)

/** Deretan pilihan warna bulat. [onNone] != null menambah pilihan "tanpa" (✕) di awal. */
@Composable
private fun SwatchRow(
    colors: List<Long>, selectedRgb: Long?, tag: String,
    onNone: (() -> Unit)?, noneSelected: Boolean, onPick: (Long) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onNone != null) {
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .border(2.dp, if (noneSelected) ChampagneGold else CharcoalBorder, CircleShape)
                    .clickable(onClick = onNone).testTag("${tag}_none"),
                contentAlignment = Alignment.Center
            ) { Text("✕", color = TextSecondary, fontSize = 13.sp) }
        }
        colors.forEachIndexed { i, c ->
            val chosen = !noneSelected && selectedRgb == (c and 0xFFFFFFL)
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Color(c))
                    .border(2.dp, if (chosen) ChampagneGold else CharcoalBorder, CircleShape)
                    .clickable { onPick(c) }.testTag("${tag}_$i")
            )
        }
    }
}

@Composable
private fun ChoiceChip(label: String, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        color = if (chosen) CharcoalSurface else Color.Transparent,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (chosen) ChampagneGold else CharcoalBorder),
        modifier = modifier.heightIn(min = 44.dp).clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (chosen) TextPrimary else TextSecondary, fontSize = 11.sp)
        }
    }
}
