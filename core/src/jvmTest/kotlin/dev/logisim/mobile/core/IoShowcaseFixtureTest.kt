package dev.logisim.mobile.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Literal oracles for the file exercised through the Android and iOS UI. */
class IoShowcaseFixtureTest {
    @Test
    fun fixtureIncludesEveryIoTypeAndHasDefinedInitialProbeValues() {
        val run = Run()
        val components = run.project.mainCircuit.components
        assertEquals(IoLibrary.definitions.map { it.kind }.toSet(), components.map { it.kind }.toSet().intersect(IoLibrary.definitions.map { it.kind }.toSet()))
        assertEquals(components.size, components.map { it.label }.distinct().size)
        assertTrue(components.filter { it.kind == ComponentKind.INPUT }.all {
            it.attributes["type"] == "input" && it.attributes["facing"] == "east" &&
                it.attributes["behavior"] == "simple" && it.attributes["width"] != null && it.attributes["initial"] != null
        })
        assertTrue(components.filter { it.kind == ComponentKind.OUTPUT }.all { it.attributes["radix"] == "2" })
        assertEquals(mapOf(
            "OUT_BUTTON" to "0", "OUT_LED_LEVEL" to "0", "OUT_DIP0" to "0", "OUT_DIP1" to "0",
            "OUT_JOY_X" to "1000", "OUT_JOY_Y" to "1000",
            "OUT_RGB_R" to "1", "OUT_RGB_G" to "1", "OUT_RGB_B" to "0",
            "OUT_BAR_VALUE" to "10100101", "OUT_SEGMENTS" to "00111111",
            "OUT_KEYBOARD_DATA" to "0000000", "OUT_KEYBOARD_AVAILABLE" to "0", "OUT_CLOCK" to "0",
            "OUT_MATRIX_ROW0" to "01110",
            "OUT_KEYPAD_C0" to "1", "OUT_KEYPAD_C1" to "1", "OUT_KEYPAD_C2" to "1", "OUT_KEYPAD_C3" to "1",
        ), run.outputs())
        assertEquals(3, assertIs<IoLampState>(run.componentState("IO_RGB")).bits)
        assertEquals(63, assertIs<IoLampState>(run.componentState("IO_SEVEN_SEGMENT")).bits)
        assertEquals(0x7f, assertIs<IoLampState>(run.componentState("IO_HEX")).bits)
        assertEquals(listOf("", "", ""), assertIs<IoTerminalState>(run.componentState("IO_TTY")).displayRows)
        run.assertClean()
    }

    @Test
    fun buttonDipJoystickAndKeypadHaveLiteralPressReleaseAndCancelOracles() {
        val run = Run()
        run.send("IO_BUTTON", IoInputEvent.Press(true))
        assertEquals("1", run.output("OUT_BUTTON"))
        assertEquals(LogicValue.HIGH, assertIs<IoLedState>(run.componentState("IO_LED_BUTTON")).value)
        run.send("IO_BUTTON", IoInputEvent.Cancel)
        assertEquals("0", run.output("OUT_BUTTON"))
        run.send("IO_DIP", IoInputEvent.ToggleSwitch(0))
        assertEquals("1", run.output("OUT_DIP0"))
        assertEquals("0", run.output("OUT_DIP1"))
        run.send("IO_DIP", IoInputEvent.ToggleSwitch(1))
        assertEquals("1", run.output("OUT_DIP0"))
        assertEquals("1", run.output("OUT_DIP1"))
        assertEquals(3uL, assertIs<IoDipState>(run.componentState("IO_DIP")).positions)
        run.send("IO_JOYSTICK", IoInputEvent.MoveJoystick(14, -14))
        assertEquals("1111", run.output("OUT_JOY_X"))
        assertEquals("0001", run.output("OUT_JOY_Y"))
        assertEquals(0x71, assertIs<IoLampState>(run.componentState("IO_HEX")).bits)
        run.send("IO_JOYSTICK", IoInputEvent.Cancel)
        assertEquals("1000", run.output("OUT_JOY_X"))
        assertEquals("1000", run.output("OUT_JOY_Y"))
        run.send("IO_KEYPAD", IoInputEvent.KeypadKey(0, 2, true))
        assertEquals(listOf("1", "1", "0", "1"), (0..3).map { run.output("OUT_KEYPAD_C$it") })
        run.send("IO_KEYPAD", IoInputEvent.KeypadKey(0, 2, false))
        assertEquals(listOf("1", "1", "1", "1"), (0..3).map { run.output("OUT_KEYPAD_C$it") })
        run.assertClean()
    }

    @Test
    fun pinActivationsDriveTheNamedDisplaysWithTheExpectedBitOrder() {
        val run = Run()
        run.drive("IN_LED_LEVEL", 1u)
        assertEquals("1", run.output("OUT_LED_LEVEL"))
        assertEquals(LogicValue.HIGH, assertIs<IoLedState>(run.componentState("IO_LED")).value)
        run.drive("IN_LED_LEVEL", 0u)
        assertEquals("0", run.output("OUT_LED_LEVEL"))
        assertEquals(LogicValue.LOW, assertIs<IoLedState>(run.componentState("IO_LED")).value)
        run.drive("IN_BAR_VALUE", 166u)
        assertEquals("10100110", run.output("OUT_BAR_VALUE"))
        assertEquals("10100110", assertIs<IoMatrixState>(run.componentState("IO_LEDBAR")).values.joinToString("") { it.display })
        run.drive("IN_SEGMENTS", 64u)
        assertEquals("01000000", run.output("OUT_SEGMENTS"))
        assertEquals(64, assertIs<IoLampState>(run.componentState("IO_SEVEN_SEGMENT")).bits)
        run.drive("IN_MATRIX_ROW_0", 15u)
        assertEquals("01111", run.output("OUT_MATRIX_ROW0"))
        assertEquals("01111", assertIs<IoMatrixState>(run.componentState("IO_DOT_MATRIX")).values.take(5).joinToString("") { it.display })
        run.drive("IN_RGB_R", 0u)
        assertEquals("0", run.output("OUT_RGB_R"))
        assertEquals(2, assertIs<IoLampState>(run.componentState("IO_RGB")).bits)
        run.drive("IN_RGB_G", 0u)
        assertEquals(0, assertIs<IoLampState>(run.componentState("IO_RGB")).bits)
        run.drive("IN_RGB_B", 1u)
        assertEquals("1", run.output("OUT_RGB_B"))
        assertEquals(4, assertIs<IoLampState>(run.componentState("IO_RGB")).bits)
        run.assertClean()
    }

    @Test
    fun twoRisingEdgesTransferKeyboardABToTtyAcrossThreeHalfCycles() {
        val run = Run()
        run.send("IO_KEYBOARD", IoInputEvent.ReplaceKeyboardText("AB", 2))
        assertEquals("1000001", run.output("OUT_KEYBOARD_DATA"))
        assertEquals("1", run.output("OUT_KEYBOARD_AVAILABLE"))
        run.risingEdge()
        assertEquals("B", assertIs<IoKeyboardState>(run.componentState("IO_KEYBOARD")).buffer)
        assertEquals("A", assertIs<IoTerminalState>(run.componentState("IO_TTY")).current)
        assertEquals("1000010", run.output("OUT_KEYBOARD_DATA"))
        run.risingEdge()
        assertEquals(3L, run.state.ticks)
        assertEquals("", assertIs<IoKeyboardState>(run.componentState("IO_KEYBOARD")).buffer)
        assertEquals(listOf("AB", "", ""), assertIs<IoTerminalState>(run.componentState("IO_TTY")).displayRows)
        assertEquals("0000000", run.output("OUT_KEYBOARD_DATA"))
        assertEquals("0", run.output("OUT_KEYBOARD_AVAILABLE"))
        run.assertClean()
    }

    @Test
    fun backspaceNewlineFormFeedAndSevenBitCharactersReachTheTty() {
        val run = Run()
        run.send("IO_KEYBOARD", IoInputEvent.ReplaceKeyboardText("AB\bC\nD", 6))
        repeat(6) { run.risingEdge() }
        assertEquals(listOf("AC", "D", ""), assertIs<IoTerminalState>(run.componentState("IO_TTY")).displayRows)
        run.send("IO_KEYBOARD", IoInputEvent.ReplaceKeyboardText("\u000cZ", 2))
        repeat(2) { run.risingEdge() }
        assertEquals(listOf("Z", "", ""), assertIs<IoTerminalState>(run.componentState("IO_TTY")).displayRows)
        run.drive("IN_TTY_CLEAR", 1u)
        assertEquals(listOf("", "", ""), assertIs<IoTerminalState>(run.componentState("IO_TTY")).displayRows)
        run.drive("IN_TTY_CLEAR", 0u)
        run.send("IO_KEYBOARD", IoInputEvent.ReplaceKeyboardText("Aé", 2))
        repeat(2) { run.risingEdge() }
        assertEquals(listOf("Ai", "", ""), assertIs<IoTerminalState>(run.componentState("IO_TTY")).displayRows)
        run.send("IO_KEYBOARD", IoInputEvent.ReplaceKeyboardText("Q", 1))
        run.drive("IN_KEYBOARD_CLEAR", 1u)
        assertEquals("", assertIs<IoKeyboardState>(run.componentState("IO_KEYBOARD")).buffer)
        assertEquals("0", run.output("OUT_KEYBOARD_AVAILABLE"))
        run.assertClean()
    }

    @Test
    fun dimensionalAttributeChangesPreserveConnectedProbeTerminalsAndRoundTrip() {
        val run = Run()
        run.send("IO_DIP", IoInputEvent.ToggleSwitch(0))
        run.send("IO_DIP", IoInputEvent.ToggleSwitch(1))
        for (count in listOf(4, 8)) {
            run.attributes("IO_DIP", mapOf("number" to "$count"))
            assertEquals("1", run.output("OUT_DIP0"))
            assertEquals("1", run.output("OUT_DIP1"))
            assertEquals(3uL, assertIs<IoDipState>(run.componentState("IO_DIP")).positions)
        }
        run.attributes("IO_LEDBAR", mapOf("height" to "4"))
        val bar = run.component("IO_LEDBAR").layout().bounds
        assertEquals(40, bar.bottom - bar.top)
        assertEquals("10100101", run.output("OUT_BAR_VALUE"))
        for (count in listOf(4, 7)) {
            run.attributes("IO_DOT_MATRIX", mapOf("matrixrows" to "$count"))
            assertEquals(count, assertIs<IoMatrixState>(run.componentState("IO_DOT_MATRIX")).rows)
            assertEquals("01110", run.output("OUT_MATRIX_ROW0"))
        }
        run.attributes("IO_TTY", mapOf("rows" to "4", "cols" to "16"))
        val tty = run.component("IO_TTY").layout().bounds
        assertEquals(124, tty.right - tty.left)
        assertEquals(72, tty.bottom - tty.top)
        val xml = CircCodec.writeProject(run.project)
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
        assertEquals(run.project.mainCircuit.components.map { it.label }, reopened.mainCircuit.components.map { it.label })
        assertEquals(xml, CircCodec.writeProject(reopened))
        run.assertClean()
    }

    @Test
    fun manifestPointerPointsMapToTheIntendedControlsWithoutKeypadGaps() {
        val run = Run()
        assertEquals(IoPointerAction(IoInputEvent.Press(true), IoInputEvent.Press(false)),
            IoInteractions.press(run.component("IO_BUTTON"), GridPoint(170, 120)))
        assertEquals(IoPointerAction(IoInputEvent.ToggleSwitch(0)),
            IoInteractions.press(run.component("IO_DIP"), GridPoint(100, 290)))
        assertEquals(IoPointerAction(IoInputEvent.ToggleSwitch(1)),
            IoInteractions.press(run.component("IO_DIP"), GridPoint(100, 300)))
        assertEquals(IoPointerAction(IoInputEvent.MoveJoystick(0, 0), IoInputEvent.Cancel),
            IoInteractions.press(run.component("IO_JOYSTICK"), GridPoint(425, 325)))
        assertEquals(IoInputEvent.MoveJoystick(14, -14),
            IoInteractions.drag(run.component("IO_JOYSTICK"), GridPoint(439, 311)))
        for ((point, row, column) in listOf(
            Triple(GridPoint(258, 958), 0, 0), Triple(GridPoint(314, 958), 0, 2), Triple(GridPoint(342, 1042), 3, 3),
        )) assertEquals(IoPointerAction(IoInputEvent.KeypadKey(row, column, true), IoInputEvent.KeypadKey(row, column, false)),
            IoInteractions.press(run.component("IO_KEYPAD"), point))
    }

    private class Run {
        var project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(fixture.readText())).project
        private val simulator = CircuitSimulator()
        private val drives = mutableMapOf<String, LogicVector>()
        var state = simulator.settle(project)
            private set
        fun component(label: String) = project.mainCircuit.components.single { it.label == label }
        fun componentState(label: String) = state.componentStates.getValue(component(label).id)
        fun output(label: String) = state.vectorValues.getValue(component(label).id).display
        fun outputs() = project.mainCircuit.components.filter { it.kind == ComponentKind.OUTPUT }.associate { it.label to output(it.label) }
        fun send(label: String, event: IoInputEvent) {
            state = simulator.settle(project, state, vectorInputs = drives, events = mapOf(component(label).id to listOf(event)))
        }
        fun drive(label: String, value: ULong) {
            val source = component(label)
            drives[source.id] = LogicVector.of(source.width, value)
            state = simulator.settle(project, state, vectorInputs = drives)
        }
        fun risingEdge() {
            if (output("OUT_CLOCK") == "1") state = simulator.settle(project, state, tick = true, vectorInputs = drives)
            assertEquals("0", output("OUT_CLOCK"))
            state = simulator.settle(project, state, tick = true, vectorInputs = drives)
            assertEquals("1", output("OUT_CLOCK"))
        }
        fun attributes(label: String, changes: Map<String, String>) {
            val original = component(label)
            val next = ComponentCatalog.registry.reconfigure(original, changes)
            project = project.copy(circuits = project.circuits.map { circuit -> circuit.copy(components = circuit.components.map { if (it.id == original.id) next else it }) })
            state = simulator.settle(project, state, vectorInputs = drives)
        }
        fun assertClean() {
            assertFalse(state.oscillating)
            assertTrue(state.diagnostics.isEmpty(), state.diagnostics.joinToString("\n"))
            assertTrue(outputs().values.none { it.contains('?') || it.contains('E') }, outputs().toString())
        }
    }

    companion object {
        private val fixture: File get() = sequenceOf(File("examples/io-showcase.circ"), File("../examples/io-showcase.circ")).first { it.isFile }
    }
}
