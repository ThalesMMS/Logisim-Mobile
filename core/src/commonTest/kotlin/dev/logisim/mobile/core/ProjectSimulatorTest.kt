package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProjectSimulatorTest {
    private val simulator = CircuitSimulator()

    @Test
    fun registersCaptureAndRetainAllBits() {
        val register = CircuitComponent("r", ComponentKind.REGISTER, GridPoint(200, 100),
            attributes = mapOf("width" to "8", "appearance" to "classic"))
        val ports = register.inputPorts()
        val document = CircuitDocument(components = listOf(register,
            CircuitComponent("d", ComponentKind.INPUT, ports[0], attributes = mapOf("width" to "8")),
            CircuitComponent("clk", ComponentKind.CLOCK, ports[1]),
            CircuitComponent("enable", ComponentKind.INPUT, ports[2]),
            CircuitComponent("clear", ComponentKind.INPUT, ports[3])))
        var state = simulator.settle(document, inputs = mapOf("enable" to LogicValue.HIGH),
            vectorInputs = mapOf("d" to LogicVector.of(8, 0xabuL)))
        assertEquals(0uL, state.vectorValues.getValue("r").toULongOrNull())
        state = simulator.settle(document, state, tick = true)
        assertEquals(0xabuL, state.vectorValues.getValue("r").toULongOrNull())
        state = simulator.settle(document, state, vectorInputs = mapOf("d" to LogicVector.of(8, 7uL)))
        assertEquals(0xabuL, state.vectorValues.getValue("r").toULongOrNull())
        state = simulator.settle(document, state, inputs = mapOf("clear" to LogicValue.HIGH))
        assertEquals(0uL, state.vectorValues.getValue("r").toULongOrNull())
    }

    @Test
    fun fallingRegisterSamplesOnlyFallingEdges() {
        val register = CircuitComponent("r", ComponentKind.REGISTER, GridPoint(200, 100),
            attributes = mapOf("width" to "4", "appearance" to "classic", "trigger" to "falling"))
        val ports = register.inputPorts()
        val document = CircuitDocument(components = listOf(register,
            CircuitComponent("d", ComponentKind.INPUT, ports[0], attributes = mapOf("width" to "4")),
            CircuitComponent("clk", ComponentKind.CLOCK, ports[1])))
        var state = simulator.settle(document, vectorInputs = mapOf("d" to LogicVector.of(4, 9uL)))
        state = simulator.settle(document, state, tick = true)
        assertEquals(0uL, state.vectorValues.getValue("r").toULongOrNull())
        state = simulator.settle(document, state, tick = true)
        assertEquals(9uL, state.vectorValues.getValue("r").toULongOrNull())
    }

    @Test
    fun twoSubcircuitInstancesKeepIndependentRegisterState() {
        val register = CircuitComponent("r", ComponentKind.REGISTER, GridPoint(200, 100),
            attributes = mapOf("width" to "8", "appearance" to "classic"))
        val ports = register.inputPorts()
        val child = CircuitDocument("storage", listOf(register,
            CircuitComponent("data", ComponentKind.INPUT, ports[0], attributes = mapOf("width" to "8")),
            CircuitComponent("clock", ComponentKind.INPUT, ports[1]),
            CircuitComponent("result", ComponentKind.OUTPUT, GridPoint(220, 100), attributes = mapOf("width" to "8"))),
            listOf(WireSegment(register.position, GridPoint(220, 100))))
        fun instance(id: String, y: Int) = CircuitComponent(id, ComponentKind.SUBCIRCUIT, GridPoint(300, y),
            circuitName = "storage", explicitPorts = listOf(
                ComponentPort("data", GridPoint(-40, 0), PortDirection.INPUT, 8),
                ComponentPort("clock", GridPoint(-40, 20), PortDirection.INPUT),
                ComponentPort("result", GridPoint(0, 0), PortDirection.OUTPUT, 8)))
        val first = instance("first", 100)
        val second = instance("second", 200)
        val main = CircuitDocument("main", listOf(first, second,
            CircuitComponent("a", ComponentKind.INPUT, first.inputPorts()[0], attributes = mapOf("width" to "8")),
            CircuitComponent("b", ComponentKind.INPUT, second.inputPorts()[0], attributes = mapOf("width" to "8")),
            CircuitComponent("ca", ComponentKind.INPUT, first.inputPorts()[1]),
            CircuitComponent("cb", ComponentKind.INPUT, second.inputPorts()[1])))
        val project = CircuitProject(listOf(main, child), "main")
        var state = simulator.settle(project, vectorInputs = mapOf("a" to LogicVector.of(8, 0x55uL),
            "b" to LogicVector.of(8, 0xaauL)))
        state = simulator.settle(project, state, inputs = mapOf("ca" to LogicValue.HIGH))
        assertEquals(0x55uL, state.vectorValues.getValue("first").toULongOrNull())
        assertEquals(0uL, state.vectorValues.getValue("second").toULongOrNull())
        state = simulator.settle(project, state, inputs = mapOf("cb" to LogicValue.HIGH))
        assertEquals(0xaauL, state.vectorValues.getValue("second").toULongOrNull())
        assertEquals(0x55uL, state.circuitStates.getValue("first").vectorValues.getValue("r").toULongOrNull())
        assertEquals("storage", state.circuitStates.getValue("second").circuitName)
        assertFalse(state.oscillating)
    }

    @Test
    fun fallingEdgeCapturesBusBeforeControlledBufferReleasesIt() {
        val buffer = CircuitComponent("buffer", ComponentKind.CONTROLLED_BUFFER, GridPoint(100, 100),
            attributes = mapOf("width" to "8"))
        val register = CircuitComponent("register", ComponentKind.REGISTER, GridPoint(200, 100),
            attributes = mapOf("width" to "8", "appearance" to "classic", "trigger" to "falling"))
        val enable = buffer.inputPorts()[1]
        val clock = register.inputPorts()[1]
        val document = CircuitDocument(components = listOf(buffer, register,
            CircuitComponent("data", ComponentKind.INPUT, buffer.inputPorts()[0], attributes = mapOf("width" to "8")),
            CircuitComponent("clock", ComponentKind.CLOCK, enable)), wires = listOf(
            WireSegment(buffer.position, register.inputPorts()[0]),
            WireSegment(enable, GridPoint(enable.x, clock.y)),
            WireSegment(GridPoint(enable.x, clock.y), clock)))
        var state = simulator.settle(document, vectorInputs = mapOf("data" to LogicVector.of(8, 0x5auL)))
        state = simulator.settle(document, state, tick = true)
        assertEquals(0x5auL, state.vectorValues.getValue("buffer").toULongOrNull())
        state = simulator.settle(document, state, tick = true)
        assertEquals(0x5auL, state.vectorValues.getValue("register").toULongOrNull())
        assertEquals(null, state.vectorValues.getValue("buffer").toULongOrNull())
    }

    @Test
    fun counterClearSuppressesCarryWhileCountingDown() {
        val counter = CircuitComponent("counter", ComponentKind.COUNTER, GridPoint(200, 100),
            attributes = mapOf("width" to "4", "appearance" to "classic", "max" to "0xf"))
        val ports = counter.inputPorts()
        val carry = counter.outputPorts()[1]
        val document = CircuitDocument(components = listOf(counter,
            CircuitComponent("clear", ComponentKind.INPUT, ports[3]),
            CircuitComponent("up", ComponentKind.INPUT, ports[5])))
        var state = simulator.settle(document, inputs = mapOf("clear" to LogicValue.HIGH))
        assertEquals(LogicValue.HIGH, state.portValues.getValue(ports[3]))
        assertEquals(LogicValue.LOW, state.portValues.getValue(ports[5]))
        assertEquals(0uL, state.vectorValues.getValue("counter").toULongOrNull())
        assertEquals(LogicValue.LOW, state.portValues.getValue(carry))
        state = simulator.settle(document, state, inputs = mapOf("clear" to LogicValue.LOW))
        assertEquals(LogicValue.HIGH, state.portValues.getValue(carry))
    }

    @Test
    fun clocksHonorHighAndLowDurations() {
        val document = CircuitDocument(components = listOf(CircuitComponent("c", ComponentKind.CLOCK,
            GridPoint(0, 0), attributes = mapOf("highDuration" to "2", "lowDuration" to "3"))))
        var state = simulator.settle(document)
        val observed = (1..10).map {
            state = simulator.settle(document, state, tick = true)
            state.componentValues.getValue("c")
        }
        assertEquals(listOf(LogicValue.LOW, LogicValue.LOW, LogicValue.HIGH, LogicValue.HIGH, LogicValue.LOW,
            LogicValue.LOW, LogicValue.LOW, LogicValue.HIGH, LogicValue.HIGH, LogicValue.LOW), observed)
    }
}
