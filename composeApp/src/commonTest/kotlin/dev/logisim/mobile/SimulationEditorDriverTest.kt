package dev.logisim.mobile

import dev.logisim.mobile.core.AttributeValues
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentBehavior
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentDefinition
import dev.logisim.mobile.core.ComponentEvaluation
import dev.logisim.mobile.core.ComponentEvaluationContext
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentLayout
import dev.logisim.mobile.core.ComponentState
import dev.logisim.mobile.core.ComponentTerminal
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.PortDirection
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.TclComponentEvent
import dev.logisim.mobile.core.TclComponents
import dev.logisim.mobile.core.TclRuntimeState
import dev.logisim.mobile.core.TclSourceFile
import dev.logisim.mobile.core.WireSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulationEditorDriverTest {
    private val bufferKind = ComponentKind("EDITOR_DRIVER_BUFFER")
    private val counterKind = ComponentKind("EDITOR_DRIVER_COUNTER")
    private val timerKind = ComponentKind("EDITOR_DRIVER_TIMER")
    private data class CounterState(val count: Int = 0) : ComponentState
    private data class Add(val amount: Int) : ComponentEvent
    private data class PinState(val value: LogicVector) : ComponentState
    private data class TimerState(val elapsed: Long, val option: String?, val automatic: Boolean) : ComponentState

    @Test
    fun eventStepAdvancesRealPropagationAndKeepsTheRemainingQueue() {
        val registry = ComponentCatalog.registry.withAdditional(listOf(bufferDefinition()))
        val initial = snapshot(bufferDocument())
        // The desktop Pin drives after 1 event unit; the custom slow output adds 5.
        val settled = advanceSimulationTime(initial, registry).state
        assertEquals(6L, settled.propagationTime)
        val snapshot = initial.copy(previous = settled, vectorInputs = mapOf("input" to LogicVector.of(4, 6uL)))
        val first = advanceSimulation(snapshot, SimulationStep.EVENT, registry)
        assertEquals(7L, first.state.propagationTime)
        assertEquals(0L, first.state.ticks)
        assertEquals(listOf(9L, 12L), first.state.pendingEvents.map { it.time })
        assertEquals(0uL, first.state.componentOutputs.getValue("buffer").getValue("fast").toULongOrNull())
        val second = advanceSimulation(snapshot.copy(previous = first.state), SimulationStep.EVENT, registry)
        assertEquals(9L, second.state.propagationTime)
        assertEquals(0L, second.state.ticks)
        assertEquals(listOf(12L), second.state.pendingEvents.map { it.time })
        assertEquals(6uL, second.state.componentOutputs.getValue("buffer").getValue("fast").toULongOrNull())
        assertEquals(0uL, second.state.componentOutputs.getValue("buffer").getValue("slow").toULongOrNull())
        val third = advanceSimulation(snapshot.copy(previous = second.state), SimulationStep.EVENT, registry)
        assertEquals(12L, third.state.propagationTime)
        assertEquals(0L, third.state.ticks)
        assertTrue(third.state.pendingEvents.isEmpty())
        assertEquals(6uL, third.state.componentOutputs.getValue("buffer").getValue("slow").toULongOrNull())
    }

    @Test
    fun clockTickAndEventStepRemainDistinct() {
        val initial = snapshot(CircuitDocument(components = listOf(
            CircuitComponent("clock", ComponentKind.CLOCK, GridPoint(100, 100)),
        )))
        val state = advanceSimulationTime(initial).state
        val tick = advanceSimulation(initial.copy(previous = state), SimulationStep.TICK).state
        assertEquals(1L, tick.ticks)
        assertEquals(LogicValue.HIGH, tick.componentValues["clock"])
        val event = advanceSimulation(initial.copy(previous = tick), SimulationStep.EVENT).state
        assertEquals(1L, event.ticks)
        assertEquals(LogicValue.HIGH, event.componentValues["clock"])
        val reset = advanceSimulation(initial.copy(previous = event), SimulationStep.RESET).state
        assertEquals(0L, reset.ticks)
        assertEquals(LogicValue.LOW, reset.componentValues["clock"])
    }

    @Test
    fun tclRunRequestConsumesOneApprovedTickAndIgnoresAReplay() {
        val (initial, waiting) = tclAfterTwoFrames("approved")
        val reply = TclComponentEvent.Response(waiting.tclState().generation, tclDigest, tclOutputs, setOf(1))
        val pending = tclStep(initial, waiting, reply)
        assertEquals(1L, pending.ticks)
        assertEquals(1, pending.tclState().pendingTickRequests)
        val event = TclComponentEvent.ApplyTick(pending.tclState().generation, 1, "approved")
        val ticked = tclStep(initial, pending, event)
        assertEquals(2L, ticked.ticks)
        assertEquals(LogicValue.LOW, ticked.componentValues["clock"])
        assertEquals(0, ticked.tclState().pendingTickRequests)
        assertEquals(15, ticked.tclState().automaticTicks?.remaining)
        assertEquals(ticked, tclStep(initial, ticked, event))
    }

    @Test
    fun tclRequestsRemainManualWithoutCurrentAuthorization() {
        for (authorization in listOf(null, "cancelled")) {
            val (initial, waiting) = tclAfterTwoFrames(authorization)
            val pending = tclStep(initial, waiting, TclComponentEvent.Response(waiting.tclState().generation, tclDigest, tclOutputs, setOf(1)))
            val cancelled = tclStep(initial, pending, TclComponentEvent.RevokeAutomaticTicks("cancelled"))
            val stale = TclComponentEvent.ApplyTick(cancelled.tclState().generation, 1, "cancelled")
            assertEquals(cancelled, tclStep(initial, cancelled, stale))
            val manual = tclStep(initial, cancelled, stale.copy(authorization = null))
            assertEquals(2L, manual.ticks)
            assertEquals(0, manual.tclState().pendingTickRequests)
        }
    }

    @Test
    fun resetUsesThePreviousComponentStateAndDiscardsPendingUserEvents() {
        val registry = ComponentCatalog.registry.withAdditional(listOf(counterDefinition()))
        val initial = snapshot(CircuitDocument(components = listOf(
            CircuitComponent("normal", counterKind, GridPoint(100, 100)),
            CircuitComponent("retained", counterKind, GridPoint(100, 140), attributes = mapOf("retain" to "true")),
        )))
        val running = advanceSimulationTime(initial.copy(elapsedTimeMillis = 90), registry,
            events = mapOf("normal" to listOf(Add(2)), "retained" to listOf(Add(7)))).state
        assertEquals(CounterState(2), running.componentStates["normal"])
        assertEquals(CounterState(7), running.componentStates["retained"])
        val reset = advanceSimulation(initial.copy(previous = running, elapsedTimeMillis = 90),
            SimulationStep.RESET, registry, events = mapOf("normal" to listOf(Add(99)))).state
        assertEquals(CounterState(0), reset.componentStates["normal"])
        assertEquals(CounterState(7), reset.componentStates["retained"])
        assertEquals(0L, reset.elapsedTimeMillis)
        assertEquals(0L, reset.ticks)
        assertTrue(reset.pendingEvents.isEmpty())
        val next = advanceSimulationTime(initial.copy(previous = reset), registry).state
        assertEquals(CounterState(0), next.componentStates["normal"])
        assertEquals(CounterState(7), next.componentStates["retained"])
    }

    @Test
    fun resetRestoresRegisteredPinInitialValuesInsteadOfReplayingCapturedDrives() {
        val pin = ComponentCatalog.registry.requireDefinition(ComponentKind.INPUT).copy(
            behavior = object : ComponentBehavior {
                override fun initialState(component: CircuitComponent, attributes: AttributeValues) =
                    PinState(LogicVector.high(1))
                override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                    val value = context.inputs["drive"] ?: (context.state as PinState).value
                    return ComponentEvaluation(mapOf("out" to value), PinState(value), delay = 0)
                }
            },
        )
        val registry = ComponentCatalog.registry.withDefinitions(listOf(pin))
        val initial = snapshot(CircuitDocument(components = listOf(
            CircuitComponent("input", ComponentKind.INPUT, GridPoint(100, 100)),
        )))
        val driven = initial.copy(inputs = mapOf("input" to LogicValue.LOW),
            vectorInputs = mapOf("input" to LogicVector.low(1)))
        val state = advanceSimulationTime(driven, registry).state
        assertEquals(LogicValue.LOW, state.componentValues["input"])
        val reset = advanceSimulation(driven.copy(previous = state), SimulationStep.RESET, registry).state
        assertEquals(LogicValue.HIGH, reset.componentValues["input"])
        assertTrue(reset.inputs.isEmpty())
        assertTrue(reset.inputVectors.isEmpty())
    }

    @Test
    fun observationsKeepIntermediateValuesAtTheirActualPropagationTimes() {
        val registry = ComponentCatalog.registry.withAdditional(listOf(bufferDefinition()))
        val initial = snapshot(bufferDocument())
        val settled = advanceSimulationTime(initial, registry).state
        assertEquals(6L, settled.propagationTime)
        val changed = advanceSimulationTime(initial.copy(previous = settled,
            vectorInputs = mapOf("input" to LogicVector.of(4, 9uL))), registry)
        assertEquals(listOf(6L, 7L, 9L, 12L), changed.observations.map { it.propagationTime })
        val intermediate = changed.observations.single { it.propagationTime == 9L }.state.componentOutputs.getValue("buffer")
        assertEquals(9uL, intermediate.getValue("fast").toULongOrNull())
        assertEquals(0uL, intermediate.getValue("slow").toULongOrNull())
        assertEquals(9uL, changed.state.componentOutputs.getValue("buffer").getValue("slow").toULongOrNull())
        assertTrue(changed.observations.all { it.state.propagationTime == it.propagationTime })
    }

    @Test
    fun timeAdvanceDeliversTheElapsedTimeAndOptionsWithoutAClockTick() {
        val timer = ComponentDefinition(timerKind, "Timer", "#Test", layout = { component, _ ->
            outputLayout(component)
        }, behavior = object : ComponentBehavior {
            override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                val expired = context.elapsedTimeMillis >= 10
                return ComponentEvaluation(mapOf("out" to if (expired) LogicVector.high(8) else LogicVector.low(8)),
                    TimerState(context.elapsedTimeMillis, context.options["gateUndefined"], context.autoPropagating),
                    delay = 0, nextWakeupMillis = if (expired) null else 10)
            }
        })
        val registry = ComponentCatalog.registry.withAdditional(listOf(timer))
        val initial = snapshot(CircuitDocument(components = listOf(
            CircuitComponent("timer", timerKind, GridPoint(100, 100)),
        ))).copy(options = mapOf("gateUndefined" to "error"))
        val before = advanceSimulationTime(initial, registry, autoPropagating = true).state
        assertEquals(10L, before.nextWakeupMillis)
        assertEquals(0uL, before.componentOutputs.getValue("timer").getValue("out").toULongOrNull())
        val event = advanceSimulation(initial.copy(previous = before), SimulationStep.EVENT, registry).state
        assertEquals(0L, event.elapsedTimeMillis)
        assertEquals(10L, event.nextWakeupMillis)
        val due = advanceSimulationTime(initial.copy(previous = event, elapsedTimeMillis = 10), registry,
            autoPropagating = true).state
        assertEquals(TimerState(10, "error", true), due.componentStates["timer"])
        assertEquals(255uL, due.componentOutputs.getValue("timer").getValue("out").toULongOrNull())
        assertNull(due.nextWakeupMillis)
        assertEquals(0L, due.ticks)
        assertFalse(due.oscillating)
    }

    private fun snapshot(document: CircuitDocument) = SimulationRunSnapshot(
        CircuitProject(listOf(document)), SimulationState(),
    )

    private val tclDigest = TclSourceFile("fixture.tcl", "if {$::count == 2} {run}").sha256
    private val tclOutputs = mapOf("result_o" to LogicVector.high(1))

    private fun SimulationState.tclState() = componentStates.getValue("tcl") as TclRuntimeState

    private fun tclAfterTwoFrames(authorization: String?): Pair<SimulationRunSnapshot, SimulationState> {
        val initial = snapshot(CircuitDocument(
            components = listOf(
                CircuitComponent("clock", ComponentKind.CLOCK, GridPoint(100, 100)),
                CircuitComponent("tcl", TclComponents.GENERIC, GridPoint(200, 60), attributes = mapOf(
                    "content" to "entity demo is port(sysclk_i : in std_logic; result_o : out std_logic); end demo;",
                )),
            ),
            wires = listOf(WireSegment(GridPoint(100, 100), GridPoint(200, 100))),
        ))
        val settled = advanceSimulation(initial, SimulationStep.SETTLE).state
        val enabled = tclStep(initial, settled, TclComponentEvent.Enable(tclDigest, authorization))
        val waiting = advanceSimulation(initial.copy(previous = enabled), SimulationStep.TICK).state
        assertEquals(2, waiting.tclState().frames.size)
        return initial to waiting
    }

    private fun tclStep(initial: SimulationRunSnapshot, previous: SimulationState, event: TclComponentEvent) =
        advanceSimulation(initial.copy(previous = previous, events = mapOf("tcl" to listOf(event))), SimulationStep.SETTLE).state

    private fun bufferDocument() = CircuitDocument(components = listOf(
        CircuitComponent("input", ComponentKind.INPUT, GridPoint(80, 100), attributes = mapOf("width" to "4")),
        CircuitComponent("buffer", bufferKind, GridPoint(100, 100)),
    ))

    private fun bufferDefinition() = ComponentDefinition(bufferKind, "Buffer", "#Test", layout = { component, _ ->
        ComponentLayout(listOf(
            ComponentTerminal("data", GridPoint(component.position.x - 20, component.position.y), PortDirection.INPUT, 4),
            ComponentTerminal("fast", component.position, PortDirection.OUTPUT, 4),
            ComponentTerminal("slow", GridPoint(component.position.x, component.position.y + 20), PortDirection.OUTPUT, 4),
        ), ComponentBounds(component.position.x - 20, component.position.y - 10,
            component.position.x, component.position.y + 30))
    }, behavior = object : ComponentBehavior {
        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
            val value = context.inputs.getValue("data")
            return ComponentEvaluation(mapOf("fast" to value, "slow" to value), delay = 2,
                outputDelays = mapOf("slow" to 5))
        }
    })

    private fun counterDefinition() = ComponentDefinition(counterKind, "Counter", "#Test", layout = { component, _ ->
        outputLayout(component)
    }, behavior = object : ComponentBehavior {
        override fun initialState(component: CircuitComponent, attributes: AttributeValues) = CounterState()
        override fun resetState(component: CircuitComponent, attributes: AttributeValues, state: ComponentState) =
            if (component.attributes["retain"] == "true") state else CounterState()
        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
            val count = (context.state as CounterState).count + context.events.filterIsInstance<Add>().sumOf { it.amount }
            return ComponentEvaluation(mapOf("out" to LogicVector.of(8, count.toULong())), CounterState(count), delay = 0)
        }
    })

    private fun outputLayout(component: CircuitComponent) = ComponentLayout(listOf(
        ComponentTerminal("out", component.position, PortDirection.OUTPUT, 8),
    ), ComponentBounds(component.position.x - 20, component.position.y - 10,
        component.position.x, component.position.y + 10))
}
