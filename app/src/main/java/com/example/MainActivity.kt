package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import com.example.audio.GameAudioStatus
import com.example.audio.StreamAudioParams
import com.example.data.model.SceneEntity
import com.example.engine.CameraLayerSource
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.permissions.PermissionCenter
import com.example.permissions.PermissionPrefs
import com.example.permissions.UsageContext
import com.example.service.CameraPreviewState
import com.example.service.StreamingService
import com.example.service.StreamingStatus
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.StudioViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setupImmersiveMode()

        setContent {
            VilichtStudioTheme {
                StudioAppScreen(
                    viewModel = viewModel,
                    onRequestScreenCapture = { launchScreenCaptureConsent() },
                    onReconnectCapture = { reconnectCapture() },
                    onStartCameraPreview = { startCameraPreviewService() },
                    onStopCameraPreview = { stopCameraPreviewService() },
                    onRetryCameraPreview = { retryCameraPreview() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupImmersiveMode()
        // TIDAK ada pembekuan otomatis di sini. Siaran dan service tidak bergantung pada Activity.
    }

    override fun onStart() {
        super.onStart()
        // Studio terlihat: jeda capture layar game (game membeku di frame terakhir) supaya Studio
        // tidak ikut ter-capture. Kamera, overlay, encoder, dan RTMP tetap berjalan.
        viewModel.setEditorVisible(true)
    }

    override fun onStop() {
        // Pengguna pindah ke game / aplikasi lain: capture game lanjut. Service tetap hidup.
        viewModel.setEditorVisible(false)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setupImmersiveMode()
        }
    }

    private fun setupImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private var screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startPreviewService(result.resultCode, result.data!!)
        } else {
            pendingLiveTarget = null
            Toast.makeText(this, "Screen capture permission is required to stream games", Toast.LENGTH_SHORT).show()
        }
    }

    /** Tujuan siaran yang sudah divalidasi (endpoint RTMP gabungan Server URL + Stream Key). */
    private class LiveTarget(val endpoint: String, val audioBitrateKbps: Int)

    /** Diisi saat GO LIVE menunggu izin capture layar; dipakai agar siaran langsung mulai setelah izin diberikan. */
    private var pendingLiveTarget: LiveTarget? = null

    /** Validasi profil + stream key. Bila tidak valid: tampilkan alasannya, buka Settings, kembalikan null. */
    private fun buildLiveTarget(): LiveTarget? {
        val profile = viewModel.selectedProfile.value
        if (profile == null) {
            Toast.makeText(this, "Atur dulu tujuan RTMP (Server URL dan Stream Key) di Settings", Toast.LENGTH_LONG).show()
            viewModel.isSettingsOpen.value = true
            return null
        }
        val plainKey = viewModel.getDecryptedKey(profile.encryptedStreamKey)
        return when (val r = com.example.encoder.RtmpEndpoint.build(profile.serverUrl, plainKey)) {
            is com.example.encoder.RtmpEndpoint.Result.Ok -> LiveTarget(r.endpoint, profile.audioBitrateKbps)
            is com.example.encoder.RtmpEndpoint.Result.Invalid -> {
                Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
                viewModel.isSettingsOpen.value = true
                null
            }
        }
    }

    /** Dipanggil setelah dialog izin opsional (notifikasi/mikrofon) selesai, apa pun hasilnya. */
    private var afterOptionalPermissions: (() -> Unit)? = null
    private val optionalPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) viewModel.audioMixer.startMonitoring()
        val next = afterOptionalPermissions
        afterOptionalPermissions = null
        next?.invoke()
    }

    /**
     * Izin yang berguna untuk siaran tapi TIDAK wajib (mikrofon, notifikasi). Diminta tepat saat pengguna
     * menekan GO LIVE, SEKALI saja. Jika ditolak, siaran tetap lanjut tanpa fitur itu.
     */
    private fun askOptionalLivePermissionsThen(next: () -> Unit) {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            PermissionCenter.canRequest(this, it) && !PermissionPrefs.wasAsked(this, it)
        }
        if (wanted.isEmpty()) { next(); return }
        wanted.forEach { PermissionPrefs.markAsked(this, it) }
        afterOptionalPermissions = next
        optionalPermissionLauncher.launch(wanted.toTypedArray())
    }

    /**
     * Sambungkan ulang capture layar ke engine yang SUDAH berjalan (siaran tidak dihentikan, encoder tidak dibuat ulang).
     * Dipakai saat sesi MediaProjection berakhir. Bila sesi masih valid, fungsi ini tidak dipanggil: tidak ada izin ulang.
     */
    private fun reconnectCapture() {
        pendingLiveTarget = null
        val mp = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mp.createScreenCaptureIntent())
    }

    private fun launchScreenCaptureConsent() = askOptionalLivePermissionsThen { proceedGoLive() }

    private fun proceedGoLive() {
        // Validasi Server URL + Stream Key SEBELUM meminta izin apa pun, supaya salah isi langsung ketahuan
        val target = buildLiveTarget() ?: return
        val st = viewModel.liveState.value
        val needsScreenNow = viewModel.currentSources.value.any { it.type == com.example.data.model.SourceType.SCREEN && it.isVisible }
        // Capture sudah jalan (mode siap) dan sesi masih valid: GO LIVE langsung, TANPA izin ulang
        if (st.status == StreamingStatus.PREVIEW && (st.hasProjection || !needsScreenNow)) {
            sendGoLive()
            return
        }
        // Preview kamera sudah berjalan dan scene TIDAK memakai layar game: siaran langsung dari pipeline yang sama,
        // tanpa dialog capture layar dan tanpa membuka kamera ulang.
        val needsScreen = viewModel.currentSources.value.any { it.type == com.example.data.model.SourceType.SCREEN && it.isVisible }
        if (st.cameraPreview == CameraPreviewState.ACTIVE && !st.hasProjection && !needsScreen) {
            sendGoLive()
            return
        }
        // Overlay (kontrol melayang) itu opsional: beri tahu, jangan paksa dan jangan buka Settings sendiri
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Kontrol melayang di atas game nonaktif. Aktifkan di Permissions & Device Protection (opsional).",
                Toast.LENGTH_LONG
            ).show()
        }

        // Setelah izin capture diberikan, siaran LANGSUNG dimulai (tanpa langkah GO LIVE kedua)
        pendingLiveTarget = target
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mpManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    /** Izin capture diberikan: nyalakan mesin dalam mode siap (belum siaran), lalu ke game. */
    private fun startPreviewService(resultCode: Int, data: Intent) {
        val preset = viewModel.currentPreset.value
        val portrait = viewModel.selectedScene.value?.isPortrait ?: false
        val live = pendingLiveTarget
        pendingLiveTarget = null
        val serviceIntent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START_PREVIEW
            if (live != null) {
                putExtra(StreamingService.EXTRA_AUTO_ENDPOINT, live.endpoint)
                putExtra(StreamingService.EXTRA_AUTO_AUDIO_BITRATE, live.audioBitrateKbps)
            }
            putExtra(StreamingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StreamingService.EXTRA_DATA, data)
            putExtra(StreamingService.EXTRA_WIDTH, preset.width)
            putExtra(StreamingService.EXTRA_HEIGHT, preset.height)
            putExtra(StreamingService.EXTRA_FPS, preset.fps)
            putExtra(StreamingService.EXTRA_BITRATE, preset.defaultBitrateKbps)
            putExtra(StreamingService.EXTRA_PORTRAIT, portrait)
            viewModel.encodeCanvasFor(viewModel.selectedScene.value, preset).let {
                putExtra(StreamingService.EXTRA_CANVAS_W, it.width)
                putExtra(StreamingService.EXTRA_CANVAS_H, it.height)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        Toast.makeText(
            this,
            if (live != null) "Menghubungkan ke server RTMP…"
            else if (viewModel.liveState.value.status == StreamingStatus.LIVE) "Capture layar tersambung kembali."
            else "Siap. Buka game, lalu tekan 'Atur Layout' di tombol melayang.",
            Toast.LENGTH_LONG
        ).show()
        // Ke game: tombol melayang muncul di atasnya
        moveTaskToBack(true)
    }

    /**
     * Preview kamera mandiri (tanpa Go Live, tanpa capture layar). Hanya dimulai saat Activity terlihat,
     * karena Android 14+ hanya mengizinkan foreground service bertipe camera dimulai dalam keadaan itu.
     */
    fun startCameraPreviewService() {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        if (!PermissionCenter.isGranted(this, Manifest.permission.CAMERA)) return
        val preset = viewModel.currentPreset.value
        val portrait = viewModel.selectedScene.value?.isPortrait ?: false
        val i = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START_CAMERA_PREVIEW
            putExtra(StreamingService.EXTRA_WIDTH, preset.width)
            putExtra(StreamingService.EXTRA_HEIGHT, preset.height)
            putExtra(StreamingService.EXTRA_FPS, viewModel.cameraFps.value)
            putExtra(StreamingService.EXTRA_BITRATE, preset.defaultBitrateKbps)
            putExtra(StreamingService.EXTRA_PORTRAIT, portrait)
            viewModel.encodeCanvasFor(viewModel.selectedScene.value, preset).let {
                putExtra(StreamingService.EXTRA_CANVAS_W, it.width)
                putExtra(StreamingService.EXTRA_CANVAS_H, it.height)
            }
            putExtra(StreamingService.EXTRA_CAMERA_MAX_PIXELS, viewModel.cameraMaxPixels.value)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Preview kamera tidak bisa dimulai: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    fun stopCameraPreviewService() {
        startService(Intent(this, StreamingService::class.java).apply { action = StreamingService.ACTION_STOP_CAMERA_PREVIEW })
    }

    fun retryCameraPreview() {
        val st = viewModel.liveState.value
        if (st.cameraPreview != CameraPreviewState.OFF && st.serviceRunning) {
            // Engine hidup: buka ulang kamera yang sama
            startService(Intent(this, StreamingService::class.java).apply {
                action = StreamingService.ACTION_CAMERA_SETTINGS
                putExtra(StreamingService.EXTRA_CAMERA_MAX_PIXELS, viewModel.cameraMaxPixels.value)
                putExtra(StreamingService.EXTRA_FPS, viewModel.cameraFps.value)
            })
        } else {
            StreamingService.resetCameraPreviewState()   // efek di UI akan memulai ulang
        }
    }

    /** Layout sudah diatur: mulai siaran RTMP dengan profil tujuan yang dipilih. */
    private fun sendGoLive() {
        val target = buildLiveTarget() ?: return
        val goLiveIntent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_GO_LIVE
            putExtra(StreamingService.EXTRA_ENDPOINT, target.endpoint)
            putExtra(StreamingService.EXTRA_AUDIO_BITRATE, target.audioBitrateKbps)
        }
        startService(goLiveIntent)
        moveTaskToBack(true)
    }
}

@Composable
fun StudioAppScreen(
    viewModel: StudioViewModel,
    onRequestScreenCapture: () -> Unit,
    onReconnectCapture: () -> Unit = {},
    onStartCameraPreview: () -> Unit = {},
    onStopCameraPreview: () -> Unit = {},
    onRetryCameraPreview: () -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Tablet: panel melebar proporsional dengan layar (canvas tetap area kerja terbesar); ponsel tetap 288/260 dp
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val leftPanelWidth = (configuration.screenWidthDp * 0.22f).coerceIn(288f, 340f).dp
    val propsPanelWidth = (configuration.screenWidthDp * 0.24f).coerceIn(260f, 360f).dp

    val scenes by viewModel.allScenes.collectAsStateWithLifecycle()
    val selectedScene by viewModel.selectedScene.collectAsStateWithLifecycle()
    val sources by viewModel.currentSources.collectAsStateWithLifecycle()
    val selectedSource by viewModel.selectedSource.collectAsStateWithLifecycle()
    val profiles by viewModel.allProfiles.collectAsStateWithLifecycle()
    val selectedProfile by viewModel.selectedProfile.collectAsStateWithLifecycle()
    val audioSettings by viewModel.audioSettings.collectAsStateWithLifecycle()
    val liveState by viewModel.liveState.collectAsStateWithLifecycle()

    val micLevel by viewModel.audioMixer.micLevel.collectAsStateWithLifecycle()
    val systemLevel by viewModel.audioMixer.systemLevel.collectAsStateWithLifecycle()
    val gameAudioStatus by viewModel.audioMixer.gameStatus.collectAsStateWithLifecycle()
    val currentPreset by viewModel.currentPreset.collectAsStateWithLifecycle()
    val audioInputs by viewModel.audioDevices.inputs.collectAsStateWithLifecycle()
    val audioOutputs by viewModel.audioDevices.outputs.collectAsStateWithLifecycle()
    val audioEvent by viewModel.audioDevices.event.collectAsStateWithLifecycle()
    val cameras by viewModel.cameras.collectAsStateWithLifecycle()
    val cameraMaxPixels by viewModel.cameraMaxPixels.collectAsStateWithLifecycle()
    val cameraFps by viewModel.cameraFps.collectAsStateWithLifecycle()
    val isPermissionsOpen by viewModel.isPermissionsOpen.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val isSettingsOpen by viewModel.isSettingsOpen.collectAsStateWithLifecycle()
    val isDiagnosticsOpen by viewModel.isDiagnosticsOpen.collectAsStateWithLifecycle()
    val isAddSourceOpen by viewModel.isAddSourceOpen.collectAsStateWithLifecycle()
    val isAddSceneOpen by viewModel.isAddSceneOpen.collectAsStateWithLifecycle()
    val isAudioDevicesOpen by viewModel.isAudioDevicesOpen.collectAsStateWithLifecycle()

    var showSafeArea by rememberSaveable { mutableStateOf(true) }
    var snapEnabled by rememberSaveable { mutableStateOf(true) }
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    var showPreviewLive by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SceneEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<SceneEntity?>(null) }
    var selectedInputId by remember { mutableStateOf(0) }

    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    val referencePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.importImage(uri, asReference = true)
    }

    // ---- Izin dimintakan KONTEKSTUAL, bukan semuanya di awal. Tidak ada yang wajib untuk membuka studio.
    var permTick by remember { mutableStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) viewModel.audioMixer.startMonitoring()
        if (permissions[Manifest.permission.CAMERA] == true) viewModel.refreshCameras()
        permTick++   // paksa status izin dibaca ulang (kamera -> preview dimulai)
    }

    /** Minta satu izin. Dipakai tombol "Grant" (aksi eksplisit pengguna) dan permintaan kontekstual. */
    fun askPermission(permission: String, onlyIfNeverAsked: Boolean) {
        if (!PermissionCenter.canRequest(context, permission)) return
        if (onlyIfNeverAsked && PermissionPrefs.wasAsked(context, permission)) return
        PermissionPrefs.markAsked(context, permission)
        permissionLauncher.launch(arrayOf(permission))
    }

    val currentSourcesHasReference = sources.any { it.isReference }
    val hasCameraSource = sources.any { it.type == com.example.data.model.SourceType.CAMERA && it.isVisible }
    // Video/Browser overlay dirender oleh pipeline GL yang sama dengan kamera. Tanpa pipeline berjalan, source ini
    // hanya tampil sebagai placeholder, jadi pipeline juga dinyalakan untuk scene yang memilikinya.
    val hasGlOnlySource = sources.any {
        it.isVisible && (it.type == com.example.data.model.SourceType.VIDEO || it.type == com.example.data.model.SourceType.WEB)
    }
    val needsEnginePreview = hasCameraSource || hasGlOnlySource
    val cameraGranted = remember(permTick, liveState.cameraPreview) {
        PermissionCenter.isGranted(context, Manifest.permission.CAMERA)
    }
    val cameraPermanentlyDenied = remember(permTick) {
        PermissionCenter.isPermanentlyDenied(context, Manifest.permission.CAMERA)
    }

    // Pengguna menambah kamera: minta izin kamera SEKARANG (sekali). Jika ditolak, kanvas menjelaskan dan menawarkan tombol.
    val cameraRequested by viewModel.cameraRequested.collectAsStateWithLifecycle()
    LaunchedEffect(cameraRequested) {
        if (cameraRequested) {
            viewModel.consumeCameraRequest()
            askPermission(Manifest.permission.CAMERA, onlyIfNeverAsked = true)
        }
    }

    // Mikrofon: level meter hanya jalan kalau izin SUDAH ada (tidak memaksa)
    LaunchedEffect(permTick) {
        if (PermissionCenter.isGranted(context, Manifest.permission.RECORD_AUDIO)) viewModel.audioMixer.startMonitoring()
    }

    // Preview kamera mandiri: nyala selama ada sumber kamera + izin + tidak sedang siaran; mati saat kamera dihapus.
    var autoAttempts by remember { mutableStateOf(0) }
    LaunchedEffect(liveState.cameraPreview) {
        if (liveState.cameraPreview == CameraPreviewState.ACTIVE) autoAttempts = 0
    }
    LaunchedEffect(needsEnginePreview, cameraGranted, liveState.status, liveState.cameraPreview, liveState.serviceRunning) {
        val idle = liveState.status == StreamingStatus.OFFLINE
        if (needsEnginePreview && cameraGranted && idle && liveState.cameraPreview == CameraPreviewState.OFF && autoAttempts < 2) {
            kotlinx.coroutines.delay(400)   // beri waktu service sebelumnya selesai berhenti
            autoAttempts++
            onStartCameraPreview()
        } else if (!needsEnginePreview && idle && liveState.cameraPreview != CameraPreviewState.OFF) {
            onStopCameraPreview()
        }
    }

    // Pesan dari service (kamera terputus, dsb) dan dari ViewModel (hasil impor, dsb)
    LaunchedEffect(liveState.errorMessage) {
        liveState.errorMessage?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(message) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.consumeMessage() }
    }
    LaunchedEffect(audioEvent) {
        audioEvent?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); viewModel.audioDevices.consumeEvent() }
    }

    val engineRunning = (liveState.status != StreamingStatus.OFFLINE && liveState.status != StreamingStatus.ERROR) ||
        liveState.cameraPreview == CameraPreviewState.ACTIVE || liveState.cameraPreview == CameraPreviewState.STARTING
    val defaultCameraId = remember(cameras) { CameraLayerSource.pickDefaultId(context) }

    // ---- Blok UI yang dipakai bersama oleh layout landscape, portrait, dan fullscreen
    val canvasBlock: @Composable (Modifier) -> Unit = { mod ->
        StudioCanvas(
            sources = sources,
            selectedId = selectedSource?.id,
            isPortrait = selectedScene?.isPortrait ?: false,
            canvasAspect = selectedScene?.canvas?.aspect ?: (16f / 9f),
            gameSnapshot = liveState.frozenSnapshot,
            isEngineRunning = engineRunning,
            isEditorMode = liveState.isEditorMode,
            showSafeArea = showSafeArea,
            snapEnabled = snapEnabled,
            isFullscreen = isFullscreen,
            canUndo = canUndo,
            canRedo = canRedo,
            hasCamera = sources.any { it.type == com.example.data.model.SourceType.CAMERA },
            cameraMessage = when {
                !hasCameraSource -> null
                !cameraGranted -> if (cameraPermanentlyDenied) "Izin kamera ditolak. Aktifkan di Pengaturan aplikasi."
                    else "Izin kamera belum diberikan, kamera tidak bisa dipakai."
                liveState.cameraStatus != null -> liveState.cameraStatus
                else -> null
            },
            cameraNeedsPermission = !cameraGranted,
            captureMessage = if (!engineRunning || sources.none { it.type == com.example.data.model.SourceType.SCREEN && it.isVisible }) null
                else when {
                    !liveState.hasProjection -> "Capture layar belum/tidak aktif. Layar game menampilkan COMING SOON."
                    liveState.gameSource == com.example.engine.GameSourceState.PAUSED ->
                        "Layar game tidak mengirim frame. Menampilkan GAME PAUSED; kembali otomatis saat game aktif."
                    else -> null
                },
            onReconnectCapture = if (engineRunning && !liveState.hasProjection) onReconnectCapture else null,
            onCameraAction = {
                when {
                    !cameraGranted && cameraPermanentlyDenied -> PermissionCenter.openAppSettings(context)
                    !cameraGranted -> askPermission(Manifest.permission.CAMERA, onlyIfNeverAsked = false)
                    else -> { autoAttempts = 0; onRetryCameraPreview() }
                }
            },
            onSelect = { id -> viewModel.selectSource(id) },
            onBeginEdit = { viewModel.beginEdit() },
            onPreviewTransform = { viewModel.previewTransform(it) },
            onCommitTransform = { viewModel.commitTransform(it) },
            onSurfaceReady = { surface, w, h -> viewModel.attachPreview(surface, w, h) },
            onSurfaceGone = { viewModel.detachPreview(it) },
            onToggleFullscreen = { isFullscreen = !isFullscreen },
            onToggleSafeArea = { showSafeArea = !showSafeArea },
            onToggleSnap = { snapEnabled = !snapEnabled },
            onImportReference = {
                referencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onClearSnapshot = { viewModel.clearGameSnapshot() },
            onUndo = { viewModel.undo() },
            onRedo = { viewModel.redo() },
            onSwitchCamera = { viewModel.switchCamera() },
            modifier = mod
        )
    }

    val leftPanelBlock: @Composable (Modifier) -> Unit = { mod ->
        LeftPanel(
            scenes = scenes,
            selectedScene = selectedScene,
            sources = sources,
            selectedSource = selectedSource,
            onSelectScene = { viewModel.selectScene(it) },
            onAddScene = { viewModel.isAddSceneOpen.value = true },
            onDuplicateScene = { viewModel.duplicateScene(it) },
            onRenameScene = { renameTarget = it },
            onDeleteScene = { deleteTarget = it },
            onSelectSource = { viewModel.selectSource(it) },
            onAddSource = { viewModel.isAddSourceOpen.value = true },
            onToggleSourceVisibility = { viewModel.toggleVisibility(it) },
            onToggleSourceLock = { viewModel.toggleLock(it) },
            onReorderSource = { src, up -> viewModel.reorderSource(src, up) },
            onMoveLayer = { from, to -> viewModel.moveLayerByDrag(from, to) },
            onLayerCommand = { src, cmd ->
                when (cmd) {
                    LayerCommand.FRONT -> viewModel.bringToFront(src)
                    LayerCommand.FORWARD -> viewModel.bringForward(src)
                    LayerCommand.BACKWARD -> viewModel.sendBackward(src)
                    LayerCommand.BACK -> viewModel.sendToBack(src)
                }
            },
            onRenameSource = { src, name -> viewModel.renameSource(src, name) },
            panelWidth = leftPanelWidth,
            onDuplicateSource = { viewModel.duplicateSource(it) },
            onDeleteSource = { viewModel.deleteSource(it) },
            modifier = mod
        )
    }

    val propertiesBlock: @Composable (Modifier) -> Unit = { mod ->
        PropertiesPanel(
            selectedSource = selectedSource,
            onUpdateSource = { viewModel.updateSource(it) },
            micLevel = micLevel,
            onPngTuberConfig = { cfg -> selectedSource?.let { viewModel.updatePngTuberConfig(it, cfg) } },
            onPngTuberReplaceImage = { uri, talking -> selectedSource?.let { viewModel.replacePngTuberImage(it, uri, talking) } },
            scalePercent = selectedSource?.let { viewModel.scalePercent(it) } ?: 100f,
            onScalePercent = { p -> selectedSource?.let { viewModel.setScalePercent(it, p) } },
            onFit = { selectedSource?.let { viewModel.fitToCanvas(it) } },
            onCenter = { selectedSource?.let { viewModel.centerSource(it) } },
            onResetCropAndRatio = { selectedSource?.let { viewModel.resetCropAndRatio(it) } },
            onToggleFlip = { selectedSource?.let { viewModel.toggleFlipH(it) } },
            onRotate90 = { selectedSource?.let { viewModel.rotate90(it) } },
            onSetRotation = { d -> selectedSource?.let { viewModel.setRotation(it, d) } },
            onSetCrop = { l, t, r, b -> selectedSource?.let { viewModel.setCrop(it, l, t, r, b) } },
            cameras = cameras,
            defaultCameraId = defaultCameraId,
            onSetCamera = { id -> selectedSource?.let { viewModel.setCameraId(it, id) } },
            cameraMaxPixels = cameraMaxPixels,
            cameraFps = cameraFps,
            onSetCameraQuality = { px, f -> viewModel.setCameraQuality(px, f) },
            onFitGame = { selectedSource?.let { viewModel.fitToCanvas(it) } },
            onFill = { selectedSource?.let { viewModel.fillCanvas(it) } },
            supportsFit = selectedSource?.let { viewModel.supportsFit(it) } ?: false,
            onSetFitMode = { m -> selectedSource?.let { viewModel.setFitMode(it, m) } },
            onSetZoom = { z -> selectedSource?.let { viewModel.setZoom(it, z) } },
            onSetPan = { x, y -> selectedSource?.let { viewModel.setPan(it, x, y) } },
            onFullWidth = { selectedSource?.let { viewModel.fullWidth(it) } },
            onFullHeight = { selectedSource?.let { viewModel.fullHeight(it) } },
            onResetCrop = { selectedSource?.let { viewModel.resetCrop(it) } },
            coveredByName = selectedSource?.let { viewModel.coveredBy(it, sources) },
            panelWidth = propsPanelWidth,
            onVideoRestart = { selectedSource?.let { viewModel.restartVideo(it) } },
            onVideoStop = { selectedSource?.let { viewModel.stopVideo(it) } },
            onResetTransform = { selectedSource?.let { viewModel.resetTransform(it) } },
            hasReference = currentSourcesHasReference,
            onAlignToReference = { selectedSource?.let { viewModel.alignGameToReference(it) } },
            modifier = mod
        )
    }

    if (showPreviewLive) {
        val sceneCanvas = selectedScene?.canvas ?: com.example.composition.CanvasSpec.LANDSCAPE
        PreviewLiveDialog(
            canvas = sceneCanvas,
            encoder = viewModel.encodeCanvasFor(selectedScene, currentPreset),
            fps = currentPreset.fps,
            engineReady = liveState.status != StreamingStatus.OFFLINE && liveState.status != StreamingStatus.ERROR,
            isLive = liveState.status == StreamingStatus.LIVE || liveState.status == StreamingStatus.PAUSED ||
                liveState.status == StreamingStatus.CONNECTING || liveState.status == StreamingStatus.RECONNECTING,
            onAttach = { surface, w, h -> viewModel.attachPreview(surface, w, h) },
            onDetach = { viewModel.detachPreview(it) },
            onSetCanvas = { viewModel.setCanvas(it) },
            onClose = { showPreviewLive = false },
            pickTargetName = selectedSource?.takeIf {
                it.type == com.example.data.model.SourceType.CAMERA || it.type == com.example.data.model.SourceType.VIDEO ||
                    it.type == com.example.data.model.SourceType.IMAGE
            }?.name,
            onPickColor = { c -> selectedSource?.let { viewModel.updateSource(it.copy(chromaKeyColor = c, chromaKeyEnabled = true)) } }
        )
    }

    if (isFullscreen) {
        Box(Modifier.fillMaxSize().background(CharcoalMain)) {
            canvasBlock(Modifier.fillMaxSize())
        }
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("studio_scaffold"),
            containerColor = CharcoalMain,
            topBar = {
                StudioTopBar(
                    status = liveState.status,
                    stats = liveState.stats,
                    isPortraitCanvas = selectedScene?.isPortrait ?: false,
                    canvas = selectedScene?.canvas ?: com.example.composition.CanvasSpec.LANDSCAPE,
                    onSetCanvas = { viewModel.setCanvas(it) },
                    onToggleRatio = { viewModel.toggleCanvasOrientation() },
                    onPreviewLive = { showPreviewLive = true },
                    onOpenProtection = { viewModel.isPermissionsOpen.value = true },
                    onOpenDiagnostics = { viewModel.isDiagnosticsOpen.value = true },
                    onOpenSettings = { viewModel.isSettingsOpen.value = true },
                    onOpenChat = { viewModel.isChatOpen.value = true }
                )
            },
            bottomBar = {
                AudioMixerPanel(
                    status = liveState.status,
                    micLevel = micLevel,
                    systemLevel = systemLevel,
                    micVolume = audioSettings?.micVolume ?: 1.0f,
                    isMicMuted = StreamAudioParams.micMutedFlow.collectAsStateWithLifecycle().value,
                    systemVolume = audioSettings?.systemVolume ?: 0.8f,
                    isSystemMuted = audioSettings?.isSystemMuted ?: false,
                    currentPreset = currentPreset,
                    isAutoMode = selectedProfile?.isAutoMode ?: true,
                    onToggleMicMute = { viewModel.toggleMicMute() },
                    onUpdateMicVolume = { viewModel.updateMicVolume(it) },
                    onToggleSystemMute = {
                        val currentSys = audioSettings?.isSystemMuted ?: false
                        viewModel.updateSystemVolume(if (currentSys) 0.8f else 0.0f)
                    },
                    onUpdateSystemVolume = { viewModel.updateSystemVolume(it) },
                    onOpenPresetSelector = { viewModel.isSettingsOpen.value = true },
                    onGoLive = { onRequestScreenCapture() },
                    onStopStream = {
                        val stopIntent = Intent(context, StreamingService::class.java).apply {
                            action = StreamingService.ACTION_STOP
                        }
                        context.startService(stopIntent)
                    },
                    onTogglePause = { StreamingService.activeServiceInstance?.togglePause() },
                    gameStatusText = when (gameAudioStatus) {
                        GameAudioStatus.NOT_LIVE -> "aktif saat LIVE"
                        GameAudioStatus.ACTIVE -> null
                        GameAudioStatus.NO_SIGNAL -> "tidak ada sinyal"
                        GameAudioStatus.UNAVAILABLE_ANDROID -> "butuh Android 10+"
                        GameAudioStatus.UNAVAILABLE -> "tidak tersedia"
                    },
                    onOpenAudioDevices = { viewModel.isAudioDevicesOpen.value = true }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding).background(CharcoalMain)
            ) {
                if (isLandscape) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        leftPanelBlock(Modifier)
                        canvasBlock(Modifier.weight(1f))
                        propertiesBlock(Modifier)
                    }
                } else if (isTablet) {
                    // Tablet portrait: canvas di atas, panel Sources dan Properties berdampingan (tanpa tab)
                    Column(modifier = Modifier.fillMaxSize()) {
                        canvasBlock(Modifier.fillMaxWidth().weight(0.55f))
                        Row(modifier = Modifier.fillMaxWidth().weight(0.45f)) {
                            Box(Modifier.weight(1f).fillMaxHeight()) { leftPanelBlock(Modifier.fillMaxSize()) }
                            Box(Modifier.weight(1f).fillMaxHeight()) { propertiesBlock(Modifier.fillMaxSize()) }
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        canvasBlock(Modifier.fillMaxWidth().weight(0.55f))

                        var portraitTab by remember { mutableStateOf(0) }
                        TabRow(
                            selectedTabIndex = portraitTab,
                            containerColor = CharcoalPanel,
                            contentColor = ChampagneGold
                        ) {
                            Tab(selected = portraitTab == 0, onClick = { portraitTab = 0 },
                                text = { Text("Scenes & Sources", fontSize = 12.sp) })
                            Tab(selected = portraitTab == 1, onClick = { portraitTab = 1 },
                                text = { Text("Properties", fontSize = 12.sp) })
                        }
                        Box(modifier = Modifier.fillMaxWidth().weight(0.45f)) {
                            if (portraitTab == 0) leftPanelBlock(Modifier.fillMaxSize())
                            else propertiesBlock(Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }

    // Modal Dialogs
    val isChatOpen by viewModel.isChatOpen.collectAsStateWithLifecycle()
    ChatDialog(
        isOpen = isChatOpen,
        runtime = viewModel.chat,
        overlayVisible = sources.firstOrNull { it.type == com.example.data.model.SourceType.CHAT }?.isVisible,
        onToggleOverlay = { viewModel.toggleChatOverlay() },
        onDismiss = { viewModel.isChatOpen.value = false }
    )

    SettingsDialog(
        isOpen = isSettingsOpen,
        profiles = profiles,
        selectedProfile = selectedProfile,
        autoDetection = viewModel.autoDetection,
        currentPreset = currentPreset,
        getDecryptedKey = { viewModel.getDecryptedKey(it) },
        onSaveProfile = { prof, key -> viewModel.saveProfile(prof, key) },
        onSelectProfile = { viewModel.selectProfile(it) },
        onSelectPreset = { viewModel.selectPreset(it) },
        onSetAutoMode = { viewModel.setAutoPresetMode() },
        onDismiss = { viewModel.isSettingsOpen.value = false }
    )

    DiagnosticsDialog(
        isOpen = isDiagnosticsOpen,
        stats = liveState.stats,
        onDismiss = { viewModel.isDiagnosticsOpen.value = false }
    )

    AddSourceDialog(
        isOpen = isAddSourceOpen,
        onAddSource = { name, type, text -> viewModel.addSource(name, type, text) },
        onAddWebUrl = { name, url -> viewModel.addWebUrl(name, url) },
        onImportHtml = { name, uris -> viewModel.importLocalHtml(name, uris) },
        onImportImage = { viewModel.importImage(it, asReference = false) },
        onImportVideo = { uri, name -> viewModel.importVideo(uri, name) },
        onAddVideoUrl = { name, url -> viewModel.addVideoUrl(name, url) },
        onAddPngTuber = { name, idle, talk -> viewModel.importPngTuber(name, idle, talk) },
        onDismiss = { viewModel.isAddSourceOpen.value = false }
    )

    AddSceneDialog(
        isOpen = isAddSceneOpen,
        onAddScene = { name, canvas -> viewModel.createScene(name, canvas) },
        onDismiss = { viewModel.isAddSceneOpen.value = false }
    )

    AudioDevicesDialog(
        isOpen = isAudioDevicesOpen,
        inputs = audioInputs,
        outputs = audioOutputs,
        selectedInputId = selectedInputId,
        onSelectInput = { selectedInputId = it; viewModel.audioDevices.selectInput(it) },
        onDismiss = { viewModel.isAudioDevicesOpen.value = false }
    )

    // Hapus scene selalu dengan konfirmasi
    deleteTarget?.let { sc ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = CharcoalPanel,
            title = { Text("Hapus scene?", color = TextPrimary) },
            text = { Text("Scene \"${sc.name}\" beserta semua source-nya akan dihapus.", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteScene(sc); deleteTarget = null }) {
                    Text("Hapus", color = StatusRed)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Batal", color = TextSecondary) } }
        )
    }

    renameTarget?.let { sc ->
        var name by remember(sc.id) { mutableStateOf(sc.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = CharcoalPanel,
            title = { Text("Ganti nama scene", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 14.sp)
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.renameScene(sc, name); renameTarget = null }) {
                    Text("Simpan", color = ChampagneGoldLight)
                }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Batal", color = TextSecondary) } }
        )
    }

    // ---- Permissions & Device Protection + wizard pertama kali (opsional, bisa dilewati)
    val protectionUsage = UsageContext(
        usesCamera = hasCameraSource,
        foregroundServiceRunning = liveState.serviceRunning,
        wakeLockHeld = liveState.stats.wakeLockHeld,
        captureSession = if (liveState.serviceRunning) liveState.hasProjection else null,
        serviceFailure = liveState.errorMessage?.takeIf { it.contains("menolak menjalankan service") }
    )
    val grantById: (String) -> Unit = { id ->
        when (id) {
            "camera" -> askPermission(Manifest.permission.CAMERA, onlyIfNeverAsked = false)
            "mic" -> askPermission(Manifest.permission.RECORD_AUDIO, onlyIfNeverAsked = false)
            "notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                askPermission(Manifest.permission.POST_NOTIFICATIONS, onlyIfNeverAsked = false)
        }
    }
    PermissionsScreen(
        isOpen = isPermissionsOpen,
        usage = protectionUsage,
        refreshSignal = permTick,
        onGrant = grantById,
        onRetryService = { autoAttempts = 0; onRetryCameraPreview() },
        onClose = { viewModel.isPermissionsOpen.value = false }
    )
    var firstRunWizard by remember { mutableStateOf(!PermissionPrefs.wizardSeen(context)) }
    if (firstRunWizard && !isPermissionsOpen) {
        OptimizationWizard(
            report = remember(permTick, protectionUsage) { PermissionCenter.evaluate(context, protectionUsage).items },
            onGrant = grantById,
            onShowOem = { viewModel.isPermissionsOpen.value = true },
            onDone = { PermissionPrefs.setWizardSeen(context); firstRunWizard = false }
        )
    }
}
