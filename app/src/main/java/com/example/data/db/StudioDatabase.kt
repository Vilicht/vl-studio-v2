package com.example.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.AudioSettingsDao
import com.example.data.dao.SceneDao
import com.example.data.dao.SourceDao
import com.example.data.dao.StreamProfileDao
import com.example.data.model.AudioSettingsEntity
import com.example.data.model.SceneEntity
import com.example.data.model.SourceEntity
import com.example.data.model.SourceType
import com.example.data.model.StreamProfileEntity

class Converters {
    @TypeConverter
    fun fromSourceType(value: SourceType): String = value.name

    @TypeConverter
    fun toSourceType(value: String): SourceType = runCatching {
        SourceType.valueOf(value)
    }.getOrDefault(SourceType.SCREEN)
}

/**
 * v1 -> v2: kolom baru untuk kamera pilihan, mode Browser Source, kunci aspek, dan flag referensi.
 * Hanya ALTER TABLE ADD COLUMN: semua Scene, Source, profil, dan stream key lama tetap utuh.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sources ADD COLUMN cameraId TEXT")
        db.execSQL("ALTER TABLE sources ADD COLUMN webMode INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sources ADD COLUMN aspectLocked INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sources ADD COLUMN isReference INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v2 -> v3: crop per sisi dan rasio asli konten. Hanya ADD COLUMN dengan default 0,
 * sehingga semua scene/source/profil lama tetap utuh dan tampil persis seperti sebelumnya.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sources ADD COLUMN cropLeft REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sources ADD COLUMN cropTop REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sources ADD COLUMN cropRight REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sources ADD COLUMN cropBottom REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sources ADD COLUMN contentAspect REAL NOT NULL DEFAULT 0")
    }
}

/**
 * v3 -> v4: viewport source independen (mode fit, zoom, pan). fitMode lama diisi 2 (Stretch) agar
 * semua source yang sudah ada tampil persis sama; zoom 1 dan pan 0 = tidak ada perubahan.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sources ADD COLUMN fitMode INTEGER NOT NULL DEFAULT 2")
        db.execSQL("ALTER TABLE sources ADD COLUMN zoom REAL NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE sources ADD COLUMN panX REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sources ADD COLUMN panY REAL NOT NULL DEFAULT 0")
    }
}

/**
 * v4 -> v5: source GL lama (layar, kamera, gambar) yang dimigrasi ke Stretch (2) pada v4 dikembalikan ke
 * Utuh/Contain (0). Stretch di kotak yang rasionya sudah terdistorsi (mis. akibat ganti orientasi canvas
 * pada versi lama) itulah yang membuat kamera tampak gepeng; Utuh menampilkan rasio asli konten.
 * Pengguna tetap bisa memilih Regangkan per source.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE sources SET fitMode = 0 WHERE type IN ('SCREEN', 'CAMERA', 'IMAGE')")
    }
}

/** v5 -> v6: spill reduction chroma key dan status jeda video. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sources ADD COLUMN chromaSpill REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sources ADD COLUMN videoPaused INTEGER NOT NULL DEFAULT 0")
    }
}

/** v6 -> v7: opsi suara video. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sources ADD COLUMN videoSound INTEGER NOT NULL DEFAULT 0")
    }
}

/** v7 -> v8: garis tepi teks, rotasi kamera manual, dan teks lama dibuat polos (tanpa background hitam bawaan). */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sources ADD COLUMN textStrokeColor INTEGER NOT NULL DEFAULT 4278190080")
        db.execSQL("ALTER TABLE sources ADD COLUMN textStrokeWidth REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sources ADD COLUMN cameraRotation INTEGER NOT NULL DEFAULT 0")
        // 0x88000000 (hitam transparan) adalah nilai bawaan lama, bukan pilihan pengguna: jadikan polos
        db.execSQL("UPDATE sources SET textBgColor = 0 WHERE type = 'TEXT' AND textBgColor = 2281701376")
    }
}

@Database(
    entities = [
        SceneEntity::class,
        SourceEntity::class,
        StreamProfileEntity::class,
        AudioSettingsEntity::class
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class StudioDatabase : RoomDatabase() {
    abstract fun sceneDao(): SceneDao
    abstract fun sourceDao(): SourceDao
    abstract fun streamProfileDao(): StreamProfileDao
    abstract fun audioSettingsDao(): AudioSettingsDao

    companion object {
        @Volatile
        private var INSTANCE: StudioDatabase? = null

        fun getInstance(context: Context): StudioDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    StudioDatabase::class.java,
                    "vilicht_studio.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
