package dev.logisim.mobile

import androidx.compose.ui.input.key.Key
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.IoInputEvent
import dev.logisim.mobile.core.IoKeyboardState
import dev.logisim.mobile.core.IoKinds
import dev.logisim.mobile.core.IoLogic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyboardPokeTest {
    @Test
    fun onlyAPokedKeyboardReceivesInputAndChangingToolsEndsTheSession() {
        val first = CircuitComponent("first", IoKinds.Keyboard, GridPoint(100, 100))
        val second = CircuitComponent("second", IoKinds.Keyboard, GridPoint(100, 200))
        val circuit = CircuitDocument(components = listOf(first, second))
        val editor = EditorState(CircCodec.writeProject(CircuitProject(listOf(circuit))))

        editor.selectComponent("first")
        assertNull(editor.pokedKeyboardId())
        editor.beginKeyboardPoke("second")
        val target = assertNotNull(editor.pokedKeyboardId())
        assertEquals("second", target)
        assertTrue(editor.sendComponentEvent(target, IoInputEvent.InsertText("A")))
        assertEquals("", assertIs<IoKeyboardState>(editor.simulation.componentStates["first"]).buffer)
        assertEquals("A", assertIs<IoKeyboardState>(editor.simulation.componentStates["second"]).buffer)

        editor.changeMode(EditorMode.SELECT)
        editor.changeMode(EditorMode.INTERACT)
        assertNull(editor.pokedKeyboardId())
        assertEquals(setOf("second"), editor.selectedIds)
    }

    @Test
    fun homeAndDeleteEditTheQueueExposedByDataAndAvailable() {
        var state = IoKeyboardState()
        fun press(key: Key, codePoint: Int = 0) {
            val event = assertNotNull(keyboardPokeEvent(key, codePoint, commandModifier = false))
            state = IoLogic.editKeyboard(state, 16, listOf(event))
        }
        fun expectOutputs(data: ULong, available: ULong) {
            val (actualData, actualAvailable) = IoLogic.keyboardOutputs(state)
            assertEquals(data, actualData.toULongOrNull())
            assertEquals(available, actualAvailable.toULongOrNull())
        }

        expectOutputs(0u, 0u)
        press(Key.A, 'A'.code)
        expectOutputs(65u, 1u)
        press(Key.MoveHome)
        press(Key.Z, 'Z'.code)
        expectOutputs(90u, 1u)

        press(Key.MoveEnd)
        press(Key.DirectionLeft)
        press(Key.Delete)
        assertEquals("Z", state.buffer)
        press(Key.DirectionRight)
        press(Key.Backspace)
        press(Key.Enter)
        assertEquals("Z\b\n", state.buffer)
    }

    @Test
    fun commandModifiedTextRemainsAvailableForEditorShortcuts() {
        assertNull(keyboardPokeEvent(Key.A, 'a'.code, commandModifier = true))
    }
}
