package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExtraIoIntegrationTest {
    private val registry = ComponentCatalog.registry.withDefinitions(ExtraIoComponents.definitions())
    private val simulator = CircuitSimulator(registry = registry)

    @Test
    fun everyExtraComponentRoundTripsThroughTheProjectCodec() {
        val components = ExtraIoComponents.definitions().mapIndexed { index, definition ->
            registry.create(definition.kind, "extra-$index", GridPoint(100 + index * 300, 200))
        }
        val project = CircuitProject(listOf(CircuitDocument("extras", components)))
        val xml = CircCodec.writeProject(project, registry)
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project
        assertEquals(components.map { it.kind }, reopened.mainCircuit.components.map { it.kind })
        assertEquals(components.map { it.attributes }, reopened.mainCircuit.components.map { it.attributes })
        assertEquals(components.map { registry.layout(it) }, reopened.mainCircuit.components.map { registry.layout(it) })
        assertTrue(xml.contains("#BFH-Praktika") && xml.contains("#Input/Output-Extra"))
        assertFalse(xml.contains("ProgrammableGenerator"))
    }

    @Test
    fun aSwitchEventReachesTheOutputAndIsNotReplayedDuringSettling() {
        val document = CircuitDocument("switch", listOf(
            registry.create(ComponentKind.INPUT, "input", GridPoint(0, 0), mapOf("width" to "8")),
            registry.create(ExtraIoKinds.Switch, "switch", GridPoint(100, 0), mapOf("width" to "8")),
            registry.create(ComponentKind.OUTPUT, "output", GridPoint(160, 0), mapOf("width" to "8")),
        ), listOf(WireSegment(GridPoint(0, 0), GridPoint(80, 0)), WireSegment(GridPoint(100, 0), GridPoint(160, 0))))
        var state = simulator.settle(document, vectorInputs = mapOf("input" to LogicVector.of(8, 0xa5u)))
        assertEquals(LogicVector.unknown(8), state.vectorValues.getValue("output"))
        state = simulator.settle(document, state, events = mapOf("switch" to listOf(ExtraIoEvent.Toggle)))
        assertEquals(0xa5uL, state.vectorValues.getValue("output").toULongOrNull())
        assertTrue(assertIs<ExtraSwitchState>(state.componentStates.getValue("switch")).closed)
        state = simulator.settle(document, state)
        assertEquals(0xa5uL, state.vectorValues.getValue("output").toULongOrNull())
        assertFalse(state.oscillating)
    }

    @Test
    fun converterBusesConnectToLiteralDecimalDigits() {
        val binary = registry.create(ExtraIoKinds.BinaryToBcd, "bcd", GridPoint(100, 100), mapOf("binvalue" to "9"))
        val document = CircuitDocument("conversion", listOf(
            registry.create(ComponentKind.INPUT, "input", GridPoint(0, 100), mapOf("width" to "9")), binary,
            registry.create(ComponentKind.OUTPUT, "ones", GridPoint(220, 20), mapOf("width" to "4")),
            registry.create(ComponentKind.OUTPUT, "tens", GridPoint(160, 20), mapOf("width" to "4")),
            registry.create(ComponentKind.OUTPUT, "hundreds", GridPoint(100, 20), mapOf("width" to "4")),
        ), listOf(
            WireSegment(GridPoint(0, 100), GridPoint(70, 100)),
            WireSegment(GridPoint(220, 80), GridPoint(220, 20)),
            WireSegment(GridPoint(160, 80), GridPoint(160, 20)),
            WireSegment(GridPoint(100, 80), GridPoint(100, 20)),
        ))
        val state = simulator.settle(document, vectorInputs = mapOf("input" to LogicVector.of(9, 321u)))
        assertEquals(listOf(1uL, 2uL, 3uL), listOf("ones", "tens", "hundreds").map { state.vectorValues.getValue(it).toULongOrNull() })
        assertFalse(state.oscillating)
        assertTrue(state.diagnostics.isEmpty(), state.diagnostics.joinToString())
    }

    @Test
    fun oscilloscopeCapturesTheFinalPropagatedValueAtTheSameClockLevel() {
        val document = CircuitDocument("capture", listOf(
            registry.create(ComponentKind.INPUT, "clock", GridPoint(0, 0)),
            registry.create(ComponentKind.INPUT, "data", GridPoint(0, 30)),
            registry.create(ExtraIoKinds.Oscilloscope, "scope", GridPoint(100, 0), mapOf("inputs" to "1", "nState" to "4")),
        ), listOf(WireSegment(GridPoint(0, 0), GridPoint(100, 0)), WireSegment(GridPoint(0, 30), GridPoint(100, 30))))
        var state = simulator.settle(document)
        state = simulator.settle(document, state, inputs = mapOf("clock" to LogicValue.HIGH, "data" to LogicValue.HIGH))
        assertEquals(listOf(listOf(LogicValue.HIGH, LogicValue.HIGH)), assertIs<ExtraOscilloscopeState>(state.componentStates.getValue("scope")).samples)
        state = simulator.settle(document, state, inputs = mapOf("data" to LogicValue.LOW))
        assertEquals(listOf(listOf(LogicValue.HIGH, LogicValue.LOW)), assertIs<ExtraOscilloscopeState>(state.componentStates.getValue("scope")).samples)
        state = simulator.settle(document, state, events = mapOf("scope" to listOf(ExtraIoEvent.ClearTrace)))
        assertTrue(assertIs<ExtraOscilloscopeState>(state.componentStates.getValue("scope")).samples.isEmpty())
    }

    @Test
    fun timerWakeupsAdvanceWithoutLogicalClockTicksAndPauseFreezesTheCapacitor() {
        val document = CircuitDocument("timer", listOf(registry.create(ExtraIoKinds.Ne555, "timer", GridPoint(0, 0))))
        var state = simulator.settle(document, autoPropagating = true)
        assertEquals(10L, state.nextWakeupMillis)
        repeat(6) { state = simulator.settle(document, state, elapsedTimeMillis = (it + 1) * 100L, autoPropagating = true) }
        assertEquals(LogicValue.LOW, state.componentValues.getValue("timer"))
        assertEquals(0L, state.ticks)
        val voltage = assertIs<Ne555State>(state.componentStates.getValue("timer")).capacitorVoltage
        state = simulator.settle(document, state, elapsedTimeMillis = 10000, autoPropagating = false)
        assertEquals(null, state.nextWakeupMillis)
        assertEquals(voltage, assertIs<Ne555State>(state.componentStates.getValue("timer")).capacitorVoltage)
        assertFalse(state.oscillating)
    }

    @Test
    fun buzzerParametersAreExposedAsStateForNativePlayback() {
        val buzzer = registry.create(ExtraIoKinds.Buzzer, "buzzer", GridPoint(100, 0))
        val inputs = registry.layout(buzzer).inputs
        val values = mapOf("frequency" to "0x1b8", "enable" to "0x1", "volume" to "0x7f", "duty" to "0x80")
        val constants = inputs.map { port -> registry.create(ComponentKind.CONSTANT, port.name, port.position,
            mapOf("width" to port.width.toString(), "value" to values.getValue(port.name))) }
        val state = simulator.settle(CircuitDocument("sound", constants + buzzer))
        val tone = assertIs<BuzzerTone>(state.componentStates.getValue("buzzer"))
        assertTrue(tone.audible)
        assertEquals(440, tone.frequencyHz)
        assertEquals(32767.0, tone.amplitude)
        assertTrue(state.diagnostics.isEmpty(), state.diagnostics.joinToString())
    }
}
