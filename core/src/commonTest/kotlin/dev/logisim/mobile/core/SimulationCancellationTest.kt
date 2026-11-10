package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SimulationCancellationTest {
    private class Cancelled : RuntimeException()

    @Test
    fun computationChecksCancellationBeforeEvaluatingTheEntireCircuit() {
        val kind = ComponentKind("CANCELLATION_TEST")
        var evaluated = 0
        val definition = ComponentDefinition(kind, "Cancellation test", "#Test",
            behavior = object : ComponentBehavior {
                override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                    evaluated++
                    return ComponentEvaluation(emptyMap())
                }
            }, layout = { component, _ -> ComponentLayout(emptyList(), ComponentBounds(
                component.position.x, component.position.y, component.position.x + 10, component.position.y + 10)) })
        val registry = ComponentRegistry(listOf(definition))
        val document = CircuitDocument(components = List(1000) { CircuitComponent("c$it", kind, GridPoint(it * 20, 0)) })
        var checks = 0
        val simulator = CircuitSimulator(registry = registry, cancellationCheck = { if (++checks >= 10) throw Cancelled() })
        assertFailsWith<Cancelled> { simulator.settle(document) }
        assertTrue(evaluated in 1..999)
    }
}
