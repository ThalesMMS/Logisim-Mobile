package dev.logisim.mobile

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Issue #314: the remaining Evolution d58a61e0 hotkeys, with their AppPreferences defaults. */
class EvolutionHotkeysTest {
    private fun primary(key: String, shift: Boolean = false) = EditorShortcutBinding(key, shift)
    private fun plain(key: String) = EditorShortcutBinding(key, primary = false)

    @Test
    fun defaultsFollowEvolutionAppPreferences() {
        val expected = mapOf(
            EditorShortcut.FILE_EXPORT to primary("E", shift = true),
            EditorShortcut.FILE_PRINT to primary("P"),
            EditorShortcut.WINDOW_CLOSE to primary("W", shift = true),
            EditorShortcut.PROJ_MOVE_UP to primary("U", shift = true),
            EditorShortcut.PROJ_MOVE_DOWN to primary("D", shift = true),
            EditorShortcut.EDIT_TOOL_DUPLICATE to plain("Insert"),
            EditorShortcut.AUTO_LABEL_TOGGLE to plain("T"),
            EditorShortcut.AUTO_LABEL_VIEW to plain("V"),
            EditorShortcut.AUTO_LABEL_HIDE to plain("H"),
            EditorShortcut.AUTO_LABEL_SELF_NUMBERED_STOP to plain("A"),
            EditorShortcut.ADD_TOOL_ROTATE to plain("R"),
            EditorShortcut.GATE_MODIFIER_SIZE_SMALL to plain("S"),
            EditorShortcut.GATE_MODIFIER_SIZE_MEDIUM to plain("M"),
            EditorShortcut.GATE_MODIFIER_SIZE_WIDE to plain("W"),
            EditorShortcut.GATE_MODIFIER_INPUT_ADD to plain("="),
            EditorShortcut.GATE_MODIFIER_INPUT_SUB to plain("-"),
        )
        expected.forEach { (command, binding) -> assertEquals(binding, command.defaultBinding, command.name) }
        val tools = EditorShortcut.entries.filter { it.toolIndex != null }
        assertEquals((0..13).toList(), tools.map { it.toolIndex })
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), tools.take(10).map { it.defaultBinding.key })
        assertTrue(tools.drop(10).all { it.defaultBinding.isUnassigned })
        assertEquals(EditorShortcut.GATE_MODIFIER_SIZE_SMALL, editorShortcutFor(plain("N"), emptyMap()))
        assertEquals(EditorShortcut.GATE_MODIFIER_INPUT_ADD, editorShortcutFor(plain("+"), emptyMap()))
        assertEquals(EditorShortcut.GATE_MODIFIER_INPUT_ADD, editorShortcutFor(plain("Num+"), emptyMap()))
        assertEquals(EditorShortcut.GATE_MODIFIER_INPUT_SUB, editorShortcutFor(plain("Num-"), emptyMap()))
    }

    @Test
    fun everyDefaultChordResolvesToItsOwnCommand() {
        for (command in EditorShortcut.entries) {
            if (command.defaultBinding.isUnassigned) continue
            assertEquals(command, editorShortcutFor(command.defaultBinding, emptyMap()), command.name)
            assertNull(editorShortcutConflict(command, command.defaultBinding, emptyMap()), command.name)
            command.defaultAliases.forEach { assertEquals(command, editorShortcutFor(it, emptyMap()), "${command.name} $it") }
        }
    }

    @Test
    fun primaryChordsReachProjectWindowAndToolbarCommands() {
        val presses = EditorKeyPresses()
        val commands = mutableListOf<EditorShortcut>()
        val chords = listOf(
            Triple(Key.E, true, EditorShortcut.FILE_EXPORT), Triple(Key.P, false, EditorShortcut.FILE_PRINT),
            Triple(Key.W, true, EditorShortcut.WINDOW_CLOSE), Triple(Key.U, true, EditorShortcut.PROJ_MOVE_UP),
            Triple(Key.D, true, EditorShortcut.PROJ_MOVE_DOWN), Triple(Key.One, false, EditorShortcut.TOOL_SELECT_1),
            Triple(Key.Nine, false, EditorShortcut.TOOL_SELECT_9), Triple(Key.Zero, false, EditorShortcut.TOOL_SELECT_10),
        )
        for ((key, shift, command) in chords) {
            assertTrue(presses.handle(key, KeyEventType.KeyDown, true, shift, false, true, commands::add), command.name)
            assertEquals(command, commands.last())
            presses.handle(key, KeyEventType.KeyUp, true, shift, false, true, commands::add)
        }
        // Ctrl+D stays Duplicate on the canvas, and plain gate keys never reach the window-level handler.
        assertFalse(presses.handle(Key.D, KeyEventType.KeyDown, true, false, false, true, commands::add))
        assertFalse(presses.handle(Key.S, KeyEventType.KeyDown, false, false, false, true, commands::add))
    }

    @Test
    fun unassignedToolsCanBeBoundClearedAndPersisted() {
        assertNull(editorShortcutFor(EditorShortcutBinding.Unassigned, emptyMap()))
        val bound = mapOf(EditorShortcut.TOOL_SELECT_11 to primary("J"))
        assertEquals(EditorShortcut.TOOL_SELECT_11, editorShortcutFor(primary("J"), bound))
        assertEquals(EditorShortcut.TOOL_SELECT_11, editorShortcutConflict(EditorShortcut.OPEN, primary("J"), bound))
        // Two cleared commands never conflict with each other.
        val cleared = bound + (EditorShortcut.TOOL_SELECT_1 to EditorShortcutBinding.Unassigned)
        assertNull(editorShortcutConflict(EditorShortcut.TOOL_SELECT_2, EditorShortcutBinding.Unassigned, cleared))
        assertNull(editorShortcutFor(primary("1"), cleared))

        val withPlain = cleared + (EditorShortcut.EDIT_TOOL_DUPLICATE to EditorShortcutBinding("", primary = false)) +
            (EditorShortcut.GATE_MODIFIER_INPUT_ADD to plain("Num+"))
        val loaded = EditorPreferencesCodec.read(EditorPreferencesCodec.write(EditorPreferences(shortcutOverrides = withPlain)))
        assertTrue(loaded.notices.isEmpty(), loaded.diagnostics.toString())
        assertEquals(withPlain, loaded.preferences.shortcutOverrides)
        assertEquals("—", EditorShortcutBinding.Unassigned.label(EditorShortcutScheme.CONTROL))
    }

    @Test
    fun plainKeysParseCaseInsensitivelyAndStayOffPrimaryChords() {
        assertEquals(plain("Insert"), EditorShortcutBinding.parse("Plain+INSERT"))
        assertEquals(plain("="), EditorShortcutBinding.parse("Plain+="))
        assertEquals(plain("+"), EditorShortcutBinding.parse("Plain++"))
        assertNull(EditorShortcutBinding.parse("Insert"))
        assertNull(EditorShortcutBinding.parse("="))
    }

    @Test
    fun doubleShiftNeedsTwoCleanTapsInsideTheWindow() {
        val detector = DoubleShiftDetector()
        fun tap(at: Long, key: Key = Key.ShiftLeft, modifier: Boolean = false): Boolean {
            detector.observe(key, KeyEventType.KeyDown, modifier, at)
            return detector.observe(key, KeyEventType.KeyUp, modifier, at + 40)
        }
        assertFalse(tap(0))
        assertTrue(tap(200, Key.ShiftRight))
        // Slow taps start a new gesture.
        assertFalse(tap(1_000))
        assertFalse(tap(1_400))
        assertTrue(tap(1_600))
        // Shift used as a modifier is not a tap.
        assertFalse(tap(3_000))
        detector.observe(Key.ShiftLeft, KeyEventType.KeyDown, false, 3_100)
        detector.observe(Key.A, KeyEventType.KeyDown, false, 3_120)
        assertFalse(detector.observe(Key.ShiftLeft, KeyEventType.KeyUp, false, 3_150))
        assertFalse(tap(3_200))
        assertFalse(tap(3_300, modifier = true))
        assertFalse(tap(3_400))
        detector.taint()
        assertFalse(tap(3_500))
        assertTrue(tap(3_700))
    }

    @Test
    fun searchDoubleShiftPreferenceDefaultsOnAndPersists() {
        assertTrue(EditorPreferences().searchDoubleShift)
        val off = EditorPreferencesCodec.read(EditorPreferencesCodec.write(EditorPreferences(searchDoubleShift = false)))
        assertFalse(off.preferences.searchDoubleShift)
        assertTrue(off.notices.isEmpty())
    }

    @Test
    fun gateKeysChangeTheAddToolAndPlacedGates() {
        val state = open()
        state.place(ComponentKind.AND)
        assertTrue(state.handleToolShortcut(EditorShortcut.GATE_MODIFIER_SIZE_SMALL))
        assertEquals("30", state.placingTemplate?.attributes?.get("size"))
        assertTrue(state.handleToolShortcut(EditorShortcut.GATE_MODIFIER_SIZE_WIDE))
        assertTrue(state.handleToolShortcut(EditorShortcut.GATE_MODIFIER_INPUT_ADD))
        assertTrue(state.handleToolShortcut(EditorShortcut.ADD_TOOL_ROTATE))
        state.tap(GridPoint(200, 200))
        val placed = state.document.components.single()
        assertEquals(ComponentKind.AND, placed.kind)
        assertEquals("70", placed.attributes["size"])
        assertEquals("3", placed.attributes["inputs"])
        assertEquals("south", placed.facing)

        state.changeMode(EditorMode.EDIT)
        state.select(placed.id, false)
        assertTrue(state.handleToolShortcut(EditorShortcut.GATE_MODIFIER_SIZE_MEDIUM))
        assertTrue(state.handleToolShortcut(EditorShortcut.GATE_MODIFIER_INPUT_SUB))
        assertTrue(state.handleToolShortcut(EditorShortcut.GATE_MODIFIER_INPUT_SUB))
        val edited = state.document.components.single()
        assertEquals("50", edited.attributes["size"])
        assertEquals("2", edited.attributes["inputs"], "Evolution keeps at least two inputs")
        assertFalse(state.handleToolShortcut(EditorShortcut.ADD_TOOL_ROTATE), "Rotate applies while adding")
        state.undo()
        assertEquals("3", state.document.components.single().attributes["inputs"])
    }

    @Test
    fun keysThatDoNotApplyFallThroughSoPlusAndMinusCanZoom() {
        val state = open()
        state.place(ComponentKind.NOT)
        assertFalse(state.handleToolShortcut(EditorShortcut.GATE_MODIFIER_SIZE_WIDE))
        assertFalse(state.handleToolShortcut(EditorShortcut.GATE_MODIFIER_INPUT_ADD))
        assertNull(state.placingTemplate)
        state.changeMode(EditorMode.EDIT)
        assertFalse(state.handleToolShortcut(EditorShortcut.GATE_MODIFIER_INPUT_SUB))
        state.changeMode(EditorMode.INTERACT)
        assertFalse(state.handleToolShortcut(EditorShortcut.AUTO_LABEL_TOGGLE))
        assertFalse(state.handleToolShortcut(EditorShortcut.TOOL_SELECT_1))
    }

    @Test
    fun labelVisibilityAndAutoLabelStopFollowEvolution() {
        val state = open()
        state.place(ComponentKind.LED)
        assertTrue(state.handleToolShortcut(EditorShortcut.AUTO_LABEL_HIDE))
        assertEquals("false", state.placingTemplate?.attributes?.get("labelvisible"))
        assertTrue(state.handleToolShortcut(EditorShortcut.AUTO_LABEL_TOGGLE))
        assertEquals("true", state.placingTemplate?.attributes?.get("labelvisible"))
        assertTrue(state.handleToolShortcut(EditorShortcut.AUTO_LABEL_HIDE))

        assertFalse(state.handleToolShortcut(EditorShortcut.AUTO_LABEL_SELF_NUMBERED_STOP))
        assertTrue(state.setPlacementAutoLabel("LED1"))
        state.tap(GridPoint(100, 100))
        assertEquals("LED2", state.placementAutoLabel)
        assertTrue(state.handleToolShortcut(EditorShortcut.AUTO_LABEL_SELF_NUMBERED_STOP))
        assertNull(state.placementAutoLabel)
        state.tap(GridPoint(100, 160))
        assertEquals(listOf("LED1", ""), state.document.components.map { it.label })
        assertEquals(listOf("false", "false"), state.document.components.map { it.attributes["labelvisible"] })

        state.changeMode(EditorMode.EDIT)
        state.select(state.document.components.first().id, false)
        assertTrue(state.handleToolShortcut(EditorShortcut.AUTO_LABEL_VIEW))
        assertEquals("true", state.document.components.first().attributes["labelvisible"])
        assertTrue(state.handleToolShortcut(EditorShortcut.AUTO_LABEL_VIEW), "Already visible still claims the key")
    }

    @Test
    fun toolNumbersSkipSeparatorsAndTheTouchOnlyMoveMode() {
        val clicked = mutableListOf<String>()
        fun action(id: String, separator: Boolean = false, enabled: Boolean = true) =
            EditorToolbarAction(id, id, enabled = enabled, separator = separator) { clicked += id }
        val targets = ToolbarShortcutTargets()
        targets.actions = listOf(action("poke"), action("edit"), action("move"), action("sep", separator = true),
            action("pin", enabled = false), action("and"))
        assertTrue(targets.select(0))
        assertTrue(targets.select(1))
        assertFalse(targets.select(2), "Disabled tools stay inert")
        assertTrue(targets.select(3))
        assertFalse(targets.select(13))
        assertEquals(listOf("poke", "edit", "and"), clicked)
    }

    private fun open() = EditorState(CircCodec.writeProject(CircuitProject(listOf(CircuitDocument("main"))))).apply {
        afterPlacement = EditorAfterPlacement.KEEP_TOOL
    }
}
