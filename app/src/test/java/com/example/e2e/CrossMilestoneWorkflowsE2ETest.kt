package com.example.e2e

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
import com.example.emulator.Rv32ImaCpuCore
import com.example.emulator.Uart8250
import com.example.emulator.VirtioBlockDevice
import com.example.emulator.VirtioBlockSlotConfig
import com.example.ui.screens.AboutDialog
import com.example.ui.screens.ConsoleScreen
import com.example.ui.screens.StoreScreen
import com.example.ui.screens.VmListScreen
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
import java.io.File

/**
 * Cross-Milestone Full Stack Workflows (Tier 4 Scenarios 5–7) & Tier 5 Adversarial Hardening Suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CrossMilestoneWorkflowsE2ETest {

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
    // Tier 4 Real-World Application Scenarios 5–7 (3 tests)
    // =========================================================================

    @Test
    fun tier4_scenario5_fullUiVmCreationDualDriveAttachmentAndRoomPersistence() = runBlocking {
        db.seedDefaultsIfNeeded()
        val isoBytes = E2eTestFixtures.createElToritoIsoBytes("WF5_ISO", platformId = 0xEF, bootImagePayload = E2eTestFixtures.createPeCoffBytes(0x5064))
        val isoFile = tempFolder.newFile("wf5_inst.iso").apply { writeBytes(isoBytes) }
        val isoId = db.localImageDao().insert(
            LocalImage(
                name = "wf5_inst.iso",
                kind = ImageKind.ISO_IMAGE.name,
                sourceType = ImageSourceType.SAF_IMPORT.name,
                localPath = isoFile.absolutePath,
                sizeBytes = isoFile.length(),
                sha256 = E2eTestFixtures.sha256Hex(isoBytes),
                architectureTag = LocalImage.ARCH_RISCV64
            )
        )
        val disk = imageRepo.createEmptyVirtualDisk("wf5_target.img", 4).getOrThrow()

        val listVm = VmListViewModel(vmRepo, imageRepo, sessionManager)
        composeRule.setContent {
            KernoVmTheme {
                VmListScreen(viewModel = listVm, onOpenConsole = {})
            }
        }
        composeRule.waitUntil(5000L) {
            listVm.uiState.value.availableIsos.any { it.id == isoId } &&
                listVm.uiState.value.availableTargetDisks.any { it.id == disk.id }
        }

        composeRule.onNodeWithTag("add_vm_fab").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("vm_editor_name_input").performScrollTo().performTextReplacement("Workstation E2E RV64")
        composeRule.onNodeWithTag("iso_attach_chip_$isoId").performScrollTo().performClick()
        composeRule.onNodeWithTag("target_disk_attach_chip_${disk.id}").performScrollTo().performClick()
        composeRule.onNodeWithTag("boot_priority_disk_chip").performScrollTo().performClick()
        composeRule.onNodeWithTag("vm_editor_save_button").performClick()

        composeRule.waitUntil(5000L) {
            runBlocking { db.vmProfileDao().observeAllProfiles().first().any { it.name == "Workstation E2E RV64" } }
        }
        val persisted = db.vmProfileDao().observeAllProfiles().first().first { it.name == "Workstation E2E RV64" }
        assertEquals(VmProfile.ARCH_RISCV64, persisted.architecture)
        assertEquals(isoId, persisted.attachedIsoImageId)
        assertEquals(disk.id, persisted.targetDiskImageId)
        assertEquals(VmProfile.BOOT_SLOT_DISK, persisted.primaryBootSlot)
    }

    @Test
    fun tier4_scenario6_fullUiStoreDownloadToAttachVdbToBootToLiveEjectIso() = runBlocking {
        db.seedDefaultsIfNeeded()
        val archiveBytes = E2eTestFixtures.createTarGzBytes(
            listOf("bin/busybox" to E2eTestFixtures.createElfBytes(2, 243))
        )
        val sha = E2eTestFixtures.sha256Hex(archiveBytes)
        val storeItem = StoreCatalog.findById("alpine_rv64")!!.copy(expectedSha256 = sha, sizeBytes = archiveBytes.size.toLong())

        val storeHttpRepo = ImageRepository(
            context,
            db.localImageDao(),
            E2eTestFixtures.createMockHttpClient { req -> E2eTestFixtures.mockResponse(req, 200, archiveBytes, "application/gzip") }
        )
        val downloadedDisk = storeHttpRepo.downloadStoreItem(storeItem).getOrThrow()

        val isoBytes = E2eTestFixtures.createElToritoIsoBytes("WF6_ISO", platformId = 0xEF, bootImagePayload = E2eTestFixtures.createPeCoffBytes(0x5064))
        val isoFile = tempFolder.newFile("wf6_inst.iso").apply { writeBytes(isoBytes) }
        val isoId = db.localImageDao().insert(
            LocalImage(
                name = "wf6_inst.iso",
                kind = ImageKind.ISO_IMAGE.name,
                sourceType = ImageSourceType.SAF_IMPORT.name,
                localPath = isoFile.absolutePath,
                sizeBytes = isoFile.length(),
                sha256 = E2eTestFixtures.sha256Hex(isoBytes),
                architectureTag = LocalImage.ARCH_RISCV64
            )
        )

        val pid = vmRepo.saveProfile(
            VmProfile(
                name = "EndToEnd Eject Flow",
                ramSizeMb = 16,
                kernelImageId = DiagnosticMonitorRom.BUILTIN_IMAGE_ID,
                attachedIsoImageId = isoId,
                targetDiskImageId = downloadedDisk.id
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
        composeRule.onNodeWithTag("eject_iso_button").performScrollTo().performClick()
        composeRule.waitUntil(5000L) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            sessionManager.state.value.attachedIsoName == null
        }

        val finalProfile = requireNotNull(vmRepo.getProfileById(pid))
        assertNull(finalProfile.attachedIsoImageId)
        assertEquals(downloadedDisk.id, finalProfile.targetDiskImageId)
        assertEquals(VmProfile.BOOT_SLOT_DISK, finalProfile.primaryBootSlot)

        val parsed = FdtInspector.parse(requireNotNull(sessionManager.activeDtbBytes))
        assertNull(parsed.nodes["/soc/virtio_mmio@10001000"])
        assertEquals(listOf("vdb" to 0), parsed.bootIndices)
    }

    @Test
    fun tier4_scenario7_uiLicenseAndStoreInspectionAuditShowsPureApache2AndAlpineRv64() {
        val imgVm = ImageViewModel(imageRepo)
        var closeClicked = false
        composeRule.setContent {
            var showAbout by remember { mutableStateOf(true) }
            KernoVmTheme {
                if (showAbout) {
                    AboutDialog(
                        onDismiss = {
                            closeClicked = true
                            showAbout = false
                        },
                        onCheckUpdate = {}
                    )
                } else {
                    StoreScreen(viewModel = imgVm)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Apache License 2.0", substring = false).performScrollTo().assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("GPL", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("QEMU", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        composeRule.onNodeWithTag("about_close_button").performScrollTo().performClick()
        assertTrue(closeClicked)

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("store_lazy_list").performScrollToNode(hasTestTag("store_item_alpine_rv64"))
        composeRule.onNodeWithTag("store_item_alpine_rv64").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("GPL", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("QEMU", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    // =========================================================================
    // Tier 5 Adversarial Hardening Tests
    // =========================================================================

    @Test
    fun tier5_roomDatabaseMigration3To4PreservesUserProfilesAndUpgradesToRv64() {
        val dbName = "migration_test_${System.nanoTime()}.db"
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        // Manually construct Room v3 schema database & row
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `vm_profiles` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `name` TEXT NOT NULL,
                                `description` TEXT NOT NULL,
                                `ramSizeMb` INTEGER NOT NULL,
                                `cpuThreadCount` INTEGER NOT NULL,
                                `cpuQuotaPercent` INTEGER NOT NULL,
                                `cpuThreadPriority` TEXT NOT NULL,
                                `storageQuotaMb` INTEGER NOT NULL,
                                `kernelImageId` INTEGER NOT NULL,
                                `customDtbImageId` INTEGER,
                                `attachedIsoImageId` INTEGER,
                                `bootArgs` TEXT NOT NULL,
                                `clockMhz` INTEGER NOT NULL,
                                `autoPauseInBackground` INTEGER NOT NULL,
                                `lastBootedAt` INTEGER NOT NULL,
                                `architecture` TEXT NOT NULL,
                                `engineType` TEXT NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS `local_images` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `name` TEXT NOT NULL,
                                `kind` TEXT NOT NULL,
                                `sourceType` TEXT NOT NULL,
                                `localPath` TEXT NOT NULL,
                                `sizeBytes` INTEGER NOT NULL,
                                `sha256` TEXT NOT NULL,
                                `sourceUrl` TEXT NOT NULL,
                                `architectureTag` TEXT NOT NULL,
                                `createdAt` INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        db.execSQL(
                            """
                            INSERT INTO vm_profiles (
                                id, name, description, ramSizeMb, cpuThreadCount, cpuQuotaPercent,
                                cpuThreadPriority, storageQuotaMb, kernelImageId, bootArgs, clockMhz,
                                autoPauseInBackground, lastBootedAt, architecture, engineType
                            ) VALUES (
                                10, 'Legacy v3 VM', 'Old', 64, 1, 100, 'NORMAL', 128, -1,
                                'console=ttyS0', 1, 0, 0, 'riscv32', 'kerno_kotlin'
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        openHelper.writableDatabase.close()

        val upgraded = Room.databaseBuilder(context, KernoDatabase::class.java, dbName)
            .addMigrations(KernoDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        runTest {
            val migratedProfile = upgraded.vmProfileDao().getById(10L)
            assertNotNull(migratedProfile)
            requireNotNull(migratedProfile)
            assertEquals("Legacy v3 VM", migratedProfile.name)
            assertEquals(VmProfile.ARCH_RISCV64, migratedProfile.architecture)
            assertNull(migratedProfile.targetDiskImageId)
            assertEquals(VmProfile.BOOT_SLOT_ISO, migratedProfile.primaryBootSlot)
        }
        upgraded.close()
        dbFile.delete()
    }

    @Test
    fun tier5_corruptElToritoCatalogSectorOverflowCannotCrashIsoInspector() {
        val evilIso = tempFolder.newFile("evil_torito.iso")
        val bytes = E2eTestFixtures.createMinimalIsoBytes("EVIL_TORITO", volumeBlocks = 20)
        // Put El Torito record at sector 17 pointing to out-of-bounds catalog sector 0x00FFFFFF
        val brOff = 17 * 2048
        bytes[brOff] = 0x00
        "CD001".toByteArray().copyInto(bytes, brOff + 1)
        bytes[brOff + 6] = 1
        "EL TORITO SPECIFICATION".toByteArray().copyInto(bytes, brOff + 7)
        bytes[brOff + 71] = 0xFF.toByte()
        bytes[brOff + 72] = 0xFF.toByte()
        bytes[brOff + 73] = 0xFF.toByte()
        evilIso.writeBytes(bytes)

        val meta = com.example.emulator.Iso9660Inspector.inspect(evilIso)
        assertTrue(meta.isIso9660)
        assertTrue(meta.hasElTorito)
        assertNull(meta.elToritoPlatformId)
    }

    @Test
    fun tier5_cyclicDescriptorChainInDualDriveVirtioBlkTerminatesCleanlyWithinGuard() {
        val f1 = E2eTestFixtures.createTempBackingFile(tempFolder, "cyclic.img", 2048)
        VirtioBlockDevice(f1, VirtioBlockSlotConfig.targetDisk(0)).use { vdb ->
            val cpu = Rv32ImaCpuCore(IntArray(16 * 1024), Uart8250(), virtioBlkSecondary = vdb)
            val descBase = 0x80001000L
            val availBase = 0x80002000L
            val usedBase = 0x80002800L
            val hdrAddr = 0x80003000L
            val dataBuf = 0x80004000L

            vdb.writeRegister(0x070, 0, cpu)
            vdb.writeRegister(0x030, 0, cpu)
            vdb.writeRegister(0x038, 16, cpu)
            vdb.writeRegister(0x080, descBase.toInt(), cpu)
            vdb.writeRegister(0x090, availBase.toInt(), cpu)
            vdb.writeRegister(0x0A0, usedBase.toInt(), cpu)
            vdb.writeRegister(0x044, 1, cpu)

            cpu.writePhysicalU32(hdrAddr, VirtioBlockDevice.VIRTIO_BLK_T_IN)
            // Desc 0 -> Desc 1 -> Desc 1 (self-loop!)
            cpu.writePhysicalU32(descBase, hdrAddr.toInt())
            cpu.writePhysicalU32(descBase + 8L, 16)
            cpu.writePhysicalU32(descBase + 12L, VirtioBlockDevice.VIRTQ_DESC_F_NEXT or (1 shl 16))
            cpu.writePhysicalU32(descBase + 16L, dataBuf.toInt())
            cpu.writePhysicalU32(descBase + 24L, 512)
            cpu.writePhysicalU32(descBase + 28L, VirtioBlockDevice.VIRTQ_DESC_F_NEXT or (1 shl 16))

            cpu.writePhysicalU32(availBase, 0x00010000)
            cpu.writePhysicalU16(availBase + 4L, 0)
            vdb.writeRegister(0x050, 0, cpu) // must finish in <= QUEUE_MAX_SIZE iterations
            assertEquals(1, cpu.readPhysicalU16(usedBase + 2L))
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
