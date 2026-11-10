package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorSimulationQueueTest {
    @Test
    fun rapidInputTransitionsAreEvaluatedInOrderAndObservedBeforeFinalState() = runBlocking {
        val input = CircuitComponent("input", ComponentKind.INPUT, GridPoint(0, 0))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(CircuitDocument(components = listOf(input))))))
        val id = state.document.components.single().id
        val observed = mutableListOf<LogicValue>()
        val jobs = mutableListOf<Job>()
        val controller = SimulationRunController(this, state::captureRunSnapshot,
            advance = { snapshot, step -> advanceSimulation(snapshot, step).state },
            isCurrent = state::isCurrentRunSnapshot, publish = state::publishRun,
            advanceWithObservations = { snapshot, step -> advanceSimulation(snapshot, step) },
            publishObservations = { _, samples -> samples.forEach { observed += it.state.componentValues.getValue(id) } })
        state.onSimulationRequested = { snapshot, _ ->
            if (controller.submitStimulus(snapshot.inputs, snapshot.vectorInputs, snapshot.events)?.let { jobs += it } != null)
                SimulationRequestResult.ACCEPTED else SimulationRequestResult.REJECTED
        }
        state.toggle(id)
        state.toggle(id)
        jobs.joinAll()
        assertTrue(LogicValue.HIGH in observed)
        assertEquals(LogicValue.LOW, observed.last())
        assertEquals(LogicValue.LOW, state.rootSimulation.componentValues[id])
        controller.close()
    }

    @Test
    fun oneFullCyclePropagatesBothEdgesAndLeavesEventAndHalfCycleSeparate() = runBlocking {
        val xml = """<project source="3.8.0"><lib name="0" desc="#Wiring"/>
            <main name="main"/><circuit name="main">
            <comp lib="0" name="Clock" loc="(100,100)"><a name="highDuration" val="1"/><a name="lowDuration" val="1"/></comp>
            <wire from="(100,100)" to="(300,100)"/>
            <comp lib="0" name="Pin" loc="(300,100)"><a name="label" val="Y"/>
            <a name="output" val="true"/><a name="facing" val="west"/></comp>
            </circuit></project>"""
        val state = EditorState(xml)
        val output = state.document.components.single { it.label == "Y" }.id
        val observed = mutableListOf<Pair<Long, LogicValue>>()
        val controller = SimulationRunController(this, state::captureRunSnapshot,
            advance = { snapshot, step -> advanceSimulation(snapshot, step).state },
            isCurrent = state::isCurrentRunSnapshot, publish = state::publishRun,
            advanceWithObservations = { snapshot, step -> advanceSimulation(snapshot, step) },
            publishObservations = { _, samples -> samples.forEach {
                it.state.componentValues[output]?.let { value -> observed += it.state.ticks to value }
            } })
        val commands = mutableListOf<Job>()
        state.onSimulationRequested = { _, step ->
            val job = controller.step(step)
            if (job == null) SimulationRequestResult.REJECTED
            else {
                commands += job
                SimulationRequestResult.ACCEPTED
            }
        }

        assertEquals(LogicValue.LOW, state.rootSimulation.componentValues[output])
        state.tick(fullCycle = true)
        commands.single().join()
        assertEquals(2L, state.rootSimulation.ticks)
        assertEquals(LogicValue.LOW, state.rootSimulation.componentValues[output])
        assertTrue(observed.indexOf(1L to LogicValue.HIGH) in 0 until observed.lastIndexOf(2L to LogicValue.LOW))

        controller.step(SimulationStep.EVENT)!!.join()
        assertEquals(2L, state.rootSimulation.ticks)
        state.tick()
        commands.last().join()
        assertEquals(3L, state.rootSimulation.ticks)
        assertEquals(LogicValue.HIGH, state.rootSimulation.componentValues[output])
        controller.close()
    }

    @Test
    fun projectEditsInvalidateAnInFlightResultInsteadOfPublishingIntoTheNewDocument() = runBlocking {
        val state = EditorState(null)
        val captured = state.captureRunSnapshot()
        state.newDocument()
        assertEquals(false, state.isCurrentRunSnapshot(captured))
        val current = state.rootSimulation
        state.publishRun(captured, captured.previous.copy(ticks = 99))
        assertEquals(current, state.rootSimulation)
    }
}
