package com.example.data

import com.example.emulator.DiagnosticMonitorRom
import kotlinx.coroutines.flow.Flow

sealed class BootGuardResult {
    data object Valid : BootGuardResult()
    data class Warning(val message: String) : BootGuardResult()
    data class Rejected(val reason: String, val suggestedRamMb: Int? = null) : BootGuardResult()
}

class VmRepository(
    private val vmProfileDao: VmProfileDao,
    private val maxHeapProvider: () -> Long = { Runtime.getRuntime().maxMemory() }
) {
    val allProfiles: Flow<List<VmProfile>> = vmProfileDao.observeAllProfiles()

    suspend fun getProfileById(id: Long): VmProfile? = vmProfileDao.getById(id)

    suspend fun saveProfile(profile: VmProfile): Long {
        val normalizedArch = VmProfile.normalizeArchitecture(profile.architecture)
        val normalizedBootSlot = if (profile.attachedIsoImageId == null && profile.targetDiskImageId != null) {
            VmProfile.BOOT_SLOT_DISK
        } else if (profile.primaryBootSlot.equals(VmProfile.BOOT_SLOT_DISK, ignoreCase = true) &&
            profile.targetDiskImageId != null
        ) {
            VmProfile.BOOT_SLOT_DISK
        } else {
            VmProfile.BOOT_SLOT_ISO
        }
        val normalized = profile.copy(
            name = profile.name.trim(),
            cpuThreadCount = profile.cpuThreadCount.coerceIn(MIN_CPU_THREADS, MAX_CPU_THREADS),
            cpuQuotaPercent = profile.cpuQuotaPercent.coerceIn(MIN_CPU_QUOTA, MAX_CPU_QUOTA),
            storageQuotaMb = profile.storageQuotaMb.coerceIn(MIN_STORAGE_QUOTA_CUSTOM_MB, MAX_STORAGE_QUOTA_CUSTOM_MB),
            ramSizeMb = profile.ramSizeMb.coerceIn(MIN_RAM_MB, MAX_RAM_CUSTOM_MB),
            clockMhz = profile.clockMhz.coerceIn(1, 10),
            architecture = normalizedArch,
            engineType = VmProfile.ENGINE_KERNO_KOTLIN,
            primaryBootSlot = normalizedBootSlot
        )
        return if (normalized.id == 0L) {
            vmProfileDao.insert(normalized)
        } else {
            vmProfileDao.update(normalized)
            normalized.id
        }
    }

    /**
     * Ejects the attached installer ISO (`/dev/vda`) and switches deterministic primary boot priority
     * (`bootindex = 0`) to Virtual Disk Target (`/dev/vdb`) cleanly via native profile configuration.
     */
    suspend fun ejectIsoAndSwitchPrimaryDisk(profileId: Long): VmProfile? {
        val current = vmProfileDao.getById(profileId) ?: return null
        val updated = current.copy(
            attachedIsoImageId = null,
            primaryBootSlot = VmProfile.BOOT_SLOT_DISK
        )
        vmProfileDao.update(updated)
        return updated
    }

    suspend fun markBooted(profileId: Long) {
        vmProfileDao.updateLastBootedAt(profileId, System.currentTimeMillis())
    }

    suspend fun deleteProfile(profileId: Long) {
        vmProfileDao.deleteById(profileId)
    }

    /**
     * Conservative ART heap safety check: requires maxMemory >= ramBytes * 2 + 64 MB UI working set.
     */
    fun canAllocateRamMb(ramSizeMb: Int): Boolean {
        val ramBytes = ramSizeMb.toLong() * 1024L * 1024L
        if (ramSizeMb <= 64) return true
        val requiredHeap = ramBytes * 2L + UI_WORKING_SET_BYTES
        return maxHeapProvider() >= requiredHeap
    }

    fun maxHeapMb(): Int = (maxHeapProvider() / (1024L * 1024L)).toInt()

    /**
     * Boot-time headroom & heap guard:
     * - Linux profiles >= 64 MB require >= 16 MB free headroom above kernel before DTB.
     * - Linux profiles at 32 MB require >= 8 MB free headroom.
     * - Bare-metal / Diagnostic ROM requires >= 64 KB headroom.
     * - Rejects Linux kernels on 16 MB unless tiny bare-metal ROM.
     */
    fun validateBootMemoryLayout(
        ramSizeMb: Int,
        kernelSizeBytes: Long,
        dtbSizeBytes: Long,
        isBuiltInDiagRom: Boolean
    ): BootGuardResult {
        if (!canAllocateRamMb(ramSizeMb)) {
            return BootGuardResult.Rejected(
                reason = "Batas heap ART perangkat (${maxHeapMb()} MB) terlalu ketat untuk alokasi RAM $ramSizeMb MB secara aman.",
                suggestedRamMb = 64
            )
        }

        val ramBytes = ramSizeMb.toLong() * 1024L * 1024L
        val alignedDtb = (dtbSizeBytes + 15L) and 15L.inv()

        val minHeadroom = when {
            isBuiltInDiagRom -> 64L * 1024L // 64 KB for diagnostic ROM
            ramSizeMb >= 64 -> 16L * 1024L * 1024L // 16 MB safe headroom for Linux .bss + initramfs + memblock
            ramSizeMb >= 32 -> 8L * 1024L * 1024L  // 8 MB minimum on 32 MB
            else -> 16L * 1024L * 1024L // Force rejection on 16 MB for real Linux kernels
        }

        if (!isBuiltInDiagRom && ramSizeMb < 32) {
            return BootGuardResult.Rejected(
                reason = "RAM $ramSizeMb MB khusus bare-metal/diagnostik dan terlalu kecil untuk kernel Linux No-MMU (gunakan minimal 64 MB).",
                suggestedRamMb = 64
            )
        }

        val requiredTotal = kernelSizeBytes + alignedDtb + minHeadroom
        if (requiredTotal > ramBytes) {
            val kernelMb = ImageRepository.formatMb(kernelSizeBytes)
            val headroomMb = ImageRepository.formatMb(minHeadroom)
            return BootGuardResult.Rejected(
                reason = "Headroom RAM tidak cukup: kernel ($kernelMb) + DTB + ruang kosong minimum ($headroomMb) " +
                    "melebihi batas RAM $ramSizeMb MB. Naikkan RAM ke 64 MB atau pilih kernel lebih ringan.",
                suggestedRamMb = if (ramSizeMb < 64) 64 else null
            )
        }

        if (!isBuiltInDiagRom && ramSizeMb == 32) {
            return BootGuardResult.Warning(
                "RAM 32 MB memiliki headroom terbatas untuk Linux No-MMU; 64 MB sangat disarankan."
            )
        }
        return BootGuardResult.Valid
    }

    fun maxSupportedCpuThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    companion object {
        private const val UI_WORKING_SET_BYTES = 64L * 1024L * 1024L

        const val MIN_RAM_MB = 8
        const val MAX_RAM_SLIDER_MB = 512
        const val MAX_RAM_CUSTOM_MB = 1024
        const val RAM_STEP_MB = 4

        const val MIN_CPU_THREADS = 1
        const val MAX_CPU_THREADS = 16

        const val MIN_CPU_QUOTA = 10
        const val MAX_CPU_QUOTA = 100
        const val CPU_QUOTA_STEP = 5

        const val MIN_STORAGE_QUOTA_MB = 32
        const val MIN_STORAGE_QUOTA_CUSTOM_MB = 16
        const val MAX_STORAGE_QUOTA_SLIDER_MB = 4096
        const val MAX_STORAGE_QUOTA_CUSTOM_MB = 8192
        const val STORAGE_STEP_MB = 32

        val SUPPORTED_RAM_OPTIONS_MB = listOf(16, 32, 64, 128)
        val SUPPORTED_STORAGE_QUOTAS_MB = listOf(64, 128, 256, 512, 1024)
        val SUPPORTED_CPU_QUOTA_STEPS = listOf(25, 50, 75, 85, 100)
        val SUPPORTED_CPU_THREAD_OPTIONS = listOf(1, 2, 4)
    }
}
