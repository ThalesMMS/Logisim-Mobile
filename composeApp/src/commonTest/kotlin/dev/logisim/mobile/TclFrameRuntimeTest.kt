package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.CircuitSimulator
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.TclComponentEvent
import dev.logisim.mobile.core.TclComponents
import dev.logisim.mobile.core.TclConsoleRequest
import dev.logisim.mobile.core.TclExecutionClient
import dev.logisim.mobile.core.TclRuntimeState
import dev.logisim.mobile.core.TclSourceFile
import dev.logisim.mobile.core.TclStepObservation
import dev.logisim.mobile.core.TclStepReply
import dev.logisim.mobile.core.TclStepRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TclFrameRuntimeTest {
    @Test
    fun approvedTickRunsOnceWithoutASelectedComponentOrOpenPanel() = runBlocking {
        val fixture = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        try {
            fixture.host.tickRequestFrames = setOf(0)
            fixture.approve(automaticTicks = true)
            fixture.await { fixture.ticks == 1L }
            repeat(3) { fixture.observe() }
            assertEquals(1L, fixture.ticks)
            assertEquals(0, fixture.state.pendingTickRequests)
            assertEquals(15, fixture.state.automaticTicks?.remaining)
        } finally {
            fixture.runtime.close()
        }
    }

    @Test
    fun revokingAnInFlightAutomaticTickPreservesTheManualRequest() = runBlocking {
        val fixture = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        try {
            fixture.host.tickRequestFrames = setOf(0)
            fixture.deferAutomaticTicks = true
            fixture.approve(automaticTicks = true)
            fixture.await { fixture.queuedTick != null }
            repeat(3) { fixture.observe() }
            fixture.revokeAutomaticTicks()
            fixture.observe()
            assertNull(fixture.queuedTick)
            assertNull(fixture.state.automaticTicks)
            assertEquals(0L, fixture.ticks)
            assertEquals(1, fixture.state.pendingTickRequests)
            fixture.applyManualTick()
            assertEquals(1L, fixture.ticks)
            assertEquals(0, fixture.state.pendingTickRequests)
        } finally {
            fixture.runtime.close()
        }
    }

    @Test
    fun approvedCounterPublishesEveryQueuedFrameWithoutAPanel() = runBlocking {
        val fixture = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        try {
            fixture.observe()
            assertTrue(fixture.host.history.isEmpty())
            fixture.approve()
            fixture.host.release = CompletableDeferred()
            fixture.observe()
            withTimeout(2_000) { fixture.host.started.await() }
            fixture.drive(LogicValue.HIGH)
            fixture.observe()
            fixture.drive(LogicValue.LOW)
            fixture.observe()
            assertEquals(listOf(listOf("0")), fixture.host.history)
            fixture.host.release?.complete(Unit)
            fixture.await { fixture.outputs.size == 3 }
            assertEquals(listOf(listOf("0"), listOf("0", "1"), listOf("0", "1", "0")), fixture.host.history)
            assertEquals(listOf(1uL, 2uL, 3uL), fixture.outputs)
        } finally {
            fixture.runtime.close()
        }
    }

    @Test
    fun aDroppedEditorReplyIsRetriedWithoutReplayingOrOvertakingItsFrame() = runBlocking {
        val fixture = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        try {
            fixture.deferReplies = true
            fixture.approve()
            fixture.await { fixture.queuedReply != null }
            fixture.drive(LogicValue.HIGH)
            repeat(3) { fixture.observe() }
            assertEquals(1, fixture.replyDeliveries)
            assertEquals(listOf(listOf("0")), fixture.host.history)
            assertTrue(fixture.outputs.isEmpty())

            fixture.dropQueuedReply()
            fixture.await { fixture.outputs.size == 2 }
            assertEquals(3, fixture.replyDeliveries)
            assertEquals(listOf(listOf("0"), listOf("0", "1")), fixture.host.history)
            assertEquals(listOf(1uL, 2uL), fixture.outputs)
        } finally {
            fixture.runtime.close()
        }
    }

    @Test
    fun resetAndStopCancelOutstandingWorkAndRequireANewApproval() = runBlocking {
        for (reset in listOf(true, false)) {
            val fixture = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
            try {
                fixture.approve()
                fixture.host.release = CompletableDeferred()
                fixture.observe()
                withTimeout(2_000) { fixture.host.started.await() }
                if (reset) fixture.reset() else fixture.stop()
                fixture.observe()
                withTimeout(2_000) { fixture.host.cancelled.await() }
                fixture.drive(LogicValue.HIGH)
                fixture.observe()
                assertEquals(1, fixture.host.history.size)
                assertTrue(fixture.outputs.isEmpty())
                assertNull(fixture.state.approvalId)
                fixture.host.release = null
                fixture.approve()
                fixture.await { fixture.outputs.isNotEmpty() }
                assertEquals(listOf(1uL), fixture.outputs)
                assertEquals(listOf("1"), fixture.host.history.last())
            } finally {
                fixture.runtime.close()
            }
        }
    }

    private class Fixture(dispatcher: CoroutineDispatcher) {
        val host = Host()
        val runtime = TclFrameRuntime(host, dispatcher)
        private val source = TclSourceFile("counter.tcl", "set counter 0")
        private val component = CircuitComponent("tcl", TclComponents.GENERIC, GridPoint(100, 100),
            attributes = mapOf("content" to "entity counter is port(sysclk_i : in std_logic; y : out std_logic_vector(3 downto 0)); end;"))
        private val circuit = CircuitDocument("main", components = listOf(
            CircuitComponent("clock", ComponentKind.INPUT, GridPoint(100, 140)), component,
        ))
        private val project = CircuitProject(listOf(circuit))
        private val simulator = CircuitSimulator()
        private var inputs = mapOf("clock" to LogicValue.LOW)
        private var simulation = simulator.settle(project, inputs = inputs)
        val state get() = simulation.componentStates["tcl"] as TclRuntimeState
        val ticks get() = simulation.ticks
        val outputs = mutableListOf<ULong>()
        var deferReplies = false
        var queuedReply: ComponentEvent? = null
            private set
        var deferAutomaticTicks = false
        var queuedTick: TclComponentEvent.ApplyTick? = null
            private set
        var replyDeliveries = 0
            private set
        private val context get() = EditorToolContext(project, circuit, simulation, emptyList(), {}, {}, {}, {}, {},
            injectEvents = ::inject, isComponentEventPending = { it === queuedReply || it === queuedTick },
            cancelComponentEvent = { if (it === queuedTick) queuedTick = null })

        private fun inject(events: Map<String, List<ComponentEvent>>): Boolean {
            val event = events.values.singleOrNull()?.singleOrNull()
            if (event is TclComponentEvent.ApplyTick) {
                if (deferAutomaticTicks && event.authorization != null) {
                    check(queuedTick == null)
                    queuedTick = event
                } else {
                    simulation = advanceSimulation(SimulationRunSnapshot(project, simulation, inputs, events = events),
                        SimulationStep.SETTLE).state
                }
                return true
            }
            val reply = event as? TclComponentEvent.Response
            if (reply != null) {
                replyDeliveries++
                if (deferReplies) {
                    check(queuedReply == null)
                    queuedReply = reply
                    return true
                }
            }
            val completed = state.completedFrameCount
            simulation = simulator.settle(project, simulation, inputs = inputs, events = events)
            if (reply != null && state.completedFrameCount > completed) outputs += requireNotNull(state.outputs.getValue("y").toULongOrNull())
            return true
        }

        fun dropQueuedReply() {
            queuedReply = null
            deferReplies = false
        }

        fun approve(automaticTicks: Boolean = false) = runtime.approve(context, component, source,
            automaticTickAuthorization = if (automaticTicks) "counter-ticks" else null)
        fun revokeAutomaticTicks() = runtime.revokeAutomaticTicks(context, component)
        fun applyManualTick() = inject(mapOf(component.id to listOf(state.tickRequest())))
        fun stop() = runtime.stop(context, component)
        fun reset() { simulation = simulator.settle(project, simulation, inputs = inputs, reset = true) }
        fun observe() = runtime.synchronize(context)
        fun drive(value: LogicValue) {
            inputs = mapOf("clock" to value)
            simulation = simulator.settle(project, simulation, inputs = inputs)
        }

        suspend fun await(done: () -> Boolean) = withTimeout(2_000) {
            while (!done()) {
                observe()
                delay(1)
            }
        }
    }

    private class Host : TclExecutionClient {
        override val hostLabel = "test"
        override val available = true
        val history = mutableListOf<List<String>>()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var tickRequestFrames = emptySet<Int>()
        var release: CompletableDeferred<Unit>? = null
        override suspend fun runConsole(request: TclConsoleRequest) = error("No console")
        override suspend fun cancel(requestId: String) = Unit

        override suspend fun step(request: TclStepRequest): TclStepReply {
            history += request.frames.map { it.inputs.getValue("sysclk_i").display }
            started.complete(Unit)
            try {
                release?.await()
            } finally {
                if (release?.isCompleted == false) cancelled.complete(Unit)
            }
            val steps = request.frames.mapIndexed { index, _ ->
                TclStepObservation(true, index in tickRequestFrames, mapOf("y" to LogicVector.of(4, (index + 1).toULong())))
            }
            return TclStepReply(steps, steps.last().outputs, "", "", request.source.sha256)
        }
    }
}
