package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.BootGuardResult
import com.example.data.CpuThreadPriority
import com.example.data.ImageKind
import com.example.data.ImageRepository
import com.example.data.KernoDatabase
import com.example.data.VmProfile
import com.example.data.VmRepository
import com.example.emulator.DeviceTreeBuilder
import com.example.emulator.DiagnosticMonitorRom
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KernoVmRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: KernoDatabase
    private lateinit var vmRepo: VmRepository
    private lateinit var imageRepo: ImageRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, KernoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vmRepo = VmRepository(db.vmProfileDao(), maxHeapProvider = { 512L * 1024L * 1024L })
        imageRepo = ImageRepository(context, db.localImageDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun roomDatabaseSeedsDiagnosticRomAndPersistsPerVmResourceQuotas() = runTest {
        db.seedDefaultsIfNeeded()

        val images = imageRepo.allImages.first()
        assertTrue(images.any { it.id == DiagnosticMonitorRom.BUILTIN_IMAGE_ID })

        val customId = vmRepo.saveProfile(
            VmProfile(
                name = "Linux Quota Test",
                ramSizeMb = 64,
                cpuQuotaPercent = 50,
                cpuThreadPriority = CpuThreadPriority.BACKGROUND.name,
                storageQuotaMb = 256,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID
            )
        )
        val loaded = vmRepo.getProfileById(customId)!!
        assertEquals("Linux Quota Test", loaded.name)
        assertEquals(64, loaded.ramSizeMb)
        assertEquals(50, loaded.cpuQuotaPercent)
        assertEquals(CpuThreadPriority.BACKGROUND, loaded.resolvedThreadPriority())
        assertEquals(256, loaded.storageQuotaMb)
    }

    @Test
    fun bootGuardEnforces16MbLinuxHeadroomAndConservativeHeapCapFor128Mb() {
        val tightHeapRepo = VmRepository(db.vmProfileDao(), maxHeapProvider = { 256L * 1024L * 1024L })
        // 128 MB requires 128*2 + 64 = 320 MB maxMemory -> rejected on 256 MB heap
        assertFalse(tightHeapRepo.canAllocateRamMb(128))
        val heapRejected = tightHeapRepo.validateBootMemoryLayout(
            ramSizeMb = 128,
            kernelSizeBytes = 4L * 1024L * 1024L,
            dtbSizeBytes = 4096L,
            isBuiltInDiagRom = false
        )
        assertTrue(heapRejected is BootGuardResult.Rejected)

        // 16 MB rejected for Linux kernel, allowed for Built-in Diagnostic ROM
        val linuxOn16 = vmRepo.validateBootMemoryLayout(
            ramSizeMb = 16,
            kernelSizeBytes = 4L * 1024L * 1024L,
            dtbSizeBytes = 4096L,
            isBuiltInDiagRom = false
        )
        assertTrue(linuxOn16 is BootGuardResult.Rejected)

        val diagOn16 = vmRepo.validateBootMemoryLayout(
            ramSizeMb = 16,
            kernelSizeBytes = DiagnosticMonitorRom.binary.size.toLong(),
            dtbSizeBytes = 4096L,
            isBuiltInDiagRom = true
        )
        assertTrue(diagOn16 is BootGuardResult.Valid)

        // 64 MB Linux profile with 50 MB kernel leaves < 16 MB headroom -> rejected
        val tooTightHeadroom = vmRepo.validateBootMemoryLayout(
            ramSizeMb = 64,
            kernelSizeBytes = 50L * 1024L * 1024L,
            dtbSizeBytes = 4096L,
            isBuiltInDiagRom = false
        )
        assertTrue(tooTightHeadroom is BootGuardResult.Rejected)

        // 64 MB Linux profile with 6 MB kernel leaves > 16 MB headroom -> valid
        val healthyLinux = vmRepo.validateBootMemoryLayout(
            ramSizeMb = 64,
            kernelSizeBytes = 6L * 1024L * 1024L,
            dtbSizeBytes = 4096L,
            isBuiltInDiagRom = false
        )
        assertTrue(healthyLinux is BootGuardResult.Valid)
    }

    @Test
    fun safContentSnifferRejectsHtmlAndInvalidDtbWhileAcceptingValidFdt() {
        val htmlBytes = "<!DOCTYPE html><html><body>404 Not Found</body></html>".toByteArray()
        val htmlResult = runCatching {
            ImageRepository.validateImportedBinaryContent(htmlBytes, ImageKind.KERNEL_IMAGE)
        }
        assertTrue("HTML error page must be rejected", htmlResult.isFailure)

        val badDtbResult = runCatching {
            ImageRepository.validateImportedBinaryContent(ByteArray(64) { 0x42 }, ImageKind.DEVICE_TREE)
        }
        assertTrue("Non-FDT file must be rejected as DTB", badDtbResult.isFailure)

        val validDtb = DeviceTreeBuilder.build(64L * 1024L * 1024L)
        val okDtbResult = runCatching {
            ImageRepository.validateImportedBinaryContent(validDtb, ImageKind.DEVICE_TREE)
        }
        assertTrue("Valid FDT binary must pass validation", okDtbResult.isSuccess)
    }

    @Test
    fun perVmStorageQuotaEnforcesWorkspaceLogLimit() = runTest {
        db.seedDefaultsIfNeeded()
        val tinyQuotaProfile = VmProfile(
            id = 99L,
            name = "Quota Tight",
            storageQuotaMb = 0, // 0 MB cap forces quota exceeded on non-empty log
            kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID
        )
        val failRes = imageRepo.saveConsoleLogForVm(tinyQuotaProfile, "Linux boot log sample")
        assertTrue(failRes.isFailure)

        val okProfile = tinyQuotaProfile.copy(storageQuotaMb = 32)
        val okRes = imageRepo.saveConsoleLogForVm(okProfile, "Linux boot log sample")
        assertTrue(okRes.isSuccess)
    }
}
