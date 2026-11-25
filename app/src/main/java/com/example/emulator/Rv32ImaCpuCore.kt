package com.example.emulator

/**
 * Pure-Kotlin RV32IMA + Zicsr + Zifencei M-Mode No-MMU CPU emulator core.
 *
 * Performance architecture:
 * - Guest RAM is backed by a little-endian [IntArray] so aligned 32-bit instruction fetches
 *   and aligned LW/SW execute via a single array access (`ram[offset ushr 2]`).
 * - The [stepBatch] hot loop uses strictly primitive Int/Long registers and locals with
 *   zero heap allocations (no nullable locals, no lambdas, no boxed UInt/ULong).
 *
 * Hardware map (`mini-rv32ima` convention):
 * - RAM:    0x80000000 .. 0x80000000 + ramSizeBytes - 1
 * - UART:   0x10000000 .. 0x100000FF (8250/16550A polling, DLL/DLM via LCR.DLAB)
 * - CLINT:  0x11000000 .. 0x1100FFFF (MSIP @ +0x0000, MTIMECMP @ +0x4000, MTIME @ +0xBFF8)
 * - SYSCON: 0x11100000 .. 0x11100FFF (0x5555 = poweroff, 0x7777 = reboot)
 */
class Rv32ImaCpuCore(
    val ram: IntArray,
    val uart: Uart8250 = Uart8250(),
    @Volatile var virtioBlk: VirtioBlockDevice? = null,
    val virtioNet: VirtioNetDevice? = null,
    private val clockSourceUs: () -> Long = { System.nanoTime() / 1_000L },
    @Volatile var virtioBlkSecondary: VirtioBlockDevice? = null
) : GuestMemoryBus {
    private val ramLimitBytes: Int = ram.size shl 2
    override val ramBaseAddress: Long = RAM_BASE_LONG
    override val ramSizeBytes: Long = ramLimitBytes.toLong()

    // General-purpose registers x0..x31 (x0 is always 0)
    val regs = IntArray(32)
    var pc: Int = RAM_BASE

    // Machine CSRs
    var mstatus: Int = INITIAL_MSTATUS
    var mie: Int = 0
    var mip: Int = 0
    var mtvec: Int = 0
    var mscratch: Int = 0
    var mepc: Int = 0
    var mcause: Int = 0
    var mtval: Int = 0
    var mcounteren: Int = 0
    var medeleg: Int = 0
    var mideleg: Int = 0

    // CLINT state (64-bit little-endian split into low/high or Long)
    var msip: Int = 0
    var mtimecmp: Long = Long.MAX_VALUE
    var lastMtime: Long = 0L

    // Counters
    var totalInstructions: Long = 0L
    var totalCycles: Long = 0L

    // LR.W / SC.W reservation state
    private var reservationValid: Boolean = false
    private var reservationAddr: Int = 0

    // Execution status flags
    var isWfiWaiting: Boolean = false
        private set
    var sysconEvent: Int = SYSCON_NONE
        private set
    var privilegeMode: Int = PRIVILEGE_M
        private set

    // Optional test-only hook for riscv-tests (disabled in normal VM runs)
    var testEcallInterceptor: ((cpu: Rv32ImaCpuCore) -> Boolean)? = null

    fun reset(entryPc: Int = RAM_BASE, hartId: Int = 0, dtbAddress: Int = 0) {
        regs.fill(0)
        pc = entryPc
        regs[10] = hartId      // a0 = hartid
        regs[11] = dtbAddress  // a1 = pointer to DTB
        mstatus = INITIAL_MSTATUS
        privilegeMode = PRIVILEGE_M
        mie = 0
        mip = 0
        mtvec = 0
        mscratch = 0
        mepc = 0
        mcause = 0
        mtval = 0
        mcounteren = 0
        medeleg = 0
        mideleg = 0
        msip = 0
        mtimecmp = Long.MAX_VALUE
        lastMtime = 0L
        totalInstructions = 0L
        totalCycles = 0L
        reservationValid = false
        reservationAddr = 0
        isWfiWaiting = false
        sysconEvent = SYSCON_NONE
        uart.reset()
        virtioBlk?.takeUnless { it.isDetached }?.reset()
        virtioBlkSecondary?.takeUnless { it.isDetached }?.reset()
        virtioNet?.reset()
    }

    fun detachPrimaryBlockDevice() {
        virtioBlk?.detach()
        virtioBlk = null
    }

    fun loadBytesIntoRam(bytes: ByteArray, ramOffsetBytes: Int) {
        require(ramOffsetBytes >= 0 && ramOffsetBytes + bytes.size <= ramLimitBytes) {
            "Data melebihi ukuran RAM (offset=$ramOffsetBytes, len=${bytes.size}, ram=$ramLimitBytes)"
        }
        var i = 0
        val len = bytes.size
        // Fast path for 4-byte aligned start
        if ((ramOffsetBytes and 3) == 0) {
            var wordIdx = ramOffsetBytes ushr 2
            while (i + 3 < len) {
                ram[wordIdx++] =
                    (bytes[i].toInt() and 0xFF) or
                        ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[i + 2].toInt() and 0xFF) shl 16) or
                        ((bytes[i + 3].toInt() and 0xFF) shl 24)
                i += 4
            }
        }
        while (i < len) {
            writeRamByteUnchecked(ramOffsetBytes + i, bytes[i].toInt() and 0xFF)
            i++
        }
    }

    private fun resolveRamOffset(addr: Long): Int {
        val normalized = if ((addr ushr 32) == 0xFFFFFFFFL) addr and 0xFFFFFFFFL else addr
        val off = normalized - ramBaseAddress
        return if (off in 0L until ramSizeBytes) off.toInt() else -1
    }

    override fun readPhysicalByte(addr: Long): Int {
        val off = resolveRamOffset(addr)
        if (off >= 0) {
            return (ram[off ushr 2] ushr ((off and 3) shl 3)) and 0xFF
        }
        return 0
    }

    override fun readPhysicalWord(addr: Long): Int {
        val off = resolveRamOffset(addr)
        if (off >= 0 && (off and 3) == 0 && off + 4 <= ramLimitBytes) {
            return ram[off ushr 2]
        }
        return readPhysicalByte(addr) or
            (readPhysicalByte(addr + 1L) shl 8) or
            (readPhysicalByte(addr + 2L) shl 16) or
            (readPhysicalByte(addr + 3L) shl 24)
    }

    override fun writePhysicalByte(addr: Long, value: Int) {
        val off = resolveRamOffset(addr)
        if (off >= 0) {
            writeRamByteUnchecked(off, value)
        }
    }

    fun readPhysicalByte(addr: Int): Int =
        readPhysicalByte(addr.toLong() and 0xFFFFFFFFL)

    fun readPhysicalWord(addr: Int): Int =
        readPhysicalWord(addr.toLong() and 0xFFFFFFFFL)

    fun wakeFromWfi() {
        isWfiWaiting = false
    }

    /**
     * Refreshes CLINT MTIP/MSIP and external VirtIO MEIP pending bits against virtual time.
     * Returns true if any enabled interrupt is pending (which also wakes WFI).
     */
    fun syncInterrupts(currentMtime: Long): Boolean {
        lastMtime = currentMtime
        var newMip = mip and (MIP_MTIP or MIP_MSIP or MIP_MEIP).inv()
        if (unsignedGte(currentMtime, mtimecmp)) {
            newMip = newMip or MIP_MTIP
        }
        if ((msip and 1) != 0) {
            newMip = newMip or MIP_MSIP
        }
        if (hasExternalInterruptPending()) {
            newMip = newMip or MIP_MEIP
        }
        mip = newMip
        val pendingEnabled = (mip and mie) != 0
        if (pendingEnabled) {
            isWfiWaiting = false
        }
        return pendingEnabled
    }

    private fun hasExternalInterruptPending(): Boolean =
        (virtioBlk?.hasPendingInterrupt == true) ||
            (virtioBlkSecondary?.hasPendingInterrupt == true) ||
            (virtioNet?.hasPendingInterrupt == true)

    /**
     * Computes remaining ticks until mtimecmp expires, or Long.MAX_VALUE if timer is disarmed.
     */
    fun remainingTimerTicks(currentMtime: Long): Long {
        if (mtimecmp == Long.MAX_VALUE || mtimecmp == -1L) return Long.MAX_VALUE
        if (unsignedGte(currentMtime, mtimecmp)) return 0L
        return mtimecmp - currentMtime
    }

    /**
     * Executes up to [maxInstructions] instructions in a zero-allocation hot loop.
     *
     * Returns a packed result code:
     * - [BATCH_OK]: completed slice normally
     * - [BATCH_WFI]: CPU executed WFI and should park until timer/UART/control wake
     * - [BATCH_POWEROFF]: guest wrote 0x5555 to SYSCON
     * - [BATCH_REBOOT]: guest wrote 0x7777 to SYSCON
     * - [BATCH_TEST_HALT]: test harness intercepted ECALL
     */
    fun stepBatch(maxInstructions: Int): Int {
        val ramArr = ram
        val ramLimit = ramLimitBytes
        val regFile = regs
        var curPc = pc
        var executed = 0

        // Refresh timer once at batch start
        val startTime = clockSourceUs()
        syncInterrupts(startTime)

        if (isWfiWaiting) {
            if ((mip and mie) == 0 && !uart.hasRxData()) {
                return BATCH_WFI
            }
            isWfiWaiting = false
        }

        while (executed < maxInstructions) {
            // Check pending enabled machine interrupts periodically (every 256 insns or batch start)
            if ((executed and 0xFF) == 0) {
                val now = clockSourceUs()
                lastMtime = now
                var curMip = mip and (MIP_MTIP or MIP_MSIP or MIP_MEIP).inv()
                if (unsignedGte(now, mtimecmp)) curMip = curMip or MIP_MTIP
                if ((msip and 1) != 0) curMip = curMip or MIP_MSIP
                if (hasExternalInterruptPending()) curMip = curMip or MIP_MEIP
                mip = curMip

                val interruptsGloballyEnabled = (privilegeMode < PRIVILEGE_M) || ((mstatus and MSTATUS_MIE) != 0)
                if (interruptsGloballyEnabled) {
                    val activeInts = curMip and mie
                    if (activeInts != 0) {
                        val intCause = when {
                            (activeInts and MIP_MSIP) != 0 -> CAUSE_MACHINE_SOFTWARE_INT
                            (activeInts and MIP_MTIP) != 0 -> CAUSE_MACHINE_TIMER_INT
                            else -> CAUSE_MACHINE_EXTERNAL_INT
                        }
                        curPc = takeTrap(curPc, intCause, 0, isInterrupt = true)
                        executed++
                        continue
                    }
                }
            }

            // Instruction fetch: check 4-byte alignment (RV32IMA has no C extension)
            if ((curPc and 3) != 0) {
                curPc = takeTrap(curPc, CAUSE_INSN_ADDR_MISALIGNED, curPc, isInterrupt = false)
                executed++
                continue
            }
            val fetchOff = curPc - RAM_BASE
            if (fetchOff < 0 || fetchOff + 4 > ramLimit) {
                curPc = takeTrap(curPc, CAUSE_INSN_ACCESS_FAULT, curPc, isInterrupt = false)
                executed++
                continue
            }
            val insn = ramArr[fetchOff ushr 2]
            var nextPc = curPc + 4

            val opcode = insn and 0x7F
            val rd = (insn ushr 7) and 0x1F
            val funct3 = (insn ushr 12) and 0x7
            val rs1 = (insn ushr 15) and 0x1F
            val rs2 = (insn ushr 20) and 0x1F
            val funct7 = insn ushr 25

            when (opcode) {
                0x37 -> { // LUI
                    if (rd != 0) regFile[rd] = insn and 0xFFFFF000.toInt()
                }
                0x17 -> { // AUIPC
                    if (rd != 0) regFile[rd] = curPc + (insn and 0xFFFFF000.toInt())
                }
                0x6F -> { // JAL
                    val imm =
                        ((insn and 0x80000000.toInt()) shr 11) or
                            (insn and 0x000FF000) or
                            ((insn ushr 9) and 0x00000800) or
                            ((insn ushr 20) and 0x000007FE)
                    val target = curPc + imm
                    if ((target and 3) != 0) {
                        nextPc = takeTrap(curPc, CAUSE_INSN_ADDR_MISALIGNED, target, isInterrupt = false)
                    } else {
                        if (rd != 0) regFile[rd] = nextPc
                        nextPc = target
                    }
                }
                0x67 -> { // JALR
                    if (funct3 != 0) {
                        nextPc = takeTrap(curPc, CAUSE_ILLEGAL_INSN, insn, isInterrupt = false)
                    } else {
                        val imm = insn shr 20
                        val target = (regFile[rs1] + imm) and 1.inv()
                        if ((target and 3) != 0) {
                            nextPc = takeTrap(curPc, CAUSE_INSN_ADDR_MISALIGNED, target, isInterrupt = false)
                        } else {
                            val link = nextPc
                            if (rd != 0) regFile[rd] = link
                            nextPc = target
                        }
                    }
                }
                0x63 -> { // BRANCH
                    val v1 = regFile[rs1]
                    val v2 = regFile[rs2]
                    val taken = when (funct3) {
                        0 -> v1 == v2
                        1 -> v1 != v2
                        4 -> v1 < v2
                        5 -> v1 >= v2
                        6 -> (v1 xor Int.MIN_VALUE) < (v2 xor Int.MIN_VALUE)
                        7 -> (v1 xor Int.MIN_VALUE) >= (v2 xor Int.MIN_VALUE)
                        else -> {
                            nextPc = takeTrap(curPc, CAUSE_ILLEGAL_INSN, insn, isInterrupt = false)
                            false
                        }
                    }
                    if (taken) {
                        val imm =
                            ((insn and 0x80000000.toInt()) shr 19) or
                                ((insn and 0x80) shl 4) or
                                ((insn ushr 20) and 0x7E0) or
                                ((insn ushr 7) and 0x1E)
                        val target = curPc + imm
                        if ((target and 3) != 0) {
                            nextPc = takeTrap(curPc, CAUSE_INSN_ADDR_MISALIGNED, target, isInterrupt = false)
                        } else {
                            nextPc = target
                        }
                    }
                }
                0x03 -> { // LOAD
                    val addr = regFile[rs1] + (insn shr 20)
                    val off = addr - RAM_BASE
                    if (funct3 == 2 && (off and 3) == 0 && off >= 0 && off + 4 <= ramLimit) {
                        // Fast-path aligned LW from RAM
                        if (rd != 0) regFile[rd] = ramArr[off ushr 2]
                    } else {
                        val loaded = loadSlow(addr, funct3)
                        if (lastBusFaultCause != 0) {
                            val cause = lastBusFaultCause
                            lastBusFaultCause = 0
                            nextPc = takeTrap(curPc, cause, addr, isInterrupt = false)
                        } else {
                            if (rd != 0) regFile[rd] = loaded
                        }
                    }
                }
                0x23 -> { // STORE
                    val imm = ((insn shr 20) and 0xFFFFFFE0.toInt()) or ((insn ushr 7) and 0x1F)
                    val addr = regFile[rs1] + imm
                    val value = regFile[rs2]
                    val off = addr - RAM_BASE
                    if (funct3 == 2 && (off and 3) == 0 && off >= 0 && off + 4 <= ramLimit) {
                        // Fast-path aligned SW to RAM
                        ramArr[off ushr 2] = value
                        if (reservationValid && reservationAddr == addr) reservationValid = false
                    } else {
                        storeSlow(addr, value, funct3)
                        if (lastBusFaultCause != 0) {
                            val cause = lastBusFaultCause
                            lastBusFaultCause = 0
                            nextPc = takeTrap(curPc, cause, addr, isInterrupt = false)
                        } else if (sysconEvent != SYSCON_NONE) {
                            pc = nextPc
                            totalInstructions += (executed + 1)
                            totalCycles += (executed + 1)
                            return if (sysconEvent == SYSCON_REBOOT) BATCH_REBOOT else BATCH_POWEROFF
                        }
                    }
                }
                0x13 -> { // OP-IMM
                    val v1 = regFile[rs1]
                    val imm = insn shr 20
                    var illegal = false
                    val res = when (funct3) {
                        0 -> v1 + imm // ADDI
                        2 -> if (v1 < imm) 1 else 0 // SLTI
                        3 -> if ((v1 xor Int.MIN_VALUE) < (imm xor Int.MIN_VALUE)) 1 else 0 // SLTIU
                        4 -> v1 xor imm // XORI
                        6 -> v1 or imm // ORI
                        7 -> v1 and imm // ANDI
                        1 -> { // SLLI
                            if (funct7 != 0) {
                                illegal = true
                                0
                            } else {
                                v1 shl (imm and 0x1F)
                            }
                        }
                        5 -> { // SRLI / SRAI
                            if (funct7 == 0x00) {
                                v1 ushr (imm and 0x1F)
                            } else if (funct7 == 0x20) {
                                v1 shr (imm and 0x1F)
                            } else {
                                illegal = true
                                0
                            }
                        }
                        else -> {
                            illegal = true
                            0
                        }
                    }
                    if (illegal) {
                        nextPc = takeTrap(curPc, CAUSE_ILLEGAL_INSN, insn, isInterrupt = false)
                    } else if (rd != 0) {
                        regFile[rd] = res
                    }
                }
                0x33 -> { // OP (RV32I + RV32M)
                    val v1 = regFile[rs1]
                    val v2 = regFile[rs2]
                    var illegal = false
                    val res = if (funct7 == 0x01) {
                        // RV32M Multiply / Divide
                        when (funct3) {
                            0 -> v1 * v2 // MUL
                            1 -> ((v1.toLong() * v2.toLong()) ushr 32).toInt() // MULH
                            2 -> ((v1.toLong() * (v2.toLong() and 0xFFFFFFFFL)) ushr 32).toInt() // MULHSU
                            3 -> (((v1.toLong() and 0xFFFFFFFFL) * (v2.toLong() and 0xFFFFFFFFL)) ushr 32).toInt() // MULHU
                            4 -> { // DIV (signed, safe on /0 and INT_MIN/-1)
                                when {
                                    v2 == 0 -> -1
                                    v1 == Int.MIN_VALUE && v2 == -1 -> Int.MIN_VALUE
                                    else -> v1 / v2
                                }
                            }
                            5 -> { // DIVU (unsigned, safe on /0)
                                if (v2 == 0) {
                                    -1
                                } else {
                                    ((v1.toLong() and 0xFFFFFFFFL) / (v2.toLong() and 0xFFFFFFFFL)).toInt()
                                }
                            }
                            6 -> { // REM (signed, safe on /0 and INT_MIN/-1)
                                when {
                                    v2 == 0 -> v1
                                    v1 == Int.MIN_VALUE && v2 == -1 -> 0
                                    else -> v1 % v2
                                }
                            }
                            7 -> { // REMU (unsigned, safe on /0)
                                if (v2 == 0) {
                                    v1
                                } else {
                                    ((v1.toLong() and 0xFFFFFFFFL) % (v2.toLong() and 0xFFFFFFFFL)).toInt()
                                }
                            }
                            else -> {
                                illegal = true
                                0
                            }
                        }
                    } else {
                        when (funct3) {
                            0 -> when (funct7) {
                                0x00 -> v1 + v2 // ADD
                                0x20 -> v1 - v2 // SUB
                                else -> { illegal = true; 0 }
                            }
                            1 -> if (funct7 == 0x00) v1 shl (v2 and 0x1F) else { illegal = true; 0 }
                            2 -> if (funct7 == 0x00) (if (v1 < v2) 1 else 0) else { illegal = true; 0 }
                            3 -> if (funct7 == 0x00) {
                                if ((v1 xor Int.MIN_VALUE) < (v2 xor Int.MIN_VALUE)) 1 else 0
                            } else {
                                illegal = true; 0
                            }
                            4 -> if (funct7 == 0x00) v1 xor v2 else { illegal = true; 0 }
                            5 -> when (funct7) {
                                0x00 -> v1 ushr (v2 and 0x1F)
                                0x20 -> v1 shr (v2 and 0x1F)
                                else -> { illegal = true; 0 }
                            }
                            6 -> if (funct7 == 0x00) v1 or v2 else { illegal = true; 0 }
                            7 -> if (funct7 == 0x00) v1 and v2 else { illegal = true; 0 }
                            else -> { illegal = true; 0 }
                        }
                    }
                    if (illegal) {
                        nextPc = takeTrap(curPc, CAUSE_ILLEGAL_INSN, insn, isInterrupt = false)
                    } else if (rd != 0) {
                        regFile[rd] = res
                    }
                }
                0x0F -> { // FENCE / FENCE.I (Zifencei) — single-hart in-order no-op
                    if (funct3 != 0 && funct3 != 1) {
                        nextPc = takeTrap(curPc, CAUSE_ILLEGAL_INSN, insn, isInterrupt = false)
                    }
                }
                0x2F -> { // RV32A Atomics (LR.W, SC.W, AMO*.W)
                    if (funct3 != 2) {
                        nextPc = takeTrap(curPc, CAUSE_ILLEGAL_INSN, insn, isInterrupt = false)
                    } else {
                        val addr = regFile[rs1]
                        if ((addr and 3) != 0) {
                            val cause = if ((funct7 ushr 2) == 0x02) {
                                CAUSE_LOAD_ADDR_MISALIGNED
                            } else {
                                CAUSE_STORE_ADDR_MISALIGNED
                            }
                            nextPc = takeTrap(curPc, cause, addr, isInterrupt = false)
                        } else {
                            val amoOp = funct7 ushr 2
                            val rs2Val = regFile[rs2]
                            when (amoOp) {
                                0x02 -> { // LR.W
                                    val oldVal = loadSlow(addr, 2)
                                    if (lastBusFaultCause != 0) {
                                        val cause = lastBusFaultCause
                                        lastBusFaultCause = 0
                                        nextPc = takeTrap(curPc, cause, addr, isInterrupt = false)
                                    } else {
                                        reservationValid = true
                                        reservationAddr = addr
                                        if (rd != 0) regFile[rd] = oldVal
                                    }
                                }
                                0x03 -> { // SC.W
                                    if (reservationValid && reservationAddr == addr) {
                                        storeSlow(addr, rs2Val, 2)
                                        reservationValid = false
                                        if (lastBusFaultCause != 0) {
                                            val cause = lastBusFaultCause
                                            lastBusFaultCause = 0
                                            nextPc = takeTrap(curPc, cause, addr, isInterrupt = false)
                                        } else {
                                            if (rd != 0) regFile[rd] = 0 // success
                                        }
                                    } else {
                                        reservationValid = false
                                        if (rd != 0) regFile[rd] = 1 // failure
                                    }
                                }
                                else -> { // AMO*.W — always read old value BEFORE writing & BEFORE mutating rd
                                    val oldVal = loadSlow(addr, 2)
                                    if (lastBusFaultCause != 0) {
                                        val cause = lastBusFaultCause
                                        lastBusFaultCause = 0
                                        nextPc = takeTrap(curPc, cause, addr, isInterrupt = false)
                                    } else {
                                        var amoIllegal = false
                                        val newVal = when (amoOp) {
                                            0x01 -> rs2Val // AMOSWAP.W
                                            0x00 -> oldVal + rs2Val // AMOADD.W
                                            0x04 -> oldVal xor rs2Val // AMOXOR.W
                                            0x0C -> oldVal and rs2Val // AMOAND.W
                                            0x08 -> oldVal or rs2Val // AMOOR.W
                                            0x10 -> if (oldVal < rs2Val) oldVal else rs2Val // AMOMIN.W
                                            0x14 -> if (oldVal > rs2Val) oldVal else rs2Val // AMOMAX.W
                                            0x18 -> if ((oldVal xor Int.MIN_VALUE) < (rs2Val xor Int.MIN_VALUE)) oldVal else rs2Val // AMOMINU.W
                                            0x1C -> if ((oldVal xor Int.MIN_VALUE) > (rs2Val xor Int.MIN_VALUE)) oldVal else rs2Val // AMOMAXU.W
                                            else -> { amoIllegal = true; 0 }
                                        }
                                        if (amoIllegal) {
                                            nextPc = takeTrap(curPc, CAUSE_ILLEGAL_INSN, insn, isInterrupt = false)
                                        } else {
                                            storeSlow(addr, newVal, 2)
                                            if (lastBusFaultCause != 0) {
                                                val cause = lastBusFaultCause
                                                lastBusFaultCause = 0
                                                nextPc = takeTrap(curPc, cause, addr, isInterrupt = false)
                                            } else {
                                                // Safe even when rd == rs1 or rd == rs2
                                                if (rd != 0) regFile[rd] = oldVal
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                0x73 -> { // SYSTEM (ECALL, EBREAK, MRET, WFI, Zicsr)
                    if (funct3 == 0) {
                        when (insn) {
                            0x00000073 -> { // ECALL (U-mode cause 8, M-mode cause 11)
                                val interceptor = testEcallInterceptor
                                if (interceptor != null && interceptor(this)) {
                                    pc = nextPc
                                    totalInstructions += (executed + 1)
                                    totalCycles += (executed + 1)
                                    return BATCH_TEST_HALT
                                }
                                val cause = if (privilegeMode == PRIVILEGE_U) CAUSE_ECALL_U else CAUSE_ECALL_M
                                nextPc = takeTrap(curPc, cause, 0, isInterrupt = false)
                            }
                            0x00100073 -> { // EBREAK
                                nextPc = takeTrap(curPc, CAUSE_BREAKPOINT, curPc, isInterrupt = false)
                            }
                            0x30200073 -> { // MRET
                                val mpie = (mstatus and MSTATUS_MPIE) != 0
                                var newMstatus = mstatus and MSTATUS_MIE.inv()
                                if (mpie) newMstatus = newMstatus or MSTATUS_MIE
                                val targetMode = (mstatus ushr 11) and 3
                                privilegeMode = if (targetMode == 0) PRIVILEGE_U else PRIVILEGE_M
                                newMstatus = (newMstatus and (0x3 shl 11).inv()) or MSTATUS_MPIE
                                mstatus = newMstatus
                                reservationValid = false
                                nextPc = mepc
                            }
                            0x10500073 -> { // WFI
                                val now = clockSourceUs()
                                val pendingEnabled = syncInterrupts(now)
                                if (!pendingEnabled && !uart.hasRxData()) {
                                    isWfiWaiting = true
                                    pc = nextPc
                                    totalInstructions += (executed + 1)
                                    totalCycles += (executed + 1)
                                    return BATCH_WFI
                                }
                            }
                            else -> {
                                // SFENCE.VMA (funct7 == 0x09) ignored in No-MMU or trap? Trap as illegal in M-mode No-MMU
                                nextPc = takeTrap(curPc, CAUSE_ILLEGAL_INSN, insn, isInterrupt = false)
                            }
                        }
                    } else {
                        // Zicsr instructions
                        val csrAddr = insn ushr 20
                        val isImm = (funct3 and 0x4) != 0
                        val srcVal = if (isImm) rs1 else regFile[rs1]
                        val op = funct3 and 0x3
                        val isWrite = when (op) {
                            1 -> true // CSRRW / CSRRWI always writes
                            2, 3 -> rs1 != 0 // CSRRS / CSRRC / CSRRSI / CSRRCI writes ONLY if rs1/uimm != 0
                            else -> false
                        }
                        if (op == 0) {
                            nextPc = takeTrap(curPc, CAUSE_ILLEGAL_INSN, insn, isInterrupt = false)
                        } else {
                            // Read-only CSRs (bits [11:10] == 0b11) MUST trap when written
                            val isReadOnlyCsr = ((csrAddr ushr 10) and 0x3) == 0x3
                            if (isWrite && isReadOnlyCsr) {
                                nextPc = takeTrap(curPc, CAUSE_ILLEGAL_INSN, insn, isInterrupt = false)
                            } else {
                                val oldCsr = readCsr(csrAddr, executed)
                                if (lastCsrIllegal) {
                                    lastCsrIllegal = false
                                    nextPc = takeTrap(curPc, CAUSE_ILLEGAL_INSN, insn, isInterrupt = false)
                                } else {
                                    if (isWrite) {
                                        val newVal = when (op) {
                                            1 -> srcVal
                                            2 -> oldCsr or srcVal
                                            3 -> oldCsr and srcVal.inv()
                                            else -> oldCsr
                                        }
                                        writeCsr(csrAddr, newVal)
                                    }
                                    if (lastCsrIllegal) {
                                        lastCsrIllegal = false
                                        nextPc = takeTrap(curPc, CAUSE_ILLEGAL_INSN, insn, isInterrupt = false)
                                    } else if (rd != 0) {
                                        regFile[rd] = oldCsr
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    nextPc = takeTrap(curPc, CAUSE_ILLEGAL_INSN, insn, isInterrupt = false)
                }
            }

            curPc = nextPc
            executed++
        }

        pc = curPc
        totalInstructions += executed
        totalCycles += executed
        return BATCH_OK
    }

    private var lastBusFaultCause: Int = 0
    private var lastCsrIllegal: Boolean = false

    private fun takeTrap(faultPc: Int, cause: Int, tval: Int, isInterrupt: Boolean): Int {
        mepc = faultPc
        mcause = if (isInterrupt) (cause or Int.MIN_VALUE) else cause
        mtval = tval

        val oldMie = (mstatus and MSTATUS_MIE) != 0
        var newMstatus = mstatus and (MSTATUS_MIE or MSTATUS_MPIE).inv()
        if (oldMie) newMstatus = newMstatus or MSTATUS_MPIE
        newMstatus = (newMstatus and (0x3 shl 11).inv()) or (privilegeMode shl 11)
        mstatus = newMstatus
        privilegeMode = PRIVILEGE_M
        reservationValid = false

        val base = mtvec and 3.inv()
        val mode = mtvec and 3
        return if (isInterrupt && mode == 1) {
            base + ((cause and 0x7FFFFFFF) shl 2)
        } else {
            base
        }
    }

    private fun readCsr(addr: Int, batchInsnDelta: Int): Int {
        return when (addr) {
            CSR_MSTATUS -> mstatus
            CSR_MSTATUSH -> 0
            CSR_MISA -> MISA_RV32IMA
            CSR_MEDELEG -> medeleg
            CSR_MIDELEG -> mideleg
            CSR_MIE -> mie
            CSR_MTVEC -> mtvec
            CSR_MCOUNTEREN -> mcounteren
            CSR_MSCRATCH -> mscratch
            CSR_MEPC -> mepc
            CSR_MCAUSE -> mcause
            CSR_MTVAL -> mtval
            CSR_MIP -> {
                syncInterrupts(clockSourceUs())
                mip
            }
            CSR_MVENDORID, CSR_MARCHID, CSR_MIMPID, CSR_MHARTID, CSR_MCONFIGPTR -> 0
            CSR_MCYCLE, CSR_CYCLE -> (totalCycles + batchInsnDelta).toInt()
            CSR_MCYCLEH, CSR_CYCLEH -> ((totalCycles + batchInsnDelta) ushr 32).toInt()
            CSR_MINSTRET, CSR_INSTRET -> (totalInstructions + batchInsnDelta).toInt()
            CSR_MINSTRETH, CSR_INSTRETH -> ((totalInstructions + batchInsnDelta) ushr 32).toInt()
            CSR_TIME -> {
                val now = clockSourceUs()
                lastMtime = now
                now.toInt()
            }
            CSR_TIMEH -> {
                val now = clockSourceUs()
                lastMtime = now
                (now ushr 32).toInt()
            }
            in 0x3A0..0x3A3, in 0x3B0..0x3BF -> 0 // PMP CSRs: WARL 0
            in 0x320..0x33F, in 0xB03..0xB1F, in 0xB83..0xB9F -> 0 // HPM counters
            else -> {
                lastCsrIllegal = true
                0
            }
        }
    }

    private fun writeCsr(addr: Int, value: Int) {
        when (addr) {
            CSR_MSTATUS -> {
                // Support User mode (0) and Machine mode (3) in MPP; FS=0, VS=0, SD=0
                val mpp = (value ushr 11) and 3
                val filteredMpp = if (mpp == 0) 0 else MSTATUS_MPP_MACHINE
                mstatus = (value and (MSTATUS_MIE or MSTATUS_MPIE)) or filteredMpp
            }
            CSR_MSTATUSH -> Unit
            CSR_MISA -> Unit // WARL: preserve 0x40001101
            CSR_MEDELEG -> medeleg = 0 // No S-mode delegation
            CSR_MIDELEG -> mideleg = 0 // No S-mode delegation
            CSR_MIE -> mie = value and (MIP_MSIP or MIP_MTIP or MIP_MEIP)
            CSR_MTVEC -> {
                // Support Direct (0) and Vectored (1); mask reserved mode bits
                val mode = value and 3
                mtvec = if (mode <= 1) value else (value and 3.inv())
            }
            CSR_MCOUNTEREN -> mcounteren = value and 0x7
            CSR_MSCRATCH -> mscratch = value
            CSR_MEPC -> mepc = value and 3.inv()
            CSR_MCAUSE -> mcause = value
            CSR_MTVAL -> mtval = value
            CSR_MIP -> {
                // Only software-writable bits (none in pure M-mode beyond CLINT MSIP)
                mip = (mip and (MIP_MSIP or MIP_MTIP or MIP_MEIP))
            }
            CSR_MCYCLE -> totalCycles = (totalCycles and 0xFFFFFFFFL.inv()) or (value.toLong() and 0xFFFFFFFFL)
            CSR_MCYCLEH -> totalCycles = (totalCycles and 0x00000000FFFFFFFFL) or ((value.toLong() and 0xFFFFFFFFL) shl 32)
            CSR_MINSTRET -> totalInstructions = (totalInstructions and 0xFFFFFFFFL.inv()) or (value.toLong() and 0xFFFFFFFFL)
            CSR_MINSTRETH -> totalInstructions = (totalInstructions and 0x00000000FFFFFFFFL) or ((value.toLong() and 0xFFFFFFFFL) shl 32)
            in 0x3A0..0x3A3, in 0x3B0..0x3BF -> Unit // PMP WARL 0
            in 0x320..0x33F, in 0xB03..0xB1F, in 0xB83..0xB9F -> Unit // HPM counters
            else -> lastCsrIllegal = true
        }
    }

    private fun loadSlow(addr: Int, funct3: Int): Int {
        val width = when (funct3) {
            0, 4 -> 1 // LB, LBU
            1, 5 -> 2 // LH, LHU
            2 -> 4    // LW
            else -> {
                lastBusFaultCause = CAUSE_ILLEGAL_INSN
                return 0
            }
        }
        val off = addr - RAM_BASE
        if (off >= 0 && off + width <= ramLimitBytes) {
            val raw = when (width) {
                1 -> readRamByteUnchecked(off)
                2 -> {
                    if ((off and 3) <= 2) {
                        (ram[off ushr 2] ushr ((off and 3) shl 3)) and 0xFFFF
                    } else {
                        readRamByteUnchecked(off) or (readRamByteUnchecked(off + 1) shl 8)
                    }
                }
                else -> {
                    if ((off and 3) == 0) {
                        ram[off ushr 2]
                    } else {
                        readRamByteUnchecked(off) or
                            (readRamByteUnchecked(off + 1) shl 8) or
                            (readRamByteUnchecked(off + 2) shl 16) or
                            (readRamByteUnchecked(off + 3) shl 24)
                    }
                }
            }
            return when (funct3) {
                0 -> (raw shl 24) shr 24 // LB sign-extend
                4 -> raw and 0xFF        // LBU
                1 -> (raw shl 16) shr 16 // LH sign-extend
                5 -> raw and 0xFFFF      // LHU
                else -> raw              // LW
            }
        }

        // MMIO Read
        if (addr >= Uart8250.BASE_ADDRESS && addr < Uart8250.BASE_ADDRESS + Uart8250.REGION_SIZE) {
            val v = uart.readRegister(addr - Uart8250.BASE_ADDRESS)
            return when (funct3) {
                0 -> (v shl 24) shr 24
                1 -> (v shl 16) shr 16
                else -> v
            }
        }
        if (addr >= VirtioBlockDevice.BASE_ADDRESS && addr < VirtioBlockDevice.BASE_ADDRESS + VirtioBlockDevice.REGION_SIZE) {
            val blk = virtioBlk?.takeUnless { it.isDetached }
            return blk?.readRegister(addr - VirtioBlockDevice.BASE_ADDRESS) ?: 0
        }
        if (addr >= VirtioBlockDevice.SECONDARY_BASE_ADDRESS &&
            addr < VirtioBlockDevice.SECONDARY_BASE_ADDRESS + VirtioBlockDevice.REGION_SIZE
        ) {
            val blk2 = virtioBlkSecondary?.takeUnless { it.isDetached }
            return blk2?.readRegister(addr - VirtioBlockDevice.SECONDARY_BASE_ADDRESS) ?: 0
        }
        if (addr >= VirtioNetDevice.BASE_ADDRESS && addr < VirtioNetDevice.BASE_ADDRESS + VirtioNetDevice.REGION_SIZE) {
            val net = virtioNet
            val v = net?.readRegister(addr - VirtioNetDevice.BASE_ADDRESS) ?: 0
            return when (funct3 and 3) {
                0 -> (v shl 24) shr 24
                1 -> (v shl 16) shr 16
                else -> v
            }
        }
        if (addr >= CLINT_BASE && addr < CLINT_BASE + CLINT_SIZE) {
            val clintOff = addr - CLINT_BASE
            val v = when (clintOff) {
                0x0000 -> msip
                0x4000 -> mtimecmp.toInt()
                0x4004 -> (mtimecmp ushr 32).toInt()
                0xBFF8 -> {
                    val now = clockSourceUs()
                    lastMtime = now
                    now.toInt()
                }
                0xBFFC -> {
                    val now = clockSourceUs()
                    lastMtime = now
                    (now ushr 32).toInt()
                }
                else -> 0
            }
            return v
        }
        if (addr >= SYSCON_BASE && addr < SYSCON_BASE + SYSCON_SIZE) {
            return 0
        }

        lastBusFaultCause = CAUSE_LOAD_ACCESS_FAULT
        return 0
    }

    private fun storeSlow(addr: Int, value: Int, funct3: Int) {
        val width = when (funct3) {
            0 -> 1 // SB
            1 -> 2 // SH
            2 -> 4 // SW
            else -> {
                lastBusFaultCause = CAUSE_ILLEGAL_INSN
                return
            }
        }
        if (reservationValid && (reservationAddr and 3.inv()) == (addr and 3.inv())) {
            reservationValid = false
        }

        val off = addr - RAM_BASE
        if (off >= 0 && off + width <= ramLimitBytes) {
            when (width) {
                1 -> writeRamByteUnchecked(off, value and 0xFF)
                2 -> {
                    writeRamByteUnchecked(off, value and 0xFF)
                    writeRamByteUnchecked(off + 1, (value ushr 8) and 0xFF)
                }
                else -> {
                    if ((off and 3) == 0) {
                        ram[off ushr 2] = value
                    } else {
                        writeRamByteUnchecked(off, value and 0xFF)
                        writeRamByteUnchecked(off + 1, (value ushr 8) and 0xFF)
                        writeRamByteUnchecked(off + 2, (value ushr 16) and 0xFF)
                        writeRamByteUnchecked(off + 3, (value ushr 24) and 0xFF)
                    }
                }
            }
            return
        }

        // MMIO Write
        if (addr >= Uart8250.BASE_ADDRESS && addr < Uart8250.BASE_ADDRESS + Uart8250.REGION_SIZE) {
            uart.writeRegister(addr - Uart8250.BASE_ADDRESS, value)
            return
        }
        if (addr >= VirtioBlockDevice.BASE_ADDRESS && addr < VirtioBlockDevice.BASE_ADDRESS + VirtioBlockDevice.REGION_SIZE) {
            virtioBlk?.takeUnless { it.isDetached }?.writeRegister(addr - VirtioBlockDevice.BASE_ADDRESS, value, this)
            return
        }
        if (addr >= VirtioBlockDevice.SECONDARY_BASE_ADDRESS &&
            addr < VirtioBlockDevice.SECONDARY_BASE_ADDRESS + VirtioBlockDevice.REGION_SIZE
        ) {
            virtioBlkSecondary?.takeUnless { it.isDetached }?.writeRegister(addr - VirtioBlockDevice.SECONDARY_BASE_ADDRESS, value, this)
            return
        }
        if (addr >= VirtioNetDevice.BASE_ADDRESS && addr < VirtioNetDevice.BASE_ADDRESS + VirtioNetDevice.REGION_SIZE) {
            virtioNet?.writeRegister(addr - VirtioNetDevice.BASE_ADDRESS, value, this)
            return
        }
        if (addr >= CLINT_BASE && addr < CLINT_BASE + CLINT_SIZE) {
            val clintOff = addr - CLINT_BASE
            when (clintOff) {
                0x0000 -> {
                    msip = value and 1
                    if (msip != 0) {
                        mip = mip or MIP_MSIP
                        isWfiWaiting = false
                    } else {
                        mip = mip and MIP_MSIP.inv()
                    }
                }
                0x4000 -> {
                    mtimecmp = (mtimecmp and 0xFFFFFFFFL.inv()) or (value.toLong() and 0xFFFFFFFFL)
                    syncInterrupts(clockSourceUs())
                }
                0x4004 -> {
                    mtimecmp = (mtimecmp and 0x00000000FFFFFFFFL) or ((value.toLong() and 0xFFFFFFFFL) shl 32)
                    syncInterrupts(clockSourceUs())
                }
            }
            return
        }
        if (addr >= SYSCON_BASE && addr < SYSCON_BASE + SYSCON_SIZE) {
            when (value and 0xFFFF) {
                0x5555 -> sysconEvent = SYSCON_POWEROFF
                0x7777 -> sysconEvent = SYSCON_REBOOT
            }
            return
        }

        lastBusFaultCause = CAUSE_STORE_ACCESS_FAULT
    }

    private fun readRamByteUnchecked(off: Int): Int {
        return (ram[off ushr 2] ushr ((off and 3) shl 3)) and 0xFF
    }

    private fun writeRamByteUnchecked(off: Int, byteVal: Int) {
        val wordIdx = off ushr 2
        val shift = (off and 3) shl 3
        val mask = (0xFF shl shift).inv()
        ram[wordIdx] = (ram[wordIdx] and mask) or ((byteVal and 0xFF) shl shift)
    }

    private fun unsignedGte(a: Long, b: Long): Boolean {
        return (a xor Long.MIN_VALUE) >= (b xor Long.MIN_VALUE)
    }

    companion object {
        const val RAM_BASE = 0x80000000.toInt()
        const val RAM_BASE_LONG = 0x80000000L
        const val CLINT_BASE = 0x11000000
        const val CLINT_SIZE = 0x10000
        const val SYSCON_BASE = 0x11100000
        const val SYSCON_SIZE = 0x1000

        // MISA: MXL=1 (32-bit, bit 30), M (bit 12), I (bit 8), A (bit 0) -> 0x40001101
        const val MISA_RV32IMA = 0x40001101

        // Privilege modes
        const val PRIVILEGE_U = 0
        const val PRIVILEGE_M = 3

        // Initial mstatus: MIE=0, MPIE=1 (bit 7), MPP=M (0b11 at bits 12:11), FS=0, VS=0, SD=0
        const val MSTATUS_MIE = 1 shl 3
        const val MSTATUS_MPIE = 1 shl 7
        const val MSTATUS_MPP_MACHINE = 0x3 shl 11
        const val INITIAL_MSTATUS = MSTATUS_MPIE or MSTATUS_MPP_MACHINE // 0x00001880

        // MIP / MIE bits
        const val MIP_MSIP = 1 shl 3
        const val MIP_MTIP = 1 shl 7
        const val MIP_MEIP = 1 shl 11

        // Synchronous exception causes
        const val CAUSE_INSN_ADDR_MISALIGNED = 0
        const val CAUSE_INSN_ACCESS_FAULT = 1
        const val CAUSE_ILLEGAL_INSN = 2
        const val CAUSE_BREAKPOINT = 3
        const val CAUSE_LOAD_ADDR_MISALIGNED = 4
        const val CAUSE_LOAD_ACCESS_FAULT = 5
        const val CAUSE_STORE_ADDR_MISALIGNED = 6
        const val CAUSE_STORE_ACCESS_FAULT = 7
        const val CAUSE_ECALL_U = 8
        const val CAUSE_ECALL_M = 11

        // Asynchronous interrupt causes (without MSB)
        const val CAUSE_MACHINE_SOFTWARE_INT = 3
        const val CAUSE_MACHINE_TIMER_INT = 7
        const val CAUSE_MACHINE_EXTERNAL_INT = 11

        // CSR addresses
        const val CSR_MSTATUS = 0x300
        const val CSR_MISA = 0x301
        const val CSR_MEDELEG = 0x302
        const val CSR_MIDELEG = 0x303
        const val CSR_MIE = 0x304
        const val CSR_MTVEC = 0x305
        const val CSR_MCOUNTEREN = 0x306
        const val CSR_MSTATUSH = 0x310
        const val CSR_MSCRATCH = 0x340
        const val CSR_MEPC = 0x341
        const val CSR_MCAUSE = 0x342
        const val CSR_MTVAL = 0x343
        const val CSR_MIP = 0x344
        const val CSR_MCYCLE = 0xB00
        const val CSR_MINSTRET = 0xB02
        const val CSR_MCYCLEH = 0xB80
        const val CSR_MINSTRETH = 0xB82
        const val CSR_CYCLE = 0xC00
        const val CSR_TIME = 0xC01
        const val CSR_INSTRET = 0xC02
        const val CSR_CYCLEH = 0xC80
        const val CSR_TIMEH = 0xC81
        const val CSR_INSTRETH = 0xC82
        const val CSR_MVENDORID = 0xF11
        const val CSR_MARCHID = 0xF12
        const val CSR_MIMPID = 0xF13
        const val CSR_MHARTID = 0xF14
        const val CSR_MCONFIGPTR = 0xF15

        // SYSCON events
        const val SYSCON_NONE = 0
        const val SYSCON_POWEROFF = 1
        const val SYSCON_REBOOT = 2

        // stepBatch return codes
        const val BATCH_OK = 0
        const val BATCH_WFI = 1
        const val BATCH_POWEROFF = 2
        const val BATCH_REBOOT = 3
        const val BATCH_TEST_HALT = 4
    }
}
