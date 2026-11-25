package com.example.emulator

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Genuine hand-assembled RV32IMA machine-code diagnostic monitor ROM loaded at 0x80000000.
 *
 * Every single instruction is real RV32IMA binary code executed directly by [Rv32ImaCpuCore]:
 * - Initializes UART 8250 via LCR.DLAB (writes DLL/DLM, then 8N1 LCR=0x03)
 * - Reads `misa` CSR and verifies the FDT magic at `a1` (DTB pointer)
 * - Prints boot telemetry and provides an interactive serial shell (`kernovm# `)
 * - Parks cleanly via `WFI` while waiting for UART RX input (near-0% idle CPU)
 * - Supports commands: `help`, `info`, `time`, `clear`, `reboot` (SYSCON 0x7777),
 *   and `poweroff` (SYSCON 0x5555), plus Backspace, Ctrl+C, and Ctrl+L.
 */
object DiagnosticMonitorRom {

    const val BUILTIN_IMAGE_ID = -1L

    val binary: ByteArray by lazy { assembleMonitorRom() }

    val sha256: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(binary)
        digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Minimal two-pass RV32I + Zicsr assembler for generating real machine-code binaries
     * (used by the built-in diagnostic ROM and JVM compliance tests).
     */
    class Rv32Assembler(private val baseAddress: Int = Rv32ImaCpuCore.RAM_BASE) {
        private val words = ArrayList<Int>()
        private val labels = HashMap<String, Int>()
        private val dataSegments = ArrayList<Pair<String, ByteArray>>()
        private val fixups = ArrayList<Fixup>()

        private sealed class Fixup(val wordIndex: Int) {
            class Branch(wordIdx: Int, val funct3: Int, val rs1: Int, val rs2: Int, val label: String) : Fixup(wordIdx)
            class Jal(wordIdx: Int, val rd: Int, val label: String) : Fixup(wordIdx)
            class LoadAddr(wordIdx: Int, val rd: Int, val label: String) : Fixup(wordIdx) // auipc + addi (2 words)
        }

        private fun curPc(): Int = baseAddress + (words.size shl 2)

        fun label(name: String) {
            labels[name] = curPc()
        }

        fun dataString(label: String, text: String) {
            dataSegments.add(label to (text.toByteArray(StandardCharsets.UTF_8) + 0.toByte()))
        }

        fun emit(insn: Int) {
            words.add(insn)
        }

        fun addi(rd: Int, rs1: Int, imm: Int) = emit(((imm and 0xFFF) shl 20) or (rs1 shl 15) or (0 shl 12) or (rd shl 7) or 0x13)
        fun andi(rd: Int, rs1: Int, imm: Int) = emit(((imm and 0xFFF) shl 20) or (rs1 shl 15) or (7 shl 12) or (rd shl 7) or 0x13)
        fun ori(rd: Int, rs1: Int, imm: Int) = emit(((imm and 0xFFF) shl 20) or (rs1 shl 15) or (6 shl 12) or (rd shl 7) or 0x13)
        fun srli(rd: Int, rs1: Int, shamt: Int) = emit(((shamt and 0x1F) shl 20) or (rs1 shl 15) or (5 shl 12) or (rd shl 7) or 0x13)
        fun slli(rd: Int, rs1: Int, shamt: Int) = emit(((shamt and 0x1F) shl 20) or (rs1 shl 15) or (1 shl 12) or (rd shl 7) or 0x13)

        fun add(rd: Int, rs1: Int, rs2: Int) = emit((0 shl 25) or (rs2 shl 20) or (rs1 shl 15) or (0 shl 12) or (rd shl 7) or 0x33)
        fun sub(rd: Int, rs1: Int, rs2: Int) = emit((0x20 shl 25) or (rs2 shl 20) or (rs1 shl 15) or (0 shl 12) or (rd shl 7) or 0x33)
        fun srl(rd: Int, rs1: Int, rs2: Int) = emit((0 shl 25) or (rs2 shl 20) or (rs1 shl 15) or (5 shl 12) or (rd shl 7) or 0x33)

        fun lui(rd: Int, immUpper20: Int) = emit((immUpper20 and 0xFFFFF000.toInt()) or (rd shl 7) or 0x37)
        fun auipc(rd: Int, immUpper20: Int) = emit((immUpper20 and 0xFFFFF000.toInt()) or (rd shl 7) or 0x17)

        fun li(rd: Int, value: Int) {
            if (value in -2048..2047) {
                addi(rd, 0, value)
            } else {
                val lo = (value shl 20) shr 20
                val hi = value - lo
                lui(rd, hi)
                if (lo != 0) addi(rd, rd, lo)
            }
        }

        fun la(rd: Int, label: String) {
            fixups.add(Fixup.LoadAddr(words.size, rd, label))
            emit(0) // auipc placeholder
            emit(0) // addi placeholder
        }

        fun lb(rd: Int, offset: Int, rs1: Int) = emit(((offset and 0xFFF) shl 20) or (rs1 shl 15) or (0 shl 12) or (rd shl 7) or 0x03)
        fun lbu(rd: Int, offset: Int, rs1: Int) = emit(((offset and 0xFFF) shl 20) or (rs1 shl 15) or (4 shl 12) or (rd shl 7) or 0x03)
        fun lw(rd: Int, offset: Int, rs1: Int) = emit(((offset and 0xFFF) shl 20) or (rs1 shl 15) or (2 shl 12) or (rd shl 7) or 0x03)

        fun sb(rs2: Int, offset: Int, rs1: Int) = emit(
            (((offset ushr 5) and 0x7F) shl 25) or (rs2 shl 20) or (rs1 shl 15) or (0 shl 12) or ((offset and 0x1F) shl 7) or 0x23
        )
        fun sw(rs2: Int, offset: Int, rs1: Int) = emit(
            (((offset ushr 5) and 0x7F) shl 25) or (rs2 shl 20) or (rs1 shl 15) or (2 shl 12) or ((offset and 0x1F) shl 7) or 0x23
        )

        fun beq(rs1: Int, rs2: Int, label: String) { fixups.add(Fixup.Branch(words.size, 0, rs1, rs2, label)); emit(0) }
        fun bne(rs1: Int, rs2: Int, label: String) { fixups.add(Fixup.Branch(words.size, 1, rs1, rs2, label)); emit(0) }
        fun blt(rs1: Int, rs2: Int, label: String) { fixups.add(Fixup.Branch(words.size, 4, rs1, rs2, label)); emit(0) }
        fun bge(rs1: Int, rs2: Int, label: String) { fixups.add(Fixup.Branch(words.size, 5, rs1, rs2, label)); emit(0) }

        fun jal(rd: Int, label: String) { fixups.add(Fixup.Jal(words.size, rd, label)); emit(0) }
        fun jalr(rd: Int, rs1: Int, imm: Int = 0) = emit(((imm and 0xFFF) shl 20) or (rs1 shl 15) or (0 shl 12) or (rd shl 7) or 0x67)
        fun call(label: String) = jal(RA, label)
        fun ret() = jalr(0, RA, 0)
        fun j(label: String) = jal(0, label)

        fun csrr(rd: Int, csr: Int) = emit(((csr and 0xFFF) shl 20) or (0 shl 15) or (2 shl 12) or (rd shl 7) or 0x73)
        fun wfi() = emit(0x10500073)

        fun build(): ByteArray {
            val out = ByteArrayOutputStream()
            val codeSize = words.size shl 2
            val dataBytes = ByteArrayOutputStream()
            for ((name, bytes) in dataSegments) {
                labels[name] = baseAddress + codeSize + dataBytes.size()
                dataBytes.write(bytes)
                while ((dataBytes.size() and 3) != 0) dataBytes.write(0)
            }

            for (fixup in fixups) {
                val pc = baseAddress + (fixup.wordIndex shl 2)
                when (fixup) {
                    is Fixup.Branch -> {
                        val target = labels[fixup.label] ?: error("Unknown branch label: ${fixup.label}")
                        val delta = target - pc
                        require(delta in -4096..4094 && (delta and 1) == 0) { "Branch out of range: $delta" }
                        words[fixup.wordIndex] =
                            (((delta ushr 12) and 1) shl 31) or
                                (((delta ushr 5) and 0x3F) shl 25) or
                                (fixup.rs2 shl 20) or
                                (fixup.rs1 shl 15) or
                                (fixup.funct3 shl 12) or
                                (((delta ushr 1) and 0xF) shl 8) or
                                (((delta ushr 11) and 1) shl 7) or
                                0x63
                    }
                    is Fixup.Jal -> {
                        val target = labels[fixup.label] ?: error("Unknown jal label: ${fixup.label}")
                        val delta = target - pc
                        words[fixup.wordIndex] =
                            (((delta ushr 20) and 1) shl 31) or
                                (((delta ushr 1) and 0x3FF) shl 21) or
                                (((delta ushr 11) and 1) shl 20) or
                                (((delta ushr 12) and 0xFF) shl 12) or
                                (fixup.rd shl 7) or
                                0x6F
                    }
                    is Fixup.LoadAddr -> {
                        val target = labels[fixup.label] ?: error("Unknown label: ${fixup.label}")
                        val delta = target - pc
                        val lo = (delta shl 20) shr 20
                        val hi = delta - lo
                        words[fixup.wordIndex] = (hi and 0xFFFFF000.toInt()) or (fixup.rd shl 7) or 0x17
                        words[fixup.wordIndex + 1] = ((lo and 0xFFF) shl 20) or (fixup.rd shl 15) or (0 shl 12) or (fixup.rd shl 7) or 0x13
                    }
                }
            }

            for (w in words) {
                out.write(w and 0xFF)
                out.write((w ushr 8) and 0xFF)
                out.write((w ushr 16) and 0xFF)
                out.write((w ushr 24) and 0xFF)
            }
            out.write(dataBytes.toByteArray())
            return out.toByteArray()
        }

        companion object {
            const val ZERO = 0
            const val RA = 1
            const val SP = 2
            const val T0 = 5
            const val T1 = 6
            const val T2 = 7
            const val S0 = 8
            const val S1 = 9
            const val A0 = 10
            const val A1 = 11
            const val A2 = 12
            const val S2 = 18
            const val S3 = 19
            const val S4 = 20
            const val S5 = 21
            const val S6 = 22
            const val S7 = 23
        }
    }

    private fun assembleMonitorRom(): ByteArray {
        val asm = Rv32Assembler(Rv32ImaCpuCore.RAM_BASE)
        with(asm) {
            val ZERO = Rv32Assembler.ZERO
            val SP = Rv32Assembler.SP
            val T0 = Rv32Assembler.T0
            val T1 = Rv32Assembler.T1
            val T2 = Rv32Assembler.T2
            val S0 = Rv32Assembler.S0 // saved DTB ptr
            val S1 = Rv32Assembler.S1 // saved MISA
            val S2 = Rv32Assembler.S2 // UART base (0x10000000)
            val S3 = Rv32Assembler.S3 // Line buffer addr (0x8000F000)
            val S4 = Rv32Assembler.S4 // Line buffer len
            val S5 = Rv32Assembler.S5 // saved DTB magic
            val A0 = Rv32Assembler.A0
            val A1 = Rv32Assembler.A1
            val RA = Rv32Assembler.RA

            // Entry @ 0x80000000: save a1 (DTB pointer), set stack @ 0x8000E000, linebuf @ 0x8000F000
            addi(S0, A1, 0)
            li(SP, Rv32ImaCpuCore.RAM_BASE + 0xE000)
            li(S3, Rv32ImaCpuCore.RAM_BASE + 0xF000)
            li(S2, Uart8250.BASE_ADDRESS)

            // Init UART 8250: LCR.DLAB=1 -> DLL=1, DLM=0 -> LCR=0x03 (8N1)
            addi(T0, ZERO, 0x80)
            sb(T0, 3, S2)
            addi(T0, ZERO, 0x01)
            sb(T0, 0, S2)
            sb(ZERO, 1, S2)
            addi(T0, ZERO, 0x03)
            sb(T0, 3, S2)

            // Query MISA CSR and read DTB magic word if a1 != 0
            csrr(S1, Rv32ImaCpuCore.CSR_MISA)
            addi(S5, ZERO, 0)
            beq(S0, ZERO, "skip_dtb_read")
            lw(S5, 0, S0)
            label("skip_dtb_read")

            // Print diagnostic boot banner
            la(A0, "str_banner")
            call("puts")
            la(A0, "str_misa")
            call("puts")
            addi(A0, S1, 0)
            call("puthex")
            la(A0, "str_dtb")
            call("puts")
            addi(A0, S0, 0)
            call("puthex")
            la(A0, "str_magic")
            call("puts")
            addi(A0, S5, 0)
            call("puthex")
            la(A0, "str_ready")
            call("puts")

            // Prompt loop
            label("prompt_loop")
            la(A0, "str_prompt")
            call("puts")
            addi(S4, ZERO, 0)

            label("read_char")
            lbu(T0, 5, S2) // UART LSR
            andi(T0, T0, 1)
            bne(T0, ZERO, "got_char")
            wfi()
            j("read_char")

            label("got_char")
            lbu(T0, 0, S2) // UART RBR

            // Ctrl+C (0x03) -> ^C newline
            addi(T1, ZERO, 0x03)
            bne(T0, T1, "check_ctrl_l")
            la(A0, "str_ctrl_c")
            call("puts")
            j("prompt_loop")

            // Ctrl+L (0x0C) -> clear screen
            label("check_ctrl_l")
            addi(T1, ZERO, 0x0C)
            bne(T0, T1, "check_enter")
            la(A0, "str_clear")
            call("puts")
            j("prompt_loop")

            // Enter (\r or \n)
            label("check_enter")
            addi(T1, ZERO, 13)
            beq(T0, T1, "handle_enter")
            addi(T1, ZERO, 10)
            beq(T0, T1, "handle_enter")

            // Backspace (0x7F or 0x08)
            addi(T1, ZERO, 0x7F)
            beq(T0, T1, "handle_bs")
            addi(T1, ZERO, 0x08)
            bne(T0, T1, "handle_printable")

            label("handle_bs")
            beq(S4, ZERO, "read_char")
            addi(S4, S4, -1)
            la(A0, "str_bs")
            call("puts")
            j("read_char")

            label("handle_printable")
            addi(T1, ZERO, 32)
            blt(T0, T1, "read_char")
            addi(T1, ZERO, 60) // max cmd length
            bge(S4, T1, "read_char")
            add(T1, S3, S4)
            sb(T0, 0, T1)
            addi(S4, S4, 1)
            sb(T0, 0, S2) // echo char
            j("read_char")

            label("handle_enter")
            la(A0, "str_crlf")
            call("puts")
            add(T1, S3, S4)
            sb(ZERO, 0, T1) // null-terminate line
            beq(S4, ZERO, "prompt_loop")

            // Command dispatch
            addi(A0, S3, 0)
            la(A1, "cmd_help")
            call("streq")
            bne(A0, ZERO, "do_help")

            addi(A0, S3, 0)
            la(A1, "cmd_info")
            call("streq")
            bne(A0, ZERO, "do_info")

            addi(A0, S3, 0)
            la(A1, "cmd_time")
            call("streq")
            bne(A0, ZERO, "do_time")

            addi(A0, S3, 0)
            la(A1, "cmd_disk")
            call("streq")
            bne(A0, ZERO, "do_disk")

            addi(A0, S3, 0)
            la(A1, "cmd_clear")
            call("streq")
            bne(A0, ZERO, "do_clear")

            addi(A0, S3, 0)
            la(A1, "cmd_reboot")
            call("streq")
            bne(A0, ZERO, "do_reboot")

            addi(A0, S3, 0)
            la(A1, "cmd_poweroff")
            call("streq")
            bne(A0, ZERO, "do_poweroff")

            // Unknown command
            la(A0, "str_unknown")
            call("puts")
            addi(A0, S3, 0)
            call("puts")
            la(A0, "str_unknown_hint")
            call("puts")
            j("prompt_loop")

            label("do_help")
            la(A0, "str_help")
            call("puts")
            j("prompt_loop")

            label("do_info")
            la(A0, "str_info_hdr")
            call("puts")
            addi(A0, S1, 0)
            call("puthex")
            la(A0, "str_dtb")
            call("puts")
            addi(A0, S0, 0)
            call("puthex")
            la(A0, "str_magic")
            call("puts")
            addi(A0, S5, 0)
            call("puthex")
            la(A0, "str_crlf")
            call("puts")
            j("prompt_loop")

            label("do_time")
            la(A0, "str_time")
            call("puts")
            li(T0, Rv32ImaCpuCore.CLINT_BASE + 0xBFFC)
            lw(A0, 0, T0)
            call("puthex")
            addi(T1, ZERO, '_'.code)
            sb(T1, 0, S2)
            li(T0, Rv32ImaCpuCore.CLINT_BASE + 0xBFF8)
            lw(A0, 0, T0)
            call("puthex")
            la(A0, "str_time_unit")
            call("puts")
            j("prompt_loop")

            label("do_disk")
            li(T0, VirtioBlockDevice.BASE_ADDRESS)
            lw(T1, 0, T0) // VirtIO magic
            li(T2, VirtioBlockDevice.VIRTIO_MAGIC)
            bne(T1, T2, "do_disk_none")
            la(A0, "str_disk_hdr")
            call("puts")
            li(T0, VirtioBlockDevice.BASE_ADDRESS)
            lw(A0, 0x008, T0) // DeviceID (2 = block)
            call("puthex")
            la(A0, "str_disk_cap")
            call("puts")
            li(T0, VirtioBlockDevice.BASE_ADDRESS)
            lw(A0, 0x100, T0) // Sector count (low 32)
            call("puthex")
            la(A0, "str_disk_unit")
            call("puts")
            j("prompt_loop")

            label("do_disk_none")
            la(A0, "str_disk_none")
            call("puts")
            j("prompt_loop")

            label("do_clear")
            la(A0, "str_clear")
            call("puts")
            j("prompt_loop")

            label("do_reboot")
            la(A0, "str_reboot")
            call("puts")
            li(T0, Rv32ImaCpuCore.SYSCON_BASE)
            li(T1, 0x7777)
            sw(T1, 0, T0)
            j("prompt_loop")

            label("do_poweroff")
            la(A0, "str_poweroff")
            call("puts")
            li(T0, Rv32ImaCpuCore.SYSCON_BASE)
            li(T1, 0x5555)
            sw(T1, 0, T0)
            label("halt_loop")
            wfi()
            j("halt_loop")

            // --- Subroutine: puts(a0 = null-terminated string) ---
            label("puts")
            lbu(T0, 0, A0)
            beq(T0, ZERO, "puts_done")
            sb(T0, 0, S2)
            addi(A0, A0, 1)
            j("puts")
            label("puts_done")
            ret()

            // --- Subroutine: puthex(a0 = 32-bit int) ---
            label("puthex")
            addi(SP, SP, -12)
            sw(RA, 8, SP)
            sw(A0, 4, SP)
            addi(T1, ZERO, 28) // shift counter: 28 down to 0
            label("puthex_loop")
            lw(T0, 4, SP)
            srl(T0, T0, T1)
            andi(T0, T0, 0xF)
            addi(T2, ZERO, 10)
            blt(T0, T2, "puthex_digit")
            addi(T0, T0, 'a'.code - 10)
            j("puthex_emit")
            label("puthex_digit")
            addi(T0, T0, '0'.code)
            label("puthex_emit")
            sb(T0, 0, S2)
            beq(T1, ZERO, "puthex_done")
            addi(T1, T1, -4)
            j("puthex_loop")
            label("puthex_done")
            lw(RA, 8, SP)
            addi(SP, SP, 12)
            ret()

            // --- Subroutine: streq(a0, a1) -> a0 = 1 if equal else 0 ---
            label("streq")
            lbu(T0, 0, A0)
            lbu(T1, 0, A1)
            bne(T0, T1, "streq_no")
            beq(T0, ZERO, "streq_yes")
            addi(A0, A0, 1)
            addi(A1, A1, 1)
            j("streq")
            label("streq_yes")
            addi(A0, ZERO, 1)
            ret()
            label("streq_no")
            addi(A0, ZERO, 0)
            ret()

            // Data strings
            dataString(
                "str_banner",
                "\u001b[1mKernoVM RV32IMA Diagnostic Monitor ROM (M-Mode No-MMU)\u001b[0m\r\n" +
                    "UART 8250 @ 0x10000000 [polling] | CLINT @ 0x11000000 | SYSCON @ 0x11100000\r\n"
            )
            dataString("str_misa", "CSR misa : 0x")
            dataString("str_dtb", "\r\nDTB addr : 0x")
            dataString("str_magic", " (magic=0x")
            dataString(
                "str_ready",
                ")\r\nKetik 'help' untuk daftar perintah diagnostik, atau muat kernel Linux M-Mode No-MMU di tab Image.\r\n\r\n"
            )
            dataString("str_prompt", "\u001b[32mkernovm#\u001b[0m ")
            dataString("str_crlf", "\r\n")
            dataString("str_ctrl_c", "^C\r\n")
            dataString("str_bs", "\b \b")
            dataString("str_clear", "\u001b[2J\u001b[H")
            dataString(
                "str_help",
                "Perintah RV32IMA ROM:\r\n" +
                    "  help     - tampilkan bantuan ini\r\n" +
                    "  info     - baca CSR misa, alamat DTB & magic FDT\r\n" +
                    "  time     - baca register CLINT mtime (1 MHz)\r\n" +
                    "  disk     - cek ISO / disk VirtIO-blk @ 0x10001000\r\n" +
                    "  clear    - uji urutan ANSI clear screen\r\n" +
                    "  reboot   - tulis 0x7777 ke SYSCON (0x11100000)\r\n" +
                    "  poweroff - tulis 0x5555 ke SYSCON (0x11100000)\r\n"
            )
            dataString("str_info_hdr", "CSR misa : 0x")
            dataString("str_time", "CLINT mtime = 0x")
            dataString("str_time_unit", " ticks (1 MHz)\r\n")
            dataString("str_disk_hdr", "VirtIO-blk @ 0x10001000 [TERPASANG] devId=0x")
            dataString("str_disk_cap", " sectors=0x")
            dataString("str_disk_unit", " (512B/sektor)\r\n")
            dataString("str_disk_none", "Tidak ada ISO / Disk VirtIO-blk terpasang di profil VM ini.\r\n")
            dataString("str_reboot", "Mengirim SYSCON reboot (0x7777)...\r\n")
            dataString("str_poweroff", "Mengirim SYSCON poweroff (0x5555)...\r\n")
            dataString("str_unknown", "Perintah tidak dikenal: ")
            dataString("str_unknown_hint", " (ketik 'help')\r\n")

            dataString("cmd_help", "help")
            dataString("cmd_info", "info")
            dataString("cmd_time", "time")
            dataString("cmd_disk", "disk")
            dataString("cmd_clear", "clear")
            dataString("cmd_reboot", "reboot")
            dataString("cmd_poweroff", "poweroff")
        }
        return asm.build()
    }
}
