package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.IoInputEvent
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.TclComponentEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class SimulationRunControllerTest {
    @Test
    fun navigationPausesAutomaticRunningWithoutDiscardingAnAcceptedRelease() = runBlocking<Unit> {
        acceptedReleaseSurvivesControlChange(resume = false) { it.pauseContinuousExecution() }
    }

    @Test
    fun frequencyChangeKeepsAnAcceptedReleaseAndResumesAtTheNewRate() = runBlocking<Unit> {
        acceptedReleaseSurvivesControlChange(resume = true) { assertTrue(it.configureFrequency(2.0)) }
    }

    @Test
    fun disablingTicksDuringAnAcceptedReleaseResumesWithoutSchedulingATick() = runBlocking<Unit> {
        acceptedReleaseSurvivesControlChange(resume = true, resumedWait = null) { it.setAutomaticTicks(false) }
    }

    private suspend fun acceptedReleaseSurvivesControlChange(
        resume: Boolean,
        resumedWait: Long? = if (resume) 500L else null,
        change: (SimulationRunController) -> Unit,
    ) {
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject("""<project source="5.1.0-dev">
            <lib name="0" desc="#I/O"/><lib name="1" desc="#Wiring"/><main name="Held"/><circuit name="Held">
            <comp lib="0" name="Button" loc="(100,100)"><a name="label" val="BTN"/><a name="press" val="active"/></comp>
            <comp lib="1" name="Pin" loc="(180,100)"><a name="label" val="OUT"/><a name="type" val="output"/><a name="facing" val="west"/><a name="width" val="1"/></comp>
            <wire from="(100,100)" to="(180,100)"/></circuit></project>""")).project
        val button = project.mainCircuit.components.single { it.label == "BTN" }.id
        val output = project.mainCircuit.components.single { it.label == "OUT" }.id
        val release = CompletableDeferred<Unit>()
        val harness = Harness(projectOverride = project) { snapshot, step ->
            if (snapshot.events.values.flatten().contains(IoInputEvent.Press(false))) release.await()
            advanceSimulation(snapshot, step).state
        }
        try {
            harness.controller.submitStimulus(emptyMap(), emptyMap(), mapOf(button to listOf(IoInputEvent.Press(true))))!!.join()
            assertEquals("1", harness.state.vectorValues[output]?.display)
            assertTrue(harness.controller.start())
            val accepted = assertNotNull(harness.controller.submitStimulus(emptyMap(), emptyMap(), mapOf(button to listOf(IoInputEvent.Press(false)))))
            assertEquals(SimulationRunPhase.STEPPING, harness.controller.status.value.phase)
            change(harness.controller)
            release.complete(Unit)
            accepted.join()
            assertEquals("0", harness.state.vectorValues[output]?.display)
            assertEquals(0L, harness.state.ticks)
            assertEquals(if (resume) SimulationRunPhase.RUNNING else SimulationRunPhase.PAUSED, harness.controller.status.value.phase)
            assertEquals(listOfNotNull(250L, resumedWait), harness.waits)
        } finally { release.complete(Unit); harness.close() }
    }

    @Test
    fun continuousExecutionUsesOneLoopAndPublishesRealClockValues() = runBlocking<Unit> {
        val harness = Harness()
        try {
            assertTrue(harness.controller.start())
            assertTrue(harness.controller.start())
            assertEquals(listOf(250L), harness.waits)
            harness.permits.send(Unit)
            yield()
            assertEquals(1L, harness.state.ticks)
            assertEquals(LogicValue.HIGH, harness.state.componentValues["clock"])
            harness.permits.send(Unit)
            yield()
            assertEquals(2L, harness.state.ticks)
            assertEquals(LogicValue.LOW, harness.state.componentValues["clock"])
            harness.controller.pause()
            harness.permits.send(Unit)
            yield()
            assertEquals(2L, harness.state.ticks)
            assertEquals(SimulationRunPhase.PAUSED, harness.controller.status.value.phase)
        } finally { harness.close() }
    }

    @Test
    fun measuredRateUsesCompletedAutomaticTicksAndStartsFreshAfterPause() = runBlocking<Unit> {
        val clock = TestTimeSource()
        val harness = Harness(tickRateTimeSource = clock)
        val controller = harness.controller
        try {
            controller.start()
            clock += 500.milliseconds
            assertNull(controller.status.value.measuredTicksPerSecond)
            harness.permits.send(Unit)
            yield()
            assertNull(controller.status.value.measuredTicksPerSecond)
            clock += 500.milliseconds
            harness.permits.send(Unit)
            yield()
            assertEquals(4.0, controller.status.value.frequencyHz)
            assertEquals(2.0, controller.status.value.measuredTicksPerSecond)

            controller.pause()
            assertNull(controller.status.value.measuredTicksPerSecond)
            clock += 10.seconds
            assertNotNull(controller.step(SimulationStep.TICK)).join()
            assertNull(controller.status.value.measuredTicksPerSecond)

            controller.start()
            clock += 250.milliseconds
            harness.permits.send(Unit)
            yield()
            assertNull(controller.status.value.measuredTicksPerSecond)
            clock += 250.milliseconds
            harness.permits.send(Unit)
            yield()
            assertEquals(4.0, controller.status.value.measuredTicksPerSecond)

            controller.setAutomaticTicks(false)
            assertEquals(SimulationRunPhase.RUNNING, controller.status.value.phase)
            assertNull(controller.status.value.measuredTicksPerSecond)
        } finally { harness.close() }
    }

    @Test
    fun propagationWithoutAutomaticTicksKeepsManualStepAndResumesOneClockLoop() = runBlocking<Unit> {
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject("""<project source="2.7.1">
            <lib name="0" desc="#Wiring"/><lib name="1" desc="#Gates"/><main name="main"/>
            <circuit name="main">
                <comp lib="0" name="Clock" loc="(20,20)"/>
                <comp lib="0" name="Pin" loc="(100,100)"><a name="label" val="A"/></comp>
                <comp lib="1" name="NOT Gate" loc="(200,100)"/>
                <comp lib="0" name="Pin" loc="(300,100)"><a name="label" val="Y"/><a name="output" val="true"/><a name="facing" val="west"/></comp>
                <wire from="(100,100)" to="(170,100)"/><wire from="(200,100)" to="(300,100)"/>
            </circuit></project>""")).project
        val input = project.mainCircuit.components.single { it.label == "A" }.id
        val output = project.mainCircuit.components.single { it.label == "Y" }.id
        val harness = Harness(projectOverride = project) { snapshot, step -> advanceSimulation(snapshot, step).state }
        try {
            harness.controller.start()
            harness.controller.setAutomaticTicks(false)
            assertEquals("1", harness.state.vectorValues[output]?.display)
            assertNotNull(harness.controller.submitStimulus(mapOf(input to LogicValue.HIGH), emptyMap())).join()
            assertEquals("0", harness.state.vectorValues[output]?.display)
            assertEquals(SimulationRunPhase.RUNNING, harness.controller.status.value.phase)
            assertEquals(0L, harness.state.ticks)
            assertEquals(listOf(250L), harness.waits)
            assertEquals(listOf(0L), harness.published.map { it.ticks })

            assertNotNull(harness.controller.step(SimulationStep.EVENT)).join()
            assertEquals(0L, harness.state.ticks)
            assertEquals(SimulationStep.EVENT, harness.controller.status.value.lastStep)
            assertNotNull(harness.controller.step(SimulationStep.TICK)).join()
            assertEquals(1L, harness.state.ticks)

            harness.controller.start()
            assertEquals(listOf(250L), harness.waits)
            harness.controller.setAutomaticTicks(true)
            harness.controller.setAutomaticTicks(true)
            assertEquals(listOf(250L, 250L), harness.waits)
            harness.permits.send(Unit)
            yield()
            assertEquals(2L, harness.state.ticks)
            assertEquals(listOf(250L, 250L, 250L), harness.waits)
        } finally { harness.close() }
    }

    @Test
    fun timedComponentWakeupDoesNotTickWhenAutomaticTicksAreDisabled() = runBlocking<Unit> {
        val steps = mutableListOf<SimulationStep>()
        val harness = Harness { snapshot, step ->
            steps += step
            snapshot.previous.copy(nextWakeupMillis = null)
        }
        try {
            harness.state = harness.state.copy(nextWakeupMillis = 40L)
            harness.controller.setAutomaticTicks(false)
            harness.controller.start()
            harness.controller.configureFrequency(2.0)
            assertEquals(listOf(40L), harness.waits)
            harness.permits.send(Unit)
            yield()
            assertEquals(listOf(SimulationStep.SETTLE), steps)
            assertEquals(0L, harness.state.ticks)
            assertEquals(listOf(40L), harness.waits)
        } finally { harness.close() }
    }

    @Test
    fun manualTickAndResetCancelContinuousExecution() = runBlocking<Unit> {
        val harness = Harness()
        try {
            harness.controller.start()
            harness.controller.step(SimulationStep.TICK)!!.join()
            assertEquals(1L, harness.state.ticks)
            assertEquals(SimulationRunPhase.PAUSED, harness.controller.status.value.phase)
            harness.controller.step(SimulationStep.RESET)!!.join()
            assertEquals(0L, harness.state.ticks)
            assertEquals(LogicValue.LOW, harness.state.componentValues["clock"])
            assertEquals(0L, harness.controller.status.value.completedSteps)
        } finally { harness.close() }
    }

    @Test
    fun manualClicksWaitForEarlierComputationAndCaptureItsPublishedState() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val simulator = CircuitSimulator()
        val harness = Harness { snapshot, _ ->
            if (snapshot.previous.ticks == 0L) gate.await()
            simulator.settle(snapshot.project, snapshot.previous, tick = true)
        }
        try {
            val first = harness.controller.step(SimulationStep.TICK)!!
            val second = harness.controller.step(SimulationStep.TICK)!!
            val third = harness.controller.step(SimulationStep.TICK)!!
            assertEquals(3, harness.controller.status.value.pendingSteps)
            gate.complete(Unit)
            first.join(); second.join(); third.join()
            assertEquals(listOf(1L, 2L, 3L), harness.published.map { it.ticks })
            assertEquals(3L, harness.state.ticks)
            assertEquals(LogicValue.HIGH, harness.state.componentValues["clock"])
            assertEquals(0, harness.controller.status.value.pendingSteps)
        } finally { gate.complete(Unit); harness.close() }
    }

    @Test
    fun cancellingOneTclRequestDiscardsItsInFlightTickAndKeepsOtherCommands() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val simulator = CircuitSimulator()
        val automatic = TclComponentEvent.ApplyTick(generation = 1, pendingRequests = 1, authorization = "approved")
        val harness = Harness { snapshot, _ ->
            if (snapshot.events.values.any { it.singleOrNull() === automatic }) {
                entered.complete(Unit)
                release.await()
            }
            simulator.settle(snapshot.project, snapshot.previous, tick = true)
        }
        try {
            val cancelled = harness.controller.submitStimulus(emptyMap(), emptyMap(), mapOf("tcl" to listOf(automatic)))!!
            entered.await()
            val manual = harness.controller.step(SimulationStep.TICK)!!
            harness.controller.cancelEvent(automatic)
            assertFalse(harness.controller.isEventPending(automatic))
            release.complete(Unit)
            cancelled.join()
            manual.join()
            assertEquals(listOf(1L), harness.published.map { it.ticks })
            assertEquals(0, harness.controller.status.value.pendingSteps)
        } finally { release.complete(Unit); harness.close() }
    }

    @Test
    fun resetCancelsQueuedManualCommandsAndCannotBeOverwrittenByOldWork() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val simulator = CircuitSimulator()
        val harness = Harness { snapshot, step ->
            if (step == SimulationStep.RESET) simulator.settle(snapshot.project)
            else {
                withContext(NonCancellable) { gate.await() }
                simulator.settle(snapshot.project, snapshot.previous, tick = true)
            }
        }
        try {
            val first = harness.controller.step(SimulationStep.TICK)!!
            val second = harness.controller.step(SimulationStep.TICK)!!
            val reset = harness.controller.step(SimulationStep.RESET)!!
            gate.complete(Unit)
            first.join(); second.join(); reset.join()
            assertTrue(first.isCancelled)
            assertTrue(second.isCancelled)
            assertEquals(listOf(0L), harness.published.map { it.ticks })
            assertEquals(SimulationStep.RESET, harness.controller.status.value.lastStep)
        } finally { gate.complete(Unit); harness.close() }
    }

    @Test
    fun eventCommandIsPassedToTheDriverWithoutBecomingATick() = runBlocking<Unit> {
        val steps = mutableListOf<SimulationStep>()
        val harness = Harness { snapshot, step ->
            steps += step
            snapshot.previous
        }
        try {
            harness.controller.step(SimulationStep.EVENT)!!.join()
            assertEquals(listOf(SimulationStep.EVENT), steps)
            assertEquals(0L, harness.state.ticks)
            assertEquals(SimulationStep.EVENT, harness.controller.status.value.lastStep)
        } finally { harness.close() }
    }

    @Test
    fun pausedNonCooperativeWorkCannotPublishItsLateResult() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val harness = Harness { snapshot, _ ->
            withContext(NonCancellable) { gate.await() }
            snapshot.previous.copy(ticks = 42)
        }
        try {
            val pending = harness.controller.step(SimulationStep.TICK)!!
            harness.controller.pause()
            gate.complete(Unit)
            pending.join()
            assertEquals(0L, harness.state.ticks)
            assertTrue(harness.published.isEmpty())
            assertEquals(SimulationRunPhase.PAUSED, harness.controller.status.value.phase)
        } finally { gate.complete(Unit); harness.close() }
    }

    @Test
    fun projectRevisionChangeDiscardsAnInFlightResult() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val harness = Harness { snapshot, _ ->
            gate.await()
            snapshot.previous.copy(ticks = 17)
        }
        try {
            val pending = harness.controller.step(SimulationStep.TICK)!!
            harness.revision++
            gate.complete(Unit)
            pending.join()
            assertTrue(harness.published.isEmpty())
            assertEquals(0L, harness.state.ticks)
            assertEquals(SimulationRunPhase.PAUSED, harness.controller.status.value.phase)
        } finally { gate.complete(Unit); harness.close() }
    }

    @Test
    fun inactiveAndClosedControllersDoNotStartWork() = runBlocking<Unit> {
        val harness = Harness()
        try {
            harness.controller.start()
            harness.controller.setActive(false)
            assertFalse(harness.controller.start())
            assertNull(harness.controller.step(SimulationStep.TICK))
            assertEquals(SimulationRunPhase.INACTIVE, harness.controller.status.value.phase)
            harness.controller.setActive(true)
            assertEquals(SimulationRunPhase.PAUSED, harness.controller.status.value.phase)
            assertTrue(harness.controller.start())
            harness.controller.close()
            assertFalse(harness.controller.start())
            assertNull(harness.controller.step(SimulationStep.RESET))
            assertEquals(SimulationRunPhase.CLOSED, harness.controller.status.value.phase)
        } finally { harness.close() }
    }

    @Test
    fun configuringTheCurrentFrequencyKeepsTheExistingRunDelay() = runBlocking<Unit> {
        val harness = Harness()
        try {
            assertTrue(harness.controller.start())
            assertTrue(harness.controller.configureFrequency(harness.controller.status.value.frequencyHz))
            assertEquals(listOf(250L), harness.waits)
            assertEquals(SimulationRunPhase.RUNNING, harness.controller.status.value.phase)
            harness.permits.send(Unit)
            yield()
            assertEquals(1L, harness.state.ticks)
        } finally { harness.close() }
    }

    @Test
    fun changingFrequencyReplacesThePendingDelay() = runBlocking<Unit> {
        val harness = Harness()
        try {
            harness.controller.start()
            assertEquals(listOf(250L), harness.waits)
            harness.controller.configureFrequency(1.0)
            assertEquals(listOf(250L, 1000L), harness.waits)
            harness.permits.send(Unit)
            yield()
            assertEquals(1L, harness.state.ticks)
            assertEquals(1.0, harness.controller.status.value.frequencyHz)
            assertFailsWith<IllegalArgumentException> { harness.controller.configureFrequency(0.0) }
            assertFailsWith<IllegalArgumentException> { harness.controller.configureFrequency(Double.NaN) }
        } finally { harness.close() }
    }

    @Test
    fun simulationFailureStopsExecutionAndReportsOnce() = runBlocking<Unit> {
        val harness = Harness { _, _ -> throw IllegalStateException("fixture failure") }
        try {
            harness.controller.step(SimulationStep.TICK)!!.join()
            assertEquals(listOf<String?>("fixture failure"), harness.failures)
            assertEquals("fixture failure", harness.controller.status.value.failure)
            assertEquals(SimulationRunPhase.PAUSED, harness.controller.status.value.phase)
            assertTrue(harness.published.isEmpty())
        } finally { harness.close() }
    }

    @Test
    fun anOscillatingResultIsPublishedThenExecutionPauses() = runBlocking<Unit> {
        val harness = Harness { snapshot, _ -> snapshot.previous.copy(oscillating = true) }
        try {
            harness.controller.start()
            harness.permits.send(Unit)
            yield()
            assertTrue(harness.state.oscillating)
            assertEquals(1, harness.published.size)
            assertEquals(SimulationRunPhase.PAUSED, harness.controller.status.value.phase)
        } finally { harness.close() }
    }

    private class Harness(projectOverride: CircuitProject? = null,
        tickRateTimeSource: TimeSource = TimeSource.Monotonic,
        advanceOverride: (suspend (SimulationRunSnapshot, SimulationStep) -> SimulationState)? = null) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val simulator = CircuitSimulator()
        private val project = projectOverride ?: CircuitProject(listOf(CircuitDocument(components = listOf(
            CircuitComponent("clock", ComponentKind.CLOCK, GridPoint(20, 20)),
        ))))
        var state = simulator.settle(project)
        var revision = 0L
        val permits = Channel<Unit>(Channel.UNLIMITED)
        val waits = mutableListOf<Long>()
        val published = mutableListOf<SimulationState>()
        val failures = mutableListOf<String?>()
        val controller = SimulationRunController(
            scope = scope,
            capture = { SimulationRunSnapshot(project, state, revision = revision) },
            advance = advanceOverride ?: { snapshot, step ->
                when (step) {
                    SimulationStep.TICK -> simulator.settle(snapshot.project, snapshot.previous, tick = true)
                    SimulationStep.RESET -> simulator.settle(snapshot.project)
                    SimulationStep.SETTLE -> simulator.settle(snapshot.project, snapshot.previous, inputs = snapshot.inputs, vectorInputs = snapshot.vectorInputs)
                    SimulationStep.EVENT, SimulationStep.TICK_CYCLE -> advanceSimulation(snapshot, step).state
                }
            },
            isCurrent = { it.revision == revision },
            publish = { _, next -> state = next; published += next },
            onFailure = { failures += it.message },
            workerDispatcher = Dispatchers.Unconfined,
            waitForNext = { waits += it; permits.receive() },
            tickRateTimeSource = tickRateTimeSource,
        )
        fun close() { controller.close(); scope.cancel(); permits.close() }
    }
}
