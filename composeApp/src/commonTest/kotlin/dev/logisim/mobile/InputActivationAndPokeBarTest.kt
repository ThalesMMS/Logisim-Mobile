package dev.logisim.mobile

import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.IoInteractions
import dev.logisim.mobile.core.IoKinds
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.localization.Localizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * X0: every interactive input's one-step action is named for what it does; C40: in the Poke tool a pressed Button,
 * Joystick or Matrix Keypad shows its menu items (Rotate, Delete, Show Attributes) in the selection bar, and nothing
 * else does.
 */
class InputActivationAndPokeBarTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#I/O" name="5"/>
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="a"><a name="label" val="A"/></comp>
    <comp lib="0" loc="(100,200)" name="Pin" logisim-mobile-id="d"><a name="width" val="8"/><a name="label" val="D"/></comp>
    <comp lib="5" loc="(100,300)" name="Button" logisim-mobile-id="b"><a name="label" val="B"/></comp>
    <comp lib="0" loc="(100,400)" name="Clock" logisim-mobile-id="clk"><a name="label" val="CLK"/></comp>
    <comp lib="5" loc="(300,100)" name="DipSwitch" logisim-mobile-id="sw"/>
    <comp lib="5" loc="(300,300)" name="Joystick" logisim-mobile-id="j"/>
    <comp lib="5" loc="(300,500)" name="MatrixKeypad" logisim-mobile-id="k"/>
    <comp lib="5" loc="(500,500)" name="Keyboard" logisim-mobile-id="kb"/>
    <comp lib="1" loc="(500,100)" name="NOT Gate" logisim-mobile-id="g"/>
  </circuit>
</project>
"""

    @Test
    fun eachInputNamesWhatItsActionDoes() {
        val state = EditorState(xml)
        val graph = CircuitAccessibilityGraph(state.document, state.componentRegistry)
        fun activation(id: String) = graph.component(id)!!.activation
        assertEquals(AccessibleActivation.TOGGLE, activation("a"))
        assertEquals(AccessibleActivation.TOGGLE_LOW_BIT, activation("d"))
        assertEquals(AccessibleActivation.PRESS, activation("b"))
        assertEquals(AccessibleActivation.TOGGLE, activation("clk"))
        for (id in listOf("sw", "j", "k", "kb")) assertEquals(AccessibleActivation.OPEN_CONTROLS, activation(id), id)
        assertEquals(null, activation("g"))
        assertEquals(listOf("a", "d", "b", "clk"), listOf("a", "d", "b", "clk", "sw", "j", "k", "kb", "g")
            .filter { graph.component(it)!!.isInteractiveInput })
        val name = LocalizedText.literal("X")
        assertEquals(listOf("Toggle X", "Toggle bit 0 of X", "Press X", "Open controls for X"),
            AccessibleActivation.entries.map { it.label(name).resolve(AppLanguage.ENGLISH) })
        assertEquals(listOf("Alternar X", "Alternar o bit 0 de X", "Pressionar X", "Abrir controles de X"),
            AccessibleActivation.entries.map { it.label(name).resolve(AppLanguage.PORTUGUESE) })
    }

    @Test
    fun toggleOnAClockTicksAndOpenControlsOpensTheInspector() {
        val state = EditorState(xml)
        val opened = mutableListOf<String>()
        val actions = CircuitAccessibilityActions(selectComponent = {}, openProperties = { opened += it },
            removeComponent = { false }, activateComponent = state::activateComponent, selectWire = {},
            removeWire = { false }, connect = { _, _ -> false }, insertComponent = { _, _ -> false })
        val graph = CircuitAccessibilityGraph(state.document, state.componentRegistry)
        val ticks = state.simulation.ticks
        assertTrue(actions.activate(graph.component("clk")!!))
        assertEquals(ticks + 1, state.simulation.ticks)
        assertTrue(actions.activate(graph.component("sw")!!))
        assertEquals(listOf("sw"), opened)
        assertFalse(actions.activate(graph.component("g")!!))
    }

    @Test
    fun theLowBitToggleTogglesBitZeroWhateverTheRadix() {
        // A Poke tap on a hex or octal pin advances its low digit; the action is named "Toggle bit 0", so it toggles that bit.
        for (radix in listOf("16", "8", "2")) {
            val state = EditorState(xml.replace("<a name=\"width\" val=\"8\"/><a name=\"label\" val=\"D\"/>",
                "<a name=\"width\" val=\"8\"/><a name=\"radix\" val=\"$radix\"/><a name=\"label\" val=\"D\"/>"))
            assertEquals(radix, state.document.components.single { it.id == "d" }.attributes["radix"] ?: "2")
            val actions = CircuitAccessibilityActions(selectComponent = {}, openProperties = {},
                removeComponent = { false }, activateComponent = state::activateComponent, selectWire = {},
                removeWire = { false }, connect = { _, _ -> false }, insertComponent = { _, _ -> false },
                toggleInputBit = state::toggleInputBit)
            val pin = CircuitAccessibilityGraph(state.document, state.componentRegistry).component("d")!!
            assertEquals(AccessibleActivation.TOGGLE_LOW_BIT, pin.activation)
            val values = (1..2).map {
                assertTrue(actions.activate(pin), "radix $radix")
                state.simulation.vectorValues["d"]?.toULongOrNull()
            }
            assertEquals(listOf(0x01uL, 0x00uL), values, "radix $radix: bit 0 goes on, then off")
        }
    }

    @Test
    fun toggleOnAClockInAnOriginalChildCircuitSaysWhyNothingHappened() {
        // A 2.7.1 project pokes the Clock (ClockPoker), which works only in the main circuit or an instance view.
        val state = EditorState("""<?xml version="1.0" encoding="UTF-8"?>
<project source="2.7.1" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Clock" logisim-mobile-id="top"/>
    <comp loc="(300,300)" name="child" logisim-mobile-id="inst"/>
  </circuit>
  <circuit name="child">
    <comp lib="0" loc="(100,100)" name="Clock" logisim-mobile-id="c"><a name="label" val="CK"/></comp>
  </circuit>
</project>
""")
        assertTrue(state.project.isOriginalLogisim)
        assertTrue(state.activateComponent("top"), "the main circuit's clock is poked")
        val child = state.project.circuit("child")!!
        state.selectCircuit(child.name)
        assertFalse(state.activateComponent("c"))
        assertEquals("status_io_controls_instance", state.statusText.key)
        assertTrue(state.statusText.resolve(AppLanguage.ENGLISH).startsWith("The controls of CK work in the main circuit"))
        // Through its instance, the poke reaches the clock.
        state.selectCircuit("main")
        val instance = state.document.components.single { it.kind == ComponentKind.SUBCIRCUIT }
        state.navigate(CircuitNavigationTarget(child.id, child.name, listOf(instance.id)))
        assertTrue(state.activateComponent("c"))
    }

    @Test
    fun openControlsOpensOnlyWhereTheInspectorShowsTheControls() {
        val state = EditorState("""<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#I/O" name="5"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="5" loc="(100,100)" name="DipSwitch" logisim-mobile-id="top"/>
    <comp loc="(300,300)" name="child" logisim-mobile-id="inst"/>
  </circuit>
  <circuit name="child">
    <comp lib="5" loc="(100,100)" name="DipSwitch" logisim-mobile-id="sw"><a name="label" val="SW"/></comp>
  </circuit>
</project>
""")
        val selected = mutableListOf<String>()
        var inspectorOpened = 0
        // The editor's own wiring: Open controls selects the component and opens the inspector only when it may.
        fun actions() = editorCircuitAccessibilityActions(EditorToolContext(project = state.project, document = state.document,
            simulation = state.simulation, selectedComponents = emptyList(), applyProject = {}, applyDocument = {},
            selectCircuit = {}, selectComponent = { selected += it }, showStatus = {}, openProperties = { inspectorOpened++ },
            canOpenIoControls = state::requestIoControls))
        fun openControls(id: String) = actions().activate(CircuitAccessibilityGraph(state.document, state.componentRegistry).component(id)!!)

        assertTrue(openControls("top"), "the main circuit shows the controls")
        assertEquals(listOf("top") to 1, selected to inspectorOpened)

        // The child circuit opened directly: the inspector has no controls there, so nothing opens and the status says why.
        val child = state.project.circuit("child")!!
        state.selectCircuit(child.name)
        assertFalse(openControls("sw"))
        assertEquals("status_io_controls_instance", state.statusText.key)
        assertEquals(1, inspectorOpened)
        assertTrue(state.statusText.resolve(AppLanguage.ENGLISH).startsWith("The controls of SW work in the main circuit"))

        // Through its instance, the controls are there.
        state.selectCircuit("main")
        val instance = state.document.components.single { it.kind == ComponentKind.SUBCIRCUIT }
        state.navigate(CircuitNavigationTarget(child.id, child.name, listOf(instance.id)))
        assertTrue(openControls("sw"))
        assertEquals(2, inspectorOpened)

        // A locked editor opens nothing either, and says so.
        state.setInteractionEnabled(false)
        assertFalse(openControls("sw"))
        assertEquals("a11y_explorer_operation_failed", state.statusText.key)
        assertEquals(2, inspectorOpened)
    }

    @Test
    fun showSaysWhenTheWarningsComponentsAreGone() {
        val state = EditorState(xml)
        assertFalse(state.showComponents(listOf("removed")))
        assertEquals("simulation_warning_source_gone", state.statusText.key)
        assertTrue(state.showComponents(listOf("removed", "g")))
        assertEquals(setOf("g"), state.selectedIds)
    }

    @Test
    fun onlyAHeldPointerInputShowsTheMenuBarInThePokeTool() {
        val state = EditorState(xml)
        val strings = Localizer(AppLanguage.ENGLISH)
        state.changeMode(EditorMode.INTERACT)
        val shown = listOf("a", "d", "b", "clk", "sw", "j", "k", "g").filter { id ->
            state.select(id)
            state.showsSelectionBar
        }
        // Button, Joystick and Matrix Keypad: a held finger presses them, so the long press never opens their menu.
        assertEquals(listOf("b", "j", "k"), shown)
        state.select("k")
        assertEquals(listOf("selection-rotate-left", "selection-rotate-right", "selection-delete", "selection-properties"),
            selectionBarActions(state, strings) {}.map { it.id })
        state.select("b")
        assertEquals(listOf("selection-rotate-left", "selection-rotate-right", "selection-delete", "selection-properties"),
            selectionBarActions(state, strings) {}.map { it.id })
        // The selecting tools keep their full bar.
        state.changeMode(EditorMode.EDIT)
        state.select("b")
        assertTrue(selectionBarActions(state, strings) {}.any { it.id == "selection-duplicate" })
    }

    @Test
    fun theBarKindsAreThePointerInputsWhosePressHasARelease() {
        // The canvas opens no long-press menu where a press has a release; those kinds, and only those, get the bar.
        val state = EditorState(xml)
        val held = state.document.components.filter { it.kind in PointerIoKinds }.filter { component ->
            val bounds = component.bounds(state.componentRegistry)
            val center = GridPoint((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2)
            IoInteractions.press(component, center, tolerance = 8)?.release != null
        }.map { it.kind }.toSet()
        assertEquals(PokeHeldKinds, held)
        assertEquals(setOf(IoKinds.Button, IoKinds.Joystick, IoKinds.MatrixKeypad), held)
    }
}
