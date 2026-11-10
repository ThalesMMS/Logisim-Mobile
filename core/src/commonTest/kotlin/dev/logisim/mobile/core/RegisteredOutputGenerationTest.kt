package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegisteredOutputGenerationTest {
    private val kind = ComponentKind("TEST_GENERATION_OUTPUT")
    private val pulseKind = ComponentKind("TEST_GENERATION_PULSE")
    private data class SourceState(
        val value: LogicValue = LogicValue.UNKNOWN,
        val delay: Long = 0,
        val generation: String? = "initial",
    ) : ComponentState
    private data class Change(val value: LogicValue, val delay: Long, val generation: String?) : ComponentEvent

    private fun definition(optIn: Boolean = true) = ComponentDefinition(kind, "Generation source", "#Test",
        layout = { component, _ -> ComponentLayout(
            listOf(ComponentTerminal("out", component.position, PortDirection.OUTPUT)),
            ComponentBounds(component.position.x - 10, component.position.y - 10, component.position.x, component.position.y + 10)) },
        behavior = object : ComponentBehavior {
            override fun initialState(component: CircuitComponent, attributes: AttributeValues) = SourceState()
            override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                val event = context.events.filterIsInstance<Change>().lastOrNull()
                val state = event?.let { SourceState(it.value, it.delay, it.generation) } ?: context.state as SourceState
                return ComponentEvaluation(mapOf("out" to LogicVector.fromBit(state.value)), state, state.delay,
                    outputGeneration = if (optIn) state.generation else null)
            }
        })

    private fun document() = CircuitDocument("main", listOf(
        CircuitComponent("source", kind, GridPoint(100, 100)),
        CircuitComponent("value", ComponentKind.OUTPUT, GridPoint(100, 100)),
        CircuitComponent("pulse", pulseKind, GridPoint(200, 100)),
        CircuitComponent("inverter", ComponentKind.NOT, GridPoint(250, 100)),
        CircuitComponent("probe", ComponentKind.OUTPUT, GridPoint(280, 100)),
    ), listOf(
        WireSegment(GridPoint(200, 100), GridPoint(220, 100)),
        WireSegment(GridPoint(250, 100), GridPoint(280, 100)),
    ))

    // The inverter supplies the one-delta marker; the stimulus must not add the Pin's own delay.
    private fun registry(source: ComponentDefinition) = ComponentCatalog.registry.withAdditional(listOf(source,
        ComponentDefinition(pulseKind, "Immediate pulse", "#Test",
            layout = { component, _ -> ComponentLayout(
                listOf(ComponentTerminal("out", component.position, PortDirection.OUTPUT)),
                ComponentBounds(component.position.x - 10, component.position.y - 10, component.position.x, component.position.y + 10)) },
            behavior = object : ComponentBehavior {
                override fun evaluate(context: ComponentEvaluationContext) = ComponentEvaluation(
                    mapOf("out" to (context.inputs["drive"] ?: LogicVector.low(1))), delay = 0)
            })))

    private fun simulator(optIn: Boolean = true) = CircuitSimulator(registry = registry(definition(optIn)))

    private fun pendingHigh(simulator: CircuitSimulator, document: CircuitDocument): SimulationState {
        val initial = simulator.settle(document, inputs = mapOf("pulse" to LogicValue.LOW))
        val pending = simulator.stepEvent(document, initial, inputs = mapOf("pulse" to LogicValue.HIGH),
            events = mapOf("source" to listOf(Change(LogicValue.HIGH, 10, "first"))))
        assertEquals(1L, initial.propagationTime)
        assertEquals(2L, pending.propagationTime)
        assertEquals(LogicValue.UNKNOWN, pending.componentValues["value"])
        assertTrue(pending.pendingEvents.any { it.componentId == "source" && it.time == 11L })
        return pending
    }

    @Test fun changingGenerationDiscardsPendingKnownOutputWithoutAdvancingToItsOldTime() {
        val simulator = simulator()
        val document = document()
        val pending = pendingHigh(simulator, document)
        val changed = simulator.settle(document, pending,
            events = mapOf("source" to listOf(Change(LogicValue.UNKNOWN, 0, "second"))))
        assertEquals(LogicValue.UNKNOWN, changed.componentValues["value"])
        assertEquals(2L, changed.propagationTime)
        assertTrue(changed.pendingEvents.isEmpty())
        assertEquals(mapOf("source" to "second"), changed.outputGenerations)
    }

    @Test fun newGenerationWithTheSameQueuedValueSchedulesAgainstTheActualDriver() {
        val simulator = simulator()
        val document = document()
        val pending = pendingHigh(simulator, document)
        val replacement = simulator.stepEvent(document, pending, inputs = mapOf("pulse" to LogicValue.LOW),
            events = mapOf("source" to listOf(Change(LogicValue.HIGH, 20, "second"))))
        assertEquals(3L, replacement.propagationTime)
        assertEquals(LogicValue.UNKNOWN, replacement.componentValues["value"])
        val sourceEvents = replacement.pendingEvents.filter { it.componentId == "source" }
        assertEquals(listOf(22L), sourceEvents.map { it.time })
        assertEquals(listOf("second"), sourceEvents.map { it.outputGeneration })
        assertEquals(mapOf("source" to "second"), replacement.outputGenerations)
        val resumed = simulator.settle(document, replacement)
        assertEquals(22L, resumed.propagationTime)
        assertEquals(LogicValue.HIGH, resumed.componentValues["value"])
        assertTrue(resumed.pendingEvents.isEmpty())
    }

    @Test fun returningToDefaultGenerationCancelsTaggedOutputsAndClearsTheOptIn() {
        val simulator = simulator()
        val document = document()
        val pending = pendingHigh(simulator, document)
        val changed = simulator.settle(document, pending,
            events = mapOf("source" to listOf(Change(LogicValue.UNKNOWN, 0, null))))
        assertEquals(LogicValue.UNKNOWN, changed.componentValues["value"])
        assertEquals(2L, changed.propagationTime)
        assertTrue(changed.pendingEvents.isEmpty())
        assertTrue(changed.outputGenerations.isEmpty())
    }

    @Test fun newGenerationSamplesTheSameKnownDriverAtItsFutureTimeExactlyOnce() {
        val simulator = simulator()
        val document = document()
        val known = simulator.settle(document, inputs = mapOf("pulse" to LogicValue.LOW),
            events = mapOf("source" to listOf(Change(LogicValue.HIGH, 0, "first"))))
        assertEquals(LogicValue.HIGH, known.componentValues["value"])
        val observed = mutableListOf<Long>()
        val sampled = simulator.settle(document, known,
            events = mapOf("source" to listOf(Change(LogicValue.HIGH, 10, "second"))),
            observer = { _, time -> observed += time })
        assertEquals(known.propagationTime + 10, sampled.propagationTime)
        assertEquals(LogicValue.HIGH, sampled.componentValues["value"])
        assertEquals(1, observed.count { it == sampled.propagationTime })
        assertTrue(sampled.pendingEvents.isEmpty())
        assertFalse(sampled.oscillating)
    }

    @Test fun defaultNullGenerationKeepsBothTransportEdges() {
        val simulator = simulator(optIn = false)
        val document = document()
        val pending = pendingHigh(simulator, document)
        assertTrue(pending.outputGenerations.isEmpty())
        assertTrue(pending.pendingEvents.all { it.outputGeneration == null })
        val observed = mutableListOf<Pair<Long, LogicValue>>()
        val changed = simulator.settle(document, pending,
            events = mapOf("source" to listOf(Change(LogicValue.LOW, 10, "ignored"))),
            observer = { state, time -> observed += time to state.componentValues.getValue("value") })
        assertEquals(LogicValue.LOW, changed.componentValues["value"])
        assertEquals(12L, changed.propagationTime)
        assertEquals(listOf(11L to LogicValue.HIGH, 12L to LogicValue.LOW), observed.filter { it.first >= 11 })
        assertTrue(changed.outputGenerations.isEmpty())
    }

    @Test fun removingRegisteredBehaviorCannotApplyItsPreviouslyTaggedOutput() {
        val document = document()
        val pending = pendingHigh(simulator(), document)
        val registry = registry(definition().copy(behavior = null))
        val resumed = CircuitSimulator(registry = registry).settle(document, pending)
        assertEquals(LogicValue.UNKNOWN, resumed.componentValues["value"])
        assertEquals(2L, resumed.propagationTime)
        assertTrue(resumed.pendingEvents.isEmpty())
        assertTrue(resumed.outputGenerations.isEmpty())
    }

    @Test fun sameLocalComponentInTwoInstancesCancelsOnlyTheChangedGeneration() {
        val simulator = simulator()
        val child = CircuitDocument("child", listOf(
            CircuitComponent("source", kind, GridPoint(0, 0)),
            CircuitComponent("pin", ComponentKind.OUTPUT, GridPoint(0, 0)),
        ))
        fun instance(id: String, x: Int) = CircuitComponent(id, ComponentKind.SUBCIRCUIT, GridPoint(x, 100),
            circuitName = "child", explicitPorts = listOf(ComponentPort("pin", GridPoint(0, 0), PortDirection.OUTPUT)))
        val base = document()
        val root = base.copy(components = base.components.filter { it.id !in setOf("source", "value") } +
            listOf(instance("one", 100), instance("two", 400)))
        val project = CircuitProject(listOf(root, child), "main")
        val initial = simulator.settle(project, inputs = mapOf("pulse" to LogicValue.LOW))
        val pending = simulator.stepEvent(project, initial, inputs = mapOf("pulse" to LogicValue.HIGH), events = mapOf(
            "one/source" to listOf(Change(LogicValue.HIGH, 10, "one-first")),
            "two/source" to listOf(Change(LogicValue.HIGH, 10, "two-first")),
        ))
        assertEquals(2, pending.pendingEvents.count { it.componentId == "source" })
        val changed = simulator.settle(project, pending,
            events = mapOf("one/source" to listOf(Change(LogicValue.UNKNOWN, 0, "one-second"))))
        assertEquals(LogicValue.UNKNOWN, changed.circuitStates.getValue("one").componentValues["pin"])
        assertEquals(LogicValue.HIGH, changed.circuitStates.getValue("two").componentValues["pin"])
        assertEquals(mapOf("source" to "one-second"), changed.circuitStates.getValue("one").outputGenerations)
        assertEquals(mapOf("source" to "two-first"), changed.circuitStates.getValue("two").outputGenerations)
        assertFalse(changed.oscillating)
    }
}
