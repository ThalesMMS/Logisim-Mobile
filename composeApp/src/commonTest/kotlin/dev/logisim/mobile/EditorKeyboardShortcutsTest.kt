package dev.logisim.mobile

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.logisim.mobile.core.Direction
import dev.logisim.mobile.core.localization.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorKeyboardShortcutsTest {
    @Test
    fun aHeldShortcutDispatchesOnlyOnceUntilTheKeyIsReleased() {
        val presses = EditorKeyPresses()
        val commands = mutableListOf<EditorShortcut>()
        val run: (EditorShortcut) -> Boolean = commands::add

        assertTrue(presses.handle(Key.T, KeyEventType.KeyDown, true, false, false, true, run))
        assertTrue(presses.handle(Key.T, KeyEventType.KeyDown, true, false, false, true, run))
        assertEquals(listOf(EditorShortcut.STEP_CLOCK), commands)

        // Releasing Control or Command before the letter still releases the recorded chord.
        assertTrue(presses.handle(Key.T, KeyEventType.KeyUp, false, false, false, true, run))
        assertTrue(presses.handle(Key.T, KeyEventType.KeyDown, true, false, false, true, run))
        assertEquals(listOf(EditorShortcut.STEP_CLOCK, EditorShortcut.STEP_CLOCK), commands)
    }

    @Test
    fun textFieldOrModalFocusLeavesKeystrokesUnclaimedAndEditorFocusCanResume() {
        val presses = EditorKeyPresses()
        val commands = mutableListOf<EditorShortcut>()
        val run: (EditorShortcut) -> Boolean = commands::add

        assertFalse(presses.handle(Key.N, KeyEventType.KeyDown, true, false, false, false, run))
        assertFalse(presses.handle(Key.N, KeyEventType.KeyUp, false, false, false, false, run))
        assertFalse(presses.handle(Key.O, KeyEventType.KeyDown, false, false, false, true, run))
        assertFalse(presses.handle(Key.R, KeyEventType.KeyDown, true, false, true, true, run))
        assertFalse(presses.handle(Key.C, KeyEventType.KeyDown, true, false, false, true, run))
        assertTrue(commands.isEmpty())

        assertTrue(presses.handle(Key.N, KeyEventType.KeyDown, true, false, false, true, run))
        assertEquals(listOf(EditorShortcut.NEW), commands)
    }

    @Test
    fun separateSaveAsAndSimulationShortcutsReachTheCorrespondingActions() {
        val presses = EditorKeyPresses()
        val commands = mutableListOf<EditorShortcut>()
        val run: (EditorShortcut) -> Boolean = commands::add
        val bindings = listOf(
            Key.O to EditorShortcut.OPEN, Key.S to EditorShortcut.SAVE, Key.E to EditorShortcut.RUN,
            Key.I to EditorShortcut.STEP_EVENT, Key.T to EditorShortcut.STEP_CLOCK, Key.F9 to EditorShortcut.STEP_CLOCK_CYCLE,
            Key.K to EditorShortcut.AUTOMATIC_TICKS, Key.R to EditorShortcut.RESET,
        )
        for ((key, command) in bindings) {
            assertTrue(presses.handle(key, KeyEventType.KeyDown, true, false, false, true, run))
            assertEquals(command, commands.last())
            assertTrue(presses.handle(key, KeyEventType.KeyUp, true, false, false, true, run))
        }
        assertTrue(presses.handle(Key.S, KeyEventType.KeyDown, true, true, false, true, run))
        assertEquals(EditorShortcut.SAVE_AS, commands.last())
        assertFalse(presses.handle(Key.I, KeyEventType.KeyDown, true, true, false, true, run))
        assertEquals(bindings.size + 1, commands.size)
    }

    @Test
    fun remappedCommandsReleaseTheirOldChordsAndKeepCommandScope() {
        val overrides = mapOf(
            EditorShortcut.OPEN to EditorShortcutBinding("J", shift = true),
            EditorShortcut.COPY to EditorShortcutBinding("O"),
        )
        val presses = EditorKeyPresses(overrides)
        val commands = mutableListOf<EditorShortcut>()
        val run: (EditorShortcut) -> Boolean = commands::add

        assertFalse(presses.handle(Key.O, KeyEventType.KeyDown, true, false, false, true, run))
        assertFalse(presses.handle(Key.J, KeyEventType.KeyDown, true, false, false, true, run))
        assertTrue(presses.handle(Key.J, KeyEventType.KeyDown, true, true, false, true, run))
        assertEquals(listOf(EditorShortcut.OPEN), commands)
        assertEquals(CanvasCommand.COPY, editorShortcutFor(EditorShortcutBinding("O"), overrides)?.canvasCommand)
        assertEquals(null, editorShortcutFor(EditorShortcutBinding("C"), overrides))
        assertEquals(EditorShortcut.OPEN, editorShortcutFor(EditorShortcutBinding("J", shift = true), overrides, EditorShortcut.SAVE))
    }

    @Test
    fun unhandledCommandsPassThroughAndFocusLossClearsHeldKeys() {
        val presses = EditorKeyPresses()
        val commands = mutableListOf<EditorShortcut>()

        assertFalse(presses.handle(Key.O, KeyEventType.KeyDown, true, false, false, true) { false })
        assertFalse(presses.handle(Key.O, KeyEventType.KeyUp, true, false, false, true, commands::add))
        assertTrue(presses.handle(Key.O, KeyEventType.KeyDown, true, false, false, true, commands::add))
        presses.clear()
        assertTrue(presses.handle(Key.O, KeyEventType.KeyDown, true, false, false, true, commands::add))
        assertEquals(listOf(EditorShortcut.OPEN, EditorShortcut.OPEN), commands)
    }

    @Test
    fun findActionChordIsReservedEvenWhenTheCanvasHasFocus() {
        val presses = EditorKeyPresses()
        val commands = mutableListOf<EditorShortcut>()

        assertEquals(CanvasCommand.SELECT_ALL, editorShortcutFor(EditorShortcutBinding("A"), emptyMap())?.canvasCommand)
        assertEquals(null, editorShortcutFor(EditorShortcutBinding("A", shift = true), emptyMap())?.canvasCommand)
        assertFalse(presses.handle(Key.A, KeyEventType.KeyDown, true, false, false, true, commands::add))
        assertTrue(presses.handle(Key.A, KeyEventType.KeyDown, true, true, false, true, commands::add))
        assertEquals(listOf(EditorShortcut.FIND_ACTION), commands)
    }

    @Test
    fun restoringRedoChecksBothLegacyAliasesItReactivates() {
        val redo = EditorShortcut.REDO
        for (alias in listOf(EditorShortcutBinding("Y"), EditorShortcutBinding("Y", shift = true))) {
            assertEquals(CanvasCommand.REDO, editorShortcutFor(alias, emptyMap())?.canvasCommand)
            assertEquals(redo, editorShortcutConflict(EditorShortcut.OPEN, alias, emptyMap()))

            val remappedRedo = mapOf(redo to EditorShortcutBinding("J"))
            assertEquals(null, editorShortcutFor(alias, remappedRedo))
            assertEquals(null, editorShortcutConflict(redo, redo.defaultBinding, remappedRedo))

            val overrides = remappedRedo + (EditorShortcut.OPEN to alias)
            assertEquals(EditorShortcut.OPEN, editorShortcutConflict(redo, redo.defaultBinding, overrides))
            assertEquals(null, editorShortcutConflict(redo, EditorShortcutBinding("J"), overrides))
        }
    }

    @Test
    fun orientationRemappingKeepsPlainAndPrimaryChordsSeparate() {
        val north = EditorShortcut.FACE_NORTH
        val original = EditorShortcutBinding("↑", primary = false)
        val remapped = EditorShortcutBinding("U", primary = false)
        val overrides = mapOf(north to remapped, EditorShortcut.OPEN to EditorShortcutBinding("U"))

        assertEquals(Direction.NORTH, editorShortcutFor(original, emptyMap())?.facing)
        assertEquals(null, editorShortcutFor(original, overrides))
        assertEquals(Direction.NORTH, editorShortcutFor(remapped, overrides)?.facing)
        assertEquals(EditorShortcut.OPEN, editorShortcutFor(EditorShortcutBinding("U"), overrides))
        assertEquals(EditorShortcut.PLACEMENT_AUTO_LABEL,
            editorShortcutConflict(north, EditorShortcutBinding("L", primary = false), overrides))
        assertEquals(Messages.MatrixPlaceTitle, EditorShortcutBinding("X", primary = false).reservedCanvasAction())
        assertEquals(Messages.ActionFit, EditorShortcutBinding("0", primary = false).reservedCanvasAction())
        assertEquals(null, EditorShortcutBinding("X").reservedCanvasAction())
    }
}
