package com.example.emulator

/**
 * Deterministic VT100 / ANSI (ECMA-48) terminal screen state machine implemented from
 * scratch in pure Kotlin (zero JNI dependencies, full JVM unit-testability).
 *
 * Explicitly supported scope:
 * - Controls: \r, \n, \b, \t (8-column stops), BEL (ignored), NUL (ignored)
 * - CSI: CUU (A), CUD (B), CUF (C), CUB (D), CHA (G), CUP (H/f), ED (J: 0/1/2/3),
 *   EL (K: 0/1/2), ICH (@), DCH (P), IL (L), DL (M), DECSTBM (r), SU (S), SD (T),
 *   SGR (m: 0 reset, 1 bold, 2 dim, 4 underline, 7 reverse, 8/16 colors, 256-color
 *   38;5;n / 48;5;n, 24-bit truecolor 38;2;r;g;b / 48;2;r;g;b)
 * - DEC private modes: ?1049 (alternate screen), ?25 (cursor visibility),
 *   ?7 (autowrap), ?1 (application cursor keys)
 * - OSC: OSC 0/2 window title safely parsed up to BEL or ST (ESC \), never leaking raw bytes
 *
 * Intentionally out of scope for v1:
 * - CJK double-width splitting, complex Unicode combining/ZWJ clusters (rendered as 1-cell
 *   fallback without corrupting cursor columns), Sixel/Kitty graphics, mouse reporting.
 */
class AnsiTerminalBuffer(
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val maxScrollback: Int = 1500
) {
    data class Cell(
        var ch: Char = ' ',
        var fgArgb: Int = DEFAULT_FG,
        var bgArgb: Int = DEFAULT_BG,
        var styleFlags: Int = 0
    ) {
        fun reset(fg: Int = DEFAULT_FG, bg: Int = DEFAULT_BG) {
            ch = ' '
            fgArgb = fg
            bgArgb = bg
            styleFlags = 0
        }

        fun copyFrom(other: Cell) {
            ch = other.ch
            fgArgb = other.fgArgb
            bgArgb = other.bgArgb
            styleFlags = other.styleFlags
        }
    }

    data class ImmutableCell(
        val ch: Char,
        val fgArgb: Int,
        val bgArgb: Int,
        val styleFlags: Int
    )

    enum class ColorTheme(
        val themeName: String,
        val defaultFg: Int,
        val defaultBg: Int,
        val cursorColor: Int,
        val ansi16: IntArray
    ) {
        DEFAULT(
            themeName = "Default Slate",
            defaultFg = DEFAULT_FG,
            defaultBg = DEFAULT_BG,
            cursorColor = 0xFF7C9CB0.toInt(),
            ansi16 = MUTED_ANSI_16
        ),
        CRT_GREEN(
            themeName = "CRT Phosphor Green",
            defaultFg = 0xFF2EF02E.toInt(), // P1 phosphor green
            defaultBg = 0xFF0A1008.toInt(), // CRT cathode black-green
            cursorColor = 0xFF33FF33.toInt(),
            ansi16 = intArrayOf(
                0xFF0D180B.toInt(), // 0 black
                0xFF2E852E.toInt(), // 1 red (dim phosphor)
                0xFF33FF33.toInt(), // 2 green (classic P1)
                0xFF66FF66.toInt(), // 3 yellow (light phosphor)
                0xFF22B822.toInt(), // 4 blue (medium phosphor)
                0xFF44DD44.toInt(), // 5 magenta (phosphor)
                0xFF55EE55.toInt(), // 6 cyan (phosphor cyan)
                0xFFB3FFB3.toInt(), // 7 white (bright phosphor)
                0xFF1B401B.toInt(), // 8 bright black (faint green)
                0xFF2E852E.toInt(), // 9 bright red
                0xFF44FF44.toInt(), // 10 bright green
                0xFF77FF77.toInt(), // 11 bright yellow
                0xFF33E033.toInt(), // 12 bright blue
                0xFF55EE55.toInt(), // 13 bright magenta
                0xFF66FF66.toInt(), // 14 bright cyan
                0xFFD4FFD4.toInt()  // 15 bright white (blooming white-green)
            )
        ),
        AMBER(
            themeName = "Retro CRT Amber",
            defaultFg = 0xFFFFA800.toInt(), // P134 phosphor amber
            defaultBg = 0xFF120A00.toInt(), // CRT cathode black-amber
            cursorColor = 0xFFFFB000.toInt(),
            ansi16 = intArrayOf(
                0xFF1C1000.toInt(), // 0 black
                0xFFB86B00.toInt(), // 1 red (dim amber)
                0xFFFFB000.toInt(), // 2 green -> amber
                0xFFFFC840.toInt(), // 3 yellow -> golden amber
                0xFFD48800.toInt(), // 4 blue -> deep amber
                0xFFE69900.toInt(), // 5 magenta -> warm amber
                0xFFFFBE33.toInt(), // 6 cyan -> amber gold
                0xFFFFE0A0.toInt(), // 7 white -> light pale amber
                0xFF593300.toInt(), // 8 bright black -> faint amber
                0xFFB86B00.toInt(), // 9 bright red
                0xFFFFB81A.toInt(), // 10 bright green -> bright amber
                0xFFFFD466.toInt(), // 11 bright yellow -> peak amber
                0xFFE69000.toInt(), // 12 bright blue -> warm amber
                0xFFFFAC1A.toInt(), // 13 bright magenta -> rich amber
                0xFFFFC247.toInt(), // 14 bright cyan -> bright gold
                0xFFFFF0D0.toInt()  // 15 bright white -> blooming white-amber
            )
        ),
        SOLARIZED(
            themeName = "Solarized Dark",
            defaultFg = 0xFF839496.toInt(), // base0
            defaultBg = 0xFF002B36.toInt(), // base03
            cursorColor = 0xFF93A1A1.toInt(), // base1
            ansi16 = intArrayOf(
                0xFF073642.toInt(), // 0 black (base02)
                0xFFDC322F.toInt(), // 1 red
                0xFF859900.toInt(), // 2 green
                0xFFB58900.toInt(), // 3 yellow
                0xFF268BD2.toInt(), // 4 blue
                0xFFD33682.toInt(), // 5 magenta
                0xFF2AA198.toInt(), // 6 cyan
                0xFFEEE8D5.toInt(), // 7 white (base2)
                0xFF002B36.toInt(), // 8 bright black (base03)
                0xFFCB4B16.toInt(), // 9 bright red (orange)
                0xFF586E75.toInt(), // 10 bright green (base01)
                0xFF657B83.toInt(), // 11 bright yellow (base00)
                0xFF839496.toInt(), // 12 bright blue (base0)
                0xFF6C71C4.toInt(), // 13 bright magenta (violet)
                0xFF93A1A1.toInt(), // 14 bright cyan (base1)
                0xFFFDF6E3.toInt()  // 15 bright white (base3)
            )
        ),
        MONOKAI(
            themeName = "Monokai",
            defaultFg = 0xFFF8F8F2.toInt(), // Monokai foreground off-white
            defaultBg = 0xFF272822.toInt(), // Monokai background dark charcoal
            cursorColor = 0xFFF8F8F0.toInt(),
            ansi16 = intArrayOf(
                0xFF272822.toInt(), // 0 black
                0xFFF92672.toInt(), // 1 red (pink)
                0xFFA6E22E.toInt(), // 2 green
                0xFFE6DB74.toInt(), // 3 yellow
                0xFF66D9EF.toInt(), // 4 blue (cyan-blue)
                0xFFAE81FF.toInt(), // 5 magenta (purple)
                0xFFA1EFE4.toInt(), // 6 cyan
                0xFFF8F8F2.toInt(), // 7 white
                0xFF75715E.toInt(), // 8 bright black (comment gray)
                0xFFFD5FF0.toInt(), // 9 bright red (hot pink)
                0xFFB8F03C.toInt(), // 10 bright green
                0xFFF0E68C.toInt(), // 11 bright yellow
                0xFF7FE6FF.toInt(), // 12 bright blue
                0xFFC099FF.toInt(), // 13 bright magenta
                0xFFB4F9F0.toInt(), // 14 bright cyan
                0xFFFFFFFF.toInt()  // 15 bright white
            )
        )
    }

    data class TerminalSnapshot(
        val cols: Int,
        val rows: Int,
        val scrollbackLines: List<List<ImmutableCell>>,
        val screenLines: List<List<ImmutableCell>>,
        val cursorRow: Int,
        val cursorCol: Int,
        val cursorVisible: Boolean,
        val windowTitle: String,
        val applicationCursorKeys: Boolean,
        val isAlternateScreen: Boolean,
        val revision: Long,
        val theme: ColorTheme = ColorTheme.DEFAULT
    ) {
        fun plainText(): String {
            val sb = StringBuilder()
            for (line in scrollbackLines) {
                sb.append(lineToTrimmedString(line)).append('\n')
            }
            for (i in screenLines.indices) {
                sb.append(lineToTrimmedString(screenLines[i]))
                if (i < screenLines.lastIndex) sb.append('\n')
            }
            return sb.toString().trimEnd()
        }

        private fun lineToTrimmedString(cells: List<ImmutableCell>): String {
            var end = cells.size
            while (end > 0 && cells[end - 1].ch == ' ') end--
            if (end == 0) return ""
            val arr = CharArray(end) { cells[it].ch }
            return String(arr)
        }
    }

    private enum class ParseState {
        GROUND, ESC, CSI, OSC, OSC_ESC
    }

    private val lock = Any()

    var cols: Int = initialCols.coerceIn(20, 240)
        private set
    var rows: Int = initialRows.coerceIn(8, 120)
        private set

    var activeTheme: ColorTheme = ColorTheme.DEFAULT
        private set

    // Current SGR attributes
    private var curFg = activeTheme.defaultFg
    private var curBg = activeTheme.defaultBg
    private var curFlags = 0

    private var primaryScreen: Array<Array<Cell>> = createGrid(rows, cols)
    private var altScreen: Array<Array<Cell>> = createGrid(rows, cols)
    private var activeScreen: Array<Array<Cell>> = primaryScreen
    private var usingAltScreen: Boolean = false

    private val scrollback = ArrayDeque<Array<Cell>>()

    private var cursorRow = 0
    private var cursorCol = 0
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var scrollTop = 0
    private var scrollBottom = rows - 1

    private var cursorVisible = true
    private var autoWrap = true
    private var appCursorKeys = false
    private var pendingWrap = false
    private var windowTitle = ""

    // Parser state
    private var state = ParseState.GROUND
    private val csiBuffer = StringBuilder(32)
    private val oscBuffer = StringBuilder(64)
    private var revision: Long = 0L

    // UTF-8 streaming decoder state
    private var utf8Expected = 0
    private var utf8Codepoint = 0

    fun setTheme(newTheme: ColorTheme) = synchronized(lock) {
        val oldTheme = activeTheme
        if (oldTheme == newTheme) return@synchronized
        activeTheme = newTheme
        curFg = newTheme.defaultFg
        curBg = newTheme.defaultBg

        remapGridColors(primaryScreen, oldTheme, newTheme)
        remapGridColors(altScreen, oldTheme, newTheme)
        for (row in scrollback) {
            remapRowColors(row, oldTheme, newTheme)
        }
        revision++
    }

    private fun remapGridColors(grid: Array<Array<Cell>>, old: ColorTheme, next: ColorTheme) {
        for (r in grid.indices) {
            remapRowColors(grid[r], old, next)
        }
    }

    private fun remapRowColors(row: Array<Cell>, old: ColorTheme, next: ColorTheme) {
        for (c in row.indices) {
            val cell = row[c]
            if (cell.fgArgb == old.defaultFg) {
                cell.fgArgb = next.defaultFg
            } else {
                val idx = old.ansi16.indexOf(cell.fgArgb)
                if (idx in 0..15) {
                    cell.fgArgb = next.ansi16[idx]
                }
            }
            if (cell.bgArgb == old.defaultBg) {
                cell.bgArgb = next.defaultBg
            } else {
                val idx = old.ansi16.indexOf(cell.bgArgb)
                if (idx in 0..15) {
                    cell.bgArgb = next.ansi16[idx]
                }
            }
        }
    }

    fun reset() = synchronized(lock) {
        primaryScreen = createGrid(rows, cols)
        altScreen = createGrid(rows, cols)
        activeScreen = primaryScreen
        usingAltScreen = false
        scrollback.clear()
        cursorRow = 0
        cursorCol = 0
        savedCursorRow = 0
        savedCursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        cursorVisible = true
        autoWrap = true
        appCursorKeys = false
        pendingWrap = false
        windowTitle = ""
        curFg = activeTheme.defaultFg
        curBg = activeTheme.defaultBg
        curFlags = 0
        state = ParseState.GROUND
        csiBuffer.setLength(0)
        oscBuffer.setLength(0)
        utf8Expected = 0
        revision++
    }

    fun resize(newCols: Int, newRows: Int) = synchronized(lock) {
        val targetCols = newCols.coerceIn(20, 240)
        val targetRows = newRows.coerceIn(8, 120)
        if (targetCols == cols && targetRows == rows) return@synchronized

        primaryScreen = resizeGrid(primaryScreen, targetRows, targetCols)
        altScreen = resizeGrid(altScreen, targetRows, targetCols)
        activeScreen = if (usingAltScreen) altScreen else primaryScreen
        cols = targetCols
        rows = targetRows
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        pendingWrap = false
        revision++
    }

    fun appendByte(rawByte: Int) = synchronized(lock) {
        val b = rawByte and 0xFF
        if (utf8Expected > 0) {
            if ((b and 0xC0) == 0x80) {
                utf8Codepoint = (utf8Codepoint shl 6) or (b and 0x3F)
                utf8Expected--
                if (utf8Expected == 0) {
                    val ch = if (utf8Codepoint in 0x20..0xFFFF) utf8Codepoint.toChar() else '\uFFFD'
                    processChar(ch)
                }
                return@synchronized
            } else {
                utf8Expected = 0
            }
        }
        when {
            b < 0x80 -> processChar(b.toChar())
            (b and 0xE0) == 0xC0 -> {
                utf8Codepoint = b and 0x1F
                utf8Expected = 1
            }
            (b and 0xF0) == 0xE0 -> {
                utf8Codepoint = b and 0x0F
                utf8Expected = 2
            }
            (b and 0xF8) == 0xF0 -> {
                utf8Codepoint = b and 0x07
                utf8Expected = 3
            }
            else -> processChar('\uFFFD')
        }
    }

    fun appendString(text: String) = synchronized(lock) {
        for (ch in text) {
            processChar(ch)
        }
    }

    fun snapshot(): TerminalSnapshot = synchronized(lock) {
        val sbCopy = if (usingAltScreen) {
            emptyList()
        } else {
            scrollback.map { row -> row.map { ImmutableCell(it.ch, it.fgArgb, it.bgArgb, it.styleFlags) } }
        }
        val screenCopy = activeScreen.map { row ->
            row.map { ImmutableCell(it.ch, it.fgArgb, it.bgArgb, it.styleFlags) }
        }
        TerminalSnapshot(
            cols = cols,
            rows = rows,
            scrollbackLines = sbCopy,
            screenLines = screenCopy,
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cursorVisible = cursorVisible,
            windowTitle = windowTitle,
            applicationCursorKeys = appCursorKeys,
            isAlternateScreen = usingAltScreen,
            revision = revision,
            theme = activeTheme
        )
    }

    private fun processChar(ch: Char) {
        revision++
        when (state) {
            ParseState.GROUND -> handleGround(ch)
            ParseState.ESC -> handleEsc(ch)
            ParseState.CSI -> handleCsi(ch)
            ParseState.OSC -> handleOsc(ch)
            ParseState.OSC_ESC -> {
                if (ch == '\\') {
                    applyOsc()
                }
                oscBuffer.setLength(0)
                state = ParseState.GROUND
            }
        }
    }

    private fun handleGround(ch: Char) {
        when (ch) {
            '\u0000', '\u0007' -> Unit // NUL, BEL ignored
            '\u001B' -> state = ParseState.ESC
            '\r' -> {
                cursorCol = 0
                pendingWrap = false
            }
            '\n', '\u000B', '\u000C' -> {
                lineFeed()
                pendingWrap = false
            }
            '\b' -> {
                if (cursorCol > 0) cursorCol--
                pendingWrap = false
            }
            '\t' -> {
                val nextTab = ((cursorCol / 8) + 1) * 8
                cursorCol = nextTab.coerceAtMost(cols - 1)
                pendingWrap = false
            }
            else -> {
                if (ch.code >= 0x20) {
                    putPrintable(ch)
                }
            }
        }
    }

    private fun handleEsc(ch: Char) {
        when (ch) {
            '[' -> {
                csiBuffer.setLength(0)
                state = ParseState.CSI
            }
            ']' -> {
                oscBuffer.setLength(0)
                state = ParseState.OSC
            }
            '7' -> { // DECSC save cursor
                savedCursorRow = cursorRow
                savedCursorCol = cursorCol
                state = ParseState.GROUND
            }
            '8' -> { // DECRC restore cursor
                cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                cursorCol = savedCursorCol.coerceIn(0, cols - 1)
                pendingWrap = false
                state = ParseState.GROUND
            }
            'M' -> { // Reverse index
                if (cursorRow == scrollTop) {
                    scrollDown(1)
                } else if (cursorRow > 0) {
                    cursorRow--
                }
                pendingWrap = false
                state = ParseState.GROUND
            }
            'c' -> { // Full reset
                reset()
            }
            else -> state = ParseState.GROUND
        }
    }

    private fun handleCsi(ch: Char) {
        if (ch.code in 0x40..0x7E) {
            executeCsi(ch, csiBuffer.toString())
            csiBuffer.setLength(0)
            state = ParseState.GROUND
        } else if (csiBuffer.length < 64) {
            csiBuffer.append(ch)
        } else {
            csiBuffer.setLength(0)
            state = ParseState.GROUND
        }
    }

    private fun handleOsc(ch: Char) {
        when (ch) {
            '\u0007' -> {
                applyOsc()
                oscBuffer.setLength(0)
                state = ParseState.GROUND
            }
            '\u001B' -> state = ParseState.OSC_ESC
            else -> {
                if (oscBuffer.length < 256) oscBuffer.append(ch)
            }
        }
    }

    private fun applyOsc() {
        val raw = oscBuffer.toString()
        val sep = raw.indexOf(';')
        if (sep > 0) {
            val cmd = raw.substring(0, sep)
            if (cmd == "0" || cmd == "2") {
                windowTitle = raw.substring(sep + 1)
            }
        }
    }

    private fun executeCsi(finalChar: Char, rawParams: String) {
        val isPrivate = rawParams.startsWith("?")
        val paramStr = if (isPrivate) rawParams.substring(1) else rawParams
        val params = if (paramStr.isEmpty()) {
            emptyList()
        } else {
            paramStr.split(';').map { it.toIntOrNull() ?: 0 }
        }
        fun p(index: Int, defaultVal: Int): Int {
            val v = params.getOrNull(index) ?: 0
            return if (v == 0) defaultVal else v
        }

        if (isPrivate) {
            when (finalChar) {
                'h', 'l' -> {
                    val enable = finalChar == 'h'
                    for (mode in params) {
                        when (mode) {
                            1 -> appCursorKeys = enable
                            7 -> autoWrap = enable
                            25 -> cursorVisible = enable
                            1049 -> switchAltScreen(enable)
                        }
                    }
                }
            }
            return
        }

        when (finalChar) {
            'A' -> { // CUU
                cursorRow = (cursorRow - p(0, 1)).coerceAtLeast(scrollTop)
                pendingWrap = false
            }
            'B' -> { // CUD
                cursorRow = (cursorRow + p(0, 1)).coerceAtMost(scrollBottom)
                pendingWrap = false
            }
            'C' -> { // CUF
                cursorCol = (cursorCol + p(0, 1)).coerceAtMost(cols - 1)
                pendingWrap = false
            }
            'D' -> { // CUB
                cursorCol = (cursorCol - p(0, 1)).coerceAtLeast(0)
                pendingWrap = false
            }
            'G' -> { // CHA
                cursorCol = (p(0, 1) - 1).coerceIn(0, cols - 1)
                pendingWrap = false
            }
            'H', 'f' -> { // CUP
                cursorRow = (p(0, 1) - 1).coerceIn(0, rows - 1)
                cursorCol = (p(1, 1) - 1).coerceIn(0, cols - 1)
                pendingWrap = false
            }
            'J' -> { // ED
                when (params.getOrNull(0) ?: 0) {
                    0 -> {
                        clearRange(cursorRow, cursorCol, cols)
                        for (r in (cursorRow + 1) until rows) clearRange(r, 0, cols)
                    }
                    1 -> {
                        for (r in 0 until cursorRow) clearRange(r, 0, cols)
                        clearRange(cursorRow, 0, (cursorCol + 1).coerceAtMost(cols))
                    }
                    2 -> {
                        for (r in 0 until rows) clearRange(r, 0, cols)
                    }
                    3 -> {
                        scrollback.clear()
                        for (r in 0 until rows) clearRange(r, 0, cols)
                    }
                }
                pendingWrap = false
            }
            'K' -> { // EL
                when (params.getOrNull(0) ?: 0) {
                    0 -> clearRange(cursorRow, cursorCol, cols)
                    1 -> clearRange(cursorRow, 0, (cursorCol + 1).coerceAtMost(cols))
                    2 -> clearRange(cursorRow, 0, cols)
                }
                pendingWrap = false
            }
            '@' -> { // ICH insert blank characters
                val count = p(0, 1).coerceAtMost(cols - cursorCol)
                val row = activeScreen[cursorRow]
                for (c in (cols - 1) downTo (cursorCol + count)) {
                    row[c].copyFrom(row[c - count])
                }
                clearRange(cursorRow, cursorCol, cursorCol + count)
            }
            'P' -> { // DCH delete characters
                val count = p(0, 1).coerceAtMost(cols - cursorCol)
                val row = activeScreen[cursorRow]
                for (c in cursorCol until (cols - count)) {
                    row[c].copyFrom(row[c + count])
                }
                clearRange(cursorRow, cols - count, cols)
            }
            'L' -> { // IL insert lines
                if (cursorRow in scrollTop..scrollBottom) {
                    val count = p(0, 1).coerceAtMost(scrollBottom - cursorRow + 1)
                    for (r in scrollBottom downTo (cursorRow + count)) {
                        activeScreen[r] = activeScreen[r - count]
                    }
                    for (r in cursorRow until (cursorRow + count)) {
                        activeScreen[r] = Array(cols) { Cell(fgArgb = curFg, bgArgb = curBg) }
                    }
                }
            }
            'M' -> { // DL delete lines
                if (cursorRow in scrollTop..scrollBottom) {
                    val count = p(0, 1).coerceAtMost(scrollBottom - cursorRow + 1)
                    for (r in cursorRow..(scrollBottom - count)) {
                        activeScreen[r] = activeScreen[r + count]
                    }
                    for (r in (scrollBottom - count + 1)..scrollBottom) {
                        activeScreen[r] = Array(cols) { Cell(fgArgb = curFg, bgArgb = curBg) }
                    }
                }
            }
            'r' -> { // DECSTBM scroll region
                val top = (p(0, 1) - 1).coerceIn(0, rows - 1)
                val bottom = (p(1, rows) - 1).coerceIn(0, rows - 1)
                if (top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                    cursorRow = 0
                    cursorCol = 0
                    pendingWrap = false
                }
            }
            'S' -> scrollUp(p(0, 1))
            'T' -> scrollDown(p(0, 1))
            'm' -> applySgr(if (params.isEmpty()) listOf(0) else params)
            's' -> {
                savedCursorRow = cursorRow
                savedCursorCol = cursorCol
            }
            'u' -> {
                cursorRow = savedCursorRow.coerceIn(0, rows - 1)
                cursorCol = savedCursorCol.coerceIn(0, cols - 1)
                pendingWrap = false
            }
        }
    }

    private fun applySgr(params: List<Int>) {
        var i = 0
        while (i < params.size) {
            when (val code = params[i]) {
                0 -> {
                    curFg = activeTheme.defaultFg
                    curBg = activeTheme.defaultBg
                    curFlags = 0
                }
                1 -> curFlags = curFlags or STYLE_BOLD
                2 -> curFlags = curFlags or STYLE_DIM
                4 -> curFlags = curFlags or STYLE_UNDERLINE
                7 -> curFlags = curFlags or STYLE_REVERSE
                22 -> curFlags = curFlags and (STYLE_BOLD or STYLE_DIM).inv()
                24 -> curFlags = curFlags and STYLE_UNDERLINE.inv()
                27 -> curFlags = curFlags and STYLE_REVERSE.inv()
                in 30..37 -> curFg = activeTheme.ansi16[code - 30]
                39 -> curFg = activeTheme.defaultFg
                in 40..47 -> curBg = activeTheme.ansi16[code - 40]
                49 -> curBg = activeTheme.defaultBg
                in 90..97 -> curFg = activeTheme.ansi16[8 + (code - 90)]
                in 100..107 -> curBg = activeTheme.ansi16[8 + (code - 100)]
                38, 48 -> {
                    val isFg = code == 38
                    val mode = params.getOrNull(i + 1)
                    if (mode == 5 && i + 2 < params.size) {
                        val color = xterm256Color(params[i + 2], activeTheme.ansi16)
                        if (isFg) curFg = color else curBg = color
                        i += 2
                    } else if (mode == 2 && i + 4 < params.size) {
                        val r = params[i + 2].coerceIn(0, 255)
                        val g = params[i + 3].coerceIn(0, 255)
                        val b = params[i + 4].coerceIn(0, 255)
                        val color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        if (isFg) curFg = color else curBg = color
                        i += 4
                    }
                }
            }
            i++
        }
    }

    private fun switchAltScreen(enable: Boolean) {
        if (enable == usingAltScreen) return
        if (enable) {
            savedCursorRow = cursorRow
            savedCursorCol = cursorCol
            altScreen = createGrid(rows, cols)
            activeScreen = altScreen
            usingAltScreen = true
            cursorRow = 0
            cursorCol = 0
            scrollTop = 0
            scrollBottom = rows - 1
        } else {
            activeScreen = primaryScreen
            usingAltScreen = false
            cursorRow = savedCursorRow.coerceIn(0, rows - 1)
            cursorCol = savedCursorCol.coerceIn(0, cols - 1)
            scrollTop = 0
            scrollBottom = rows - 1
        }
        pendingWrap = false
    }

    private fun putPrintable(ch: Char) {
        if (pendingWrap) {
            if (autoWrap) {
                cursorCol = 0
                lineFeed()
            }
            pendingWrap = false
        }
        val cell = activeScreen[cursorRow][cursorCol]
        cell.ch = ch
        cell.fgArgb = curFg
        cell.bgArgb = curBg
        cell.styleFlags = curFlags

        if (cursorCol == cols - 1) {
            pendingWrap = autoWrap
        } else {
            cursorCol++
        }
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun scrollUp(lines: Int) {
        val count = lines.coerceAtLeast(1)
        repeat(count) {
            if (!usingAltScreen && scrollTop == 0 && scrollBottom == rows - 1) {
                val topRow = activeScreen[0]
                scrollback.addLast(topRow)
                if (scrollback.size > maxScrollback) {
                    scrollback.removeFirst()
                }
            }
            for (r in scrollTop until scrollBottom) {
                activeScreen[r] = activeScreen[r + 1]
            }
            activeScreen[scrollBottom] = Array(cols) { Cell(fgArgb = activeTheme.defaultFg, bgArgb = activeTheme.defaultBg) }
        }
    }

    private fun scrollDown(lines: Int) {
        val count = lines.coerceAtLeast(1)
        repeat(count) {
            for (r in scrollBottom downTo (scrollTop + 1)) {
                activeScreen[r] = activeScreen[r - 1]
            }
            activeScreen[scrollTop] = Array(cols) { Cell(fgArgb = activeTheme.defaultFg, bgArgb = activeTheme.defaultBg) }
        }
    }

    private fun clearRange(row: Int, startCol: Int, endColExclusive: Int) {
        val r = activeScreen[row]
        for (c in startCol.coerceAtLeast(0) until endColExclusive.coerceAtMost(cols)) {
            r[c].reset(activeTheme.defaultFg, activeTheme.defaultBg)
        }
    }

    private fun createGrid(r: Int, c: Int): Array<Array<Cell>> {
        val theme = runCatching { activeTheme }.getOrNull() ?: ColorTheme.DEFAULT
        val fg = theme.defaultFg
        val bg = theme.defaultBg
        return Array(r) { Array(c) { Cell(fgArgb = fg, bgArgb = bg) } }
    }

    private fun resizeGrid(old: Array<Array<Cell>>, newRows: Int, newCols: Int): Array<Array<Cell>> {
        val next = createGrid(newRows, newCols)
        val copyRows = minOf(old.size, newRows)
        for (r in 0 until copyRows) {
            val copyCols = minOf(old[r].size, newCols)
            for (c in 0 until copyCols) {
                next[r][c].copyFrom(old[r][c])
            }
        }
        return next
    }

    companion object {
        const val DEFAULT_FG = 0xFFD6DCE2.toInt() // soft chalk off-white
        const val DEFAULT_BG = 0xFF111315.toInt() // deep matte charcoal

        const val STYLE_BOLD = 1 shl 0
        const val STYLE_DIM = 1 shl 1
        const val STYLE_UNDERLINE = 1 shl 2
        const val STYLE_REVERSE = 1 shl 3

        // Restrained, desaturated low-chroma ANSI 16 palette (no harsh neon)
        val MUTED_ANSI_16 = intArrayOf(
            0xFF1A1E22.toInt(), // 0 black
            0xFFAE6E6E.toInt(), // 1 red (dusty crimson)
            0xFF6D987A.toInt(), // 2 green (calm sage)
            0xFFB39A6B.toInt(), // 3 yellow (warm ochre)
            0xFF7C9CB0.toInt(), // 4 blue (muted slate)
            0xFF9A83A3.toInt(), // 5 magenta (muted plum)
            0xFF6F9BA0.toInt(), // 6 cyan (muted teal)
            0xFFC4CCD4.toInt(), // 7 white
            0xFF5A636E.toInt(), // 8 bright black
            0xFFC28282.toInt(), // 9 bright red
            0xFF84AE90.toInt(), // 10 bright green
            0xFFC7AF82.toInt(), // 11 bright yellow
            0xFF93B2C5.toInt(), // 12 bright blue
            0xFFB09AB8.toInt(), // 13 bright magenta
            0xFF86B0B5.toInt(), // 14 bright cyan
            0xFFE2E7EC.toInt()  // 15 bright white
        )

        fun xterm256Color(index: Int, ansi16: IntArray = MUTED_ANSI_16): Int {
            val idx = index.coerceIn(0, 255)
            if (idx < 16) return ansi16[idx]
            if (idx >= 232) {
                val gray = 8 + (idx - 232) * 10
                return (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
            }
            val cube = idx - 16
            val r = (cube / 36) * 51
            val g = ((cube / 6) % 6) * 51
            val b = (cube % 6) * 51
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
