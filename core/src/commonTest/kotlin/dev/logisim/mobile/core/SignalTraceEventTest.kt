package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SignalTraceEventTest {
    @Test
    fun recordsBothDelayedOutputsAtTheirStableEventTimestamps() {
        val kind = ComponentKind("TEST_TRACE_DELAYED_OUTPUTS")
        val definition = ComponentDefinition(kind, "Delayed outputs", "#Test", layout = { component, _ ->
            ComponentLayout(listOf(
                ComponentTerminal("data", GridPoint(component.position.x - 20, component.position.y), PortDirection.INPUT, 4),
                ComponentTerminal("fast", component.position, PortDirection.OUTPUT, 4),
                ComponentTerminal("slow", GridPoint(component.position.x, component.position.y + 20), PortDirection.OUTPUT, 4)),
                ComponentBounds(component.position.x - 20, component.position.y - 10, component.position.x, component.position.y + 30))
        }, behavior = object : ComponentBehavior {
            override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                val value = context.inputs.getValue("data")
                return ComponentEvaluation(mapOf("fast" to value, "slow" to value), delay = 2, outputDelays = mapOf("slow" to 5))
            }
        })
        val registry = ComponentCatalog.registry.withAdditional(listOf(definition))
        val project = CircuitProject(listOf(CircuitDocument("main", components = listOf(
            CircuitComponent("input", ComponentKind.INPUT, GridPoint(80, 100), attributes = mapOf("width" to "4")),
            CircuitComponent("buffer", kind, GridPoint(100, 100)),
            CircuitComponent("fast", ComponentKind.OUTPUT, GridPoint(100, 100), attributes = mapOf("width" to "4")),
            CircuitComponent("slow", ComponentKind.OUTPUT, GridPoint(100, 120), attributes = mapOf("width" to "4"))))))
        val signals = TraceSignals.fromProject(project, registry).filter { it.reference.componentId in setOf("fast", "slow") }
        val session = TraceSession(project, signals, simulator = CircuitSimulator(registry = registry))
        assertEquals(6L, session.state.propagationTime)
        session.advance(mapOf("input" to LogicVector.of(4, 9uL)))

        assertEquals(TraceTimeUnit.EVENTS, session.recorder.timeUnit)
        assertEquals(listOf(6L, 7L, 9L, 12L), session.recorder.frames.map { it.time })
        assertEquals(listOf(
            listOf("0000", "0000"), listOf("0000", "0000"),
            listOf("1001", "0000"), listOf("1001", "1001")),
            session.recorder.frames.map { frame -> frame.values.map { it?.display } })
        assertEquals("1001", session.recorder.valueAt(signals[0].reference, 9)?.display)
        assertEquals("0000", session.recorder.valueAt(signals[1].reference, 11)?.display)
        assertEquals(1L, session.state.ticks)
    }
}
