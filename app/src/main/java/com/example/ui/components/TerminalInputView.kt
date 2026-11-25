package com.example.ui.components

import android.content.Context
import android.text.InputType
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Custom transparent focusable Android View providing a raw terminal [BaseInputConnection].
 *
 * Avoids Jetpack Compose TextField pitfalls with Gboard swipe-typing, autocorrect word
 * buffering, and suppressed Backspace on empty buffers:
 * - TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS | IME_FLAG_NO_EXTRACT_UI
 * - commitText() emits raw UTF-8 (\n mapped to \r)
 * - setComposingText() commits immediately (no stuck composing underline)
 * - deleteSurroundingText() emits 0x7F (DEL) even when buffer is empty
 * - sendKeyEvent() maps DEL, ENTER, TAB, ESC, and DPAD arrow keys
 */
class RawTerminalImeView(
    context: Context,
    var onTerminalBytes: (ByteArray) -> Unit = {},
    var applicationCursorKeysProvider: () -> Boolean = { false }
) : View(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(48)
        val h = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(48)
        setMeasuredDimension(w, h)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this)
    }

    fun showSoftKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(this)
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    private inner class TerminalInputConnection(targetView: View) :
        BaseInputConnection(targetView, false) {

        private var lastComposing: String = ""

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!text.isNullOrEmpty()) {
                val payload = text.toString()
                if (lastComposing.isNotEmpty()) {
                    // Replace previously emitted composing chars if IME replaces word on commit
                    if (payload.startsWith(lastComposing)) {
                        emitText(payload.substring(lastComposing.length))
                    } else {
                        repeat(lastComposing.length) { onTerminalBytes(byteArrayOf(0x7F)) }
                        emitText(payload)
                    }
                    lastComposing = ""
                } else {
                    emitText(payload)
                }
            }
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val next = text?.toString().orEmpty()
            if (next.startsWith(lastComposing)) {
                val delta = next.substring(lastComposing.length)
                if (delta.isNotEmpty()) emitText(delta)
            } else {
                repeat(lastComposing.length) { onTerminalBytes(byteArrayOf(0x7F)) }
                if (next.isNotEmpty()) emitText(next)
            }
            lastComposing = next
            return true
        }

        override fun finishComposingText(): Boolean {
            lastComposing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            val count = beforeLength.coerceAtLeast(1)
            if (lastComposing.isNotEmpty()) {
                lastComposing = lastComposing.dropLast(count.coerceAtMost(lastComposing.length))
            }
            val del = ByteArray(count) { 0x7F }
            onTerminalBytes(del)
            return true
        }

        override fun sendKeyEvent(event: AndroidKeyEvent): Boolean {
            if (event.action != AndroidKeyEvent.ACTION_DOWN) return true
            val appCursor = applicationCursorKeysProvider()
            val mapped: ByteArray? = when (event.keyCode) {
                AndroidKeyEvent.KEYCODE_DEL -> {
                    if (lastComposing.isNotEmpty()) lastComposing = lastComposing.dropLast(1)
                    if (event.isAltPressed) byteArrayOf(0x1B, 0x7F) else byteArrayOf(0x7F)
                }
                AndroidKeyEvent.KEYCODE_ENTER,
                AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    lastComposing = ""
                    byteArrayOf(0x0D)
                }
                AndroidKeyEvent.KEYCODE_TAB -> byteArrayOf(0x09)
                AndroidKeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)
                AndroidKeyEvent.KEYCODE_DPAD_UP -> arrowSeq('A', appCursor)
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> arrowSeq('B', appCursor)
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> arrowSeq('C', appCursor)
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> arrowSeq('D', appCursor)
                else -> {
                    val unicode = event.unicodeChar
                    when {
                        event.isCtrlPressed && unicode in 'a'.code..'z'.code ->
                            byteArrayOf((unicode - 'a'.code + 1).toByte())
                        event.isCtrlPressed && unicode in 'A'.code..'Z'.code ->
                            byteArrayOf((unicode - 'A'.code + 1).toByte())
                        event.isAltPressed && unicode >= 0x20 ->
                            byteArrayOf(0x1B) + unicode.toChar().toString().toByteArray(Charsets.UTF_8)
                        unicode > 0 -> {
                            val ch = unicode.toChar()
                            if (ch == '\n') byteArrayOf(0x0D) else ch.toString().toByteArray(Charsets.UTF_8)
                        }
                        else -> null
                    }
                }
            }
            if (mapped != null) {
                onTerminalBytes(mapped)
                return true
            }
            return super.sendKeyEvent(event)
        }

        private fun emitText(text: String) {
            val normalized = text.replace("\n", "\r")
            onTerminalBytes(normalized.toByteArray(Charsets.UTF_8))
        }
    }
}

fun arrowSeq(code: Char, applicationMode: Boolean): ByteArray {
    val prefix = if (applicationMode) 'O' else '['
    return byteArrayOf(0x1B, prefix.code.toByte(), code.code.toByte())
}

/**
 * Maps Compose hardware/Bluetooth keyboard [KeyEvent]s (via Modifier.onPreviewKeyEvent)
 * into raw VT100/ANSI terminal bytes including Ctrl+A..Z, Alt, Esc, Tab, Home/End/PgUp/PgDn.
 */
fun mapHardwareKeyEventToTerminalBytes(
    event: KeyEvent,
    applicationCursorKeys: Boolean
): ByteArray? {
    if (event.type != KeyEventType.KeyDown) return null
    val cp = event.utf16CodePoint

    if (event.isCtrlPressed) {
        val lower = cp.toChar().lowercaseChar()
        if (lower in 'a'..'z') {
            return byteArrayOf((lower.code - 'a'.code + 1).toByte())
        }
    }

    val special: ByteArray? = when (event.key) {
        Key.Escape -> byteArrayOf(0x1B)
        Key.Tab -> byteArrayOf(0x09)
        Key.Enter, Key.NumPadEnter -> byteArrayOf(0x0D)
        Key.Backspace -> byteArrayOf(0x7F)
        Key.Delete -> "\u001b[3~".toByteArray(Charsets.US_ASCII)
        Key.DirectionUp -> arrowSeq('A', applicationCursorKeys)
        Key.DirectionDown -> arrowSeq('B', applicationCursorKeys)
        Key.DirectionRight -> arrowSeq('C', applicationCursorKeys)
        Key.DirectionLeft -> arrowSeq('D', applicationCursorKeys)
        Key.MoveHome -> "\u001b[H".toByteArray(Charsets.US_ASCII)
        Key.MoveEnd -> "\u001b[F".toByteArray(Charsets.US_ASCII)
        Key.PageUp -> "\u001b[5~".toByteArray(Charsets.US_ASCII)
        Key.PageDown -> "\u001b[6~".toByteArray(Charsets.US_ASCII)
        else -> null
    }
    if (special != null) return special

    if (cp >= 0x20) {
        val utf8 = cp.toChar().toString().toByteArray(Charsets.UTF_8)
        return if (event.isAltPressed) byteArrayOf(0x1B) + utf8 else utf8
    }
    return null
}

fun mapCtrlKey(ch: Char): Byte? {
    val lower = ch.lowercaseChar()
    return if (lower in 'a'..'z') (lower.code - 'a'.code + 1).toByte() else null
}

fun mapAltKey(ch: Char): ByteArray {
    return byteArrayOf(0x1B) + ch.toString().toByteArray(Charsets.UTF_8)
}

@Composable
fun TerminalInputBridge(
    modifier: Modifier = Modifier,
    applicationCursorKeys: Boolean,
    onTerminalBytes: (ByteArray) -> Unit,
    onViewReady: (RawTerminalImeView) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            RawTerminalImeView(
                context = ctx,
                onTerminalBytes = onTerminalBytes,
                applicationCursorKeysProvider = { applicationCursorKeys }
            ).also(onViewReady)
        },
        update = { view ->
            view.onTerminalBytes = onTerminalBytes
            view.applicationCursorKeysProvider = { applicationCursorKeys }
        }
    )
}
