package com.example.emulator

import java.io.ByteArrayOutputStream

/**
 * VirtIO-MMIO v2 Network Device mapped at 0x10002000..0x10002FFF.
 *
 * Implements standard VirtIO MMIO v2 network interface (specification v1.1):
 * - Magic = 0x74726976 ("virt"), Version = 2, DeviceId = 1 (VIRTIO_ID_NET)
 * - Device features: VIRTIO_NET_F_MAC (bit 5), VIRTIO_NET_F_STATUS (bit 16), VIRTIO_F_VERSION_1 (bit 32)
 * - Config space: MAC address (default 52:54:00:12:34:56), status = LINK_UP, max_virtqueue_pairs = 1, MTU = 1500
 * - Virtqueues:
 *   - Queue 0: RX (guest supplies receive buffers)
 *   - Queue 1: TX (guest transmits outgoing packets)
 * - User-space networking engine:
 *   - Automatic ARP responder (resolves gateway MAC 52:54:00:12:34:01 for guest queries)
 *   - ICMP Echo responder (replies to guest ping with RFC 1071 Internet checksum)
 *   - Loopback mode and socket bridge injection interface
 */
class VirtioNetDevice(
    val macBytes: ByteArray = byteArrayOf(0x52, 0x54, 0x00, 0x12, 0x34, 0x56),
    var loopbackEnabled: Boolean = true
) {
    init {
        require(macBytes.size == 6) { "MAC address must be exactly 6 bytes" }
    }

    val gatewayMacBytes: ByteArray = byteArrayOf(0x52, 0x54, 0x00, 0x12, 0x34, 0x01)

    // Config space buffer (12 bytes: 6 bytes MAC, 2 bytes status, 2 bytes max_vq_pairs, 2 bytes mtu)
    private val configBytes = ByteArray(12).apply {
        System.arraycopy(macBytes, 0, this, 0, 6)
        this[6] = (VIRTIO_NET_S_LINK_UP and 0xFF).toByte()
        this[7] = ((VIRTIO_NET_S_LINK_UP ushr 8) and 0xFF).toByte()
        this[8] = 1 // max_virtqueue_pairs = 1
        this[9] = 0
        this[10] = (1500 and 0xFF).toByte() // MTU = 1500
        this[11] = ((1500 ushr 8) and 0xFF).toByte()
    }

    class VirtQueue(val index: Int) {
        var num: Int = QUEUE_MAX_SIZE
        var ready: Int = 0
        var descLow: Int = 0
        var descHigh: Int = 0
        var availLow: Int = 0
        var availHigh: Int = 0
        var usedLow: Int = 0
        var usedHigh: Int = 0
        var lastAvailIdx: Int = 0

        fun reset() {
            num = QUEUE_MAX_SIZE
            ready = 0
            descLow = 0
            descHigh = 0
            availLow = 0
            availHigh = 0
            usedLow = 0
            usedHigh = 0
            lastAvailIdx = 0
        }

        fun descBase(): Long = combineAddress64(descLow, descHigh)
        fun availBase(): Long = combineAddress64(availLow, availHigh)
        fun usedBase(): Long = combineAddress64(usedLow, usedHigh)
    }

    private val rxQueue = VirtQueue(0)
    private val txQueue = VirtQueue(1)

    private var deviceFeaturesSel = 0
    private var driverFeaturesSel = 0
    var driverFeaturesLow = 0
        private set
    var driverFeaturesHigh = 0
        private set

    private var queueSel = 0
    var status = 0
        private set
    var interruptStatus = 0
        private set

    val hasPendingInterrupt: Boolean
        get() = interruptStatus != 0

    val pendingRxPackets = ArrayDeque<ByteArray>()

    // Telemetry counters
    var packetsTx: Long = 0L
        private set
    var packetsRx: Long = 0L
        private set
    var bytesTx: Long = 0L
        private set
    var bytesRx: Long = 0L
        private set

    /**
     * Optional listener invoked when guest Linux transmits an Ethernet frame.
     */
    var onPacketTransmitted: ((ByteArray) -> Unit)? = null

    fun reset() {
        deviceFeaturesSel = 0
        driverFeaturesSel = 0
        driverFeaturesLow = 0
        driverFeaturesHigh = 0
        queueSel = 0
        status = 0
        interruptStatus = 0
        rxQueue.reset()
        txQueue.reset()
        pendingRxPackets.clear()
        packetsTx = 0L
        packetsRx = 0L
        bytesTx = 0L
        bytesRx = 0L
    }

    fun formattedMacAddress(): String =
        macBytes.joinToString(":") { "%02X".format(it) }

    private fun getQueue(sel: Int): VirtQueue? = when (sel) {
        0 -> rxQueue
        1 -> txQueue
        else -> null
    }

    fun readRegister(offset: Int): Int {
        return when (offset) {
            0x000 -> VIRTIO_MAGIC
            0x004 -> VIRTIO_VERSION_2
            0x008 -> VIRTIO_DEVICE_ID_NET
            0x00C -> VIRTIO_VENDOR_ID
            0x010 -> { // DeviceFeatures
                if (deviceFeaturesSel == 0) {
                    VIRTIO_NET_F_MAC or VIRTIO_NET_F_STATUS
                } else {
                    VIRTIO_F_VERSION_1
                }
            }
            0x034 -> { // QueueNumMax
                if (queueSel == 0 || queueSel == 1) QUEUE_MAX_SIZE else 0
            }
            0x044 -> { // QueueReady
                getQueue(queueSel)?.ready ?: 0
            }
            0x060 -> interruptStatus
            0x070 -> status
            0x0FC -> 0 // ConfigGeneration
            else -> {
                if (offset >= 0x100 && offset < 0x100 + configBytes.size) {
                    readConfigWord(offset - 0x100)
                } else {
                    0
                }
            }
        }
    }

    private fun readConfigWord(cfgOff: Int): Int {
        var v = 0
        for (b in 0 until 4) {
            val idx = cfgOff + b
            if (idx < configBytes.size) {
                v = v or ((configBytes[idx].toInt() and 0xFF) shl (b * 8))
            }
        }
        return v
    }

    fun writeRegister(offset: Int, value: Int, bus: GuestMemoryBus) {
        when (offset) {
            0x014 -> deviceFeaturesSel = value
            0x020 -> {
                if (driverFeaturesSel == 0) driverFeaturesLow = value else driverFeaturesHigh = value
            }
            0x024 -> driverFeaturesSel = value
            0x030 -> queueSel = value
            0x038 -> {
                getQueue(queueSel)?.num = value.coerceIn(1, QUEUE_MAX_SIZE)
            }
            0x044 -> {
                getQueue(queueSel)?.ready = value and 1
            }
            0x050 -> { // QueueNotify
                when (value) {
                    0 -> processRxNotify(bus)
                    1 -> processTxQueue(bus)
                }
            }
            0x064 -> interruptStatus = interruptStatus and value.inv() // InterruptACK
            0x070 -> {
                if (value == 0) reset() else status = value
            }
            0x080 -> getQueue(queueSel)?.descLow = value
            0x084 -> getQueue(queueSel)?.descHigh = value
            0x090 -> getQueue(queueSel)?.availLow = value
            0x094 -> getQueue(queueSel)?.availHigh = value
            0x0A0 -> getQueue(queueSel)?.usedLow = value
            0x0A4 -> getQueue(queueSel)?.usedHigh = value
        }
    }

    private fun processRxNotify(bus: GuestMemoryBus) {
        while (pendingRxPackets.isNotEmpty()) {
            val pkt = pendingRxPackets.first()
            if (receivePacket(bus, pkt)) {
                pendingRxPackets.removeFirst()
            } else {
                break
            }
        }
    }

    private fun processTxQueue(bus: GuestMemoryBus) {
        val descBase = txQueue.descBase()
        val availBase = txQueue.availBase()
        val usedBase = txQueue.usedBase()
        if (descBase == 0L || availBase == 0L || usedBase == 0L) return

        val availIdx = bus.readPhysicalU16(availBase + 2L)
        val qSize = txQueue.num.coerceAtLeast(1)

        while (txQueue.lastAvailIdx != availIdx) {
            val ringSlot = txQueue.lastAvailIdx % qSize
            val headDescIdx = bus.readPhysicalU16(availBase + 4L + (ringSlot * 2).toLong())

            // Extract transmitted Ethernet frame from descriptor chain
            val packetData = extractTxPacket(bus, descBase, qSize, headDescIdx)

            // Append completion to TX Used Ring
            val usedIdx = bus.readPhysicalU16(usedBase + 2L)
            val usedSlot = usedIdx % qSize
            val usedElemAddr = usedBase + 4L + (usedSlot * 8).toLong()
            bus.writePhysicalU32(usedElemAddr, headDescIdx)
            bus.writePhysicalU32(usedElemAddr + 4L, 0)
            bus.writePhysicalU16(usedBase + 2L, (usedIdx + 1) and 0xFFFF)

            txQueue.lastAvailIdx = (txQueue.lastAvailIdx + 1) and 0xFFFF
            interruptStatus = interruptStatus or 1

            if (packetData != null && packetData.isNotEmpty()) {
                packetsTx++
                bytesTx += packetData.size
                onPacketTransmitted?.invoke(packetData)
                handleOutgoingPacket(bus, packetData)
            }
        }
    }

    private fun extractTxPacket(
        bus: GuestMemoryBus,
        descBase: Long,
        qSize: Int,
        headIdx: Int
    ): ByteArray? {
        if (headIdx !in 0 until qSize) return null
        val out = ByteArrayOutputStream()
        var curIdx = headIdx
        var guard = 0
        var isFirst = true

        while (guard++ < QUEUE_MAX_SIZE && curIdx in 0 until qSize) {
            val dAddr = descBase + (curIdx * 16).toLong()
            val bufAddr = bus.readPhysicalU64(dAddr)
            val rawBufLen = bus.readPhysicalWord(dAddr + 8L)
            val flags = bus.readPhysicalU16(dAddr + 12L)
            val nextIdx = bus.readPhysicalU16(dAddr + 14L)

            if (rawBufLen < 0 || rawBufLen > MAX_BUFFER_BYTES) {
                break
            }
            val bufLen = rawBufLen.coerceIn(0, MAX_BUFFER_BYTES)

            val chunk = ByteArray(bufLen)
            for (i in 0 until bufLen) {
                chunk[i] = bus.readPhysicalByte(bufAddr + i.toLong()).toByte()
            }

            if (isFirst) {
                // Determine virtio_net_hdr size (12 bytes for VIRTIO_F_VERSION_1, 10 bytes for legacy)
                val hdrSize = if (bufLen >= 12 && (driverFeaturesHigh and 1 != 0)) 12 else 10
                if (bufLen > hdrSize) {
                    out.write(chunk, hdrSize, bufLen - hdrSize)
                }
                isFirst = false
            } else {
                out.write(chunk)
            }

            if ((flags and VIRTQ_DESC_F_NEXT) == 0) break
            curIdx = nextIdx
        }
        val result = out.toByteArray()
        return if (result.isNotEmpty()) result else null
    }

    /**
     * Injects an incoming Ethernet packet into guest memory via RX Queue (Queue 0).
     * Automatically prepends a standard virtio_net_hdr.
     * Returns true if packet was delivered to guest buffers, or false if buffered into [pendingRxPackets].
     */
    fun receivePacket(bus: GuestMemoryBus, packet: ByteArray): Boolean {
        val descBase = rxQueue.descBase()
        val availBase = rxQueue.availBase()
        val usedBase = rxQueue.usedBase()
        if (rxQueue.ready == 0 || descBase == 0L || availBase == 0L || usedBase == 0L) {
            if (pendingRxPackets.size < 32) pendingRxPackets.add(packet)
            return false
        }

        val availIdx = bus.readPhysicalU16(availBase + 2L)
        if (rxQueue.lastAvailIdx == availIdx) {
            if (pendingRxPackets.size < 32) pendingRxPackets.add(packet)
            return false
        }

        val qSize = rxQueue.num.coerceAtLeast(1)
        val ringSlot = rxQueue.lastAvailIdx % qSize
        val headDescIdx = bus.readPhysicalU16(availBase + 4L + (ringSlot * 2).toLong())
        if (headDescIdx !in 0 until qSize) return false

        val hdrSize = if (driverFeaturesHigh and 1 != 0) 12 else 10
        val fullData = ByteArray(hdrSize + packet.size)
        // virtio_net_hdr header bytes are zero (flags=0, gso_type=0, etc.)
        System.arraycopy(packet, 0, fullData, hdrSize, packet.size)

        var curIdx = headDescIdx
        var guard = 0
        var dataOffset = 0

        while (guard++ < QUEUE_MAX_SIZE && dataOffset < fullData.size && curIdx in 0 until qSize) {
            val dAddr = descBase + (curIdx * 16).toLong()
            val bufAddr = bus.readPhysicalU64(dAddr)
            val rawBufLen = bus.readPhysicalWord(dAddr + 8L)
            val flags = bus.readPhysicalU16(dAddr + 12L)
            val nextIdx = bus.readPhysicalU16(dAddr + 14L)

            if (rawBufLen < 0 || rawBufLen > MAX_BUFFER_BYTES) {
                break
            }
            val bufLen = rawBufLen.coerceIn(0, MAX_BUFFER_BYTES)

            val toWrite = minOf(bufLen, fullData.size - dataOffset)
            for (i in 0 until toWrite) {
                bus.writePhysicalByte(bufAddr + i.toLong(), fullData[dataOffset + i].toInt() and 0xFF)
            }
            dataOffset += toWrite

            if ((flags and VIRTQ_DESC_F_NEXT) == 0) break
            curIdx = nextIdx
        }

        // Add to RX Used Ring
        val usedIdx = bus.readPhysicalU16(usedBase + 2L)
        val usedSlot = usedIdx % qSize
        val usedElemAddr = usedBase + 4L + (usedSlot * 8).toLong()
        bus.writePhysicalU32(usedElemAddr, headDescIdx)
        bus.writePhysicalU32(usedElemAddr + 4L, dataOffset)
        bus.writePhysicalU16(usedBase + 2L, (usedIdx + 1) and 0xFFFF)

        rxQueue.lastAvailIdx = (rxQueue.lastAvailIdx + 1) and 0xFFFF
        interruptStatus = interruptStatus or 1
        packetsRx++
        bytesRx += packet.size
        return true
    }

    /**
     * Internal user-space networking handler for outgoing Ethernet packets from guest Linux.
     * Handles ARP requests and ICMP Echo (ping) requests automatically.
     */
    private fun handleOutgoingPacket(bus: GuestMemoryBus, packet: ByteArray) {
        if (packet.size < 14) return
        val etherType = ((packet[12].toInt() and 0xFF) shl 8) or (packet[13].toInt() and 0xFF)

        when (etherType) {
            0x0806 -> { // ARP
                handleArpPacket(bus, packet)
            }
            0x0800 -> { // IPv4
                handleIpv4Packet(bus, packet)
            }
            else -> {
                if (loopbackEnabled && isBroadcastOrMyMac(packet)) {
                    receivePacket(bus, packet)
                }
            }
        }
    }

    private fun handleArpPacket(bus: GuestMemoryBus, packet: ByteArray) {
        if (packet.size < 42) return
        val opcode = ((packet[20].toInt() and 0xFF) shl 8) or (packet[21].toInt() and 0xFF)
        if (opcode == 1) { // ARP Request (Who-has)
            val senderMac = packet.copyOfRange(22, 28)
            val senderIp = packet.copyOfRange(28, 32)
            val targetIp = packet.copyOfRange(38, 42)

            // Construct ARP Reply from gateway
            val reply = ByteArray(42)
            // Ethernet header (14 bytes)
            System.arraycopy(senderMac, 0, reply, 0, 6) // Dest MAC
            System.arraycopy(gatewayMacBytes, 0, reply, 6, 6) // Src MAC
            reply[12] = 0x08.toByte()
            reply[13] = 0x06.toByte() // EtherType ARP

            // ARP payload (28 bytes)
            reply[14] = 0x00.toByte(); reply[15] = 0x01.toByte() // Hardware: Ethernet (1)
            reply[16] = 0x08.toByte(); reply[17] = 0x00.toByte() // Protocol: IPv4 (0x0800)
            reply[18] = 6; reply[19] = 4 // Hw size = 6, Proto size = 4
            reply[20] = 0x00.toByte(); reply[21] = 0x02.toByte() // Opcode: Reply (2)
            System.arraycopy(gatewayMacBytes, 0, reply, 22, 6) // Sender MAC
            System.arraycopy(targetIp, 0, reply, 28, 4) // Sender IP (requested IP)
            System.arraycopy(senderMac, 0, reply, 32, 6) // Target MAC
            System.arraycopy(senderIp, 0, reply, 38, 4) // Target IP

            receivePacket(bus, reply)
        }
    }

    private fun handleIpv4Packet(bus: GuestMemoryBus, packet: ByteArray) {
        if (packet.size < 34) return
        val ipHdrLen = (packet[14].toInt() and 0x0F) * 4
        if (ipHdrLen < 20 || packet.size < 14 + ipHdrLen) return

        val protocol = packet[23].toInt() and 0xFF
        val icmpOffset = 14 + ipHdrLen

        if (protocol == 1 && packet.size >= icmpOffset + 8) { // ICMP
            val icmpType = packet[icmpOffset].toInt() and 0xFF
            if (icmpType == 8) { // ICMP Echo Request
                val reply = packet.clone()

                // Swap Ethernet MACs
                System.arraycopy(packet, 6, reply, 0, 6) // Dest MAC = packet Src MAC
                System.arraycopy(gatewayMacBytes, 0, reply, 6, 6) // Src MAC = Gateway MAC

                // Swap IPv4 Addresses
                System.arraycopy(packet, 30, reply, 26, 4) // Src IP = packet Dst IP
                System.arraycopy(packet, 26, reply, 30, 4) // Dst IP = packet Src IP

                // Recompute IPv4 Header Checksum
                reply[24] = 0; reply[25] = 0
                val ipCsum = computeInternetChecksum(reply, 14, ipHdrLen)
                reply[24] = (ipCsum ushr 8).toByte()
                reply[25] = (ipCsum and 0xFF).toByte()

                // Set ICMP Type to 0 (Echo Reply) and recompute ICMP checksum
                reply[icmpOffset] = 0
                reply[icmpOffset + 2] = 0
                reply[icmpOffset + 3] = 0
                val icmpLen = reply.size - icmpOffset
                val icmpCsum = computeInternetChecksum(reply, icmpOffset, icmpLen)
                reply[icmpOffset + 2] = (icmpCsum ushr 8).toByte()
                reply[icmpOffset + 3] = (icmpCsum and 0xFF).toByte()

                receivePacket(bus, reply)
                return
            }
        }

        if (loopbackEnabled && isBroadcastOrMyMac(packet)) {
            receivePacket(bus, packet)
        }
    }

    private fun isBroadcastOrMyMac(packet: ByteArray): Boolean {
        var isBcast = true
        var isMine = true
        for (i in 0 until 6) {
            if (packet[i] != 0xFF.toByte()) isBcast = false
            if (packet[i] != macBytes[i]) isMine = false
        }
        return isBcast || isMine
    }

    private fun computeInternetChecksum(data: ByteArray, offset: Int, length: Int): Int {
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

    companion object {
        const val BASE_ADDRESS = 0x10002000
        const val REGION_SIZE = 0x1000

        const val VIRTIO_MAGIC = 0x74726976 // "virt"
        const val VIRTIO_VERSION_2 = 2
        const val VIRTIO_DEVICE_ID_NET = 1
        const val VIRTIO_VENDOR_ID = 0x4E52454B // "KERN"

        const val QUEUE_MAX_SIZE = 64
        const val VIRTQ_DESC_F_NEXT = 1
        const val VIRTQ_DESC_F_WRITE = 2

        const val VIRTIO_NET_F_MAC = (1 shl 5)
        const val VIRTIO_NET_F_STATUS = (1 shl 16)
        const val VIRTIO_F_VERSION_1 = 1

        const val VIRTIO_NET_S_LINK_UP = 1

        const val MAX_BUFFER_BYTES = 65536

        private fun combineAddress64(low: Int, high: Int): Long =
            ((high.toLong() and 0xFFFFFFFFL) shl 32) or (low.toLong() and 0xFFFFFFFFL)
    }
}
