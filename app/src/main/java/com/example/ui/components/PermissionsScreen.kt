package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.permissions.ActionKind
import com.example.permissions.Level
import com.example.permissions.OemGuide
import com.example.permissions.PermissionCenter
import com.example.permissions.PermissionItem
import com.example.permissions.Readiness
import com.example.permissions.UsageContext
import com.example.ui.theme.CharcoalBorder
import com.example.ui.theme.CharcoalMain
import com.example.ui.theme.CharcoalPanel
import com.example.ui.theme.CharcoalSurface
import com.example.ui.theme.ChampagneGold
import com.example.ui.theme.ChampagneGoldLight
import com.example.ui.theme.StatusAmber
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusRed
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

private fun levelColor(l: Level): Color = when (l) {
    Level.OK -> StatusGreen
    Level.WARNING -> StatusAmber
    Level.ERROR -> StatusRed
    Level.NEUTRAL -> TextMuted
}

/**
 * Halaman "Permissions & Device Protection". Semua status dibaca langsung dari Android setiap kali
 * halaman dibuka, aplikasi kembali dari Settings (ON_RESUME), atau [refreshSignal] berubah.
 * Halaman ini MEMBERI INFORMASI; tidak ada yang dipaksa dan tidak ada yang memblokir studio.
 */
@Composable
fun PermissionsScreen(
    isOpen: Boolean,
    usage: UsageContext,
    refreshSignal: Int,
    /** Activity yang meluncurkan dialog izin (perlu ActivityResult launcher). id: "camera" | "mic" | "notifications". */
    onGrant: (String) -> Unit,
    onRetryService: () -> Unit,
    onClose: () -> Unit
) {
    if (!isOpen) return
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var instructionsFor by remember { mutableStateOf<PermissionItem?>(null) }
    var wizardOpen by remember { mutableStateOf(false) }

    // Jangan andalkan status lama: baca ulang saat kembali dari Settings / dialog izin
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val report = remember(tick, refreshSignal, usage) { PermissionCenter.evaluate(ctx, usage) }

    fun perform(item: PermissionItem) {
        val a = item.action ?: return
        when (a.kind) {
            ActionKind.GRANT -> onGrant(item.id)
            ActionKind.OPEN_APP_SETTINGS -> PermissionCenter.openAppSettings(ctx)
            ActionKind.OPEN_OVERLAY_SETTINGS -> PermissionCenter.openOverlaySettings(ctx)
            ActionKind.DISABLE_BATTERY_OPT -> PermissionCenter.requestIgnoreBatteryOptimizations(ctx)
            ActionKind.OPEN_BACKGROUND_SETTINGS -> if (!OemGuide.tryOpen(ctx)) instructionsFor = item
            ActionKind.VIEW_INSTRUCTIONS -> instructionsFor = item
            ActionKind.RETRY -> { onRetryService(); tick++ }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(color = CharcoalMain, modifier = Modifier.fillMaxSize().testTag("permissions_screen")) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Row(
                    Modifier.fillMaxWidth().background(CharcoalPanel).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Shield, null, tint = ChampagneGold, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Permissions & Device Protection", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("VL Studio informs you. Nothing here is required to open the studio.", color = TextMuted, fontSize = 11.sp)
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = TextSecondary)
                    }
                }

                LazyColumn(
                    Modifier.fillMaxSize().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        val (label, color) = when (report.readiness) {
                            Readiness.READY -> "READY" to StatusGreen
                            Readiness.READY_WITH_WARNINGS -> "READY WITH WARNINGS" to StatusAmber
                            Readiness.RESTRICTED -> "RESTRICTED" to StatusRed
                        }
                        Surface(
                            color = CharcoalPanel,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth().testTag("protection_status")
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Device Protection Status", color = TextSecondary, fontSize = 11.sp)
                                Text(label, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text(report.headline, color = TextSecondary, fontSize = 12.sp)
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { wizardOpen = true },
                                    modifier = Modifier.heightIn(min = 44.dp)
                                ) { Text("Run device optimization wizard", fontSize = 12.sp, color = ChampagneGoldLight) }
                            }
                        }
                    }
                    items(report.items, key = { it.id }) { item -> PermissionRow(item) { perform(item) } }
                    item {
                        Text(
                            "Battery optimization exemption does not guarantee that Android or the device " +
                                "manufacturer will never stop the app. It only reduces the chance.",
                            color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }

    instructionsFor?.let {
        AlertDialog(
            onDismissRequest = { instructionsFor = null },
            containerColor = CharcoalPanel,
            title = { Text(OemGuide.current()?.displayName ?: "Background settings", color = TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Android cannot open this screen directly on your device, so follow these steps. " +
                            "Wording can differ slightly between software versions.",
                        color = TextSecondary, fontSize = 12.sp
                    )
                    OemGuide.steps().forEachIndexed { i, step ->
                        Text("${i + 1}. $step", color = TextPrimary, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { PermissionCenter.openAppSettings(ctx); instructionsFor = null }) {
                    Text("Open App Settings", color = ChampagneGoldLight)
                }
            },
            dismissButton = { TextButton(onClick = { instructionsFor = null }) { Text("Close", color = TextSecondary) } }
        )
    }

    if (wizardOpen) {
        OptimizationWizard(
            report = report.items,
            onGrant = onGrant,
            onShowOem = { instructionsFor = report.items.firstOrNull { it.id == "autostart" } },
            onDone = { wizardOpen = false; tick++ }
        )
    }
}

@Composable
private fun PermissionRow(item: PermissionItem, onAction: () -> Unit) {
    val color = levelColor(item.level)
    Surface(
        color = CharcoalPanel,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, CharcoalBorder),
        modifier = Modifier.fillMaxWidth().testTag("perm_${item.id}")
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = when (item.level) {
                    Level.OK -> Icons.Default.CheckCircle
                    Level.WARNING -> Icons.Default.Warning
                    Level.ERROR -> Icons.Default.Error
                    Level.NEUTRAL -> Icons.Default.RemoveCircleOutline
                },
                contentDescription = when (item.level) {
                    Level.OK -> "OK"; Level.WARNING -> "Warning"; Level.ERROR -> "Error"; Level.NEUTRAL -> "Not applicable"
                },
                tint = color, modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(item.explanation, color = TextMuted, fontSize = 11.sp)
                Text(item.statusText, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (item.level != Level.OK && item.impact != null) {
                    Text(item.impact, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
                item.action?.let { a ->
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onAction,
                        modifier = Modifier.heightIn(min = 40.dp),
                        border = BorderStroke(1.dp, color.copy(alpha = 0.7f))
                    ) { Text(a.label, fontSize = 11.sp, color = TextPrimary) }
                }
            }
        }
    }
}

/**
 * Wizard "VL Studio Device Optimization": menjelaskan tiap pengaturan lalu membiarkan pengguna
 * mengatur atau melewatinya. Setiap langkah bisa dilewati.
 */
@Composable
fun OptimizationWizard(
    report: List<PermissionItem>,
    onGrant: (String) -> Unit,
    onShowOem: () -> Unit,
    onDone: () -> Unit
) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val last = 4

    data class Page(val title: String, val body: String, val actionLabel: String?, val action: () -> Unit)

    val battery = report.firstOrNull { it.id == "battery" }
    val notif = report.firstOrNull { it.id == "notifications" }
    val pages = listOf(
        Page(
            "VL Studio Device Optimization",
            "Live streams run for a long time. Android and some manufacturers stop background apps to save " +
                "power. These optional steps lower the chance that your stream gets interrupted. " +
                "You can skip any step and change everything later in Permissions & Device Protection.",
            null, {}
        ),
        Page(
            "Battery optimization",
            if (battery?.level == Level.OK) "Already unrestricted. Nothing to do."
            else "Battery optimization may interrupt your live stream. Android will ask you to confirm. " +
                "Exempting the app reduces interruptions but does not guarantee the stream is never stopped.",
            if (battery?.level == Level.OK) null else "Disable Battery Optimization",
            { PermissionCenter.requestIgnoreBatteryOptimizations(ctx) }
        ),
        Page(
            "Background activity & auto start",
            "Some devices (${OemGuide.current()?.displayName ?: "your device"}) add their own limits. " +
                "Android cannot tell us whether they are on, so please check them once.",
            "View instructions", onShowOem
        ),
        Page(
            "Notifications",
            if (notif?.level == Level.OK) "Notifications are enabled. The stream notification shows the LIVE status, duration, and Stop / Return buttons."
            else "Notifications show the LIVE status with Stop and Return to Studio buttons. Without them, streaming still works but you lose those controls.",
            if (notif?.level == Level.OK) null else (notif?.action?.label ?: "Open App Settings"),
            {
                if (notif?.action?.kind == ActionKind.GRANT) onGrant("notifications")
                else PermissionCenter.openAppSettings(ctx)
            }
        ),
        Page("All set", "You can reopen this wizard any time from Permissions & Device Protection.", null, {})
    )
    val page = pages[step]

    AlertDialog(
        onDismissRequest = onDone,
        containerColor = CharcoalSurface,
        title = { Text(page.title, color = TextPrimary, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Step ${step + 1} of ${last + 1}", color = TextMuted, fontSize = 10.sp)
                Text(page.body, color = TextSecondary, fontSize = 13.sp)
                page.actionLabel?.let {
                    Button(
                        onClick = page.action,
                        colors = ButtonDefaults.buttonColors(containerColor = ChampagneGold, contentColor = CharcoalMain),
                        modifier = Modifier.heightIn(min = 44.dp)
                    ) { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (step >= last) onDone() else step++ }) {
                Text(if (step >= last) "Done" else if (step == 0) "Start" else "Next", color = ChampagneGoldLight)
            }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text(if (step >= last) "" else "Skip for now", color = TextSecondary) }
        }
    )
}
