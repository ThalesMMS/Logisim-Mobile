package dev.logisim.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.IoInputEvent
import dev.logisim.mobile.core.IoKeyboardState
import dev.logisim.mobile.core.IoLogic

/** A controlled field must echo an edit before the asynchronous engine publishes it. */
internal class KeyboardInputDraft {
    private class Edit(val event: IoInputEvent.ReplaceKeyboardText, val value: TextFieldValue)
    private var latest by mutableStateOf<Edit?>(null)

    fun edit(value: TextFieldValue, capacity: Int): IoInputEvent.ReplaceKeyboardText {
        val event = IoInputEvent.ReplaceKeyboardText(value.text, value.selection.end)
        fun normalized(at: Int) = IoLogic.editKeyboard(IoKeyboardState(), capacity, listOf(event.copy(cursor = at)))
        val text = normalized(value.selection.end).buffer
        fun range(value: TextRange) = TextRange(normalized(value.start).cursor, normalized(value.end).cursor)
        latest = Edit(event, TextFieldValue(text, range(value.selection), value.composition?.let(::range)))
        return event
    }

    fun value(
        keyboard: IoKeyboardState,
        pendingSteps: Int,
        isEventPending: (ComponentEvent) -> Boolean,
    ): TextFieldValue {
        val edit = latest
        return if (pendingSteps > 0 && edit != null && isEventPending(edit.event)) edit.value
            else TextFieldValue(keyboard.buffer, TextRange(keyboard.cursor))
    }
}
