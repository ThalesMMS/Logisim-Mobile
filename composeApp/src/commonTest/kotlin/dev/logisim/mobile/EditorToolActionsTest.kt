package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorToolActionsTest {
    @Test
    fun insertionSelectsNewComponentAndSharesUndoHistory() {
        val state = EditorState(null)
        val original = state.document
        assertTrue(state.insertComponent(ComponentKind.NOT, GridPoint(400, 400)))
        assertEquals(original.components.size + 1, state.document.components.size)
        assertEquals(ComponentKind.NOT, state.selected?.kind)
        state.undo()
        assertEquals(original, state.document)
        assertFalse(state.insertComponent(ComponentKind("missing-extension"), GridPoint(0, 0)))
        assertEquals(original, state.document)
    }

    @Test
    fun connectionActionUsesTheSameCircuitHistoryAsTheWireTool() {
        val state = EditorState(null)
        val first = state.document.components.first { it.kind == ComponentKind.INPUT }.position
        val last = state.document.components.first { it.kind == ComponentKind.OUTPUT }.position
        val original = state.document
        assertFalse(state.connectTerminals(GridPoint(-100, -100), last))
        assertEquals(original, state.document)
        assertTrue(state.connectTerminals(first, last))
        state.undo()
        assertEquals(original, state.document)
    }

    @Test
    fun focusLocationSelectsItsComponentsAndRequestsSelectionFraming() {
        val state = EditorState(null)
        val id = state.document.components.first().id
        state.focusLocation(EditorLocation(state.document.id, componentIds = setOf(id)))
        assertEquals(setOf(id), state.selectedIds)
        assertTrue(state.fitSelection)
        val original = state.activeTarget
        state.focusLocation(EditorLocation(state.document.id, instancePath = listOf("missing")))
        assertEquals(original, state.activeTarget)
        assertEquals(setOf(id), state.selectedIds)
    }

    @Test
    fun activationChangesRootInputAndRejectsAMissingComponent() {
        val input = CircuitComponent("input", ComponentKind.INPUT, GridPoint(0, 0))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(CircuitDocument("main", listOf(input))))))
        val id = state.document.components.single().id
        assertEquals(LogicValue.LOW, state.simulation.componentValues[id])
        assertTrue(state.activateComponent(id))
        assertEquals(LogicValue.HIGH, state.simulation.componentValues[id])
        assertFalse(state.activateComponent("missing"))
    }
    @Test
    fun activationCannotOverrideASubcircuitInstanceInput() {
        val child = CircuitDocument("child", listOf(CircuitComponent("pin", ComponentKind.INPUT, GridPoint(0, 0))))
        val main = CircuitDocument("main", listOf(CircuitComponent("child-instance", ComponentKind.SUBCIRCUIT,
            GridPoint(100, 100), circuitName = "child")))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(main, child))))
        val importedChild = state.project.circuit("child")!!
        val instanceId = state.project.mainCircuit.components.single().id
        state.navigate(CircuitNavigationTarget(importedChild.id, importedChild.name, listOf(instanceId)))
        val original = state.rootSimulation
        assertTrue(state.activateComponent(state.document.components.single().id))
        assertTrue(state.canConfirmDebugCopy)
        assertEquals(original, state.rootSimulation)
    }

}
