package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.DemoCircuits
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.TraceRecorder
import dev.logisim.mobile.core.TraceSignals
import dev.logisim.mobile.core.TraceTimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceToolEventTest {
    @Test
    fun receivesIntermediateSamplesWithoutComposingItsPanel() {
        val project = CircuitProject(listOf(DemoCircuits.andGate()))
        val recorder = TraceRecorder()
        val tool = traceEditorTool(recorder = recorder)
        val simulator = CircuitSimulator()
        val initial = simulator.settle(project)
        tool.onSimulation!!.invoke(project, initial)
        recorder.select(TraceSignals.fromProject(project).filter { it.reference.componentId == "out" })
        val timestamps = mutableListOf<Long>()
        val final = simulator.settle(project, initial, vectorInputs = mapOf("a" to LogicVector.high(1), "b" to LogicVector.high(1)),
            observer = { snapshot, time ->
                timestamps += time
                tool.onSimulation!!.invoke(project, snapshot)
            })
        tool.onSimulation!!.invoke(project, final)

        assertEquals(TraceTimeUnit.EVENTS, recorder.timeUnit)
        assertEquals(timestamps.distinct(), recorder.frames.map { it.time })
        assertTrue(recorder.frames.size > 1)
        assertTrue(final.propagationTime > final.ticks)
        assertEquals(0L, final.ticks)
        assertEquals("0", recorder.frames.first().values.single()?.display)
        assertEquals("1", recorder.frames.last().values.single()?.display)
        assertTrue(recorder.exportCsv().startsWith("time_events,"))
    }
}
