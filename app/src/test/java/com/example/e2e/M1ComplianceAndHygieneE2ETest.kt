package com.example.e2e

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.DistroPresetCatalog
import com.example.data.ImageKind
import com.example.data.ImageRepository
import com.example.data.ImageSourceType
import com.example.data.KernoDatabase
import com.example.data.LocalImage
import com.example.data.StoreCatalog
import com.example.data.StoreCategory
import com.example.data.VmProfile
import com.example.data.VmRepository
import com.example.emulator.AnsiTerminalBuffer
import com.example.emulator.DiagnosticMonitorRom
import com.example.emulator.Rv32ImaCpuCore
import com.example.emulator.Uart8250
import com.example.emulator.VirtioBlockDevice
import com.example.emulator.VirtioNetDevice
import com.example.ui.screens.StoreScreen
import com.example.ui.screens.VmEditorDialog
import com.example.ui.theme.KernoVmTheme
import com.example.ui.viewmodel.ImageViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase B1 Opaque-Box E2E Suite (30 tests: 15 Tier 1 + 15 Tier 2) covering:
 * - Feature #2: Remove QEMU Engine & Catalog References
 * - Feature #3: Replace QEMU Vendor ID ("KERN" = 0x4E52454B) & Clean Comments
 * - Feature #4: Remove GPL Traces & Add Apache-2.0 Attributions
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class M1ComplianceAndHygieneE2ETest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: KernoDatabase
    private lateinit var vmRepo: VmRepository
    private lateinit var imageRepo: ImageRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = E2eTestFixtures.createInMemoryDatabase(context)
        vmRepo = VmRepository(db.vmProfileDao(), maxHeapProvider = { 512L * 1024L * 1024L })
        imageRepo = ImageRepository(context, db.localImageDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // =========================================================================
    // Feature #2 — Remove QEMU Engine & Catalog References (5 T1 + 5 T2)
    // =========================================================================

    @Test
    fun f2_t1_storeCatalogHasOnlyPermissiveRiscvCategoriesAndNoQemu() {
        val categoryNames = StoreCategory.entries.map { it.name }.toSet()
        assertFalse("StoreCategory must not contain ENGINE", "ENGINE" in categoryNames)
        assertTrue("StoreCategory must include ISO", StoreCategory.ISO in StoreCategory.entries)
        assertTrue("StoreCategory must include KERNEL", StoreCategory.KERNEL in StoreCategory.entries)

        val items = StoreCatalog.items
        assertTrue("StoreCatalog.items must not be empty", items.isNotEmpty())
        assertNotNull("Built-in diagnostic ROM must exist", StoreCatalog.findById("kernel_diag_rom"))

        for (item in items) {
            assertTrue(
                "StoreItem ${item.id} must target RISC-V, was '${item.architecture}'",
                item.architecture.startsWith("riscv", ignoreCase = true)
            )
            val combined = listOf(
                item.id,
                item.name,
                item.description,
                item.filename,
                item.architecture,
                item.downloadUrl
            ).joinToString(" ")
            assertFalse("StoreItem ${item.id} must not reference QEMU", combined.contains("qemu", ignoreCase = true))
            assertFalse("StoreItem ${item.id} must not reference x86", combined.contains("x86", ignoreCase = true))
            assertFalse("StoreItem ${item.id} must not reference amd64", combined.contains("amd64", ignoreCase = true))
        }
    }

    @Test
    fun f2_t1_vmProfileDefaultsAndConstantsExcludeQemuAndX86() {
        val profile = VmProfile(name = "Compliance Check", kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID)
        assertTrue(
            "Default VmProfile architecture must be RISC-V, was '${profile.architecture}'",
            profile.architecture.startsWith("riscv")
        )
        assertNotEquals("x86_64", profile.architecture)
        assertEquals(VmProfile.ENGINE_KERNO_KOTLIN, profile.engineType)

        val companionFields = VmProfile.Companion::class.java.declaredFields
        val outerFields = VmProfile::class.java.declaredFields
        val allFieldNames = (companionFields + outerFields).map { it.name }.toSet()
        assertFalse("ENGINE_QEMU field must not exist", "ENGINE_QEMU" in allFieldNames)
        assertFalse("ARCH_X86_64 field must not exist", "ARCH_X86_64" in allFieldNames)

        for (field in outerFields) {
            if (field.type == String::class.java && java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                field.isAccessible = true
                val value = field.get(null) as? String ?: continue
                assertFalse("Constant ${field.name} must not contain 'qemu': $value", value.contains("qemu", ignoreCase = true))
                assertFalse("Constant ${field.name} must not contain 'x86': $value", value.contains("x86", ignoreCase = true))
            }
        }
    }

    @Test
    fun f2_t1_seededDatabaseProfilesUseKernoKotlinAndRiscv() = runTest {
        db.seedDefaultsIfNeeded()
        val seeded = db.vmProfileDao().observeAllProfiles().first()
        assertTrue("Expected at least 2 seeded profiles", seeded.size >= 2)

        for (profile in seeded) {
            assertEquals(VmProfile.ENGINE_KERNO_KOTLIN, profile.engineType)
            assertTrue(
                "Seeded profile '${profile.name}' must target RISC-V, was '${profile.architecture}'",
                profile.architecture.startsWith("riscv")
            )
            assertNotEquals("x86_64", profile.architecture)
            assertFalse("Seeded profile name must not mention QEMU", profile.name.contains("qemu", ignoreCase = true))
            assertFalse("Seeded profile description must not mention QEMU", profile.description.contains("qemu", ignoreCase = true))
            assertFalse("Seeded profile name must not mention x86", profile.name.contains("x86", ignoreCase = true))
        }
    }

    @Test
    fun f2_t1_storeScreenRendersTabsWithoutQemuOrEngineTab() {
        val viewModel = ImageViewModel(imageRepo)
        composeRule.setContent {
            KernoVmTheme {
                StoreScreen(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("store_tab_iso").assertIsDisplayed()
        composeRule.onNodeWithTag("store_tab_kernel").assertIsDisplayed()

        assertEquals(
            0,
            composeRule.onAllNodesWithText("QEMU", substring = true, ignoreCase = true).fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithText("x86_64", substring = true, ignoreCase = true).fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithText("Engine", substring = true, ignoreCase = true).fetchSemanticsNodes().size
        )

        if (StoreCatalog.isoItems.isEmpty()) {
            composeRule.onNodeWithTag("store_empty_state").assertIsDisplayed()
        } else {
            for (iso in StoreCatalog.isoItems) {
                assertTrue(iso.architecture.startsWith("riscv", ignoreCase = true))
                assertFalse(iso.name.contains("qemu", ignoreCase = true))
            }
            val firstIso = StoreCatalog.isoItems.first()
            composeRule.onNodeWithTag("store_item_${firstIso.id}").assertIsDisplayed()
        }

        composeRule.onNodeWithTag("store_tab_kernel").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("store_item_kernel_diag_rom").assertIsDisplayed()
    }

    @Test
    fun f2_t1_vmEditorDialogExposesOnlyRiscvArchChipAndNoQemu() {
        val builtinRom = LocalImage(
            id = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
            name = "KernoVM Diagnostic ROM",
            kind = ImageKind.BUILTIN_ROM.name,
            sourceType = ImageSourceType.BUILTIN.name,
            localPath = "",
            sizeBytes = DiagnosticMonitorRom.binary.size.toLong(),
            sha256 = DiagnosticMonitorRom.sha256
        )

        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = null,
                    availableKernels = listOf(builtinRom),
                    availableDtbs = emptyList(),
                    availableIsos = emptyList(),
                    canAllocate128Mb = true,
                    maxHeapMb = 512,
                    maxSupportedCpuThreads = 4,
                    inspectedDtbSummary = null,
                    onInspectCustomDtb = {},
                    onDismiss = {},
                    onSave = {}
                )
            }
        }
        composeRule.waitForIdle()

        val hasRv32Chip = composeRule.onAllNodesWithTag("arch_chip_riscv32").fetchSemanticsNodes().isNotEmpty()
        val hasRv64Chip = composeRule.onAllNodesWithTag("arch_chip_riscv64").fetchSemanticsNodes().isNotEmpty()
        assertTrue("Expected at least one RISC-V architecture chip in VmEditorDialog", hasRv32Chip || hasRv64Chip)

        assertEquals(
            "arch_chip_x86_64 must not exist in VmEditorDialog",
            0,
            composeRule.onAllNodesWithTag("arch_chip_x86_64").fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithText("QEMU", substring = true, ignoreCase = true).fetchSemanticsNodes().size
        )
        assertEquals(
            0,
            composeRule.onAllNodesWithText("x86_64", substring = true, ignoreCase = true).fetchSemanticsNodes().size
        )
    }

    @Test
    fun f2_t2_vmEditorCoercesLegacyX8664AndQemuProfileOnSave() {
        val legacyProfile = VmProfile(
            id = 77L,
            name = "Legacy Profile",
            kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
            architecture = "x86_64",
            engineType = "qemu"
        )
        var captured: VmProfile? = null

        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = legacyProfile,
                    availableKernels = emptyList(),
                    availableDtbs = emptyList(),
                    availableIsos = emptyList(),
                    canAllocate128Mb = true,
                    maxHeapMb = 512,
                    maxSupportedCpuThreads = 4,
                    inspectedDtbSummary = null,
                    onInspectCustomDtb = {},
                    onDismiss = {},
                    onSave = { captured = it }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("vm_editor_save_button").performClick()
        composeRule.waitForIdle()

        val saved = captured
        assertNotNull("Expected onSave callback to fire", saved)
        requireNotNull(saved)
        assertEquals(77L, saved.id)
        assertTrue("Saved architecture must be coerced to RISC-V, was '${saved.architecture}'", saved.architecture.startsWith("riscv"))
        assertNotEquals("x86_64", saved.architecture)
        assertEquals(VmProfile.ENGINE_KERNO_KOTLIN, saved.engineType)
    }

    @Test
    fun f2_t2_vmEditorBlankNameAndUnknownArchPersistSanitizedInRoom() = runTest {
        val malformedProfile = VmProfile(
            id = 0L,
            name = "   ",
            kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
            architecture = "amd64",
            engineType = "qemu_legacy"
        )
        var captured: VmProfile? = null

        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = malformedProfile,
                    availableKernels = emptyList(),
                    availableDtbs = emptyList(),
                    availableIsos = emptyList(),
                    canAllocate128Mb = true,
                    maxHeapMb = 512,
                    maxSupportedCpuThreads = 4,
                    inspectedDtbSummary = null,
                    onInspectCustomDtb = {},
                    onDismiss = {},
                    onSave = { captured = it }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("vm_editor_save_button").performClick()
        composeRule.waitForIdle()

        val saved = requireNotNull(captured)
        val rowId = vmRepo.saveProfile(saved)
        val reloaded = vmRepo.getProfileById(rowId)
        assertNotNull(reloaded)
        requireNotNull(reloaded)

        assertTrue("Blank name must default to a non-blank VM name", reloaded.name.isNotBlank())
        assertFalse(reloaded.name.contains("qemu", ignoreCase = true))
        assertTrue(reloaded.architecture.startsWith("riscv"))
        assertEquals(VmProfile.ENGINE_KERNO_KOTLIN, reloaded.engineType)
    }

    @Test
    fun f2_t2_storeCatalogNegativeAndBoundaryLookupsReturnNull() {
        val invalidIds = listOf(
            "engine_qemu_x86_64",
            "iso_alpine_x86",
            "",
            "   ",
            "KERNEL_DIAG_ROM",
            "kernel_diag_rom "
        )
        for (id in invalidIds) {
            assertNull("Expected null for lookup '$id'", StoreCatalog.findById(id))
        }
    }

    @Test
    fun f2_t2_distroPresetCatalogContainsZeroQemuOrX86AndValidBuildrootRecipe() {
        assertTrue(DistroPresetCatalog.presets.isNotEmpty())
        for (preset in DistroPresetCatalog.presets) {
            val allText = listOf(
                preset.id,
                preset.name,
                preset.description,
                preset.kernelVersion,
                preset.sourceRepoUrl,
                preset.downloadUrl,
                preset.kernelConfigNotes
            ).joinToString(" ")
            assertFalse("Preset ${preset.id} must not mention QEMU", allText.contains("qemu", ignoreCase = true))
            assertFalse("Preset ${preset.id} must not mention x86", allText.contains("x86", ignoreCase = true))
        }

        val diag = DistroPresetCatalog.presets.firstOrNull { it.id == "builtin_diag_rom" }
        assertNotNull(diag)
        assertTrue(diag!!.allDownloadCandidateUrls().isEmpty())

        val recipe = DistroPresetCatalog.BUILDROOT_CONFIG_RECIPE
        assertTrue("Buildroot recipe must configure RISC-V", recipe.contains("CONFIG_RISCV"))
        assertFalse("Buildroot recipe must not reference QEMU", recipe.contains("QEMU", ignoreCase = true))
        assertFalse("Buildroot recipe must not reference x86", recipe.contains("X86", ignoreCase = true))
    }

    @Test
    fun f2_t2_mainSourcesResourcesAndDocsContainZeroQemuOrX86Tokens() {
        val forbiddenRegex = Regex(
            """(\bqemu\b|x86_64|\bx86\b|\bamd64\b|\bi386\b|\bi686\b|\bia32\b|0x554d4551|engine_qemu)""",
            RegexOption.IGNORE_CASE
        )
        val targets = E2eTestFixtures.collectTextFilesUnder(E2eTestFixtures.mainSourceDir(), excludeE2e = true) +
            E2eTestFixtures.rootProjectAuditFiles()
        assertTrue("Expected main and root project files to scan", targets.isNotEmpty())

        val violations = ArrayList<String>()
        for (file in targets) {
            val text = file.readText()
            val match = forbiddenRegex.find(text)
            if (match != null) {
                violations.add("${file.name}: matched '${match.value}'")
            }
        }
        assertTrue("Found forbidden QEMU/x86 tokens: $violations", violations.isEmpty())
    }

    // =========================================================================
    // Feature #3 — Replace QEMU Vendor ID & Clean Comments (5 T1 + 5 T2)
    // =========================================================================

    @Test
    fun f3_t1_virtioBlockAndNetExposeKernVendorIdAt00C() {
        val expectedKern = 0x4E52454B // "KERN" little-endian
        val legacyQemu = 0x554D4551   // "QEMU"

        assertEquals(expectedKern, VirtioBlockDevice.VIRTIO_VENDOR_ID)
        assertEquals(expectedKern, VirtioNetDevice.VIRTIO_VENDOR_ID)
        assertNotEquals(legacyQemu, VirtioBlockDevice.VIRTIO_VENDOR_ID)
        assertNotEquals(legacyQemu, VirtioNetDevice.VIRTIO_VENDOR_ID)

        val backingFile = E2eTestFixtures.createTempBackingFile(tempFolder, "vendor_blk.img")
        VirtioBlockDevice(backingFile, readOnly = true).use { blk ->
            val net = VirtioNetDevice()
            assertEquals(expectedKern, blk.readRegister(0x00C))
            assertEquals(expectedKern, net.readRegister(0x00C))
        }
    }

    @Test
    fun f3_t1_cpuCoreMmioLoads00CFromBothVirtioDevicesAsKern() {
        val backingFile = E2eTestFixtures.createTempBackingFile(tempFolder, "mmio_blk.img")
        VirtioBlockDevice(backingFile, readOnly = true).use { blk ->
            val net = VirtioNetDevice()
            val ram = IntArray((64 * 1024) ushr 2)
            val cpu = Rv32ImaCpuCore(ram, Uart8250(), virtioBlk = blk, virtioNet = net)

            val rom = E2eTestFixtures.assembleMmioReadAndPoweroff(
                mmioReads = listOf(
                    (VirtioBlockDevice.BASE_ADDRESS + 0x00C) to 0x2000,
                    (VirtioNetDevice.BASE_ADDRESS + 0x00C) to 0x2004
                )
            )
            cpu.loadBytesIntoRam(rom, 0)
            cpu.reset(entryPc = Rv32ImaCpuCore.RAM_BASE)

            val status = cpu.stepBatch(10_000)
            assertEquals(Rv32ImaCpuCore.BATCH_POWEROFF, status)
            assertEquals(0x4E52454B, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2000))
            assertEquals(0x4E52454B, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2004))
        }
    }

    @Test
    fun f3_t1_virtioDevicesPreserveKernVendorIdAcrossStatusAndReset() {
        val backingFile = E2eTestFixtures.createTempBackingFile(tempFolder, "reset_blk.img")
        VirtioBlockDevice(backingFile, readOnly = false).use { blk ->
            val net = VirtioNetDevice()
            val cpu = Rv32ImaCpuCore(IntArray((64 * 1024) ushr 2), Uart8250(), virtioBlk = blk, virtioNet = net)

            blk.writeRegister(0x070, 0x0F, cpu)
            net.writeRegister(0x070, 0x0F, cpu)
            assertEquals(0x0F, blk.readRegister(0x070))
            assertEquals(0x0F, net.readRegister(0x070))
            assertEquals(0x4E52454B, blk.readRegister(0x00C))
            assertEquals(0x4E52454B, net.readRegister(0x00C))

            // VirtIO reset via writing 0 to Status register (0x070)
            blk.writeRegister(0x070, 0, cpu)
            net.writeRegister(0x070, 0, cpu)
            assertEquals(0, blk.readRegister(0x070))
            assertEquals(0, net.readRegister(0x070))
            assertEquals(0x4E52454B, blk.readRegister(0x00C))
            assertEquals(0x4E52454B, net.readRegister(0x00C))

            // Full CPU reset also calls device reset()
            blk.writeRegister(0x070, 0x03, cpu)
            net.writeRegister(0x070, 0x03, cpu)
            cpu.reset()
            assertEquals(0, blk.readRegister(0x070))
            assertEquals(0, net.readRegister(0x070))
            assertEquals(0x4E52454B, blk.readRegister(0x00C))
            assertEquals(0x4E52454B, net.readRegister(0x00C))
        }
    }

    @Test
    fun f3_t1_virtioBlockRoAndRwModesShareKernVendorIdAndSpecIds() {
        val roFile = E2eTestFixtures.createTempBackingFile(tempFolder, "ro_disk.img")
        val rwFile = E2eTestFixtures.createTempBackingFile(tempFolder, "rw_disk.img")

        VirtioBlockDevice(roFile, readOnly = true).use { roBlk ->
            VirtioBlockDevice(rwFile, readOnly = false).use { rwBlk ->
                for (dev in listOf(roBlk, rwBlk)) {
                    assertEquals(0x74726976, dev.readRegister(0x000)) // "virt" magic
                    assertEquals(2, dev.readRegister(0x004))          // MMIO v2
                    assertEquals(2, dev.readRegister(0x008))          // Block device ID
                    assertEquals(0x4E52454B, dev.readRegister(0x00C)) // "KERN" vendor ID
                }
                val roBit = 1 shl 5
                assertTrue((roBlk.readRegister(0x010) and roBit) != 0)
                assertEquals(0, rwBlk.readRegister(0x010) and roBit)
            }
        }
    }

    @Test
    fun f3_t1_ansiTerminalBufferCleanRoomAndZeroTermuxInEmulator() {
        val term = AnsiTerminalBuffer(initialCols = 80, initialRows = 24)
        term.appendString("\u001B]0;KernoVM Console\u0007")
        term.appendString("\u001B[32mKERN\u001B[0m Ready")

        val snap = term.snapshot()
        assertEquals("KernoVM Console", snap.windowTitle)
        assertTrue(snap.plainText().contains("KERN Ready"))

        term.reset()
        val resetSnap = term.snapshot()
        assertEquals("", resetSnap.windowTitle)
        assertEquals("", resetSnap.plainText())

        val emulatorDir = File(E2eTestFixtures.mainSourceDir(), "java/com/example/emulator")
        val emulatorFiles = E2eTestFixtures.collectTextFilesUnder(emulatorDir, excludeE2e = true)
        assertTrue(emulatorFiles.isNotEmpty())
        val forbidden = Regex("""(termux|554d4551)""", RegexOption.IGNORE_CASE)
        for (file in emulatorFiles) {
            val match = forbidden.find(file.readText())
            assertNull("Unexpected token '${match?.value}' in ${file.name}", match)
        }
    }

    @Test
    fun f3_t2_detachedVirtioDevicesReturnZeroAt00CInCpuMmio() {
        val ram = IntArray((64 * 1024) ushr 2)
        val cpu = Rv32ImaCpuCore(ram, Uart8250(), virtioBlk = null, virtioNet = null)

        val rom = E2eTestFixtures.assembleMmioReadAndPoweroff(
            mmioReads = listOf(
                (VirtioBlockDevice.BASE_ADDRESS + 0x00C) to 0x2000,
                (VirtioNetDevice.BASE_ADDRESS + 0x00C) to 0x2004
            )
        )
        cpu.loadBytesIntoRam(rom, 0)
        cpu.reset(entryPc = Rv32ImaCpuCore.RAM_BASE)

        assertEquals(Rv32ImaCpuCore.BATCH_POWEROFF, cpu.stepBatch(10_000))
        assertEquals(0, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2000))
        assertEquals(0, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2004))
    }

    @Test
    fun f3_t2_subwordAndAdjacentMmioReadsAround00CAreExact() {
        val backingFile = E2eTestFixtures.createTempBackingFile(tempFolder, "subword_blk.img")
        VirtioBlockDevice(backingFile, readOnly = true).use { blk ->
            val net = VirtioNetDevice()
            val ram = IntArray((64 * 1024) ushr 2)
            val cpu = Rv32ImaCpuCore(ram, Uart8250(), virtioBlk = blk, virtioNet = net)

            // 0x4E52454B: byte 0 is 0x4B ('K'), halfword 0 is 0x454B ("KE")
            val rom = E2eTestFixtures.assembleSubwordMmioReadAndPoweroff(
                reads = listOf(
                    Triple(VirtioNetDevice.BASE_ADDRESS + 0x00C, 4, 0x2000), // LBU
                    Triple(VirtioNetDevice.BASE_ADDRESS + 0x00C, 5, 0x2004)  // LHU
                )
            )
            cpu.loadBytesIntoRam(rom, 0)
            cpu.reset(entryPc = Rv32ImaCpuCore.RAM_BASE)

            assertEquals(Rv32ImaCpuCore.BATCH_POWEROFF, cpu.stepBatch(10_000))
            assertEquals(0x4B, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2000))
            assertEquals(0x454B, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2004))

            for (offset in listOf(0x00F, 0x018, 0xFFC)) {
                assertEquals(0, blk.readRegister(offset))
                assertEquals(0, net.readRegister(offset))
            }
        }
    }

    @Test
    fun f3_t2_writesToVendorId00CAreIgnoredOnBothDevices() {
        val backingFile = E2eTestFixtures.createTempBackingFile(tempFolder, "ro_vendor.img")
        VirtioBlockDevice(backingFile, readOnly = true).use { blk ->
            val net = VirtioNetDevice()
            val ram = IntArray((64 * 1024) ushr 2)
            val cpu = Rv32ImaCpuCore(ram, Uart8250(), virtioBlk = blk, virtioNet = net)

            // Direct register writes
            blk.writeRegister(0x00C, 0x554D4551, cpu)
            net.writeRegister(0x00C, -1, cpu)
            assertEquals(0x4E52454B, blk.readRegister(0x00C))
            assertEquals(0x4E52454B, net.readRegister(0x00C))

            // CPU SW MMIO writes + readback
            val rom = E2eTestFixtures.assembleMmioWriteAndPoweroff(
                writes = listOf(
                    (VirtioBlockDevice.BASE_ADDRESS + 0x00C) to 0x554D4551,
                    (VirtioNetDevice.BASE_ADDRESS + 0x00C) to -1
                ),
                readBacks = listOf(
                    (VirtioBlockDevice.BASE_ADDRESS + 0x00C) to 0x2000,
                    (VirtioNetDevice.BASE_ADDRESS + 0x00C) to 0x2004
                )
            )
            cpu.loadBytesIntoRam(rom, 0)
            cpu.reset(entryPc = Rv32ImaCpuCore.RAM_BASE)

            assertEquals(Rv32ImaCpuCore.BATCH_POWEROFF, cpu.stepBatch(10_000))
            assertEquals(0x4E52454B, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2000))
            assertEquals(0x4E52454B, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2004))
        }
    }

    @Test
    fun f3_t2_virtioBlockZeroByteAndOneByteFilesPreserveKernVendorId() {
        val zeroFile = E2eTestFixtures.createTempBackingFile(tempFolder, "zero.img", sizeBytes = 0)
        val oneByteFile = E2eTestFixtures.createTempBackingFile(tempFolder, "one.img", sizeBytes = 1, fill = 0x7F)
        val cpu = Rv32ImaCpuCore(IntArray(1024), Uart8250())

        VirtioBlockDevice(zeroFile, readOnly = true).use { zeroBlk ->
            assertEquals(0L, zeroBlk.sectorCount)
            assertEquals(0, zeroBlk.readRegister(0x100))
            assertEquals(0x4E52454B, zeroBlk.readRegister(0x00C))
            zeroBlk.writeRegister(0x070, 0, cpu)
            assertEquals(0x4E52454B, zeroBlk.readRegister(0x00C))
        }

        VirtioBlockDevice(oneByteFile, readOnly = false).use { oneBlk ->
            assertEquals(1L, oneBlk.sectorCount)
            assertEquals(1, oneBlk.readRegister(0x100))
            assertEquals(0x4E52454B, oneBlk.readRegister(0x00C))
            oneBlk.writeRegister(0x070, 0, cpu)
            assertEquals(0x4E52454B, oneBlk.readRegister(0x00C))
        }
    }

    @Test
    fun f3_t2_ansiTerminalBufferClampsBoundsAndHandlesMalformedInput() {
        val tinyTerm = AnsiTerminalBuffer(initialCols = 5, initialRows = 2)
        assertEquals(20, tinyTerm.cols)
        assertEquals(8, tinyTerm.rows)

        tinyTerm.resize(newCols = 500, newRows = 300)
        assertEquals(240, tinyTerm.cols)
        assertEquals(120, tinyTerm.rows)

        // Overlong CSI (> 64 parameter bytes) + invalid UTF-8 bytes must not throw
        tinyTerm.appendString("\u001B[" + "1".repeat(80) + "mSAFE")
        tinyTerm.appendByte(0xFF)
        tinyTerm.appendByte(0x80)
        tinyTerm.appendString("OK")

        val text = tinyTerm.snapshot().plainText()
        assertTrue(text.contains("SAFE"))
        assertTrue(text.contains("OK"))

        tinyTerm.reset()
        assertEquals("", tinyTerm.snapshot().plainText())
    }

    // =========================================================================
    // Feature #4 — Remove GPL Traces & Add Apache-2.0 Attributions (5 T1 + 5 T2)
    // =========================================================================

    @Test
    fun f4_t1_licenseAndNoticeCarryValidApache2AndAttributions() {
        val root = E2eTestFixtures.resolveWorkspaceRoot()
        val licenseFile = File(root, "LICENSE")
        val noticeFile = File(root, "NOTICE")
        assertTrue("LICENSE must exist", licenseFile.isFile)
        assertTrue("NOTICE must exist", noticeFile.isFile)

        val licenseLines = licenseFile.readLines()
        val licenseText = licenseLines.joinToString("\n")
        assertEquals("Apache-2.0 LICENSE should have 202 lines", 202, licenseLines.size)
        assertTrue(licenseText.contains("Apache License"))
        assertTrue(licenseText.contains("Version 2.0, January 2004"))
        assertTrue(licenseText.contains("Copyright 2026 Iky969"))

        val noticeText = noticeFile.readText()
        for (expected in listOf(
            "KernoVM",
            "Copyright 2026 Iky969",
            "Apache License, Version 2.0",
            "mini-rv32ima",
            "CNLohr",
            "JetBrains Mono",
            "IBM Plex Sans",
            "SIL Open Font License, Version 1.1"
        )) {
            assertTrue("NOTICE missing required text: '$expected'", noticeText.contains(expected))
        }
    }

    @Test
    fun f4_t1_thirdPartyLicensesIncludesMitAndFullSilOfl11() {
        val root = E2eTestFixtures.resolveWorkspaceRoot()
        val thirdPartyFile = File(root, "THIRD_PARTY_LICENSES.md")
        assertTrue("THIRD_PARTY_LICENSES.md must exist", thirdPartyFile.isFile)
        val text = thirdPartyFile.readText()

        for (expected in listOf(
            "Copyright (c) 2022 CNLohr",
            "Permission is hereby granted, free of charge",
            "SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007",
            "PREAMBLE",
            "PERMISSION & CONDITIONS",
            "TERMINATION",
            "DISCLAIMER",
            "app/src/main/res/font/jetbrains_mono.ttf",
            "app/src/main/res/font/ibm_plex_sans.ttf"
        )) {
            assertTrue("THIRD_PARTY_LICENSES.md missing '$expected'", text.contains(expected))
        }
    }

    @Test
    fun f4_t1_readmeAndMetadataDeclareApache2WithoutGpl() {
        val root = E2eTestFixtures.resolveWorkspaceRoot()
        val readmeFile = File(root, "README.md")
        val metadataFile = File(root, "metadata.json")
        assertTrue(readmeFile.isFile)
        assertTrue(metadataFile.isFile)

        val readmeText = readmeFile.readText()
        for (expected in listOf("Apache License, Version 2.0", "mini-rv32ima", "NOTICE", "THIRD_PARTY_LICENSES.md")) {
            assertTrue("README.md missing '$expected'", readmeText.contains(expected))
        }

        val metadataText = metadataFile.readText()
        val json = JSONObject(metadataText)
        assertEquals("KernoVM", json.getString("name"))
        assertTrue(json.getString("description").isNotBlank())

        val forbidden = Regex("""\b(gpl|gplv2|gplv3|lgpl|agpl|copyleft|qemu)\b""", RegexOption.IGNORE_CASE)
        assertNull("README.md contains forbidden token", forbidden.find(readmeText))
        assertNull("metadata.json contains forbidden token", forbidden.find(metadataText))
    }

    @Test
    fun f4_t1_gradleDependenciesAlignWithAttributedApache2AndTestLicenses() {
        val root = E2eTestFixtures.resolveWorkspaceRoot()
        val appGradle = File(root, "app/build.gradle.kts").readText()
        val tomlText = File(root, "gradle/libs.versions.toml").readText()
        val thirdPartyText = File(root, "THIRD_PARTY_LICENSES.md").readText()

        val versions = parseTomlVersions(tomlText)
        val libraries = parseTomlLibraries(tomlText)
        val activeAliases = extractActiveGradleDependencyAliases(appGradle)
        assertTrue("Expected active Gradle dependencies in app/build.gradle.kts", activeAliases.isNotEmpty())

        for ((alias, config) in activeAliases) {
            val lib = libraries[alias]
                ?: error("Dependency alias '$alias' ($config) in app/build.gradle.kts missing from libs.versions.toml")
            val version = lib.versionRef?.let { ref ->
                versions[ref] ?: error("Version ref '$ref' for '$alias' missing from [versions]")
            }

            // Compose UI/Material libraries derive their version and attribution from compose-bom
            val documentedViaBom = lib.group in setOf(
                "androidx.compose.material3",
                "androidx.compose.material",
                "androidx.compose.ui"
            ) && lib.name !in setOf("ui-test-junit4", "ui-test-manifest", "ui-tooling")

            if (documentedViaBom) {
                assertTrue(thirdPartyText.contains("androidx.compose:compose-bom"))
            } else {
                assertTrue(
                    "Artifact '${lib.group}:${lib.name}' ($config) is not documented in THIRD_PARTY_LICENSES.md",
                    thirdPartyText.contains(lib.name)
                )
            }
            if (version != null) {
                assertTrue(
                    "Version '$version' for '${lib.group}:${lib.name}' is not documented in THIRD_PARTY_LICENSES.md",
                    thirdPartyText.contains(version)
                )
            }
        }
    }

    @Test
    fun f4_t1_catalogPresetsAndStringsArePermissiveAndGplFree() {
        val allowedLicenses = setOf(
            "Apache-2.0",
            "MIT",
            "Apache-2.0 / MIT",
            "MIT / Apache-2.0 (Bawaan)"
        )
        for (item in StoreCatalog.items) {
            assertTrue("Disallowed license '${item.license}' on StoreItem ${item.id}", item.license in allowedLicenses)
        }
        for (preset in DistroPresetCatalog.presets) {
            assertTrue("Disallowed license '${preset.license}' on preset ${preset.id}", preset.license in allowedLicenses)
        }

        val root = E2eTestFixtures.resolveWorkspaceRoot()
        val stringsXml = File(root, "app/src/main/res/values/strings.xml").readText()
        assertTrue(stringsXml.contains("<string name=\"app_name\">KernoVM</string>"))
        val forbidden = Regex("""\b(gpl|gplv2|gplv3|lgpl|agpl|copyleft|qemu)\b""", RegexOption.IGNORE_CASE)
        assertNull("strings.xml contains forbidden license/QEMU token", forbidden.find(stringsXml))
    }

    @Test
    fun f4_t2_staticScanFindsZeroGplTracesAcrossMainNonE2eTestsAndDocs() {
        val gplRegex = Regex(
            """\b(gpl|gplv2|gplv3|lgpl|lgplv2|lgplv3|agpl|copyleft|free\s+software\s+foundation|fsf)\b""",
            RegexOption.IGNORE_CASE
        )
        val auditFiles = E2eTestFixtures.collectAuditTextFiles()
        assertTrue("Audit file list must not be empty", auditFiles.isNotEmpty())

        val violations = ArrayList<String>()
        for (file in auditFiles) {
            val match = gplRegex.find(file.readText())
            if (match != null) {
                violations.add("${file.invariantSeparatorsPath}: '${match.value}'")
            }
        }
        assertTrue("GPL/copyleft traces detected: $violations", violations.isEmpty())
    }

    @Test
    fun f4_t2_licenseDocsContainZeroUnfilledPlaceholders() {
        val root = E2eTestFixtures.resolveWorkspaceRoot()
        val docNames = listOf("LICENSE", "NOTICE", "THIRD_PARTY_LICENSES.md", "README.md")
        val placeholderRegex = Regex(
            """(\[yyyy]|\[name of copyright owner]|<year>|<copyright\b|\bTODO\b|\bFIXME\b|\bTBD\b)""",
            RegexOption.IGNORE_CASE
        )

        for (docName in docNames) {
            val file = File(root, docName)
            assertTrue("Expected doc '$docName' to exist", file.isFile)
            val match = placeholderRegex.find(file.readText())
            assertNull("Unfilled placeholder '${match?.value}' found in $docName", match)
        }
    }

    @Test
    fun f4_t2_allBundledFontsHaveValidHeadersThemeRefsAndLicenseNotices() {
        val root = E2eTestFixtures.resolveWorkspaceRoot()
        val fontDir = File(root, "app/src/main/res/font")
        assertTrue(fontDir.isDirectory)

        val fontFiles = fontDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        assertTrue("Expected at least 2 bundled fonts, found ${fontFiles.size}", fontFiles.size >= 2)

        val typeKtText = File(root, "app/src/main/java/com/example/ui/theme/Type.kt").readText()
        val noticeText = File(root, "NOTICE").readText()
        val thirdPartyText = File(root, "THIRD_PARTY_LICENSES.md").readText()

        for (font in fontFiles) {
            val bytes = font.readBytes()
            assertTrue("Font ${font.name} is too small", bytes.size > 12)
            val magic = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            val isTtf = magic == 0x00010000
            val isOtf = magic == 0x4F54544F // "OTTO"
            assertTrue("Font ${font.name} lacks valid TTF/OTF magic: 0x${magic.toUInt().toString(16)}", isTtf || isOtf)

            val resRef = "R.font.${font.nameWithoutExtension}"
            assertTrue("Type.kt must reference $resRef", typeKtText.contains(resRef))
            assertTrue("NOTICE must mention ${font.name}", noticeText.contains(font.name))
            assertTrue("THIRD_PARTY_LICENSES.md must mention ${font.name}", thirdPartyText.contains(font.name))
        }
    }

    @Test
    fun f4_t2_allActiveBuildGradleCoordinatesAreDocumentedAndCopyleftFree() {
        val root = E2eTestFixtures.resolveWorkspaceRoot()
        val appGradle = File(root, "app/build.gradle.kts").readText()
        val tomlText = File(root, "gradle/libs.versions.toml").readText()
        val thirdPartyText = File(root, "THIRD_PARTY_LICENSES.md").readText()

        val libraries = parseTomlLibraries(tomlText)
        val activeAliases = extractActiveGradleDependencyAliases(appGradle)
        assertTrue(activeAliases.size >= 20)

        val forbiddenGroupTokens = listOf("gnu", "gpl", "lgpl", "qemu", "termux", "busybox", "ffmpeg")
        for ((alias, config) in activeAliases) {
            val lib = libraries[alias] ?: error("Missing '$alias' in libs.versions.toml")
            val coord = "${lib.group}:${lib.name}"
            for (token in forbiddenGroupTokens) {
                assertFalse("Forbidden token '$token' in active dependency '$coord'", coord.contains(token, ignoreCase = true))
            }
            val documentedViaBom = lib.group in setOf(
                "androidx.compose.material3",
                "androidx.compose.material",
                "androidx.compose.ui"
            ) && lib.name !in setOf("ui-test-junit4", "ui-test-manifest", "ui-tooling")

            val isDocumented = if (documentedViaBom) {
                thirdPartyText.contains("androidx.compose:compose-bom")
            } else {
                thirdPartyText.contains(lib.name)
            }
            assertTrue("Unattributed active dependency '$coord' ($config) missing from THIRD_PARTY_LICENSES.md", isDocumented)
        }
    }

    @Test
    fun f4_t2_nonE2eUnitTestsRemainScannedAndClean() {
        val nonE2eTests = E2eTestFixtures.nonE2eTestSourceFiles()
        assertTrue("Expected at least 11 non-E2E test files, found ${nonE2eTests.size}", nonE2eTests.size >= 11)

        val names = nonE2eTests.map { it.name }.toSet()
        assertTrue("StoreCatalogTest.kt" in names)
        assertTrue("AboutAndUpdateDialogTest.kt" in names)
        assertTrue("VmGuardsAndValidationTest.kt" in names)

        for (file in nonE2eTests) {
            assertFalse(
                "nonE2eTestSourceFiles must exclude e2e/: ${file.invariantSeparatorsPath}",
                file.invariantSeparatorsPath.contains("/com/example/e2e/")
            )
        }

        val forbidden = Regex(
            """(\b(gpl|gplv2|gplv3|lgpl|lgplv2|lgplv3|agpl|copyleft|qemu)\b|0x554d4551|554d4551)""",
            RegexOption.IGNORE_CASE
        )
        for (file in nonE2eTests) {
            val match = forbidden.find(file.readText())
            assertNull("Forbidden token '${match?.value}' in ${file.name}", match)
        }
    }

    // =========================================================================
    // Private TOML / Gradle Parsing Helpers
    // =========================================================================

    private data class TomlLib(
        val group: String,
        val name: String,
        val versionRef: String?
    )

    private fun parseTomlVersions(tomlText: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var inVersions = false
        val lineRegex = Regex("""^([a-zA-Z0-9_-]+)\s*=\s*"([^"]+)"""")
        for (rawLine in tomlText.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("[")) {
                inVersions = line == "[versions]"
                continue
            }
            if (inVersions && line.isNotEmpty() && !line.startsWith("#")) {
                lineRegex.find(line)?.let { m ->
                    map[m.groupValues[1]] = m.groupValues[2]
                }
            }
        }
        return map
    }

    private fun parseTomlLibraries(tomlText: String): Map<String, TomlLib> {
        val map = LinkedHashMap<String, TomlLib>()
        var inLibraries = false
        val lineRegex = Regex("""^([a-zA-Z0-9_-]+)\s*=\s*\{(.+)}""")
        val groupRegex = Regex("""group\s*=\s*"([^"]+)"""")
        val nameRegex = Regex("""name\s*=\s*"([^"]+)"""")
        val verRegex = Regex("""version\.ref\s*=\s*"([^"]+)"""")

        for (rawLine in tomlText.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("[")) {
                inLibraries = line == "[libraries]"
                continue
            }
            if (inLibraries && line.isNotEmpty() && !line.startsWith("#")) {
                lineRegex.find(line)?.let { m ->
                    val key = m.groupValues[1]
                    val body = m.groupValues[2]
                    val group = groupRegex.find(body)?.groupValues?.get(1) ?: return@let
                    val name = nameRegex.find(body)?.groupValues?.get(1) ?: return@let
                    val verRef = verRegex.find(body)?.groupValues?.get(1)
                    map[key] = TomlLib(group, name, verRef)
                }
            }
        }
        return map
    }

    private fun extractActiveGradleDependencyAliases(gradleText: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        val depRegex = Regex(
            """^\s*(implementation|testImplementation|androidTestImplementation|debugImplementation|"ksp")\((?:platform\()?(libs\.[a-zA-Z0-9_.]+)\)?\)"""
        )
        for (rawLine in gradleText.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("/*")) continue
            depRegex.find(trimmed)?.let { m ->
                val config = m.groupValues[1].trim('"')
                val alias = m.groupValues[2].removePrefix("libs.").replace('.', '-')
                result.add(alias to config)
            }
        }
        return result
    }
}
