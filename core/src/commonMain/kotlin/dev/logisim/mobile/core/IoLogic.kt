/*
 * Adapted from Logisim-evolution I/O components.
 * Copyright by the Logisim-evolution developers.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package dev.logisim.mobile.core

/** Events describe a user's action; painting a component never creates an event. */
sealed interface IoInputEvent : ComponentEvent {
    data class Press(val pressed: Boolean) : IoInputEvent
    data class ToggleSwitch(val index: Int) : IoInputEvent
    data class MoveJoystick(val x: Int, val y: Int) : IoInputEvent
    data class InsertText(val text: String) : IoInputEvent
    data class ReplaceKeyboardText(val text: String, val cursor: Int) : IoInputEvent
    data class EditKeyboard(val command: KeyboardCommand) : IoInputEvent
    data class KeypadKey(val row: Int, val column: Int, val pressed: Boolean) : IoInputEvent
    data object Cancel : IoInputEvent
}

internal data class IoTerminalText(val text: String) : IoInputEvent

enum class KeyboardCommand { LEFT, RIGHT, HOME, END, DELETE }

data class IoButtonState(val pressed: Boolean = false) : ComponentState
data class IoDipState(val positions: ULong = 0u) : ComponentState
data class IoJoystickState(val x: Int = 0, val y: Int = 0) : ComponentState
data class IoKeypadState(val pressed: Set<Int> = emptySet()) : ComponentState
data class IoLampState(val bits: Int = 0) : ComponentState

data class IoKeyboardState(
    val buffer: String = "",
    val cursor: Int = 0,
    val lastClock: LogicValue = LogicValue.UNKNOWN,
) : ComponentState {
    val displayText: String get() = buffer.map { if (isControl(it)) ' ' else it }.joinToString("")
}

data class IoTerminalState(
    val rows: Int = 8,
    val columns: Int = 32,
    val committed: List<String> = emptyList(),
    val current: String = "",
    val lastClock: LogicValue = LogicValue.UNKNOWN,
) : ComponentState {
    init {
        require(rows in 1..48 && columns in 1..120)
        require(committed.size < rows && current.length <= columns)
    }

    val displayRows: List<String> get() = (committed + current).let { it + List(rows - it.size) { "" } }
    val cursorRow: Int get() = committed.size
    val cursorColumn: Int get() = current.length
}

data class IoMatrixState(
    val rows: Int,
    val columns: Int,
    val values: List<LogicValue> = List(rows * columns) { LogicValue.UNKNOWN },
    val persistUntil: List<Long> = List(rows * columns) { -1 },
) : ComponentState {
    init {
        require(rows in 1..64 && columns in 1..64)
        require(values.size == rows * columns && persistUntil.size == values.size)
    }

    fun visible(row: Int, column: Int, ticks: Long): LogicValue {
        val index = row * columns + column
        return if (values[index] == LogicValue.LOW && persistUntil[index] >= ticks) LogicValue.HIGH
        else values[index]
    }
}

object IoLogic {
    fun buttonOutput(state: IoButtonState, activeOnPress: Boolean): LogicVector =
        LogicVector.fromBit(if (state.pressed == activeOnPress) LogicValue.HIGH else LogicValue.LOW)

    fun dipInitial(count: Int, reset: String, saved: ULong): IoDipState {
        require(count in 1..32)
        return IoDipState(when (reset) {
            "closed" -> dipMask(count)
            "last" -> saved and dipMask(count)
            else -> 0uL
        })
    }

    fun dipToggle(state: IoDipState, count: Int, index: Int): IoDipState =
        if (index !in 0 until count) state
        else IoDipState((state.positions xor (1uL shl index)) and dipMask(count))

    fun resizeDip(state: IoDipState, count: Int): IoDipState {
        require(count in 1..32)
        return IoDipState(state.positions and dipMask(count))
    }

    fun dipOutputs(state: IoDipState, count: Int): List<LogicVector> =
        (0 until count).map { LogicVector.of(1, state.positions shr it) }

    fun joystickOutput(state: IoJoystickState, width: Int): Pair<LogicVector, LogicVector> {
        require(width in 2..5)
        fun coordinate(raw: Int): LogicVector {
            val steps = (1 shl width) - 1
            var value = (raw.coerceIn(-14, 14) + 14) * steps / 29 + 1
            if (width > 4 && value >= steps / 2) value++
            return LogicVector.of(width, value.toULong())
        }
        return coordinate(state.x) to coordinate(state.y)
    }

    fun editKeyboard(state: IoKeyboardState, capacity: Int, events: List<IoInputEvent>): IoKeyboardState {
        require(capacity in 1..256)
        var buffer = state.buffer.take(capacity)
        var cursor = state.cursor.coerceIn(0, buffer.length)
        for (event in events) when (event) {
            is IoInputEvent.InsertText -> for (character in event.text) {
                if ((!isControl(character) || character in "\b\n\u000c") && buffer.length < capacity) {
                    buffer = buffer.substring(0, cursor) + character + buffer.substring(cursor)
                    cursor++
                }
            }
            is IoTerminalText -> {
                val addition = event.text.take(capacity - buffer.length)
                buffer = buffer.substring(0, cursor) + addition + buffer.substring(cursor)
                cursor += addition.length
            }
            is IoInputEvent.ReplaceKeyboardText -> {
                fun allowed(character: Char) = !isControl(character) || character in "\b\n\u000c"
                buffer = event.text.filter(::allowed).take(capacity)
                cursor = event.text.take(event.cursor.coerceIn(0, event.text.length)).count(::allowed).coerceAtMost(buffer.length)
            }
            is IoInputEvent.EditKeyboard -> when (event.command) {
                KeyboardCommand.LEFT -> cursor = (cursor - 1).coerceAtLeast(0)
                KeyboardCommand.RIGHT -> cursor = (cursor + 1).coerceAtMost(buffer.length)
                KeyboardCommand.HOME -> cursor = 0
                KeyboardCommand.END -> cursor = buffer.length
                KeyboardCommand.DELETE -> if (cursor < buffer.length) buffer = buffer.removeRange(cursor, cursor + 1)
            }
            else -> Unit
        }
        return state.copy(buffer = buffer, cursor = cursor)
    }

    fun stepKeyboard(
        state: IoKeyboardState,
        clock: LogicValue,
        enable: LogicValue,
        clear: LogicValue,
        falling: Boolean,
    ): IoKeyboardState {
        var result = state.copy(lastClock = clock)
        if (clear == LogicValue.HIGH) result = result.copy(buffer = "", cursor = 0)
        else if (enable != LogicValue.LOW && edge(state.lastClock, clock, falling) && state.buffer.isNotEmpty()) {
            result = result.copy(buffer = state.buffer.drop(1), cursor = (state.cursor - 1).coerceAtLeast(0))
        }
        return result
    }

    fun keyboardOutputs(state: IoKeyboardState): Pair<LogicVector, LogicVector> {
        val first = state.buffer.firstOrNull() ?: '\u0000'
        return LogicVector.of(7, (first.code and 0x7f).toULong()) to
            LogicVector.of(1, if (first != '\u0000') 1u else 0u)
    }

    fun resizeTerminal(state: IoTerminalState, rows: Int, columns: Int): IoTerminalState =
        state.copy(rows = rows, columns = columns,
            committed = state.committed.takeLast(rows - 1).map { it.take(columns) }, current = state.current.take(columns))

    fun stepTerminal(
        state: IoTerminalState,
        clock: LogicValue,
        enable: LogicValue,
        clear: LogicValue,
        input: LogicVector,
        falling: Boolean,
    ): IoTerminalState = evaluateTerminal(state, clock, enable, clear, input, falling).first

    internal fun evaluateTerminal(
        state: IoTerminalState,
        clock: LogicValue,
        enable: LogicValue,
        clear: LogicValue,
        input: LogicVector,
        falling: Boolean,
    ): Pair<IoTerminalState, Char?> {
        val result = state.copy(lastClock = clock)
        if (clear == LogicValue.HIGH) return result.copy(committed = emptyList(), current = "") to null
        if (enable == LogicValue.LOW || !edge(state.lastClock, clock, falling)) return result to null
        val character = input.toULongOrNull()?.toInt()?.toChar() ?: '?'
        return addTerminal(result, character) to character
    }

    fun addTerminal(state: IoTerminalState, character: Char): IoTerminalState = when (character) {
        '\u000c' -> state.copy(committed = emptyList(), current = "")
        '\b' -> state.copy(current = state.current.dropLast(1))
        '\n', '\r' -> commitTerminal(state)
        else -> if (isControl(character)) state else {
            val room = if (state.current.length == state.columns) commitTerminal(state) else state
            room.copy(current = room.current + character)
        }
    }

    private fun commitTerminal(state: IoTerminalState): IoTerminalState =
        state.copy(committed = (state.committed + state.current).takeLast(state.rows - 1), current = "")

    fun keypadOutputs(state: IoKeypadState, rows: List<LogicValue>, pmod: Boolean): List<LogicVector> {
        require(rows.size == 4)
        return (0 until 4).map { column ->
            var value = if (pmod) LogicValue.HIGH else LogicValue.UNKNOWN
            for (row in 0 until 4) if (row * 4 + column in state.pressed) {
                val drive = rows[row]
                value = if (!pmod) {
                    when {
                        value == LogicValue.UNKNOWN -> drive
                        drive != LogicValue.UNKNOWN && value != drive -> LogicValue.ERROR
                        else -> value
                    }
                } else when {
                    drive == LogicValue.LOW && value == LogicValue.HIGH -> LogicValue.LOW
                    drive == LogicValue.LOW && value != LogicValue.LOW -> LogicValue.ERROR
                    drive == LogicValue.ERROR -> LogicValue.ERROR
                    drive == LogicValue.HIGH && value == LogicValue.LOW -> LogicValue.ERROR
                    else -> value
                }
            }
            LogicVector.fromBit(value)
        }
    }

    fun applyKeypadEvents(state: IoKeypadState, events: List<IoInputEvent>): IoKeypadState {
        var pressed = state.pressed
        for (event in events) when (event) {
            is IoInputEvent.KeypadKey -> if (event.row in 0..3 && event.column in 0..3) {
                val key = event.row * 4 + event.column
                pressed = if (event.pressed) pressed + key else pressed - key
            }
            IoInputEvent.Cancel -> pressed = emptySet()
            else -> Unit
        }
        return IoKeypadState(pressed)
    }

    fun ledOn(input: LogicValue, activeHigh: Boolean): Boolean =
        input == if (activeHigh) LogicValue.HIGH else LogicValue.LOW

    fun lampMask(inputs: List<LogicValue>): Int = inputs.foldIndexed(0) { index, bits, value ->
        if (value == LogicValue.HIGH) bits or (1 shl index) else bits
    }

    fun rgbColor(mask: Int, activeHigh: Boolean): UInt {
        val bits = if (activeHigh) mask else mask xor 7
        return 0xff000000u or (if (bits and 1 != 0) 0xff0000u else 0u) or
            (if (bits and 2 != 0) 0x00ff00u else 0u) or (if (bits and 4 != 0) 0x0000ffu else 0u)
    }

    fun hexSegments(value: LogicVector, decimalPoint: LogicValue = LogicValue.LOW, originalLogisim: Boolean = false): Int {
        val dot = if (decimalPoint == LogicValue.HIGH) 0x80 else 0
        val number = value.toULongOrNull()?.toInt() ?: return dot or (if (originalLogisim) 0x40 else 0)
        val segments = intArrayOf(0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07,
            0x7f, 0x67, 0x77, 0x7c, 0x39, 0x5e, 0x79, 0x71)[number and 15]
        return segments or dot
    }

    fun matrixUpdate(
        previous: IoMatrixState,
        mode: String,
        inputs: List<LogicVector>,
        ticks: Long,
        persistence: Long,
    ): IoMatrixState {
        val values = previous.values.toMutableList()
        val until = previous.persistUntil.toMutableList()
        val expiry = ticks + persistence
        fun set(row: Int, column: Int, value: LogicValue) {
            val index = row * previous.columns + column
            if (values[index] == LogicValue.HIGH) until[index] = expiry - 1
            values[index] = value
            if (value == LogicValue.HIGH) until[index] = expiry
        }
        when (mode) {
            "row" -> for (row in 0 until previous.rows) for (column in 0 until previous.columns) {
                val input = inputs.getOrNull(row)
                set(row, column, input?.takeIf { previous.columns - column <= it.width }
                    ?.bit(previous.columns - column - 1) ?: LogicValue.UNKNOWN)
            }
            "select" -> for (row in 0 until previous.rows) {
                val rowVector = inputs.getOrNull(1)
                val selected = rowVector?.takeIf { previous.rows - row <= it.width }
                    ?.bit(previous.rows - row - 1) ?: LogicValue.UNKNOWN
                for (column in 0 until previous.columns) {
                    val value = if (selected == LogicValue.HIGH) {
                        inputs.firstOrNull()?.takeIf { column < it.width }?.bit(column) ?: LogicValue.UNKNOWN
                    } else if (selected == LogicValue.LOW) LogicValue.LOW else LogicValue.ERROR
                    set(row, column, value)
                }
            }
            else -> for (column in 0 until previous.columns) for (row in 0 until previous.rows) {
                val input = inputs.getOrNull(column)
                set(row, column, input?.takeIf { previous.rows - row <= it.width }
                    ?.bit(previous.rows - row - 1) ?: LogicValue.UNKNOWN)
            }
        }
        return previous.copy(values = values, persistUntil = until)
    }

    private fun edge(previous: LogicValue, current: LogicValue, falling: Boolean): Boolean =
        if (falling) previous == LogicValue.HIGH && current == LogicValue.LOW
        else previous == LogicValue.LOW && current == LogicValue.HIGH

    private fun dipMask(count: Int): ULong = (1uL shl count) - 1uL
}

private fun isControl(character: Char): Boolean = character.code in 0..31 || character.code in 127..159
