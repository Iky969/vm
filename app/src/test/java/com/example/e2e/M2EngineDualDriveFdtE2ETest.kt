package com.example.e2e

import com.example.emulator.DeviceTreeBuilder
import com.example.emulator.FdtInspector
import com.example.emulator.FdtTargetArch
import com.example.emulator.GuestMemoryBus
import com.example.emulator.Rv32ImaCpuCore
import com.example.emulator.Uart8250
import com.example.emulator.VirtioBlockDevice
import com.example.emulator.VirtioBlockFdtDescriptor
import com.example.emulator.VirtioBlockSlotConfig
import com.example.emulator.VirtioNetDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase B2 Opaque-Box E2E Suite (33 tests: 15 Tier 1 + 15 Tier 2 + 3 Tier 3 pairwise) covering:
 * - Feature #5: GuestMemoryBus Abstraction (5 T1 + 5 T2)
 * - Feature #6: Dual-Drive VirtioBlockDevice & MMIO Routing (5 T1 + 5 T2)
 * - Feature #7: DeviceTreeBuilder RV64 & bootindex FDT (5 T1 + 5 T2)
 * - Tier 3 Cross-Feature Pairwise Interactions (#3x#6, #5x#6, #6x#7)
 */
class M2EngineDualDriveFdtE2ETest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // Feature #5 — GuestMemoryBus Abstraction (5 T1 + 5 T2)
    // =========================================================================

    @Test
    fun f5_t1_rv32CpuCoreImplementsGuestMemoryBusWithLittleEndianSemantics() {
        val ram = IntArray(4096)
        val bus: GuestMemoryBus = Rv32ImaCpuCore(ram, Uart8250())

        assertEquals(0x80000000L, bus.ramBaseAddress)
        assertEquals(4096L * 4L, bus.ramSizeBytes)

        val target = bus.ramBaseAddress + 0x100L
        bus.writePhysicalU32(target, 0x12345678)
        assertEquals(0x78, bus.readPhysicalByte(target))
        assertEquals(0x56, bus.readPhysicalByte(target + 1L))
        assertEquals(0x34, bus.readPhysicalByte(target + 2L))
        assertEquals(0x12, bus.readPhysicalByte(target + 3L))
        assertEquals(0x12345678, bus.readPhysicalWord(target))
    }

    @Test
    fun f5_t1_virtioBlockAcceptsHigh64BitGuestMemoryBusAddresses() {
        val highBase = 0x00000002_80000000L
        val bus = E2eTestFixtures.HighAddressGuestMemoryBus(highBase, 64 * 1024)
        val disk = E2eTestFixtures.createTempBackingFile(tempFolder, "high_bus.img", 2048)
        val seed = ByteArray(512) { idx -> ((idx * 3 + 7) and 0xFF).toByte() }
        disk.writeBytes(seed + ByteArray(1536))

        VirtioBlockDevice(disk, VirtioBlockSlotConfig.targetDisk(bootIndex = 0)).use { blk ->
            val dataAddr = highBase + 0x4000L
            val statusAddr = highBase + 0x5000L
            val status = submitBlkRequest64(
                dev = blk,
                bus = bus,
                reqType = VirtioBlockDevice.VIRTIO_BLK_T_IN,
                sector = 0L,
                dataBufAddr = dataAddr,
                dataLen = 512,
                statusAddr = statusAddr
            )
            assertEquals(VirtioBlockDevice.VIRTIO_BLK_S_OK, status)
            val actual = ByteArray(512) { i -> bus.readPhysicalByte(dataAddr + i).toByte() }
            assertArrayEquals(seed, actual)
        }
    }

    @Test
    fun f5_t1_virtioNetOperatesOverAbstractGuestMemoryBusWithoutCpuDependency() {
        val highBase = 0x00000001_00000000L
        val bus = E2eTestFixtures.HighAddressGuestMemoryBus(highBase, 64 * 1024)
        val net = VirtioNetDevice()

        val rxDesc = highBase + 0x1000L
        val rxAvail = highBase + 0x1400L
        val rxUsed = highBase + 0x1800L
        val rxBuf = highBase + 0x2000L

        configureQueue64(net, bus, queueSel = 0, qSize = 8, descAddr = rxDesc, availAddr = rxAvail, usedAddr = rxUsed)
        writeDesc64(bus, rxDesc, 0, rxBuf, 256, VirtioNetDevice.VIRTQ_DESC_F_WRITE, 0)
        bus.writePhysicalU16(rxAvail, 0)
        bus.writePhysicalU16(rxAvail + 2L, 1)
        bus.writePhysicalU16(rxAvail + 4L, 0)

        val ethPacket = ByteArray(42) { i -> (0xA0 + i).toByte() }
        assertTrue(net.receivePacket(bus, ethPacket))
        assertEquals(1L, net.packetsRx)
        assertEquals(1, bus.readPhysicalU16(rxUsed + 2L))
    }

    @Test
    fun f5_t1_guestMemoryBusReadsAndWritesUnalignedWordsAndU64() {
        val cpu = Rv32ImaCpuCore(IntArray(4096), Uart8250())
        val bus: GuestMemoryBus = cpu
        val unaligned = bus.ramBaseAddress + 0x101L
        bus.writePhysicalU32(unaligned, 0xCAFEBABE.toInt())
        assertEquals(0xCAFEBABE.toInt(), bus.readPhysicalWord(unaligned))

        val qwordAddr = bus.ramBaseAddress + 0x200L
        bus.writePhysicalU32(qwordAddr, 0x11223344)
        bus.writePhysicalU32(qwordAddr + 4L, 0x55667788)
        assertEquals(0x55667788_11223344L, bus.readPhysicalU64(qwordAddr))
    }

    @Test
    fun f5_t1_guestMemoryBusMapsUnsignedIntOverloadsIdentically() {
        val cpu = Rv32ImaCpuCore(IntArray(4096), Uart8250())
        cpu.loadBytesIntoRam(byteArrayOf(0x11, 0x22, 0x33, 0x44), 0x40)
        assertEquals(0x44332211, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x40))
        assertEquals(0x44332211, (cpu as GuestMemoryBus).readPhysicalWord(0x80000040L))
    }

    @Test
    fun f5_t2_outOfRange64BitAddressesReturnZeroAndNeverCorruptRam() {
        val cpu = Rv32ImaCpuCore(IntArray(2048), Uart8250())
        val bus: GuestMemoryBus = cpu
        bus.writePhysicalU32(bus.ramBaseAddress, 0x5A5A5A5A)

        // Above 4 GiB aliasing guard & below RAM base guard
        bus.writePhysicalByte(0x00000001_80000000L, 0xFF)
        bus.writePhysicalByte(0x7FFFFFFFL, 0xFF)
        bus.writePhysicalByte(bus.ramBaseAddress + bus.ramSizeBytes + 16L, 0xFF)

        assertEquals(0x5A5A5A5A, bus.readPhysicalWord(bus.ramBaseAddress))
        assertEquals(0, bus.readPhysicalByte(0x00000001_80000000L))
        assertEquals(0, bus.readPhysicalByte(0x7FFFFFFFL))
    }

    @Test
    fun f5_t2_signExtendedIntToLongAddressesResolveInsideRv32RamSafely() {
        val cpu = Rv32ImaCpuCore(IntArray(2048), Uart8250())
        val bus: GuestMemoryBus = cpu
        val signExtendedAddr = (Rv32ImaCpuCore.RAM_BASE + 0x80).toLong() // 0xFFFFFFFF_80000080L
        bus.writePhysicalU32(signExtendedAddr, 0x0BADF00D)
        assertEquals(0x0BADF00D, bus.readPhysicalWord(0x80000080L))
    }

    @Test
    fun f5_t2_highAddressQueueWrappingAndZeroRingPointersFailGracefully() {
        val bus = E2eTestFixtures.HighAddressGuestMemoryBus(0x00000005_00000000L, 8192)
        val disk = E2eTestFixtures.createTempBackingFile(tempFolder, "zero_ring.img", 1024)
        VirtioBlockDevice(disk, VirtioBlockSlotConfig.primaryIso()).use { blk ->
            blk.writeRegister(0x044, 1, bus)
            blk.writeRegister(0x050, 0, bus) // ring bases are 0 -> no crash
            assertEquals(0, blk.readRegister(0x060))
        }
    }

    @Test
    fun f5_t2_outOfBoundsDescriptorIndexInVirtioQueueIsIgnoredSafely() {
        val bus = E2eTestFixtures.HighAddressGuestMemoryBus(0x80000000L, 32 * 1024)
        val disk = E2eTestFixtures.createTempBackingFile(tempFolder, "bad_desc.img", 1024)
        VirtioBlockDevice(disk, VirtioBlockSlotConfig.targetDisk()).use { blk ->
            val desc = 0x80001000L
            val avail = 0x80002000L
            val used = 0x80003000L
            configureQueue64(blk, bus, qSize = 4, descAddr = desc, availAddr = avail, usedAddr = used)
            bus.writePhysicalU16(avail + 2L, 1)
            bus.writePhysicalU16(avail + 4L, 999) // out of bounds >= 4
            blk.writeRegister(0x050, 0, bus)
            assertEquals(1, bus.readPhysicalU16(used + 2L))
        }
    }

    @Test
    fun f5_t2_nonQueueZeroSelectIgnoresQueueConfigWritesInBlockDevice() {
        val bus = E2eTestFixtures.HighAddressGuestMemoryBus(0x80000000L, 8192)
        val disk = E2eTestFixtures.createTempBackingFile(tempFolder, "qsel1.img", 1024)
        VirtioBlockDevice(disk, VirtioBlockSlotConfig.primaryIso()).use { blk ->
            blk.writeRegister(0x030, 1, bus)
            assertEquals(0, blk.readRegister(0x034))
            blk.writeRegister(0x080, 0x80001000.toInt(), bus)
            blk.writeRegister(0x044, 1, bus)
            assertEquals(0, blk.readRegister(0x044))
        }
    }

    // =========================================================================
    // Feature #6 — Dual-Drive VirtioBlockDevice & MMIO Routing (5 T1 + 5 T2)
    // =========================================================================

    @Test
    fun f6_t1_slotConfigsDifferentiateIsoVdaReadOnlyAndTargetDiskVdbReadWrite() {
        val isoFile = E2eTestFixtures.createTempBackingFile(tempFolder, "inst.iso", 2048)
        val diskFile = E2eTestFixtures.createTempBackingFile(tempFolder, "target.img", 4096)

        VirtioBlockDevice(isoFile, VirtioBlockSlotConfig.primaryIso(bootIndex = 0)).use { vda ->
            VirtioBlockDevice(diskFile, VirtioBlockSlotConfig.targetDisk(bootIndex = 1)).use { vdb ->
                assertEquals(0, vda.slotIndex)
                assertEquals(VirtioBlockDevice.BASE_ADDRESS.toLong(), vda.baseAddress)
                assertEquals("vda", vda.deviceNodeName)
                assertTrue(vda.readOnly)
                assertEquals(0, vda.bootIndex)

                assertEquals(1, vdb.slotIndex)
                assertEquals(VirtioBlockDevice.SECONDARY_BASE_ADDRESS.toLong(), vdb.baseAddress)
                assertEquals("vdb", vdb.deviceNodeName)
                assertFalse(vdb.readOnly)
                assertEquals(1, vdb.bootIndex)
            }
        }
    }

    @Test
    fun f6_t1_cpuCoreRoutesMmioIndependentlyBetween10001000And10003000() {
        val f0 = E2eTestFixtures.createTempBackingFile(tempFolder, "vda_2sec.iso", 1024)
        val f1 = E2eTestFixtures.createTempBackingFile(tempFolder, "vdb_6sec.img", 3072)

        VirtioBlockDevice(f0, VirtioBlockSlotConfig.primaryIso(0)).use { vda ->
            VirtioBlockDevice(f1, VirtioBlockSlotConfig.targetDisk(1)).use { vdb ->
                val cpu = Rv32ImaCpuCore(IntArray(16 * 1024), Uart8250(), virtioBlk = vda, virtioBlkSecondary = vdb)
                val rom = E2eTestFixtures.assembleMmioReadAndPoweroff(
                    listOf(
                        (VirtioBlockDevice.BASE_ADDRESS + 0x100) to 0x2000,
                        (VirtioBlockDevice.SECONDARY_BASE_ADDRESS + 0x100) to 0x2004,
                        (VirtioBlockDevice.BASE_ADDRESS + 0x010) to 0x2008,
                        (VirtioBlockDevice.SECONDARY_BASE_ADDRESS + 0x010) to 0x200C
                    )
                )
                cpu.loadBytesIntoRam(rom, 0)
                cpu.reset()
                assertEquals(Rv32ImaCpuCore.BATCH_POWEROFF, cpu.stepBatch(10_000))

                assertEquals(2, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2000))
                assertEquals(6, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2004))
                assertTrue((cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2008) and (1 shl 5)) != 0)
                assertEquals(0, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x200C) and (1 shl 5))
            }
        }
    }

    @Test
    fun f6_t1_vdbPersistsWritesAndFlushWhileVdaRejectsWrites() {
        val f0 = E2eTestFixtures.createTempBackingFile(tempFolder, "ro.iso", 1024, fill = 0x1A)
        val f1 = E2eTestFixtures.createTempBackingFile(tempFolder, "rw.img", 1024, fill = 0x00)

        VirtioBlockDevice(f0, VirtioBlockSlotConfig.primaryIso(0)).use { vda ->
            VirtioBlockDevice(f1, VirtioBlockSlotConfig.targetDisk(1)).use { vdb ->
                val cpu = Rv32ImaCpuCore(IntArray(16 * 1024), Uart8250(), virtioBlk = vda, virtioBlkSecondary = vdb)
                val writeBuf = (Rv32ImaCpuCore.RAM_BASE + 0x4000).toLong() and 0xFFFFFFFFL
                val statusBuf = (Rv32ImaCpuCore.RAM_BASE + 0x5000).toLong() and 0xFFFFFFFFL
                for (i in 0 until 512) cpu.writePhysicalByte(writeBuf + i, 0xC3)

                assertEquals(
                    VirtioBlockDevice.VIRTIO_BLK_S_IOERR,
                    submitBlkRequest64(vda, cpu, VirtioBlockDevice.VIRTIO_BLK_T_OUT, 0L, writeBuf, 512, statusBuf)
                )
                assertEquals(
                    VirtioBlockDevice.VIRTIO_BLK_S_OK,
                    submitBlkRequest64(vdb, cpu, VirtioBlockDevice.VIRTIO_BLK_T_OUT, 1L, writeBuf, 512, statusBuf)
                )
                assertEquals(
                    VirtioBlockDevice.VIRTIO_BLK_S_OK,
                    submitBlkRequest64(vdb, cpu, VirtioBlockDevice.VIRTIO_BLK_T_FLUSH, 0L, writeBuf, 0, statusBuf)
                )
            }
        }
        assertTrue(f0.readBytes().all { it == 0x1A.toByte() })
        assertTrue(f1.readBytes().copyOfRange(512, 1024).all { it == 0xC3.toByte() })
    }

    @Test
    fun f6_t1_meipInterruptBitAssertsAndClearsOnSecondaryBlockInterruptAck() {
        val f1 = E2eTestFixtures.createTempBackingFile(tempFolder, "irq.img", 1024)
        VirtioBlockDevice(f1, VirtioBlockSlotConfig.targetDisk(0)).use { vdb ->
            val cpu = Rv32ImaCpuCore(IntArray(16 * 1024), Uart8250(), virtioBlkSecondary = vdb)
            val buf = (Rv32ImaCpuCore.RAM_BASE + 0x4000).toLong() and 0xFFFFFFFFL
            val st = (Rv32ImaCpuCore.RAM_BASE + 0x5000).toLong() and 0xFFFFFFFFL

            submitBlkRequest64(vdb, cpu, VirtioBlockDevice.VIRTIO_BLK_T_IN, 0L, buf, 512, st)
            assertTrue(vdb.hasPendingInterrupt)
            cpu.syncInterrupts(0L)
            assertTrue((cpu.mip and Rv32ImaCpuCore.MIP_MEIP) != 0)

            vdb.writeRegister(0x064, 1, cpu)
            assertFalse(vdb.hasPendingInterrupt)
            cpu.syncInterrupts(0L)
            assertEquals(0, cpu.mip and Rv32ImaCpuCore.MIP_MEIP)
        }
    }

    @Test
    fun f6_t1_bootIndexUpdatesDynamicallyInPlace() {
        val f1 = E2eTestFixtures.createTempBackingFile(tempFolder, "idx.img", 1024)
        VirtioBlockDevice(f1, VirtioBlockSlotConfig.targetDisk(bootIndex = 1)).use { vdb ->
            assertEquals(1, vdb.bootIndex)
            vdb.updateBootIndex(0)
            assertEquals(0, vdb.bootIndex)
            assertEquals(0, vdb.config.bootIndex)
        }
    }

    @Test
    fun f6_t2_detachedVdaReturnsZeroSafeOnMmioReadsAndWritesWhileVdbStaysActive() {
        val f0 = E2eTestFixtures.createTempBackingFile(tempFolder, "detach_vda.iso", 1024)
        val f1 = E2eTestFixtures.createTempBackingFile(tempFolder, "live_vdb.img", 2048)

        VirtioBlockDevice(f0, VirtioBlockSlotConfig.primaryIso(0)).use { vda ->
            VirtioBlockDevice(f1, VirtioBlockSlotConfig.targetDisk(1)).use { vdb ->
                val cpu = Rv32ImaCpuCore(IntArray(16 * 1024), Uart8250(), virtioBlk = vda, virtioBlkSecondary = vdb)
                cpu.detachPrimaryBlockDevice()

                assertTrue(vda.isDetached)
                assertNull(cpu.virtioBlk)
                assertEquals(0, vda.readRegister(0x000))
                vda.writeRegister(0x070, 0x0F, cpu) // must not throw StreamClosed IOException

                val rom = E2eTestFixtures.assembleMmioReadAndPoweroff(
                    listOf(
                        (VirtioBlockDevice.BASE_ADDRESS + 0x000) to 0x2000,
                        (VirtioBlockDevice.SECONDARY_BASE_ADDRESS + 0x000) to 0x2004
                    )
                )
                cpu.loadBytesIntoRam(rom, 0)
                cpu.reset()
                assertEquals(Rv32ImaCpuCore.BATCH_POWEROFF, cpu.stepBatch(10_000))
                assertEquals(0, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2000))
                assertEquals(VirtioBlockDevice.VIRTIO_MAGIC, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2004))
            }
        }
    }

    @Test
    fun f6_t2_secondaryDriveRejectsWritePastEndOfBackingFileWithIoErr() {
        val f1 = E2eTestFixtures.createTempBackingFile(tempFolder, "small.img", 512)
        VirtioBlockDevice(f1, VirtioBlockSlotConfig.targetDisk(0)).use { vdb ->
            val cpu = Rv32ImaCpuCore(IntArray(16 * 1024), Uart8250(), virtioBlkSecondary = vdb)
            val buf = (Rv32ImaCpuCore.RAM_BASE + 0x4000).toLong() and 0xFFFFFFFFL
            val st = (Rv32ImaCpuCore.RAM_BASE + 0x5000).toLong() and 0xFFFFFFFFL
            assertEquals(
                VirtioBlockDevice.VIRTIO_BLK_S_IOERR,
                submitBlkRequest64(vdb, cpu, VirtioBlockDevice.VIRTIO_BLK_T_OUT, 1L, buf, 512, st)
            )
        }
    }

    @Test
    fun f6_t2_unsupportedRequestTypeSetsVirtioBlkSUnsupp() {
        val f1 = E2eTestFixtures.createTempBackingFile(tempFolder, "unsupp.img", 1024)
        VirtioBlockDevice(f1, VirtioBlockSlotConfig.targetDisk(0)).use { vdb ->
            val cpu = Rv32ImaCpuCore(IntArray(16 * 1024), Uart8250(), virtioBlkSecondary = vdb)
            val buf = (Rv32ImaCpuCore.RAM_BASE + 0x4000).toLong() and 0xFFFFFFFFL
            val st = (Rv32ImaCpuCore.RAM_BASE + 0x5000).toLong() and 0xFFFFFFFFL
            assertEquals(
                VirtioBlockDevice.VIRTIO_BLK_S_UNSUPP,
                submitBlkRequest64(vdb, cpu, 999, 0L, buf, 512, st)
            )
        }
    }

    @Test
    fun f6_t2_unattachedSecondarySlotReturnsZeroAt10003000InCpuMmio() {
        val cpu = Rv32ImaCpuCore(IntArray(16 * 1024), Uart8250(), virtioBlk = null, virtioBlkSecondary = null)
        val rom = E2eTestFixtures.assembleMmioReadAndPoweroff(
            listOf((VirtioBlockDevice.SECONDARY_BASE_ADDRESS + 0x00C) to 0x2000)
        )
        cpu.loadBytesIntoRam(rom, 0)
        cpu.reset()
        assertEquals(Rv32ImaCpuCore.BATCH_POWEROFF, cpu.stepBatch(10_000))
        assertEquals(0, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2000))
    }

    @Test
    fun f6_t2_negativeBootIndexCoercesToZero() {
        val f1 = E2eTestFixtures.createTempBackingFile(tempFolder, "negidx.img", 1024)
        VirtioBlockDevice(f1, VirtioBlockSlotConfig.targetDisk(bootIndex = 1)).use { vdb ->
            vdb.updateBootIndex(-5)
            assertEquals(0, vdb.bootIndex)
        }
    }

    // =========================================================================
    // Feature #7 — DeviceTreeBuilder RV64 & bootindex FDT (5 T1 + 5 T2)
    // =========================================================================

    @Test
    fun f7_t1_defaultDeviceTreeTargetsRv64WithExpectedCompatibleModelAndIsa() {
        val dtb = DeviceTreeBuilder.build(ramSizeBytes = 64L * 1024L * 1024L)
        val parsed = FdtInspector.parse(dtb)

        assertEquals("KernoVM RV64", parsed.modelName)
        assertEquals("rv64ima", parsed.targetArchitectureIsa)
        assertEquals(listOf("riscv-minimal", "kernovm,rv64"), parsed.nodes["/"]?.stringListProp("compatible"))
        assertEquals("riscv,sv39", parsed.nodes["/cpus/cpu@0"]?.stringProp("mmu-type"))
    }

    @Test
    fun f7_t1_explicitRv32TargetRetainsLegacyNommuIsaAndModel() {
        val dtb = DeviceTreeBuilder.build(
            ramSizeBytes = 32L * 1024L * 1024L,
            targetArch = FdtTargetArch.RISCV32
        )
        val parsed = FdtInspector.parse(dtb)
        assertEquals("KernoVM RV32IMA M-Mode No-MMU", parsed.modelName)
        assertEquals("rv32ima", parsed.targetArchitectureIsa)
        assertEquals("riscv,none", parsed.nodes["/cpus/cpu@0"]?.stringProp("mmu-type"))
    }

    @Test
    fun f7_t1_dualDriveIsoFirstOrdersVdaBeforeVdbWithBootIndicesZeroAndOne() {
        val dtb = DeviceTreeBuilder.build(
            ramSizeBytes = 64L * 1024L * 1024L,
            targetArch = FdtTargetArch.RISCV64,
            virtioBlockDevices = listOf(
                VirtioBlockFdtDescriptor(VirtioBlockDevice.SECONDARY_BASE_ADDRESS.toLong(), 0x1000, VirtioBlockDevice.IRQ_SECONDARY, 1, "vdb", false),
                VirtioBlockFdtDescriptor(VirtioBlockDevice.BASE_ADDRESS.toLong(), 0x1000, VirtioBlockDevice.IRQ_PRIMARY, 0, "vda", true)
            )
        )
        val parsed = FdtInspector.parse(dtb)
        assertEquals(listOf("vda" to 0, "vdb" to 1), parsed.bootIndices)
        assertEquals(listOf("/soc/virtio_mmio@10001000", "/soc/virtio_mmio@10003000"), parsed.virtioBlockNodes.map { it.path })
        assertTrue(parsed.virtioBlockNodes[0].readOnly)
        assertFalse(parsed.virtioBlockNodes[1].readOnly)
    }

    @Test
    fun f7_t1_dualDriveDiskPrimaryOrdersVdbFirstWithBootIndexZero() {
        val dtb = DeviceTreeBuilder.build(
            ramSizeBytes = 64L * 1024L * 1024L,
            targetArch = FdtTargetArch.RISCV64,
            virtioBlockDevices = listOf(
                VirtioBlockFdtDescriptor(VirtioBlockDevice.BASE_ADDRESS.toLong(), 0x1000, VirtioBlockDevice.IRQ_PRIMARY, 1, "vda", true),
                VirtioBlockFdtDescriptor(VirtioBlockDevice.SECONDARY_BASE_ADDRESS.toLong(), 0x1000, VirtioBlockDevice.IRQ_SECONDARY, 0, "vdb", false)
            )
        )
        val parsed = FdtInspector.parse(dtb)
        assertEquals(listOf("vdb" to 0, "vda" to 1), parsed.bootIndices)
        assertEquals("/soc/virtio_mmio@10003000", parsed.virtioBlockNodes.first().path)
    }

    @Test
    fun f7_t1_virtioBlockFdtDescriptorFromDeviceExtractsAccurateSlotMetadata() {
        val f1 = E2eTestFixtures.createTempBackingFile(tempFolder, "desc_vdb.img", 1024)
        VirtioBlockDevice(f1, VirtioBlockSlotConfig.targetDisk(bootIndex = 0)).use { vdb ->
            val desc = VirtioBlockFdtDescriptor.fromDevice(vdb)
            assertEquals(VirtioBlockDevice.SECONDARY_BASE_ADDRESS.toLong(), desc.baseAddress)
            assertEquals(VirtioBlockDevice.IRQ_SECONDARY, desc.irqNumber)
            assertEquals(0, desc.bootIndex)
            assertEquals("vdb", desc.deviceName)
            assertFalse(desc.readOnly)
        }
    }

    @Test
    fun f7_t2_emptyVirtioListWithHasVirtioBlockFalseEmitsZeroBlockNodes() {
        val dtb = DeviceTreeBuilder.build(
            ramSizeBytes = 64L * 1024L * 1024L,
            hasVirtioBlock = false,
            hasVirtioNet = true,
            virtioBlockDevices = emptyList()
        )
        val parsed = FdtInspector.parse(dtb)
        assertTrue(parsed.virtioBlockNodes.isEmpty())
        assertTrue(parsed.bootIndices.isEmpty())
        assertNotNull(parsed.nodes["/soc/virtio_mmio@10002000"])
    }

    @Test
    fun f7_t2_equalBootIndicesTieBreakDeterministicallyByMmioBaseAddress() {
        val dtb = DeviceTreeBuilder.build(
            ramSizeBytes = 64L * 1024L * 1024L,
            virtioBlockDevices = listOf(
                VirtioBlockFdtDescriptor(VirtioBlockDevice.SECONDARY_BASE_ADDRESS.toLong(), 0x1000, 13, 0, "vdb", false),
                VirtioBlockFdtDescriptor(VirtioBlockDevice.BASE_ADDRESS.toLong(), 0x1000, 11, 0, "vda", true)
            )
        )
        val parsed = FdtInspector.parse(dtb)
        assertEquals(listOf("vda", "vdb"), parsed.virtioBlockNodes.mapNotNull { it.deviceName })
    }

    @Test
    fun f7_t2_virtioNetNodeExcludedFromVirtioBlockNodesAndBootIndices() {
        val dtb = DeviceTreeBuilder.build(
            ramSizeBytes = 64L * 1024L * 1024L,
            hasVirtioBlock = true,
            hasVirtioNet = true
        )
        val parsed = FdtInspector.parse(dtb)
        assertEquals(1, parsed.virtioBlockNodes.size)
        assertEquals("vda", parsed.virtioBlockNodes.single().deviceName)
        assertNotNull(parsed.nodes["/soc/virtio_mmio@10002000"])
    }

    @Test
    fun f7_t2_framebufferPlusDualDriveDtbRemains8ByteAlignedAndValidReservedMemory() {
        val dtb = DeviceTreeBuilder.build(
            ramSizeBytes = 64L * 1024L * 1024L,
            framebufferConfig = DeviceTreeBuilder.FramebufferConfig(),
            targetArch = FdtTargetArch.RISCV64,
            virtioBlockDevices = listOf(
                VirtioBlockFdtDescriptor(VirtioBlockDevice.BASE_ADDRESS.toLong(), 0x1000, 11, 0, "vda", true),
                VirtioBlockFdtDescriptor(VirtioBlockDevice.SECONDARY_BASE_ADDRESS.toLong(), 0x1000, 13, 1, "vdb", false)
            )
        )
        assertEquals(0, dtb.size and 7)
        val parsed = FdtInspector.parse(dtb)
        assertNotNull(parsed.detectedFramebufferNode)
        assertNotNull(parsed.nodes["/reserved-memory"])
        assertEquals(2, parsed.virtioBlockNodes.size)
    }

    @Test
    fun f7_t2_blankBootArgsFallsBackToDefaultBootArgsOnRv64Fdt() {
        val dtb = DeviceTreeBuilder.build(ramSizeBytes = 64L * 1024L * 1024L, bootArgs = "   ")
        val parsed = FdtInspector.parse(dtb)
        assertEquals(DeviceTreeBuilder.DEFAULT_BOOTARGS, parsed.bootArgs)
    }

    // =========================================================================
    // Tier 3: Cross-Feature Pairwise Interactions (#3x#6, #5x#6, #6x#7)
    // =========================================================================

    @Test
    fun tier3_f3xF6_cpuMmioReadsKernVendorIdFromBothDualDriveSlots() {
        val f0 = E2eTestFixtures.createTempBackingFile(tempFolder, "pair_vda.iso", 1024)
        val f1 = E2eTestFixtures.createTempBackingFile(tempFolder, "pair_vdb.img", 1024)
        VirtioBlockDevice(f0, VirtioBlockSlotConfig.primaryIso(0)).use { vda ->
            VirtioBlockDevice(f1, VirtioBlockSlotConfig.targetDisk(1)).use { vdb ->
                val cpu = Rv32ImaCpuCore(IntArray(16 * 1024), Uart8250(), virtioBlk = vda, virtioBlkSecondary = vdb)
                val rom = E2eTestFixtures.assembleMmioReadAndPoweroff(
                    listOf(
                        (VirtioBlockDevice.BASE_ADDRESS + 0x00C) to 0x2000,
                        (VirtioBlockDevice.SECONDARY_BASE_ADDRESS + 0x00C) to 0x2004
                    )
                )
                cpu.loadBytesIntoRam(rom, 0)
                cpu.reset()
                assertEquals(Rv32ImaCpuCore.BATCH_POWEROFF, cpu.stepBatch(10_000))
                assertEquals(0x4E52454B, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2000))
                assertEquals(0x4E52454B, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2004))
            }
        }
    }

    @Test
    fun tier3_f5xF6_dualDriveVdbServes64BitHighAddressQueueIoViaGuestMemoryBus() {
        val highBase = 0x00000004_00000000L
        val bus = E2eTestFixtures.HighAddressGuestMemoryBus(highBase, 64 * 1024)
        val targetImg = E2eTestFixtures.createTempBackingFile(tempFolder, "vdb_high64.img", 2048)

        VirtioBlockDevice(targetImg, VirtioBlockSlotConfig.targetDisk(bootIndex = 0)).use { vdb ->
            val writeBuf = highBase + 0x4000L
            val readBuf = highBase + 0x5000L
            val statusAddr = highBase + 0x6000L
            val payload = ByteArray(512) { i -> ((i xor 0x5A) and 0xFF).toByte() }
            bus.writeBytes(writeBuf, payload)

            assertEquals(
                VirtioBlockDevice.VIRTIO_BLK_S_OK,
                submitBlkRequest64(vdb, bus, VirtioBlockDevice.VIRTIO_BLK_T_OUT, 1L, writeBuf, 512, statusAddr)
            )
            assertEquals(
                VirtioBlockDevice.VIRTIO_BLK_S_OK,
                submitBlkRequest64(vdb, bus, VirtioBlockDevice.VIRTIO_BLK_T_IN, 1L, readBuf, 512, statusAddr)
            )
            val actual = ByteArray(512) { i -> bus.readPhysicalByte(readBuf + i).toByte() }
            assertArrayEquals(payload, actual)
        }
    }

    @Test
    fun tier3_f6xF7_fdtDualDriveAddressesMatchCpuCoreMmioDispatch() {
        val f0 = E2eTestFixtures.createTempBackingFile(tempFolder, "fdt_vda.iso", 1024)
        val f1 = E2eTestFixtures.createTempBackingFile(tempFolder, "fdt_vdb.img", 2048)
        VirtioBlockDevice(f0, VirtioBlockSlotConfig.primaryIso(bootIndex = 1)).use { vda ->
            VirtioBlockDevice(f1, VirtioBlockSlotConfig.targetDisk(bootIndex = 0)).use { vdb ->
                val dtb = DeviceTreeBuilder.build(
                    ramSizeBytes = 64L * 1024L * 1024L,
                    targetArch = FdtTargetArch.RISCV64,
                    virtioBlockDevices = listOf(vda, vdb).map(VirtioBlockFdtDescriptor::fromDevice)
                )
                val parsed = FdtInspector.parse(dtb)
                assertEquals(2, parsed.virtioBlockNodes.size)
                assertEquals(listOf("vdb" to 0, "vda" to 1), parsed.bootIndices)

                val cpu = Rv32ImaCpuCore(IntArray(16 * 1024), Uart8250(), virtioBlk = vda, virtioBlkSecondary = vdb)
                val reads = parsed.virtioBlockNodes.mapIndexed { idx, node ->
                    (node.baseAddress.toInt() + 0x100) to (0x2000 + idx * 4)
                }
                val rom = E2eTestFixtures.assembleMmioReadAndPoweroff(reads)
                cpu.loadBytesIntoRam(rom, 0)
                cpu.reset()
                assertEquals(Rv32ImaCpuCore.BATCH_POWEROFF, cpu.stepBatch(10_000))

                // First sorted FDT node is vdb (4 sectors), second is vda (2 sectors)
                assertEquals(4, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2000))
                assertEquals(2, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2004))
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun configureQueue64(
        blk: VirtioBlockDevice,
        bus: GuestMemoryBus,
        qSize: Int,
        descAddr: Long,
        availAddr: Long,
        usedAddr: Long
    ) {
        blk.writeRegister(0x070, 0, bus)
        blk.writeRegister(0x030, 0, bus)
        blk.writeRegister(0x038, qSize, bus)
        blk.writeRegister(0x080, (descAddr and 0xFFFFFFFFL).toInt(), bus)
        blk.writeRegister(0x084, (descAddr ushr 32).toInt(), bus)
        blk.writeRegister(0x090, (availAddr and 0xFFFFFFFFL).toInt(), bus)
        blk.writeRegister(0x094, (availAddr ushr 32).toInt(), bus)
        blk.writeRegister(0x0A0, (usedAddr and 0xFFFFFFFFL).toInt(), bus)
        blk.writeRegister(0x0A4, (usedAddr ushr 32).toInt(), bus)
        blk.writeRegister(0x044, 1, bus)
    }

    private fun configureQueue64(
        net: VirtioNetDevice,
        bus: GuestMemoryBus,
        queueSel: Int,
        qSize: Int,
        descAddr: Long,
        availAddr: Long,
        usedAddr: Long
    ) {
        net.writeRegister(0x070, 0, bus)
        net.writeRegister(0x030, queueSel, bus)
        net.writeRegister(0x038, qSize, bus)
        net.writeRegister(0x080, (descAddr and 0xFFFFFFFFL).toInt(), bus)
        net.writeRegister(0x084, (descAddr ushr 32).toInt(), bus)
        net.writeRegister(0x090, (availAddr and 0xFFFFFFFFL).toInt(), bus)
        net.writeRegister(0x094, (availAddr ushr 32).toInt(), bus)
        net.writeRegister(0x0A0, (usedAddr and 0xFFFFFFFFL).toInt(), bus)
        net.writeRegister(0x0A4, (usedAddr ushr 32).toInt(), bus)
        net.writeRegister(0x044, 1, bus)
    }

    private fun writeDesc64(
        bus: GuestMemoryBus,
        descBase: Long,
        index: Int,
        addr: Long,
        len: Int,
        flags: Int,
        next: Int
    ) {
        val base = descBase + (index * 16).toLong()
        bus.writePhysicalU32(base, (addr and 0xFFFFFFFFL).toInt())
        bus.writePhysicalU32(base + 4L, (addr ushr 32).toInt())
        bus.writePhysicalU32(base + 8L, len)
        bus.writePhysicalU32(base + 12L, (flags and 0xFFFF) or ((next and 0xFFFF) shl 16))
    }

    private fun submitBlkRequest64(
        dev: VirtioBlockDevice,
        bus: GuestMemoryBus,
        reqType: Int,
        sector: Long,
        dataBufAddr: Long,
        dataLen: Int,
        statusAddr: Long
    ): Int {
        val descBase = bus.ramBaseAddress + 0x1000L
        val availBase = bus.ramBaseAddress + 0x2000L
        val usedBase = bus.ramBaseAddress + 0x2800L
        val hdrAddr = bus.ramBaseAddress + 0x3000L

        configureQueue64(dev, bus, 16, descBase, availBase, usedBase)

        bus.writePhysicalU32(hdrAddr, reqType)
        bus.writePhysicalU32(hdrAddr + 4L, 0)
        bus.writePhysicalU32(hdrAddr + 8L, (sector and 0xFFFFFFFFL).toInt())
        bus.writePhysicalU32(hdrAddr + 12L, (sector ushr 32).toInt())

        writeDesc64(bus, descBase, 0, hdrAddr, 16, VirtioBlockDevice.VIRTQ_DESC_F_NEXT, 1)
        writeDesc64(bus, descBase, 1, dataBufAddr, dataLen, VirtioBlockDevice.VIRTQ_DESC_F_NEXT, 2)
        writeDesc64(bus, descBase, 2, statusAddr, 1, 0, 0)

        bus.writePhysicalU32(usedBase, 0)
        bus.writePhysicalU32(availBase, 0x00010000)
        bus.writePhysicalU16(availBase + 4L, 0)
        dev.writeRegister(0x050, 0, bus)

        assertEquals(1, bus.readPhysicalU16(usedBase + 2L))
        return bus.readPhysicalByte(statusAddr)
    }
}
