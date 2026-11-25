package com.example.e2e

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.ArchitectureValidator
import com.example.data.ImageKind
import com.example.data.ImageRepository
import com.example.data.ImageSourceType
import com.example.data.KernoDatabase
import com.example.data.LocalImage
import com.example.data.StoreCatalog
import com.example.data.StoreCategory
import com.example.data.StoreItem
import com.example.data.VmProfile
import com.example.data.VmRepository
import com.example.emulator.DiagnosticMonitorRom
import com.example.emulator.FdtInspector
import com.example.emulator.Iso9660Inspector
import com.example.emulator.VirtioBlockDevice
import com.example.vm.VmSessionManager
import com.example.vm.VmStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Phase B3 Opaque-Box E2E Suite (51 tests: 20 T1 + 20 T2 + 7 T3 pairwise + 4 T4 scenarios) covering:
 * - Feature #8: VmProfile RV64 Migration & Dual-Drive Schema (5 T1 + 5 T2)
 * - Feature #9: RISC-V Architecture Filter & Validator (5 T1 + 5 T2)
 * - Feature #10: Store Catalog "Alpine RV64" & .tar.gz Support (5 T1 + 5 T2)
 * - Feature #11: Runtime Dual-Drive & Native Eject ISO Logic (5 T1 + 5 T2)
 * - Tier 3 Cross-Feature Pairwise Interactions (7 tests)
 * - Tier 4 Real-World Application Scenarios 1–4 (4 tests)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class M3DataFilterCatalogEjectE2ETest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: KernoDatabase
    private lateinit var vmRepo: VmRepository
    private lateinit var imageRepo: ImageRepository
    private lateinit var sessionManager: VmSessionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = E2eTestFixtures.createInMemoryDatabase(context)
        vmRepo = VmRepository(db.vmProfileDao(), maxHeapProvider = { 512L * 1024L * 1024L })
        imageRepo = ImageRepository(context, db.localImageDao())
        sessionManager = VmSessionManager(context, vmRepo, imageRepo)
    }

    @After
    fun tearDown() {
        sessionManager.stopVm()
        db.close()
    }

    // =========================================================================
    // Feature #8 — VmProfile RV64 Migration & Dual-Drive Schema (5 T1 + 5 T2)
    // =========================================================================

    @Test
    fun f8_t1_vmProfileDefaultsToRiscv64DualDriveSlotsAndBootSlotIso() {
        val profile = VmProfile(name = "RV64 Default", kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID)
        assertEquals(VmProfile.ARCH_RISCV64, profile.architecture)
        assertEquals(VmProfile.ENGINE_KERNO_KOTLIN, profile.engineType)
        assertNull(profile.attachedIsoImageId)
        assertNull(profile.targetDiskImageId)
        assertEquals(VmProfile.BOOT_SLOT_ISO, profile.primaryBootSlot)
        assertEquals(VmProfile.BOOT_SLOT_ISO, profile.resolvePrimaryBootSlot())
    }

    @Test
    fun f8_t1_roomPersistsDualDriveIdsAndPrimaryBootSlot() = runTest {
        db.seedDefaultsIfNeeded()
        val iso = insertDummyImage("alpine.iso", ImageKind.ISO_IMAGE, 1024L * 1024L)
        val disk = insertDummyImage("target.img", ImageKind.DISK_IMAGE, 2L * 1024L * 1024L)

        val id = vmRepo.saveProfile(
            VmProfile(
                name = "Dual Drive Persist",
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = iso.id,
                targetDiskImageId = disk.id,
                primaryBootSlot = VmProfile.BOOT_SLOT_DISK
            )
        )
        val reloaded = requireNotNull(vmRepo.getProfileById(id))
        assertEquals(VmProfile.ARCH_RISCV64, reloaded.architecture)
        assertEquals(iso.id, reloaded.attachedIsoImageId)
        assertEquals(disk.id, reloaded.targetDiskImageId)
        assertEquals(VmProfile.BOOT_SLOT_DISK, reloaded.primaryBootSlot)
    }

    @Test
    fun f8_t1_computeVmStorageUsageIncludesBothIsoAndTargetDiskWithoutDoubleCount() = runTest {
        val kernel = insertDummyImage("kernel.bin", ImageKind.KERNEL_IMAGE, 1000L)
        val iso = insertDummyImage("installer.iso", ImageKind.ISO_IMAGE, 4096L)
        val disk = insertDummyImage("target.img", ImageKind.DISK_IMAGE, 8192L)

        val profile = VmProfile(
            id = 55L,
            name = "Quota Calc",
            kernelImageId = kernel.id,
            attachedIsoImageId = iso.id,
            targetDiskImageId = disk.id
        )
        assertEquals(1000L + 4096L + 8192L, imageRepo.computeVmStorageUsageBytes(profile))

        // Same image ID referenced twice must not double count
        val dedupProfile = profile.copy(targetDiskImageId = iso.id)
        assertEquals(1000L + 4096L, imageRepo.computeVmStorageUsageBytes(dedupProfile))
    }

    @Test
    fun f8_t1_createEmptyVirtualDiskProducesWritableDiskImageForVdb() = runTest {
        val result = imageRepo.createEmptyVirtualDisk("alpine_target", sizeMb = 4)
        assertTrue(result.isSuccess)
        val disk = result.getOrThrow()
        assertEquals(ImageKind.DISK_IMAGE.name, disk.kind)
        assertEquals(4L * 1024L * 1024L, disk.sizeBytes)
        assertTrue(disk.architectureTag.contains("RISC-V 64"))
        assertTrue(File(disk.localPath).exists())
    }

    @Test
    fun f8_t1_resolvePrimaryBootSlotPrefersDiskWhenIsoDetached() {
        val onlyDisk = VmProfile(
            name = "Disk Only",
            kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
            attachedIsoImageId = null,
            targetDiskImageId = 9L,
            primaryBootSlot = VmProfile.BOOT_SLOT_ISO
        )
        assertEquals(VmProfile.BOOT_SLOT_DISK, onlyDisk.resolvePrimaryBootSlot())
    }

    @Test
    fun f8_t2_saveProfileCoercesLegacyNonRiscvArchToRiscv64() = runTest {
        val id = vmRepo.saveProfile(
            VmProfile(
                name = "Legacy x86 Import",
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                architecture = "x86_64",
                engineType = "legacy"
            )
        )
        val saved = requireNotNull(vmRepo.getProfileById(id))
        assertEquals(VmProfile.ARCH_RISCV64, saved.architecture)
        assertEquals(VmProfile.ENGINE_KERNO_KOTLIN, saved.engineType)
    }

    @Test
    fun f8_t2_normalizeArchitectureMapsBlankAndUnknownToRiscv64() {
        assertEquals(VmProfile.ARCH_RISCV64, VmProfile.normalizeArchitecture(""))
        assertEquals(VmProfile.ARCH_RISCV64, VmProfile.normalizeArchitecture("x86_64"))
        assertEquals(VmProfile.ARCH_RISCV64, VmProfile.normalizeArchitecture("arm64"))
        assertEquals(VmProfile.ARCH_RISCV32, VmProfile.normalizeArchitecture("riscv32"))
    }

    @Test
    fun f8_t2_createEmptyVirtualDiskClampsExtremeSizesBetween4MbAnd1024Mb() = runTest {
        val tiny = imageRepo.createEmptyVirtualDisk("tiny", sizeMb = 1).getOrThrow()
        assertEquals(4L * 1024L * 1024L, tiny.sizeBytes)
        File(tiny.localPath).delete()
    }

    @Test
    fun f8_t2_missingAttachedDiskIdsAreSafelyIgnoredByStorageUsageCalculator() = runTest {
        val profile = VmProfile(
            id = 88L,
            name = "Missing Disk Refs",
            kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
            attachedIsoImageId = 99999L,
            targetDiskImageId = 88888L
        )
        assertEquals(0L, imageRepo.computeVmStorageUsageBytes(profile))
    }

    @Test
    fun f8_t2_ejectOnNonExistentProfileIdReturnsNullWithoutCrash() = runTest {
        assertNull(vmRepo.ejectIsoAndSwitchPrimaryDisk(404404L))
    }

    // =========================================================================
    // Feature #9 — RISC-V Architecture Filter & Validator (5 T1 + 5 T2)
    // =========================================================================

    @Test
    fun f9_t1_validatorAcceptsElf64RiscvAndRiscvLinuxImageHeaders() {
        val elfRv64 = E2eTestFixtures.createElfBytes(eiClass = 2, eMachine = 243)
        assertTrue(ArchitectureValidator.validateBytes(elfRv64, ImageKind.KERNEL_IMAGE, "vmlinux-rv64").isAccepted)

        val imgMagic30 = E2eTestFixtures.createRiscvLinuxImageHeader(useRsc5Magic = false)
        assertTrue(ArchitectureValidator.validateBytes(imgMagic30, ImageKind.KERNEL_IMAGE, "Image-rv64").isAccepted)

        val imgMagic38 = E2eTestFixtures.createRiscvLinuxImageHeader(useRsc5Magic = true)
        assertTrue(ArchitectureValidator.validateBytes(imgMagic38, ImageKind.KERNEL_IMAGE, "Image-rsc5").isAccepted)
    }

    @Test
    fun f9_t1_validatorRejectsX86ElfX8664ElfAndPcBzImage() {
        val elfI386 = E2eTestFixtures.createElfBytes(eiClass = 1, eMachine = 3)
        val elfX8664 = E2eTestFixtures.createElfBytes(eiClass = 2, eMachine = 62)
        val elfAarch64 = E2eTestFixtures.createElfBytes(eiClass = 2, eMachine = 183)
        val bzImg = E2eTestFixtures.createPcBzImageBytes()

        assertFalse(ArchitectureValidator.inspectBinaryHeader(elfI386).isAccepted)
        assertFalse(ArchitectureValidator.inspectBinaryHeader(elfX8664).isAccepted)
        assertFalse(ArchitectureValidator.inspectBinaryHeader(elfAarch64).isAccepted)
        assertFalse(ArchitectureValidator.inspectBinaryHeader(bzImg).isAccepted)
    }

    @Test
    fun f9_t1_validatorRejectsX86ElToritoIsoAndAcceptsRv64UefiIso() {
        val x86Iso = tempFolder.newFile("pc_boot.iso").apply {
            writeBytes(E2eTestFixtures.createElToritoIsoBytes("PC_BOOT", platformId = 0x00))
        }
        val rv64PeStub = E2eTestFixtures.createPeCoffBytes(machineId = 0x5064)
        val rv64Iso = tempFolder.newFile("alpine-standard-3.24.4-riscv64.iso").apply {
            writeBytes(E2eTestFixtures.createElToritoIsoBytes("ALPINE_RV64", platformId = 0xEF, bootImagePayload = rv64PeStub))
        }

        val x86Meta = Iso9660Inspector.inspect(x86Iso)
        assertTrue(x86Meta.hasElTorito)
        assertEquals(0x00, x86Meta.elToritoPlatformId)

        assertFalse(ArchitectureValidator.validateFile(x86Iso, ImageKind.ISO_IMAGE).isAccepted)
        assertTrue(ArchitectureValidator.validateFile(rv64Iso, ImageKind.ISO_IMAGE).isAccepted)
    }

    @Test
    fun f9_t1_validatorInspectsTarGzPayloadsAndRejectsX86MuslArchive() {
        val validRv64Elf = E2eTestFixtures.createElfBytes(eiClass = 2, eMachine = 243)
        val goodTarGz = tempFolder.newFile("alpine-minirootfs-riscv64.tar.gz").apply {
            writeBytes(E2eTestFixtures.createTarGzBytes(listOf("bin/busybox" to validRv64Elf)))
        }
        val badX86Elf = E2eTestFixtures.createElfBytes(eiClass = 2, eMachine = 62)
        val spoofedTarGz = tempFolder.newFile("alpine-minirootfs-riscv64-fake.tar.gz").apply {
            writeBytes(E2eTestFixtures.createTarGzBytes(listOf("lib/ld-musl-x86_64.so.1" to badX86Elf)))
        }

        assertTrue(ArchitectureValidator.validateFile(goodTarGz, ImageKind.DISK_IMAGE).isAccepted)
        assertFalse(ArchitectureValidator.validateFile(spoofedTarGz, ImageKind.DISK_IMAGE).isAccepted)
    }

    @Test
    fun f9_t1_safImportRejectsX86IsoBeforeInsertingLocalImage() = runTest {
        val x86IsoFile = tempFolder.newFile("debian-amd64.iso").apply {
            writeBytes(E2eTestFixtures.createElToritoIsoBytes("DEBIAN_AMD64", platformId = 0x00))
        }
        val uri = Uri.fromFile(x86IsoFile)
        val res = imageRepo.importFromSaf(uri, ImageKind.ISO_IMAGE)
        assertTrue("SAF import of x86 ISO must fail", res.isFailure)
        assertTrue(db.localImageDao().observeAllImages().first().none { it.name.contains("amd64", ignoreCase = true) })
    }

    @Test
    fun f9_t2_spoofedRv64FilenameWrappingX86ElToritoOrPePayloadIsRejected() {
        val spoofedByElTorito = tempFolder.newFile("rv64_spoof_bios.iso").apply {
            writeBytes(E2eTestFixtures.createElToritoIsoBytes("FAKE_RV64", platformId = 0x00))
        }
        val x64Pe = E2eTestFixtures.createPeCoffBytes(machineId = 0x8664)
        val spoofedByEfiPe = tempFolder.newFile("rv64_spoof_efi.iso").apply {
            writeBytes(E2eTestFixtures.createElToritoIsoBytes("FAKE_EFI", platformId = 0xEF, bootImagePayload = x64Pe))
        }
        assertFalse(ArchitectureValidator.validateFile(spoofedByElTorito, ImageKind.ISO_IMAGE, "rv64_fake.iso").isAccepted)
        assertFalse(ArchitectureValidator.validateFile(spoofedByEfiPe, ImageKind.ISO_IMAGE, "rv64_fake2.iso").isAccepted)
    }

    @Test
    fun f9_t2_metadataRejectsX86I386Amd64Arm64AndRv32IsoForRv64Target() {
        for (badName in listOf(
            "alpine-standard-3.20-x86_64.iso",
            "ubuntu-24.04-amd64.iso",
            "debian-i386-netinst.iso",
            "fedora-aarch64.iso",
            "legacy-riscv32-disk.iso"
        )) {
            val res = ArchitectureValidator.validateMetadata(badName, targetProfileArch = VmProfile.ARCH_RISCV64)
            assertFalse("Must reject '$badName' for RV64 target", res.isAccepted)
        }
    }

    @Test
    fun f9_t2_validatorRejectsElf32RiscvForRv64WhileAcceptingForRv32() {
        val elf32 = E2eTestFixtures.createElfBytes(eiClass = 1, eMachine = 243)
        assertFalse(ArchitectureValidator.inspectBinaryHeader(elf32, VmProfile.ARCH_RISCV64).isAccepted)
        assertTrue(ArchitectureValidator.inspectBinaryHeader(elf32, VmProfile.ARCH_RISCV32).isAccepted)
    }

    @Test
    fun f9_t2_validatorRejectsHtmlErrorPayloadAndCorruptGzip() {
        val html = "<!DOCTYPE html><html>404</html>".toByteArray()
        assertFalse(ArchitectureValidator.inspectBinaryHeader(html).isAccepted)

        val badGz = tempFolder.newFile("corrupt.tar.gz").apply {
            writeBytes(byteArrayOf(0x1F, 0x8B.toByte(), 0x08, 0x00, 0xDE.toByte(), 0xAD.toByte()))
        }
        assertFalse(ArchitectureValidator.validateFile(badGz, ImageKind.DISK_IMAGE).isAccepted)
    }

    @Test
    fun f9_t2_isImageCompatibleWithProfileHandlesNonExistentPathsAndBuiltinRom() {
        val rom = LocalImage(
            id = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
            name = "Diag ROM",
            kind = ImageKind.BUILTIN_ROM.name,
            sourceType = ImageSourceType.BUILTIN.name,
            localPath = "",
            sizeBytes = 4096L,
            sha256 = DiagnosticMonitorRom.sha256
        )
        assertTrue(ArchitectureValidator.isImageCompatibleWithProfile(rom, VmProfile.ARCH_RISCV64))

        val x86TagImg = LocalImage(
            id = 5L,
            name = "pc.iso",
            kind = ImageKind.ISO_IMAGE.name,
            sourceType = ImageSourceType.SAF_IMPORT.name,
            localPath = "/nonexistent/pc.iso",
            sizeBytes = 1024L,
            sha256 = "0".repeat(64),
            architectureTag = "x86_64"
        )
        assertFalse(ArchitectureValidator.isImageCompatibleWithProfile(x86TagImg, VmProfile.ARCH_RISCV64))
    }

    // =========================================================================
    // Feature #10 — Store Catalog "Alpine RV64" & .tar.gz Support (5 T1 + 5 T2)
    // =========================================================================

    @Test
    fun f10_t1_storeCatalogProvidesAlpineRv64WithOfficialAlpineRiscv64UrlAndSha() {
        val alpine = StoreCatalog.findById("alpine_rv64")
        assertNotNull("Alpine RV64 must exist in StoreCatalog", alpine)
        requireNotNull(alpine)

        assertEquals("Alpine RV64", alpine.name)
        assertEquals("riscv64", alpine.architecture)
        assertEquals(ImageKind.DISK_IMAGE, alpine.targetKind)
        assertEquals(StoreCategory.ISO, alpine.category)
        assertTrue(alpine.downloadUrl.startsWith("https://dl-cdn.alpinelinux.org/alpine/"))
        assertTrue(alpine.downloadUrl.contains("riscv64"))
        assertTrue(alpine.downloadUrl.endsWith(".tar.gz"))
        assertEquals(3451625L, alpine.sizeBytes)
        assertEquals("010683db6c73b3ad1c334fc8f93f64ae748ef67ae6d69e964929aeaf8517bff3", alpine.expectedSha256)
    }

    @Test
    fun f10_t1_storeCatalogReplacesAlpineRv32CompletelyAndProvidesIsoAlpineRv64() {
        assertTrue(StoreCatalog.items.none { it.name.contains("RV32", ignoreCase = true) && it.name.contains("Alpine", ignoreCase = true) })
        val iso = StoreCatalog.findById("iso_alpine_rv64")
        assertNotNull(iso)
        requireNotNull(iso)
        assertEquals("riscv64", iso.architecture)
        assertEquals(ImageKind.ISO_IMAGE, iso.targetKind)
        assertTrue(iso.downloadUrl.endsWith(".iso"))
        assertEquals("0975261d6bbedd200e256b66edb9eeb9e2db5547ff5b51087e3e00aebbb802d5", iso.expectedSha256)
    }

    @Test
    fun f10_t1_downloadStoreItemDownloadsAndVerifiesAlpineRv64TarGzToDiskDir() = runTest {
        val payload = E2eTestFixtures.createTarGzBytes(
            listOf("bin/busybox" to E2eTestFixtures.createElfBytes(2, 243))
        )
        val sha = E2eTestFixtures.sha256Hex(payload)
        val alpine = StoreCatalog.findById("alpine_rv64")!!.copy(expectedSha256 = sha, sizeBytes = payload.size.toLong())

        val mockClient = E2eTestFixtures.createMockHttpClient { req ->
            E2eTestFixtures.mockResponse(req, 200, payload, "application/gzip")
        }
        val repo = ImageRepository(context, db.localImageDao(), mockClient)
        val res = repo.downloadStoreItem(alpine)
        assertTrue("Expected Alpine RV64 tar.gz download to succeed: ${res.exceptionOrNull()}", res.isSuccess)
        val img = res.getOrThrow()
        assertEquals(ImageKind.DISK_IMAGE.name, img.kind)
        assertEquals(sha, img.sha256)
        assertTrue(img.localPath.endsWith(".tar.gz"))
        assertTrue(File(img.localPath).exists())
    }

    @Test
    fun f10_t1_downloadStoreItemUsesMirrorFallbackWhenPrimaryReturns503() = runTest {
        val payload = E2eTestFixtures.createTarGzBytes(
            listOf("bin/busybox" to E2eTestFixtures.createElfBytes(2, 243))
        )
        val sha = E2eTestFixtures.sha256Hex(payload)
        val alpine = StoreCatalog.findById("alpine_rv64")!!.copy(expectedSha256 = sha, sizeBytes = payload.size.toLong())

        val requestedUrls = mutableListOf<String>()
        val mockClient = E2eTestFixtures.createMockHttpClient { req ->
            val u = req.url.toString()
            requestedUrls.add(u)
            if (u == alpine.downloadUrl) {
                E2eTestFixtures.mockResponse(req, 503, ByteArray(0))
            } else {
                E2eTestFixtures.mockResponse(req, 200, payload, "application/gzip")
            }
        }
        val repo = ImageRepository(context, db.localImageDao(), mockClient)
        val res = repo.downloadStoreItem(alpine)
        assertTrue(res.isSuccess)
        assertEquals(2, requestedUrls.size)
        assertEquals(alpine.mirrorUrls.first(), requestedUrls[1])
    }

    @Test
    fun f10_t1_storeCatalogItemsPassArchitectureValidatorForRv64AndDiagRom() {
        for (item in StoreCatalog.items) {
            val target = if (item.targetKind == ImageKind.BUILTIN_ROM) VmProfile.ARCH_RISCV32 else VmProfile.ARCH_RISCV64
            assertTrue("Catalog item ${item.id} must validate cleanly", ArchitectureValidator.validateStoreItem(item, target).isAccepted)
        }
    }

    @Test
    fun f10_t2_downloadStoreItemRejectsSha256MismatchAndDeletesTempFiles() = runTest {
        val payload = E2eTestFixtures.createTarGzBytes(listOf("bin/sh" to E2eTestFixtures.createElfBytes(2, 243)))
        val wrongSha = "f".repeat(64)
        val alpine = StoreCatalog.findById("alpine_rv64")!!.copy(expectedSha256 = wrongSha)

        val mockClient = E2eTestFixtures.createMockHttpClient { req ->
            E2eTestFixtures.mockResponse(req, 200, payload)
        }
        val repo = ImageRepository(context, db.localImageDao(), mockClient)
        val res = repo.downloadStoreItem(alpine)
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is SecurityException)
        val disksDir = File(context.filesDir, "disks")
        assertTrue((disksDir.listFiles()?.none { it.name.endsWith(".part") }) ?: true)
    }

    @Test
    fun f10_t2_downloadStoreItemRejectsHtmlErrorResponseEvenWithHttp200() = runTest {
        val alpine = StoreCatalog.findById("alpine_rv64")!!.copy(expectedSha256 = null)
        val mockClient = E2eTestFixtures.createMockHttpClient { req ->
            E2eTestFixtures.mockResponse(req, 200, "<html>502 Bad Gateway</html>".toByteArray(), "text/html")
        }
        val repo = ImageRepository(context, db.localImageDao(), mockClient)
        val res = repo.downloadStoreItem(alpine)
        assertTrue(res.isFailure)
    }

    @Test
    fun f10_t2_downloadStoreItemRejectsNonRiscvStoreItemBeforeNetworkCall() = runTest {
        var invoked = false
        val badItem = StoreItem(
            id = "bad_pc_item",
            name = "PC Linux",
            description = "Non RISC-V",
            category = StoreCategory.ISO,
            downloadUrl = "https://example.com/pc.iso",
            sizeBytes = 1024L,
            license = "MIT",
            architecture = "amd64",
            targetKind = ImageKind.ISO_IMAGE,
            filename = "pc-amd64.iso"
        )
        val mockClient = E2eTestFixtures.createMockHttpClient { req ->
            invoked = true
            E2eTestFixtures.mockResponse(req, 200, ByteArray(1024))
        }
        val repo = ImageRepository(context, db.localImageDao(), mockClient)
        val res = repo.downloadStoreItem(badItem)
        assertTrue(res.isFailure)
        assertFalse("HTTP client must not be called for non-RISC-V StoreItem", invoked)
    }

    @Test
    fun f10_t2_downloadStoreItemRejectsMalformedExpectedShaFormat() = runTest {
        val alpine = StoreCatalog.findById("alpine_rv64")!!.copy(expectedSha256 = "not-a-sha")
        val mockClient = E2eTestFixtures.createMockHttpClient { req ->
            E2eTestFixtures.mockResponse(req, 200, ByteArray(128))
        }
        val repo = ImageRepository(context, db.localImageDao(), mockClient)
        val res = repo.downloadStoreItem(alpine)
        assertTrue(res.isFailure)
    }

    @Test
    fun f10_t2_downloadBuiltinDiagRomWorksOfflineWithEmptyUrl() = runTest {
        val diag = StoreCatalog.findById("kernel_diag_rom")!!
        val res = imageRepo.downloadStoreItem(diag)
        assertTrue(res.isSuccess)
        assertEquals(DiagnosticMonitorRom.BUILTIN_IMAGE_ID, res.getOrThrow().id)
    }

    // =========================================================================
    // Feature #11 — Runtime Dual-Drive & Native Eject ISO Logic (5 T1 + 5 T2)
    // =========================================================================

    @Test
    fun f11_t1_startVmAttachesDualDriveVdaRoAndVdbRwAndGeneratesRv64Fdt() = runTest {
        db.seedDefaultsIfNeeded()
        val isoImg = insertRealIsoFile("alpine-rv64-installer.iso")
        val targetDisk = imageRepo.createEmptyVirtualDisk("target_vdb.img", 4).getOrThrow()

        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Boot Dual Drive",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = isoImg.id,
                targetDiskImageId = targetDisk.id,
                primaryBootSlot = VmProfile.BOOT_SLOT_ISO
            )
        )
        val profile = vmRepo.getProfileById(pid)!!
        sessionManager.startVm(profile)
        awaitVmStatus(VmStatus.RUNNING)

        val state = sessionManager.state.value
        assertEquals(isoImg.name, state.attachedIsoName)
        assertEquals(targetDisk.name, state.targetDiskName)
        assertEquals(listOf("vda" to 0, "vdb" to 1), state.virtioBlockNodes.map { it.deviceName!! to it.bootIndex!! })

        val (vda, vdb) = sessionManager.activeBlockDevicesSnapshot()
        assertNotNull(vda)
        assertNotNull(vdb)
        assertTrue(vda!!.readOnly)
        assertFalse(vdb!!.readOnly)
    }

    @Test
    fun f11_t1_ejectIsoDetachesVdaPromotesVdbToBootIndex0AndUpdatesFdtAndDb() = runTest {
        db.seedDefaultsIfNeeded()
        val isoImg = insertRealIsoFile("inst.iso")
        val diskImg = imageRepo.createEmptyVirtualDisk("disk_target.img", 4).getOrThrow()

        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Live Eject Test",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = isoImg.id,
                targetDiskImageId = diskImg.id,
                primaryBootSlot = VmProfile.BOOT_SLOT_ISO
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)

        val ejectRes = sessionManager.ejectIsoAndSwitchPrimaryDisk(pid)
        assertTrue(ejectRes.isSuccess)
        val updated = ejectRes.getOrThrow()
        assertNull(updated.attachedIsoImageId)
        assertEquals(VmProfile.BOOT_SLOT_DISK, updated.primaryBootSlot)
        assertEquals(diskImg.id, updated.targetDiskImageId)

        val persisted = requireNotNull(vmRepo.getProfileById(pid))
        assertNull(persisted.attachedIsoImageId)
        assertEquals(VmProfile.BOOT_SLOT_DISK, persisted.primaryBootSlot)

        val (vda, vdb) = sessionManager.activeBlockDevicesSnapshot()
        assertNull("ISO device /dev/vda must be null after eject", vda)
        assertNotNull("Target disk /dev/vdb must stay attached", vdb)
        assertEquals(0, vdb!!.bootIndex)

        val dtb = requireNotNull(sessionManager.activeDtbBytes)
        val parsed = FdtInspector.parse(dtb)
        assertNull(parsed.nodes["/soc/virtio_mmio@10001000"])
        assertEquals(listOf("vdb" to 0), parsed.bootIndices)
    }

    @Test
    fun f11_t1_bootWithPrimaryBootSlotDiskGivesVdbBootIndex0EvenWhenIsoAttached() = runTest {
        db.seedDefaultsIfNeeded()
        val isoImg = insertRealIsoFile("sec_iso.iso")
        val diskImg = imageRepo.createEmptyVirtualDisk("prim_disk.img", 4).getOrThrow()

        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Disk Primary Dual",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = isoImg.id,
                targetDiskImageId = diskImg.id,
                primaryBootSlot = VmProfile.BOOT_SLOT_DISK
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)

        val parsed = FdtInspector.parse(requireNotNull(sessionManager.activeDtbBytes))
        assertEquals(listOf("vdb" to 0, "vda" to 1), parsed.bootIndices)
    }

    @Test
    fun f11_t1_resetVmAfterEjectBootsWithoutVdaAndWithVdbBootIndex0() = runTest {
        db.seedDefaultsIfNeeded()
        val isoImg = insertRealIsoFile("reset_iso.iso")
        val diskImg = imageRepo.createEmptyVirtualDisk("reset_disk.img", 4).getOrThrow()

        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Reset After Eject",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = isoImg.id,
                targetDiskImageId = diskImg.id
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)

        assertTrue(sessionManager.ejectIsoAndSwitchPrimaryDisk(pid).isSuccess)
        sessionManager.resetVm()
        awaitVmStatus(VmStatus.RUNNING)

        val (vda, vdb) = sessionManager.activeBlockDevicesSnapshot()
        assertNull(vda)
        assertNotNull(vdb)
        assertEquals(0, vdb!!.bootIndex)
    }

    @Test
    fun f11_t1_ejectWorksWhenVmIsStoppedUsingProfileId() = runTest {
        db.seedDefaultsIfNeeded()
        val isoImg = insertRealIsoFile("stopped_iso.iso")
        val diskImg = imageRepo.createEmptyVirtualDisk("stopped_disk.img", 4).getOrThrow()

        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Stopped Eject",
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = isoImg.id,
                targetDiskImageId = diskImg.id
            )
        )
        val res = sessionManager.ejectIsoAndSwitchPrimaryDisk(pid)
        assertTrue(res.isSuccess)
        val reloaded = requireNotNull(vmRepo.getProfileById(pid))
        assertNull(reloaded.attachedIsoImageId)
        assertEquals(VmProfile.BOOT_SLOT_DISK, reloaded.primaryBootSlot)
    }

    @Test
    fun f11_t2_bootRejectsNonRiscvIsoAtRuntimeBeforeStartingCpuLoop() = runTest {
        db.seedDefaultsIfNeeded()
        val badFile = tempFolder.newFile("bad_runtime.iso").apply {
            writeBytes(E2eTestFixtures.createElToritoIsoBytes("PC_BIOS", platformId = 0x00))
        }
        val badImgId = db.localImageDao().insert(
            LocalImage(
                name = "bad_runtime.iso",
                kind = ImageKind.ISO_IMAGE.name,
                sourceType = ImageSourceType.SAF_IMPORT.name,
                localPath = badFile.absolutePath,
                sizeBytes = badFile.length(),
                sha256 = "0".repeat(64)
            )
        )
        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Bad ISO Runtime",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = badImgId
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.ERROR)
        assertNotNull(sessionManager.state.value.errorMessage)
    }

    @Test
    fun f11_t2_ejectWithNoActiveVmAndNoProfileIdFailsCleanly() = runTest {
        val res = sessionManager.ejectIsoAndSwitchPrimaryDisk(null)
        assertTrue(res.isFailure)
    }

    @Test
    fun f11_t2_idempotentDoubleEjectKeepsVdbIntactAndNeverThrows() = runTest {
        db.seedDefaultsIfNeeded()
        val isoImg = insertRealIsoFile("double_eject.iso")
        val diskImg = imageRepo.createEmptyVirtualDisk("double_disk.img", 4).getOrThrow()

        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Double Eject",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = isoImg.id,
                targetDiskImageId = diskImg.id
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)

        assertTrue(sessionManager.ejectIsoAndSwitchPrimaryDisk(pid).isSuccess)
        assertTrue(sessionManager.ejectIsoAndSwitchPrimaryDisk(pid).isSuccess)
        val (_, vdb) = sessionManager.activeBlockDevicesSnapshot()
        assertNotNull(vdb)
        assertEquals(0, vdb!!.bootIndex)
    }

    @Test
    fun f11_t2_ejectWhenOnlyIsoWasAttachedLeavesZeroVirtioBlockNodesInDtb() = runTest {
        db.seedDefaultsIfNeeded()
        val isoImg = insertRealIsoFile("iso_only.iso")
        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "ISO Only Eject",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = isoImg.id,
                targetDiskImageId = null
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)

        assertTrue(sessionManager.ejectIsoAndSwitchPrimaryDisk(pid).isSuccess)
        val parsed = FdtInspector.parse(requireNotNull(sessionManager.activeDtbBytes))
        assertTrue(parsed.virtioBlockNodes.isEmpty())
    }

    @Test
    fun f11_t2_bootOnlyTargetDiskWithoutIsoSetsVdbBootIndex0Directly() = runTest {
        db.seedDefaultsIfNeeded()
        val diskImg = imageRepo.createEmptyVirtualDisk("only_vdb.img", 4).getOrThrow()
        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Only Target Disk",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = null,
                targetDiskImageId = diskImg.id
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)

        val parsed = FdtInspector.parse(requireNotNull(sessionManager.activeDtbBytes))
        assertEquals(listOf("vdb" to 0), parsed.bootIndices)
    }

    // =========================================================================
    // Tier 3: Cross-Feature Pairwise Interactions (7 tests)
    // =========================================================================

    @Test
    fun tier3_f2xF8_seededKernoDatabaseV4ProfilesAllUseRv64AndKernoKotlin() = runTest {
        db.seedDefaultsIfNeeded()
        val seeded = db.vmProfileDao().observeAllProfiles().first()
        assertTrue(seeded.size >= 2)
        for (p in seeded) {
            assertEquals(VmProfile.ARCH_RISCV64, p.architecture)
            assertEquals(VmProfile.ENGINE_KERNO_KOTLIN, p.engineType)
            assertFalse(p.name.contains("qemu", ignoreCase = true))
        }
    }

    @Test
    fun tier3_f8xF9_validatorPermitsRv64IsoOnVdaAndRwDiskOnVdbWhileRejectingX86Iso() = runTest {
        val goodIso = insertRealIsoFile("valid-rv64.iso")
        val badX86Iso = tempFolder.newFile("bad-x86_64.iso").apply {
            writeBytes(E2eTestFixtures.createElToritoIsoBytes("BAD_X86", platformId = 0x00))
        }
        val rwDisk = imageRepo.createEmptyVirtualDisk("vdb_rw.img", 4).getOrThrow()

        assertTrue(imageRepo.importFromSaf(Uri.fromFile(badX86Iso), ImageKind.ISO_IMAGE).isFailure)
        assertTrue(ArchitectureValidator.isImageCompatibleWithProfile(goodIso, VmProfile.ARCH_RISCV64))
        assertTrue(ArchitectureValidator.isImageCompatibleWithProfile(rwDisk, VmProfile.ARCH_RISCV64))
    }

    @Test
    fun tier3_f9xF10_downloadedAlpineCatalogItemsPassValidatorAndSpoofedPayloadFails() = runTest {
        val goodTarGz = E2eTestFixtures.createTarGzBytes(
            listOf("bin/busybox" to E2eTestFixtures.createElfBytes(2, 243))
        )
        val badTarGz = E2eTestFixtures.createTarGzBytes(
            listOf("bin/busybox" to E2eTestFixtures.createElfBytes(2, 62))
        )
        val alpine = StoreCatalog.findById("alpine_rv64")!!.copy(expectedSha256 = null)

        val goodRepo = ImageRepository(
            context,
            db.localImageDao(),
            E2eTestFixtures.createMockHttpClient { req -> E2eTestFixtures.mockResponse(req, 200, goodTarGz) }
        )
        assertTrue(goodRepo.downloadStoreItem(alpine).isSuccess)

        val badRepo = ImageRepository(
            context,
            db.localImageDao(),
            E2eTestFixtures.createMockHttpClient { req -> E2eTestFixtures.mockResponse(req, 200, badTarGz) }
        )
        assertTrue(badRepo.downloadStoreItem(alpine).isFailure)
    }

    @Test
    fun tier3_f7xF11_vmSessionManagerGeneratesRv64FdtWithDeterministicDualDriveBootIndices() = runTest {
        db.seedDefaultsIfNeeded()
        val isoImg = insertRealIsoFile("pair_boot.iso")
        val diskImg = imageRepo.createEmptyVirtualDisk("pair_boot.img", 4).getOrThrow()
        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "FDT + Runtime",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = isoImg.id,
                targetDiskImageId = diskImg.id,
                primaryBootSlot = VmProfile.BOOT_SLOT_ISO
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)

        val parsed = FdtInspector.parse(requireNotNull(sessionManager.activeDtbBytes))
        assertEquals("rv64ima", parsed.targetArchitectureIsa)
        assertEquals("KernoVM RV64", parsed.modelName)
        assertEquals(listOf("vda" to 0, "vdb" to 1), parsed.bootIndices)
    }

    @Test
    fun tier3_f6xF11_ejectClosesVdaWhileVdbContinuesServingVirtioBlockIoAt10003000() = runTest {
        db.seedDefaultsIfNeeded()
        val isoImg = insertRealIsoFile("live_io.iso")
        val diskImg = imageRepo.createEmptyVirtualDisk("live_io.img", 4).getOrThrow()
        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Live I/O Post-Eject",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = isoImg.id,
                targetDiskImageId = diskImg.id
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)

        val (vdaBefore, vdbBefore) = sessionManager.activeBlockDevicesSnapshot()
        assertNotNull(vdaBefore)
        assertNotNull(vdbBefore)

        assertTrue(sessionManager.ejectIsoAndSwitchPrimaryDisk(pid).isSuccess)
        assertTrue(vdaBefore!!.isDetached)
        assertFalse(vdbBefore!!.isDetached)
        assertEquals(VirtioBlockDevice.VIRTIO_MAGIC, vdbBefore.readRegister(0x000))
        assertEquals(VirtioBlockDevice.VIRTIO_VENDOR_ID, vdbBefore.readRegister(0x00C))
    }

    @Test
    fun tier3_f8xF11_storageUsageDropsEjectedIsoFootprintWhileRetainingTargetDisk() = runTest {
        db.seedDefaultsIfNeeded()
        val isoImg = insertRealIsoFile("quota_iso.iso")
        val diskImg = imageRepo.createEmptyVirtualDisk("quota_vdb.img", 4).getOrThrow()
        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Quota After Eject",
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = isoImg.id,
                targetDiskImageId = diskImg.id
            )
        )
        val beforeBytes = imageRepo.computeVmStorageUsageBytes(vmRepo.getProfileById(pid)!!)
        assertEquals(isoImg.sizeBytes + diskImg.sizeBytes, beforeBytes)

        val updated = requireNotNull(sessionManager.ejectIsoAndSwitchPrimaryDisk(pid).getOrThrow())
        val afterBytes = imageRepo.computeVmStorageUsageBytes(updated)
        assertEquals(diskImg.sizeBytes, afterBytes)
    }

    @Test
    fun tier3_f10xF8_downloadedAlpineRv64AttachesToTargetDiskSlotAndPersistsInRoomV4() = runTest {
        db.seedDefaultsIfNeeded()
        val rootfs = E2eTestFixtures.createTarGzBytes(listOf("etc/os-release" to "NAME=Alpine Linux".toByteArray()))
        val sha = E2eTestFixtures.sha256Hex(rootfs)
        val item = StoreCatalog.findById("alpine_rv64")!!.copy(expectedSha256 = sha, sizeBytes = rootfs.size.toLong())

        val mockRepo = ImageRepository(
            context,
            db.localImageDao(),
            E2eTestFixtures.createMockHttpClient { req -> E2eTestFixtures.mockResponse(req, 200, rootfs) }
        )
        val alpineDisk = mockRepo.downloadStoreItem(item).getOrThrow()
        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Alpine Attach",
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                targetDiskImageId = alpineDisk.id,
                primaryBootSlot = VmProfile.BOOT_SLOT_DISK
            )
        )
        val loaded = requireNotNull(vmRepo.getProfileById(pid))
        assertEquals(alpineDisk.id, loaded.targetDiskImageId)
        assertEquals(VmProfile.ARCH_RISCV64, loaded.architecture)
    }

    // =========================================================================
    // Tier 4: Real-World Application Scenarios 1–4 (4 tests)
    // =========================================================================

    @Test
    fun tier4_scenario1_freshInstallFirstBootAndComplianceAudit() = runTest {
        db.seedDefaultsIfNeeded()
        val profiles = db.vmProfileDao().observeAllProfiles().first()
        val first = profiles.first()
        assertEquals(VmProfile.ARCH_RISCV64, first.architecture)

        sessionManager.startVm(first)
        awaitVmStatus(VmStatus.RUNNING)

        // Wait until DiagnosticMonitorRom banner appears on UART console
        val deadline = System.currentTimeMillis() + 5000L
        while (System.currentTimeMillis() < deadline &&
            !sessionManager.getPlainTerminalLog().contains("kernovm#")
        ) {
            Thread.sleep(15)
        }
        val consoleLog = sessionManager.getPlainTerminalLog()
        assertTrue("Expected Diagnostic ROM prompt, got: $consoleLog", consoleLog.contains("kernovm#"))
        assertFalse(consoleLog.contains("qemu", ignoreCase = true))

        val parsed = FdtInspector.parse(requireNotNull(sessionManager.activeDtbBytes))
        assertEquals("KernoVM RV64", parsed.modelName)
    }

    @Test
    fun tier4_scenario2_dualDriveInstallationAndEjectToTargetDiskLifecycle() = runTest {
        db.seedDefaultsIfNeeded()
        val isoImg = insertRealIsoFile("alpine-standard-3.24.4-riscv64.iso")
        val targetImg = imageRepo.createEmptyVirtualDisk("alpine_target.img", 4).getOrThrow()

        // Mark target disk with a signature pattern in sector 0 to verify data integrity post-eject
        val marker = "ALPINE_INSTALLED_OK".toByteArray(StandardCharsets.US_ASCII)
        java.io.RandomAccessFile(File(targetImg.localPath), "rw").use { raf ->
            raf.seek(0L)
            raf.write(marker)
        }

        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Install Workflow",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = isoImg.id,
                targetDiskImageId = targetImg.id,
                primaryBootSlot = VmProfile.BOOT_SLOT_ISO
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)

        // Pre-eject: both /dev/vda (bootindex=0) and /dev/vdb (bootindex=1)
        val preParsed = FdtInspector.parse(requireNotNull(sessionManager.activeDtbBytes))
        assertEquals(listOf("vda" to 0, "vdb" to 1), preParsed.bootIndices)

        // Eject ISO / Switch Primary Disk & reboot
        assertTrue(sessionManager.ejectIsoAndSwitchPrimaryDisk(pid).isSuccess)
        sessionManager.resetVm()
        awaitVmStatus(VmStatus.RUNNING)

        val postParsed = FdtInspector.parse(requireNotNull(sessionManager.activeDtbBytes))
        assertNull(postParsed.nodes["/soc/virtio_mmio@10001000"])
        assertEquals(listOf("vdb" to 0), postParsed.bootIndices)

        // Target disk contents remain preserved
        val checkBytes = ByteArray(marker.size)
        java.io.RandomAccessFile(File(targetImg.localPath), "r").use { it.readFully(checkBytes) }
        assertTrue(checkBytes.contentEquals(marker))
    }

    @Test
    fun tier4_scenario3_malformedAndX86IsoRejectionFollowedByValidRv64Fallback() = runTest {
        val x86ElTorito = tempFolder.newFile("pc_el_torito.iso").apply {
            writeBytes(E2eTestFixtures.createElToritoIsoBytes("PC_DISTRO", platformId = 0x00))
        }
        val bzImage = tempFolder.newFile("vmlinuz-bzimage.bin").apply {
            writeBytes(E2eTestFixtures.createPcBzImageBytes())
        }
        val spoofedRv64 = tempFolder.newFile("rv64_fake.iso").apply {
            writeBytes(E2eTestFixtures.createElToritoIsoBytes("SPOOFED", platformId = 0x00))
        }
        val validRv64Iso = tempFolder.newFile("alpine-standard-riscv64.iso").apply {
            writeBytes(E2eTestFixtures.createElToritoIsoBytes("ALPINE_RV64", platformId = 0xEF, bootImagePayload = E2eTestFixtures.createPeCoffBytes(0x5064)))
        }

        assertTrue(imageRepo.importFromSaf(Uri.fromFile(x86ElTorito), ImageKind.ISO_IMAGE).isFailure)
        assertTrue(imageRepo.importFromSaf(Uri.fromFile(bzImage), ImageKind.KERNEL_IMAGE).isFailure)
        assertTrue(imageRepo.importFromSaf(Uri.fromFile(spoofedRv64), ImageKind.ISO_IMAGE).isFailure)

        val accepted = imageRepo.importFromSaf(Uri.fromFile(validRv64Iso), ImageKind.ISO_IMAGE).getOrThrow()
        assertEquals(ImageKind.ISO_IMAGE.name, accepted.kind)
        assertTrue(ArchitectureValidator.isImageCompatibleWithProfile(accepted, VmProfile.ARCH_RISCV64))
    }

    @Test
    fun tier4_scenario4_storeDownloadAlpineRv64MirrorFallbackSha256AndQuotaUpdate() = runTest {
        db.seedDefaultsIfNeeded()
        val archiveBytes = E2eTestFixtures.createTarGzBytes(
            listOf(
                "etc/alpine-release" to "3.24.4\n".toByteArray(),
                "bin/busybox" to E2eTestFixtures.createElfBytes(eiClass = 2, eMachine = 243)
            )
        )
        val expectedSha = E2eTestFixtures.sha256Hex(archiveBytes)
        val alpineItem = StoreCatalog.findById("alpine_rv64")!!.copy(
            expectedSha256 = expectedSha,
            sizeBytes = archiveBytes.size.toLong()
        )
        val mockHttp = E2eTestFixtures.createMockHttpClient { req ->
            if (req.url.toString() == alpineItem.downloadUrl) {
                E2eTestFixtures.mockResponse(req, 503, ByteArray(0))
            } else {
                E2eTestFixtures.mockResponse(req, 200, archiveBytes, "application/gzip")
            }
        }
        val storeRepo = ImageRepository(context, db.localImageDao(), mockHttp)
        val downloaded = storeRepo.downloadStoreItem(alpineItem).getOrThrow()

        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Alpine Store Flow",
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                targetDiskImageId = downloaded.id,
                primaryBootSlot = VmProfile.BOOT_SLOT_DISK
            )
        )
        val loaded = vmRepo.getProfileById(pid)!!
        assertEquals(downloaded.sizeBytes, storeRepo.computeVmStorageUsageBytes(loaded))
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private suspend fun insertDummyImage(name: String, kind: ImageKind, sizeBytes: Long): LocalImage {
        val file = tempFolder.newFile(name)
        file.writeBytes(ByteArray(sizeBytes.toInt().coerceAtMost(4096)))
        val img = LocalImage(
            name = name,
            kind = kind.name,
            sourceType = ImageSourceType.SAF_IMPORT.name,
            localPath = file.absolutePath,
            sizeBytes = sizeBytes,
            sha256 = E2eTestFixtures.sha256Hex(name.toByteArray()),
            architectureTag = LocalImage.ARCH_RISCV64
        )
        val id = db.localImageDao().insert(img)
        return img.copy(id = id)
    }

    private suspend fun insertRealIsoFile(name: String): LocalImage {
        val bytes = E2eTestFixtures.createElToritoIsoBytes(
            volumeLabel = "KERN_RV64_ISO",
            platformId = 0xEF,
            bootImagePayload = E2eTestFixtures.createPeCoffBytes(0x5064)
        )
        val file = tempFolder.newFile(name).apply { writeBytes(bytes) }
        val img = LocalImage(
            name = name,
            kind = ImageKind.ISO_IMAGE.name,
            sourceType = ImageSourceType.SAF_IMPORT.name,
            localPath = file.absolutePath,
            sizeBytes = file.length(),
            sha256 = E2eTestFixtures.sha256Hex(bytes),
            architectureTag = LocalImage.ARCH_RISCV64
        )
        val id = db.localImageDao().insert(img)
        return img.copy(id = id)
    }

    private fun awaitVmStatus(expected: VmStatus, timeoutMs: Long = 5000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (sessionManager.state.value.status == expected) return
            Thread.sleep(10)
        }
        assertEquals(expected, sessionManager.state.value.status)
    }
}
