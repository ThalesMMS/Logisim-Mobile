package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.PrintHeader
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.WireSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PagedCircuitPdfTest {
    @Test
    fun headersExpandWithoutReprocessingPercentSignsFromCircuitNames() {
        assertEquals("Circuito %P – 2/3 (100%) %x %",
            PrintHeader.expand("%n – %p/%P (100%%) %x %", "Circuito %P", 2, 3))
        assertEquals("", PrintHeader.expand("", "main", 1, 1))
        assertEquals("12", PrintHeader.expand("%p%P", "main", 1, 2))
    }

    @Test
    fun defaultsFollowEvolutionsPrintDialog() {
        val options = PagedCircuitPdfOptions()
        assertEquals(null, options.circuitIds)
        assertEquals("main (1 of 2)", PrintHeader.expand(options.header, "main", 1, 2))
        assertEquals(true, options.rotateToFit)
    }

    @Test
    fun pagesFollowProjectOrderAndNeverReuseAnotherCircuitsSelectionOrSignals() {
        val wire = WireSegment(GridPoint(0, 0), GridPoint(40, 0))
        val main = CircuitDocument(name = "Main", wires = listOf(wire))
        val empty = CircuitDocument(name = "Empty")
        val active = CircuitDocument(name = "Active", wires = listOf(wire))
        val inactive = CircuitDocument(name = "Inactive", wires = listOf(wire))
        val project = CircuitProject(listOf(main, empty, active, inactive))
        val activeState = SimulationState(circuitName = active.name, portValues = mapOf(wire.from to LogicValue.HIGH))
        val rootState = SimulationState(circuitName = main.name, portValues = mapOf(wire.from to LogicValue.LOW))
        val context = EditorToolContext(project, active, activeState, emptyList(),
            applyProject = { error("Print must not edit the project") },
            applyDocument = { error("Print must not edit the circuit") },
            selectCircuit = { error("Print must not change the active circuit") },
            selectComponent = { error("Print must not change the selection") },
            showStatus = {}, projectSimulation = rootState, selectedWires = setOf(wire))

        assertEquals(listOf(main, active, inactive), printableCircuits(context))
        assertSame(context, context.forVisualExport(active, ComponentCatalog.registry))
        val root = context.forVisualExport(main, ComponentCatalog.registry)
        assertSame(rootState, root.simulation)
        assertEquals(emptySet(), root.selectedWires)
        val other = context.forVisualExport(inactive, ComponentCatalog.registry)
        assertEquals(inactive.name, other.simulation.circuitName)
        assertEquals(emptySet(), other.selectedWires)
    }
}
