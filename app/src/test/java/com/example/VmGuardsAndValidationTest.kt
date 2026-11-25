package com.example

import com.example.data.BootGuardResult
import com.example.data.DistroPresetCatalog
import com.example.data.ImageKind
import com.example.data.ImageRepository
import com.example.data.VmProfile
import com.example.data.VmProfileDao
import com.example.data.VmRepository
import com.example.emulator.DeviceTreeBuilder
import com.example.emulator.DiagnosticMonitorRom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VmGuardsAndValidationTest {

    private val dummyDao = object : VmProfileDao {
        override fun observeAllProfiles(): Flow<List<VmProfile>> = flowOf(emptyList())
        override suspend fun getById(id: Long): VmProfile? = null
        override suspend fun count(): Int = 0
        override suspend fun insert(profile: VmProfile): Long = 1L
        override suspend fun update(profile: VmProfile) = Unit
        override suspend fun updateLastBootedAt(id: Long, timestamp: Long) = Unit
        override suspend fun deleteById(id: Long) = Unit
    }

    @Test
    fun bootGuardEnforces16MbLinuxHeadroomAndConservativeHeapCapFor128Mb() {
        val tightHeapRepo = VmRepository(dummyDao, maxHeapProvider = { 256L * 1024L * 1024L })
        assertFalse(tightHeapRepo.canAllocateRamMb(128))
        val heapRejected = tightHeapRepo.validateBootMemoryLayout(
            ramSizeMb = 128,
            kernelSizeBytes = 4L * 1024L * 1024L,
            dtbSizeBytes = 4096L,
            isBuiltInDiagRom = false
        )
        assertTrue(heapRejected is BootGuardResult.Rejected)

        val spaciousRepo = VmRepository(dummyDao, maxHeapProvider = { 512L * 1024L * 1024L })
        assertTrue(spaciousRepo.canAllocateRamMb(128))

        // 16 MB rejected for Linux kernel, allowed for Built-in Diagnostic ROM
        assertTrue(
            spaciousRepo.validateBootMemoryLayout(
                ramSizeMb = 16,
                kernelSizeBytes = 4L * 1024L * 1024L,
                dtbSizeBytes = 4096L,
                isBuiltInDiagRom = false
            ) is BootGuardResult.Rejected
        )
        assertTrue(
            spaciousRepo.validateBootMemoryLayout(
                ramSizeMb = 16,
                kernelSizeBytes = DiagnosticMonitorRom.binary.size.toLong(),
                dtbSizeBytes = 4096L,
                isBuiltInDiagRom = true
            ) is BootGuardResult.Valid
        )

        // 64 MB Linux profile with 50 MB kernel leaves < 16 MB headroom -> rejected
        assertTrue(
            spaciousRepo.validateBootMemoryLayout(
                ramSizeMb = 64,
                kernelSizeBytes = 50L * 1024L * 1024L,
                dtbSizeBytes = 4096L,
                isBuiltInDiagRom = false
            ) is BootGuardResult.Rejected
        )

        // 64 MB Linux profile with 6 MB kernel leaves > 16 MB headroom -> valid
        assertTrue(
            spaciousRepo.validateBootMemoryLayout(
                ramSizeMb = 64,
                kernelSizeBytes = 6L * 1024L * 1024L,
                dtbSizeBytes = 4096L,
                isBuiltInDiagRom = false
            ) is BootGuardResult.Valid
        )
    }

    @Test
    fun safContentSnifferRejectsHtmlAndInvalidDtbWhileAcceptingValidFdt() {
        val htmlBytes = "<!DOCTYPE html><html><body>404 Not Found</body></html>".toByteArray()
        assertTrue(
            runCatching {
                ImageRepository.validateImportedBinaryContent(htmlBytes, ImageKind.KERNEL_IMAGE)
            }.isFailure
        )

        assertTrue(
            runCatching {
                ImageRepository.validateImportedBinaryContent(ByteArray(64) { 0x42 }, ImageKind.DEVICE_TREE)
            }.isFailure
        )

        val validDtb = DeviceTreeBuilder.build(64L * 1024L * 1024L)
        assertTrue(
            runCatching {
                ImageRepository.validateImportedBinaryContent(validDtb, ImageKind.DEVICE_TREE)
            }.isSuccess
        )
    }

    @Test
    fun resolveCandidateDownloadUrlsGeneratesMirrorsForGithubRawAndCdn() {
        val rawGhUrl = "https://raw.githubusercontent.com/Iky969/vm/main/README.md"
        val resolved = ImageRepository.resolveCandidateDownloadUrls(rawGhUrl)

        // Must start with primary URL
        assertEquals(rawGhUrl, resolved.first())
        // Must contain jsdelivr CDN mirror
        assertTrue(
            resolved.any { it.contains("cdn.jsdelivr.net/gh/Iky969/vm@main/README.md") }
        )
        // Must contain github.com web raw mirror
        assertTrue(
            resolved.any { it.contains("github.com/Iky969/vm/raw/main/README.md") }
        )

        // Custom mirror deduplication
        val explicitMirrors = listOf(
            "https://cdn.jsdelivr.net/gh/Iky969/vm@main/README.md",
            "https://backup-mirror.local/sample.bin"
        )
        val resolvedWithExplicit = ImageRepository.resolveCandidateDownloadUrls(rawGhUrl, explicitMirrors)
        assertEquals(rawGhUrl, resolvedWithExplicit[0])
        assertEquals(explicitMirrors[0], resolvedWithExplicit[1])
        assertEquals(explicitMirrors[1], resolvedWithExplicit[2])
        // No duplicates
        assertEquals(resolvedWithExplicit.toSet().size, resolvedWithExplicit.size)
    }

    @Test
    fun builtinDiagRomPresetIsPresentAndPermissive() {
        val diagRom = DistroPresetCatalog.presets.firstOrNull { it.id == "builtin_diag_rom" }
        assertNotNull("Built-in diagnostic ROM preset must be registered in catalog", diagRom)
        requireNotNull(diagRom)

        assertTrue("Built-in diagnostic ROM must be marked offline", diagRom.isBuiltInOffline)
        assertTrue("Built-in diagnostic ROM must have empty downloadUrl", diagRom.downloadUrl.isEmpty())
        val allowedLicenses = setOf("MIT / Apache-2.0 (Bawaan)", "Apache-2.0", "MIT")
        for (preset in DistroPresetCatalog.presets) {
            assertTrue(
                "Preset ${preset.id} must use a permissive license: ${preset.license}",
                preset.license in allowedLicenses
            )
        }
    }
}
