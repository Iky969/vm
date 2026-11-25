package com.example

import com.example.emulator.AnsiTerminalBuffer
import com.example.emulator.DeviceTreeBuilder
import com.example.emulator.DiagnosticMonitorRom
import com.example.emulator.FdtInspector
import com.example.emulator.FdtTargetArch
import com.example.emulator.Rv32ImaCpuCore
import com.example.emulator.Uart8250
import com.example.emulator.VirtioBlockDevice
import com.example.emulator.VirtioNetDevice
import com.example.ui.components.arrowSeq
import com.example.ui.components.mapAltKey
import com.example.ui.components.mapCtrlKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface SlowTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceTreeAndTerminalTest {

    @Test
    fun dynamicFdtBuilderProducesValidBinaryWithTtyS0PollingAndSysconNodes() {
        val ramSize = 64L * 1024L * 1024L
        val dtb = DeviceTreeBuilder.build(
            ramSizeBytes = ramSize,
            bootArgs = DeviceTreeBuilder.DEFAULT_BOOTARGS,
            timebaseFrequencyHz = 1_000_000,
            targetArch = FdtTargetArch.RISCV32
        )

        assertTrue("DTB size must be 8-byte aligned", (dtb.size and 7) == 0)
        assertTrue(FdtInspector.hasValidMagic(dtb))

        val parsed = FdtInspector.parse(dtb)
        assertEquals(DeviceTreeBuilder.FDT_MAGIC, parsed.magic)
        assertEquals(DeviceTreeBuilder.FDT_VERSION, parsed.version)
        assertEquals(ramSize, parsed.detectedMemorySizeBytes)
        assertEquals(
            DeviceTreeBuilder.DEFAULT_BOOTARGS,
            parsed.bootArgs
        )

        // Verify /cpus/cpu@0
        val cpuNode = parsed.nodes["/cpus/cpu@0"]
        assertNotNull(cpuNode)
        assertEquals("rv32ima", cpuNode!!.stringProp("riscv,isa"))
        assertEquals("riscv,none", cpuNode.stringProp("mmu-type"))

        // Default targetArch builds RV64 FDT
        val rv64Dtb = DeviceTreeBuilder.build(ramSizeBytes = ramSize)
        val parsedRv64 = FdtInspector.parse(rv64Dtb)
        assertEquals("rv64ima", parsedRv64.targetArchitectureIsa)
        assertEquals("KernoVM RV64", parsedRv64.modelName)
        assertEquals("riscv,sv39", parsedRv64.nodes["/cpus/cpu@0"]?.stringProp("mmu-type"))

        // Verify /soc/serial@10000000 is ns16550a polling (NO PLIC interrupts property)
        val serialNode = parsed.nodes["/soc/serial@10000000"]
        assertNotNull(serialNode)
        assertEquals("ns16550a", serialNode!!.stringProp("compatible"))
        assertEquals(listOf(0x10000000, 0x100), serialNode.u32ListProp("reg"))
        assertFalse(serialNode.properties.containsKey("interrupts"))

        // Verify CLINT
        val clintNode = parsed.nodes["/soc/clint@11000000"]
        assertNotNull(clintNode)
        assertTrue(clintNode!!.stringListProp("compatible").contains("riscv,clint0"))

        // Verify SYSCON + explicit Linux syscon-poweroff (0x5555) & syscon-reboot (0x7777)
        val sysconNode = parsed.nodes["/soc/syscon@11100000"]
        val poweroffNode = parsed.nodes["/soc/poweroff"]
        val rebootNode = parsed.nodes["/soc/reboot"]
        assertNotNull(sysconNode)
        assertNotNull(poweroffNode)
        assertNotNull(rebootNode)
        assertEquals("syscon-poweroff", poweroffNode!!.stringProp("compatible"))
        assertEquals(listOf(0x5555), poweroffNode.u32ListProp("value"))
        assertEquals("syscon-reboot", rebootNode!!.stringProp("compatible"))
        assertEquals(listOf(0x7777), rebootNode.u32ListProp("value"))
    }

    @Test
    fun dynamicFdtBuilderProducesValidSimpleFramebufferNode() {
        val ramSize = 64L * 1024L * 1024L
        val fbConfig = DeviceTreeBuilder.FramebufferConfig(
            width = 640,
            height = 480,
            format = "r5g6b5"
        )
        val expectedBase = fbConfig.resolveBaseAddress(ramSize)
        val dtb = DeviceTreeBuilder.build(
            ramSizeBytes = ramSize,
            framebufferConfig = fbConfig
        )

        assertTrue(FdtInspector.hasValidMagic(dtb))
        val parsed = FdtInspector.parse(dtb)

        val fbNode = parsed.detectedFramebufferNode
        assertNotNull("Expected simple-framebuffer node in DTB", fbNode)
        assertEquals("simple-framebuffer", fbNode!!.stringProp("compatible"))
        assertEquals("r5g6b5", fbNode.stringProp("format"))
        assertEquals("okay", fbNode.stringProp("status"))
        assertEquals(listOf(640), fbNode.u32ListProp("width"))
        assertEquals(listOf(480), fbNode.u32ListProp("height"))
        assertEquals(listOf(1280), fbNode.u32ListProp("stride"))
        assertEquals(listOf(expectedBase.toInt(), (640 * 480 * 2)), fbNode.u32ListProp("reg"))
    }

    @Test
    fun dynamicFdtBuilderGeneratesReservedMemoryNodeForFramebufferAndDtb() {
        val ramSize = 64L * 1024L * 1024L
        val fbConfig = DeviceTreeBuilder.FramebufferConfig(
            width = 640,
            height = 480,
            format = "r5g6b5"
        )
        val expectedFbBase = fbConfig.resolveBaseAddress(ramSize)
        val dtb = DeviceTreeBuilder.build(
            ramSizeBytes = ramSize,
            framebufferConfig = fbConfig
        )

        assertTrue(FdtInspector.hasValidMagic(dtb))
        val parsed = FdtInspector.parse(dtb)

        // Memory should be truncated before framebuffer base
        val effectiveMemory = parsed.detectedMemorySizeBytes
        assertNotNull(effectiveMemory)
        assertEquals(expectedFbBase - 0x80000000L, effectiveMemory)

        // Check /reserved-memory node
        val reservedNode = parsed.nodes["/reserved-memory"]
        assertNotNull("Expected /reserved-memory node in DTB", reservedNode)

        val hexFbAddr = expectedFbBase.toInt().toUInt().toString(16)
        val fbRsvNode = parsed.nodes["/reserved-memory/framebuffer@$hexFbAddr"]
        assertNotNull("Expected /reserved-memory/framebuffer node", fbRsvNode)
        assertTrue(fbRsvNode!!.properties.containsKey("no-map"))
        assertEquals(listOf(expectedFbBase.toInt(), (640 * 480 * 2)), fbRsvNode.u32ListProp("reg"))

        val dtbAddr = 0x80000000L + ramSize - 0x20000L
        val hexDtbAddr = dtbAddr.toInt().toUInt().toString(16)
        val dtbRsvNode = parsed.nodes["/reserved-memory/dtb@$hexDtbAddr"]
        assertNotNull("Expected /reserved-memory/dtb node", dtbRsvNode)
        assertTrue(dtbRsvNode!!.properties.containsKey("no-map"))
        assertEquals(listOf(dtbAddr.toInt(), 0x20000), dtbRsvNode.u32ListProp("reg"))
    }

    @Test
    fun ansiTerminalBufferHandlesCsiSgrAltScreenAndOscCleanly() {
        val term = AnsiTerminalBuffer(initialCols = 40, initialRows = 6)

        // OSC window title must not leak raw bytes into screen cells
        term.appendString("\u001b]2;Linux RV32\u0007")
        term.appendString("\u001b[1;32mroot@rv32\u001b[0m:~# ls\r\n")
        val snap1 = term.snapshot()
        assertEquals("Linux RV32", snap1.windowTitle)
        assertTrue(snap1.plainText().contains("root@rv32:~# ls"))
        assertFalse(snap1.plainText().contains("Linux RV32"))

        // Verify bold & muted green SGR cell attributes
        val firstCell = snap1.screenLines[0][0]
        assertEquals('r', firstCell.ch)
        assertTrue((firstCell.styleFlags and AnsiTerminalBuffer.STYLE_BOLD) != 0)
        assertEquals(AnsiTerminalBuffer.MUTED_ANSI_16[2], firstCell.fgArgb)

        // Verify 256-color and truecolor SGR
        term.appendString("\u001b[38;2;120;140;160mT\u001b[0m")
        val snapTc = term.snapshot()
        assertEquals(0xFF788CA0.toInt(), snapTc.screenLines[1][0].fgArgb)

        // Verify ?1049 alternate screen preserves primary buffer on exit
        term.appendString("\u001b[?1049hALT_CONTENT")
        assertTrue(term.snapshot().isAlternateScreen)
        assertTrue(term.snapshot().plainText().contains("ALT_CONTENT"))
        term.appendString("\u001b[?1049l")
        assertFalse(term.snapshot().isAlternateScreen)
        assertTrue(term.snapshot().plainText().contains("root@rv32:~# ls"))
        assertFalse(term.snapshot().plainText().contains("ALT_CONTENT"))

        // Verify CSI 2J clear screen
        term.appendString("\u001b[2J\u001b[HFRESH")
        assertEquals("FRESH", term.snapshot().plainText().trim())
    }

    @Test
    fun terminalInstantiationOrderHazardSafe() {
        // Must instantiate without NullPointerException or uninitialized activeTheme
        val term = AnsiTerminalBuffer(initialCols = 80, initialRows = 24)
        assertNotNull(term.activeTheme)
        assertEquals(AnsiTerminalBuffer.ColorTheme.DEFAULT, term.activeTheme)
        val snap = term.snapshot()
        assertEquals(24, snap.screenLines.size)
        assertEquals(80, snap.screenLines[0].size)
        assertEquals(term.activeTheme.defaultFg, snap.screenLines[0][0].fgArgb)
        assertEquals(term.activeTheme.defaultBg, snap.screenLines[0][0].bgArgb)
    }

    @Test
    fun terminalThemesPaletteAndLiveRecoloring() {
        val term = AnsiTerminalBuffer(initialCols = 40, initialRows = 6)
        assertEquals(AnsiTerminalBuffer.ColorTheme.DEFAULT, term.activeTheme)
        assertEquals(AnsiTerminalBuffer.ColorTheme.DEFAULT, term.snapshot().theme)

        // Write plain and styled text
        term.appendString("Hello \u001b[32mGreen\u001b[0m")
        val snapDefault = term.snapshot()
        assertEquals(AnsiTerminalBuffer.DEFAULT_FG, snapDefault.screenLines[0][0].fgArgb)
        assertEquals(AnsiTerminalBuffer.MUTED_ANSI_16[2], snapDefault.screenLines[0][6].fgArgb)

        // 1. Switch to CRT Phosphor Green: verifies live buffer remapping
        term.setTheme(AnsiTerminalBuffer.ColorTheme.CRT_GREEN)
        val snapCrt = term.snapshot()
        assertEquals(AnsiTerminalBuffer.ColorTheme.CRT_GREEN, snapCrt.theme)
        assertEquals(AnsiTerminalBuffer.ColorTheme.CRT_GREEN.defaultFg, snapCrt.screenLines[0][0].fgArgb)
        assertEquals(AnsiTerminalBuffer.ColorTheme.CRT_GREEN.ansi16[2], snapCrt.screenLines[0][6].fgArgb)

        // 2. Switch to Retro CRT Amber
        term.setTheme(AnsiTerminalBuffer.ColorTheme.AMBER)
        val snapAmber = term.snapshot()
        assertEquals(AnsiTerminalBuffer.ColorTheme.AMBER, snapAmber.theme)
        assertEquals(AnsiTerminalBuffer.ColorTheme.AMBER.defaultFg, snapAmber.screenLines[0][0].fgArgb)
        assertEquals(AnsiTerminalBuffer.ColorTheme.AMBER.ansi16[2], snapAmber.screenLines[0][6].fgArgb)

        // 3. Switch to Solarized Dark
        term.setTheme(AnsiTerminalBuffer.ColorTheme.SOLARIZED)
        val snapSolarized = term.snapshot()
        assertEquals(AnsiTerminalBuffer.ColorTheme.SOLARIZED, snapSolarized.theme)
        assertEquals(AnsiTerminalBuffer.ColorTheme.SOLARIZED.defaultFg, snapSolarized.screenLines[0][0].fgArgb)
        assertEquals(AnsiTerminalBuffer.ColorTheme.SOLARIZED.ansi16[2], snapSolarized.screenLines[0][6].fgArgb)

        // 4. Switch to Monokai
        term.setTheme(AnsiTerminalBuffer.ColorTheme.MONOKAI)
        val snapMonokai = term.snapshot()
        assertEquals(AnsiTerminalBuffer.ColorTheme.MONOKAI, snapMonokai.theme)
        assertEquals(AnsiTerminalBuffer.ColorTheme.MONOKAI.defaultFg, snapMonokai.screenLines[0][0].fgArgb)
        assertEquals(AnsiTerminalBuffer.ColorTheme.MONOKAI.ansi16[2], snapMonokai.screenLines[0][6].fgArgb)
    }

    @Test
    fun terminalVirtualKeyToolbarMappings() {
        // Arrow keys normal mode: ESC [ A/B/C/D
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()), arrowSeq('A', applicationMode = false))
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte()), arrowSeq('B', applicationMode = false))
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte()), arrowSeq('C', applicationMode = false))
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte()), arrowSeq('D', applicationMode = false))

        // Arrow keys application mode: ESC O A/B/C/D
        assertArrayEquals(byteArrayOf(0x1B, 'O'.code.toByte(), 'A'.code.toByte()), arrowSeq('A', applicationMode = true))
        assertArrayEquals(byteArrayOf(0x1B, 'O'.code.toByte(), 'B'.code.toByte()), arrowSeq('B', applicationMode = true))

        // Ctrl key helper
        assertEquals(0x03.toByte(), mapCtrlKey('c'))
        assertEquals(0x03.toByte(), mapCtrlKey('C'))
        assertEquals(0x04.toByte(), mapCtrlKey('d'))
        assertEquals(0x04.toByte(), mapCtrlKey('D'))
        assertEquals(0x0C.toByte(), mapCtrlKey('l'))
        assertEquals(null, mapCtrlKey('1'))

        // Alt key helper (ESC prefix)
        assertArrayEquals(byteArrayOf(0x1B, 'x'.code.toByte()), mapAltKey('x'))
        assertArrayEquals(byteArrayOf(0x1B, 'f'.code.toByte()), mapAltKey('f'))
    }

    @Test
    fun headlessBootOfDiagnosticMonitorRomVerifiesUartInteractiveCommandsAndSyscon() {
        val ramSize = 16 * 1024 * 1024
        val dtb = DeviceTreeBuilder.build(ramSize.toLong())
        val alignedDtb = (dtb.size + 15) and 15.inv()
        val dtbOffset = ramSize - alignedDtb
        val dtbPhys = Rv32ImaCpuCore.RAM_BASE + dtbOffset

        val txOut = StringBuilder()
        var cpuRef: Rv32ImaCpuCore? = null
        val uart = Uart8250(
            onTxByte = { txOut.append(it.toChar()) },
            onRxAvailable = { cpuRef?.wakeFromWfi() }
        )
        var fakeUs = 42_000L
        val cpu = Rv32ImaCpuCore(IntArray(ramSize ushr 2), uart, clockSourceUs = { fakeUs })
        cpuRef = cpu

        cpu.loadBytesIntoRam(DiagnosticMonitorRom.binary, 0)
        cpu.loadBytesIntoRam(dtb, dtbOffset)
        cpu.reset(entryPc = Rv32ImaCpuCore.RAM_BASE, hartId = 0, dtbAddress = dtbPhys)

        // Boot until ROM prints banner + prompt and parks at WFI
        val bootStatus = cpu.stepBatch(200_000)
        assertEquals(Rv32ImaCpuCore.BATCH_WFI, bootStatus)
        val banner = txOut.toString()
        assertTrue("Expected diagnostic banner", banner.contains("KernoVM RV32IMA Diagnostic Monitor ROM"))
        assertTrue("Expected misa 40001101", banner.contains("40001101"))
        assertTrue("Expected interactive prompt", banner.contains("kernovm#"))

        // Send 'help\r'
        uart.pushRxBytes("help\r".toByteArray())
        assertEquals(Rv32ImaCpuCore.BATCH_WFI, cpu.stepBatch(200_000))
        assertTrue(txOut.toString().contains("Perintah RV32IMA ROM"))

        // Send 'time\r'
        fakeUs = 0x123456L
        uart.pushRxBytes("time\r".toByteArray())
        assertEquals(Rv32ImaCpuCore.BATCH_WFI, cpu.stepBatch(200_000))
        assertTrue(txOut.toString().contains("00123456"))

        // Send 'poweroff\r' -> triggers SYSCON 0x5555
        uart.pushRxBytes("poweroff\r".toByteArray())
        val poweroffStatus = cpu.stepBatch(200_000)
        assertEquals(Rv32ImaCpuCore.BATCH_POWEROFF, poweroffStatus)
        assertTrue(txOut.toString().contains("SYSCON poweroff (0x5555)"))
    }

    @Test
    @Category(SlowTest::class)
    fun headlessLinuxBuildrootBootIfFixturePresent() {
        val fixture = File("test-fixtures/linux-rv32nommu-Image")
        Assume.assumeTrue("Skipping slow Linux boot test (fixture not present)", fixture.exists())

        val ramSize = 64 * 1024 * 1024
        val dtb = DeviceTreeBuilder.build(ramSize.toLong(), DeviceTreeBuilder.DEFAULT_BOOTARGS)
        val alignedDtb = (dtb.size + 15) and 15.inv()
        val dtbOffset = ramSize - alignedDtb
        val dtbPhys = Rv32ImaCpuCore.RAM_BASE + dtbOffset

        val txOut = StringBuilder()
        val uart = Uart8250(onTxByte = { txOut.append(it.toChar()) })
        var virtualUs = 0L
        val cpu = Rv32ImaCpuCore(IntArray(ramSize ushr 2), uart, clockSourceUs = { virtualUs })
        cpu.loadBytesIntoRam(fixture.readBytes(), 0)
        cpu.loadBytesIntoRam(dtb, dtbOffset)
        cpu.reset(entryPc = Rv32ImaCpuCore.RAM_BASE, hartId = 0, dtbAddress = dtbPhys)

        // Run up to 300M instructions simulating ~1MHz timer ticks
        var total = 0L
        while (total < 300_000_000L) {
            val res = cpu.stepBatch(65_536)
            total += 65_536L
            virtualUs += 5_000L
            if (res == Rv32ImaCpuCore.BATCH_WFI) {
                cpu.syncInterrupts(virtualUs)
            }
            val current = txOut.toString()
            if (current.contains("/ #") || current.contains("login:")) break
        }

        val output = txOut.toString()
        assertTrue("Expected Linux kernel banner on earlycon/ttyS0", output.contains("Linux version"))
    }

    @Test
    fun rawTerminalImeViewEnforcesPositiveDimensionsOnMeasure() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val imeView = com.example.ui.components.RawTerminalImeView(context)
        imeView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        assertTrue(imeView.measuredWidth >= 48)
        assertTrue(imeView.measuredHeight >= 48)
        assertTrue(imeView.onCheckIsTextEditor())
    }

    @Test
    fun terminalResizeStabilityUnderSimulatedKeyboardEvent() {
        val term = AnsiTerminalBuffer(initialCols = 80, initialRows = 30)
        assertEquals(80, term.cols)
        assertEquals(30, term.rows)

        // Writing content
        term.appendString("user@kernovm:~$ ls -la\r\n")
        val snapBefore = term.snapshot()
        assertEquals(30, snapBefore.rows)

        // While keyboard is open, UI preserves stable rows (30) rather than shrinking to 12
        term.resize(80, 30)
        val snapStable = term.snapshot()
        assertEquals(30, snapStable.rows)
        assertTrue(snapStable.plainText().contains("user@kernovm:~$ ls -la"))
    }

    @Test
    fun cpuWakesFromWfiWhenUartReceivesByte() {
        val ramSize = 64 * 1024
        val txOut = StringBuilder()
        var cpuRef: Rv32ImaCpuCore? = null
        val uart = Uart8250(
            onTxByte = { txOut.append(it.toChar()) },
            onRxAvailable = { cpuRef?.wakeFromWfi() }
        )
        val cpu = Rv32ImaCpuCore(IntArray(ramSize ushr 2), uart)
        cpuRef = cpu

        // Test RISC-V program:
        // 0x00: WFI (0x10500073)
        // 0x04: ADDI x1, x0, 42 (0x02a08093)
        // 0x08: J . (0x0000006F)
        val bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x10500073) // wfi
        bb.putInt(0x02a08093) // addi x1, x0, 42
        bb.putInt(0x0000006F) // jal x0, 0
        cpu.loadBytesIntoRam(bb.array(), 0)
        cpu.reset(entryPc = Rv32ImaCpuCore.RAM_BASE)

        // 1. Initial execution steps into WFI and parks
        val status1 = cpu.stepBatch(1)
        assertEquals(Rv32ImaCpuCore.BATCH_WFI, status1)
        assertTrue("CPU should be parked in WFI", cpu.isWfiWaiting)
        assertEquals("x1 must not be executed yet", 0, cpu.regs[1])

        // 2. Stepping again without RX data or interrupts must remain parked in WFI
        val status2 = cpu.stepBatch(1)
        assertEquals(Rv32ImaCpuCore.BATCH_WFI, status2)
        assertTrue("CPU should remain parked in WFI", cpu.isWfiWaiting)
        assertEquals(0, cpu.regs[1])

        // 3. Push a byte into UART RX queue
        uart.pushRxByte('Z'.code)
        assertTrue("UART must have RX data pending", uart.hasRxData())

        // 4. Stepping now must wake from WFI and execute addi x1, x0, 42
        val status3 = cpu.stepBatch(1)
        assertFalse("CPU should have woken from WFI", cpu.isWfiWaiting)
        assertEquals("x1 should now contain 42", 42, cpu.regs[1])

        // Verify Uart8250 IIR register reports RX data available (0x04) when IER RX int is enabled
        uart.writeRegister(1, 0x01) // enable RX interrupt in IER
        val iir = uart.readRegister(2)
        assertEquals("IIR must report RX data available (0x04)", 0x04, iir and 0x0F)
    }

    @Test
    fun virtioHandlesBoundaryBufferLengthsSafely() {
        val tempImg = File.createTempFile("virtio_boundary_test", ".img")
        try {
            tempImg.writeBytes(ByteArray(2048) { 0xAA.toByte() })
            val blockDev = VirtioBlockDevice(tempImg, readOnly = false)

            val ramSize = 64 * 1024
            val cpu = Rv32ImaCpuCore(IntArray(ramSize ushr 2), Uart8250(), virtioBlk = blockDev)

            // Layout descriptor table and virtqueues in RAM at physical addresses:
            val descAddr = Rv32ImaCpuCore.RAM_BASE + 0x1000   // 0x80001000
            val availAddr = Rv32ImaCpuCore.RAM_BASE + 0x2000  // 0x80002000
            val usedAddr = Rv32ImaCpuCore.RAM_BASE + 0x3000   // 0x80003000

            val reqHdrAddr = Rv32ImaCpuCore.RAM_BASE + 0x800
            val dataBufAddr = Rv32ImaCpuCore.RAM_BASE + 0x4000
            val statusByteAddr = Rv32ImaCpuCore.RAM_BASE + 0x5000

            // Setup request header: type = VIRTIO_BLK_T_IN (0), sector = 0
            val hdrBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            hdrBuf.putInt(0) // type: READ
            hdrBuf.putInt(0) // reserved
            hdrBuf.putLong(0L) // sector: 0
            cpu.loadBytesIntoRam(hdrBuf.array(), 0x800)

            // Setup Descriptor Table:
            // Desc 0: Header (len 16, NEXT -> desc 1)
            // Desc 1: Corrupted huge buffer length (128 KB > MAX_BUFFER_BYTES 65536)
            // Desc 2: Status (len 1, flags = 0)
            val descBuf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
            descBuf.putLong(reqHdrAddr.toLong())
            descBuf.putInt(16)
            descBuf.putShort(1.toShort()) // VIRTQ_DESC_F_NEXT
            descBuf.putShort(1.toShort()) // next: 1
            descBuf.putLong(dataBufAddr.toLong())
            descBuf.putInt(128 * 1024) // 131072 > MAX_BUFFER_BYTES
            descBuf.putShort(1.toShort()) // VIRTQ_DESC_F_NEXT
            descBuf.putShort(2.toShort()) // next: 2
            descBuf.putLong(statusByteAddr.toLong())
            descBuf.putInt(1)
            descBuf.putShort(0.toShort())
            descBuf.putShort(0.toShort())
            cpu.loadBytesIntoRam(descBuf.array(), 0x1000)

            // Setup Avail Ring (availIdx = 1, pointing to desc 0)
            val availBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            availBuf.putShort(0.toShort())
            availBuf.putShort(1.toShort()) // idx = 1
            availBuf.putShort(0.toShort()) // ring[0] = desc 0
            cpu.loadBytesIntoRam(availBuf.array(), 0x2000)

            // Configure VirtioBlockDevice MMIO registers
            blockDev.writeRegister(0x030, 0, cpu) // queueSel = 0
            blockDev.writeRegister(0x038, 16, cpu) // queueNum = 16
            blockDev.writeRegister(0x080, descAddr, cpu) // queueDescLow
            blockDev.writeRegister(0x090, availAddr, cpu) // queueAvailLow
            blockDev.writeRegister(0x0A0, usedAddr, cpu) // queueUsedLow
            blockDev.writeRegister(0x044, 1, cpu) // queueReady = 1

            // Trigger queue notification: handles excessive buffer length safely without OOM
            blockDev.writeRegister(0x050, 0, cpu)

            // Verify status byte reported IO error due to invalid length
            val statusResult = cpu.readPhysicalByte(statusByteAddr)
            assertEquals("Expected VIRTIO_BLK_S_IOERR on excessive length", VirtioBlockDevice.VIRTIO_BLK_S_IOERR, statusResult)

            // Now test negative buffer length (-1 / 0xFFFFFFFF)
            val descBufNeg = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
            descBufNeg.putLong(reqHdrAddr.toLong())
            descBufNeg.putInt(16)
            descBufNeg.putShort(1.toShort())
            descBufNeg.putShort(1.toShort())
            descBufNeg.putLong(dataBufAddr.toLong())
            descBufNeg.putInt(-1) // negative length
            descBufNeg.putShort(1.toShort())
            descBufNeg.putShort(2.toShort())
            descBufNeg.putLong(statusByteAddr.toLong())
            descBufNeg.putInt(1)
            descBufNeg.putShort(0.toShort())
            descBufNeg.putShort(0.toShort())
            cpu.loadBytesIntoRam(descBufNeg.array(), 0x1000)

            // Advance avail ring: idx = 2
            val availBuf2 = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            availBuf2.putShort(0.toShort())
            availBuf2.putShort(2.toShort()) // idx = 2
            availBuf2.putShort(0.toShort())
            availBuf2.putShort(0.toShort())
            cpu.loadBytesIntoRam(availBuf2.array(), 0x2000)

            // Trigger queue notification: handles negative length safely without NegativeArraySizeException
            blockDev.writeRegister(0x050, 0, cpu)
            val negStatusResult = cpu.readPhysicalByte(statusByteAddr)
            assertEquals("Expected VIRTIO_BLK_S_IOERR on negative buffer length", VirtioBlockDevice.VIRTIO_BLK_S_IOERR, negStatusResult)

            // Also verify VirtioNetDevice handles excessive/negative buffer lengths in TX queue safely
            val netDev = VirtioNetDevice()
            netDev.writeRegister(0x030, 1, cpu) // TX queue (queueSel = 1)
            netDev.writeRegister(0x038, 16, cpu)
            netDev.writeRegister(0x080, descAddr, cpu)
            netDev.writeRegister(0x090, availAddr, cpu)
            netDev.writeRegister(0x0A0, usedAddr, cpu)
            netDev.writeRegister(0x044, 1, cpu)

            // Descriptor with negative length for TX queue
            val netDescBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            netDescBuf.putLong(dataBufAddr.toLong())
            netDescBuf.putInt(-1) // invalid length
            netDescBuf.putShort(0.toShort())
            netDescBuf.putShort(0.toShort())
            cpu.loadBytesIntoRam(netDescBuf.array(), 0x1000)

            val netAvail = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            netAvail.putShort(0.toShort())
            netAvail.putShort(1.toShort())
            netAvail.putShort(0.toShort())
            cpu.loadBytesIntoRam(netAvail.array(), 0x2000)

            // Must process queue without throwing NegativeArraySizeException or crashing
            netDev.writeRegister(0x050, 1, cpu)

            blockDev.close()
        } finally {
            tempImg.delete()
        }
    }
}
