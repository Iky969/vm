package com.example

import com.example.data.VmRepository
import com.example.emulator.DeviceTreeBuilder
import com.example.emulator.DiagnosticMonitorRom
import com.example.emulator.FdtInspector
import com.example.emulator.FdtTargetArch
import com.example.emulator.Iso9660Inspector
import com.example.emulator.Rv32ImaCpuCore
import com.example.emulator.Uart8250
import com.example.emulator.VirtioBlockDevice
import com.example.emulator.VirtioBlockFdtDescriptor
import com.example.emulator.VirtioBlockSlotConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets

class VirtioBlockAndIsoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun deviceTreeEmitsVirtioMmioNodeOnlyWhenBlockDeviceAttached() {
        val dtbWithout = DeviceTreeBuilder.build(
            ramSizeBytes = 64L * 1024L * 1024L,
            hasVirtioBlock = false
        )
        val parsedWithout = FdtInspector.parse(dtbWithout)
        assertNull(parsedWithout.nodes["/soc/virtio_mmio@10001000"])

        val dtbWith = DeviceTreeBuilder.build(
            ramSizeBytes = 64L * 1024L * 1024L,
            hasVirtioBlock = true
        )
        val parsedWith = FdtInspector.parse(dtbWith)
        val virtioNode = parsedWith.nodes["/soc/virtio_mmio@10001000"]
        assertNotNull(virtioNode)
        assertEquals("virtio,mmio", virtioNode!!.stringProp("compatible"))
        assertEquals(
            listOf(VirtioBlockDevice.BASE_ADDRESS, VirtioBlockDevice.REGION_SIZE),
            virtioNode.u32ListProp("reg")
        )
        assertEquals(listOf(1, 11), virtioNode.u32ListProp("interrupts-extended"))
        assertEquals(listOf(0), virtioNode.u32ListProp("bootindex"))
        assertEquals("okay", virtioNode.stringProp("status"))
    }

    @Test
    fun deviceTreeEmitsDualDriveVirtioBlockNodesOrderedByBootIndexAndOmitsEjectedIso() {
        val vdaIso = VirtioBlockFdtDescriptor(
            baseAddress = VirtioBlockDevice.BASE_ADDRESS.toLong(),
            irqNumber = VirtioBlockDevice.IRQ_PRIMARY,
            bootIndex = 1,
            deviceName = "vda",
            readOnly = true
        )
        val vdbDisk = VirtioBlockFdtDescriptor(
            baseAddress = VirtioBlockDevice.SECONDARY_BASE_ADDRESS.toLong(),
            irqNumber = VirtioBlockDevice.IRQ_SECONDARY,
            bootIndex = 0,
            deviceName = "vdb",
            readOnly = false
        )

        val dualDtb = DeviceTreeBuilder.build(
            ramSizeBytes = 64L * 1024L * 1024L,
            targetArch = FdtTargetArch.RISCV64,
            virtioBlockDevices = listOf(vdaIso, vdbDisk)
        )
        val parsedDual = FdtInspector.parse(dualDtb)
        assertEquals("rv64ima", parsedDual.targetArchitectureIsa)
        assertEquals(listOf("vdb" to 0, "vda" to 1), parsedDual.bootIndices)
        assertEquals(listOf("/soc/virtio_mmio@10003000", "/soc/virtio_mmio@10001000"), parsedDual.virtioBlockNodes.map { it.path })

        val ejectedDtb = DeviceTreeBuilder.build(
            ramSizeBytes = 64L * 1024L * 1024L,
            targetArch = FdtTargetArch.RISCV64,
            virtioBlockDevices = listOf(vdbDisk)
        )
        val parsedEjected = FdtInspector.parse(ejectedDtb)
        assertNull(parsedEjected.nodes["/soc/virtio_mmio@10001000"])
        assertEquals(listOf("vdb" to 0), parsedEjected.bootIndices)
    }

    @Test
    fun dualDriveCpuCoreDispatchesIndependentVirtioBlockSlots0And1() {
        val isoFile = tempFolder.newFile("installer.iso").apply {
            writeBytes(ByteArray(1024) { 0x11 })
        }
        val diskFile = tempFolder.newFile("target.img").apply {
            writeBytes(ByteArray(1024) { 0x22 })
        }

        VirtioBlockDevice(isoFile, VirtioBlockSlotConfig.primaryIso(bootIndex = 0)).use { devA ->
            VirtioBlockDevice(diskFile, VirtioBlockSlotConfig.targetDisk(bootIndex = 1)).use { devB ->
                val cpu = Rv32ImaCpuCore(
                    ram = IntArray(32 * 1024),
                    uart = Uart8250(),
                    virtioBlk = devA,
                    virtioBlkSecondary = devB
                )
                assertEquals(VirtioBlockDevice.BASE_ADDRESS.toLong(), devA.baseAddress)
                assertEquals(VirtioBlockDevice.SECONDARY_BASE_ADDRESS.toLong(), devB.baseAddress)
                assertEquals("vda", devA.deviceNodeName)
                assertEquals("vdb", devB.deviceNodeName)
                assertEquals(0, devA.bootIndex)
                assertEquals(1, devB.bootIndex)

                val outBuf = Rv32ImaCpuCore.RAM_BASE + 0x6000
                val statusBuf = Rv32ImaCpuCore.RAM_BASE + 0x7000
                cpu.loadBytesIntoRam(ByteArray(512) { 0x55 }, outBuf - Rv32ImaCpuCore.RAM_BASE)
                assertEquals(
                    VirtioBlockDevice.VIRTIO_BLK_S_OK,
                    submitVirtioBlkRequest(devB, cpu, VirtioBlockDevice.VIRTIO_BLK_T_OUT, 0L, outBuf, 512, statusBuf)
                )

                // Eject /dev/vda and promote /dev/vdb to bootindex 0; slot 1 still works cleanly
                cpu.detachPrimaryBlockDevice()
                devB.updateBootIndex(0)
                assertTrue(devA.isDetached)
                assertNull(cpu.virtioBlk)
                assertEquals(0, devB.bootIndex)
                assertEquals(
                    VirtioBlockDevice.VIRTIO_BLK_S_OK,
                    submitVirtioBlkRequest(devB, cpu, VirtioBlockDevice.VIRTIO_BLK_T_IN, 0L, outBuf, 512, statusBuf)
                )
                assertEquals(0x55, cpu.readPhysicalByte(outBuf))
            }
        }
    }

    @Test
    fun iso9660InspectorParsesPrimaryVolumeDescriptorAndFallsBackForRawImg() {
        val isoFile = createSyntheticIsoFile("KERNOVM_LINUX_ISO", volumeBlocks = 256)
        val isoMeta = Iso9660Inspector.inspect(isoFile)
        assertTrue(isoMeta.isIso9660)
        assertEquals("KERNOVM_LINUX_ISO", isoMeta.volumeId)
        assertEquals(2048, isoMeta.logicalBlockSize)
        assertEquals(256L, isoMeta.volumeSpaceBlocks)
        assertEquals((isoFile.length() + 511L) / 512L, isoMeta.sectorCount512)

        val rawImg = tempFolder.newFile("disk.img")
        rawImg.writeBytes(ByteArray(4096) { 0x5A })
        val rawMeta = Iso9660Inspector.inspect(rawImg)
        assertFalse(rawMeta.isIso9660)
        assertEquals("", rawMeta.volumeId)
        assertEquals(8L, rawMeta.sectorCount512)
    }

    @Test
    fun virtioBlockDeviceSupportsReadOnlyIsoAndReadWriteImgSplitVirtqueue() {
        val isoFile = tempFolder.newFile("sample.iso")
        val initialPayload = ByteArray(1024) { idx -> (idx and 0xFF).toByte() }
        isoFile.writeBytes(initialPayload)

        val cpu = Rv32ImaCpuCore(IntArray(64 * 1024), Uart8250())

        // 1. Read-only ISO mode: verify registers, sector read (IN), and write rejection (OUT -> IOERR)
        VirtioBlockDevice(isoFile, readOnly = true).use { roDev ->
            assertEquals(VirtioBlockDevice.VIRTIO_MAGIC, roDev.readRegister(0x000))
            assertEquals(VirtioBlockDevice.VIRTIO_VERSION_2, roDev.readRegister(0x004))
            assertEquals(VirtioBlockDevice.VIRTIO_DEVICE_ID_BLK, roDev.readRegister(0x008))
            assertEquals(0x4E52454B, VirtioBlockDevice.VIRTIO_VENDOR_ID)
            assertEquals(0x4E52454B, roDev.readRegister(0x00C))
            assertEquals(2, roDev.readRegister(0x100)) // 1024 bytes = 2 sectors of 512B
            assertTrue("VIRTIO_BLK_F_RO must be set", (roDev.readRegister(0x010) and (1 shl 5)) != 0)

            val readBufAddr = Rv32ImaCpuCore.RAM_BASE + 0x4000
            val statusAddr = Rv32ImaCpuCore.RAM_BASE + 0x5000
            val readStatus = submitVirtioBlkRequest(
                dev = roDev,
                cpu = cpu,
                reqType = VirtioBlockDevice.VIRTIO_BLK_T_IN,
                sector = 1L,
                dataBufAddr = readBufAddr,
                dataLen = 512,
                statusAddr = statusAddr
            )
            assertEquals(VirtioBlockDevice.VIRTIO_BLK_S_OK, readStatus)
            val actualSector1 = ByteArray(512) { i -> cpu.readPhysicalByte(readBufAddr + i).toByte() }
            assertArrayEquals(initialPayload.copyOfRange(512, 1024), actualSector1)

            // Attempt write on read-only ISO -> must fail with VIRTIO_BLK_S_IOERR
            val writeStatus = submitVirtioBlkRequest(
                dev = roDev,
                cpu = cpu,
                reqType = VirtioBlockDevice.VIRTIO_BLK_T_OUT,
                sector = 0L,
                dataBufAddr = readBufAddr,
                dataLen = 512,
                statusAddr = statusAddr
            )
            assertEquals(VirtioBlockDevice.VIRTIO_BLK_S_IOERR, writeStatus)
        }

        // 2. Read-write IMG mode: verify VIRTIO_BLK_T_OUT persists to backing file
        val imgFile = tempFolder.newFile("data.img")
        imgFile.writeBytes(ByteArray(1024))
        VirtioBlockDevice(imgFile, readOnly = false).use { rwDev ->
            assertEquals(0, rwDev.readRegister(0x010) and (1 shl 5))

            val writeBufAddr = Rv32ImaCpuCore.RAM_BASE + 0x6000
            val statusAddr = Rv32ImaCpuCore.RAM_BASE + 0x7000
            val pattern = ByteArray(512) { i -> ((i * 7 + 3) and 0xFF).toByte() }
            cpu.loadBytesIntoRam(pattern, writeBufAddr - Rv32ImaCpuCore.RAM_BASE)

            val writeStatus = submitVirtioBlkRequest(
                dev = rwDev,
                cpu = cpu,
                reqType = VirtioBlockDevice.VIRTIO_BLK_T_OUT,
                sector = 1L,
                dataBufAddr = writeBufAddr,
                dataLen = 512,
                statusAddr = statusAddr
            )
            assertEquals(VirtioBlockDevice.VIRTIO_BLK_S_OK, writeStatus)
        }
        val persisted = imgFile.readBytes()
        val expected = ByteArray(512) { i -> ((i * 7 + 3) and 0xFF).toByte() }
        assertArrayEquals(expected, persisted.copyOfRange(512, 1024))
    }

    @Test
    fun diagnosticMonitorRomDiskCommandReportsDetachedAndAttachedVirtioBlk() {
        val ramSize = 16 * 1024 * 1024

        // Case A: No VirtIO block device attached
        run {
            val dtb = DeviceTreeBuilder.build(ramSize.toLong(), hasVirtioBlock = false)
            val dtbOffset = ramSize - ((dtb.size + 15) and 15.inv())
            val txOut = StringBuilder()
            var cpuRef: Rv32ImaCpuCore? = null
            val uart = Uart8250(
                onTxByte = { txOut.append(it.toChar()) },
                onRxAvailable = { cpuRef?.wakeFromWfi() }
            )
            val cpu = Rv32ImaCpuCore(IntArray(ramSize ushr 2), uart, virtioBlk = null)
            cpuRef = cpu
            cpu.loadBytesIntoRam(DiagnosticMonitorRom.binary, 0)
            cpu.loadBytesIntoRam(dtb, dtbOffset)
            cpu.reset(Rv32ImaCpuCore.RAM_BASE, 0, Rv32ImaCpuCore.RAM_BASE + dtbOffset)

            assertEquals(Rv32ImaCpuCore.BATCH_WFI, cpu.stepBatch(200_000))
            uart.pushRxBytes("disk\r".toByteArray())
            assertEquals(Rv32ImaCpuCore.BATCH_WFI, cpu.stepBatch(200_000))
            assertTrue(txOut.toString().contains("Tidak ada ISO / Disk VirtIO-blk terpasang"))
        }

        // Case B: Synthetic ISO attached at 0x10001000
        val isoFile = createSyntheticIsoFile("TEST_DISK", volumeBlocks = 32)
        VirtioBlockDevice(isoFile, readOnly = true).use { blk ->
            val dtb = DeviceTreeBuilder.build(ramSize.toLong(), hasVirtioBlock = true)
            val dtbOffset = ramSize - ((dtb.size + 15) and 15.inv())
            val txOut = StringBuilder()
            var cpuRef: Rv32ImaCpuCore? = null
            val uart = Uart8250(
                onTxByte = { txOut.append(it.toChar()) },
                onRxAvailable = { cpuRef?.wakeFromWfi() }
            )
            val cpu = Rv32ImaCpuCore(IntArray(ramSize ushr 2), uart, virtioBlk = blk)
            cpuRef = cpu
            cpu.loadBytesIntoRam(DiagnosticMonitorRom.binary, 0)
            cpu.loadBytesIntoRam(dtb, dtbOffset)
            cpu.reset(Rv32ImaCpuCore.RAM_BASE, 0, Rv32ImaCpuCore.RAM_BASE + dtbOffset)

            assertEquals(Rv32ImaCpuCore.BATCH_WFI, cpu.stepBatch(200_000))
            uart.pushRxBytes("disk\r".toByteArray())
            assertEquals(Rv32ImaCpuCore.BATCH_WFI, cpu.stepBatch(200_000))
            val out = txOut.toString()
            assertTrue("Expected attached VirtIO-blk output, got: $out", out.contains("VirtIO-blk @ 0x10001000 [TERPASANG]"))
            assertTrue("Expected devId=0x00000002, got: $out", out.contains("devId=0x00000002"))
            val expectedHexSectors = "%08x".format(blk.sectorCount.toInt())
            assertTrue("Expected sectors=0x$expectedHexSectors, got: $out", out.contains("sectors=0x$expectedHexSectors"))
        }
    }

    @Test
    fun resourceLimitConstantsExposeExpectedCpuThreadAndStorageQuotaOptions() {
        assertEquals(listOf(1, 2, 4), VmRepository.SUPPORTED_CPU_THREAD_OPTIONS)
        assertEquals(listOf(64, 128, 256, 512, 1024), VmRepository.SUPPORTED_STORAGE_QUOTAS_MB)
        assertEquals(listOf(16, 32, 64, 128), VmRepository.SUPPORTED_RAM_OPTIONS_MB)
        assertEquals(listOf(25, 50, 75, 85, 100), VmRepository.SUPPORTED_CPU_QUOTA_STEPS)

        val defaultProfile = com.example.data.VmProfile(name = "Test VM", kernelImageId = -1L)
        assertEquals(1, defaultProfile.cpuThreadCount)
        assertEquals(100, defaultProfile.cpuQuotaPercent)
        assertEquals(128, defaultProfile.storageQuotaMb)
        assertEquals(64, defaultProfile.ramSizeMb)
        assertNull(defaultProfile.attachedIsoImageId)

        // Verify unlocked custom slider/typed limits are saved without clamping to rigid presets or host core count
        var captured: com.example.data.VmProfile? = null
        val fakeDao = object : com.example.data.VmProfileDao {
            override fun observeAllProfiles() = kotlinx.coroutines.flow.flowOf(emptyList<com.example.data.VmProfile>())
            override suspend fun getById(id: Long) = captured
            override suspend fun count() = 0
            override suspend fun insert(profile: com.example.data.VmProfile): Long {
                captured = profile
                return 1L
            }
            override suspend fun update(profile: com.example.data.VmProfile) {
                captured = profile
            }
            override suspend fun updateLastBootedAt(id: Long, timestamp: Long) = Unit
            override suspend fun deleteById(id: Long) = Unit
        }
        val repo = VmRepository(fakeDao)
        kotlinx.coroutines.runBlocking {
            repo.saveProfile(
                com.example.data.VmProfile(
                    name = "Custom Unlocked VM",
                    kernelImageId = -1L,
                    ramSizeMb = 192,
                    cpuThreadCount = 8,
                    cpuQuotaPercent = 15,
                    storageQuotaMb = 2048
                )
            )
        }
        val saved = captured
        assertNotNull(saved)
        assertEquals(192, saved!!.ramSizeMb)
        assertEquals(8, saved.cpuThreadCount)
        assertEquals(15, saved.cpuQuotaPercent)
        assertEquals(2048, saved.storageQuotaMb)
    }

    private fun createSyntheticIsoFile(volumeLabel: String, volumeBlocks: Int): File {
        val file = tempFolder.newFile("$volumeLabel.iso")
        val pvdOffset = 16 * 2048
        val bytes = ByteArray(pvdOffset + 2048)
        bytes[pvdOffset] = 1 // Primary Volume Descriptor type
        "CD001".toByteArray(StandardCharsets.US_ASCII).copyInto(bytes, pvdOffset + 1)
        val labelPadded = volumeLabel.padEnd(32, ' ').take(32).toByteArray(StandardCharsets.US_ASCII)
        labelPadded.copyInto(bytes, pvdOffset + 40)
        // Volume Space Size (little-endian at PVD+80)
        bytes[pvdOffset + 80] = (volumeBlocks and 0xFF).toByte()
        bytes[pvdOffset + 81] = ((volumeBlocks ushr 8) and 0xFF).toByte()
        bytes[pvdOffset + 82] = ((volumeBlocks ushr 16) and 0xFF).toByte()
        bytes[pvdOffset + 83] = ((volumeBlocks ushr 24) and 0xFF).toByte()
        // Logical Block Size = 2048 (little-endian at PVD+128)
        bytes[pvdOffset + 128] = (2048 and 0xFF).toByte()
        bytes[pvdOffset + 129] = ((2048 ushr 8) and 0xFF).toByte()
        file.writeBytes(bytes)
        return file
    }

    private fun submitVirtioBlkRequest(
        dev: VirtioBlockDevice,
        cpu: Rv32ImaCpuCore,
        reqType: Int,
        sector: Long,
        dataBufAddr: Int,
        dataLen: Int,
        statusAddr: Int
    ): Int {
        val descBase = Rv32ImaCpuCore.RAM_BASE + 0x1000
        val availBase = Rv32ImaCpuCore.RAM_BASE + 0x2000
        val usedBase = Rv32ImaCpuCore.RAM_BASE + 0x2800
        val hdrAddr = Rv32ImaCpuCore.RAM_BASE + 0x3000

        // Reset and configure queue 0
        dev.writeRegister(0x070, 0, cpu)
        dev.writeRegister(0x030, 0, cpu)
        dev.writeRegister(0x038, 16, cpu)
        dev.writeRegister(0x080, descBase, cpu)
        dev.writeRegister(0x090, availBase, cpu)
        dev.writeRegister(0x0A0, usedBase, cpu)
        dev.writeRegister(0x044, 1, cpu)

        // Write 16-byte virtio_blk_outhdr
        writeWord(cpu, hdrAddr, reqType)
        writeWord(cpu, hdrAddr + 4, 0)
        writeWord(cpu, hdrAddr + 8, (sector and 0xFFFFFFFFL).toInt())
        writeWord(cpu, hdrAddr + 12, ((sector ushr 32) and 0xFFFFFFFFL).toInt())

        // Descriptor 0: header -> next=1
        writeDesc(cpu, descBase, 0, hdrAddr, 16, VirtioBlockDevice.VIRTQ_DESC_F_NEXT, 1)
        // Descriptor 1: data buffer -> next=2
        writeDesc(cpu, descBase, 1, dataBufAddr, dataLen, VirtioBlockDevice.VIRTQ_DESC_F_NEXT, 2)
        // Descriptor 2: 1-byte status -> terminal
        writeDesc(cpu, descBase, 2, statusAddr, 1, 0, 0)

        // Reset used/avail rings and publish head descriptor 0
        writeWord(cpu, usedBase, 0)
        writeWord(cpu, availBase, 0x00010000) // flags=0, idx=1
        writeWord(cpu, availBase + 4, 0)      // ring[0]=0

        // Ring QueueNotify doorbell for queue 0
        dev.writeRegister(0x050, 0, cpu)

        // Verify Used Ring advanced to idx=1
        val usedIdx = cpu.readPhysicalByte(usedBase + 2) or (cpu.readPhysicalByte(usedBase + 3) shl 8)
        assertEquals(1, usedIdx)
        return cpu.readPhysicalByte(statusAddr)
    }

    private fun writeDesc(
        cpu: Rv32ImaCpuCore,
        descBase: Int,
        index: Int,
        addr: Int,
        len: Int,
        flags: Int,
        next: Int
    ) {
        val base = descBase + index * 16
        writeWord(cpu, base, addr)
        writeWord(cpu, base + 4, 0)
        writeWord(cpu, base + 8, len)
        writeWord(cpu, base + 12, (flags and 0xFFFF) or ((next and 0xFFFF) shl 16))
    }

    private fun writeWord(cpu: Rv32ImaCpuCore, physAddr: Int, value: Int) {
        val off = physAddr - Rv32ImaCpuCore.RAM_BASE
        cpu.ram[off ushr 2] = value
    }
}
