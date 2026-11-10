package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SequentialIntegrationTest {
    private val registry = ComponentCatalog.registry

    @Test fun allEightDevicesAreAvailableThroughTheDefaultFileCodec() {
        val devices = SequentialLibrary.definitions.mapIndexed { index, definition ->
            registry.create(definition.kind, "device-$index", GridPoint(index * 400, 200),
                if (definition.kind == SequentialLibrary.Random) mapOf("seed" to "1") else emptyMap())
        }
        val project = CircuitProject(listOf(CircuitDocument("sequential", devices)))
        val xml = CircCodec.writeProject(project)
        val restored = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
        assertEquals(SequentialLibrary.definitions.map { it.kind }, restored.mainCircuit.components.map { it.kind })
        assertEquals(devices.map { it.attributes }, restored.mainCircuit.components.map { it.attributes })
    }

    @Test fun registersCaptureTheSameEventInputsBeforeEitherOutputChanges() {
        val first = registry.create(ComponentKind.REGISTER, "a", GridPoint(100, 100), mapOf("width" to "8"))
        val second = registry.create(ComponentKind.REGISTER, "b", GridPoint(100, 200), mapOf("width" to "8"))
        fun port(component: CircuitComponent, name: String) = registry.layout(component).ports.single { it.name == name }.position
        val firstData = port(first, "data")
        val secondData = port(second, "data")
        val firstClock = port(first, "clock")
        val secondClock = port(second, "clock")
        val document = CircuitDocument(components = listOf(first, second,
            CircuitComponent("input", ComponentKind.INPUT, firstData, attributes = mapOf("width" to "8")),
            CircuitComponent("clock-a", ComponentKind.INPUT, firstClock),
            CircuitComponent("clock-b", ComponentKind.INPUT, secondClock)),
            wires = listOf(WireSegment(first.position, GridPoint(130, 100)),
                WireSegment(GridPoint(130, 100), GridPoint(130, 170)),
                WireSegment(GridPoint(130, 170), GridPoint(40, 170)),
                WireSegment(GridPoint(40, 170), GridPoint(40, 200)),
                WireSegment(GridPoint(40, 200), secondData)))
        val simulator = CircuitSimulator()
        var state = simulator.settle(document, vectorInputs = mapOf("input" to LogicVector.of(8, 0x5auL)))
        state = simulator.settle(document, state, inputs = mapOf("clock-a" to LogicValue.HIGH, "clock-b" to LogicValue.HIGH))
        assertFalse(state.oscillating, state.diagnostics.joinToString())
        assertEquals(0x5auL, state.vectorValues.getValue("a").toULongOrNull())
        assertEquals(0uL, state.vectorValues.getValue("b").toULongOrNull())
        state = simulator.settle(document, state, inputs = mapOf("clock-a" to LogicValue.LOW, "clock-b" to LogicValue.LOW))
        state = simulator.settle(document, state, inputs = mapOf("clock-a" to LogicValue.HIGH, "clock-b" to LogicValue.HIGH))
        assertEquals(0x5auL, state.vectorValues.getValue("b").toULongOrNull())
    }

    @Test fun newFlipFlopExecutesWithIndependentHierarchyState() {
        val flip = registry.create(SequentialLibrary.TFlipFlop, "toggle", GridPoint(100, 100))
        val layout = registry.layout(flip)
        fun point(name: String) = layout.ports.single { it.name == name }.position
        val child = CircuitDocument("toggle-unit", components = listOf(flip,
            CircuitComponent("data", ComponentKind.INPUT, point("t")),
            CircuitComponent("clock", ComponentKind.INPUT, point("clock")),
            CircuitComponent("out", ComponentKind.OUTPUT, point("q"))))
        fun instance(id: String, x: Int) = CircuitComponent(id, ComponentKind.SUBCIRCUIT, GridPoint(x, 200),
            circuitName = child.name, explicitPorts = listOf(
                ComponentPort("data", GridPoint(-20, -10), PortDirection.INPUT),
                ComponentPort("clock", GridPoint(-20, 10), PortDirection.INPUT),
                ComponentPort("out", GridPoint(0, 0), PortDirection.OUTPUT)))
        val project = CircuitProject(listOf(CircuitDocument("main", components = listOf(instance("one", 100), instance("two", 200),
            CircuitComponent("one-t", ComponentKind.INPUT, GridPoint(80, 190)),
            CircuitComponent("one-clock", ComponentKind.INPUT, GridPoint(80, 210)),
            CircuitComponent("two-t", ComponentKind.INPUT, GridPoint(180, 190)),
            CircuitComponent("two-clock", ComponentKind.INPUT, GridPoint(180, 210)))), child), "main")
        val simulator = CircuitSimulator()
        var state = simulator.settle(project, inputs = mapOf("one-t" to LogicValue.HIGH, "two-t" to LogicValue.HIGH))
        state = simulator.settle(project, state, inputs = mapOf("one-clock" to LogicValue.HIGH))
        assertFalse(state.oscillating)
        assertEquals("1", state.vectorValues.getValue("one").display)
        assertEquals("0", state.vectorValues.getValue("two").display)
        assertTrue(state.circuitStates.getValue("one").componentStates.getValue("toggle") !==
            state.circuitStates.getValue("two").componentStates.getValue("toggle"))
        val reset = simulator.settle(project, state, inputs = mapOf("one-clock" to LogicValue.LOW), reset = true)
        assertEquals("0", reset.vectorValues.getValue("one").display)
        assertEquals("0", reset.vectorValues.getValue("two").display)
    }
}
