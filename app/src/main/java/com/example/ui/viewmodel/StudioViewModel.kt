package com.example.ui.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.room.withTransaction
import com.example.audio.AudioDeviceMonitor
import com.example.audio.MicEffects
import com.example.composition.CanvasSpec
import com.example.composition.CompositionMath
import com.example.composition.FitMode
import com.example.composition.NRect
import com.example.engine.CameraInfo
import com.example.engine.CameraLayerSource
import com.example.engine.LocalOverlayStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioMixerController
import com.example.audio.StreamAudioParams
import com.example.data.db.StudioDatabase
import com.example.data.model.AudioSettingsEntity
import com.example.data.model.SceneEntity
import com.example.data.model.SourceEntity
import com.example.data.model.SourceType
import com.example.data.model.StreamProfileEntity
import com.example.data.repository.StudioRepository
import com.example.encoder.AutoDetectionResult
import com.example.encoder.DeviceCapabilityDetector
import com.example.encoder.StreamKeyStorage
import com.example.encoder.StreamPreset
import android.content.Intent
import com.example.service.CameraPreviewState
import com.example.service.LiveStudioState
import kotlin.math.abs
import kotlin.math.min
import com.example.service.StreamPrefs
import com.example.service.StreamingService
import com.example.service.StreamingStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val db = StudioDatabase.getInstance(application)
    private val repository = StudioRepository(
        db.sceneDao(),
        db.sourceDao(),
        db.streamProfileDao(),
        db.audioSettingsDao()
    )

    val audioMixer = AudioMixerController(application)
    val audioDevices = AudioDeviceMonitor(application)
    val autoDetection: AutoDetectionResult = DeviceCapabilityDetector.detectOptimalPreset(application)

    val allScenes: StateFlow<List<SceneEntity>> = repository.allScenes
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selectedScene: StateFlow<SceneEntity?> = repository.selectedScene
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _selectedSourceId = MutableStateFlow<String?>(null)
    val selectedSourceId: StateFlow<String?> = _selectedSourceId.asStateFlow()

    val currentSources: StateFlow<List<SourceEntity>> = selectedScene
        .flatMapLatest { scene ->
            if (scene != null) repository.getSourcesForScene(scene.id)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selectedSource: StateFlow<SourceEntity?> = combine(currentSources, _selectedSourceId) { list, id ->
        list.find { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val allProfiles: StateFlow<List<StreamProfileEntity>> = repository.allProfiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selectedProfile: StateFlow<StreamProfileEntity?> = repository.selectedProfile
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val audioSettings: StateFlow<AudioSettingsEntity?> = repository.audioSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val liveState: StateFlow<LiveStudioState> = StreamingService.studioState

    // UI dialog states
    val isSettingsOpen = MutableStateFlow(false)
    val isChatOpen = MutableStateFlow(false)
    /** Runtime chat (hub + TTS + pengaturan). Hidup mandiri dari service dan siaran. */
    val chat: com.example.chat.ChatRuntime by lazy { com.example.chat.ChatRuntime.get(getApplication()) }
    val isPermissionsOpen = MutableStateFlow(false)
    val isDiagnosticsOpen = MutableStateFlow(false)
    val isAddSourceOpen = MutableStateFlow(false)
    val isAddSceneOpen = MutableStateFlow(false)
    val isEditProfileOpen = MutableStateFlow(false)
    val isAudioDevicesOpen = MutableStateFlow(false)

    /** Pesan singkat untuk pengguna (toast). Activity mengonsumsinya lalu memanggil consumeMessage(). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }
    private fun say(msg: String) { _message.value = msg }

    // ---- Kamera yang terdeteksi (termasuk USB/eksternal)
    private val _cameras = MutableStateFlow<List<CameraInfo>>(emptyList())
    val cameras: StateFlow<List<CameraInfo>> = _cameras.asStateFlow()
    fun refreshCameras() { _cameras.value = CameraLayerSource.listCameras(getApplication()) }
    private val cameraAvailability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) { refreshCameras() }
        override fun onCameraUnavailable(cameraId: String) { refreshCameras() }
    }
    /** Webcam USB dicolok/dicabut: daftar kamera di picker ikut diperbarui. */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshCameras() }
    }

    // ---- Undo / Redo (per scene)
    private val undoStack = ArrayDeque<List<SourceEntity>>()
    private val redoStack = ArrayDeque<List<SourceEntity>>()
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    private var lastPushAt = 0L

    private fun pushUndo() {
        undoStack.addLast(currentSources.value)
        while (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        lastPushAt = System.currentTimeMillis()
        syncUndoFlags()
    }
    /** Slider/ketikan berturut-turut dihitung satu langkah undo. */
    private fun pushUndoCoalesced() {
        if (System.currentTimeMillis() - lastPushAt > 800) pushUndo()
    }
    private fun syncUndoFlags() { _canUndo.value = undoStack.isNotEmpty(); _canRedo.value = redoStack.isNotEmpty() }
    private fun clearHistory() { undoStack.clear(); redoStack.clear(); syncUndoFlags() }

    /** Dipanggil kanvas saat drag/resize dimulai: satu gestur = satu langkah undo. */
    fun beginEdit() = pushUndo()

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(currentSources.value)
        restore(prev)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(currentSources.value)
        restore(next)
    }

    private fun restore(list: List<SourceEntity>) {
        val scene = selectedScene.value ?: return
        syncUndoFlags()
        viewModelScope.launch { db.withTransaction { repository.replaceSources(scene.id, list) } }
    }

    // ---- Transformasi langsung dari kanvas (tanpa menunggu database)
    private val overrides = HashMap<String, SourceEntity>()

    private fun isEngineActive(st: StreamingStatus) =
        liveState.value.cameraPreview != CameraPreviewState.OFF ||
        st == StreamingStatus.PREVIEW || st == StreamingStatus.LIVE || st == StreamingStatus.PAUSED ||
            st == StreamingStatus.CONNECTING || st == StreamingStatus.RECONNECTING ||
            st == StreamingStatus.PREPARING || st == StreamingStatus.STOPPING

    private fun pushSceneToEngine() {
        if (!isEngineActive(liveState.value.status)) return
        StreamingService.activeServiceInstance?.applyScene(currentSources.value.map { overrides[it.id] ?: it })
    }

    fun previewTransform(updated: SourceEntity) {
        overrides[updated.id] = updated
        pushSceneToEngine()
    }

    fun commitTransform(updated: SourceEntity) {
        viewModelScope.launch {
            repository.updateSource(updated)
            overrides.remove(updated.id)
        }
    }

    // ---- Preview Studio & mode editor
    fun attachPreview(surface: Surface, w: Int, h: Int) {
        StreamingService.activeServiceInstance?.attachStudioPreview(surface, w, h)
    }
    fun detachPreview(surface: Surface) {
        StreamingService.activeServiceInstance?.detachStudioPreview(surface)
    }
    /** Video overlay: kembali ke awal. */
    fun restartVideo(source: SourceEntity) { StreamingService.activeServiceInstance?.restartVideo(source.id) }

    /** Video overlay: berhenti = jeda + kembali ke awal. */
    fun stopVideo(source: SourceEntity) {
        updateSource(source.copy(videoPaused = true))
        StreamingService.activeServiceInstance?.restartVideo(source.id)
    }

    fun setEditorVisible(visible: Boolean) {
        StreamingService.activeServiceInstance?.setEditorVisible(visible)
    }

    // Active preset
    val currentPreset = MutableStateFlow(autoDetection.selectedPreset)

    init {
        viewModelScope.launch {
            repository.initializeDefaultsIfNeeded()
            audioMixer.startMonitoring()
        }
        // Mic: seed sumber tunggal dari DB sekali (hanya jika proses baru, bukan saat service sudah live),
        // lalu simpan setiap perubahan dari mana pun (UI utama, floating window, notifikasi).
        viewModelScope.launch {
            val saved = repository.audioSettings.filterNotNull().first()
            if (StreamingService.activeServiceInstance == null) {
                StreamAudioParams.micMuted = saved.isMicMuted
                StreamAudioParams.micGain = saved.micVolume
                StreamAudioParams.systemVolume = saved.systemVolume
            }
            StreamAudioParams.micMutedFlow.collect { muted ->
                val cur = repository.audioSettings.firstOrNull() ?: AudioSettingsEntity()
                if (cur.isMicMuted != muted) repository.saveAudioSettings(cur.copy(isMicMuted = muted))
            }
        }
        audioDevices.start()
        StreamPrefs.applyAll(application)
        refreshCameras()
        StreamingService.contentAspectListener = { id, a -> onContentAspect(id, a) }
        StreamingService.nextSceneListener = { cycleScene() }
        try {
            (application.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
                .registerAvailabilityCallback(cameraAvailability, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {}
        try {
            val usbFilter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                application.registerReceiver(usbReceiver, usbFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                application.registerReceiver(usbReceiver, usbFilter)
            }
        } catch (_: Exception) {}

        // Canvas engine mengikuti scene aktif + preset (rasio, resolusi, fps). Tidak pernah dijalankan saat LIVE.
        viewModelScope.launch {
            combine(selectedScene.map { it?.canvas }.distinctUntilChanged(), currentPreset) { c, p -> c to p }
                .collect { (c, _) -> if (c != null) syncEngineCanvas() }
        }

        // Seluruh source scene -> compositor GL (posisi, ukuran, urutan z, konten). Hanya saat status
        // berubah atau source berubah; update statistik tiap detik TIDAK memicu apa pun di sini.
        viewModelScope.launch {
            combine(currentSources, liveState.map { it.status to it.cameraPreview }.distinctUntilChanged()) { srcs, st -> srcs to st.first }
                .collect { (srcs, st) ->
                    if (isEngineActive(st)) {
                        StreamingService.activeServiceInstance
                            ?.applyScene(srcs.map { overrides[it.id] ?: it })
                    }
                }
        }
    }

    // ---------------------------------------------------------------- Main Canvas

    /** true bila ada siaran yang sedang berjalan/tersambung: encoder tidak boleh berganti rasio/resolusi. */
    private fun isLiveNow(): Boolean = when (liveState.value.status) {
        StreamingStatus.LIVE, StreamingStatus.PAUSED, StreamingStatus.CONNECTING,
        StreamingStatus.RECONNECTING, StreamingStatus.PREPARING, StreamingStatus.STOPPING -> true
        else -> false
    }

    /** Ukuran frame yang benar-benar dikirim encoder untuk canvas scene ini dengan preset kualitas aktif. */
    fun encodeCanvasFor(scene: SceneEntity?, preset: StreamPreset = currentPreset.value): CanvasSpec {
        val long = max(preset.width, preset.height)
        val short = min(preset.width, preset.height)
        return (scene?.canvas ?: CanvasSpec.LANDSCAPE).encodeSize(long, short)
    }

    private var lastCanvasNoticeKey: String? = null

    /** Samakan canvas engine (GL + encoder) dengan scene aktif dan preset. Ditolak saat LIVE. */
    private fun syncEngineCanvas() {
        val scene = selectedScene.value ?: return
        val svc = StreamingService.activeServiceInstance ?: return
        val p = currentPreset.value
        val enc = encodeCanvasFor(scene, p)
        if (isLiveNow()) {
            val key = "${enc.width}x${enc.height}@${p.fps}"
            if (key != lastCanvasNoticeKey) {
                lastCanvasNoticeKey = key
                svc.engineCanvasSize()?.let { (w, h) ->
                    if (w != enc.width || h != enc.height) say("Siaran berjalan di ${w}x$h. Perubahan canvas/resolusi berlaku setelah siaran dihentikan.")
                }
            }
            return
        }
        svc.reconfigureCanvas(enc.width, enc.height, p.fps, p.defaultBitrateKbps) { say(it) }
    }

    /**
     * Ganti canvas scene aktif (Portrait 9:16, Landscape 16:9, Square 1:1, atau resolusi kustom).
     * Kotak setiap source dipetakan ulang di ruang piksel sehingga RASIO ASLI source tidak berubah
     * (tidak gepeng) dan posisinya tetap proporsional. Riwayat undo dikosongkan karena snapshot lama
     * milik canvas yang berbeda.
     */
    fun setCanvas(spec: CanvasSpec) {
        val scene = selectedScene.value ?: return
        val target = CanvasSpec.custom(spec.width, spec.height)
        val old = scene.canvas
        if (target == old) return
        if (isLiveNow()) { say("Canvas tidak bisa diubah saat LIVE. Hentikan siaran dulu."); return }
        viewModelScope.launch {
            db.withTransaction {
                repository.updateScene(
                    scene.copy(canvasWidth = target.width, canvasHeight = target.height, isPortrait = target.isPortrait)
                )
                for (src in repository.sourcesOnce(scene.id)) {
                    val b = CompositionMath.remapBox(NRect(src.x, src.y, src.width, src.height), old, target)
                    repository.updateSource(src.copy(x = b.x, y = b.y, width = b.w, height = b.h))
                }
            }
            clearHistory()
        }
    }

    fun setCustomCanvas(width: Int, height: Int) = setCanvas(CanvasSpec.custom(width, height))

    fun selectScene(scene: SceneEntity) {
        val cur = selectedScene.value
        if (cur != null && cur.id != scene.id && isLiveNow() && encodeCanvasFor(scene) != encodeCanvasFor(cur)) {
            say("Scene \"${scene.name}\" memakai canvas ${scene.canvas.label()}, sedangkan siaran berjalan di ${cur.canvas.label()}. Hentikan siaran dulu untuk pindah.")
            return
        }
        viewModelScope.launch {
            repository.selectScene(scene.id)
            _selectedSourceId.value = null
            clearHistory()
        }
    }

    /** Pindah ke scene berikutnya (urutan daftar, berputar). Dipakai tombol "Ganti Scene" di bubble melayang. */
    fun cycleScene() {
        val list = allScenes.value
        if (list.size < 2) { say("Hanya ada satu scene."); return }
        val cur = selectedScene.value
        val idx = list.indexOfFirst { it.id == cur?.id }
        selectScene(list[(idx + 1).mod(list.size)])
    }

    fun renameScene(scene: SceneEntity, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        viewModelScope.launch { repository.renameScene(scene, n) }
    }

    fun createScene(name: String, isPortrait: Boolean = false) {
        viewModelScope.launch {
            val scene = repository.createScene(name, isPortrait)
            repository.selectScene(scene.id)
        }
    }

    /** Scene baru dengan canvas pilihan (9:16, 16:9, 1:1, atau kustom). */
    fun createScene(name: String, canvas: CanvasSpec) {
        val cur = selectedScene.value
        val target = CanvasSpec.custom(canvas.width, canvas.height)
        if (cur != null && isLiveNow() && encodeCanvasFor(SceneEntity(name = name, canvasWidth = target.width, canvasHeight = target.height)) != encodeCanvasFor(cur)) {
            say("Siaran berjalan di canvas ${cur.canvas.label()}. Scene baru dengan canvas ${target.label()} bisa dibuat setelah siaran dihentikan.")
            return
        }
        viewModelScope.launch {
            val scene = repository.createScene(name, target.isPortrait)
            val withCanvas = scene.copy(canvasWidth = target.width, canvasHeight = target.height, isPortrait = target.isPortrait)
            repository.updateScene(withCanvas)
            repository.selectScene(scene.id)
        }
    }

    fun duplicateScene(scene: SceneEntity) {
        viewModelScope.launch {
            val duplicated = repository.duplicateScene(scene)
            repository.selectScene(duplicated.id)
        }
    }

    fun deleteScene(scene: SceneEntity) {
        if (allScenes.value.size <= 1) { say("Minimal harus ada satu scene."); return }
        viewModelScope.launch {
            repository.sourcesOnce(scene.id).forEach { cleanupMedia(it, excludingScene = scene.id) }
            repository.deleteScene(scene.id)
            val remaining = allScenes.value.filter { it.id != scene.id }
            remaining.firstOrNull()?.let { repository.selectScene(it.id) }
        }
    }

    /** Putar canvas: 16:9 <-> 9:16 (atau tukar sisi untuk resolusi kustom); 1:1 menjadi 9:16. */
    fun toggleCanvasOrientation() {
        val scene = selectedScene.value ?: return
        val c = scene.canvas
        setCanvas(if (c.isSquare) CanvasSpec.PORTRAIT else CanvasSpec(c.height, c.width))
    }

    fun selectSource(sourceId: String?) {
        _selectedSourceId.value = sourceId
    }

    private fun canvasPx(): Pair<Float, Float> {
        val sc = selectedScene.value
        return (sc?.canvasWidth ?: 1280).toFloat() to (sc?.canvasHeight ?: 720).toFloat()
    }

    private fun nextZ() = (currentSources.value.maxOfOrNull { it.zIndex } ?: 0) + 1

    fun addSource(name: String, type: SourceType, textContent: String? = null) {
        val scene = selectedScene.value ?: return
        val (w, h, x, y) = when (type) {
            SourceType.SCREEN -> listOf(1f, 1f, 0f, 0f)
            // Kamera di pojok kanan bawah; tinggi dikoreksi di bawah agar kotak berrasio 4:3 PIKSEL
            SourceType.CAMERA -> defaultCameraBox()
            SourceType.TEXT -> listOf(0.4f, 0.1f, 0.05f, 0.05f)
            SourceType.IMAGE -> listOf(0.3f, 0.3f, 0.35f, 0.35f)
            SourceType.WEB -> listOf(1f, 1f, 0f, 0f)
            SourceType.VIDEO -> listOf(0.5f, 0.5f, 0.25f, 0.25f)
            SourceType.CHAT -> defaultChatBox()
            SourceType.PNGTUBER -> defaultChatBox()   // tidak dipakai: PNGTuber dibuat lewat importPngTuber
        }
        val source = SourceEntity(
            sceneId = scene.id, name = name, type = type, zIndex = nextZ(),
            x = x, y = y, width = w, height = h,
            textContent = textContent ?: if (type == SourceType.TEXT) "STREAM TITLE" else null,
            aspectLocked = type == SourceType.CAMERA,
            // Kamera tampil apa adanya (tidak otomatis dicerminkan); pengguna bisa memakai tombol Mirror
            contentAspect = if (type == SourceType.CAMERA) initialCameraAspect() else 0f
        )
        insertSource(source)
        if (type == SourceType.CAMERA) _cameraRequested.value = true
    }

    /**
     * Perkiraan rasio konten kamera TEGAK sebelum kamera melapor nilai nyata: sensor 4:3 diputar 90°
     * pada HP portrait menjadi 3:4. Nilai nyata menggantikannya lewat [onContentAspect] begitu kamera terbuka.
     */
    private fun initialCameraAspect(): Float =
        if (getApplication<Application>().resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT) 3f / 4f else 4f / 3f

    /** Kotak overlay komentar awal: bawah-kiri; lebar mengikuti orientasi canvas. [w, h, x, y] */
    private fun defaultChatBox(): List<Float> {
        val (cw, ch) = canvasPx()
        return if (ch > cw) listOf(0.92f, 0.26f, 0.04f, 0.60f) else listOf(0.34f, 0.40f, 0.02f, 0.56f)
    }

    /**
     * Overlay komentar: bila scene belum punya, tambahkan source CHAT (memakai sistem layer yang ada);
     * bila sudah ada, tampilkan/sembunyikan. Tidak menyentuh siaran, encoder, atau koneksi chat.
     */
    fun toggleChatOverlay() {
        val existing = currentSources.value.firstOrNull { it.type == SourceType.CHAT }
        if (existing == null) addSource("Chat Overlay", SourceType.CHAT)
        else updateSource(existing.copy(isVisible = !existing.isVisible))
    }

    /**
     * Tambah avatar PNGTuber dari dua gambar PNG transparan: [idleUri] (diam) dan [talkUri] (bicara; null = pakai idle).
     * Gambar disalin ke penyimpanan internal. Menjadi source tersendiri: bisa dipindah, diubah ukuran, di-crop, dst.
     */
    fun importPngTuber(name: String, idleUri: Uri, talkUri: Uri?) {
        persistReadPermission(idleUri); talkUri?.let { persistReadPermission(it) }
        val scene = selectedScene.value ?: return
        val (cw, ch) = canvasPx()
        viewModelScope.launch {
            val idle = withContext(Dispatchers.IO) { copyImage(idleUri) }
            if (idle == null) { say("Gambar idle tidak bisa diimpor."); return@launch }
            val talk = talkUri?.let { withContext(Dispatchers.IO) { copyImage(it) } }
            if (talkUri != null && talk == null) say("Gambar bicara tidak bisa diimpor; memakai gambar idle.")
            val (path, iw, ih) = idle
            val aspect = iw.toFloat() / ih
            var w = if (cw >= ch) 0.22f else 0.42f
            var h = (w * cw) / (aspect * ch)
            if (h > 0.9f) { h = 0.9f; w = (aspect * ch * h) / cw }
            insertSource(
                SourceEntity(
                    sceneId = scene.id, name = name.ifBlank { "PNGTuber" }, type = SourceType.PNGTUBER, zIndex = nextZ(),
                    x = (1f - w) - 0.04f, y = (1f - h) - 0.04f, width = w, height = h,
                    contentUri = path, aspectLocked = true, contentAspect = aspect,
                    textContent = com.example.pngtuber.PngTuberConfig(talkUri = talk?.first.orEmpty()).encode()
                )
            )
        }
    }

    /** Ubah pengaturan PNGTuber (threshold/sensitivitas/smoothing/aktif). Tanpa rebuild layer. */
    fun updatePngTuberConfig(s: SourceEntity, cfg: com.example.pngtuber.PngTuberConfig) =
        updateSource(s.copy(textContent = cfg.sanitized().encode()))

    /** Ganti salah satu gambar PNGTuber; file lama dihapus bila tidak dipakai source lain. */
    fun replacePngTuberImage(s: SourceEntity, uri: Uri, talking: Boolean) {
        persistReadPermission(uri)
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) { copyImage(uri) }
            if (copied == null) { say("Gambar tidak bisa diimpor."); return@launch }
            val cfg = com.example.pngtuber.PngTuberConfig.decode(s.textContent)
            val old = s
            val updated = if (talking) s.copy(textContent = cfg.copy(talkUri = copied.first).encode())
            else s.copy(contentUri = copied.first, contentAspect = copied.second.toFloat() / copied.third)
            repository.updateSource(updated)
            cleanupMedia(old, excludingScene = "") // lama dibersihkan bila tak lagi dipakai
        }
    }

    /** Kotak kamera awal: rasio PIKSEL pada canvas apa pun, di pojok kanan bawah. */
    private fun defaultCameraBox(): List<Float> {
        val (cw, ch) = canvasPx()
        val wN = if (cw >= ch) 0.28f else 0.42f
        val hN = wN * cw / (initialCameraAspect() * ch)
        return listOf(wN, hN, 1f - wN - 0.03f, 1f - hN - 0.04f)
    }

    private fun insertSource(source: SourceEntity) {
        pushUndo()
        viewModelScope.launch {
            repository.addSource(source)
            _selectedSourceId.value = source.id
        }
    }

    /** Browser Source dari URL. */
    fun addWebUrl(name: String, url: String) {
        val scene = selectedScene.value ?: return
        val t = url.trim()
        if (t.isEmpty()) { say("URL kosong."); return }
        insertSource(
            SourceEntity(
                sceneId = scene.id, name = name.ifBlank { "Browser" }, type = SourceType.WEB, zIndex = nextZ(),
                x = 0f, y = 0f, width = 1f, height = 1f, contentUri = t, webMode = 0
            )
        )
    }

    /** Browser Source dari file HTML lokal (+ resource pendukung atau satu ZIP). */
    fun importLocalHtml(name: String, uris: List<Uri>) {
        val scene = selectedScene.value ?: return
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                LocalOverlayStore.import(getApplication(), id, uris) { err -> say(err) }
            } ?: return@launch
            insertSource(
                SourceEntity(
                    id = id, sceneId = scene.id, name = name.ifBlank { "HTML Lokal" }, type = SourceType.WEB,
                    zIndex = nextZ(), x = 0f, y = 0f, width = 1f, height = 1f, contentUri = path, webMode = 1
                )
            )
        }
    }

    /** Gambar overlay, atau screenshot referensi (asReference=true: hanya editor, tidak ke RTMP). */
    fun importImage(uri: Uri, asReference: Boolean) {
        persistReadPermission(uri)
        val scene = selectedScene.value ?: return
        val (cw, ch) = canvasPx()
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) { copyImage(uri) }
            if (copied == null) { say("Gambar tidak bisa diimpor."); return@launch }
            val (path, iw, ih) = copied
            val imgAspect = iw.toFloat() / ih
            val canvasAspect = cw / ch
            val src = if (asReference) {
                val (w, h) = if (imgAspect > canvasAspect) 1f to (cw / imgAspect) / ch else ((ch * imgAspect) / cw) to 1f
                SourceEntity(
                    sceneId = scene.id, name = "Referensi", type = SourceType.IMAGE,
                    zIndex = (currentSources.value.minOfOrNull { it.zIndex } ?: 0) - 1,
                    x = (1f - w) / 2f, y = (1f - h) / 2f, width = w, height = h,
                    opacity = 0.6f, contentUri = path, isReference = true, aspectLocked = true
                )
            } else {
                val w = 0.3f
                val h = (((w * cw) / imgAspect) / ch).coerceAtMost(1f)
                SourceEntity(
                    sceneId = scene.id, name = "Gambar", type = SourceType.IMAGE, zIndex = nextZ(),
                    x = 0.35f, y = 0.35f, width = w, height = h, contentUri = path, aspectLocked = true,
                    contentAspect = imgAspect
                )
            }
            insertSource(src)
        }
    }

    /**
     * Minta izin baca URI bertahan segera setelah dipilih. Tidak semua penyedia mendukungnya (URI photo picker
     * hanya memberi izin sementara dan melempar SecurityException), jadi kegagalan diabaikan: file toh disalin
     * ke penyimpanan aplikasi segera setelah ini, sehingga source tidak bergantung pada izin URI asli.
     */
    private fun persistReadPermission(uri: Uri) {
        try {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    /** Video lokal sebagai overlay (loop). Disalin ke penyimpanan app agar tetap ada setelah aplikasi dibuka ulang. */
    fun importVideo(uri: Uri, name: String) {
        persistReadPermission(uri)
        val scene = selectedScene.value ?: return
        val (cw, ch) = canvasPx()
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) { copyVideo(uri) }
            if (copied == null) { say("Video tidak bisa diimpor (format tidak didukung atau terlalu besar, maks 500 MB)."); return@launch }
            val (path, vw, vh) = copied
            val aspect = vw.toFloat() / vh
            var w = 0.5f
            var h = (w * cw) / (aspect * ch)
            if (h > 1f) { h = 1f; w = (aspect * ch) / cw }
            insertSource(
                SourceEntity(
                    sceneId = scene.id, name = name.ifBlank { "Video" }, type = SourceType.VIDEO, zIndex = nextZ(),
                    x = (1f - w) / 2f, y = (1f - h) / 2f, width = w, height = h,
                    contentUri = path, aspectLocked = true, contentAspect = aspect
                )
            )
        }
    }

    /** Video dari URL http(s) (stream langsung oleh MediaPlayer; tidak disalin). */
    fun addVideoUrl(name: String, url: String) {
        val scene = selectedScene.value ?: return
        val scheme = Uri.parse(url).scheme?.lowercase()
        if (scheme != "https" && scheme != "http") { say("URL video harus diawali https:// atau http://"); return }
        val (cw, ch) = canvasPx()
        val w = 0.5f
        val h = (w * cw) / ((16f / 9f) * ch)
        insertSource(
            SourceEntity(
                sceneId = scene.id, name = name.ifBlank { "Video URL" }, type = SourceType.VIDEO, zIndex = nextZ(),
                x = (1f - w) / 2f, y = (1f - h) / 2f, width = w, height = h,
                contentUri = url, aspectLocked = true, contentAspect = 0f
            )
        )
    }

    /**
     * Nama source di depan yang menutup SELURUH source terpilih (agar pengguna tahu mengapa source "hilang").
     * Konservatif: hanya source opak (layar/kamera/video) tanpa rotasi, crop, atau chroma key.
     */
    fun coveredBy(target: SourceEntity, all: List<SourceEntity>): String? {
        if (!target.isVisible || target.isReference) return null
        val (cw, ch) = canvasPx()
        val visible = visibleBox(target)
        val covers = all.filter { s ->
            !s.isReference && s.isVisible && s.id != target.id && s.zIndex > target.zIndex &&
                s.opacity >= 0.98f && s.rotation == 0f && !s.chromaKeyEnabled &&
                (s.type == SourceType.SCREEN || s.type == SourceType.CAMERA || s.type == SourceType.VIDEO) &&
                (s.fitMode != FitMode.CONTAIN || s.contentAspect <= 0f ||
                    kotlin.math.abs((s.width * cw) / (s.height * ch) / s.contentAspect - 1f) < 0.02f)
        }
        val hit = covers.firstOrNull { CompositionMath.isFullyCovered(visible, listOf(visibleBox(it))) }
        return hit?.name
    }

    private fun visibleBox(s: SourceEntity) = NRect(
        s.x + s.cropLeft * s.width, s.y + s.cropTop * s.height,
        s.width * (1f - s.cropLeft - s.cropRight), s.height * (1f - s.cropTop - s.cropBottom)
    )

    private fun copyVideo(uri: Uri): Triple<String, Int, Int>? = try {
        val dir = File(getApplication<Application>().filesDir, "media").apply { mkdirs() }
        val out = File(dir, UUID.randomUUID().toString() + ".vid")
        var total = 0L
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { ins ->
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 500L * 1024 * 1024) throw IllegalStateException("Video terlalu besar")
                    os.write(buf, 0, n)
                }
            }
        }
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(out.absolutePath)
            var w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            if (w <= 0 || h <= 0) { out.delete(); null } else Triple(out.absolutePath, w, h)
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    } catch (e: Exception) { null }

    private fun copyImage(uri: Uri): Triple<String, Int, Int>? = try {
        val dir = File(getApplication<Application>().filesDir, "media").apply { mkdirs() }
        val out = File(dir, UUID.randomUUID().toString() + ".img")
        var total = 0L
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { ins ->
            out.outputStream().use { os ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 30L * 1024 * 1024) throw IllegalStateException("Gambar terlalu besar (maks 30 MB)")
                    os.write(buf, 0, n)
                }
            }
        }
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.absolutePath, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) { out.delete(); null } else Triple(out.absolutePath, b.outWidth, b.outHeight)
    } catch (e: Exception) { null }

    /**
     * Hapus file media milik source hanya bila tidak ada source lain yang memakainya (mis. hasil duplikasi scene).
     * Dipanggil sebelum scene dihapus; baris milik scene itu sendiri tidak dihitung sebagai pemakai.
     */
    private suspend fun cleanupMedia(src: SourceEntity, excludingScene: String) {
        if (src.type == SourceType.PNGTUBER) {
            // Gambar "bicara" ada di config (textContent), bukan contentUri
            val talk = com.example.pngtuber.PngTuberConfig.decode(src.textContent).talkUri
            if (talk.isNotEmpty() && talk.startsWith(getApplication<Application>().filesDir.canonicalPath)) {
                val inScene = repository.sourcesOnce(excludingScene).count {
                    it.type == SourceType.PNGTUBER && com.example.pngtuber.PngTuberConfig.decode(it.textContent).talkUri == talk
                }
                if (repository.countPngTuberTalkUsing(talk) - inScene <= 0) {
                    withContext(Dispatchers.IO) { try { File(talk).delete() } catch (_: Exception) {} }
                }
            }
        }
        val path = src.contentUri ?: return
        if (!path.startsWith(getApplication<Application>().filesDir.canonicalPath)) return
        val inThisScene = repository.sourcesOnce(excludingScene).count { it.contentUri == path }
        if (repository.countUsing(path) - inThisScene > 0) return
        withContext(Dispatchers.IO) {
            try {
                if (src.type == SourceType.WEB && src.webMode == 1) File(path).parentFile?.deleteRecursively()
                else if (src.type == SourceType.IMAGE || src.type == SourceType.VIDEO || src.type == SourceType.PNGTUBER) File(path).delete()
            } catch (_: Exception) {}
        }
    }

    /** Ubah source mana pun (dipakai panel properti, tombol kunci/sembunyi, dsb). */
    fun updateSource(updated: SourceEntity, recordUndo: Boolean = true) {
        if (recordUndo) pushUndoCoalesced()
        viewModelScope.launch { repository.updateSource(updated) }
    }

    fun updateSelectedSource(transform: (SourceEntity) -> SourceEntity) {
        val current = selectedSource.value ?: return
        updateSource(transform(current))
    }

    fun toggleVisibility(src: SourceEntity) = updateSource(src.copy(isVisible = !src.isVisible))
    fun toggleLock(src: SourceEntity) = updateSource(src.copy(isLocked = !src.isLocked))

    fun deleteSource(source: SourceEntity) {
        pushUndo()
        viewModelScope.launch {
            repository.deleteSource(source.id)
            if (_selectedSourceId.value == source.id) _selectedSourceId.value = null
            // File media sengaja TIDAK dihapus di sini supaya Undo bisa memulihkan source; dibersihkan saat scene dihapus.
        }
    }

    fun duplicateSource(source: SourceEntity) {
        val scene = selectedScene.value ?: return
        insertSource(
            source.copy(
                id = UUID.randomUUID().toString(), sceneId = scene.id, name = "${source.name} (Copy)",
                x = source.x + 0.03f,
                y = source.y + 0.03f,
                zIndex = nextZ()
            )
        )
    }

    // ---------------------------------------------------------------- Layer / z-index
    // Daftar layer = source non-referensi, indeks 0 = paling bawah (background), terakhir = paling depan.
    // Setiap perubahan urutan menulis zIndex yang RAPAT (0..n-1) sehingga tidak ada zIndex kembar.

    private fun layersBottomToTop(): List<SourceEntity> =
        currentSources.value.filter { !it.isReference }.sortedWith(compareBy({ it.zIndex }, { it.id }))

    private fun applyLayerOrder(newOrder: List<SourceEntity>) {
        pushUndo()
        viewModelScope.launch {
            db.withTransaction {
                newOrder.forEachIndexed { i, src -> if (src.zIndex != i) repository.updateSource(src.copy(zIndex = i)) }
            }
        }
    }

    private fun moveLayerTo(source: SourceEntity, toIndex: Int) {
        val cur = layersBottomToTop()
        val item = cur.firstOrNull { it.id == source.id } ?: return
        val next = CompositionMath.moveInOrder(cur, item, toIndex)
        if (next.map { it.id } != cur.map { it.id } || cur.withIndex().any { (i, x) -> x.zIndex != i }) applyLayerOrder(next)
    }

    private fun layerIndex(source: SourceEntity) = layersBottomToTop().indexOfFirst { it.id == source.id }

    fun bringToFront(source: SourceEntity) = moveLayerTo(source, Int.MAX_VALUE)
    fun sendToBack(source: SourceEntity) = moveLayerTo(source, 0)
    fun bringForward(source: SourceEntity) { val i = layerIndex(source); if (i >= 0) moveLayerTo(source, i + 1) }
    fun sendBackward(source: SourceEntity) { val i = layerIndex(source); if (i >= 0) moveLayerTo(source, i - 1) }

    /** Dipakai tombol naik/turun lama: bringForward = true -> lebih depan. */
    fun reorderSource(source: SourceEntity, bringForward: Boolean) =
        if (bringForward) bringForward(source) else sendBackward(source)

    /**
     * Drag-and-drop di daftar layer. Daftar di UI ditampilkan paling depan di ATAS, jadi indeks UI
     * (0 = teratas) dikonversi ke indeks z (0 = terbawah).
     */
    fun moveLayerByDrag(fromUiIndex: Int, toUiIndex: Int) {
        val cur = layersBottomToTop()
        val n = cur.size
        if (fromUiIndex !in 0 until n || toUiIndex !in 0 until n || fromUiIndex == toUiIndex) return
        val item = cur[n - 1 - fromUiIndex]
        moveLayerTo(item, n - 1 - toUiIndex)
    }

    fun renameSource(source: SourceEntity, name: String) {
        val n = name.trim()
        if (n.isEmpty()) { say("Nama tidak boleh kosong."); return }
        updateSource(source.copy(name = n))
    }

    // ---- Transformasi layer (Properties Panel). Semua rumus di sini bekerja di ruang canvas piksel
    // supaya rasio tidak pernah terdistorsi, dan memakai kotak layer PENUH (x,y,width,height) + crop.

    private fun visW(s: SourceEntity) = s.width * (1f - s.cropLeft - s.cropRight).coerceAtLeast(0.01f)
    private fun visH(s: SourceEntity) = s.height * (1f - s.cropTop - s.cropBottom).coerceAtLeast(0.01f)

    /** Persen skala: 100% = area terlihat persis muat di dalam canvas (contain). */
    fun scalePercent(s: SourceEntity): Float {
        val (cw, ch) = canvasPx()
        val aspect = (visW(s) * cw) / (visH(s) * ch)
        val containW = if (aspect >= cw / ch) 1f else aspect * ch / cw
        return visW(s) / containW * 100f
    }

    /** Skala proporsional di sekitar pusat layer; lebar dan tinggi berubah bersama, tidak ada distorsi. */
    fun setScalePercent(s: SourceEntity, percent: Float) {
        val (cw, ch) = canvasPx()
        val aspect = (visW(s) * cw) / (visH(s) * ch)
        val containW = if (aspect >= cw / ch) 1f else aspect * ch / cw
        val newVisW = containW * percent.coerceIn(5f, 400f) / 100f
        val k = newVisW / visW(s)
        val cx = s.x + s.width / 2f
        val cy = s.y + s.height / 2f
        val nw = s.width * k
        val nh = s.height * k
        updateSource(s.copy(x = cx - nw / 2f, y = cy - nh / 2f, width = nw, height = nh))
    }

    // ---------------------------------------------------------------- Viewport source (fit / zoom / pan)

    /** GL layer yang mendukung mode fit. Teks selalu seukuran kotaknya; Browser Source tidak mendukung fit. */
    fun supportsFit(s: SourceEntity) =
        s.type == SourceType.SCREEN || s.type == SourceType.CAMERA || s.type == SourceType.IMAGE || s.type == SourceType.VIDEO

    /** Rasio asli konten; layar game memakai rasio capture nyata. Bila tidak ada, rasio kotak sekarang. */
    private fun effectiveAspect(s: SourceEntity): Float {
        val a = if (s.type == SourceType.SCREEN) gameAspect(s) else s.contentAspect
        if (a > 0f && supportsFit(s)) return a
        val (cw, ch) = canvasPx()
        return (s.width * cw) / (s.height * ch)
    }

    private fun gameAspect(source: SourceEntity): Float =
        liveState.value.captureAspect.takeIf { it > 0f } ?: source.contentAspect

    private fun canvasSpec(): CanvasSpec = selectedScene.value?.canvas ?: CanvasSpec.LANDSCAPE

    private fun placeBox(s: SourceEntity, b: NRect, mode: Int? = null, resetView: Boolean) {
        updateSource(
            s.copy(
                x = b.x, y = b.y, width = b.w, height = b.h, rotation = 0f,
                fitMode = if (mode != null && supportsFit(s)) mode else s.fitMode,
                zoom = if (resetView) 1f else s.zoom,
                panX = if (resetView) 0f else s.panX,
                panY = if (resetView) 0f else s.panY,
                cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f
            )
        )
    }

    /** Mode fit milik SATU source: Utuh / Penuhi / Regangkan. Kotak (viewport) tidak berubah. */
    fun setFitMode(s: SourceEntity, mode: Int) {
        if (!supportsFit(s)) { say("Mode fit tidak tersedia untuk jenis source ini."); return }
        updateSource(s.copy(fitMode = FitMode.sanitize(mode)))
    }

    /** Zoom proporsional di sekitar pusat viewport; pan ikut dibatasi agar konten tidak keluar viewport. */
    fun setZoom(s: SourceEntity, zoom: Float) {
        val z = zoom.coerceIn(CompositionMath.MIN_ZOOM, CompositionMath.MAX_ZOOM)
        val lim = CompositionMath.panLimit(z)
        updateSource(s.copy(zoom = z, panX = s.panX.coerceIn(-lim, lim), panY = s.panY.coerceIn(-lim, lim)))
    }

    fun setPan(s: SourceEntity, x: Float? = null, y: Float? = null) {
        val lim = CompositionMath.panLimit(s.zoom)
        updateSource(s.copy(panX = (x ?: s.panX).coerceIn(-lim, lim), panY = (y ?: s.panY).coerceIn(-lim, lim)))
    }

    /** Fit to Canvas: seluruh konten terlihat, rasio asli, di tengah canvas. */
    fun fitToCanvas(s: SourceEntity) =
        placeBox(s, CompositionMath.fitBoxToCanvas(effectiveAspect(s), canvasSpec(), fill = false), FitMode.CONTAIN, true)

    /** Fill Canvas: viewport = seluruh canvas, konten memenuhi dengan rasio asli (kelebihan terpotong). */
    fun fillCanvas(s: SourceEntity) =
        if (supportsFit(s)) placeBox(s, NRect(0f, 0f, 1f, 1f), FitMode.COVER, true)
        else placeBox(s, CompositionMath.fitBoxToCanvas(effectiveAspect(s), canvasSpec(), fill = true), null, true)

    /** Lebar penuh canvas (mis. gameplay/video 16:9 di canvas portrait), tinggi mengikuti rasio asli. */
    fun fullWidth(s: SourceEntity) =
        placeBox(s, CompositionMath.fullWidthBox(effectiveAspect(s), canvasSpec()), FitMode.CONTAIN, true)

    /** Tinggi penuh canvas (mis. konten 9:16 di canvas landscape), lebar mengikuti rasio asli. */
    fun fullHeight(s: SourceEntity) =
        placeBox(s, CompositionMath.fullHeightBox(effectiveAspect(s), canvasSpec()), FitMode.CONTAIN, true)

    /** Reset Crop: hanya crop yang dihapus; posisi, ukuran, zoom tetap. */
    fun resetCrop(s: SourceEntity) =
        updateSource(s.copy(cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f))

    /** Reset Transform: Fit di tengah, rotasi 0, zoom 1, pan 0, tanpa crop. Mirror (flip) dipertahankan. */
    fun resetTransform(s: SourceEntity) =
        placeBox(s, CompositionMath.fitBoxToCanvas(effectiveAspect(s), canvasSpec(), fill = false), FitMode.CONTAIN, true)

    // Nama lama (dipakai Properties Panel yang sudah ada) diarahkan ke sistem baru
    fun fitToScreen(s: SourceEntity) = fitToCanvas(s)
    fun fillToScreen(s: SourceEntity) = fillCanvas(s)
    fun fitGame(source: SourceEntity) = fitToCanvas(source)
    fun fillGame(source: SourceEntity) = fillCanvas(source)

    fun centerSource(s: SourceEntity) {
        updateSource(
            s.copy(
                x = 0.5f - visW(s) / 2f - s.cropLeft * s.width,
                y = 0.5f - visH(s) / 2f - s.cropTop * s.height
            )
        )
    }

    /** Hapus crop dan kembalikan rasio asli konten (bila diketahui), pusat layer tetap. */
    fun resetCropAndRatio(s: SourceEntity) {
        // effectiveAspect: rasio capture nyata untuk game, rasio kamera setelah koreksi orientasi, dst.
        val b = CompositionMath.reshapeBoxForAspect(NRect(s.x, s.y, s.width, s.height), effectiveAspect(s), canvasSpec())
        updateSource(
            s.copy(
                x = b.x, y = b.y, width = b.w, height = b.h,
                cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f
            )
        )
    }

    fun toggleFlipH(s: SourceEntity) = updateSource(s.copy(flipH = !s.flipH))
    fun toggleFlipV(s: SourceEntity) = updateSource(s.copy(flipV = !s.flipV))
    fun rotate90(s: SourceEntity) = updateSource(s.copy(rotation = ((s.rotation + 90f) % 360f + 360f) % 360f))
    fun setRotation(s: SourceEntity, deg: Float) = updateSource(s.copy(rotation = ((deg % 360f) + 360f) % 360f))

    /** Crop per sisi dalam pecahan 0..0.95. Dua sisi berlawanan tidak boleh menutup seluruh konten. */
    fun setCrop(s: SourceEntity, l: Float? = null, t: Float? = null, r: Float? = null, b: Float? = null) {
        var nl = (l ?: s.cropLeft).coerceIn(0f, 0.95f)
        var nt = (t ?: s.cropTop).coerceIn(0f, 0.95f)
        var nr = (r ?: s.cropRight).coerceIn(0f, 0.95f)
        var nb = (b ?: s.cropBottom).coerceIn(0f, 0.95f)
        if (nl + nr > 0.95f) { if (l != null) nl = 0.95f - nr else nr = 0.95f - nl }
        if (nt + nb > 0.95f) { if (t != null) nt = 0.95f - nb else nb = 0.95f - nt }
        updateSource(s.copy(cropLeft = nl, cropTop = nt, cropRight = nr, cropBottom = nb))
    }

    /** Rasio asli konten dilaporkan engine (kamera, setelah koreksi orientasi). */
    private fun onContentAspect(id: String, aspect: Float) {
        val s = currentSources.value.firstOrNull { it.id == id } ?: return
        if (abs(s.contentAspect - aspect) < 0.01f) return
        val (cw, ch) = canvasPx()
        val currentPx = (s.width * cw) / (s.height * ch)
        // Sesuaikan tinggi hanya bila pengguna belum mendistorsi layer (rasio sekarang = rasio lama)
        val untouched = s.contentAspect <= 0f || abs(currentPx - s.contentAspect) / s.contentAspect < 0.03f
        var u = s.copy(contentAspect = aspect)
        if (untouched) {
            // Rasio piksel mengikuti konten nyata, tetap di dalam canvas (kamera 4:3 -> 3:4 saat diputar sensor)
            val b = CompositionMath.reshapeBoxForAspect(NRect(s.x, s.y, s.width, s.height), aspect, canvasSpec())
            u = u.copy(x = b.x, y = b.y, width = b.w, height = b.h)
        }
        viewModelScope.launch { repository.updateSource(u) }
    }

    // ---- Kualitas kamera (disimpan; berlaku tanpa mengubah transformasi layer)
    private val cameraPrefs = application.getSharedPreferences("vl_camera", Context.MODE_PRIVATE)
    private val _cameraMaxPixels = MutableStateFlow(cameraPrefs.getInt("maxPixels", 1280 * 720))
    private val _cameraFps = MutableStateFlow(cameraPrefs.getInt("fps", 30))
    val cameraMaxPixels: StateFlow<Int> = _cameraMaxPixels.asStateFlow()
    val cameraFps: StateFlow<Int> = _cameraFps.asStateFlow()

    fun setCameraQuality(maxPixels: Int, fps: Int) {
        _cameraMaxPixels.value = maxPixels
        _cameraFps.value = fps
        cameraPrefs.edit().putInt("maxPixels", maxPixels).putInt("fps", fps).apply()
        if (liveState.value.cameraPreview != CameraPreviewState.OFF) {
            getApplication<Application>().startService(
                Intent(getApplication(), StreamingService::class.java).apply {
                    action = StreamingService.ACTION_CAMERA_SETTINGS
                    putExtra(StreamingService.EXTRA_CAMERA_MAX_PIXELS, maxPixels)
                    putExtra(StreamingService.EXTRA_FPS, fps)
                }
            )
        }
    }

    // ---- Pengaturan siaran tambahan: bitrate adaptif dan efek mikrofon (default mati)
    private val _streamExtras = MutableStateFlow(StreamPrefs.read(application))
    val streamExtras: StateFlow<StreamPrefs.Snapshot> = _streamExtras.asStateFlow()
    /** Efek mikrofon yang disediakan perangkat ini; toggle yang tidak didukung dinonaktifkan di UI. */
    val micEffectSupport = MicEffects.support()

    fun setAdaptiveBitrate(enabled: Boolean) {
        StreamPrefs.setAdaptiveBitrate(getApplication(), enabled)
        _streamExtras.value = _streamExtras.value.copy(adaptiveBitrate = enabled)
    }

    fun setMicEffects(noiseSuppression: Boolean, echoCancel: Boolean, autoGain: Boolean) {
        StreamPrefs.setMicEffects(getApplication(), noiseSuppression, echoCancel, autoGain)
        _streamExtras.value = _streamExtras.value.copy(
            noiseSuppression = noiseSuppression, echoCancel = echoCancel, autoGain = autoGain
        )
    }

    /** Pengguna menambah/memakai sumber kamera: Activity memintakan izin kamera secara kontekstual. */
    private val _cameraRequested = MutableStateFlow(false)
    val cameraRequested: StateFlow<Boolean> = _cameraRequested.asStateFlow()
    fun consumeCameraRequest() { _cameraRequested.value = false }

    // ---- Kamera
    fun switchCamera() {
        val cam = currentSources.value.firstOrNull { it.type == SourceType.CAMERA && it.isVisible }
            ?: currentSources.value.firstOrNull { it.type == SourceType.CAMERA } ?: return
        val list = _cameras.value
        if (list.isEmpty()) { say("Tidak ada kamera terdeteksi."); return }
        val currentId = cam.cameraId ?: CameraLayerSource.pickDefaultId(getApplication())
        val idx = list.indexOfFirst { it.id == currentId }
        val next = list[(idx + 1).mod(list.size)]
        updateSource(cam.copy(cameraId = next.id))
        say("Kamera: ${next.label}")
    }

    fun setCameraId(source: SourceEntity, id: String?) = updateSource(source.copy(cameraId = id))

    /** true bila scene punya screenshot referensi. */
    fun hasReference(): Boolean = currentSources.value.any { it.isReference }

    /**
     * Align Game to Reference: layer game mengambil kotak referensi (posisi, ukuran, rotasi). Bila rasio
     * kotak berbeda dari rasio game, game dimuat (contain) di tengah kotak itu agar tidak distorsi.
     * Screenshot referensi sendiri tidak berubah dan tetap hanya tampil di editor.
     */
    fun alignGameToReference(game: SourceEntity) {
        val ref = currentSources.value.firstOrNull { it.isReference }
        if (ref == null) { say("Belum ada screenshot referensi. Impor dulu lewat Screenshot Referensi."); return }
        val (cw, ch) = canvasPx()
        val bx = ref.x + ref.cropLeft * ref.width
        val by = ref.y + ref.cropTop * ref.height
        val bw = visW(ref)
        val bh = visH(ref)
        val boxAspect = (bw * cw) / (bh * ch)
        val aspect = gameAspect(game)
        var w = bw; var h = bh; var x = bx; var y = by
        if (aspect > 0f && abs(boxAspect - aspect) / aspect > 0.02f) {
            if (aspect > boxAspect) { h = bw * cw / (aspect * ch) } else { w = bh * ch * aspect / cw }
            x = bx + (bw - w) / 2f
            y = by + (bh - h) / 2f
        }
        updateSource(
            game.copy(
                x = x, y = y, width = w, height = h, rotation = ref.rotation,
                cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f
            )
        )
        say("Game diselaraskan ke screenshot referensi.")
    }

    fun clearGameSnapshot() { resumePreview() }

    fun selectPreset(preset: StreamPreset) {
        currentPreset.value = preset
        val profile = selectedProfile.value
        if (profile != null) {
            viewModelScope.launch {
                repository.updateProfile(
                    profile.copy(
                        width = preset.width,
                        height = preset.height,
                        fps = preset.fps,
                        bitrateKbps = preset.defaultBitrateKbps,
                        isAutoMode = false
                    )
                )
            }
        }
    }

    fun setAutoPresetMode() {
        currentPreset.value = autoDetection.selectedPreset
        val profile = selectedProfile.value
        if (profile != null) {
            viewModelScope.launch {
                repository.updateProfile(
                    profile.copy(
                        width = autoDetection.selectedPreset.width,
                        height = autoDetection.selectedPreset.height,
                        fps = autoDetection.selectedPreset.fps,
                        bitrateKbps = autoDetection.selectedPreset.defaultBitrateKbps,
                        isAutoMode = true
                    )
                )
            }
        }
    }

    fun saveProfile(profile: StreamProfileEntity, plainStreamKey: String) {
        val encryptedKey = StreamKeyStorage.encrypt(plainStreamKey)
        viewModelScope.launch {
            repository.saveProfile(profile.copy(encryptedStreamKey = encryptedKey))
        }
    }

    fun selectProfile(profileId: String) {
        viewModelScope.launch {
            repository.selectProfile(profileId)
        }
    }

    fun resumePreview() {
        StreamingService.activeServiceInstance?.resumePreview()
    }

    /** Toggle dari UI utama. Hanya menulis ke sumber tunggal; persistensi DB dilakukan oleh collector di init. */
    fun toggleMicMute() {
        StreamAudioParams.micMuted = !StreamAudioParams.micMuted
    }

    fun updateMicVolume(volume: Float) {
        audioMixer.micGain = volume
        StreamAudioParams.micGain = volume
        val settings = audioSettings.value ?: AudioSettingsEntity()
        viewModelScope.launch {
            repository.saveAudioSettings(settings.copy(micVolume = volume))
        }
    }

    fun updateSystemVolume(volume: Float) {
        audioMixer.systemVolume = volume
        StreamAudioParams.systemVolume = volume
        val settings = audioSettings.value ?: AudioSettingsEntity()
        viewModelScope.launch {
            repository.saveAudioSettings(settings.copy(systemVolume = volume))
        }
    }

    fun getDecryptedKey(encrypted: String): String {
        return StreamKeyStorage.decrypt(encrypted)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            (getApplication<Application>().getSystemService(Context.CAMERA_SERVICE) as CameraManager)
                .unregisterAvailabilityCallback(cameraAvailability)
        } catch (_: Exception) {}
        try { getApplication<Application>().unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        StreamingService.contentAspectListener = null
        StreamingService.nextSceneListener = null
        audioDevices.stop()
        audioMixer.stopMonitoring()
    }
}
