package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.audio.AudioDevice
import com.example.audio.MicEffectSupport
import com.example.ui.theme.*

@Composable
fun AudioDevicesDialog(
    isOpen: Boolean,
    inputs: List<AudioDevice>,
    outputs: List<AudioDevice>,
    selectedInputId: Int,
    onSelectInput: (Int) -> Unit,
    onDismiss: () -> Unit,
    noiseSuppression: Boolean = false,
    echoCancel: Boolean = false,
    autoGain: Boolean = false,
    micEffectSupport: MicEffectSupport = MicEffectSupport(noiseSuppression = false, echoCancel = false, autoGain = false),
    onMicEffectsChange: (noiseSuppression: Boolean, echoCancel: Boolean, autoGain: Boolean) -> Unit = { _, _, _ -> }
) {
    if (!isOpen) return
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = CharcoalPanel,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CharcoalBorder),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("PERANGKAT AUDIO", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

                Text("Input (mikrofon)", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                DeviceRow("Otomatis (dipilih sistem)", selectedInputId == 0) { onSelectInput(0) }
                inputs.forEach { d -> DeviceRow(d.label, selectedInputId == d.id) { onSelectInput(d.id) } }
                if (inputs.isEmpty()) Text("Tidak ada input terdeteksi.", color = TextMuted, fontSize = 10.sp)

                Divider(color = CharcoalBorder)
                Text("Output yang terdeteksi", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                outputs.forEach { d -> Text("• ${d.label}", color = TextPrimary, fontSize = 11.sp) }
                if (outputs.isEmpty()) Text("Tidak ada output terdeteksi.", color = TextMuted, fontSize = 10.sp)

                Divider(color = CharcoalBorder)
                Text("Pemrosesan mikrofon", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                ToggleRow(
                    title = "Peredam noise",
                    subtitle = if (micEffectSupport.noiseSuppression) "Kurangi dengung dan desis latar" else "Tidak didukung perangkat ini",
                    checked = noiseSuppression,
                    enabled = micEffectSupport.noiseSuppression,
                    onCheckedChange = { onMicEffectsChange(it, echoCancel, autoGain) },
                    testTag = "mic_fx_noise"
                )
                ToggleRow(
                    title = "Peredam gema (echo)",
                    subtitle = if (micEffectSupport.echoCancel) "Cegah suara speaker masuk lagi ke mikrofon" else "Tidak didukung perangkat ini",
                    checked = echoCancel,
                    enabled = micEffectSupport.echoCancel,
                    onCheckedChange = { onMicEffectsChange(noiseSuppression, it, autoGain) },
                    testTag = "mic_fx_echo"
                )
                ToggleRow(
                    title = "Gain otomatis",
                    subtitle = if (micEffectSupport.autoGain) "Ratakan volume suara saat berbicara pelan atau keras" else "Tidak didukung perangkat ini",
                    checked = autoGain,
                    enabled = micEffectSupport.autoGain,
                    onCheckedChange = { onMicEffectsChange(noiseSuppression, echoCancel, it) },
                    testTag = "mic_fx_gain"
                )
                Text(
                    "Efek bawaan Android, hanya untuk mikrofon (bukan audio game). Hasilnya bergantung perangkat; " +
                        "matikan bila suara mic terdengar aneh. Semua mati = mikrofon tidak diproses sama sekali.",
                    color = TextMuted, fontSize = 10.sp
                )

                Text(
                    "Catatan: output hanya ditampilkan. Android tidak mengizinkan aplikasi memindahkan output audio " +
                        "aplikasi/game lain; itu diatur sistem. Audio game tertangkap lewat AudioPlaybackCapture " +
                        "(Android 10+) apa pun outputnya, dan hanya jika game mengizinkan capture. " +
                        "Mic Bluetooth bergantung pada dukungan perangkat.",
                    color = TextMuted, fontSize = 10.sp
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Tutup", color = ChampagneGoldLight)
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(label: String, chosen: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (chosen) CharcoalSurface else Color.Transparent,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (chosen) ChampagneGold else CharcoalBorder),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick)
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(label, color = if (chosen) TextPrimary else TextSecondary, fontSize = 12.sp)
        }
    }
}
