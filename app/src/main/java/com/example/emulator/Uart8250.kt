package com.example.emulator

/**
 * 8250 / 16550A-compatible polling UART mapped at 0x10000000..0x10000007.
 *
 * Designed for M-mode No-MMU Linux kernels running without a PLIC
 * (`console=ttyS0 earlycon=uart8250,mmio,0x10000000`).
 *
 * Supports LCR.DLAB (bit 7) switching for DLL (offset 0) and DLM (offset 1)
 * so Linux's 8250 serial driver can probe and program baud divisors cleanly
 * without destabilizing LSR or RX/TX FIFOs.
 */
class Uart8250(
    private val onTxByte: (Int) -> Unit = {},
    private val onRxAvailable: () -> Unit = {}
) {
    private val rxLock = Any()
    private val rxQueue = ByteArray(RX_BUFFER_CAPACITY)
    private var rxHead = 0
    private var rxTail = 0
    private var rxCount = 0

    // 8250 register state
    private var ier = 0
    private var fcr = 0
    private var lcr = 0x03 // 8N1 default
    private var mcr = 0
    private var msr = 0xB0 // DCD | DSR | CTS asserted
    private var scr = 0
    private var dll = 0x01 // Default divisor latch low (115200 virtual)
    private var dlm = 0x00 // Default divisor latch high

    fun reset() {
        synchronized(rxLock) {
            rxHead = 0
            rxTail = 0
            rxCount = 0
        }
        ier = 0
        fcr = 0
        lcr = 0x03
        mcr = 0
        msr = 0xB0
        scr = 0
        dll = 0x01
        dlm = 0x00
    }

    fun pushRxByte(value: Int) {
        synchronized(rxLock) {
            if (rxCount < RX_BUFFER_CAPACITY) {
                rxQueue[rxTail] = value.toByte()
                rxTail = (rxTail + 1) and RX_BUFFER_MASK
                rxCount++
            }
        }
        onRxAvailable()
    }

    fun pushRxBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(rxLock) {
            for (b in bytes) {
                if (rxCount < RX_BUFFER_CAPACITY) {
                    rxQueue[rxTail] = b
                    rxTail = (rxTail + 1) and RX_BUFFER_MASK
                    rxCount++
                }
            }
        }
        onRxAvailable()
    }

    fun hasRxData(): Boolean = synchronized(rxLock) { rxCount > 0 }

    fun rxBufferedCount(): Int = synchronized(rxLock) { rxCount }

    fun readRegister(offset: Int): Int {
        val dlab = (lcr and 0x80) != 0
        return when (offset and 0x7) {
            0 -> if (dlab) {
                dll
            } else {
                synchronized(rxLock) {
                    if (rxCount > 0) {
                        val b = rxQueue[rxHead].toInt() and 0xFF
                        rxHead = (rxHead + 1) and RX_BUFFER_MASK
                        rxCount--
                        b
                    } else {
                        0
                    }
                }
            }
            1 -> if (dlab) dlm else ier
            2 -> {
                // IIR: bit 0: 0 = interrupt pending, 1 = no interrupt; bits 3:1: 0b010 = RDA (0x04); bits 7:6 = FIFO enabled
                val fifoBits = if ((fcr and 0x01) != 0) 0xC0 else 0x00
                val rxPending = hasRxData() && ((ier and 0x01) != 0)
                if (rxPending) {
                    fifoBits or 0x04
                } else {
                    fifoBits or 0x01
                }
            }
            3 -> lcr
            4 -> mcr
            5 -> {
                // LSR: bit 5 (THRE) | bit 6 (TEMT) always ready; bit 0 (DR) if RX FIFO has data
                val dr = if (hasRxData()) 0x01 else 0x00
                0x60 or dr
            }
            6 -> msr
            7 -> scr
            else -> 0
        }
    }

    fun writeRegister(offset: Int, value: Int) {
        val v = value and 0xFF
        val dlab = (lcr and 0x80) != 0
        when (offset and 0x7) {
            0 -> if (dlab) {
                dll = v
            } else {
                onTxByte(v)
            }
            1 -> if (dlab) {
                dlm = v
            } else {
                ier = v and 0x0F
            }
            2 -> {
                fcr = v
                if ((v and 0x02) != 0) {
                    // Clear RX FIFO
                    synchronized(rxLock) {
                        rxHead = 0
                        rxTail = 0
                        rxCount = 0
                    }
                }
            }
            3 -> lcr = v
            4 -> mcr = v and 0x1F
            5 -> { /* LSR is read-only */ }
            6 -> { /* MSR is read-only */ }
            7 -> scr = v
        }
    }

    companion object {
        const val BASE_ADDRESS = 0x10000000
        const val REGION_SIZE = 0x100
        private const val RX_BUFFER_CAPACITY = 4096 // power of two
        private const val RX_BUFFER_MASK = RX_BUFFER_CAPACITY - 1
    }
}
