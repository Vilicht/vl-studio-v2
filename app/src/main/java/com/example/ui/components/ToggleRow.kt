package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CharcoalBorder
import com.example.ui.theme.CharcoalMain
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.ChampagneGold
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Baris pengaturan on/off bertema Charcoal + Champagne Gold, sama gayanya dengan baris pilihan lain.
 * [enabled] = false menampilkan baris redup yang tidak bisa diubah (mis. efek tidak didukung perangkat).
 */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String = ""
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 52.dp)
        .let { if (testTag.isNotEmpty()) it.testTag(testTag) else it }
        .let { if (enabled) it.clickable { onCheckedChange(!checked) } else it }
    Surface(
        color = if (checked && enabled) CharcoalSurface else Color.Transparent,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (checked && enabled) ChampagneGold else CharcoalBorder),
        modifier = rowModifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (enabled) TextPrimary else TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = subtitle, color = if (enabled) TextSecondary else TextMuted, fontSize = 10.sp)
            }
            Switch(
                checked = checked && enabled,
                onCheckedChange = if (enabled) onCheckedChange else null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CharcoalMain,
                    checkedTrackColor = ChampagneGold,
                    uncheckedThumbColor = TextSecondary,
                    uncheckedTrackColor = CharcoalSurface,
                    uncheckedBorderColor = CharcoalBorder
                )
            )
        }
    }
}
