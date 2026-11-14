package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDiagnosticAnalyzer
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.CircuitStatistics
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.DiagnosticCode
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.WireSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticNavigationTest {
    @Test
    fun openingAnInstanceDiagnosticSelectsItsOriginAndFramesItWithoutEditingTheProject() {
        val child = CircuitDocument("child", components = listOf(
            CircuitComponent("source", ComponentKind.INPUT, GridPoint(0, 0), attributes = mapOf("width" to "8")),
            CircuitComponent("sink", ComponentKind.OUTPUT, GridPoint(100, 0), attributes = mapOf("width" to "4"))),
            wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(100, 0))))
        val main = CircuitDocument("main", components = listOf(CircuitComponent("instance",
            ComponentKind.SUBCIRCUIT, GridPoint(100, 100), circuitName = "child")))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(main, child))))
        val original = state.project
        val instanceId = original.mainCircuit.components.single().id
        val diagnostic = CircuitDiagnosticAnalyzer.analyze(state.project, state.rootSimulation)
            .diagnostics.single { it.code == DiagnosticCode.WIDTH_MISMATCH }
        state.focusLocation(diagnostic.target.editorLocation())
        assertEquals("child", state.activeCircuitName)
        assertEquals(listOf(instanceId), state.activeInstancePath)
        assertEquals(diagnostic.target.componentIds, state.selectedIds)
        assertEquals(diagnostic.target.wires, state.selectedWires)
        assertTrue(state.fitSelection)
        assertEquals(original, state.project)
    }

    @Test
    fun statisticsFollowTheEditorInsertionDeletionAndUndoHistory() {
        val state = EditorState(null)
        val count = CircuitStatistics.measure(state.project).definitionComponentCount
        assertTrue(state.insertComponent(ComponentKind.NOT, GridPoint(500, 500)))
        assertEquals(count + 1, CircuitStatistics.measure(state.project).definitionComponentCount)
        state.removeSelected()
        assertEquals(count, CircuitStatistics.measure(state.project).definitionComponentCount)
        state.undo()
        assertEquals(count + 1, CircuitStatistics.measure(state.project).definitionComponentCount)
        state.undo()
        assertEquals(count, CircuitStatistics.measure(state.project).definitionComponentCount)
    }

    @Test
    fun goingToAnOriginNavigatesAndClosesTheSheetThatCoversTheCanvas() {
        val child = CircuitDocument("child", components = listOf(
            CircuitComponent("source", ComponentKind.INPUT, GridPoint(0, 0), attributes = mapOf("width" to "8")),
            CircuitComponent("sink", ComponentKind.OUTPUT, GridPoint(100, 0), attributes = mapOf("width" to "4"))),
            wires = listOf(WireSegment(GridPoint(0, 0), GridPoint(100, 0))))
        val project = CircuitProject(listOf(child))
        val diagnostic = CircuitDiagnosticAnalyzer.analyze(project, EditorState(CircCodec.writeProject(project)).rootSimulation)
            .diagnostics.first { it.code == DiagnosticCode.WIDTH_MISMATCH }
        val events = mutableListOf<String>()
        var location: EditorLocation? = null
        val context = EditorToolContext(project, child, SimulationState(), emptyList(), {}, {}, {}, {}, {},
            navigate = { location = it; events += "navigate" }, closeTool = { events += "close" })
        diagnosticNavigation(context)(diagnostic.target)
        assertEquals(listOf("navigate", "close"), events)
        assertEquals(diagnostic.target.editorLocation(), location)
    }

    @Test
    fun anImportFailureCannotNavigateToAnUnrelatedOpenProject() {
        val state = EditorState(null)
        val target = state.activeTarget
        val original = state.project
        val failure = CircuitDiagnosticAnalyzer.importFailure("Malformed XML", "external-file")
        state.focusLocation(failure.target.editorLocation())
        assertEquals(target, state.activeTarget)
        assertEquals(original, state.project)
    }
}
