package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

enum class SourceType {
    SCREEN,
    CAMERA,
    IMAGE,
    TEXT,
    WEB,
    /** Video file lokal (loop), tanpa audio ke siaran. Mendukung crop dan chroma key. */
    VIDEO,
    /** Overlay komentar LIVE: dirender ke bitmap dari ChatHub; memakai sistem layer yang sama dengan Teks/Gambar. */
    CHAT,
    /** Avatar PNGTuber: gambar idle (contentUri) + bicara (config di textContent), bereaksi pada level mic. */
    PNGTUBER
}

@Entity(tableName = "scenes")
data class SceneEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val isSelected: Boolean = false,
    val orderIndex: Int = 0,
    val canvasWidth: Int = 1280,
    val canvasHeight: Int = 720,
    val isPortrait: Boolean = false
) {
    /** Canvas scene sebagai objek murni. Rasio canvas independen dari semua source. */
    @get:androidx.room.Ignore
    val canvas: com.example.composition.CanvasSpec
        get() = com.example.composition.CanvasSpec.custom(canvasWidth, canvasHeight)
}

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sceneId: String,
    val name: String,
    val type: SourceType,
    val isVisible: Boolean = true,
    val isLocked: Boolean = false,
    val zIndex: Int = 0,
    // Normalized 0f..1f relative to canvas
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val contentUri: String? = null,
    val textContent: String? = null,
    val textColor: Long = 0xFFF2F0E9L,
    /** Background teks (ARGB). 0 = tanpa background (polos), ini bawaan. */
    val textBgColor: Long = 0x00000000L,
    val chromaKeyEnabled: Boolean = false,
    val chromaKeyColor: Long = 0xFF00FF00L, // Green default
    val chromaSimilarity: Float = 0.4f,
    val chromaSmoothness: Float = 0.1f,
    // --- Ditambahkan di DB versi 2 (lihat MIGRATION_1_2). Semua punya default agar data lama aman. ---
    /** ID Camera2 yang dipilih untuk source CAMERA. null = otomatis (kamera depan). */
    val cameraId: String? = null,
    /** Hanya untuk WEB: 0 = URL, 1 = file HTML lokal (path di contentUri). */
    @ColumnInfo(defaultValue = "0") val webMode: Int = 0,
    /** Kunci rasio aspek saat resize. */
    @ColumnInfo(defaultValue = "0") val aspectLocked: Boolean = false,
    /** Hanya untuk IMAGE: true = screenshot referensi editor, TIDAK ikut ke output RTMP. */
    @ColumnInfo(defaultValue = "0") val isReference: Boolean = false,
    // --- Ditambahkan di DB versi 3 (lihat MIGRATION_2_3). ---
    // Crop = pecahan 0..1 dari sisi konten (BUKAN dari canvas) yang dibuang. x/y/width/height tetap
    // menyimpan kotak layer PENUH (sebelum crop); area yang terlihat = kotak itu dikurangi crop.
    @ColumnInfo(defaultValue = "0") val cropLeft: Float = 0f,
    @ColumnInfo(defaultValue = "0") val cropTop: Float = 0f,
    @ColumnInfo(defaultValue = "0") val cropRight: Float = 0f,
    @ColumnInfo(defaultValue = "0") val cropBottom: Float = 0f,
    /** Rasio asli konten (lebar/tinggi, piksel). 0 = belum diketahui. Dipakai "Reset Crop & Ratio" dan "Fit". */
    @ColumnInfo(defaultValue = "0") val contentAspect: Float = 0f,
    // --- Ditambahkan di DB versi 4 (lihat MIGRATION_3_4): viewport source independen. ---
    /**
     * Cara konten mengisi kotak (viewport) source: 0 = UTUH/Contain, 1 = PENUHI/Cover, 2 = REGANGKAN/Stretch.
     * Default kolom di database = 2 supaya source lama tampil persis seperti sebelumnya; source baru
     * dibuat dengan 0 (Contain) di ViewModel. Lihat com.example.composition.FitMode.
     */
    @ColumnInfo(defaultValue = "2") val fitMode: Int = 0,
    /** Zoom konten di dalam viewport (1 = tanpa zoom). */
    @ColumnInfo(defaultValue = "1") val zoom: Float = 1f,
    /** Geser konten di dalam viewport, pecahan viewport (-0.5..0.5). */
    @ColumnInfo(defaultValue = "0") val panX: Float = 0f,
    @ColumnInfo(defaultValue = "0") val panY: Float = 0f,
    // --- Ditambahkan di DB versi 6 (MIGRATION_5_6) ---
    /** Pengurangan spill warna kunci pada tepi (0..1). */
    @ColumnInfo(defaultValue = "0") val chromaSpill: Float = 0.3f,
    /** Video dijeda (true) atau diputar (false). */
    @ColumnInfo(defaultValue = "0") val videoPaused: Boolean = false,
    // --- DB versi 7 (MIGRATION_6_7) ---
    /** Suara video ikut siaran (eksperimental): lewat capture audio internal, hanya bila izin capture layar aktif. */
    @ColumnInfo(defaultValue = "0") val videoSound: Boolean = false,
    // --- DB versi 8 (MIGRATION_7_8) ---
    /** Warna garis tepi teks (ARGB). Hanya terlihat bila [textStrokeWidth] > 0. */
    @ColumnInfo(defaultValue = "4278190080") val textStrokeColor: Long = 0xFF000000L,
    /** Tebal garis tepi teks sebagai pecahan ukuran font (0 = tanpa garis tepi). */
    @ColumnInfo(defaultValue = "0") val textStrokeWidth: Float = 0f,
    /** Hanya untuk CAMERA: putaran tambahan isi kamera dalam kelipatan 90° searah jarum jam (0..3), koreksi manual. */
    @ColumnInfo(defaultValue = "0") val cameraRotation: Int = 0
)

@Entity(tableName = "stream_profiles")
data class StreamProfileEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val serverUrl: String,
    val encryptedStreamKey: String = "",
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateKbps: Int = 3000,
    val audioBitrateKbps: Int = 128,
    val isAutoMode: Boolean = true,
    val isSelected: Boolean = false
)

@Entity(tableName = "audio_settings")
data class AudioSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val micVolume: Float = 1.0f,
    val isMicMuted: Boolean = false,
    val systemVolume: Float = 0.8f,
    val isSystemMuted: Boolean = false
)
