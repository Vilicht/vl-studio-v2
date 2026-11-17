package com.example.permissions

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Warna indikator di UI: hijau, kuning, merah, abu-abu. */
enum class Level { OK, WARNING, ERROR, NEUTRAL }

enum class ActionKind {
    GRANT, OPEN_APP_SETTINGS, DISABLE_BATTERY_OPT, OPEN_BACKGROUND_SETTINGS, VIEW_INSTRUCTIONS, RETRY, OPEN_OVERLAY_SETTINGS
}

data class ItemAction(val kind: ActionKind, val label: String)

data class PermissionItem(
    val id: String,
    val title: String,
    val explanation: String,
    val statusText: String,
    val level: Level,
    val action: ItemAction? = null,
    /** Fitur yang terpengaruh bila item ini bermasalah. Hanya informasi, tidak memblokir apa pun. */
    val impact: String? = null,
    /** true bila item ini benar-benar menghalangi konfigurasi yang sedang dipakai (mis. kamera dipakai tapi izin ditolak). */
    val blocksCurrentSetup: Boolean = false
)

enum class Readiness { READY, READY_WITH_WARNINGS, RESTRICTED }

data class ProtectionReport(
    val items: List<PermissionItem>,
    val readiness: Readiness,
    val headline: String
)

/** Apa yang sedang dipakai pengguna. Izin hanya "wajib" bila fiturnya dipakai. */
data class UsageContext(
    val usesCamera: Boolean = false,
    val foregroundServiceRunning: Boolean = false,
    val wakeLockHeld: Boolean = false,
    val serviceFailure: String? = null,
    /** Sesi capture layar (MediaProjection): null = service belum berjalan, true/false = sesi valid/berakhir. */
    val captureSession: Boolean? = null
)

/**
 * Pencatat izin yang sudah pernah diminta. Dipakai agar dialog izin Android tidak muncul berulang
 * setelah pengguna menolak, dan untuk mendeteksi "ditolak permanen" (Android tidak punya API langsung).
 */
object PermissionPrefs {
    private const val FILE = "vl_permissions"
    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun markAsked(c: Context, permission: String) { p(c).edit().putBoolean("asked:$permission", true).apply() }
    fun wasAsked(c: Context, permission: String) = p(c).getBoolean("asked:$permission", false)

    fun wizardSeen(c: Context) = p(c).getBoolean("wizard_seen", false)
    fun setWizardSeen(c: Context) { p(c).edit().putBoolean("wizard_seen", true).apply() }
}

object PermissionCenter {

    fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    fun isGranted(c: Context, permission: String) =
        ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Android tidak punya API "ditolak permanen". Pendekatan resmi: sudah pernah diminta, belum diberikan,
     * dan shouldShowRequestPermissionRationale == false -> sistem tidak akan menampilkan dialog lagi.
     */
    fun isPermanentlyDenied(c: Context, permission: String): Boolean {
        if (isGranted(c, permission)) return false
        if (!PermissionPrefs.wasAsked(c, permission)) return false
        val a = c.findActivity() ?: return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(a, permission)
    }

    /** true bila dialog izin masih boleh/berguna diminta (belum pernah ditolak permanen). */
    fun canRequest(c: Context, permission: String) =
        !isGranted(c, permission) && !isPermanentlyDenied(c, permission)

    fun isIgnoringBatteryOptimizations(c: Context): Boolean {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(c.packageName)
    }

    /** null bila tidak bisa dideteksi di versi Android ini. */
    fun isBackgroundRestricted(c: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.isBackgroundRestricted
    }

    fun notificationsEnabled(c: Context): Boolean =
        NotificationManagerCompat.from(c).areNotificationsEnabled()

    fun canDrawOverlays(c: Context): Boolean = Settings.canDrawOverlays(c)

    // ------------------------------------------------------------------ evaluasi

    fun evaluate(c: Context, usage: UsageContext): ProtectionReport {
        val items = mutableListOf<PermissionItem>()

        // 1. Kamera
        val camGranted = isGranted(c, Manifest.permission.CAMERA)
        items += when {
            camGranted -> PermissionItem(
                "camera", "Camera", "Needed for camera sources and the live camera preview.",
                "Granted", Level.OK
            )
            isPermanentlyDenied(c, Manifest.permission.CAMERA) -> PermissionItem(
                "camera", "Camera", "Needed for camera sources and the live camera preview.",
                "Permanently denied", if (usage.usesCamera) Level.ERROR else Level.NEUTRAL,
                ItemAction(ActionKind.OPEN_APP_SETTINGS, "Open App Settings"),
                "Camera sources cannot be used until camera access is granted. Android will not show the request again; enable it in App Settings.",
                blocksCurrentSetup = usage.usesCamera
            )
            else -> PermissionItem(
                "camera", "Camera", "Needed for camera sources and the live camera preview.",
                if (usage.usesCamera) "Not granted" else "Not granted (not needed right now)",
                if (usage.usesCamera) Level.WARNING else Level.NEUTRAL,
                ItemAction(ActionKind.GRANT, "Grant Permission"),
                "Camera sources cannot be used until camera access is granted.",
                blocksCurrentSetup = usage.usesCamera
            )
        }

        // 2. Mikrofon (tidak fatal: audio game tetap bisa tertangkap di Android 10+)
        val micGranted = isGranted(c, Manifest.permission.RECORD_AUDIO)
        items += when {
            micGranted -> PermissionItem(
                "mic", "Microphone", "Needed to include your voice in the stream.", "Granted", Level.OK
            )
            isPermanentlyDenied(c, Manifest.permission.RECORD_AUDIO) -> PermissionItem(
                "mic", "Microphone", "Needed to include your voice in the stream.",
                "Permanently denied", Level.ERROR,
                ItemAction(ActionKind.OPEN_APP_SETTINGS, "Open App Settings"),
                "Microphone audio capture is unavailable. Game audio can still be captured on Android 10+."
            )
            else -> PermissionItem(
                "mic", "Microphone", "Needed to include your voice in the stream.", "Not granted", Level.WARNING,
                ItemAction(ActionKind.GRANT, "Grant Permission"),
                "Microphone audio capture is unavailable."
            )
        }

        // 3. Notifikasi
        items += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val g = isGranted(c, Manifest.permission.POST_NOTIFICATIONS)
            when {
                g && notificationsEnabled(c) -> PermissionItem(
                    "notifications", "Notifications",
                    "Shows the live status and the Stop / Return to Studio buttons.", "Granted", Level.OK
                )
                isPermanentlyDenied(c, Manifest.permission.POST_NOTIFICATIONS) || (g && !notificationsEnabled(c)) ->
                    PermissionItem(
                        "notifications", "Notifications",
                        "Shows the live status and the Stop / Return to Studio buttons.",
                        "Disabled", Level.WARNING,
                        ItemAction(ActionKind.OPEN_APP_SETTINGS, "Open App Settings"),
                        "Streaming notifications and notification controls may be unavailable. Streaming itself still works."
                    )
                else -> PermissionItem(
                    "notifications", "Notifications",
                    "Shows the live status and the Stop / Return to Studio buttons.", "Not granted", Level.WARNING,
                    ItemAction(ActionKind.GRANT, "Grant Permission"),
                    "Streaming notifications and notification controls may be unavailable."
                )
            }
        } else {
            if (notificationsEnabled(c)) PermissionItem(
                "notifications", "Notifications",
                "Shows the live status and the Stop / Return to Studio buttons.",
                "Enabled (no permission needed on this Android version)", Level.OK
            ) else PermissionItem(
                "notifications", "Notifications",
                "Shows the live status and the Stop / Return to Studio buttons.", "Disabled", Level.WARNING,
                ItemAction(ActionKind.OPEN_APP_SETTINGS, "Open App Settings"),
                "Streaming notifications and notification controls may be unavailable."
            )
        }

        // 4. Optimasi baterai
        items += if (isIgnoringBatteryOptimizations(c)) PermissionItem(
            "battery", "Battery optimization",
            "Android may pause apps in the background to save power.", "Unrestricted ✓", Level.OK
        ) else PermissionItem(
            "battery", "Battery optimization",
            "Android may pause apps in the background to save power.", "Restricted", Level.WARNING,
            ItemAction(ActionKind.DISABLE_BATTERY_OPT, "Disable Battery Optimization"),
            "Long-running background streaming may be interrupted by Android."
        )

        // 5. Pembatasan aktivitas latar belakang
        val bgRestricted = isBackgroundRestricted(c)
        items += when (bgRestricted) {
            null -> PermissionItem(
                "background", "Background activity",
                "Whether you restricted this app's background activity.",
                "Cannot be detected on this Android version", Level.NEUTRAL
            )
            true -> PermissionItem(
                "background", "Background activity",
                "Whether you restricted this app's background activity.", "Restricted by system settings", Level.ERROR,
                ItemAction(ActionKind.OPEN_APP_SETTINGS, "Open App Settings"),
                "Android is restricting this app in the background. Streaming will likely stop when you leave the app.",
                blocksCurrentSetup = true
            )
            false -> PermissionItem(
                "background", "Background activity",
                "Whether you restricted this app's background activity.", "Allowed", Level.OK
            )
        }

        // 6. Auto-start (tidak ada API resmi untuk membaca statusnya)
        val oem = OemGuide.current()
        items += if (oem != null) PermissionItem(
            "autostart", "Auto start / OEM background limits",
            "${oem.displayName} adds its own background limits on top of Android.",
            "Cannot be detected - verify manually", Level.NEUTRAL,
            ItemAction(
                if (OemGuide.canOpenDirectly(c)) ActionKind.OPEN_BACKGROUND_SETTINGS else ActionKind.VIEW_INSTRUCTIONS,
                if (OemGuide.canOpenDirectly(c)) "Open Background Settings" else "View Instructions"
            ),
            "Your device manufacturer may restrict background streaming."
        ) else PermissionItem(
            "autostart", "Auto start / OEM background limits",
            "Extra manufacturer limits on background apps.", "Not applicable on this device", Level.NEUTRAL
        )

        // 7. Foreground service
        items += when {
            usage.serviceFailure != null -> PermissionItem(
                "fgs", "Foreground service",
                "Keeps streaming alive when the app is minimized or the screen is off.",
                "Failed to start", Level.ERROR,
                ItemAction(ActionKind.RETRY, "Retry"),
                usage.serviceFailure, blocksCurrentSetup = true
            )
            usage.foregroundServiceRunning -> PermissionItem(
                "fgs", "Foreground service",
                "Keeps streaming alive when the app is minimized or the screen is off.", "Running", Level.OK
            )
            else -> PermissionItem(
                "fgs", "Foreground service",
                "Keeps streaming alive when the app is minimized or the screen is off.",
                "Idle - starts automatically with camera preview or Go Live", Level.NEUTRAL
            )
        }

        // 8. WakeLock
        val wl = isGranted(c, Manifest.permission.WAKE_LOCK)
        items += if (wl) PermissionItem(
            "wakelock", "WakeLock",
            "Keeps the CPU awake while streaming. Never keeps the screen on.",
            if (usage.wakeLockHeld) "Available - held now (streaming)" else "Available", Level.OK
        ) else PermissionItem(
            "wakelock", "WakeLock", "Keeps the CPU awake while streaming.",
            "Unavailable", Level.ERROR, null,
            "The CPU may sleep when the screen turns off, which can interrupt streaming."
        )

        // 9. Tampil di atas aplikasi lain (kontrol melayang saat main game) - opsional
        items += if (canDrawOverlays(c)) PermissionItem(
            "overlay", "Display over other apps",
            "Optional: floating live controls on top of your game.", "Granted", Level.OK
        ) else PermissionItem(
            "overlay", "Display over other apps",
            "Optional: floating live controls on top of your game.", "Not granted", Level.WARNING,
            ItemAction(ActionKind.OPEN_OVERLAY_SETTINGS, "Open Settings"),
            "The floating live control (Atur Layout, Stop, Mic) will not appear over your game. You can still stream."
        )

        // 10. Capture layar (MediaProjection): bukan izin manifest; token diberikan lewat dialog sistem saat mulai
        items += when (usage.captureSession) {
            true -> PermissionItem(
                "capture", "Screen capture session",
                "Granted by Android when you start. Switching games or apps does not ask again while the session is valid.",
                "Active", Level.OK
            )
            false -> PermissionItem(
                "capture", "Screen capture session",
                "The capture session ended (stopped by you or by the system).",
                "Ended", Level.WARNING, null,
                "The game layer shows a COMING SOON placeholder while the stream keeps running. Use Reconnect in Studio to grant a new session."
            )
            null -> PermissionItem(
                "capture", "Screen capture session",
                "Android asks for it every time you start; it cannot be pre-granted.",
                "Asked on start", Level.NEUTRAL
            )
        }

        // 11. Storage: tidak diperlukan (pemilih sistem + penyalinan ke penyimpanan aplikasi)
        items += PermissionItem(
            "storage", "Storage / media access",
            "Not needed: images, video and HTML are chosen with the system picker and copied into the app's own storage.",
            "Not required", Level.OK
        )

        val readiness = when {
            items.any { it.blocksCurrentSetup } -> Readiness.RESTRICTED
            items.any { it.level == Level.WARNING || it.level == Level.ERROR } -> Readiness.READY_WITH_WARNINGS
            else -> Readiness.READY
        }
        val headline = when (readiness) {
            Readiness.READY -> "Everything the current setup needs is available."
            Readiness.READY_WITH_WARNINGS -> "You can stream. Some optional protections are off."
            Readiness.RESTRICTED -> "Something in the current setup cannot work until it is fixed."
        }
        return ProtectionReport(items, readiness, headline)
    }

    // ------------------------------------------------------------------ aksi

    /** Buka pengaturan app (selalu tersedia). */
    fun openAppSettings(c: Context) {
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { c.startActivity(i) } catch (_: Exception) {}
    }

    fun openOverlaySettings(c: Context) {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { c.startActivity(i) } catch (_: Exception) { openAppSettings(c) }
    }

    /**
     * Dialog resmi "izinkan app mengabaikan optimasi baterai" (butuh REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * di manifest). Jika perangkat tidak menyediakannya, jatuh ke daftar pengecualian baterai, lalu ke
     * pengaturan app. Hasil akhirnya tidak dijamin: pengguna bisa menolak, dan OEM tetap boleh menghentikan app.
     */
    fun requestIgnoreBatteryOptimizations(c: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { c.startActivity(direct); return } catch (_: Exception) {}
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { c.startActivity(list); return } catch (_: Exception) {}
        openAppSettings(c)
    }
}

/** Panduan per produsen. Intent langsung ke layar OEM hanya "upaya terbaik"; panduan manual selalu tersedia. */
data class OemInfo(val displayName: String, val steps: List<String>, val intents: List<ComponentName>)

object OemGuide {
    private val xiaomi = OemInfo(
        "Xiaomi / Redmi / POCO",
        listOf(
            "Open Settings > Apps > Manage apps > VL Studio > Autostart and turn it ON.",
            "In the same screen open Battery saver and choose \"No restrictions\".",
            "Open the Recent Apps screen, long-press the VL Studio card and tap the lock icon."
        ),
        listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
    )
    private val samsung = OemInfo(
        "Samsung",
        listOf(
            "Open Settings > Battery > Background usage limits and remove VL Studio from \"Sleeping apps\" / \"Deep sleeping apps\".",
            "Open Settings > Apps > VL Studio > Battery and choose \"Unrestricted\".",
            "Open the Recent Apps screen, tap the VL Studio icon and choose \"Keep open\"."
        ),
        listOf(
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
        )
    )
    private val oppo = OemInfo(
        "OPPO",
        listOf(
            "Open Settings > Apps > App management > VL Studio > Battery usage and allow background activity.",
            "Enable \"Auto-launch\" / \"Allow auto-start\" for VL Studio.",
            "Open Recent Apps, pull down on the VL Studio card (or tap the lock) to lock it."
        ),
        listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
        )
    )
    private val realme = OemInfo(
        "Realme",
        listOf(
            "Open Settings > Apps > App management > VL Studio > Battery usage and allow background activity.",
            "Enable \"Auto-launch\" for VL Studio.",
            "Lock VL Studio in Recent Apps."
        ),
        listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
        )
    )
    private val vivo = OemInfo(
        "Vivo",
        listOf(
            "Open Settings > Battery > Background power consumption and allow VL Studio.",
            "Open iManager / Settings > Apps > Autostart and enable VL Studio.",
            "Lock VL Studio in Recent Apps."
        ),
        listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
        )
    )
    private val asus = OemInfo(
        "ASUS",
        listOf(
            "Open Mobile Manager > Autostart manager and allow VL Studio.",
            "Open Settings > Battery > PowerMaster / Battery optimization and set VL Studio to no restriction.",
            "Lock VL Studio in Recent Apps."
        ),
        listOf(
            ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
            ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings")
        )
    )

    /** Produsen perangkat ini, atau null bila bukan salah satu yang punya panduan. */
    fun current(): OemInfo? {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            "xiaomi" in m || "redmi" in m || "poco" in m -> xiaomi
            "samsung" in m -> samsung
            "realme" in m -> realme
            "oppo" in m -> oppo
            "vivo" in m || "iqoo" in m -> vivo
            "asus" in m -> asus
            else -> null
        }
    }

    private fun resolvable(c: Context): ComponentName? {
        val info = current() ?: return null
        return info.intents.firstOrNull { cn ->
            try {
                val i = Intent().setComponent(cn)
                c.packageManager.resolveActivity(i, 0) != null
            } catch (_: Exception) { false }
        }
    }

    fun canOpenDirectly(c: Context) = resolvable(c) != null

    /** true bila layar OEM berhasil dibuka. Jika false, tampilkan panduan manual. */
    fun tryOpen(c: Context): Boolean {
        val cn = resolvable(c) ?: return false
        return try {
            c.startActivity(Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }
    }

    /** Langkah manual; jika produsen tidak dikenal, langkah umum Android. */
    fun steps(): List<String> = current()?.steps ?: listOf(
        "Open Settings > Apps > VL Studio > Battery and choose \"Unrestricted\" (wording differs per device).",
        "Allow background activity for VL Studio if your device has that option.",
        "Lock VL Studio in Recent Apps if your device supports it."
    )
}
