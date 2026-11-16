package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.SourceEntity
import com.example.pngtuber.PngTuberConfig
import com.example.pngtuber.PngTuberVoice
import com.example.ui.theme.*

/**
 * Pengaturan PNGTuber: preview idle/bicara yang bereaksi pada level mic NYATA (meter mixer), aktif/nonaktif,
 * sensitivitas, threshold, dan smoothing. Posisi/ukuran/crop diatur seperti source lain.
 */
@Composable
fun PngTuberPanel(
    source: SourceEntity,
    /** Level mic 0..100 dari pipeline audio yang sudah ada (0 saat mute). */
    micLevel: Float,
    onConfig: (PngTuberConfig) -> Unit,
    onReplaceImage: (Uri, Boolean) -> Unit
) {
    val cfg = remember(source.textContent) { PngTuberConfig.decode(source.textContent) }
    val voice = remember { PngTuberVoice() }
    var talking by remember { mutableStateOf(false) }
    var smooth by remember { mutableStateOf(0f) }
    LaunchedEffect(micLevel, cfg) { talking = voice.update(micLevel, cfg); smooth = voice.level }

    val pickIdle = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> onReplaceImage(u, false) } }
    val pickTalk = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> onReplaceImage(u, true) } }
    val imageOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    Column(Modifier.fillMaxWidth().testTag("pngtuber_panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("PNGTuber", color = TextSecondary, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)

        // Preview: gambar aktif disorot sesuai suara saat ini
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val showTalk = talking && cfg.enabled
            Preview("DIAM", source.contentUri, !showTalk, Modifier.weight(1f))
            Preview("BICARA", cfg.talkUri.ifEmpty { source.contentUri }, showTalk, Modifier.weight(1f))
        }
        Text(
            if (!cfg.enabled) "Reaksi suara nonaktif (selalu diam)" else if (talking) "Terdeteksi: BICARA" else "Terdeteksi: DIAM",
            color = if (talking && cfg.enabled) ChampagneGold else TextMuted, fontSize = 11.sp
        )
        LinearProgressIndicator(
            progress = { (smooth / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if (talking) ChampagneGold else TextMuted, trackColor = CharcoalSurface
        )
        Text("Level (setelah sensitivitas & smoothing) · threshold ${cfg.threshold.toInt()}", color = TextMuted, fontSize = 9.sp)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Bereaksi pada suara", color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Switch(checked = cfg.enabled, onCheckedChange = { onConfig(cfg.copy(enabled = it)) }, modifier = Modifier.testTag("pngtuber_enabled"))
        }
        PngSlider("Sensitivitas", cfg.sensitivity, 0.5f..4f, "${"%.1f".format(cfg.sensitivity)}×") { onConfig(cfg.copy(sensitivity = it)) }
        PngSlider("Threshold bicara", cfg.threshold, 1f..80f, "${cfg.threshold.toInt()}") { onConfig(cfg.copy(threshold = it)) }
        PngSlider("Smoothing", cfg.smoothing, 0f..0.95f, "${(cfg.smoothing * 100).toInt()}%") { onConfig(cfg.copy(smoothing = it)) }
        Text("Mic yang dipakai sama dengan mixer siaran. Mute mic = avatar diam.", color = TextMuted, fontSize = 9.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickIdle.launch(imageOnly) }, modifier = Modifier.weight(1f).height(40.dp).testTag("pngtuber_replace_idle")) {
                Text("Ganti gambar diam", fontSize = 10.sp)
            }
            OutlinedButton(onClick = { pickTalk.launch(imageOnly) }, modifier = Modifier.weight(1f).height(40.dp).testTag("pngtuber_replace_talk")) {
                Text("Ganti gambar bicara", fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun Preview(label: String, path: String?, active: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = CharcoalSurface, shape = RoundedCornerShape(8.dp),
            border = BorderStroke(if (active) 2.dp else 1.dp, if (active) ChampagneGold else CharcoalBorder),
            modifier = Modifier.fillMaxWidth().height(72.dp)
        ) {
            if (!path.isNullOrEmpty()) AsyncImage(path, label, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
        }
        Text(label, color = if (active) ChampagneGold else TextMuted, fontSize = 9.sp)
    }
}

@Composable
private fun PngSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = TextPrimary, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text(display, color = TextMuted, fontSize = 11.sp)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}
