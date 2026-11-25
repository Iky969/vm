package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.emulator.DeviceTreeBuilder

enum class ImageKind {
    BUILTIN_ROM,
    KERNEL_IMAGE,
    DEVICE_TREE,
    ISO_IMAGE,
    DISK_IMAGE
}

enum class ImageSourceType {
    BUILTIN,
    DOWNLOADED,
    SAF_IMPORT
}

enum class CpuThreadPriority(val label: String, val androidThreadPriority: Int) {
    NORMAL("Normal", android.os.Process.THREAD_PRIORITY_DEFAULT),
    BACKGROUND("Hemat Daya (Background)", android.os.Process.THREAD_PRIORITY_BACKGROUND)
}

@Entity(tableName = "vm_profiles")
data class VmProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val description: String = "",
    val ramSizeMb: Int = 64,
    val cpuThreadCount: Int = 1, // 1..min(4, hostCores) worker/IO thread limit
    val cpuQuotaPercent: Int = 100, // 25..100 duty-cycle enforcement
    val cpuThreadPriority: String = CpuThreadPriority.NORMAL.name,
    val storageQuotaMb: Int = 128, // Per-VM storage budget in MB
    val kernelImageId: Long,
    val customDtbImageId: Long? = null,
    val attachedIsoImageId: Long? = null, // Installer ISO / read-only media mounted at VirtIO-blk /dev/vda
    val targetDiskImageId: Long? = null,  // Read-write Virtual Disk Target mounted at VirtIO-blk /dev/vdb
    val primaryBootSlot: String = BOOT_SLOT_ISO, // BOOT_SLOT_ISO ("iso") or BOOT_SLOT_DISK ("disk")
    val bootArgs: String = DeviceTreeBuilder.DEFAULT_BOOTARGS,
    val clockMhz: Int = 1,
    val autoPauseInBackground: Boolean = false,
    val lastBootedAt: Long = 0L,
    val architecture: String = ARCH_RISCV64,
    val engineType: String = ENGINE_KERNO_KOTLIN
) {
    fun resolvedThreadPriority(): CpuThreadPriority {
        return runCatching { CpuThreadPriority.valueOf(cpuThreadPriority) }
            .getOrDefault(CpuThreadPriority.NORMAL)
    }

    fun resolvePrimaryBootSlot(): String {
        if (attachedIsoImageId == null) {
            return if (targetDiskImageId != null) BOOT_SLOT_DISK else BOOT_SLOT_ISO
        }
        return if (primaryBootSlot.equals(BOOT_SLOT_DISK, ignoreCase = true) && targetDiskImageId != null) {
            BOOT_SLOT_DISK
        } else {
            BOOT_SLOT_ISO
        }
    }

    companion object {
        const val ARCH_RISCV64 = "riscv64"
        const val ARCH_RISCV32 = "riscv32"
        const val ENGINE_KERNO_KOTLIN = "kerno_kotlin"
        const val BOOT_SLOT_ISO = "iso"
        const val BOOT_SLOT_DISK = "disk"

        fun normalizeArchitecture(arch: String): String {
            return if (arch.trim().equals(ARCH_RISCV32, ignoreCase = true)) {
                ARCH_RISCV32
            } else {
                ARCH_RISCV64
            }
        }
    }
}

@Entity(tableName = "local_images")
data class LocalImage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val kind: String, // ImageKind.name
    val sourceType: String, // ImageSourceType.name
    val localPath: String, // empty for BUILTIN_ROM
    val sizeBytes: Long,
    val sha256: String,
    val sourceUrl: String = "",
    val architectureTag: String = ARCH_RISCV64,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun resolvedKind(): ImageKind =
        runCatching { ImageKind.valueOf(kind) }.getOrDefault(ImageKind.KERNEL_IMAGE)

    fun resolvedSourceType(): ImageSourceType =
        runCatching { ImageSourceType.valueOf(sourceType) }.getOrDefault(ImageSourceType.SAF_IMPORT)

    companion object {
        const val ARCH_RISCV64 = "RISC-V 64-bit (RV64)"
        const val ARCH_RV32IMA_MMODE_NOMMU = "RV32IMA M-Mode No-MMU"
    }
}

data class DistroPreset(
    val id: String,
    val name: String,
    val description: String,
    val kernelVersion: String,
    val license: String,
    val sourceRepoUrl: String,
    val downloadUrl: String,
    val mirrorUrls: List<String> = emptyList(),
    val expectedSha256: String?, // Non-null enforces strict SHA-256 check
    val estimatedSizeLabel: String,
    val recommendedRamMb: Int,
    val defaultBootArgs: String = DeviceTreeBuilder.DEFAULT_BOOTARGS,
    val kernelConfigNotes: String,
    val isBuiltInOffline: Boolean = false
) {
    /**
     * Returns an ordered list of candidate URLs for download:
     * primary [downloadUrl] followed by any configured [mirrorUrls].
     */
    fun allDownloadCandidateUrls(): List<String> {
        val list = mutableListOf<String>()
        if (downloadUrl.isNotBlank()) list.add(downloadUrl)
        mirrorUrls.forEach { if (it.isNotBlank() && !list.contains(it)) list.add(it) }
        return list
    }
}

object DistroPresetCatalog {
    const val BUILDROOT_CONFIG_RECIPE = """
CONFIG_RISCV_M_MODE=y
CONFIG_MMU=n
CONFIG_RISCV_SBI=n
CONFIG_ARCH_RV32I=y
CONFIG_SERIAL_8250=y
CONFIG_SERIAL_8250_CONSOLE=y
CONFIG_MFD_SYSCON=y
CONFIG_POWER_RESET_SYSCON=y
CONFIG_POWER_RESET_SYSCON_POWEROFF=y
CONFIG_BLK_DEV_INITRD=y
CONFIG_INITRAMFS_SOURCE="rootfs.cpio"
    """

    val presets: List<DistroPreset> = listOf(
        DistroPreset(
            id = "builtin_diag_rom",
            name = "KernoVM RV32IMA Diagnostic ROM",
            description = "ROM monitor kode mesin RV32IMA bawaan offline untuk menguji UART 8250, CSR misa, FDT, CLINT mtime, WFI, dan SYSCON poweroff/reboot.",
            kernelVersion = "Bare-Metal RV32IMA v1.0",
            license = "MIT / Apache-2.0 (Bawaan)",
            sourceRepoUrl = "Built-in KernoVM",
            downloadUrl = "",
            mirrorUrls = emptyList(),
            expectedSha256 = null,
            estimatedSizeLabel = "< 4 KB",
            recommendedRamMb = 16,
            defaultBootArgs = DeviceTreeBuilder.DEFAULT_BOOTARGS,
            kernelConfigNotes = "M-Mode No-MMU bare-metal; langsung aktif tanpa unduhan.",
            isBuiltInOffline = true
        )
    )
}
