package com.example.emulator

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * ISO-9660 Primary Volume Descriptor (sector 16 / offset 0x8000) & El Torito metadata inspector.
 * Lets KernoVM display volume label, block size, capacity, and boot catalog platform ID for
 * user-imported `.iso` files without loading the entire ISO into JVM heap memory.
 */
data class Iso9660Metadata(
    val isIso9660: Boolean,
    val volumeId: String,
    val logicalBlockSize: Int,
    val volumeSpaceBlocks: Long,
    val fileSizeBytes: Long,
    val systemId: String = "",
    val hasElTorito: Boolean = false,
    val elToritoPlatformId: Int? = null,
    val elToritoBootImageSector: Long? = null,
    val rootEntryNames: List<String> = emptyList(),
    val bootCatalogHeaderBytes: ByteArray = ByteArray(0)
) {
    val sectorCount512: Long get() = (fileSizeBytes + 511L) / 512L
}

object Iso9660Inspector {
    private const val SECTOR_SIZE = 2048L
    private const val PVD_OFFSET = 16L * SECTOR_SIZE // 0x8000

    fun inspect(file: File): Iso9660Metadata {
        val len = file.length()
        if (len < PVD_OFFSET + 128L) {
            return Iso9660Metadata(false, "", 512, len / 512L, len)
        }
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(PVD_OFFSET)
                val pvd = ByteArray(256)
                val read = raf.read(pvd)
                if (read < 132) {
                    return@use Iso9660Metadata(false, "", 512, len / 512L, len)
                }
                val isPrimary = (pvd[0].toInt() and 0xFF) == 1
                val magic = String(pvd, 1, 5, StandardCharsets.US_ASCII)
                if (!isPrimary || magic != "CD001") {
                    return@use Iso9660Metadata(false, "", 512, len / 512L, len)
                }
                val sysId = String(pvd, 8, 32, StandardCharsets.US_ASCII).trim()
                val volId = String(pvd, 40, 32, StandardCharsets.US_ASCII).trim()
                val volBlocks =
                    (pvd[80].toLong() and 0xFFL) or
                        ((pvd[81].toLong() and 0xFFL) shl 8) or
                        ((pvd[82].toLong() and 0xFFL) shl 16) or
                        ((pvd[83].toLong() and 0xFFL) shl 24)
                val blockSize =
                    (pvd[128].toInt() and 0xFF) or
                        ((pvd[129].toInt() and 0xFF) shl 8)

                // Parse root directory entry names (if extent fits safely within file)
                val rootNames = if (read >= 190) {
                    val rootLba = readLe32(pvd, 158)
                    val rootLen = readLe32(pvd, 166).coerceAtMost(8192)
                    if (rootLba > 0 && rootLen > 0) {
                        val rootOff = rootLba.toLong() * SECTOR_SIZE
                        if (rootOff in 0L until len) {
                            val toRead = minOf(rootLen.toLong(), len - rootOff).toInt()
                            val dirBytes = ByteArray(toRead)
                            raf.seek(rootOff)
                            raf.readFully(dirBytes)
                            parseIsoDirectoryNames(dirBytes)
                        } else emptyList()
                    } else emptyList()
                } else emptyList()

                // Scan supplementary/boot volume descriptors (sectors 17..31) for El Torito Boot Record
                var hasElTorito = false
                var platformId: Int? = null
                var bootImageSector: Long? = null
                var bootImgHeader = ByteArray(0)

                val vd = ByteArray(128)
                for (sectorIdx in 17L..31L) {
                    val off = sectorIdx * SECTOR_SIZE
                    if (off + 80L > len) break
                    raf.seek(off)
                    val vdRead = raf.read(vd)
                    if (vdRead < 76) break
                    val type = vd[0].toInt() and 0xFF
                    val vdMagic = String(vd, 1, 5, StandardCharsets.US_ASCII)
                    if (vdMagic != "CD001") break
                    if (type == 0xFF) break
                    if (type == 0) {
                        val bootSysId = String(vd, 7, 23, StandardCharsets.US_ASCII)
                        if (bootSysId.startsWith("EL TORITO SPECIFICATION")) {
                            hasElTorito = true
                            val catalogSector = readLe32(vd, 71).toLong() and 0xFFFFFFFFL
                            val catOff = catalogSector * SECTOR_SIZE
                            if (catalogSector > 0L && catOff + 64L <= len) {
                                val catalog = ByteArray(64)
                                raf.seek(catOff)
                                raf.readFully(catalog)
                                if ((catalog[0].toInt() and 0xFF) == 1) {
                                    platformId = catalog[1].toInt() and 0xFF
                                    val defaultEntryOff = 32
                                    val loadRba = readLe32(catalog, defaultEntryOff + 8).toLong() and 0xFFFFFFFFL
                                    if (loadRba > 0L) {
                                        bootImageSector = loadRba
                                        val imgOff = loadRba * SECTOR_SIZE
                                        if (imgOff + 16L <= len) {
                                            val sampleLen = minOf(4096L, len - imgOff).toInt()
                                            bootImgHeader = ByteArray(sampleLen)
                                            raf.seek(imgOff)
                                            raf.readFully(bootImgHeader)
                                        }
                                    }
                                }
                            }
                            break
                        }
                    }
                }

                Iso9660Metadata(
                    isIso9660 = true,
                    volumeId = volId.ifBlank { "ISO9660_VOLUME" },
                    logicalBlockSize = if (blockSize > 0) blockSize else 2048,
                    volumeSpaceBlocks = volBlocks,
                    fileSizeBytes = len,
                    systemId = sysId,
                    hasElTorito = hasElTorito,
                    elToritoPlatformId = platformId,
                    elToritoBootImageSector = bootImageSector,
                    rootEntryNames = rootNames,
                    bootCatalogHeaderBytes = bootImgHeader
                )
            }
        }.getOrElse {
            Iso9660Metadata(false, "", 512, len / 512L, len)
        }
    }

    private fun readLe32(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            ((buf[off + 2].toInt() and 0xFF) shl 16) or
            ((buf[off + 3].toInt() and 0xFF) shl 24)

    private fun parseIsoDirectoryNames(dirBytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        var cursor = 0
        while (cursor < dirBytes.size && names.size < 64) {
            val recLen = dirBytes[cursor].toInt() and 0xFF
            if (recLen == 0) {
                val nextSector = ((cursor / 2048) + 1) * 2048
                if (nextSector <= cursor || nextSector >= dirBytes.size) break
                cursor = nextSector
                continue
            }
            if (cursor + recLen > dirBytes.size || recLen < 34) break
            val nameLen = dirBytes[cursor + 32].toInt() and 0xFF
            if (nameLen > 0 && cursor + 33 + nameLen <= cursor + recLen) {
                val rawName = String(dirBytes, cursor + 33, nameLen, StandardCharsets.US_ASCII)
                    .substringBefore(';')
                    .trim()
                if (rawName.isNotEmpty() && rawName != "\u0000" && rawName != "\u0001") {
                    names.add(rawName)
                }
            }
            cursor += recLen
        }
        return names
    }
}

/**
 * Slot topology and boot metadata for a VirtIO-MMIO v2 block device instance.
 */
data class VirtioBlockSlotConfig(
    val slotIndex: Int,
    val baseAddress: Long,
    val deviceNodeName: String,
    val readOnly: Boolean,
    val bootIndex: Int,
    val isIsoMedia: Boolean = readOnly
) {
    companion object {
        fun primaryIso(bootIndex: Int = 0): VirtioBlockSlotConfig = VirtioBlockSlotConfig(
            slotIndex = 0,
            baseAddress = VirtioBlockDevice.BASE_ADDRESS.toLong(),
            deviceNodeName = "vda",
            readOnly = true,
            bootIndex = bootIndex,
            isIsoMedia = true
        )

        fun targetDisk(
            bootIndex: Int = 1,
            slotIndex: Int = 1,
            baseAddress: Long = VirtioBlockDevice.SECONDARY_BASE_ADDRESS.toLong()
        ): VirtioBlockSlotConfig = VirtioBlockSlotConfig(
            slotIndex = slotIndex,
            baseAddress = baseAddress,
            deviceNodeName = "vdb",
            readOnly = false,
            bootIndex = bootIndex,
            isIsoMedia = false
        )
    }
}

/**
 * VirtIO-MMIO v2 Block Device mapped at configurable MMIO slots:
 * - Slot 0 (`/dev/vda`): `0x10001000..0x10001FFF` (default installer ISO, read-only)
 * - Slot 1 (`/dev/vdb`): `0x10003000..0x10003FFF` (default target virtual disk, read-write)
 *
 * Backs a user-imported `.iso` (read-only) or `.img` virtual disk (read-write) directly via
 * [RandomAccessFile] so large images never consume ART JVM heap memory.
 *
 * Implements standard VirtIO MMIO v2 register interface (`magic = 0x74726976` "virt",
 * `version = 2`, `deviceId = 2` [block], `VIRTIO_BLK_F_RO` when read-only), 64-bit queue
 * address registers (`0x084`, `0x094`, `0x0A4`), and split virtqueue processing via [GuestMemoryBus].
 */
class VirtioBlockDevice(
    private val backingFile: File,
    val readOnly: Boolean = true,
    initialConfig: VirtioBlockSlotConfig = VirtioBlockSlotConfig(
        slotIndex = 0,
        baseAddress = BASE_ADDRESS.toLong(),
        deviceNodeName = "vda",
        readOnly = readOnly,
        bootIndex = 0,
        isIsoMedia = readOnly
    )
) : Closeable {

    constructor(backingFile: File, config: VirtioBlockSlotConfig) : this(
        backingFile = backingFile,
        readOnly = config.readOnly,
        initialConfig = config
    )

    var config: VirtioBlockSlotConfig = initialConfig.copy(readOnly = readOnly)
        private set

    val slotIndex: Int get() = config.slotIndex
    val baseAddress: Long get() = config.baseAddress
    val deviceNodeName: String get() = config.deviceNodeName
    val bootIndex: Int get() = config.bootIndex

    @Volatile
    var isDetached: Boolean = false
        private set

    private val raf: RandomAccessFile = RandomAccessFile(backingFile, if (readOnly) "r" else "rw")
    val sizeBytes: Long = backingFile.length()
    val sectorCount: Long = (sizeBytes + 511L) / 512L

    // VirtIO-MMIO register state (with 64-bit queue physical addresses)
    private var deviceFeaturesSel = 0
    private var driverFeaturesSel = 0
    private var driverFeaturesLow = 0
    private var driverFeaturesHigh = 0
    private var queueSel = 0
    private var queueNum = QUEUE_MAX_SIZE
    private var queueReady = 0
    private var queueDescLow = 0
    private var queueDescHigh = 0
    private var queueAvailLow = 0
    private var queueAvailHigh = 0
    private var queueUsedLow = 0
    private var queueUsedHigh = 0
    private var status = 0
    private var interruptStatus = 0
    private var lastAvailIdx = 0

    val hasPendingInterrupt: Boolean
        get() = !isDetached && (interruptStatus != 0)

    fun updateBootIndex(newBootIndex: Int) {
        config = config.copy(bootIndex = newBootIndex.coerceAtLeast(0))
    }

    fun reset() {
        deviceFeaturesSel = 0
        driverFeaturesSel = 0
        driverFeaturesLow = 0
        driverFeaturesHigh = 0
        queueSel = 0
        queueNum = QUEUE_MAX_SIZE
        queueReady = 0
        queueDescLow = 0
        queueDescHigh = 0
        queueAvailLow = 0
        queueAvailHigh = 0
        queueUsedLow = 0
        queueUsedHigh = 0
        status = 0
        interruptStatus = 0
        lastAvailIdx = 0
    }

    fun readRegister(offset: Int): Int {
        if (isDetached) return 0
        return when (offset) {
            0x000 -> VIRTIO_MAGIC
            0x004 -> VIRTIO_VERSION_2
            0x008 -> VIRTIO_DEVICE_ID_BLK
            0x00C -> VIRTIO_VENDOR_ID
            0x010 -> { // DeviceFeatures
                if (deviceFeaturesSel == 0) {
                    val roBit = if (readOnly) (1 shl 5) else 0 // VIRTIO_BLK_F_RO
                    roBit or (1 shl 6) // VIRTIO_BLK_F_BLK_SIZE
                } else {
                    1 // VIRTIO_F_VERSION_1 (bit 32)
                }
            }
            0x034 -> if (queueSel == 0) QUEUE_MAX_SIZE else 0 // QueueNumMax
            0x044 -> if (queueSel == 0) queueReady else 0
            0x060 -> interruptStatus
            0x070 -> status
            0x0FC -> 0 // ConfigGeneration
            // Block device config space (offset 0x100+):
            // 0x100..0x107: u64 capacity (in 512-byte sectors)
            0x100 -> (sectorCount and 0xFFFFFFFFL).toInt()
            0x104 -> ((sectorCount ushr 32) and 0xFFFFFFFFL).toInt()
            // 0x114: u32 blk_size (512)
            0x114 -> 512
            else -> 0
        }
    }

    fun writeRegister(offset: Int, value: Int, bus: GuestMemoryBus) {
        if (isDetached) return
        when (offset) {
            0x014 -> deviceFeaturesSel = value
            0x020 -> if (driverFeaturesSel == 0) driverFeaturesLow = value else driverFeaturesHigh = value
            0x024 -> driverFeaturesSel = value
            0x030 -> queueSel = value
            0x038 -> if (queueSel == 0) queueNum = value.coerceIn(1, QUEUE_MAX_SIZE)
            0x044 -> if (queueSel == 0) queueReady = value and 1
            0x050 -> if (value == 0 && queueReady != 0) processQueue(bus) // QueueNotify
            0x064 -> interruptStatus = interruptStatus and value.inv() // InterruptACK
            0x070 -> {
                if (value == 0) reset() else status = value
            }
            0x080 -> if (queueSel == 0) queueDescLow = value
            0x084 -> if (queueSel == 0) queueDescHigh = value
            0x090 -> if (queueSel == 0) queueAvailLow = value
            0x094 -> if (queueSel == 0) queueAvailHigh = value
            0x0A0 -> if (queueSel == 0) queueUsedLow = value
            0x0A4 -> if (queueSel == 0) queueUsedHigh = value
        }
    }

    private fun processQueue(bus: GuestMemoryBus) {
        val descBase = combineAddress64(queueDescLow, queueDescHigh)
        val availBase = combineAddress64(queueAvailLow, queueAvailHigh)
        val usedBase = combineAddress64(queueUsedLow, queueUsedHigh)
        if (descBase == 0L || availBase == 0L || usedBase == 0L) return

        val availIdx = bus.readPhysicalU16(availBase + 2L)
        val qSize = queueNum.coerceAtLeast(1)

        while (lastAvailIdx != availIdx) {
            val ringSlot = lastAvailIdx % qSize
            val headDescIdx = bus.readPhysicalU16(availBase + 4L + (ringSlot * 2).toLong())
            val bytesWritten = executeRequestChain(bus, descBase, qSize, headDescIdx)

            // Append completion to Used Ring
            val usedIdx = bus.readPhysicalU16(usedBase + 2L)
            val usedSlot = usedIdx % qSize
            val usedElemAddr = usedBase + 4L + (usedSlot * 8).toLong()
            bus.writePhysicalU32(usedElemAddr, headDescIdx)
            bus.writePhysicalU32(usedElemAddr + 4L, bytesWritten)
            bus.writePhysicalU16(usedBase + 2L, (usedIdx + 1) and 0xFFFF)

            lastAvailIdx = (lastAvailIdx + 1) and 0xFFFF
            interruptStatus = interruptStatus or 1
        }
    }

    private fun executeRequestChain(
        bus: GuestMemoryBus,
        descBase: Long,
        qSize: Int,
        headIdx: Int
    ): Int {
        if (headIdx !in 0 until qSize) return 0
        // Descriptor 0: virtio_blk_outhdr (16 bytes: u32 type, u32 reserved, u64 sector)
        val desc0 = descBase + (headIdx * 16).toLong()
        val hdrAddr = bus.readPhysicalU64(desc0)
        val hdrFlags = bus.readPhysicalU16(desc0 + 12L)
        val next1 = bus.readPhysicalU16(desc0 + 14L)

        val reqType = bus.readPhysicalWord(hdrAddr)
        val sectorLo = bus.readPhysicalWord(hdrAddr + 8L).toLong() and 0xFFFFFFFFL
        val sectorHi = bus.readPhysicalWord(hdrAddr + 12L).toLong() and 0xFFFFFFFFL
        var byteOffset = ((sectorHi shl 32) or sectorLo) * 512L

        if ((hdrFlags and VIRTQ_DESC_F_NEXT) == 0) return 0

        var curIdx = next1
        var statusCode = VIRTIO_BLK_S_OK
        var totalWrittenToGuest = 0

        var guard = 0
        while (guard++ < QUEUE_MAX_SIZE && curIdx in 0 until qSize) {
            val dAddr = descBase + (curIdx * 16).toLong()
            val bufAddr = bus.readPhysicalU64(dAddr)
            val rawBufLen = bus.readPhysicalWord(dAddr + 8L)
            val flags = bus.readPhysicalU16(dAddr + 12L)
            val nextIdx = bus.readPhysicalU16(dAddr + 14L)
            val hasNext = (flags and VIRTQ_DESC_F_NEXT) != 0

            if (rawBufLen < 0 || rawBufLen > MAX_BUFFER_BYTES) {
                statusCode = VIRTIO_BLK_S_IOERR
            }
            val bufLen = rawBufLen.coerceIn(0, MAX_BUFFER_BYTES)

            if (!hasNext) {
                // Final 1-byte status descriptor
                bus.writePhysicalByte(bufAddr, statusCode)
                totalWrittenToGuest += 1
                break
            }

            if (statusCode == VIRTIO_BLK_S_OK) {
                when (reqType) {
                    VIRTIO_BLK_T_IN -> {
                        if (byteOffset < 0L) {
                            statusCode = VIRTIO_BLK_S_IOERR
                        } else {
                            val data = ByteArray(bufLen)
                            if (byteOffset < sizeBytes) {
                                val toRead = minOf(bufLen.toLong(), sizeBytes - byteOffset).toInt()
                                synchronized(raf) {
                                    raf.seek(byteOffset)
                                    raf.readFully(data, 0, toRead)
                                }
                            }
                            for (i in 0 until bufLen) {
                                bus.writePhysicalByte(bufAddr + i.toLong(), data[i].toInt() and 0xFF)
                            }
                            byteOffset += bufLen.toLong()
                            totalWrittenToGuest += bufLen
                        }
                    }
                    VIRTIO_BLK_T_OUT -> {
                        if (readOnly || byteOffset < 0L || (byteOffset + bufLen > sizeBytes)) {
                            statusCode = VIRTIO_BLK_S_IOERR
                        } else {
                            val data = ByteArray(bufLen) { i ->
                                bus.readPhysicalByte(bufAddr + i.toLong()).toByte()
                            }
                            synchronized(raf) {
                                raf.seek(byteOffset)
                                raf.write(data)
                            }
                            byteOffset += bufLen.toLong()
                        }
                    }
                    VIRTIO_BLK_T_FLUSH -> {
                        if (!readOnly) {
                            synchronized(raf) { raf.fd.sync() }
                        }
                    }
                    else -> statusCode = VIRTIO_BLK_S_UNSUPP
                }
            }

            curIdx = nextIdx
        }
        return totalWrittenToGuest
    }

    fun detach() {
        isDetached = true
        reset()
        close()
    }

    override fun close() {
        isDetached = true
        runCatching {
            synchronized(raf) { raf.close() }
        }
    }

    companion object {
        const val BASE_ADDRESS = 0x10001000
        const val SECONDARY_BASE_ADDRESS = 0x10003000
        const val REGION_SIZE = 0x1000

        const val IRQ_PRIMARY = 11
        const val IRQ_SECONDARY = 13

        const val VIRTIO_MAGIC = 0x74726976 // "virt"
        const val VIRTIO_VERSION_2 = 2
        const val VIRTIO_DEVICE_ID_BLK = 2
        const val VIRTIO_VENDOR_ID = 0x4E52454B // "KERN"

        const val QUEUE_MAX_SIZE = 64
        const val VIRTQ_DESC_F_NEXT = 1

        const val VIRTIO_BLK_T_IN = 0
        const val VIRTIO_BLK_T_OUT = 1
        const val VIRTIO_BLK_T_FLUSH = 4

        const val VIRTIO_BLK_S_OK = 0
        const val VIRTIO_BLK_S_IOERR = 1
        const val VIRTIO_BLK_S_UNSUPP = 2

        const val MAX_BUFFER_BYTES = 65536

        private fun combineAddress64(low: Int, high: Int): Long =
            ((high.toLong() and 0xFFFFFFFFL) shl 32) or (low.toLong() and 0xFFFFFFFFL)
    }
}
