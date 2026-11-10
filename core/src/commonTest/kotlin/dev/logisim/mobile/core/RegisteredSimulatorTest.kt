package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegisteredSimulatorTest {
    private val bufferKind = ComponentKind("TEST_REGISTERED_BUFFER")
    private val counterKind = ComponentKind("TEST_REGISTERED_COUNTER")
    private data class CounterState(val value: Int = 0) : ComponentState
    private data class Add(val amount: Int) : ComponentEvent

    private fun bufferDefinition() = ComponentDefinition(bufferKind, "Test Buffer", "#Test",
        layout = { c, _ -> ComponentLayout(listOf(
            ComponentTerminal("data", GridPoint(c.position.x - 20, c.position.y), PortDirection.INPUT, 4),
            ComponentTerminal("fast", c.position, PortDirection.OUTPUT, 4),
            ComponentTerminal("slow", GridPoint(c.position.x, c.position.y + 20), PortDirection.OUTPUT, 4)),
            ComponentBounds(c.position.x - 20, c.position.y - 10, c.position.x, c.position.y + 30)) },
        behavior = object : ComponentBehavior {
            override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                val value = context.inputs.getValue("data")
                return ComponentEvaluation(mapOf("fast" to value, "slow" to value),
                    delay = 2, outputDelays = mapOf("slow" to 5))
            }
        })

    private fun counterDefinition() = ComponentDefinition(counterKind, "Test Counter", "#Test",
        layout = { c, _ -> ComponentLayout(listOf(ComponentTerminal("out", c.position, PortDirection.OUTPUT, 8)),
            ComponentBounds(c.position.x - 20, c.position.y - 10, c.position.x, c.position.y + 10)) },
        behavior = object : ComponentBehavior {
            override fun initialState(component: CircuitComponent, attributes: AttributeValues) = CounterState()
            override fun resetState(component: CircuitComponent, attributes: AttributeValues, state: ComponentState): ComponentState =
                if (component.attributes["retain"] == "true") state else CounterState()
            override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                val old = context.state as CounterState
                val value = old.value + context.events.filterIsInstance<Add>().sumOf { it.amount }
                return ComponentEvaluation(mapOf("out" to LogicVector.of(8, value.toULong())), CounterState(value), 3,
                    nextWakeupMillis = context.elapsedTimeMillis + 100)
            }
        })

    @Test
    fun registeredOutputsApplyTheSourcePinDelayBeforeNamedDelays() {
        val registry = ComponentCatalog.registry.withAdditional(listOf(bufferDefinition()))
        val simulator = CircuitSimulator(registry = registry)
        val document = CircuitDocument(components = listOf(
            CircuitComponent("input", ComponentKind.INPUT, GridPoint(80, 100), attributes = mapOf("width" to "4")),
            CircuitComponent("buffer", bufferKind, GridPoint(100, 100))))
        val initial = simulator.settle(document)
        assertEquals(6L, initial.propagationTime, "The source Pin adds one delta before the buffer delay of five.")
        val observed = mutableListOf<Pair<Long, SimulationState>>()
        val state = simulator.settle(document, initial, vectorInputs = mapOf("input" to LogicVector.of(4, 9uL)),
            observer = { snapshot, time -> observed += time to snapshot })
        assertEquals(12L, state.propagationTime)
        assertEquals(listOf(6L, 7L, 9L, 12L), observed.map { it.first })
        assertEquals(9uL, observed[2].second.componentOutputs.getValue("buffer").getValue("fast").toULongOrNull())
        assertEquals(0uL, observed[2].second.componentOutputs.getValue("buffer").getValue("slow").toULongOrNull())
        assertEquals(9uL, state.portVectors.getValue(GridPoint(100, 120)).toULongOrNull())
    }

    @Test
    fun eventStepFirstAppliesThePinThenPersistsTheBufferQueue() {
        val registry = ComponentCatalog.registry.withAdditional(listOf(bufferDefinition()))
        val simulator = CircuitSimulator(registry = registry)
        val document = CircuitDocument(components = listOf(
            CircuitComponent("input", ComponentKind.INPUT, GridPoint(80, 100), attributes = mapOf("width" to "4")),
            CircuitComponent("buffer", bufferKind, GridPoint(100, 100))))
        val first = simulator.stepEvent(document, vectorInputs = mapOf("input" to LogicVector.of(4, 6uL)))
        assertEquals(1L, first.propagationTime)
        assertEquals(0L, first.ticks)
        assertFalse(first.oscillating)
        assertEquals(6uL, first.vectorValues.getValue("input").toULongOrNull())
        assertEquals(null, first.componentOutputs.getValue("buffer").getValue("fast").toULongOrNull())
        assertEquals(null, first.componentOutputs.getValue("buffer").getValue("slow").toULongOrNull())
        assertEquals(listOf(3L, 6L), first.pendingEvents.map { it.time })
        val second = simulator.stepEvent(document, first)
        assertEquals(3L, second.propagationTime)
        assertEquals(6uL, second.componentOutputs.getValue("buffer").getValue("fast").toULongOrNull())
        assertEquals(null, second.componentOutputs.getValue("buffer").getValue("slow").toULongOrNull())
        assertEquals(listOf(6L), second.pendingEvents.map { it.time })
        val third = simulator.stepEvent(document, second)
        assertEquals(6L, third.propagationTime)
        assertTrue(third.pendingEvents.isEmpty())
        assertEquals(6uL, third.componentOutputs.getValue("buffer").getValue("slow").toULongOrNull())
    }

    @Test
    fun externalEventsAreConsumedOnceAndResetDelegatesToTheBehavior() {
        val registry = ComponentCatalog.registry.withAdditional(listOf(counterDefinition()))
        val simulator = CircuitSimulator(registry = registry)
        val document = CircuitDocument(components = listOf(
            CircuitComponent("a", counterKind, GridPoint(0, 0)),
            CircuitComponent("b", counterKind, GridPoint(0, 30), attributes = mapOf("retain" to "true"))))
        val first = simulator.settle(document, events = mapOf("a" to listOf(Add(2)), "b" to listOf(Add(7))), elapsedTimeMillis = 40)
        assertEquals(CounterState(2), first.componentStates["a"])
        assertEquals(CounterState(7), first.componentStates["b"])
        assertEquals(140L, first.nextWakeupMillis)
        val second = simulator.settle(document, first)
        assertEquals(first.componentStates, second.componentStates)
        val reset = simulator.settle(document, second, reset = true)
        assertEquals(CounterState(0), reset.componentStates["a"])
        assertEquals(CounterState(7), reset.componentStates["b"])
    }

    @Test
    fun nestedRegisteredStateUsesTheInstancePath() {
        val registry = ComponentCatalog.registry.withAdditional(listOf(counterDefinition()))
        val simulator = CircuitSimulator(registry = registry)
        val child = CircuitDocument("child", components = listOf(
            CircuitComponent("counter", counterKind, GridPoint(0, 0)),
            CircuitComponent("pin", ComponentKind.OUTPUT, GridPoint(0, 0), attributes = mapOf("width" to "8"))))
        fun instance(id: String, x: Int) = CircuitComponent(id, ComponentKind.SUBCIRCUIT, GridPoint(x, 100),
            circuitName = "child", explicitPorts = listOf(ComponentPort("pin", GridPoint(0, 0), PortDirection.OUTPUT, 8)))
        val project = CircuitProject(listOf(CircuitDocument("main", listOf(instance("first", 100), instance("second", 200))), child), "main")
        val observed = mutableListOf<SimulationState>()
        val state = simulator.settle(project, events = mapOf("first/counter" to listOf(Add(11))), observer = { snapshot, _ -> observed += snapshot })
        assertEquals(CounterState(11), state.circuitStates.getValue("first").componentStates["counter"])
        assertEquals(CounterState(0), state.circuitStates.getValue("second").componentStates["counter"])
        assertEquals(11uL, state.vectorValues.getValue("first").toULongOrNull())
        assertEquals(0uL, state.vectorValues.getValue("second").toULongOrNull())
        assertTrue(observed.all { it.circuitStates.keys == setOf("first", "second") })
    }

    @Test
    fun registeredInputReceivesExternalDriveAndUnknownWidthIsInferred() {
        data class WidthState(val width: Int) : ComponentState
        val sense = ComponentKind("TEST_WIDTH_SENSOR")
        val pin = ComponentCatalog.registry.requireDefinition(ComponentKind.INPUT).copy(behavior = object : ComponentBehavior {
            override fun evaluate(context: ComponentEvaluationContext) = ComponentEvaluation(mapOf("out" to context.inputs.getValue("drive")), delay = 0)
        })
        val sensor = ComponentDefinition(sense, "Width", "#Test", layout = { c, _ ->
            ComponentLayout(listOf(ComponentTerminal("sense", c.position, PortDirection.INPUT, 0)), ComponentBounds(0, 0, 10, 10))
        }, behavior = object : ComponentBehavior {
            override fun evaluate(context: ComponentEvaluationContext) = ComponentEvaluation(emptyMap(), WidthState(context.inputs.getValue("sense").width))
        })
        val registry = ComponentCatalog.registry.withDefinitions(listOf(pin, sensor))
        val document = CircuitDocument(components = listOf(
            CircuitComponent("input", ComponentKind.INPUT, GridPoint(0, 0), attributes = mapOf("width" to "8")),
            CircuitComponent("sensor", sense, GridPoint(0, 0))))
        val state = CircuitSimulator(registry = registry).settle(document, vectorInputs = mapOf("input" to LogicVector.of(8, 0xabuL)))
        assertEquals(WidthState(8), state.componentStates["sensor"])
        assertEquals(0xabuL, state.portVectors.getValue(GridPoint(0, 0)).toULongOrNull())
    }

    @Test
    fun registeredInputKeepsItsInitialDriveUntilAnExplicitStimulusArrives() {
        data class PinState(val intended: LogicVector) : ComponentState
        val definition = ComponentCatalog.registry.requireDefinition(ComponentKind.INPUT).copy(
            behavior = object : ComponentBehavior {
                override fun initialState(component: CircuitComponent, attributes: AttributeValues) =
                    PinState(LogicVector.high(1))
                override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                    val value = context.inputs["drive"] ?: (context.state as PinState).intended
                    return ComponentEvaluation(mapOf("out" to value), PinState(value))
                }
            })
        val simulator = CircuitSimulator(registry = ComponentCatalog.registry.withDefinitions(listOf(definition)))
        val document = CircuitDocument(components = listOf(CircuitComponent("pin", ComponentKind.INPUT, GridPoint(0, 0))))
        val initial = simulator.settle(document)
        assertEquals(LogicValue.HIGH, initial.componentValues["pin"])
        assertTrue(initial.inputVectors.isEmpty())
        val changed = simulator.settle(document, initial, inputs = mapOf("pin" to LogicValue.LOW))
        assertEquals(LogicValue.LOW, changed.componentValues["pin"])
        assertEquals(LogicValue.LOW, simulator.settle(document, changed).componentValues["pin"])
        assertEquals(LogicValue.HIGH, simulator.settle(document, changed, reset = true).componentValues["pin"])
    }

}
