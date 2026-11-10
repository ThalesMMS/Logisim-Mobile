package dev.logisim.mobile

import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.TraceRecorder
import dev.logisim.mobile.core.TraceSignal
import dev.logisim.mobile.core.TraceSignalReference
import java.io.File
import java.io.FileOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidTraceFileWriterTest {
    @Test
    fun appendsUtf8SignalsToExistingFileAcrossPauseAndResume() {
        val file = File.createTempFile("logisim-trace-", ".tsv")
        try {
            file.writeText("previous\n")
            val recorder = TraceRecorder(listOf(TraceSignal(TraceSignalReference("child", "bus"), "Saída", 4)), capacity = 1)
            val recording = TraceFileRecording(recorder) { error(it.resolve()) }
            recording.start(AndroidTraceFileWriter(file.name, FileOutputStream(file, true)), header = true)
            fun capture(time: Long, value: Int) = recorder.capture(SimulationState(circuitStates = mapOf(
                "child" to SimulationState(vectorValues = mapOf("bus" to LogicVector.of(4, value.toULong())))
            )), time)
            capture(1, 3)
            recording.pause()
            capture(2, 7)
            capture(3, 9)
            recording.resume()
            capture(4, 12)
            recording.stop()
            assertEquals("previous\ntime_events\tchild/bus:Saída[4]\n1\t0011\n3\t1001\n4\t1100\n", file.readText())
            assertEquals(1, recorder.frames.size)
            assertEquals(TraceFileRecordingState.STOPPED, recording.state)
        } finally { file.delete() }
    }
}
