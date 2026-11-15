package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.composition.CanvasSpec
import com.example.ui.theme.*
import kotlin.math.abs

/** Jenis preset canvas yang cocok dengan [spec] berdasarkan rasio (0 = 9:16, 1 = 16:9, 2 = 1:1, 3 = kustom). */
private fun presetIndex(spec: CanvasSpec): Int = when {
    spec.isSquare -> 2
    abs(spec.aspect - 9f / 16f) < 0.005f -> 0
    abs(spec.aspect - 16f / 9f) < 0.005f -> 1
    else -> 3
}

/**
 * Pemilih Main Canvas: Portrait 9:16 (1080x1920), Landscape 16:9 (1920x1080), Square 1:1 (1080x1080),
 * atau resolusi kustom. Canvas independen dari semua source; rasio source tidak pernah dipaksa ikut.
 */
@Composable
fun CanvasPicker(current: CanvasSpec, onChange: (CanvasSpec) -> Unit, modifier: Modifier = Modifier) {
    var customMode by remember(current) { mutableStateOf(presetIndex(current) == 3) }
    var customW by remember(current) { mutableStateOf(current.width.toString()) }
    var customH by remember(current) { mutableStateOf(current.height.toString()) }
    val idx = if (customMode) 3 else presetIndex(current)

    @Composable
    fun Chip(label: String, i: Int, tag: String, modifier: Modifier, onClick: () -> Unit) {
        OutlinedButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (idx == i) CharcoalSurface else Color.Transparent,
                contentColor = if (idx == i) ChampagneGold else TextSecondary
            ),
            modifier = modifier.testTag(tag)
        ) { Text(label, fontSize = 11.sp, maxLines = 1) }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("9:16", 0, "canvas_portrait", Modifier.weight(1f)) { customMode = false; onChange(CanvasSpec.PORTRAIT) }
            Chip("16:9", 1, "canvas_landscape", Modifier.weight(1f)) { customMode = false; onChange(CanvasSpec.LANDSCAPE) }
            Chip("1:1", 2, "canvas_square", Modifier.weight(1f)) { customMode = false; onChange(CanvasSpec.SQUARE) }
            Chip("Kustom", 3, "canvas_custom", Modifier.weight(1f)) { customMode = true }
        }
        Text(
            "Canvas: ${current.width} × ${current.height} (${current.label()})",
            color = TextMuted, fontSize = 10.sp
        )
        if (customMode) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = customW, onValueChange = { customW = it.filter(Char::isDigit).take(4) },
                    label = { Text("Lebar", fontSize = 10.sp) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("canvas_custom_w")
                )
                OutlinedTextField(
                    value = customH, onValueChange = { customH = it.filter(Char::isDigit).take(4) },
                    label = { Text("Tinggi", fontSize = 10.sp) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("canvas_custom_h")
                )
                Button(
                    onClick = {
                        val w = customW.toIntOrNull() ?: return@Button
                        val h = customH.toIntOrNull() ?: return@Button
                        onChange(CanvasSpec.custom(w, h))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ChampagneGold, contentColor = CharcoalMain),
                    modifier = Modifier.testTag("canvas_custom_apply")
                ) { Text("OK", fontSize = 11.sp) }
            }
            Text("Batas ${CanvasSpec.MIN_SIDE}–${CanvasSpec.MAX_SIDE} px, dibulatkan ke genap.", color = TextMuted, fontSize = 9.sp)
        }
    }
}
