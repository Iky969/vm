package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.emulator.DeviceTreeBuilder
import com.example.emulator.DiagnosticMonitorRom
import kotlinx.coroutines.flow.Flow

@Dao
interface VmProfileDao {
    @Query("SELECT * FROM vm_profiles ORDER BY lastBootedAt DESC, id ASC")
    fun observeAllProfiles(): Flow<List<VmProfile>>

    @Query("SELECT * FROM vm_profiles WHERE id = :id")
    suspend fun getById(id: Long): VmProfile?

    @Query("SELECT COUNT(*) FROM vm_profiles")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: VmProfile): Long

    @Update
    suspend fun update(profile: VmProfile)

    @Query("UPDATE vm_profiles SET lastBootedAt = :timestamp WHERE id = :id")
    suspend fun updateLastBootedAt(id: Long, timestamp: Long)

    @Query("DELETE FROM vm_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface LocalImageDao {
    @Query("SELECT * FROM local_images ORDER BY createdAt DESC, id ASC")
    fun observeAllImages(): Flow<List<LocalImage>>

    @Query("SELECT * FROM local_images WHERE id = :id")
    suspend fun getById(id: Long): LocalImage?

    @Query("SELECT COUNT(*) FROM local_images")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: LocalImage): Long

    @Query("DELETE FROM local_images WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Database(
    entities = [VmProfile::class, LocalImage::class],
    version = 4,
    exportSchema = false
)
abstract class KernoDatabase : RoomDatabase() {
    abstract fun vmProfileDao(): VmProfileDao
    abstract fun localImageDao(): LocalImageDao

    suspend fun seedDefaultsIfNeeded() {
        val imgDao = localImageDao()
        val existingRom = imgDao.getById(DiagnosticMonitorRom.BUILTIN_IMAGE_ID)
        if (existingRom == null) {
            imgDao.insert(
                LocalImage(
                    id = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                    name = "KernoVM RV32IMA Diagnostic ROM",
                    kind = ImageKind.BUILTIN_ROM.name,
                    sourceType = ImageSourceType.BUILTIN.name,
                    localPath = "",
                    sizeBytes = DiagnosticMonitorRom.binary.size.toLong(),
                    sha256 = DiagnosticMonitorRom.sha256,
                    sourceUrl = "builtin://diagnostic-rom",
                    architectureTag = LocalImage.ARCH_RV32IMA_MMODE_NOMMU
                )
            )
        }

        val vmDao = vmProfileDao()
        if (vmDao.count() == 0) {
            vmDao.insert(
                VmProfile(
                    name = "RV64 Diagnostik",
                    description = "Profil target RISC-V 64-bit (RV64) dengan ROM diagnostik bawaan siap boot offline (UART 8250, CLINT, SYSCON).",
                    ramSizeMb = 16,
                    cpuQuotaPercent = 100,
                    cpuThreadPriority = CpuThreadPriority.NORMAL.name,
                    storageQuotaMb = 64,
                    kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                    customDtbImageId = null,
                    attachedIsoImageId = null,
                    targetDiskImageId = null,
                    primaryBootSlot = VmProfile.BOOT_SLOT_ISO,
                    bootArgs = DeviceTreeBuilder.DEFAULT_BOOTARGS,
                    clockMhz = 1,
                    autoPauseInBackground = false,
                    architecture = VmProfile.ARCH_RISCV64,
                    engineType = VmProfile.ENGINE_KERNO_KOTLIN
                )
            )
            vmDao.insert(
                VmProfile(
                    name = "Alpine RV64 Workstation",
                    description = "Profil RISC-V 64-bit (RV64) 64 MB dengan slot installer ISO (/dev/vda RO) dan Virtual Disk Target (/dev/vdb RW).",
                    ramSizeMb = 64,
                    cpuQuotaPercent = 85,
                    cpuThreadPriority = CpuThreadPriority.NORMAL.name,
                    storageQuotaMb = 256,
                    kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                    customDtbImageId = null,
                    attachedIsoImageId = null,
                    targetDiskImageId = null,
                    primaryBootSlot = VmProfile.BOOT_SLOT_ISO,
                    bootArgs = DeviceTreeBuilder.DEFAULT_BOOTARGS,
                    clockMhz = 1,
                    autoPauseInBackground = false,
                    architecture = VmProfile.ARCH_RISCV64,
                    engineType = VmProfile.ENGINE_KERNO_KOTLIN
                )
            )
        }
    }

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vm_profiles ADD COLUMN targetDiskImageId INTEGER")
                db.execSQL("ALTER TABLE vm_profiles ADD COLUMN primaryBootSlot TEXT NOT NULL DEFAULT 'iso'")
                db.execSQL("UPDATE vm_profiles SET architecture = 'riscv64' WHERE architecture != 'riscv64'")
            }
        }

        @Volatile
        private var instance: KernoDatabase? = null

        fun getInstance(context: Context): KernoDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KernoDatabase::class.java,
                    "kernovm_local.db"
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
