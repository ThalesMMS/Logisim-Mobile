package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C67: simulation notices name the components they are about, so the warnings dialog can show them. The sources are
 * navigation data beside the notices: the values, the notices and the document stay what they were.
 */
class SimulationNoticeSourcesTest {
    private fun pin(id: String, x: Int, width: Int = 1, output: Boolean = false) = CircuitComponent(id,
        if (output) ComponentKind.OUTPUT else ComponentKind.INPUT, GridPoint(x, 100), attributes = mapOf("width" to width.toString()))

    @Test
    fun aWidthMismatchNamesEveryComponentOnTheNet() {
        val wide = pin("wide", 100, width = 8)
        val narrow = pin("narrow", 300, output = true)
        val document = CircuitDocument("main", listOf(wide, narrow), listOf(WireSegment(wide.position, narrow.position)))
        val state = CircuitSimulator().settle(document)
        val notice = state.notices.single { it.key == Messages.ErrorNetworkWidth }
        assertEquals(SimulationNoticeSource(listOf("wide", "narrow")), state.noticeSources[notice])
        assertEquals(CircuitNetworks(document).notices, state.notices, "the notices themselves are unchanged")
        assertTrue(state.noticeSources.keys.all { it in state.notices })
    }

    @Test
    fun anUnsupportedComponentIsItsOwnSource() {
        val component = CircuitComponent("mux-1", ComponentKind.MULTIPLEXER, GridPoint(100, 100))
        val registry = ComponentCatalog.registry.withDefinitions(listOf(
            ComponentCatalog.registry.requireDefinition(ComponentKind.MULTIPLEXER).copy(behavior = null)))
        val state = CircuitSimulator(registry = registry).settle(CircuitDocument("main", listOf(component)))
        val notice = state.notices.single { it.key == Messages.CoreSimulationUnsupportedComponent }
        assertEquals(SimulationNoticeSource(listOf("mux-1")), state.noticeSources[notice])
    }

    @Test
    fun aSourceInsideASubcircuitIsShownThroughTheInstanceThatHoldsIt() {
        val registry = ComponentCatalog.registry.withDefinitions(listOf(
            ComponentCatalog.registry.requireDefinition(ComponentKind.MULTIPLEXER).copy(behavior = null)))
        val inner = CircuitDocument("inner", listOf(CircuitComponent("mux-1", ComponentKind.MULTIPLEXER, GridPoint(100, 100))))
        val sub = CircuitComponent("sub", ComponentKind.SUBCIRCUIT, GridPoint(200, 200), circuitName = "inner")
        val state = CircuitSimulator(registry = registry).settle(CircuitProject(listOf(CircuitDocument("main", listOf(sub)), inner), "main"))
        val notice = state.notices.single { it.key == Messages.CoreSimulationUnsupportedComponent }
        // The main circuit has no mux-1 to show, so it shows the instance that holds it; the instance view shows mux-1.
        assertEquals(SimulationNoticeSource(listOf("sub")), state.noticeSources[notice])
        assertEquals(SimulationNoticeSource(listOf("mux-1")), state.circuitStates.getValue("sub").noticeSources[notice])
    }

    @Test
    fun aNestedSourceIsShownThroughTheInstanceAtEachLevel() {
        val registry = ComponentCatalog.registry.withDefinitions(listOf(
            ComponentCatalog.registry.requireDefinition(ComponentKind.MULTIPLEXER).copy(behavior = null)))
        val inner = CircuitDocument("inner", listOf(CircuitComponent("mux-1", ComponentKind.MULTIPLEXER, GridPoint(100, 100))))
        val middle = CircuitDocument("middle", listOf(CircuitComponent("in1", ComponentKind.SUBCIRCUIT, GridPoint(100, 100),
            circuitName = "inner")))
        val main = CircuitDocument("main", listOf(
            CircuitComponent("m1", ComponentKind.SUBCIRCUIT, GridPoint(100, 100), circuitName = "middle"),
            CircuitComponent("m2", ComponentKind.SUBCIRCUIT, GridPoint(300, 100), circuitName = "middle")))
        val state = CircuitSimulator(registry = registry).settle(CircuitProject(listOf(main, middle, inner), "main"))
        val notice = state.notices.single { it.key == Messages.CoreSimulationUnsupportedComponent }
        assertEquals(SimulationNoticeSource(listOf("m1", "m2")), state.noticeSources[notice], "both instances hold it")
        assertEquals(SimulationNoticeSource(listOf("in1")), state.circuitStates.getValue("m1").noticeSources[notice])
        assertEquals(SimulationNoticeSource(listOf("in1")), state.circuitStates.getValue("m2").noticeSources[notice])
        assertEquals(SimulationNoticeSource(listOf("mux-1")), state.circuitStates.getValue("m1/in1").noticeSources[notice])
    }

    @Test
    fun aCircuitWithoutWarningsHasNoSourcesAndKeepsItsValues() {
        val input = pin("a", 100)
        val output = pin("y", 300, output = true)
        val document = CircuitDocument("main", listOf(input, output), listOf(WireSegment(input.position, output.position)))
        val state = CircuitSimulator().settle(document, inputs = mapOf("a" to LogicValue.HIGH))
        assertEquals(emptyMap(), state.noticeSources)
        assertEquals(LogicValue.HIGH, state.componentValues["y"])
    }
}
