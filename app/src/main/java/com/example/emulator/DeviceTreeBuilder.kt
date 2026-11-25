package com.example.emulator

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Architecture target descriptor for FDT generation.
 */
enum class FdtTargetArch(
    val compatibleRoot: String,
    val model: String,
    val isaString: String,
    val mmuType: String
) {
    RISCV64(
        compatibleRoot = "kernovm,rv64",
        model = "KernoVM RV64",
        isaString = "rv64ima",
        mmuType = "riscv,sv39"
    ),
    RISCV32(
        compatibleRoot = "kernovm,rv32ima",
        model = "KernoVM RV32IMA M-Mode No-MMU",
        isaString = "rv32ima",
        mmuType = "riscv,none"
    )
}

/**
 * FDT descriptor for a VirtIO-MMIO block slot with deterministic boot priority metadata.
 */
data class VirtioBlockFdtDescriptor(
    val baseAddress: Long,
    val regionSize: Int = VirtioBlockDevice.REGION_SIZE,
    val irqNumber: Int,
    val bootIndex: Int,
    val deviceName: String,
    val readOnly: Boolean
) {
    companion object {
        fun fromDevice(dev: VirtioBlockDevice): VirtioBlockFdtDescriptor = VirtioBlockFdtDescriptor(
            baseAddress = dev.baseAddress,
            regionSize = VirtioBlockDevice.REGION_SIZE,
            irqNumber = if (dev.slotIndex == 0) VirtioBlockDevice.IRQ_PRIMARY else VirtioBlockDevice.IRQ_SECONDARY,
            bootIndex = dev.bootIndex,
            deviceName = dev.deviceNodeName,
            readOnly = dev.readOnly
        )
    }
}

/**
 * Generates a spec-compliant Flattened Device Tree (FDT v17, magic 0xD00DFEED)
 * for KernoVM's RISC-V virtual hardware map (defaults to RV64 profile target):
 * - /cpus/cpu@0 (`riscv,isa`, `mmu-type`)
 * - /memory@80000000 (configurable RAM size)
 * - /chosen (bootargs = "console=ttyS0 earlycon=uart8250,mmio,0x10000000")
 * - /soc/serial@10000000 (ns16550a polling without PLIC interrupts)
 * - /soc/virtio_mmio@10001000 & @10003000 (dual-drive VirtIO-blk sorted by `bootindex`)
 * - /soc/clint@11000000 (riscv,clint0 timer + software interrupt)
 * - /soc/syscon@11100000 + /soc/poweroff (0x5555) + /soc/reboot (0x7777)
 */
object DeviceTreeBuilder {

    const val FDT_MAGIC = 0xD00DFEED.toInt()
    const val FDT_VERSION = 17
    const val FDT_LAST_COMP_VERSION = 16

    private const val FDT_BEGIN_NODE = 0x00000001
    private const val FDT_END_NODE = 0x00000002
    private const val FDT_PROP = 0x00000003
    private const val FDT_NOP = 0x00000004
    private const val FDT_END = 0x00000009

    const val DEFAULT_BOOTARGS = "console=ttyS0 earlycon=uart8250,mmio,0x10000000 panic=10 oops=panic swiotlb=noforce"

    data class FramebufferConfig(
        val width: Int = 640,
        val height: Int = 480,
        val format: String = "r5g6b5",
        val baseAddress: Long = 0L,
        val stride: Int = width * 2,
        val sizeBytes: Long = (width * height * 2).toLong()
    ) {
        fun resolveBaseAddress(ramSizeBytes: Long): Long {
            if (baseAddress != 0L) return baseAddress
            val alignedFbSize = (sizeBytes + 0xFFFFL) and 0xFFFF0000L
            val dtbReserve = 0x20000L // 128 KB reserve at top of RAM for DTB
            val ramBase = 0x80000000L
            val fbOffset = (ramSizeBytes - dtbReserve - alignedFbSize) and 0xFFFF0000L
            return ramBase + fbOffset
        }
    }

    fun build(
        ramSizeBytes: Long,
        bootArgs: String = DEFAULT_BOOTARGS,
        timebaseFrequencyHz: Int = 1_000_000,
        hasVirtioBlock: Boolean = false,
        hasVirtioNet: Boolean = false,
        framebufferConfig: FramebufferConfig? = null,
        targetArch: FdtTargetArch = FdtTargetArch.RISCV64,
        virtioBlockDevices: List<VirtioBlockFdtDescriptor> = emptyList()
    ): ByteArray {
        val structOut = ByteArrayOutputStream()
        val stringsOut = ByteArrayOutputStream()
        val stringOffsets = LinkedHashMap<String, Int>()

        fun internString(name: String): Int {
            return stringOffsets.getOrPut(name) {
                val offset = stringsOut.size()
                stringsOut.write(name.toByteArray(StandardCharsets.US_ASCII))
                stringsOut.write(0)
                offset
            }
        }

        fun writeBe32(out: ByteArrayOutputStream, value: Int) {
            out.write((value ushr 24) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write(value and 0xFF)
        }

        fun writeBe64(out: ByteArrayOutputStream, value: Long) {
            writeBe32(out, (value ushr 32).toInt())
            writeBe32(out, value.toInt())
        }

        fun pad4(out: ByteArrayOutputStream) {
            while ((out.size() and 3) != 0) {
                out.write(0)
            }
        }

        fun beginNode(name: String) {
            writeBe32(structOut, FDT_BEGIN_NODE)
            structOut.write(name.toByteArray(StandardCharsets.US_ASCII))
            structOut.write(0)
            pad4(structOut)
        }

        fun endNode() {
            writeBe32(structOut, FDT_END_NODE)
        }

        fun propBytes(name: String, data: ByteArray) {
            val nameOff = internString(name)
            writeBe32(structOut, FDT_PROP)
            writeBe32(structOut, data.size)
            writeBe32(structOut, nameOff)
            structOut.write(data)
            pad4(structOut)
        }

        fun propEmpty(name: String) = propBytes(name, ByteArray(0))

        fun propString(name: String, vararg values: String) {
            val buf = ByteArrayOutputStream()
            for (v in values) {
                buf.write(v.toByteArray(StandardCharsets.UTF_8))
                buf.write(0)
            }
            propBytes(name, buf.toByteArray())
        }

        fun propU32(name: String, vararg values: Int) {
            val buf = ByteArrayOutputStream()
            for (v in values) writeBe32(buf, v)
            propBytes(name, buf.toByteArray())
        }

        fun propU64(name: String, vararg values: Long) {
            val buf = ByteArrayOutputStream()
            for (v in values) writeBe64(buf, v)
            propBytes(name, buf.toByteArray())
        }

        val cpuIntcPhandle = 1
        val sysconPhandle = 2

        // Root node
        beginNode("")
        propU32("#address-cells", 1)
        propU32("#size-cells", 1)
        val minimalTag = if (targetArch == FdtTargetArch.RISCV32) "riscv-minimal-nommu" else "riscv-minimal"
        propString("compatible", minimalTag, targetArch.compatibleRoot)
        propString("model", targetArch.model)

        // /chosen
        beginNode("chosen")
        propString("bootargs", bootArgs.ifBlank { DEFAULT_BOOTARGS })
        propString("stdout-path", "/soc/serial@10000000")
        endNode()

        // /memory@80000000
        val effectiveMemorySize = if (framebufferConfig != null) {
            val fbAddr = framebufferConfig.resolveBaseAddress(ramSizeBytes)
            // Truncate memory reg size to fbOffset so Linux physical allocator stops before the framebuffer & DTB
            (fbAddr - 0x80000000L).coerceAtLeast(16L * 1024L * 1024L)
        } else {
            ramSizeBytes
        }
        beginNode("memory@80000000")
        propString("device_type", "memory")
        propU32("reg", 0x80000000.toInt(), effectiveMemorySize.toInt())
        endNode()

        // /reserved-memory node (declares no-map regions so Linux No-MMU buddy allocator does not clobber FB or DTB)
        val dtbReserve = 0x20000L // 128 KB reserve at top of RAM for DTB
        val dtbAddr = 0x80000000L + ramSizeBytes - dtbReserve
        val hexDtbAddr = dtbAddr.toInt().toUInt().toString(16)

        beginNode("reserved-memory")
        propU32("#address-cells", 1)
        propU32("#size-cells", 1)
        propEmpty("ranges")

        if (framebufferConfig != null) {
            val fbAddr = framebufferConfig.resolveBaseAddress(ramSizeBytes)
            val fbSize = framebufferConfig.sizeBytes
            val hexFbAddr = fbAddr.toInt().toUInt().toString(16)
            beginNode("framebuffer@$hexFbAddr")
            propEmpty("no-map")
            propU32("reg", fbAddr.toInt(), fbSize.toInt())
            endNode()
        }

        beginNode("dtb@$hexDtbAddr")
        propEmpty("no-map")
        propU32("reg", dtbAddr.toInt(), dtbReserve.toInt())
        endNode()

        endNode() // reserved-memory

        // Standard Linux simple-framebuffer node
        if (framebufferConfig != null) {
            val fbAddr = framebufferConfig.resolveBaseAddress(ramSizeBytes)
            val fbSize = framebufferConfig.sizeBytes
            val hexAddr = fbAddr.toInt().toUInt().toString(16)
            beginNode("framebuffer@$hexAddr")
            propString("compatible", "simple-framebuffer")
            propU32("reg", fbAddr.toInt(), fbSize.toInt())
            propU32("width", framebufferConfig.width)
            propU32("height", framebufferConfig.height)
            propU32("stride", framebufferConfig.stride)
            propString("format", framebufferConfig.format)
            propString("status", "okay")
            endNode()
        }

        // /cpus
        beginNode("cpus")
        propU32("#address-cells", 1)
        propU32("#size-cells", 0)
        propU32("timebase-frequency", timebaseFrequencyHz)

        beginNode("cpu@0")
        propU32("clock-frequency", 50_000_000)
        propString("device_type", "cpu")
        propU32("reg", 0)
        propString("status", "okay")
        propString("compatible", "riscv")
        propString("riscv,isa", targetArch.isaString)
        propString("mmu-type", targetArch.mmuType)

        beginNode("interrupt-controller")
        propU32("#interrupt-cells", 1)
        propEmpty("interrupt-controller")
        propString("compatible", "riscv,cpu-intc")
        propU32("phandle", cpuIntcPhandle)
        endNode() // interrupt-controller

        endNode() // cpu@0
        endNode() // cpus

        // /soc
        beginNode("soc")
        propU32("#address-cells", 1)
        propU32("#size-cells", 1)
        propString("compatible", "simple-bus")
        propEmpty("ranges")

        // UART 8250/16550A (polling mode — intentionally omits interrupts / PLIC)
        beginNode("serial@10000000")
        propString("compatible", "ns16550a")
        propU32("reg", Uart8250.BASE_ADDRESS, Uart8250.REGION_SIZE)
        propU32("reg-shift", 0)
        propU32("reg-io-width", 1)
        propU32("clock-frequency", 3_686_400)
        propU32("current-speed", 115_200)
        propString("status", "okay")
        endNode() // serial@10000000

        val effectiveBlockDevices = when {
            virtioBlockDevices.isNotEmpty() -> virtioBlockDevices
            hasVirtioBlock -> listOf(
                VirtioBlockFdtDescriptor(
                    baseAddress = VirtioBlockDevice.BASE_ADDRESS.toLong(),
                    regionSize = VirtioBlockDevice.REGION_SIZE,
                    irqNumber = VirtioBlockDevice.IRQ_PRIMARY,
                    bootIndex = 0,
                    deviceName = "vda",
                    readOnly = true
                )
            )
            else -> emptyList()
        }.sortedWith(compareBy<VirtioBlockFdtDescriptor> { it.bootIndex }.thenBy { it.baseAddress })

        for (blk in effectiveBlockDevices) {
            val hexAddr = blk.baseAddress.toUInt().toString(16)
            beginNode("virtio_mmio@$hexAddr")
            propString("compatible", "virtio,mmio")
            propU32("reg", blk.baseAddress.toInt(), blk.regionSize)
            propU32("interrupts-extended", cpuIntcPhandle, blk.irqNumber)
            propU32("bootindex", blk.bootIndex)
            propString("kernovm,device-name", blk.deviceName)
            propString("kernovm,block-role", if (blk.readOnly) "installer-iso-ro" else "target-disk-rw")
            if (blk.readOnly) {
                propEmpty("read-only")
            }
            propString("status", "okay")
            endNode()
        }

        if (hasVirtioNet) {
            // VirtIO-MMIO v2 network device (eth0)
            beginNode("virtio_mmio@10002000")
            propString("compatible", "virtio,mmio")
            propU32("reg", VirtioNetDevice.BASE_ADDRESS, VirtioNetDevice.REGION_SIZE)
            propU32("interrupts-extended", cpuIntcPhandle, 11)
            propString("status", "okay")
            endNode()
        }

        // CLINT timer & M-mode software interrupt
        beginNode("clint@11000000")
        propString("compatible", "riscv,clint0", "sifive,clint0")
        propU32("reg", 0x11000000, 0x00010000)
        propU32("interrupts-extended", cpuIntcPhandle, 3, cpuIntcPhandle, 7)
        endNode() // clint@11000000

        // SYSCON MMIO register block
        beginNode("syscon@11100000")
        propString("compatible", "syscon", "simple-mfd")
        propU32("reg", 0x11100000, 0x1000)
        propU32("phandle", sysconPhandle)
        endNode() // syscon@11100000

        // Explicit Linux syscon-poweroff driver binding (0x5555)
        beginNode("poweroff")
        propString("compatible", "syscon-poweroff")
        propU32("regmap", sysconPhandle)
        propU32("offset", 0x0)
        propU32("value", 0x5555)
        endNode() // poweroff

        // Explicit Linux syscon-reboot driver binding (0x7777)
        beginNode("reboot")
        propString("compatible", "syscon-reboot")
        propU32("regmap", sysconPhandle)
        propU32("offset", 0x0)
        propU32("value", 0x7777)
        endNode() // reboot

        endNode() // soc
        endNode() // root

        writeBe32(structOut, FDT_END)

        val structBytes = structOut.toByteArray()
        val stringsBytes = stringsOut.toByteArray()

        val headerSize = 40
        // 8-byte aligned empty memory reservation block (16 bytes of zero terminator)
        val memRsvOff = 40
        val memRsvSize = 16
        val structOff = memRsvOff + memRsvSize
        val stringsOff = structOff + structBytes.size
        val totalSizeUnaligned = stringsOff + stringsBytes.size
        val totalSize = (totalSizeUnaligned + 7) and 7.inv()

        val finalOut = ByteArrayOutputStream(totalSize)
        writeBe32(finalOut, FDT_MAGIC)
        writeBe32(finalOut, totalSize)
        writeBe32(finalOut, structOff)
        writeBe32(finalOut, stringsOff)
        writeBe32(finalOut, memRsvOff)
        writeBe32(finalOut, FDT_VERSION)
        writeBe32(finalOut, FDT_LAST_COMP_VERSION)
        writeBe32(finalOut, 0) // boot_cpuid_phys
        writeBe32(finalOut, stringsBytes.size)
        writeBe32(finalOut, structBytes.size)

        // Memory reservation terminator (address=0, size=0)
        writeBe64(finalOut, 0L)
        writeBe64(finalOut, 0L)

        finalOut.write(structBytes)
        finalOut.write(stringsBytes)
        while (finalOut.size() < totalSize) {
            finalOut.write(0)
        }
        return finalOut.toByteArray()
    }
}

/**
 * Lightweight binary FDT parser used both in the UI (to show read-only metadata
 * when a custom .dtb is selected) and in unit tests to validate DeviceTreeBuilder output.
 */
data class ParsedFdtNode(
    val path: String,
    val properties: Map<String, ByteArray>
) {
    fun stringProp(name: String): String? {
        val raw = properties[name] ?: return null
        val end = raw.indexOf(0.toByte()).let { if (it < 0) raw.size else it }
        return String(raw, 0, end, StandardCharsets.UTF_8)
    }

    fun stringListProp(name: String): List<String> {
        val raw = properties[name] ?: return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        for (i in raw.indices) {
            if (raw[i] == 0.toByte()) {
                if (i >= start) {
                    result.add(String(raw, start, i - start, StandardCharsets.UTF_8))
                }
                start = i + 1
            }
        }
        return result
    }

    fun u32ListProp(name: String): List<Int> {
        val raw = properties[name] ?: return emptyList()
        val count = raw.size / 4
        return List(count) { idx ->
            val o = idx * 4
            ((raw[o].toInt() and 0xFF) shl 24) or
                ((raw[o + 1].toInt() and 0xFF) shl 16) or
                ((raw[o + 2].toInt() and 0xFF) shl 8) or
                (raw[o + 3].toInt() and 0xFF)
        }
    }
}

data class ParsedVirtioBlockNode(
    val path: String,
    val baseAddress: Long,
    val regionSize: Int,
    val irqNumber: Int?,
    val bootIndex: Int?,
    val deviceName: String?,
    val blockRole: String?,
    val readOnly: Boolean
)

data class ParsedFdtSummary(
    val magic: Int,
    val totalSize: Int,
    val version: Int,
    val lastCompVersion: Int,
    val nodes: Map<String, ParsedFdtNode>
) {
    val bootArgs: String? get() = nodes["/chosen"]?.stringProp("bootargs")
    val modelName: String? get() = nodes["/"]?.stringProp("model")
    val targetArchitectureIsa: String? get() = nodes["/cpus/cpu@0"]?.stringProp("riscv,isa")
    val detectedMemorySizeBytes: Long?
        get() {
            val memNode = nodes.entries.firstOrNull {
                it.key == "/memory" || it.key.startsWith("/memory@")
            }?.value ?: return null
            val reg = memNode.u32ListProp("reg")
            return when {
                reg.size >= 4 && reg[0] == 0 && reg[2] == 0 -> reg[3].toLong() and 0xFFFFFFFFL
                reg.size >= 2 -> reg[1].toLong() and 0xFFFFFFFFL
                else -> null
            }
        }
    val detectedFramebufferNode: ParsedFdtNode?
        get() = nodes.entries.firstOrNull { it.key.startsWith("/framebuffer") }?.value

    val virtioBlockNodes: List<ParsedVirtioBlockNode>
        get() = nodes.values.mapNotNull { node ->
            if (!node.path.startsWith("/soc/virtio_mmio@")) return@mapNotNull null
            val reg = node.u32ListProp("reg")
            if (reg.size < 2) return@mapNotNull null
            val baseAddr = reg[0].toLong() and 0xFFFFFFFFL
            val regionSize = reg[1]
            val hasBootIndex = node.properties.containsKey("bootindex")
            val isBlockAddress = baseAddr != VirtioNetDevice.BASE_ADDRESS.toLong()
            if (!hasBootIndex && !isBlockAddress) return@mapNotNull null

            val intr = node.u32ListProp("interrupts-extended")
            ParsedVirtioBlockNode(
                path = node.path,
                baseAddress = baseAddr,
                regionSize = regionSize,
                irqNumber = intr.getOrNull(1),
                bootIndex = node.u32ListProp("bootindex").firstOrNull(),
                deviceName = node.stringProp("kernovm,device-name"),
                blockRole = node.stringProp("kernovm,block-role"),
                readOnly = node.properties.containsKey("read-only")
            )
        }

    val bootIndices: List<Pair<String, Int>>
        get() = virtioBlockNodes.mapNotNull { blk ->
            blk.bootIndex?.let { (blk.deviceName ?: blk.path) to it }
        }
}

object FdtInspector {
    private const val FDT_BEGIN_NODE = 0x00000001
    private const val FDT_END_NODE = 0x00000002
    private const val FDT_PROP = 0x00000003
    private const val FDT_NOP = 0x00000004
    private const val FDT_END = 0x00000009

    fun hasValidMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 40) return false
        return readBe32(bytes, 0) == DeviceTreeBuilder.FDT_MAGIC
    }

    fun parse(bytes: ByteArray): ParsedFdtSummary {
        require(bytes.size >= 40) { "DTB terlalu kecil (${bytes.size} byte)" }
        val magic = readBe32(bytes, 0)
        require(magic == DeviceTreeBuilder.FDT_MAGIC) {
            "Magic FDT tidak valid: 0x${magic.toUInt().toString(16)}"
        }
        val totalSize = readBe32(bytes, 4)
        val structOff = readBe32(bytes, 8)
        val stringsOff = readBe32(bytes, 12)
        val version = readBe32(bytes, 20)
        val lastComp = readBe32(bytes, 24)
        val stringsSize = readBe32(bytes, 32)
        val structSize = readBe32(bytes, 36)

        require(totalSize in 40..bytes.size) { "totalSize FDT tidak valid: $totalSize" }
        require((structOff and 3) == 0) { "dt_struct tidak rata 4-byte" }
        require(structOff >= 40 && structOff + structSize <= totalSize) { "Rentang dt_struct di luar batas" }
        require(stringsOff >= 40 && stringsOff + stringsSize <= totalSize) { "Rentang dt_strings di luar batas" }

        val nodes = LinkedHashMap<String, ParsedFdtNode>()
        val pathStack = ArrayDeque<String>()
        val propsStack = ArrayDeque<LinkedHashMap<String, ByteArray>>()

        var cursor = structOff
        val structEnd = structOff + structSize
        var sawEnd = false

        while (cursor + 4 <= structEnd) {
            val token = readBe32(bytes, cursor)
            cursor += 4
            when (token) {
                FDT_BEGIN_NODE -> {
                    val nameStart = cursor
                    while (cursor < structEnd && bytes[cursor] != 0.toByte()) cursor++
                    require(cursor < structEnd) { "Nama node FDT tidak memiliki null-terminator" }
                    val name = String(bytes, nameStart, cursor - nameStart, StandardCharsets.US_ASCII)
                    cursor = align4(cursor + 1)
                    val fullPath = when {
                        pathStack.isEmpty() -> if (name.isEmpty()) "/" else "/$name"
                        pathStack.last() == "/" -> "/$name"
                        else -> "${pathStack.last()}/$name"
                    }
                    pathStack.addLast(fullPath)
                    propsStack.addLast(LinkedHashMap())
                }
                FDT_END_NODE -> {
                    require(pathStack.isNotEmpty()) { "FDT_END_NODE tanpa BEGIN_NODE" }
                    val path = pathStack.removeLast()
                    val props = propsStack.removeLast()
                    nodes[path] = ParsedFdtNode(path, props)
                }
                FDT_PROP -> {
                    require(cursor + 8 <= structEnd) { "Header FDT_PROP terpotong" }
                    val len = readBe32(bytes, cursor)
                    val nameOff = readBe32(bytes, cursor + 4)
                    cursor += 8
                    require(len >= 0 && cursor + len <= structEnd) { "Data FDT_PROP melebihi batas" }
                    val propName = readNullTerminated(bytes, stringsOff + nameOff, stringsOff + stringsSize)
                    val data = bytes.copyOfRange(cursor, cursor + len)
                    cursor = align4(cursor + len)
                    require(propsStack.isNotEmpty()) { "FDT_PROP di luar node" }
                    propsStack.last()[propName] = data
                }
                FDT_NOP -> Unit
                FDT_END -> {
                    sawEnd = true
                    break
                }
                else -> throw IllegalArgumentException("Token FDT tidak dikenal: 0x${token.toUInt().toString(16)}")
            }
        }
        require(sawEnd) { "Blok FDT tidak diakhiri FDT_END" }
        return ParsedFdtSummary(magic, totalSize, version, lastComp, nodes)
    }

    private fun readBe32(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun align4(v: Int): Int = (v + 3) and 3.inv()

    private fun readNullTerminated(bytes: ByteArray, start: Int, limit: Int): String {
        require(start in 0 until limit) { "Offset string FDT di luar batas" }
        var end = start
        while (end < limit && bytes[end] != 0.toByte()) end++
        return String(bytes, start, end - start, StandardCharsets.US_ASCII)
    }
}
