package com.example.vm

import com.example.data.VmProfile
import com.example.emulator.AnsiTerminalBuffer
import com.example.emulator.ParsedVirtioBlockNode

enum class VmStatus {
    STOPPED,
    BOOTING,
    RUNNING,
    PAUSED,
    HALTED,
    ERROR
}

data class RegisterSnapshot(
    val pc: Int = 0,
    val regs: IntArray = IntArray(32),
    val misa: Int = 0x40001101,
    val mstatus: Int = 0,
    val mcause: Int = 0,
    val mepc: Int = 0,
    val mtval: Int = 0,
    val mie: Int = 0,
    val mip: Int = 0,
    val mtvec: Int = 0,
    val mtime: Long = 0L,
    val mtimecmp: Long = Long.MAX_VALUE,
    val uartRxBuffered: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RegisterSnapshot) return false
        return pc == other.pc &&
            regs.contentEquals(other.regs) &&
            misa == other.misa &&
            mstatus == other.mstatus &&
            mcause == other.mcause &&
            mepc == other.mepc &&
            mtval == other.mtval &&
            mie == other.mie &&
            mip == other.mip &&
            mtvec == other.mtvec &&
            mtime == other.mtime &&
            mtimecmp == other.mtimecmp &&
            uartRxBuffered == other.uartRxBuffered
    }

    override fun hashCode(): Int {
        var result = pc
        result = 31 * result + regs.contentHashCode()
        result = 31 * result + mstatus
        result = 31 * result + mcause
        result = 31 * result + mtime.hashCode()
        return result
    }
}

data class FramebufferState(
    val enabled: Boolean = true,
    val width: Int = 640,
    val height: Int = 480,
    val format: String = "r5g6b5",
    val baseAddress: Long = 0L,
    val sizeBytes: Long = 640L * 480L * 2L
)

data class VmRuntimeState(
    val activeVmId: Long? = null,
    val vmName: String = "",
    val status: VmStatus = VmStatus.STOPPED,
    val errorMessage: String? = null,
    val oomFallbackSuggestedRamMb: Int? = null,
    val allocatedRamMb: Int = 0,
    val cpuThreadCount: Int = 1,
    val effectiveCpuQuotaPercent: Int = 100,
    val cpuThreadPriorityLabel: String = "Normal",
    val storageQuotaMb: Int = 128,
    val attachedIsoName: String? = null,
    val targetDiskName: String? = null,
    val primaryBootSlot: String = VmProfile.BOOT_SLOT_ISO,
    val targetArchitecture: String = VmProfile.ARCH_RISCV64,
    val virtioBlockNodes: List<ParsedVirtioBlockNode> = emptyList(),
    val instructionsPerSecond: Long = 0L,
    val totalCycles: Long = 0L,
    val uptimeSeconds: Long = 0L,
    val isWfiSleeping: Boolean = false,
    val isUsingCustomDtb: Boolean = false,
    val autoPausedByBackground: Boolean = false,
    val terminalSnapshot: AnsiTerminalBuffer.TerminalSnapshot = AnsiTerminalBuffer().snapshot(),
    val registerSnapshot: RegisterSnapshot = RegisterSnapshot(),
    val ctrlStickyActive: Boolean = false,
    val altStickyActive: Boolean = false,
    val terminalFontSizeSp: Float = 12.5f,
    val terminalTheme: AnsiTerminalBuffer.ColorTheme = AnsiTerminalBuffer.ColorTheme.DEFAULT,
    val framebuffer: FramebufferState = FramebufferState()
) {
    val isActiveSession: Boolean
        get() = status == VmStatus.BOOTING || status == VmStatus.RUNNING || status == VmStatus.PAUSED
}

/**
 * Monotonic virtual clock for CLINT `mtime` that cleanly freezes on Pause
 * and resumes seamlessly without any wall-clock time jump or spurious timer expiration.
 */
class FreezableVirtualClock(
    private val clockMhz: Int = 1,
    private val nanoTimeProvider: () -> Long = { System.nanoTime() }
) {
    private val lock = Any()
    private var accumulatedNs: Long = 0L
    private var resumeStartNs: Long = 0L
    private var running: Boolean = false

    fun reset(startImmediately: Boolean = true) = synchronized(lock) {
        accumulatedNs = 0L
        if (startImmediately) {
            resumeStartNs = nanoTimeProvider()
            running = true
        } else {
            resumeStartNs = 0L
            running = false
        }
    }

    fun pause() = synchronized(lock) {
        if (running) {
            accumulatedNs += (nanoTimeProvider() - resumeStartNs).coerceAtLeast(0L)
            running = false
        }
    }

    fun resume() = synchronized(lock) {
        if (!running) {
            resumeStartNs = nanoTimeProvider()
            running = true
        }
    }

    fun currentTicks(): Long = synchronized(lock) {
        val totalNs = if (running) {
            accumulatedNs + (nanoTimeProvider() - resumeStartNs).coerceAtLeast(0L)
        } else {
            accumulatedNs
        }
        (totalNs * clockMhz.coerceAtLeast(1).toLong()) / 1_000L
    }

    fun elapsedSeconds(): Long = synchronized(lock) {
        val totalNs = if (running) {
            accumulatedNs + (nanoTimeProvider() - resumeStartNs).coerceAtLeast(0L)
        } else {
            accumulatedNs
        }
        totalNs / 1_000_000_000L
    }
}
