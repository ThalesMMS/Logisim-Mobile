package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.DemoCircuits
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.TraceRadix
import dev.logisim.mobile.core.TraceRecorder
import dev.logisim.mobile.core.TraceSignal
import dev.logisim.mobile.core.TraceSignalReference
import dev.logisim.mobile.core.TraceSignals
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraceFileRecordingTest {
    @Test
    fun reorderingKeepsSamplesWithTheirSignalsAndOrdersNewTsvColumns() {
        val a = TraceSignal(TraceSignalReference("", "a"), "A", 1)
        val y = TraceSignal(TraceSignalReference("", "y"), "Y", 1)
        val recorder = TraceRecorder(listOf(a, y))
        recorder.capture(SimulationState(vectorValues = mapOf("a" to LogicVector.low(1), "y" to LogicVector.high(1))), 2)
        recorder.setCursors(2, 3)
        val recording = TraceFileRecording(recorder) { error("Unexpected failure: $it") }
        val firstFile = MemoryWriter()
        recording.start(firstFile, header = true)
        recording.stop()

        recorder.setRadix(y.reference, TraceRadix.HEXADECIMAL)
        assertFalse(recorder.moveSignal(a.reference, -1))
        assertTrue(recorder.moveSignal(y.reference, -1))
        assertEquals(listOf("Y", "A"), recorder.signals.map { it.label })
        assertEquals(listOf("1", "0"), recorder.frames.single().values.map { it?.display })
        assertEquals("0", recorder.valueAt(a.reference, 2)?.display)
        assertEquals("1", recorder.valueAt(y.reference, 2)?.display)
        assertEquals(2L, recorder.cursorA)
        assertEquals(3L, recorder.cursorB)

        val secondFile = MemoryWriter()
        recording.start(secondFile, header = true)
        recorder.capture(SimulationState(vectorValues = mapOf("a" to LogicVector.high(1), "y" to LogicVector.low(1))), 3)
        recording.stop()

        assertEquals("time_events\ta:A[1]\ty:Y[1]\n2\t0\t1\n", firstFile.text)
        assertEquals("time_events\ty:Y[1]\ta:A[1]\n2\t0x1\t0\n3\t0x0\t1\n", secondFile.text)
        assertEquals("time_events,\"y:Y[1]\",\"a:A[1]\"\n2,0x1,0\n3,0x0,1\n", recorder.exportCsv())
    }

    @Test
    fun anOpenTsvNeverWritesRowsInAChangedColumnOrder() {
        val a = TraceSignal(TraceSignalReference("", "a"), "A", 1)
        val y = TraceSignal(TraceSignalReference("", "y"), "Y", 1)
        val recorder = TraceRecorder(listOf(a, y))
        val state = SimulationState(vectorValues = mapOf("a" to LogicVector.low(1), "y" to LogicVector.high(1)))
        recorder.capture(state, 2)
        val errors = mutableListOf<String>()
        val recording = TraceFileRecording(recorder) { errors += it.key }
        val file = MemoryWriter()
        recording.start(file, header = true)

        // An out-of-band change must not write a row under the old header.
        assertTrue(recorder.moveSignal(y.reference, -1))
        recorder.capture(state, 3)

        assertEquals("time_events\ta:A[1]\ty:Y[1]\n2\t0\t1\n", file.text)
        assertEquals(listOf("trace_file_signals_changed"), errors)
        assertEquals(TraceFileRecordingState.STOPPED, recording.state)
    }

    @Test
    fun writesKnownSequenceAcrossPauseResumeAndHistoryEviction() {
        val recorder = TraceRecorder(listOf(
            TraceSignal(TraceSignalReference("child", "bus"), "Data\tbus", 4),
        ), capacity = 2)
        val writer = MemoryWriter("previous\n")
        val recording = TraceFileRecording(recorder) { error("Unexpected failure: $it") }
        recording.start(writer, header = true)
        fun capture(time: Long, value: Int) = recorder.capture(SimulationState(circuitStates = mapOf(
            "child" to SimulationState(vectorValues = mapOf("bus" to LogicVector.of(4, value.toULong())))
        )), time)
        capture(1, 3)
        capture(2, 5)
        recording.pause()
        capture(3, 7)
        recording.resume()
        capture(4, 9)
        capture(5, 12)
        recording.stop()
        assertEquals("previous\ntime_events\t\"child/bus:Data\tbus[4]\"\n1\t0011\n2\t0101\n3\t0111\n4\t1001\n5\t1100\n", writer.text)
        assertEquals(2, recorder.frames.size)
        assertEquals(3, recorder.droppedFrames)
        assertEquals(TraceFileRecordingState.STOPPED, recording.state)
        assertEquals(1, writer.closeCount)
    }

    @Test
    fun legacyTsvWritesOnlySignalColumnsAndChanges() {
        val recorder = TraceRecorder(listOf(
            TraceSignal(TraceSignalReference("", "out"), "Y", 1),
            TraceSignal(TraceSignalReference("child", "bus"), "Bus", 8),
        ))
        val recording = TraceFileRecording(recorder) { error("Unexpected failure: $it") }
        val writer = MemoryWriter("previous\n")
        fun capture(time: Long, out: LogicVector, bus: LogicVector?) {
            val children = if (bus == null) emptyMap()
                else mapOf("child" to SimulationState(vectorValues = mapOf("bus" to bus)))
            recorder.capture(SimulationState(vectorValues = mapOf("out" to out), circuitStates = children), time)
        }
        recording.start(writer, header = true, format = TraceFileFormat.LOGISIM_2_7_1)
        assertEquals("previous\n", writer.text)
        val known = LogicVector.of(8, 0xa5uL)
        capture(1, LogicVector.high(1), known)
        capture(2, LogicVector.high(1), known)
        capture(3, LogicVector.low(1), known)
        recording.pause()
        capture(4, LogicVector.low(1), known)
        recording.resume()
        capture(5, LogicVector.unknown(1), LogicVector.unknown(8))
        capture(6, LogicVector.error(1), null)
        recording.stop()

        assertEquals("previous\nY\tchild.Bus\n1\t1010 0101\n0\t1010 0101\nx\txxxx xxxx\nE\t\n", writer.text)
        assertEquals(1, writer.closeCount)
    }

    @Test
    fun fileFormatIsSelectedIndependentlyForEachRecording() {
        val recorder = TraceRecorder(listOf(TraceSignal(TraceSignalReference("", "out"), "Y", 1)))
        val recording = TraceFileRecording(recorder) { error("Unexpected failure: $it") }
        val legacy = MemoryWriter()
        val timestamped = MemoryWriter()
        val low = SimulationState(vectorValues = mapOf("out" to LogicVector.low(1)))
        recording.start(legacy, header = false, format = TraceFileFormat.LOGISIM_2_7_1)
        recorder.capture(low, 1)
        recording.stop()
        recording.start(timestamped, header = true)
        recorder.capture(low, 2)
        recording.stop()

        assertEquals("0\n", legacy.text)
        assertEquals("time_events\tout:Y[1]\n1\t0\n2\t0\n", timestamped.text)
    }

    @Test
    fun legacyHeaderUsesOriginalDescriptionsForLabeledAndUnlabeledInstances() {
        val leaf = CircuitDocument("LegacyInverter", listOf(
            CircuitComponent("out", ComponentKind.OUTPUT, GridPoint(300, 100), label = "Y"),
        ))
        val middle = CircuitDocument("Middle", listOf(
            CircuitComponent("inner", ComponentKind.SUBCIRCUIT, GridPoint(150, 180), circuitName = leaf.name),
        ))
        val root = CircuitDocument("Root", listOf(
            CircuitComponent("single", ComponentKind.SUBCIRCUIT, GridPoint(100, 200), label = "Stage", circuitName = leaf.name),
            CircuitComponent("outer", ComponentKind.SUBCIRCUIT, GridPoint(200, 200), label = "Outer/Stage", circuitName = middle.name),
        ))
        val signals = TraceSignals.fromProject(CircuitProject(listOf(root, middle, leaf))).filter { it.label == "Y" }
        val recorder = TraceRecorder(signals)
        recorder.capture(SimulationState(circuitStates = mapOf(
            "single" to SimulationState(vectorValues = mapOf("out" to LogicVector.low(1))),
            "outer/inner" to SimulationState(vectorValues = mapOf("out" to LogicVector.high(1))),
        )), 1)
        val recording = TraceFileRecording(recorder) { error("Unexpected failure: $it") }
        val legacy = MemoryWriter()
        recording.start(legacy, header = true, format = TraceFileFormat.LOGISIM_2_7_1)
        recording.stop()
        val timestamped = MemoryWriter()
        recording.start(timestamped, header = true)
        recording.stop()

        assertEquals("Stage.Y\tOuter/Stage..LegacyInverter(150,180).Y\n0\t1\n", legacy.text)
        assertEquals("time_events\tsingle/out:Y[1]\touter/inner/out:Y[1]\n1\t0\t1\n", timestamped.text)
    }

    @Test
    fun flushesPendingRowsBeforeClosingAndDoesNotRepeatIdenticalSamples() {
        val recorder = TraceRecorder(listOf(TraceSignal(TraceSignalReference("", "out"), "Out", 1)))
        val writer = MemoryWriter(delayed = true)
        val recording = TraceFileRecording(recorder) { error("Unexpected failure: $it") }
        recording.start(writer, header = false)
        recorder.capture(SimulationState(vectorValues = mapOf("out" to LogicVector.low(1))), 1)
        recorder.capture(SimulationState(vectorValues = mapOf("out" to LogicVector.low(1))), 1)
        recorder.capture(SimulationState(vectorValues = mapOf("out" to LogicVector.high(1))), 2)
        recording.stop()
        assertEquals(TraceFileRecordingState.STOPPING, recording.state)
        assertEquals(0, writer.closeCount)
        writer.complete()
        assertEquals(0, writer.closeCount)
        writer.complete()
        assertEquals("1\t0\n2\t1\n", writer.text)
        assertEquals(1, writer.closeCount)
        assertEquals(TraceFileRecordingState.STOPPED, recording.state)
    }

    @Test
    fun recordsIntermediateSimulationEventsThroughTheToolEntryPoint() {
        val project = CircuitProject(listOf(DemoCircuits.andGate()))
        val recorder = TraceRecorder()
        val recording = TraceFileRecording(recorder) { error("Unexpected failure: $it") }
        val tool = traceEditorTool(recorder = recorder, recording = recording)
        val simulator = CircuitSimulator()
        val initial = simulator.settle(project)
        tool.onSimulation!!.invoke(project, initial)
        recorder.select(TraceSignals.fromProject(project).filter { it.reference.componentId == "out" })
        val writer = MemoryWriter()
        recording.start(writer, header = false)
        val final = simulator.settle(project, initial,
            vectorInputs = mapOf("a" to LogicVector.high(1), "b" to LogicVector.high(1)),
            observer = { snapshot, _ -> tool.onSimulationObservation!!.invoke(project, snapshot) })
        tool.onSimulation!!.invoke(project, final)
        recording.stop()
        assertEquals(recorder.frames.joinToString("") { "${it.time}\t${it.values.single()?.display}\n" }, writer.text)
        assertTrue(writer.text.lines().filter { it.isNotBlank() }.size > 1)
    }

    @Test
    fun legacyTsvSkipsTheTransientPulseStillRecordedInTimestampedTsv() {
        val imported = CircCodec.readProject("""
            <project source="2.7.1" version="1.0">
              <lib desc="#Wiring" name="0"/>
              <lib desc="#Gates" name="1"/>
              <main name="Hazard"/>
              <circuit name="Hazard">
                <wire from="(100,100)" to="(140,100)"/>
                <wire from="(140,100)" to="(340,100)"/>
                <wire from="(140,100)" to="(140,140)"/>
                <wire from="(140,140)" to="(190,140)"/>
                <wire from="(220,140)" to="(250,140)"/>
                <wire from="(280,140)" to="(340,140)"/>
                <wire from="(400,120)" to="(500,120)"/>
                <comp lib="1" loc="(220,140)" name="NOT Gate"/>
                <comp lib="1" loc="(280,140)" name="NOT Gate"/>
                <comp lib="1" loc="(400,120)" name="XOR Gate"><a name="inputs" val="2"/></comp>
                <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="A"/></comp>
                <comp lib="0" loc="(500,120)" name="Pin">
                  <a name="facing" val="west"/><a name="output" val="true"/><a name="label" val="Y"/>
                </comp>
              </circuit>
            </project>
        """.trimIndent()) as CircProjectImportResult.Success
        val project = imported.project
        val input = project.mainCircuit.components.single { it.label == "A" }.id
        val output = TraceSignals.fromProject(project).single { it.label == "Y" }
        val initial = CircuitSimulator().settle(project)
        val advance = advanceSimulation(
            SimulationRunSnapshot(project, initial, vectorInputs = mapOf(input to LogicVector.high(1))),
            SimulationStep.SETTLE,
            autoPropagating = true,
        )

        fun record(format: TraceFileFormat): String {
            val recorder = TraceRecorder()
            val recording = TraceFileRecording(recorder) { error("Unexpected failure: $it") }
            val tool = traceEditorTool(recorder = recorder, recording = recording)
            tool.onSimulation!!.invoke(project, initial)
            recorder.select(listOf(output))
            tool.onSimulation!!.invoke(project, initial)
            val writer = MemoryWriter()
            recording.start(writer, header = true, format = format)
            advance.observations.forEach { tool.onSimulationObservation!!.invoke(project, it.state) }
            tool.onSimulation!!.invoke(project, advance.state)
            recording.stop()
            return writer.text
        }

        assertEquals("Y\n0\n", record(TraceFileFormat.LOGISIM_2_7_1))
        val timestamped = record(TraceFileFormat.TIMESTAMPED)
        val values = timestamped.lineSequence().filter(String::isNotEmpty).drop(1)
            .map { it.substringAfter('\t') }.toList()
        assertEquals(listOf("0", "1", "0"), values.fold(emptyList<String>()) { changes, value ->
            if (changes.lastOrNull() == value) changes else changes + value
        })
    }

    @Test
    fun formatsEachColumnInItsOwnBaseWithoutClearingTheHistory() {
        val binary = TraceSignal(TraceSignalReference("", "bin"), "Binary", 8)
        val decimal = TraceSignal(TraceSignalReference("", "dec"), "Decimal", 8)
        val hexadecimal = TraceSignal(TraceSignalReference("", "hex"), "Hex", 8)
        val signals = listOf(binary, decimal, hexadecimal)
        val recorder = TraceRecorder(signals)
        fun capture(time: Long, value: LogicVector) {
            recorder.capture(SimulationState(vectorValues = signals.associate { it.reference.componentId to value }), time)
        }

        capture(1, LogicVector.of(8, 0xa5uL))
        recorder.setCursors(1, null)
        recorder.setRadix(decimal.reference, TraceRadix.DECIMAL)
        recorder.setRadix(hexadecimal.reference, TraceRadix.HEXADECIMAL)
        assertEquals(1L, recorder.cursorA)
        assertEquals(1L, recorder.frames.single().time)

        val timestamped = MemoryWriter()
        val recording = TraceFileRecording(recorder) { error("Unexpected failure: $it") }
        recording.start(timestamped, header = false)
        capture(2, LogicVector.unknown(8))
        capture(3, LogicVector.fromMasks(8, 0uL, 0x10uL, 0x01uL))
        recording.stop()

        assertEquals("1\t10100101\t165\t0xA5\n2\t????????\t????????\t0x??\n3\t000?000E\t000?000E\t0x?E\n", timestamped.text)

        recorder.clear()
        val original = MemoryWriter()
        recording.start(original, header = false, format = TraceFileFormat.LOGISIM_2_7_1)
        capture(1, LogicVector.of(8, 0xa5uL))
        capture(2, LogicVector.unknown(8))
        capture(3, LogicVector.fromMasks(8, 0uL, 0x10uL, 0x01uL))
        capture(4, LogicVector.fromMasks(8, 0uL, 0x08uL, 0x01uL))
        recording.stop()

        assertEquals("1010 0101\t165\ta5\nxxxx xxxx\t???\txx\n000x 000E\tError\txE\n0000 x00E\tError\t0x\n", original.text)
        assertEquals(
            listOf("1,10100101,165,0xA5", "2,????????,????????,0x??", "3,000?000E,000?000E,0x?E", "4,0000?00E,0000?00E,0x0E"),
            recorder.exportCsv().lineSequence().drop(1).filter(String::isNotEmpty).toList(),
        )
    }

    @Test
    fun closesTheFileAndReportsWriteFailures() {
        val recorder = TraceRecorder(listOf(TraceSignal(TraceSignalReference("", "out"), "Out", 1)))
        val errors = mutableListOf<String>()
        val writer = MemoryWriter(fails = true)
        val recording = TraceFileRecording(recorder) { errors += it.key }
        recording.start(writer, header = true)
        assertEquals(listOf("trace_file_write_failed"), errors)
        assertEquals(TraceFileRecordingState.STOPPED, recording.state)
        assertEquals(1, writer.closeCount)
        assertTrue(recording.failure != null)
    }

    @Test
    fun recordsUnknownErrorAndMissingValues() {
        val recorder = TraceRecorder(listOf(TraceSignal(TraceSignalReference("", "out"), "Out", 2)))
        val writer = MemoryWriter()
        val recording = TraceFileRecording(recorder) { error("Unexpected failure: $it") }
        recording.start(writer, header = false)
        recorder.capture(SimulationState(vectorValues = mapOf("out" to LogicVector.fromBits(listOf(LogicValue.UNKNOWN, LogicValue.ERROR)))), 1)
        recorder.capture(SimulationState(), 2)
        recording.stop()
        assertEquals("1\tE?\n2\t<missing>\n", writer.text)
    }

    @Test
    fun stopsWhenTheDocumentChangesOrTheSimulationClockRestarts() {
        val recorder = TraceRecorder(listOf(TraceSignal(TraceSignalReference("", "out"), "Out", 1)))
        val failures = mutableListOf<String>()
        val recording = TraceFileRecording(recorder) { failures += it.key }
        val first = MemoryWriter()
        recording.bindDocument(1)
        recording.start(first, header = false)
        recorder.capture(SimulationState(), 4)
        recording.bindDocument(2)
        assertEquals(1, first.closeCount)
        assertEquals(TraceFileRecordingState.STOPPED, recording.state)
        val second = MemoryWriter()
        recording.start(second, header = false)
        recorder.clear()
        recorder.capture(SimulationState(), 0)
        assertEquals(listOf("trace_file_clock_reset"), failures)
        assertEquals("4\t<missing>\n", second.text)
        assertEquals(1, second.closeCount)
    }

    @Test
    fun stopsWithAVisibleFailureWhenTheProviderCannotKeepUp() {
        val recorder = TraceRecorder(listOf(TraceSignal(TraceSignalReference("", "out"), "Out", 1)), capacity = 1)
        val failures = mutableListOf<String>()
        val recording = TraceFileRecording(recorder) { failures += it.key }
        val writer = MemoryWriter(delayed = true)
        recording.start(writer, header = false)
        repeat(60_000) { recorder.capture(SimulationState(), it.toLong()) }
        assertEquals(listOf("trace_file_queue_full"), failures)
        assertEquals(TraceFileRecordingState.STOPPING, recording.state)
        assertEquals(0, writer.closeCount)
        writer.complete()
        assertEquals(TraceFileRecordingState.STOPPED, recording.state)
        assertEquals(1, writer.closeCount)
    }

    @Test
    fun noticesClockResetWhileFileRecordingIsPaused() {
        val recorder = TraceRecorder(listOf(TraceSignal(TraceSignalReference("", "out"), "Out", 1)))
        val failures = mutableListOf<String>()
        val recording = TraceFileRecording(recorder) { failures += it.key }
        val writer = MemoryWriter()
        recording.start(writer, header = false)
        recorder.capture(SimulationState(), 4)
        recording.pause()
        recorder.capture(SimulationState(), 6)
        recorder.clear()
        recorder.capture(SimulationState(), 5)
        assertEquals(listOf("trace_file_clock_reset"), failures)
        assertEquals(TraceFileRecordingState.STOPPED, recording.state)
        assertEquals("4\t<missing>\n", writer.text)
    }

    private class MemoryWriter(initial: String = "", private val delayed: Boolean = false, private val fails: Boolean = false) : TraceFileWriter {
        override val name = "signals.tsv"
        var text = initial
        var closeCount = 0
        private var completion: (() -> Unit)? = null
        override fun append(text: String, onResult: (AssetActionResult) -> Unit) {
            val finish = {
                if (fails) onResult(AssetActionResult.Failure(message("trace_file_write_failed")))
                else { this.text += text; onResult(AssetActionResult.Completed) }
            }
            if (delayed) completion = finish else finish()
        }
        fun complete() { val pending = completion!!; completion = null; pending() }
        override fun close(onResult: (AssetActionResult) -> Unit) { closeCount++; onResult(AssetActionResult.Completed) }
    }
}
