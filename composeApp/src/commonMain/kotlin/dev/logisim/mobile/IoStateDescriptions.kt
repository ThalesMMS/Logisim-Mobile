package dev.logisim.mobile

import dev.logisim.mobile.core.*
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

/** Read-only descriptions use the same captured simulation as the drawing and signal labels. */
fun accessibleComponentValue(component: AccessibleComponent, simulation: SimulationState): LocalizedText =
    accessibleIoStateDescription(component.component, simulation.componentStates[component.component.id], simulation.ticks)
        ?: accessibleSignal(component.signal(simulation))

/** Returns null for other libraries. Missing I/O state is distinct from an evaluated off state. */
fun accessibleIoStateDescription(component: CircuitComponent, state: ComponentState?, ticks: Long): LocalizedText? {
    val definition = IoLibrary.definition(component.kind) ?: return null
    val attributes = definition.schema.parse(component.attributes)
    fun unavailable() = message("io_state_unavailable")
    fun onOff(on: Boolean) = message(if (on) "io_state_on" else "io_state_off")
    return when (component.kind) {
        IoKinds.Button -> (state as? IoButtonState)?.let { message(if (it.pressed) "io_button_pressed" else "io_button_released") } ?: unavailable()
        IoKinds.DipSwitch -> (state as? IoDipState)?.let {
            val count = attributes[IoAttributes.Switches]
            val on = (0 until count).filter { index -> it.positions and (1uL shl index) != 0uL }.map { index -> index + 1 }
            message("io_state_dip", "count" to count, "on" to numberedItems(on))
        } ?: unavailable()
        IoKinds.Joystick -> (state as? IoJoystickState)?.let {
            val bits = attributes[IoAttributes.JoystickBits]
            val (x, y) = IoLogic.joystickOutput(it, bits)
            message("io_state_joystick", "x" to it.x, "y" to it.y,
                "outputX" to x.toULongOrNull(), "outputY" to y.toULongOrNull(), "bits" to bits)
        } ?: unavailable()
        IoKinds.Keyboard -> (state as? IoKeyboardState)?.let {
            message("io_state_keyboard", "count" to it.buffer.length, "capacity" to attributes[IoAttributes.BufferLength],
                "cursor" to it.cursor + 1, "text" to readableIoText(it.buffer))
        } ?: unavailable()
        IoKinds.Led -> (state as? IoLedState)?.let {
            message("io_state_led", "state" to onOff(IoLogic.ledOn(it.value, attributes[IoAttributes.Active])),
                "signal" to accessibleSignal(LogicVector.fromBit(it.value)))
        } ?: unavailable()
        IoKinds.RgbLed -> (state as? IoLampState)?.let {
            val color = when (IoLogic.rgbColor(it.bits, attributes[IoAttributes.Active])) {
                0xffff0000u -> "red"; 0xff00ff00u -> "green"; 0xffffff00u -> "yellow"
                0xff0000ffu -> "blue"; 0xffff00ffu -> "magenta"; 0xff00ffffu -> "cyan"
                0xffffffffu -> "white"; else -> "black"
            }
            message("io_state_rgb", "color" to message("io_state_$color"))
        } ?: unavailable()
        IoKinds.HexDigit, IoKinds.SevenSegment -> (state as? IoLampState)?.let {
            val active = component.kind == IoKinds.HexDigit || attributes[IoAttributes.Active]
            val segments = (0..6).filter { index -> (it.bits ushr index and 1 == 1) == active }
            val point = if (attributes[IoAttributes.DecimalPoint]) onOff((it.bits ushr 7 and 1 == 1) == active)
                else message("io_state_not_shown")
            val mask = segments.fold(0) { bits, index -> bits or (1 shl index) }
            val digit = (0..15).firstOrNull { number ->
                IoLogic.hexSegments(LogicVector.of(4, number.toULong()), originalLogisim = component.isOriginalHexDigit) == mask
            }
            when {
                digit != null -> message("io_state_digit", "digit" to digit.toString(16).uppercase(), "point" to point)
                segments.isEmpty() -> message("io_state_blank_segments", "point" to point)
                else -> message("io_state_segments", "segments" to LocalizedText.join(segments.map { index ->
                    message("io_state_segment_${SegmentNames[index]}")
                }, ", "), "point" to point)
            }
        } ?: unavailable()
        IoKinds.DotMatrix, IoKinds.LedBar -> matrixDescription(component.kind, attributes, state as? IoMatrixState, ticks)
        IoKinds.Tty -> (state as? IoTerminalState)?.let {
            val text = readableIoText(it.displayRows.joinToString("\n").trimEnd('\n'))
            if (it.cursorColumn == it.columns) message("io_state_tty_full_row", "rows" to it.rows,
                "columns" to it.columns, "row" to it.cursorRow + 1, "text" to text)
            else message("io_state_tty", "rows" to it.rows, "columns" to it.columns, "row" to it.cursorRow + 1,
                "column" to it.cursorColumn + 1, "text" to text)
        } ?: unavailable()
        IoKinds.MatrixKeypad -> (state as? IoKeypadState)?.let {
            val keys = it.pressed.filter { key -> key in 0..15 }.sorted()
            val labels = keypadLabels(component)
            val shown = keys.take(MaximumListedKeys).map { key ->
                message("io_state_key_position", "key" to boundedKeyLabel(labels[key]), "row" to key / 4 + 1, "column" to key % 4 + 1)
            }.toMutableList()
            if (keys.size > shown.size) shown += message("io_state_more", "count" to keys.size - shown.size)
            message("io_state_keypad", "keys" to if (shown.isEmpty()) message("io_state_none") else LocalizedText.join(shown, "; "))
        } ?: unavailable()
        else -> null
    }
}

private val SegmentNames = listOf("top", "upper_right", "lower_right", "bottom", "lower_left", "upper_left", "middle")
private const val MaximumPreviewCharacters = 96
private const val MaximumNamedControls = 8
private const val MaximumListedKeys = 8
private const val MaximumKeyLabelCharacters = 12
private const val MaximumPreviewRows = 3
private const val MaximumPreviewColumns = 8

private fun numberedItems(values: List<Int>): LocalizedText =
    if (values.isEmpty()) message("io_state_none") else LocalizedText.literal(values.joinToString(", "))

private fun matrixDescription(kind: ComponentKind, attributes: AttributeValues, state: IoMatrixState?, ticks: Long): LocalizedText {
    val rows = if (kind == IoKinds.LedBar) 1 else attributes[IoAttributes.MatrixRows]
    val columns = if (kind == IoKinds.LedBar) attributes[IoAttributes.BarColumns] else attributes[IoAttributes.MatrixColumns]
    val counts = mutableMapOf<LogicValue, Int>()
    val preview = mutableListOf<LocalizedText>()
    for (row in 0 until rows) {
        val lit = mutableListOf<Int>()
        for (column in 0 until columns) {
            val value = state?.takeIf { it.rows == rows && it.columns == columns }?.visible(row, column, ticks) ?: LogicValue.UNKNOWN
            counts[value] = counts.getOrElse(value) { 0 } + 1
            if (value == LogicValue.HIGH) lit += column + 1
        }
        if (row < MaximumPreviewRows) {
            val parts = mutableListOf(numberedItems(lit.take(MaximumPreviewColumns)))
            if (lit.size > MaximumPreviewColumns) parts += message("io_state_more", "count" to lit.size - MaximumPreviewColumns)
            preview += message("io_state_matrix_row", "row" to row + 1, "columns" to LocalizedText.join(parts, ", "))
        }
    }
    if (rows > MaximumPreviewRows) preview += LocalizedText("io_state_more_rows", quantity = (rows - MaximumPreviewRows).toLong())
    return message("io_state_matrix", "rows" to rows, "columns" to columns,
        "on" to counts.getOrElse(LogicValue.HIGH) { 0 }, "off" to counts.getOrElse(LogicValue.LOW) { 0 },
        "unknown" to counts.getOrElse(LogicValue.UNKNOWN) { 0 }, "error" to counts.getOrElse(LogicValue.ERROR) { 0 },
        "preview" to LocalizedText.join(preview, " "))
}

/** Bound long project data and name control characters without emitting native control bytes. */
private fun readableIoText(value: String): LocalizedText {
    if (value.isEmpty()) return message("io_state_empty")
    val limit = textPrefixLength(value, MaximumPreviewCharacters)
    val parts = mutableListOf<LocalizedText>()
    val literal = StringBuilder()
    var controls = 0
    var consumed = 0
    while (consumed < limit) {
        val character = value[consumed]
        if (character.code < 32 || character.code in 127..159) {
            if (controls == MaximumNamedControls) break
            if (literal.isNotEmpty()) { parts += LocalizedText.literal(literal.toString()); literal.clear() }
            val control = when (character) {
                '\b' -> message("io_state_backspace"); '\u000c' -> message("io_state_form_feed")
                '\n' -> message("io_state_line_break"); '\r' -> message("io_state_carriage_return"); '\t' -> message("io_state_tab")
                else -> message("io_state_control_character", "code" to character.code)
            }
            parts += message("io_state_control_marker", "control" to control)
            controls++
        } else literal.append(character)
        consumed++
    }
    if (literal.isNotEmpty()) parts += LocalizedText.literal(literal.toString())
    val text = LocalizedText.join(parts, "")
    return if (consumed == value.length) text else LocalizedText("io_state_preview_more",
        localizedArguments = mapOf("text" to text), quantity = value.substring(consumed).codePointCount().toLong())
}

private fun String.codePointCount(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        if (this[index].isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) index++
        count++
        index++
    }
    return count
}

private fun boundedKeyLabel(value: String): LocalizedText {
    val end = textPrefixLength(value, MaximumKeyLabelCharacters)
    val label = value.substring(0, end).map { if (it.code < 32 || it.code in 127..159) ' ' else it }.joinToString("").trim()
    val result = if (label.isEmpty()) message("io_state_empty") else LocalizedText.literal(label)
    return if (end == value.length) result else LocalizedText.join(listOf(result, LocalizedText.literal("…")), "")
}

private fun textPrefixLength(value: String, maximumCharacters: Int): Int {
    val end = minOf(value.length, maximumCharacters)
    return if (end in 1 until value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end - 1 else end
}
