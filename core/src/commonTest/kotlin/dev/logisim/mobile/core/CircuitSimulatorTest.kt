package dev.logisim.mobile.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircuitSimulatorTest {
    private val simulator = CircuitSimulator()

    @Test
    fun projectNoiseScalesDelaysAndCarriesItsCountdownBetweenSteps() {
        val clock = CircuitComponent("clock", ComponentKind.CLOCK, GridPoint(100, 100))
        val output = CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(200, 100))
        val document = CircuitDocument(components = listOf(clock, output),
            wires = listOf(WireSegment(clock.position, output.position)))
        fun project(simrand: Int) = CircuitProject(listOf(document), extraXml = listOf(
            """<options><a name="simrand" val="$simrand"/></options>""",
        ))
        val draws = ArrayDeque(listOf(2L, 0L, 0L))
        val noise = object : Random() {
            override fun nextBits(bitCount: Int): Int = error("Expected a bounded noise draw")
            override fun nextLong(until: Long): Long {
                assertEquals(16L, until)
                return draws.removeFirst()
            }
        }
        fun trace(project: CircuitProject): List<Pair<Long, LogicValue?>> {
            var state = CircuitSimulator(noise).stepEvent(project)
            return List(5) { tick ->
                if (tick > 0) state = CircuitSimulator(noise).settle(project, state, tick = true)
                state.propagationTime to state.componentValues["out"]
            }
        }
        val values = listOf(LogicValue.LOW, LogicValue.HIGH, LogicValue.LOW, LogicValue.HIGH, LogicValue.LOW)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L).zip(values), trace(project(0)))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L).zip(values), trace(project(1)))
        assertEquals(listOf(17L, 33L, 49L, 66L, 83L).zip(values), trace(project(10)))
        assertTrue(draws.isEmpty())
    }

    @Test
    fun everyBinaryGateMatchesItsTruthTable() {
        val tables = mapOf(
            ComponentKind.AND to listOf(0, 0, 0, 1),
            ComponentKind.OR to listOf(0, 1, 1, 1),
            ComponentKind.XOR to listOf(0, 1, 1, 0),
            ComponentKind.NAND to listOf(1, 1, 1, 0),
            ComponentKind.NOR to listOf(1, 0, 0, 0),
            ComponentKind.XNOR to listOf(1, 0, 0, 1),
        )
        for ((kind, expected) in tables) {
            val document = binaryGate(kind)
            val actual = (0..3).map { bits ->
                simulator.settle(document, inputs = mapOf(
                    "a" to bit(bits / 2), "b" to bit(bits % 2),
                )).componentValues.getValue("out")
            }
            assertEquals(expected.map(::bit), actual, kind.name)
        }
    }

    @Test
    fun unaryGateUsesItsActualPortGeometry() {
        for (kind in listOf(ComponentKind.NOT, ComponentKind.BUFFER)) {
            val gate = CircuitComponent("gate", kind, GridPoint(100, 100))
            val input = CircuitComponent("in", ComponentKind.INPUT, gate.inputPorts().single())
            val output = CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(200, 100))
            val document = CircuitDocument(components = listOf(input, gate, output),
                wires = listOf(WireSegment(gate.position, output.position)))
            for (value in listOf(LogicValue.LOW, LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.ERROR)) {
                val state = simulator.settle(document, inputs = mapOf("in" to value))
                val expected = if (kind == ComponentKind.BUFFER) value else when (value) {
                    LogicValue.LOW -> LogicValue.HIGH
                    LogicValue.HIGH -> LogicValue.LOW
                    else -> LogicValue.ERROR
                }
                assertEquals(expected, state.componentValues["out"])
            }
        }
    }

    @Test
    fun demoInputsDefaultLowAndCanBeDrivenAcrossCalls() {
        val document = DemoCircuits.andGate()
        val initial = simulator.settle(document)
        assertEquals(LogicValue.LOW, initial.componentValues["out"])
        val aHigh = simulator.settle(document, initial, inputs = mapOf("a" to LogicValue.HIGH))
        assertEquals(LogicValue.LOW, aHigh.componentValues["out"])
        val bothHigh = simulator.settle(document, aHigh, inputs = mapOf("b" to LogicValue.HIGH))
        assertEquals(LogicValue.HIGH, bothHigh.componentValues["out"])
        assertEquals(LogicValue.HIGH, bothHigh.inputs["a"])
        assertFalse(bothHigh.oscillating)
    }

    @Test
    fun controllingValuesResolveUnknownInputs() {
        assertEquals(LogicValue.LOW, simulator.settle(binaryGate(ComponentKind.AND), inputs = mapOf(
            "a" to LogicValue.LOW, "b" to LogicValue.UNKNOWN)).componentValues["out"])
        assertEquals(LogicValue.HIGH, simulator.settle(binaryGate(ComponentKind.OR), inputs = mapOf(
            "a" to LogicValue.HIGH, "b" to LogicValue.UNKNOWN)).componentValues["out"])
        assertEquals(LogicValue.ERROR, simulator.settle(binaryGate(ComponentKind.XOR), inputs = mapOf(
            "a" to LogicValue.HIGH, "b" to LogicValue.UNKNOWN)).componentValues["out"])
        assertEquals(LogicValue.ERROR, simulator.settle(binaryGate(ComponentKind.XOR), inputs = mapOf(
            "a" to LogicValue.HIGH, "b" to LogicValue.ERROR)).componentValues["out"])
    }

    @Test
    fun connectedFourStateInputsMatchDesktopGateTruthTables() {
        val low = LogicValue.LOW
        val high = LogicValue.HIGH
        val unknown = LogicValue.UNKNOWN
        val error = LogicValue.ERROR
        val states = listOf(low, high, unknown, error)
        val expected = mapOf(
            ComponentKind.AND to listOf(low, low, low, low, low, high, error, error,
                low, error, error, error, low, error, error, error),
            ComponentKind.OR to listOf(low, high, error, error, high, high, high, high,
                error, high, error, error, error, high, error, error),
            ComponentKind.XOR to listOf(low, high, error, error, high, low, error, error,
                error, error, error, error, error, error, error, error),
        )
        for ((kind, table) in expected) {
            val document = binaryGate(kind)
            val actual = states.flatMap { a -> states.map { b ->
                simulator.settle(document, inputs = mapOf("a" to a, "b" to b))
                    .componentValues.getValue("out")
            } }
            assertEquals(table, actual, kind.name)
        }
    }

    @Test
    fun unusedGateInputsAreIgnoredButEmptyGatesProduceError() {
        val expectedHigh = mapOf(
            ComponentKind.AND to LogicValue.HIGH, ComponentKind.OR to LogicValue.HIGH,
            ComponentKind.XOR to LogicValue.HIGH, ComponentKind.NAND to LogicValue.LOW,
            ComponentKind.NOR to LogicValue.LOW, ComponentKind.XNOR to LogicValue.LOW,
        )
        for ((kind, expected) in expectedHigh) {
            val gate = CircuitComponent("gate", kind, GridPoint(200, 100))
            val output = CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(300, 100))
            val empty = CircuitDocument(components = listOf(gate, output),
                wires = listOf(WireSegment(gate.position, output.position)))
            assertEquals(LogicValue.ERROR, simulator.settle(empty).componentValues["out"], "$kind empty")
            val oneInput = empty.copy(components = empty.components +
                CircuitComponent("a", ComponentKind.INPUT, gate.inputPorts()[0]))
            assertEquals(expected, simulator.settle(oneInput, inputs = mapOf("a" to LogicValue.HIGH))
                .componentValues["out"], "$kind one input")
        }
    }

    @Test
    fun wireWithoutDriverIsConnectedUnknownRatherThanUnusedInput() {
        val gate = CircuitComponent("gate", ComponentKind.AND, GridPoint(200, 100))
        val (a, b) = gate.inputPorts()
        val document = CircuitDocument(components = listOf(gate,
            CircuitComponent("a", ComponentKind.INPUT, a),
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(300, 100))),
            wires = listOf(WireSegment(gate.position, GridPoint(300, 100)),
                WireSegment(b, GridPoint(b.x - 10, b.y))))
        assertEquals(LogicValue.ERROR, simulator.settle(document, inputs = mapOf("a" to LogicValue.HIGH))
            .componentValues["out"])
        val onlyA = document.copy(wires = document.wires.take(1))
        assertEquals(LogicValue.HIGH, simulator.settle(onlyA, inputs = mapOf("a" to LogicValue.HIGH))
            .componentValues["out"])
        assertEquals(LogicValue.UNKNOWN, simulator.settle(onlyA, inputs = mapOf("a" to LogicValue.UNKNOWN))
            .componentValues["out"])
    }

    @Test
    fun floatingOutputIsUnknown() {
        val document = CircuitDocument(components = listOf(
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(100, 100))))
        assertEquals(LogicValue.UNKNOWN, simulator.settle(document).componentValues["out"])
    }

    @Test
    fun conflictingDriversProduceErrorOnTheWholeNet() {
        val document = CircuitDocument(components = listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0)),
            CircuitComponent("b", ComponentKind.INPUT, GridPoint(100, 0)),
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(50, 0))),
            wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(100, 0))))
        val state = simulator.settle(document, inputs = mapOf("a" to LogicValue.HIGH))
        assertEquals(LogicValue.ERROR, state.componentValues["out"])
        assertEquals(LogicValue.ERROR, state.portValues[GridPoint(0, 0)])
    }

    @Test
    fun unknownDriverIsNeutralWhenAnotherDriverIsKnown() {
        val document = CircuitDocument(components = listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0)),
            CircuitComponent("b", ComponentKind.INPUT, GridPoint(100, 0)),
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(50, 0))),
            wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(100, 0))))
        for (value in listOf(LogicValue.LOW, LogicValue.HIGH)) {
            val state = simulator.settle(document, inputs = mapOf("a" to value, "b" to LogicValue.UNKNOWN))
            assertEquals(value, state.componentValues["out"])
        }
    }

    @Test
    fun crossingInteriorsStaySeparateAndEndpointJunctionConnects() {
        val components = listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 50)),
            CircuitComponent("b", ComponentKind.INPUT, GridPoint(50, 0)),
            CircuitComponent("x", ComponentKind.OUTPUT, GridPoint(100, 50)),
            CircuitComponent("y", ComponentKind.OUTPUT, GridPoint(50, 100)))
        val horizontal = WireSegment(GridPoint(0, 50), GridPoint(100, 50))
        val vertical = WireSegment(GridPoint(50, 0), GridPoint(50, 100))
        val crossing = CircuitDocument(components = components, wires = listOf(horizontal, vertical))
        val separate = simulator.settle(crossing, inputs = mapOf("a" to LogicValue.HIGH))
        assertEquals(LogicValue.HIGH, separate.componentValues["x"])
        assertEquals(LogicValue.LOW, separate.componentValues["y"])
        val joined = crossing.copy(wires = listOf(horizontal,
            WireSegment(GridPoint(50, 0), GridPoint(50, 50)),
            WireSegment(GridPoint(50, 50), GridPoint(50, 100))))
        val conflict = simulator.settle(joined, inputs = mapOf("a" to LogicValue.HIGH))
        assertEquals(LogicValue.ERROR, conflict.componentValues["x"])
        assertEquals(LogicValue.ERROR, conflict.componentValues["y"])
    }

    @Test
    fun flipFlopCapturesOnlyOnRisingClockAndRetainsData() {
        val document = DemoCircuits.flipFlop()
        var state = simulator.settle(document)
        assertEquals(LogicValue.LOW, state.componentValues["out"])
        state = simulator.settle(document, state, inputs = mapOf("data" to LogicValue.HIGH))
        assertEquals(LogicValue.LOW, state.componentValues["out"])
        state = simulator.settle(document, state, tick = true)
        assertEquals(LogicValue.HIGH, state.componentValues["out"])
        assertEquals(1L, state.ticks)
        state = simulator.settle(document, state, inputs = mapOf("data" to LogicValue.LOW))
        assertEquals(LogicValue.HIGH, state.componentValues["out"])
        state = simulator.settle(document, state, tick = true)
        assertEquals(LogicValue.HIGH, state.componentValues["out"])
        state = simulator.settle(document, state, tick = true)
        assertEquals(LogicValue.LOW, state.componentValues["out"])
        assertEquals(3L, state.ticks)
    }

    @Test
    fun classicFlipFlopExposesInvertedOutputAndAsynchronousReset() {
        val original = DemoCircuits.flipFlop()
        val document = original.copy(components = original.components + listOf(
            CircuitComponent("reset", ComponentKind.INPUT, GridPoint(290, 180)),
            CircuitComponent("inverse", ComponentKind.OUTPUT, GridPoint(400, 170))),
            wires = original.wires + WireSegment(GridPoint(300, 170), GridPoint(400, 170)))
        var state = simulator.settle(document, inputs = mapOf("data" to LogicValue.HIGH))
        assertEquals(LogicValue.HIGH, state.componentValues["inverse"])
        state = simulator.settle(document, state, tick = true)
        assertEquals(LogicValue.LOW, state.componentValues["inverse"])
        state = simulator.settle(document, state, inputs = mapOf("reset" to LogicValue.HIGH))
        assertEquals(LogicValue.LOW, state.componentValues["out"])
        assertEquals(LogicValue.HIGH, state.componentValues["inverse"])
    }

    @Test
    fun cascadedFlipFlopClockIsConsumedBeforeSettleReturns() {
        val first = CircuitComponent("first", ComponentKind.D_FLIP_FLOP, GridPoint(200, 100))
        val second = CircuitComponent("second", ComponentKind.D_FLIP_FLOP, GridPoint(400, 100))
        val document = CircuitDocument(components = listOf(first, second,
            CircuitComponent("clock", ComponentKind.CLOCK, first.inputPorts()[1]),
            CircuitComponent("a", ComponentKind.INPUT, first.inputPorts()[0]),
            CircuitComponent("b", ComponentKind.INPUT, second.inputPorts()[0]),
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(500, 100))),
            wires = listOf(WireSegment(first.position, second.inputPorts()[1]),
                WireSegment(second.position, GridPoint(500, 100))))
        val initial = simulator.settle(document, inputs = mapOf("a" to LogicValue.HIGH, "b" to LogicValue.HIGH))
        assertEquals(LogicValue.LOW, initial.componentValues["out"])
        val rising = simulator.settle(document, initial, tick = true)
        assertEquals(LogicValue.HIGH, rising.componentValues["first"])
        assertEquals(LogicValue.HIGH, rising.componentValues["second"])
        assertEquals(LogicValue.HIGH, rising.componentValues["out"])
        assertEquals(rising, simulator.settle(document, rising))
    }

    @Test
    fun registersOnOneClockSampleOldDataSimultaneously() {
        val first = CircuitComponent("first", ComponentKind.D_FLIP_FLOP, GridPoint(200, 100))
        val second = CircuitComponent("second", ComponentKind.D_FLIP_FLOP, GridPoint(400, 100))
        val document = CircuitDocument(components = listOf(first, second,
            CircuitComponent("a", ComponentKind.INPUT, first.inputPorts()[0]),
            CircuitComponent("clock", ComponentKind.CLOCK, GridPoint(100, 50))),
            wires = listOf(
                WireSegment(GridPoint(100, 50), GridPoint(360, 50)),
                WireSegment(GridPoint(160, 50), first.inputPorts()[1]),
                WireSegment(GridPoint(360, 50), second.inputPorts()[1]),
                WireSegment(first.position, GridPoint(300, 100)),
                WireSegment(GridPoint(300, 100), GridPoint(300, 120)),
                WireSegment(GridPoint(300, 120), second.inputPorts()[0]),
            ))
        val initial = simulator.settle(document, inputs = mapOf("a" to LogicValue.HIGH))
        val rising = simulator.settle(document, initial, tick = true)
        assertEquals(LogicValue.HIGH, rising.componentValues["first"])
        assertEquals(LogicValue.LOW, rising.componentValues["second"])
        val falling = simulator.settle(document, rising, tick = true)
        val nextRising = simulator.settle(document, falling, tick = true)
        assertEquals(LogicValue.HIGH, nextRising.componentValues["second"])
    }

    @Test
    fun flipFlopResetWinsOverPresetAndUndefinedDataRetainsMemory() {
        val flipFlop = CircuitComponent("ff", ComponentKind.D_FLIP_FLOP, GridPoint(200, 100))
        val ports = flipFlop.inputPorts()
        val document = CircuitDocument(components = listOf(flipFlop,
            CircuitComponent("data", ComponentKind.INPUT, ports[0]),
            CircuitComponent("clock", ComponentKind.CLOCK, ports[1]),
            CircuitComponent("reset", ComponentKind.INPUT, ports[2]),
            CircuitComponent("set", ComponentKind.INPUT, ports[3])))
        var state = simulator.settle(document, inputs = mapOf("data" to LogicValue.HIGH))
        state = simulator.settle(document, state, tick = true)
        assertEquals(LogicValue.HIGH, state.componentValues["ff"])
        state = simulator.settle(document, state, tick = true)
        state = simulator.settle(document, state, inputs = mapOf("data" to LogicValue.UNKNOWN), tick = true)
        assertEquals(LogicValue.HIGH, state.componentValues["ff"])
        state = simulator.settle(document, state, inputs = mapOf(
            "reset" to LogicValue.HIGH, "set" to LogicValue.HIGH))
        assertEquals(LogicValue.LOW, state.componentValues["ff"])
        state = simulator.settle(document, state, inputs = mapOf("reset" to LogicValue.LOW))
        assertEquals(LogicValue.HIGH, state.componentValues["ff"])
    }

    @Test
    fun boundedFeedbackReportsNonconvergenceWithoutHanging() {
        val document = CircuitDocument(components = listOf(
            CircuitComponent("not", ComponentKind.NOT, GridPoint(100, 100))),
            wires = listOf(WireSegment(GridPoint(70, 100), GridPoint(100, 100))))
        val state = CircuitSimulator(propagationLimit = 20).settle(document,
            previous = SimulationState(componentValues = mapOf("not" to LogicValue.HIGH)))
        assertTrue(state.oscillating)
    }

    private fun binaryGate(kind: ComponentKind): CircuitDocument {
        val gate = CircuitComponent("gate", kind, GridPoint(200, 100))
        val inputs = gate.inputPorts()
        return CircuitDocument(components = listOf(
            CircuitComponent("a", ComponentKind.INPUT, inputs[0]),
            CircuitComponent("b", ComponentKind.INPUT, inputs[1]), gate,
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(300, 100))),
            wires = listOf(WireSegment(gate.position, GridPoint(300, 100))))
    }

    private fun bit(number: Int): LogicValue = if (number == 0) LogicValue.LOW else LogicValue.HIGH
}
