package com.example.e2e

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.example.data.ImageKind
import com.example.data.ImageRepository
import com.example.data.ImageSourceType
import com.example.data.KernoDatabase
import com.example.data.LocalImage
import com.example.data.StoreCatalog
import com.example.data.VmProfile
import com.example.data.VmRepository
import com.example.emulator.DiagnosticMonitorRom
import com.example.emulator.FdtInspector
import com.example.ui.screens.AboutDialog
import com.example.ui.screens.ConsoleScreen
import com.example.ui.screens.StoreScreen
import com.example.ui.screens.VmEditorDialog
import com.example.ui.theme.KernoVmTheme
import com.example.ui.viewmodel.ConsoleViewModel
import com.example.ui.viewmodel.ImageViewModel
import com.example.ui.viewmodel.VmListViewModel
import com.example.vm.VmSessionManager
import com.example.vm.VmStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowLooper

/**
 * Phase B4 Opaque-Box E2E Suite (26 tests: 10 T1 + 10 T2 + 6 T3 pairwise) covering:
 * - Feature #12: UI Editor /dev/vdb Slot & RV64 Filtering (5 T1 + 5 T2)
 * - Feature #13: UI Eject ISO / Switch Primary Disk Button (5 T1 + 5 T2)
 * - Tier 3 Cross-Feature Pairwise Interactions (#2x#12, #4x#12, #9x#12, #11x#13, #7x#13, #10x#12)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class M4UiDualDriveEjectE2ETest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val composeRule = createComposeRule()

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
    // Feature #12 — UI Editor /dev/vdb Slot & RV64 Filtering (5 T1 + 5 T2)
    // =========================================================================

    @Test
    fun f12_t1_vmEditorDisplaysRv64ChipAndDualDriveIsoAndTargetDiskSlots() {
        val iso = dummyLocalImage(10L, "alpine-standard-riscv64.iso", ImageKind.ISO_IMAGE)
        val disk = dummyLocalImage(20L, "alpine-rv64-target.img", ImageKind.DISK_IMAGE)

        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = null,
                    availableKernels = listOf(builtinRomImage()),
                    availableDtbs = emptyList(),
                    availableIsos = listOf(iso),
                    availableTargetDisks = listOf(disk),
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

        composeRule.onNodeWithTag("arch_chip_riscv64").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("iso_none_chip").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("iso_attach_chip_10").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("target_disk_none_chip").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("target_disk_attach_chip_20").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun f12_t1_selectingIsoAndTargetDiskInEditorPopulatesSavedProfileFields() {
        val iso = dummyLocalImage(11L, "inst.iso", ImageKind.ISO_IMAGE)
        val disk = dummyLocalImage(22L, "target.img", ImageKind.DISK_IMAGE)
        var saved: VmProfile? = null

        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = null,
                    availableKernels = listOf(builtinRomImage()),
                    availableDtbs = emptyList(),
                    availableIsos = listOf(iso),
                    availableTargetDisks = listOf(disk),
                    canAllocate128Mb = true,
                    maxHeapMb = 512,
                    maxSupportedCpuThreads = 4,
                    inspectedDtbSummary = null,
                    onInspectCustomDtb = {},
                    onDismiss = {},
                    onSave = { saved = it }
                )
            }
        }
        composeRule.onNodeWithTag("vm_editor_name_input").performScrollTo().performTextReplacement("Dual RV64 VM")
        composeRule.onNodeWithTag("iso_attach_chip_11").performScrollTo().performClick()
        composeRule.onNodeWithTag("target_disk_attach_chip_22").performScrollTo().performClick()
        composeRule.onNodeWithTag("boot_priority_disk_chip").performScrollTo().performClick()
        composeRule.onNodeWithTag("vm_editor_save_button").performClick()
        composeRule.waitForIdle()

        val result = requireNotNull(saved)
        assertEquals("Dual RV64 VM", result.name)
        assertEquals(VmProfile.ARCH_RISCV64, result.architecture)
        assertEquals(11L, result.attachedIsoImageId)
        assertEquals(22L, result.targetDiskImageId)
        assertEquals(VmProfile.BOOT_SLOT_DISK, result.primaryBootSlot)
    }

    @Test
    fun f12_t1_storeScreenDisplaysAlpineRv64ItemAndDownloadButton() {
        val viewModel = ImageViewModel(imageRepo)
        composeRule.setContent {
            KernoVmTheme {
                StoreScreen(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("store_lazy_list").performScrollToNode(hasTestTag("store_item_alpine_rv64"))
        composeRule.onNodeWithTag("store_item_alpine_rv64").assertIsDisplayed()
        composeRule.onNodeWithTag("store_download_button_alpine_rv64").assertIsDisplayed()
        composeRule.onNodeWithText("Alpine RV64").assertIsDisplayed()
    }

    @Test
    fun f12_t1_aboutDialogDisplaysPureApacheMitAndSilOflWithZeroGpl() {
        composeRule.setContent {
            KernoVmTheme {
                AboutDialog(onDismiss = {}, onCheckUpdate = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("about_dialog").assertIsDisplayed()
        composeRule.onNodeWithText("Apache License 2.0", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("MIT License (Charles Lohr)", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("SIL OFL 1.1", substring = false).performScrollTo().assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("GPL", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("QEMU", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    @Test
    fun f12_t1_clearingIsoChipInEditorDetachesIsoWhileRetainingTargetDisk() {
        val iso = dummyLocalImage(31L, "inst.iso", ImageKind.ISO_IMAGE)
        val disk = dummyLocalImage(32L, "target.img", ImageKind.DISK_IMAGE)
        val initial = VmProfile(
            id = 5L,
            name = "Initial Dual",
            kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
            attachedIsoImageId = 31L,
            targetDiskImageId = 32L
        )
        var saved: VmProfile? = null

        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = initial,
                    availableKernels = listOf(builtinRomImage()),
                    availableDtbs = emptyList(),
                    availableIsos = listOf(iso),
                    availableTargetDisks = listOf(disk),
                    canAllocate128Mb = true,
                    maxHeapMb = 512,
                    maxSupportedCpuThreads = 4,
                    inspectedDtbSummary = null,
                    onInspectCustomDtb = {},
                    onDismiss = {},
                    onSave = { saved = it }
                )
            }
        }
        composeRule.onNodeWithTag("iso_none_chip").performScrollTo().performClick()
        composeRule.onNodeWithTag("vm_editor_save_button").performClick()
        composeRule.waitForIdle()

        val result = requireNotNull(saved)
        assertNull(result.attachedIsoImageId)
        assertEquals(32L, result.targetDiskImageId)
        assertEquals(VmProfile.BOOT_SLOT_DISK, result.primaryBootSlot)
    }

    @Test
    fun f12_t2_editorShowsEmptyTargetDiskGuidanceWhenNoTargetDisksAvailable() {
        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = null,
                    availableKernels = listOf(builtinRomImage()),
                    availableDtbs = emptyList(),
                    availableIsos = emptyList(),
                    availableTargetDisks = emptyList(),
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
        composeRule.onNodeWithTag("target_disk_none_chip").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Belum ada Virtual Disk Target", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun f12_t2_editorDismissCallbackFiresCleanlyWithoutSaving() {
        var dismissed = false
        var saved = false
        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = null,
                    availableKernels = emptyList(),
                    availableDtbs = emptyList(),
                    availableIsos = emptyList(),
                    canAllocate128Mb = true,
                    maxHeapMb = 512,
                    maxSupportedCpuThreads = 4,
                    inspectedDtbSummary = null,
                    onInspectCustomDtb = {},
                    onDismiss = { dismissed = true },
                    onSave = { saved = true }
                )
            }
        }
        composeRule.onNodeWithText("Batal").performClick()
        composeRule.waitForIdle()
        assertTrue(dismissed)
        assertTrue(!saved)
    }

    @Test
    fun f12_t2_editorZeroX86OrArmChipsRenderedEvenWhenEmptyListsPassed() {
        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = VmProfile(name = "Test", kernelImageId = -1L, architecture = "x86_64"),
                    availableKernels = emptyList(),
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
        assertEquals(0, composeRule.onAllNodesWithTag("arch_chip_x86_64").fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("arch_chip_riscv64").assertIsDisplayed()
    }

    @Test
    fun f12_t2_storeScreenDoesNotRenderAlpineRv32Anywhere() {
        val viewModel = ImageViewModel(imageRepo)
        composeRule.setContent {
            KernoVmTheme {
                StoreScreen(viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("Alpine RV32", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    @Test
    fun f12_t2_clearingTargetDiskChipWhileIsoRemainsRetainsIsoBootPriority() {
        val iso = dummyLocalImage(41L, "inst.iso", ImageKind.ISO_IMAGE)
        val disk = dummyLocalImage(42L, "target.img", ImageKind.DISK_IMAGE)
        var saved: VmProfile? = null
        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = VmProfile(
                        id = 6L,
                        name = "Drop Target",
                        kernelImageId = -1L,
                        attachedIsoImageId = 41L,
                        targetDiskImageId = 42L,
                        primaryBootSlot = VmProfile.BOOT_SLOT_ISO
                    ),
                    availableKernels = listOf(builtinRomImage()),
                    availableDtbs = emptyList(),
                    availableIsos = listOf(iso),
                    availableTargetDisks = listOf(disk),
                    canAllocate128Mb = true,
                    maxHeapMb = 512,
                    maxSupportedCpuThreads = 4,
                    inspectedDtbSummary = null,
                    onInspectCustomDtb = {},
                    onDismiss = {},
                    onSave = { saved = it }
                )
            }
        }
        composeRule.onNodeWithTag("target_disk_none_chip").performScrollTo().performClick()
        composeRule.onNodeWithTag("vm_editor_save_button").performClick()
        composeRule.waitForIdle()
        val res = requireNotNull(saved)
        assertEquals(41L, res.attachedIsoImageId)
        assertNull(res.targetDiskImageId)
        assertEquals(VmProfile.BOOT_SLOT_ISO, res.primaryBootSlot)
    }

    // =========================================================================
    // Feature #13 — UI Eject ISO / Switch Primary Disk Button (5 T1 + 5 T2)
    // =========================================================================

    @Test
    fun f13_t1_vmEditorRendersEjectIsoButtonWhenIsoAttachedAndInvokesCallback() {
        val iso = dummyLocalImage(51L, "alpine-standard-riscv64.iso", ImageKind.ISO_IMAGE)
        val disk = dummyLocalImage(52L, "target.img", ImageKind.DISK_IMAGE)
        var ejectedProfileId: Long? = null

        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = VmProfile(
                        id = 99L,
                        name = "Eject Test",
                        kernelImageId = -1L,
                        attachedIsoImageId = 51L,
                        targetDiskImageId = 52L
                    ),
                    availableKernels = listOf(builtinRomImage()),
                    availableDtbs = emptyList(),
                    availableIsos = listOf(iso),
                    availableTargetDisks = listOf(disk),
                    canAllocate128Mb = true,
                    maxHeapMb = 512,
                    maxSupportedCpuThreads = 4,
                    inspectedDtbSummary = null,
                    onInspectCustomDtb = {},
                    onDismiss = {},
                    onSave = {},
                    onEjectIsoAndSwitchPrimaryDisk = { ejectedProfileId = it }
                )
            }
        }
        composeRule.onNodeWithTag("eject_iso_button").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(99L, ejectedProfileId)
    }

    @Test
    fun f13_t1_ejectButtonInEditorClearsIsoChipEvenForUnsavedNewProfileDraft() {
        val iso = dummyLocalImage(61L, "inst.iso", ImageKind.ISO_IMAGE)
        val disk = dummyLocalImage(62L, "target.img", ImageKind.DISK_IMAGE)
        var saved: VmProfile? = null

        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = null,
                    availableKernels = listOf(builtinRomImage()),
                    availableDtbs = emptyList(),
                    availableIsos = listOf(iso),
                    availableTargetDisks = listOf(disk),
                    canAllocate128Mb = true,
                    maxHeapMb = 512,
                    maxSupportedCpuThreads = 4,
                    inspectedDtbSummary = null,
                    onInspectCustomDtb = {},
                    onDismiss = {},
                    onSave = { saved = it }
                )
            }
        }
        composeRule.onNodeWithTag("iso_attach_chip_61").performScrollTo().performClick()
        composeRule.onNodeWithTag("target_disk_attach_chip_62").performScrollTo().performClick()
        composeRule.onNodeWithTag("eject_iso_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("vm_editor_save_button").performClick()
        composeRule.waitForIdle()

        val result = requireNotNull(saved)
        assertNull(result.attachedIsoImageId)
        assertEquals(62L, result.targetDiskImageId)
        assertEquals(VmProfile.BOOT_SLOT_DISK, result.primaryBootSlot)
    }

    @Test
    fun f13_t1_consoleInspectorExposesEnabledEjectIsoButtonDuringDualDriveSession() = runTest {
        db.seedDefaultsIfNeeded()
        val iso = insertRealIsoFile("console_eject.iso")
        val disk = imageRepo.createEmptyVirtualDisk("console_target.img", 4).getOrThrow()
        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Console Eject",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = iso.id,
                targetDiskImageId = disk.id
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)

        val consoleVm = ConsoleViewModel(sessionManager)
        composeRule.setContent {
            KernoVmTheme {
                ConsoleScreen(viewModel = consoleVm, onNavigateBackToVms = {})
            }
        }
        composeRule.onNodeWithTag("console_inspector_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("eject_iso_button").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun f13_t1_editorHidesEjectButtonWhenNoIsoIsAttached() {
        val disk = dummyLocalImage(71L, "target.img", ImageKind.DISK_IMAGE)
        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = VmProfile(
                        id = 1L,
                        name = "Disk Only",
                        kernelImageId = -1L,
                        attachedIsoImageId = null,
                        targetDiskImageId = 71L
                    ),
                    availableKernels = emptyList(),
                    availableDtbs = emptyList(),
                    availableIsos = emptyList(),
                    availableTargetDisks = listOf(disk),
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
        assertEquals(0, composeRule.onAllNodesWithTag("eject_iso_button").fetchSemanticsNodes().size)
    }

    @Test
    fun f13_t1_editorHidesEjectButtonAfterLocalEjectClick() {
        val iso = dummyLocalImage(81L, "inst.iso", ImageKind.ISO_IMAGE)
        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = VmProfile(id = 1L, name = "Hide After Click", kernelImageId = -1L, attachedIsoImageId = 81L),
                    availableKernels = emptyList(),
                    availableDtbs = emptyList(),
                    availableIsos = listOf(iso),
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
        composeRule.onNodeWithTag("eject_iso_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithTag("eject_iso_button").fetchSemanticsNodes().size)
    }

    @Test
    fun f13_t2_consoleInspectorDisablesEjectButtonWhenNoIsoAttached() = runTest {
        db.seedDefaultsIfNeeded()
        val disk = imageRepo.createEmptyVirtualDisk("no_iso_target.img", 4).getOrThrow()
        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "No ISO",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = null,
                targetDiskImageId = disk.id
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)

        val consoleVm = ConsoleViewModel(sessionManager)
        composeRule.setContent {
            KernoVmTheme {
                ConsoleScreen(viewModel = consoleVm, onNavigateBackToVms = {})
            }
        }
        composeRule.onNodeWithTag("console_inspector_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("eject_iso_button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun f13_t2_consoleInspectorDisablesEjectButtonWhenVmStopped() {
        val consoleVm = ConsoleViewModel(sessionManager)
        composeRule.setContent {
            KernoVmTheme {
                ConsoleScreen(viewModel = consoleVm, onNavigateBackToVms = {})
            }
        }
        composeRule.onNodeWithTag("console_inspector_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("eject_iso_button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun f13_t2_ejectButtonWorksWhenEditorCallbackIsNullWithoutCrashing() {
        val iso = dummyLocalImage(91L, "safe.iso", ImageKind.ISO_IMAGE)
        composeRule.setContent {
            KernoVmTheme {
                VmEditorDialog(
                    initialProfile = VmProfile(id = 7L, name = "Null Cb", kernelImageId = -1L, attachedIsoImageId = 91L),
                    availableKernels = emptyList(),
                    availableDtbs = emptyList(),
                    availableIsos = listOf(iso),
                    canAllocate128Mb = true,
                    maxHeapMb = 512,
                    maxSupportedCpuThreads = 4,
                    inspectedDtbSummary = null,
                    onInspectCustomDtb = {},
                    onDismiss = {},
                    onSave = {},
                    onEjectIsoAndSwitchPrimaryDisk = null
                )
            }
        }
        composeRule.onNodeWithTag("eject_iso_button").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun f13_t2_consoleInspectorRendersBootOrderSummaryCleanlyPostEject() = runTest {
        db.seedDefaultsIfNeeded()
        val iso = insertRealIsoFile("post_eject_ui.iso")
        val disk = imageRepo.createEmptyVirtualDisk("post_eject_ui.img", 4).getOrThrow()
        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Inspector Sync",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = iso.id,
                targetDiskImageId = disk.id
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)

        val consoleVm = ConsoleViewModel(sessionManager)
        composeRule.setContent {
            KernoVmTheme {
                ConsoleScreen(viewModel = consoleVm, onNavigateBackToVms = {})
            }
        }
        openInspectorAndEjectIso()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("FDT order: vdb(bootindex=0)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun f13_t2_uiContainsZeroQemuStringsDuringBootIndexSwitch() = runTest {
        db.seedDefaultsIfNeeded()
        val iso = insertRealIsoFile("clean_ui_eject.iso")
        val disk = imageRepo.createEmptyVirtualDisk("clean_ui_disk.img", 4).getOrThrow()
        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Clean RV64 UI",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = iso.id,
                targetDiskImageId = disk.id
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)
        val consoleVm = ConsoleViewModel(sessionManager)
        composeRule.setContent {
            KernoVmTheme {
                ConsoleScreen(viewModel = consoleVm, onNavigateBackToVms = {})
            }
        }
        composeRule.onNodeWithTag("console_inspector_button").performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText("QEMU", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    // =========================================================================
    // Tier 3: Cross-Feature Pairwise Interactions (6 tests)
    // =========================================================================

    @Test
    fun tier3_f2xF12_storeAndEditorRenderZeroQemuStringsWhileExposingRv64AndAlpineRv64() {
        val imgVm = ImageViewModel(imageRepo)
        composeRule.setContent {
            KernoVmTheme {
                StoreScreen(viewModel = imgVm)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("store_item_alpine_rv64").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("QEMU", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Engine", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    @Test
    fun tier3_f4xF12_aboutDialogExposesApache2MitSilOflAndZeroGpl() {
        composeRule.setContent {
            KernoVmTheme {
                AboutDialog(onDismiss = {}, onCheckUpdate = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("KernoVM v0.1.0", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Apache License 2.0", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("mini-rv32ima", substring = false).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("SIL OFL 1.1", substring = false).performScrollTo().assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("GPL", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    @Test
    fun tier3_f9xF12_vmListViewModelFiltersOutIncompatibleNonRiscvImagesFromEditorLists() {
        val (goodIso, badId) = runBlocking {
            db.seedDefaultsIfNeeded()
            val good = insertRealIsoFile("compatible-riscv64.iso")
            val badX86 = tempFolder.newFile("incompatible-amd64.iso").apply {
                writeBytes(E2eTestFixtures.createElToritoIsoBytes("INCOMPAT_AMD64", platformId = 0x00))
            }
            val bad = db.localImageDao().insert(
                LocalImage(
                    name = "incompatible-amd64.iso",
                    kind = ImageKind.ISO_IMAGE.name,
                    sourceType = ImageSourceType.SAF_IMPORT.name,
                    localPath = badX86.absolutePath,
                    sizeBytes = badX86.length(),
                    sha256 = "0".repeat(64),
                    architectureTag = "amd64"
                )
            )
            good to bad
        }
        val viewModel = VmListViewModel(vmRepo, imageRepo, sessionManager)
        composeRule.setContent {
            KernoVmTheme {
                com.example.ui.screens.VmListScreen(viewModel = viewModel, onOpenConsole = {})
            }
        }
        composeRule.waitUntil(5000L) {
            ShadowLooper.idleMainLooper()
            viewModel.uiState.value.availableIsos.any { it.id == goodIso.id }
        }
        val emitted = viewModel.uiState.value
        assertEquals(listOf(goodIso.id), emitted.availableIsos.map { it.id })
        assertTrue(emitted.availableIsos.none { it.id == badId })
    }

    @Test
    fun tier3_f11xF13_ejectButtonUpdatesVmSessionManagerDtbAndRoomTogether() = runTest {
        db.seedDefaultsIfNeeded()
        val iso = insertRealIsoFile("full_sync.iso")
        val disk = imageRepo.createEmptyVirtualDisk("full_sync.img", 4).getOrThrow()
        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "Sync Eject",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = iso.id,
                targetDiskImageId = disk.id
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)

        val consoleVm = ConsoleViewModel(sessionManager)
        composeRule.setContent {
            KernoVmTheme {
                ConsoleScreen(viewModel = consoleVm, onNavigateBackToVms = {})
            }
        }
        openInspectorAndEjectIso {
            sessionManager.activeBlockDevicesSnapshot().first == null
        }
        val persisted = requireNotNull(vmRepo.getProfileById(pid))
        assertNull(persisted.attachedIsoImageId)
        assertEquals(VmProfile.BOOT_SLOT_DISK, persisted.primaryBootSlot)
        val parsed = FdtInspector.parse(requireNotNull(sessionManager.activeDtbBytes))
        assertNull(parsed.nodes["/soc/virtio_mmio@10001000"])
        assertEquals(listOf("vdb" to 0), parsed.bootIndices)
    }

    @Test
    fun tier3_f7xF13_switchingPrimaryDiskViaInspectorSetsVdbBootIndex0InFdt() = runTest {
        db.seedDefaultsIfNeeded()
        val iso = insertRealIsoFile("bootidx_iso.iso")
        val disk = imageRepo.createEmptyVirtualDisk("bootidx_disk.img", 4).getOrThrow()
        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "FDT Switch",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = iso.id,
                targetDiskImageId = disk.id,
                primaryBootSlot = VmProfile.BOOT_SLOT_ISO
            )
        )
        sessionManager.startVm(vmRepo.getProfileById(pid)!!)
        awaitVmStatus(VmStatus.RUNNING)
        assertEquals(listOf("vda" to 0, "vdb" to 1), FdtInspector.parse(sessionManager.activeDtbBytes!!).bootIndices)

        val consoleVm = ConsoleViewModel(sessionManager)
        composeRule.setContent {
            KernoVmTheme {
                ConsoleScreen(viewModel = consoleVm, onNavigateBackToVms = {})
            }
        }
        openInspectorAndEjectIso()

        assertEquals(listOf("vdb" to 0), FdtInspector.parse(sessionManager.activeDtbBytes!!).bootIndices)
    }

    @Test
    fun tier3_f10xF12_storeScreenDownloadTriggersImageViewModelAndRegistersAlpineRv64Locally() {
        val archiveBytes = E2eTestFixtures.createTarGzBytes(
            listOf("bin/busybox" to E2eTestFixtures.createElfBytes(2, 243))
        )
        val sha = E2eTestFixtures.sha256Hex(archiveBytes)
        val originalItem = StoreCatalog.findById("alpine_rv64")!!
        val mockRepo = ImageRepository(
            context,
            db.localImageDao(),
            E2eTestFixtures.createMockHttpClient { req ->
                E2eTestFixtures.mockResponse(req, 200, archiveBytes, "application/gzip")
            }
        )
        val imgVm = ImageViewModel(mockRepo)
        imgVm.downloadStoreItem(originalItem.copy(expectedSha256 = sha, sizeBytes = archiveBytes.size.toLong()))

        composeRule.setContent {
            KernoVmTheme {
                StoreScreen(viewModel = imgVm)
            }
        }
        composeRule.waitUntil(5000L) {
            ShadowLooper.idleMainLooper()
            imgVm.uiState.value.localImages.any { it.name == "Alpine RV64" }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("store_lazy_list").performScrollToNode(hasTestTag("store_item_alpine_rv64"))
        composeRule.onNodeWithTag("store_item_alpine_rv64").assertIsDisplayed()
        composeRule.onAllNodesWithText("Terpasang").onFirst().assertIsDisplayed()
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun builtinRomImage() = LocalImage(
        id = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
        name = "KernoVM RV32IMA Diagnostic ROM",
        kind = ImageKind.BUILTIN_ROM.name,
        sourceType = ImageSourceType.BUILTIN.name,
        localPath = "",
        sizeBytes = DiagnosticMonitorRom.binary.size.toLong(),
        sha256 = DiagnosticMonitorRom.sha256
    )

    private fun dummyLocalImage(id: Long, name: String, kind: ImageKind) = LocalImage(
        id = id,
        name = name,
        kind = kind.name,
        sourceType = ImageSourceType.SAF_IMPORT.name,
        localPath = "",
        sizeBytes = 4096L,
        sha256 = "a".repeat(64),
        architectureTag = LocalImage.ARCH_RISCV64
    )

    private suspend fun insertRealIsoFile(name: String): LocalImage {
        val bytes = E2eTestFixtures.createElToritoIsoBytes(
            volumeLabel = "UI_RV64_ISO",
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

    /** Opens the inspector bottom sheet, waits for settle, clicks Eject ISO, and drains MainLooper until detached. */
    private fun openInspectorAndEjectIso(extraPredicate: () -> Boolean = { true }) {
        composeRule.onNodeWithTag("console_inspector_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("eject_iso_button").performScrollTo().performClick()
        composeRule.waitUntil(5000L) {
            ShadowLooper.idleMainLooper()
            sessionManager.state.value.attachedIsoName == null && extraPredicate()
        }
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
