package com.example

import com.example.emulator.DiagnosticMonitorRom.Rv32Assembler
import com.example.emulator.Rv32ImaCpuCore
import com.example.emulator.Uart8250
import com.example.vm.FreezableVirtualClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Rv32ImaCpuComplianceTest {

    private fun newCpu(
        ramBytes: Int = 64 * 1024,
        clockUs: () -> Long = { 0L }
    ): Rv32ImaCpuCore {
        val core = Rv32ImaCpuCore(
            ram = IntArray(ramBytes ushr 2),
            uart = Uart8250(),
            clockSourceUs = clockUs
        )
        core.reset(entryPc = Rv32ImaCpuCore.RAM_BASE)
        return core
    }

    private fun rType(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int, opcode: Int): Int =
        ((funct7 and 0x7F) shl 25) or
            ((rs2 and 0x1F) shl 20) or
            ((rs1 and 0x1F) shl 15) or
            ((funct3 and 0x7) shl 12) or
            ((rd and 0x1F) shl 7) or
            (opcode and 0x7F)

    private fun csrInsn(csr: Int, rs1: Int, funct3: Int, rd: Int): Int =
        ((csr and 0xFFF) shl 20) or
            ((rs1 and 0x1F) shl 15) or
            ((funct3 and 0x7) shl 12) or
            ((rd and 0x1F) shl 7) or
            0x73

    @Test
    fun rv32mDivisionEdgeCasesNeverThrowAndMatchSpec() {
        val cpu = newCpu()
        val asm = Rv32Assembler().apply {
            // x1 = Int.MIN_VALUE, x2 = -1, x3 = 0, x4 = 42
            li(1, Int.MIN_VALUE)
            li(2, -1)
            li(3, 0)
            li(4, 42)
            // DIV x5 = x4 / 0 -> -1
            emit(rType(0x01, 3, 4, 4, 5, 0x33))
            // DIVU x6 = x4 / 0 -> -1
            emit(rType(0x01, 3, 4, 5, 6, 0x33))
            // DIV x7 = INT_MIN / -1 -> INT_MIN
            emit(rType(0x01, 2, 1, 4, 7, 0x33))
            // REM x8 = x4 % 0 -> 42
            emit(rType(0x01, 3, 4, 6, 8, 0x33))
            // REM x9 = INT_MIN % -1 -> 0
            emit(rType(0x01, 2, 1, 6, 9, 0x33))
            // REMU x10 = x4 % 0 -> 42
            emit(rType(0x01, 3, 4, 7, 10, 0x33))
            // MULH x11 = (-2) * (-3) high 32 -> 0; (-2) * 3 high 32 -> -1
            li(12, -2)
            li(13, 3)
            emit(rType(0x01, 13, 12, 1, 11, 0x33))
        }
        cpu.loadBytesIntoRam(asm.build(), 0)
        cpu.stepBatch(15)

        assertEquals(-1, cpu.regs[5])
        assertEquals(-1, cpu.regs[6])
        assertEquals(Int.MIN_VALUE, cpu.regs[7])
        assertEquals(42, cpu.regs[8])
        assertEquals(0, cpu.regs[9])
        assertEquals(42, cpu.regs[10])
        assertEquals(-1, cpu.regs[11])
    }

    @Test
    fun rv32aAmoPreservesOldValueWhenRdEqualsRs1OrRs2AndLrScWorks() {
        val cpu = newCpu()
        val dataAddr = Rv32ImaCpuCore.RAM_BASE + 0x2000
        cpu.ram[0x2000 ushr 2] = 100

        val asm = Rv32Assembler().apply {
            // x5 = dataAddr, x6 = 25
            li(5, dataAddr)
            li(6, 25)
            // AMOADD.W x5, x6, (x5)  [rd == rs1!]: mem becomes 125, x5 receives old value 100
            emit(rType(0x00 shl 2, 6, 5, 2, 5, 0x2F))
            // Restore x7 = dataAddr; AMOSWAP.W x6, x6, (x7) [rd == rs2!]: mem becomes 25, x6 receives old value 125
            li(7, dataAddr)
            emit(rType(0x01 shl 2, 6, 7, 2, 6, 0x2F))
            // LR.W x8, (x7) -> 25
            emit(rType(0x02 shl 2, 0, 7, 2, 8, 0x2F))
            // SC.W x9, x8, (x7) -> x9 == 0 (success)
            li(10, 777)
            emit(rType(0x03 shl 2, 10, 7, 2, 9, 0x2F))
            // Second SC.W x11, x10, (x7) without LR -> x11 == 1 (fail)
            emit(rType(0x03 shl 2, 10, 7, 2, 11, 0x2F))
        }
        cpu.loadBytesIntoRam(asm.build(), 0)
        cpu.stepBatch(15)

        assertEquals(100, cpu.regs[5])
        assertEquals(125, cpu.regs[6])
        assertEquals(25, cpu.regs[8])
        assertEquals(0, cpu.regs[9])
        assertEquals(1, cpu.regs[11])
        assertEquals(777, cpu.readPhysicalWord(dataAddr))
    }

    @Test
    fun csrSpecMisaReadOnlyTrapsPmpMretEcallEbreakAndMtvecVectored() {
        val cpu = newCpu()
        // Initial mstatus check: MIE=0, MPIE=1, MPP=M -> 0x00001880
        assertEquals(0x00001880, cpu.mstatus)

        val trapHandlerAddr = Rv32ImaCpuCore.RAM_BASE + 0x100
        val asm = Rv32Assembler().apply {
            // Set mtvec direct to trapHandlerAddr
            li(1, trapHandlerAddr)
            emit(csrInsn(Rv32ImaCpuCore.CSR_MTVEC, 1, 1, 0)) // csrw mtvec, x1
            // Read misa -> x2 (expect 0x40001101)
            emit(csrInsn(Rv32ImaCpuCore.CSR_MISA, 0, 2, 2))
            // Read mvendorid (RO CSR) via CSRRS with rs1=0 -> must NOT trap, x3 = 0
            emit(csrInsn(Rv32ImaCpuCore.CSR_MVENDORID, 0, 2, 3))
            // Read pmpcfg0 -> 0, write pmpaddr0 -> ignored (no trap)
            emit(csrInsn(0x3A0, 0, 2, 4))
            li(5, 0x1234)
            emit(csrInsn(0x3B0, 5, 1, 0))
            // Attempt write to RO CSR mvendorid (rs1=x5 != 0) -> MUST trap Illegal Instruction (mcause=2)
            emit(csrInsn(Rv32ImaCpuCore.CSR_MVENDORID, 5, 2, 6))
            // After handler returns (mepc+4), execute ECALL -> mcause=11
            emit(0x00000073)
            // After handler returns, execute EBREAK -> mcause=3
            emit(0x00100073)
            // Park cleanly after the 3 expected traps
            label("done")
            j("done")
        }
        cpu.loadBytesIntoRam(asm.build(), 0)

        // Place trap handler at +0x100 that logs mcause into s0/s1/s2 and advances mepc by 4 then MRET
        val handlerAsm = Rv32Assembler(trapHandlerAddr).apply {
            // Read mcause into t0, append to ring in RAM at 0x80002000 + x28
            emit(csrInsn(Rv32ImaCpuCore.CSR_MCAUSE, 0, 2, 29)) // t4 = mcause
            li(30, Rv32ImaCpuCore.RAM_BASE + 0x2000)
            add(30, 30, 28)
            sw(29, 0, 30)
            addi(28, 28, 4)
            // Advance mepc += 4
            emit(csrInsn(Rv32ImaCpuCore.CSR_MEPC, 0, 2, 29))
            addi(29, 29, 4)
            emit(csrInsn(Rv32ImaCpuCore.CSR_MEPC, 29, 1, 0))
            // MRET
            emit(0x30200073)
        }
        cpu.loadBytesIntoRam(handlerAsm.build(), 0x100)

        cpu.stepBatch(80)

        assertEquals(0x40001101, cpu.regs[2])
        assertEquals(0, cpu.regs[3])
        assertEquals(0, cpu.regs[4])
        // Three traps recorded in exact order: RO CSR write (2), ECALL (11), EBREAK (3)
        assertEquals(12, cpu.regs[28])
        assertEquals(Rv32ImaCpuCore.CAUSE_ILLEGAL_INSN, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2000))
        assertEquals(Rv32ImaCpuCore.CAUSE_ECALL_M, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2004))
        assertEquals(Rv32ImaCpuCore.CAUSE_BREAKPOINT, cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2008))
    }

    @Test
    fun mtvecVectoredDispatchesTimerInterruptToBasePlus4TimesCause() {
        var fakeUs = 500L
        val cpu = newCpu(clockUs = { fakeUs })
        val vectorBase = Rv32ImaCpuCore.RAM_BASE + 0x200
        val timerVectorAddr = vectorBase + (Rv32ImaCpuCore.CAUSE_MACHINE_TIMER_INT * 4)

        val asm = Rv32Assembler().apply {
            // mtvec = vectorBase | 1 (vectored mode)
            li(1, vectorBase or 1)
            emit(csrInsn(Rv32ImaCpuCore.CSR_MTVEC, 1, 1, 0))
            // Enable MTIE (bit 7) and MSTATUS.MIE (bit 3)
            li(2, Rv32ImaCpuCore.MIP_MTIP)
            emit(csrInsn(Rv32ImaCpuCore.CSR_MIE, 2, 1, 0))
            li(3, Rv32ImaCpuCore.MSTATUS_MIE)
            emit(csrInsn(Rv32ImaCpuCore.CSR_MSTATUS, 3, 2, 0))
            // Arm mtimecmp = 100 (already <= fakeUs=500)
            li(4, Rv32ImaCpuCore.CLINT_BASE + 0x4000)
            li(5, 100)
            sw(5, 0, 4)
            sw(0, 4, 4)
            // Loop NOPs until interrupt hits
            label("spin")
            addi(0, 0, 0)
            j("spin")
        }
        cpu.loadBytesIntoRam(asm.build(), 0)

        // At timerVectorAddr, set x20 = 0xCAFE and trigger SYSCON poweroff
        val vecAsm = Rv32Assembler(timerVectorAddr).apply {
            li(20, 0xCAFE)
            li(21, Rv32ImaCpuCore.SYSCON_BASE)
            li(22, 0x5555)
            sw(22, 0, 21)
        }
        cpu.loadBytesIntoRam(vecAsm.build(), timerVectorAddr - Rv32ImaCpuCore.RAM_BASE)

        val result = cpu.stepBatch(500)
        assertEquals(Rv32ImaCpuCore.BATCH_POWEROFF, result)
        assertEquals(0xCAFE, cpu.regs[20])
        assertEquals(Rv32ImaCpuCore.CAUSE_MACHINE_TIMER_INT or Int.MIN_VALUE, cpu.mcause)
    }

    @Test
    fun uart8250SupportsDlabDllDlmWithoutCorruptingLsrOrFifo() {
        val tx = mutableListOf<Int>()
        val uart = Uart8250(onTxByte = { tx.add(it) })

        // Enable DLAB (LCR bit 7 = 1), program DLL=0x0C, DLM=0x01
        uart.writeRegister(3, 0x83)
        uart.writeRegister(0, 0x0C)
        uart.writeRegister(1, 0x01)
        assertEquals(0x0C, uart.readRegister(0))
        assertEquals(0x01, uart.readRegister(1))
        assertTrue(tx.isEmpty()) // Writing DLL must NOT emit TX byte

        // Disable DLAB (LCR=0x03), verify THR TX & LSR stability
        uart.writeRegister(3, 0x03)
        assertEquals(0x60, uart.readRegister(5)) // THRE | TEMT, no DR yet
        uart.writeRegister(0, 'K'.code)
        assertEquals(listOf('K'.code), tx)

        // Push RX byte -> LSR DR bit set, read clears it
        uart.pushRxByte('A'.code)
        assertEquals(0x61, uart.readRegister(5))
        assertEquals('A'.code, uart.readRegister(0))
        assertEquals(0x60, uart.readRegister(5))
    }

    @Test
    fun freezableVirtualClockFreezesOnPauseAndResumesWithoutJump() {
        var fakeNano = 1_000_000_000L
        val clock = FreezableVirtualClock(clockMhz = 1, nanoTimeProvider = { fakeNano })
        clock.reset(startImmediately = true)

        fakeNano += 10_000_000L // +10ms -> 10,000 ticks
        assertEquals(10_000L, clock.currentTicks())

        // Pause and let wall clock advance by 60 seconds!
        clock.pause()
        fakeNano += 60_000_000_000L
        assertEquals(10_000L, clock.currentTicks()) // Frozen!

        // Resume and advance 2ms -> must be 12,000 ticks, NOT 60,012,000 ticks
        clock.resume()
        fakeNano += 2_000_000L
        assertEquals(12_000L, clock.currentTicks())
    }

    @Test
    fun wfiParksUntilTimerOrUartRxWakesCore() {
        var fakeUs = 100L
        val cpu = newCpu(clockUs = { fakeUs })
        val asm = Rv32Assembler().apply {
            // Enable MTIE (without global MIE so WFI wakes cleanly into next insn)
            li(1, Rv32ImaCpuCore.MIP_MTIP)
            emit(csrInsn(Rv32ImaCpuCore.CSR_MIE, 1, 1, 0))
            // Set mtimecmp = 500
            li(2, Rv32ImaCpuCore.CLINT_BASE + 0x4000)
            li(3, 500)
            sw(3, 0, 2)
            sw(0, 4, 2)
            wfi()
            li(10, 0x1234)
        }
        cpu.loadBytesIntoRam(asm.build(), 0)

        val first = cpu.stepBatch(100)
        assertEquals(Rv32ImaCpuCore.BATCH_WFI, first)
        assertTrue(cpu.isWfiWaiting)
        assertEquals(0, cpu.regs[10])

        // Advance time to 500 -> timer wakes WFI
        fakeUs = 500L
        assertTrue(cpu.syncInterrupts(fakeUs))
        assertFalse(cpu.isWfiWaiting)

        cpu.stepBatch(10)
        assertEquals(0x1234, cpu.regs[10])
    }

    @Test
    fun riscvTestsBinaryRunnerHarnessVerifiesPassCondition() {
        // Synthetic riscv-tests style binary: sets gp=1, a0=0, executes ECALL (pass)
        val cpu = newCpu()
        var passed = false
        cpu.testEcallInterceptor = { core ->
            passed = (core.regs[3] == 1 && core.regs[10] == 0)
            true
        }
        val asm = Rv32Assembler().apply {
            li(3, 1)  // gp = 1 (pass)
            li(10, 0) // a0 = 0
            emit(0x00000073) // ecall
        }
        cpu.loadBytesIntoRam(asm.build(), 0)
        val res = cpu.stepBatch(20)
        assertEquals(Rv32ImaCpuCore.BATCH_TEST_HALT, res)
        assertTrue("riscv-tests pass convention (gp==1 && a0==0) failed", passed)

        // If local riscv-tests raw binaries are dropped into test-fixtures/riscv-tests/, run them all
        val fixturesDir = File("test-fixtures/riscv-tests")
        if (fixturesDir.exists() && fixturesDir.isDirectory) {
            fixturesDir.listFiles { f -> f.extension == "bin" }?.forEach { bin ->
                val testCpu = newCpu(ramBytes = 2 * 1024 * 1024)
                var testPass = false
                testCpu.testEcallInterceptor = { c ->
                    testPass = (c.regs[3] == 1 && c.regs[10] == 0)
                    true
                }
                testCpu.loadBytesIntoRam(bin.readBytes(), 0)
                val status = testCpu.stepBatch(5_000_000)
                assertEquals("Fixture ${bin.name} did not reach ECALL", Rv32ImaCpuCore.BATCH_TEST_HALT, status)
                assertTrue("Fixture ${bin.name} failed with gp=${testCpu.regs[3]} a0=${testCpu.regs[10]}", testPass)
            }
        }
    }

    @Test
    fun userModePrivilegeTransitionAndEcallCause8() {
        val cpu = newCpu()
        val trapHandlerAddr = Rv32ImaCpuCore.RAM_BASE + 0x100
        val userCodeAddr = Rv32ImaCpuCore.RAM_BASE + 0x200

        // 1. Initial state: M-mode
        assertEquals(Rv32ImaCpuCore.PRIVILEGE_M, cpu.privilegeMode)

        // Set up trap handler at 0x100
        val handlerAsm = Rv32Assembler(trapHandlerAddr).apply {
            emit(csrInsn(Rv32ImaCpuCore.CSR_MCAUSE, 0, 2, 29)) // t4 = mcause
            li(30, Rv32ImaCpuCore.RAM_BASE + 0x2000)
            sw(29, 0, 30) // store mcause to RAM
            // Read mstatus to inspect MPP
            emit(csrInsn(Rv32ImaCpuCore.CSR_MSTATUS, 0, 2, 28))
            sw(28, 4, 30) // store mstatus to RAM
            // Advance mepc += 4
            emit(csrInsn(Rv32ImaCpuCore.CSR_MEPC, 0, 2, 27))
            addi(27, 27, 4)
            emit(csrInsn(Rv32ImaCpuCore.CSR_MEPC, 27, 1, 0))
            // MRET back
            emit(0x30200073)
        }
        cpu.loadBytesIntoRam(handlerAsm.build(), 0x100)

        // Set up user code at 0x200: executes ecall
        val userAsm = Rv32Assembler(userCodeAddr).apply {
            emit(0x00000073) // ecall in U-mode
            li(1, 0x42)      // ra = 0x42
            // Loop forever
            emit(0x0000006f) // j .
        }
        cpu.loadBytesIntoRam(userAsm.build(), 0x200)

        // Set up boot code at 0x0:
        // Set mtvec = trapHandlerAddr
        // Set mepc = userCodeAddr
        // Set mstatus.MPP = 0 (U-mode)
        // MRET -> enters U-mode at 0x200
        val bootAsm = Rv32Assembler(Rv32ImaCpuCore.RAM_BASE).apply {
            li(1, trapHandlerAddr)
            emit(csrInsn(Rv32ImaCpuCore.CSR_MTVEC, 1, 1, 0))
            li(2, userCodeAddr)
            emit(csrInsn(Rv32ImaCpuCore.CSR_MEPC, 2, 1, 0))
            // Clear MPP (bits 12:11) in mstatus to switch to U-mode on MRET
            li(3, (3 shl 11))
            emit(csrInsn(Rv32ImaCpuCore.CSR_MSTATUS, 3, 3, 0)) // csrrc mstatus, x3
            // MRET to user mode
            emit(0x30200073)
        }
        cpu.loadBytesIntoRam(bootAsm.build(), 0)

        cpu.stepBatch(30)

        // Check trapped mcause: must be CAUSE_ECALL_U (8)
        val recordedCause = cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2000)
        assertEquals("User-mode ecall must yield mcause = 8", Rv32ImaCpuCore.CAUSE_ECALL_U, recordedCause)

        // Check that trap saved previous privilege (U-mode: 0) in mstatus.MPP
        val recordedMstatus = cpu.readPhysicalWord(Rv32ImaCpuCore.RAM_BASE + 0x2004)
        val recordedMpp = (recordedMstatus ushr 11) and 3
        assertEquals("Trap from U-mode must save MPP=0 in mstatus", 0, recordedMpp)
    }
}
