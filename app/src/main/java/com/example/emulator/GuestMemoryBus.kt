package com.example.emulator

/**
 * Abstraction for guest physical memory access used by VirtIO-MMIO devices.
 * Decouples VirtIO DMA and split-virtqueue traversal from any single CPU core
 * implementation while supporting full 64-bit guest physical addresses.
 */
interface GuestMemoryBus {
    val ramBaseAddress: Long
    val ramSizeBytes: Long

    fun readPhysicalByte(addr: Long): Int
    fun readPhysicalWord(addr: Long): Int
    fun writePhysicalByte(addr: Long, value: Int)

    fun readPhysicalU16(addr: Long): Int =
        readPhysicalByte(addr) or (readPhysicalByte(addr + 1L) shl 8)

    fun writePhysicalU16(addr: Long, value: Int) {
        writePhysicalByte(addr, value and 0xFF)
        writePhysicalByte(addr + 1L, (value ushr 8) and 0xFF)
    }

    fun writePhysicalU32(addr: Long, value: Int) {
        writePhysicalByte(addr, value and 0xFF)
        writePhysicalByte(addr + 1L, (value ushr 8) and 0xFF)
        writePhysicalByte(addr + 2L, (value ushr 16) and 0xFF)
        writePhysicalByte(addr + 3L, (value ushr 24) and 0xFF)
    }

    fun readPhysicalU64(addr: Long): Long {
        val lo = readPhysicalWord(addr).toLong() and 0xFFFFFFFFL
        val hi = readPhysicalWord(addr + 4L).toLong() and 0xFFFFFFFFL
        return (hi shl 32) or lo
    }
}
