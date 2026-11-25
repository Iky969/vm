package com.example

import com.example.emulator.DeviceTreeBuilder
import com.example.emulator.FdtInspector
import com.example.emulator.Rv32ImaCpuCore
import com.example.emulator.Uart8250
import com.example.emulator.VirtioNetDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtioNetDeviceTest {

    @Test
    fun virtioNetRegistersAndFeaturesMatchSpec() {
        val dev = VirtioNetDevice()
        assertEquals(VirtioNetDevice.VIRTIO_MAGIC, dev.readRegister(0x000))
        assertEquals(VirtioNetDevice.VIRTIO_VERSION_2, dev.readRegister(0x004))
        assertEquals(VirtioNetDevice.VIRTIO_DEVICE_ID_NET, dev.readRegister(0x008))
        assertEquals(0x4E52454B, VirtioNetDevice.VIRTIO_VENDOR_ID)
        assertEquals(VirtioNetDevice.VIRTIO_VENDOR_ID, dev.readRegister(0x00C))

        // Device features: sel 0 -> VIRTIO_NET_F_MAC and VIRTIO_NET_F_STATUS
        dev.writeRegister(0x014, 0, dummyCpu())
        val feat0 = dev.readRegister(0x010)
        assertTrue((feat0 and VirtioNetDevice.VIRTIO_NET_F_MAC) != 0)
        assertTrue((feat0 and VirtioNetDevice.VIRTIO_NET_F_STATUS) != 0)

        // Device features: sel 1 -> VIRTIO_F_VERSION_1
        dev.writeRegister(0x014, 1, dummyCpu())
        val feat1 = dev.readRegister(0x010)
        assertEquals(VirtioNetDevice.VIRTIO_F_VERSION_1, feat1)

        // QueueNumMax: Queue 0 (RX) and Queue 1 (TX) return 64; Queue 2 returns 0
        dev.writeRegister(0x030, 0, dummyCpu())
        assertEquals(VirtioNetDevice.QUEUE_MAX_SIZE, dev.readRegister(0x034))
        dev.writeRegister(0x030, 1, dummyCpu())
        assertEquals(VirtioNetDevice.QUEUE_MAX_SIZE, dev.readRegister(0x034))
        dev.writeRegister(0x030, 2, dummyCpu())
        assertEquals(0, dev.readRegister(0x034))

        // Config space (0x100..0x10B)
        // MAC address: 52:54:00:12:34:56
        val word0 = dev.readRegister(0x100)
        assertEquals(0x52, word0 and 0xFF)
        assertEquals(0x54, (word0 ushr 8) and 0xFF)
        assertEquals(0x00, (word0 ushr 16) and 0xFF)
        assertEquals(0x12, (word0 ushr 24) and 0xFF)

        val word1 = dev.readRegister(0x104)
        assertEquals(0x34, word1 and 0xFF)
        assertEquals(0x56, (word1 ushr 8) and 0xFF)
        assertEquals(VirtioNetDevice.VIRTIO_NET_S_LINK_UP, (word1 ushr 16) and 0xFFFF)

        val word2 = dev.readRegister(0x108)
        assertEquals(1, word2 and 0xFFFF) // max_virtqueue_pairs = 1
        assertEquals(1500, (word2 ushr 16) and 0xFFFF) // MTU = 1500

        assertEquals("52:54:00:12:34:56", dev.formattedMacAddress())
    }

    @Test
    fun deviceTreeBuilderEmitsVirtioNetNodeAt10002000() {
        val dtbWithout = DeviceTreeBuilder.build(
            ramSizeBytes = 64L * 1024L * 1024L,
            hasVirtioNet = false
        )
        val parsedWithout = FdtInspector.parse(dtbWithout)
        assertNull(parsedWithout.nodes["/soc/virtio_mmio@10002000"])

        val dtbWith = DeviceTreeBuilder.build(
            ramSizeBytes = 64L * 1024L * 1024L,
            hasVirtioNet = true
        )
        val parsedWith = FdtInspector.parse(dtbWith)
        val netNode = parsedWith.nodes["/soc/virtio_mmio@10002000"]
        assertNotNull(netNode)
        assertEquals("virtio,mmio", netNode!!.stringProp("compatible"))
        assertEquals(
            listOf(VirtioNetDevice.BASE_ADDRESS, VirtioNetDevice.REGION_SIZE),
            netNode.u32ListProp("reg")
        )
        assertEquals(listOf(1, 11), netNode.u32ListProp("interrupts-extended"))
        assertEquals("okay", netNode.stringProp("status"))
    }

    @Test
    fun virtioNetArpResponderGeneratesValidReply() {
        val cpu = dummyCpu()
        val dev = VirtioNetDevice()

        // Set up Queue 0 (RX) and Queue 1 (TX)
        val rxDescBase = Rv32ImaCpuCore.RAM_BASE + 0x1000
        val rxAvailBase = Rv32ImaCpuCore.RAM_BASE + 0x1200
        val rxUsedBase = Rv32ImaCpuCore.RAM_BASE + 0x1400

        val txDescBase = Rv32ImaCpuCore.RAM_BASE + 0x2000
        val txAvailBase = Rv32ImaCpuCore.RAM_BASE + 0x2200
        val txUsedBase = Rv32ImaCpuCore.RAM_BASE + 0x2400

        // Init device
        dev.writeRegister(0x070, 0, cpu) // reset

        // Configure Queue 0 (RX)
        dev.writeRegister(0x030, 0, cpu)
        dev.writeRegister(0x038, 16, cpu)
        dev.writeRegister(0x080, rxDescBase, cpu)
        dev.writeRegister(0x090, rxAvailBase, cpu)
        dev.writeRegister(0x0A0, rxUsedBase, cpu)
        dev.writeRegister(0x044, 1, cpu)

        // Configure Queue 1 (TX)
        dev.writeRegister(0x030, 1, cpu)
        dev.writeRegister(0x038, 16, cpu)
        dev.writeRegister(0x080, txDescBase, cpu)
        dev.writeRegister(0x090, txAvailBase, cpu)
        dev.writeRegister(0x0A0, txUsedBase, cpu)
        dev.writeRegister(0x044, 1, cpu)

        // Provide an RX buffer in Queue 0
        val rxBufAddr = Rv32ImaCpuCore.RAM_BASE + 0x3000
        writeDesc(cpu, rxDescBase, 0, rxBufAddr, 1536, VirtioNetDevice.VIRTQ_DESC_F_WRITE, 0)
        writeWord(cpu, rxUsedBase, 0)
        writeWord(cpu, rxAvailBase, 0x00010000) // flags=0, idx=1
        writeWord(cpu, rxAvailBase + 4, 0)      // ring[0]=0

        // Prepare ARP Request in Queue 1:
        // Guest (52:54:00:12:34:56 / 192.168.1.100) asks "Who has 192.168.1.1?"
        val arpRequest = createArpRequestPacket(
            srcMac = byteArrayOf(0x52, 0x54, 0x00, 0x12, 0x34, 0x56),
            srcIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 100),
            targetIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 1)
        )

        val txHdrAddr = Rv32ImaCpuCore.RAM_BASE + 0x4000
        val txPacketAddr = Rv32ImaCpuCore.RAM_BASE + 0x4100
        // virtio_net_hdr (10 bytes of 0)
        for (i in 0 until 10) cpu.ram[(txHdrAddr - Rv32ImaCpuCore.RAM_BASE + i) ushr 2] = 0
        cpu.loadBytesIntoRam(arpRequest, txPacketAddr - Rv32ImaCpuCore.RAM_BASE)

        // TX Descriptor 0: header -> next=1
        writeDesc(cpu, txDescBase, 0, txHdrAddr, 10, VirtioNetDevice.VIRTQ_DESC_F_NEXT, 1)
        // TX Descriptor 1: packet data -> terminal
        writeDesc(cpu, txDescBase, 1, txPacketAddr, arpRequest.size, 0, 0)

        writeWord(cpu, txUsedBase, 0)
        writeWord(cpu, txAvailBase, 0x00010000) // flags=0, idx=1
        writeWord(cpu, txAvailBase + 4, 0)      // ring[0]=0

        // Notify Queue 1 (TX)
        dev.writeRegister(0x050, 1, cpu)

        assertEquals(1, dev.packetsTx)
        assertEquals(arpRequest.size.toLong(), dev.bytesTx)

        // Queue 0 (RX) should have received the ARP Reply!
        val rxUsedIdx = cpu.readPhysicalByte(rxUsedBase + 2) or (cpu.readPhysicalByte(rxUsedBase + 3) shl 8)
        assertEquals("RX Used Ring must advance to 1", 1, rxUsedIdx)
        assertEquals(1, dev.packetsRx)

        // Read received packet from rxBufAddr (skipping 10-byte virtio_net_hdr)
        val packetOffset = rxBufAddr + 10
        val replyEthType = (cpu.readPhysicalByte(packetOffset + 12) shl 8) or cpu.readPhysicalByte(packetOffset + 13)
        assertEquals(0x0806, replyEthType) // ARP

        val replyOpcode = (cpu.readPhysicalByte(packetOffset + 20) shl 8) or cpu.readPhysicalByte(packetOffset + 21)
        assertEquals(0x0002, replyOpcode) // ARP Reply

        // Sender MAC must be gateway MAC
        val senderMac = ByteArray(6) { i -> cpu.readPhysicalByte(packetOffset + 22 + i).toByte() }
        assertArrayEquals(dev.gatewayMacBytes, senderMac)

        // Sender IP must be 192.168.1.1
        val senderIp = ByteArray(4) { i -> cpu.readPhysicalByte(packetOffset + 28 + i).toByte() }
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 1), senderIp)

        // Target IP must be 192.168.1.100
        val targetIp = ByteArray(4) { i -> cpu.readPhysicalByte(packetOffset + 38 + i).toByte() }
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 100), targetIp)
    }

    @Test
    fun virtioNetIcmpEchoResponderRepliesToPing() {
        val cpu = dummyCpu()
        val dev = VirtioNetDevice()

        val rxDescBase = Rv32ImaCpuCore.RAM_BASE + 0x1000
        val rxAvailBase = Rv32ImaCpuCore.RAM_BASE + 0x1200
        val rxUsedBase = Rv32ImaCpuCore.RAM_BASE + 0x1400

        val txDescBase = Rv32ImaCpuCore.RAM_BASE + 0x2000
        val txAvailBase = Rv32ImaCpuCore.RAM_BASE + 0x2200
        val txUsedBase = Rv32ImaCpuCore.RAM_BASE + 0x2400

        // Init & setup queues
        dev.writeRegister(0x070, 0, cpu)
        dev.writeRegister(0x030, 0, cpu)
        dev.writeRegister(0x038, 16, cpu)
        dev.writeRegister(0x080, rxDescBase, cpu)
        dev.writeRegister(0x090, rxAvailBase, cpu)
        dev.writeRegister(0x0A0, rxUsedBase, cpu)
        dev.writeRegister(0x044, 1, cpu)

        dev.writeRegister(0x030, 1, cpu)
        dev.writeRegister(0x038, 16, cpu)
        dev.writeRegister(0x080, txDescBase, cpu)
        dev.writeRegister(0x090, txAvailBase, cpu)
        dev.writeRegister(0x0A0, txUsedBase, cpu)
        dev.writeRegister(0x044, 1, cpu)

        // Supply RX buffer
        val rxBufAddr = Rv32ImaCpuCore.RAM_BASE + 0x3000
        writeDesc(cpu, rxDescBase, 0, rxBufAddr, 1536, VirtioNetDevice.VIRTQ_DESC_F_WRITE, 0)
        writeWord(cpu, rxUsedBase, 0)
        writeWord(cpu, rxAvailBase, 0x00010000)
        writeWord(cpu, rxAvailBase + 4, 0)

        // Prepare ICMP Echo Request (Ping)
        val pingPacket = createIcmpEchoRequestPacket(
            srcMac = byteArrayOf(0x52, 0x54, 0x00, 0x12, 0x34, 0x56),
            dstMac = dev.gatewayMacBytes,
            srcIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 100),
            dstIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 1),
            identifier = 0x1234,
            sequence = 1
        )

        val txPacketAddr = Rv32ImaCpuCore.RAM_BASE + 0x4000
        // Single descriptor containing 10-byte virtio_net_hdr + pingPacket
        val combined = ByteArray(10 + pingPacket.size)
        System.arraycopy(pingPacket, 0, combined, 10, pingPacket.size)
        cpu.loadBytesIntoRam(combined, txPacketAddr - Rv32ImaCpuCore.RAM_BASE)

        writeDesc(cpu, txDescBase, 0, txPacketAddr, combined.size, 0, 0)
        writeWord(cpu, txUsedBase, 0)
        writeWord(cpu, txAvailBase, 0x00010000)
        writeWord(cpu, txAvailBase + 4, 0)

        // Transmit ping
        dev.writeRegister(0x050, 1, cpu)

        assertEquals(1, dev.packetsTx)
        assertEquals(1, dev.packetsRx)

        // Verify ICMP Reply
        val replyOffset = rxBufAddr + 10
        val ethType = (cpu.readPhysicalByte(replyOffset + 12) shl 8) or cpu.readPhysicalByte(replyOffset + 13)
        assertEquals(0x0800, ethType) // IPv4

        val protocol = cpu.readPhysicalByte(replyOffset + 23)
        assertEquals(1, protocol) // ICMP

        val icmpType = cpu.readPhysicalByte(replyOffset + 34)
        assertEquals("ICMP type must be 0 (Echo Reply)", 0, icmpType)

        // Verify swapped IP addresses
        val replySrcIp = ByteArray(4) { i -> cpu.readPhysicalByte(replyOffset + 26 + i).toByte() }
        val replyDstIp = ByteArray(4) { i -> cpu.readPhysicalByte(replyOffset + 30 + i).toByte() }
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 1), replySrcIp)
        assertArrayEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 100), replyDstIp)
    }

    private fun dummyCpu(): Rv32ImaCpuCore =
        Rv32ImaCpuCore(ram = IntArray(128 * 1024), uart = Uart8250())

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

    private fun createArpRequestPacket(srcMac: ByteArray, srcIp: ByteArray, targetIp: ByteArray): ByteArray {
        val pkt = ByteArray(42)
        // Eth header
        for (i in 0 until 6) pkt[i] = 0xFF.toByte() // Broadcast
        System.arraycopy(srcMac, 0, pkt, 6, 6)
        pkt[12] = 0x08.toByte(); pkt[13] = 0x06.toByte() // ARP

        // ARP header
        pkt[14] = 0x00.toByte(); pkt[15] = 0x01.toByte()
        pkt[16] = 0x08.toByte(); pkt[17] = 0x00.toByte()
        pkt[18] = 6; pkt[19] = 4
        pkt[20] = 0x00.toByte(); pkt[21] = 0x01.toByte() // Request
        System.arraycopy(srcMac, 0, pkt, 22, 6)
        System.arraycopy(srcIp, 0, pkt, 28, 4)
        for (i in 0 until 6) pkt[32 + i] = 0
        System.arraycopy(targetIp, 0, pkt, 38, 4)
        return pkt
    }

    private fun createIcmpEchoRequestPacket(
        srcMac: ByteArray,
        dstMac: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        identifier: Int,
        sequence: Int
    ): ByteArray {
        val pkt = ByteArray(14 + 20 + 8 + 32)
        // Eth header (14)
        System.arraycopy(dstMac, 0, pkt, 0, 6)
        System.arraycopy(srcMac, 0, pkt, 6, 6)
        pkt[12] = 0x08.toByte(); pkt[13] = 0x00.toByte() // IPv4

        // IPv4 Header (20)
        pkt[14] = 0x45.toByte() // Version 4, IHL 5
        pkt[15] = 0x00.toByte()
        val totalLen = 20 + 8 + 32
        pkt[16] = (totalLen ushr 8).toByte()
        pkt[17] = (totalLen and 0xFF).toByte()
        pkt[18] = 0x12.toByte(); pkt[19] = 0x34.toByte() // ID
        pkt[20] = 0x40.toByte(); pkt[21] = 0x00.toByte() // DF
        pkt[22] = 64.toByte() // TTL
        pkt[23] = 1.toByte() // Protocol ICMP
        System.arraycopy(srcIp, 0, pkt, 26, 4)
        System.arraycopy(dstIp, 0, pkt, 30, 4)

        // IP Checksum
        val ipCsum = computeChecksum(pkt, 14, 20)
        pkt[24] = (ipCsum ushr 8).toByte()
        pkt[25] = (ipCsum and 0xFF).toByte()

        // ICMP Echo Request (8 bytes + 32 payload)
        pkt[34] = 8.toByte() // Type 8 (Echo)
        pkt[35] = 0.toByte() // Code 0
        pkt[36] = 0.toByte(); pkt[37] = 0.toByte() // Checksum placeholder
        pkt[38] = (identifier ushr 8).toByte()
        pkt[39] = (identifier and 0xFF).toByte()
        pkt[40] = (sequence ushr 8).toByte()
        pkt[41] = (sequence and 0xFF).toByte()

        for (i in 0 until 32) {
            pkt[42 + i] = (0x61 + (i % 26)).toByte()
        }

        val icmpCsum = computeChecksum(pkt, 34, 8 + 32)
        pkt[36] = (icmpCsum ushr 8).toByte()
        pkt[37] = (icmpCsum and 0xFF).toByte()
        return pkt
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = 0
        while (i < length - 1) {
            val w = ((data[offset + i].toInt() and 0xFF) shl 8) or (data[offset + i + 1].toInt() and 0xFF)
            sum += w
            i += 2
        }
        if (i < length) {
            sum += ((data[offset + i].toInt() and 0xFF) shl 8)
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFFL) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }
}
