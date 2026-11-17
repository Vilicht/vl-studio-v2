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
import com.example.composition.CanvasPreset
import com.example.composition.CanvasPresets
import com.example.composition.CanvasSpec
import com.example.ui.theme.*

/**
 * Pemilih Main Canvas: 9:16, 16:9, 1:1, 4:3, 3:4, 6:19, 19:6, atau kustom (resolusi atau rasio).
 * Canvas independen dari semua source; rasio source tidak pernah dipaksa ikut canvas.
 */
@Composable
fun CanvasPicker(current: CanvasSpec, onChange: (CanvasSpec) -> Unit, modifier: Modifier = Modifier) {
    val matched = CanvasPresets.match(current)
    var customMode by remember(current) { mutableStateOf(matched == null) }
    var customW by remember(current) { mutableStateOf(current.width.toString()) }
    var customH by remember(current) { mutableStateOf(current.height.toString()) }
    var ratioW by remember(current) { mutableStateOf("") }
    var ratioH by remember(current) { mutableStateOf("") }

    @Composable
    fun Chip(label: String, selected: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
        OutlinedButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (selected) CharcoalSurface else Color.Transparent,
                contentColor = if (selected) ChampagneGold else TextSecondary
            ),
            modifier = modifier.testTag(tag)
        ) { Text(label, fontSize = 11.sp, maxLines = 1) }
    }

    @Composable
    fun PresetChip(p: CanvasPreset, modifier: Modifier) =
        Chip(p.label, !customMode && matched == p, "canvas_${p.rw}x${p.rh}", modifier) {
            customMode = false; onChange(p.spec())
        }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val all = CanvasPresets.all
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            all.take(4).forEach { PresetChip(it, Modifier.weight(1f)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            all.drop(4).forEach { PresetChip(it, Modifier.weight(1f)) }
            Chip("Kustom", customMode, "canvas_custom", Modifier.weight(1f)) { customMode = true }
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = ratioW, onValueChange = { ratioW = it.filter(Char::isDigit).take(3) },
                    label = { Text("Rasio W", fontSize = 10.sp) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("canvas_ratio_w")
                )
                OutlinedTextField(
                    value = ratioH, onValueChange = { ratioH = it.filter(Char::isDigit).take(3) },
                    label = { Text("Rasio H", fontSize = 10.sp) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("canvas_ratio_h")
                )
                Button(
                    onClick = {
                        val rw = ratioW.toIntOrNull()?.takeIf { it > 0 } ?: return@Button
                        val rh = ratioH.toIntOrNull()?.takeIf { it > 0 } ?: return@Button
                        onChange(CanvasSpec.fromRatio(rw, rh))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ChampagneGold, contentColor = CharcoalMain),
                    modifier = Modifier.testTag("canvas_ratio_apply")
                ) { Text("Rasio", fontSize = 11.sp) }
            }
            Text("Batas ${CanvasSpec.MIN_SIDE}–${CanvasSpec.MAX_SIDE} px, dibulatkan ke genap.", color = TextMuted, fontSize = 9.sp)
        }
    }
}
