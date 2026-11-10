package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalTraceTest {
    @Test
    fun tracesTheLiveCircuitAndPausesWithoutAdvancingIt() {
        val project = CircuitProject(listOf(DemoCircuits.andGate()))
        val signal = TraceSignals.fromProject(project).single { it.reference.componentId == "out" }
        val session = TraceSession(project, listOf(signal))
        assertEquals("0", session.recorder.frames.single().values.single()?.display)
        session.advance(mapOf("a" to LogicVector.high(1), "b" to LogicVector.high(1)))
        assertEquals("1", session.recorder.valueAt(signal.reference, session.state.propagationTime)?.display)
        assertEquals(TraceTimeUnit.EVENTS, session.recorder.timeUnit)
        assertEquals(session.state.propagationTime, session.recorder.frames.last().time)
        session.recorder.pause()
        session.advance(mapOf("a" to LogicVector.low(1)))
        assertEquals(1, session.state.ticks)
        assertTrue(session.recorder.frames.size > 2)
        session.reset()
        assertTrue(session.recorder.paused)
        assertEquals(session.state.propagationTime, session.recorder.frames.single().time)
        assertEquals("0", session.recorder.frames.single().values.single()?.display)
        session.recorder.resume()
        session.advance()
        assertEquals(1, session.state.ticks)
        session.changeProject(CircuitProject(listOf(DemoCircuits.flipFlop())))
        assertTrue(session.recorder.frames.isEmpty())
        assertTrue(session.recorder.signals.isEmpty())
    }

    @Test
    fun retainsOnlyTheConfiguredHistoryAndExportsMissingValuesExplicitly() {
        val selected = TraceSignal(TraceSignalReference("", "bus"), "Data, \"main\"", 8)
        val recorder = TraceRecorder(listOf(selected), capacity = 3)
        repeat(100_000) { time ->
            recorder.capture(SimulationState(vectorValues = mapOf("bus" to LogicVector.of(8, time.toULong()))), time.toLong())
        }
        assertEquals(3, recorder.frames.size)
        assertEquals(3, recorder.retainedValueCount)
        assertEquals(99_997, recorder.droppedFrames)
        assertEquals(99_997, recorder.firstTime)
        assertEquals("10011111", recorder.frames.last().values.single()?.display)
        recorder.capture(SimulationState(), 100_000)
        assertTrue(recorder.exportCsv().startsWith("time_events,\"bus:Data, \"\"main\"\"[8]\"\n"))
        assertTrue(recorder.exportCsv().endsWith("100000,<missing>\n"))
        assertFailsWith<IllegalArgumentException> { recorder.capture(SimulationState(), 99_999) }
    }

    @Test
    fun distinguishesTwoInstancesOfTheSameCircuitAndKeepsUnknownAndErrorBits() {
        val first = TraceSignal(TraceSignalReference("first", "reg"), "First", 4)
        val second = TraceSignal(TraceSignalReference("second", "reg"), "Second", 4)
        val recorder = TraceRecorder(listOf(first, second))
        val state = SimulationState(circuitStates = mapOf(
            "first" to SimulationState(vectorValues = mapOf("reg" to LogicVector.of(4, 3uL))),
            "second" to SimulationState(vectorValues = mapOf("reg" to LogicVector.fromBits(
                listOf(LogicValue.HIGH, LogicValue.UNKNOWN, LogicValue.ERROR, LogicValue.LOW)))),
        ))
        recorder.capture(state, 0)
        assertEquals(listOf("0011", "0E?1"), recorder.frames.single().values.map { it?.display })
        assertEquals("0E?1", recorder.valueAt(second.reference, 8)?.display)
        assertFalse(recorder.valueAt(first.reference, -1) != null)
        recorder.setCursors(4, 11)
        assertEquals(7, recorder.cursorDistance)
        recorder.select(listOf(first))
        assertEquals(null, recorder.cursorA)
        assertTrue(recorder.frames.isEmpty())
    }

    @Test
    fun discoversNestedInstancePathsInsteadOfConflatingCircuitDefinitions() {
        val leaf = CircuitDocument("leaf", listOf(CircuitComponent("pin", ComponentKind.OUTPUT, GridPoint(0, 0), "Data")))
        val child = CircuitDocument("child", listOf(CircuitComponent("nested", ComponentKind.SUBCIRCUIT,
            GridPoint(0, 0), circuitName = "leaf")))
        val main = CircuitDocument("main", listOf(
            CircuitComponent("one", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "child"),
            CircuitComponent("two", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "child"),
        ))
        val signals = TraceSignals.fromProject(CircuitProject(listOf(main, child, leaf)))
            .filter { it.reference.componentId == "pin" }
        assertEquals(listOf("one/nested", "two/nested"), signals.map { it.reference.instancePath })
    }

    @Test
    fun usesTheObservedTerminalWidthForSubcircuitOutputs() {
        val leaf = CircuitDocument("leaf", listOf(CircuitComponent("pin", ComponentKind.OUTPUT,
            GridPoint(0, 0), attributes = mapOf("width" to "4"))))
        val instance = CircuitComponent("instance", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "leaf",
            explicitPorts = listOf(ComponentPort("pin", GridPoint(0, 0), PortDirection.OUTPUT, 4)))
        val main = CircuitDocument("main", listOf(instance))
        val signal = TraceSignals.fromProject(CircuitProject(listOf(main, leaf))).first { it.reference.componentId == "instance" }
        assertEquals(4, signal.width)
    }

    @Test
    fun waveDromKeepsSelectedOrderClockEdgesAndBusData() {
        val clock = TraceSignal(TraceSignalReference("", "clock"), "Clock", 1)
        val bus = TraceSignal(TraceSignalReference("", "bus"), "Data \"A\"", 8, radix = TraceRadix.HEXADECIMAL)
        val recorder = TraceRecorder(listOf(clock, bus), capacity = 5)
        fun capture(time: Long, high: Boolean, data: LogicVector?) {
            val values = buildMap {
                put("clock", if (high) LogicVector.high(1) else LogicVector.low(1))
                if (data != null) put("bus", data)
            }
            recorder.capture(SimulationState(vectorValues = values), time)
        }
        capture(0, false, LogicVector.of(8, 42uL))
        capture(10, false, LogicVector.of(8, 0uL))
        capture(12, true, LogicVector.of(8, 0uL))
        capture(16, true, LogicVector.of(8, 255uL))
        capture(18, false, LogicVector.of(8, 3uL))
        capture(20, false, null)
        recorder.moveSignal(bus.reference, -1)

        val lanes = CompanionJsonCodec.parseObject(recorder.exportWaveDrom()).array("signal")
            .map { it as CompanionJsonObject }
        assertEquals(listOf("Data \"A\"", "Clock"), lanes.map { it.string("name") })
        // Like Evolution, a bus at 0 or all ones uses 0 or 1 without a data entry.
        assertEquals("0..12x", lanes[0].string("wave"))
        assertEquals(listOf("0x03"), lanes[0].array("data").map { (it as CompanionJsonString).value })
        assertEquals("01..0.", lanes[1].string("wave"))
        assertFalse("data" in lanes[1].fields)
    }

    @Test
    fun waveDromMarksSparseGapsWithoutDroppingTransitions() {
        val signal = TraceSignal(TraceSignalReference("", "clock"), "Clock", 1)
        val recorder = TraceRecorder(listOf(signal))
        recorder.capture(SimulationState(vectorValues = mapOf("clock" to LogicVector.low(1))), 0)
        recorder.capture(SimulationState(vectorValues = mapOf("clock" to LogicVector.high(1))), 1)
        recorder.capture(SimulationState(), Long.MAX_VALUE)
        val lane = CompanionJsonCodec.parseObject(recorder.exportWaveDrom()).array("signal").single() as CompanionJsonObject
        assertEquals("01|x", lane.string("wave"))
    }

    @Test
    fun windowZoomKeepsTheCursorAndClampsPanningAtTheRecordedRange() {
        val window = TraceWindow(20, 60)
        assertEquals(TraceWindow(30, 50), window.zoom(2.0, 40, 100))
        assertEquals(TraceWindow(0, 40), window.pan(-100, 100))
        assertEquals(TraceWindow(60, 100), window.pan(100, 100))
        assertFailsWith<IllegalArgumentException> { window.zoom(Double.NaN, 40, 100) }
    }
}
