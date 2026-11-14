package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircProjectImportResult
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MatrixPlacementTest {
    private fun emptyEditor() = EditorState(CircCodec.writeProject(CircuitProject(listOf(CircuitDocument()))))

    @Test
    fun placesLabeledCopiesAtTheRequestedSpacingAndUndoesThemTogether() {
        val state = emptyEditor()
        val original = state.project
        val template = state.componentRegistry.create(
            ComponentKind.NOT, "prototype", GridPoint(0, 0), mapOf("facing" to "north"), label = "Original",
        )
        state.place(template)
        state.togglePlacementMatrix()
        state.tap(GridPoint(100, 200))
        assertFalse(state.canUndo)

        assertTrue(state.confirmMatrixPlacement(assertNotNull(state.pendingMatrixPlacement), 2, 3, 50, 70, "INV"))
        val expected = mapOf(
            "INV_X0_Y0" to GridPoint(100, 200),
            "INV_X0_Y1" to GridPoint(100, 270),
            "INV_X0_Y2" to GridPoint(100, 340),
            "INV_X1_Y0" to GridPoint(150, 200),
            "INV_X1_Y1" to GridPoint(150, 270),
            "INV_X1_Y2" to GridPoint(150, 340),
        )
        val components = state.document.components
        assertEquals(6, components.size)
        assertEquals(expected, components.associate { it.label to it.position })
        assertTrue(components.all { it.kind == ComponentKind.NOT && it.facing == "north" })
        assertEquals(components.map { it.id }.toSet(), state.selectedIds)
        assertEquals(EditorMode.EDIT, state.mode)
        assertFalse(state.placementMatrixEnabled)
        assertNull(state.pendingMatrixPlacement)
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(state.exportXml()))
        assertEquals(expected, reopened.project.mainCircuit.components.associate { it.label to it.position })
        state.undo()
        assertEquals(original, state.project)
        assertFalse(state.canUndo)
        state.redo()
        assertEquals(expected, state.document.components.associate { it.label to it.position })
    }

    @Test
    fun cancelLeavesTheCircuitAndHistoryUntouchedAndDiscardsTheOldOrigin() {
        val state = emptyEditor()
        state.afterPlacement = EditorAfterPlacement.KEEP_TOOL
        val before = state.project
        state.place(ComponentKind.LED)
        state.togglePlacementMatrix()
        state.tap(GridPoint(100, 200))
        val cancelled = assertNotNull(state.pendingMatrixPlacement)
        state.cancelMatrixPlacement()
        assertNull(state.pendingMatrixPlacement)
        assertEquals(before, state.project)
        assertEquals(0, state.revision)
        assertFalse(state.canUndo)

        state.tap(GridPoint(300, 400))
        val active = assertNotNull(state.pendingMatrixPlacement)
        assertFalse(state.confirmMatrixPlacement(cancelled, 2, 3, 50, 70, "OLD"))
        assertFalse(state.confirmMatrixPlacement(active, 0, 3, 50, 70, "INVALID"))
        assertFalse(state.confirmMatrixPlacement(active, 2, 3, 51, 70, "INVALID"))
        assertEquals(before, state.project)
        assertTrue(state.confirmMatrixPlacement(active, 1, 1, 50, 70, ""))
        assertEquals(GridPoint(300, 400), state.document.components.single().position)
        assertEquals("", state.document.components.single().label)

        state.tap(GridPoint(400, 500))
        assertNull(state.pendingMatrixPlacement)
        assertEquals(2, state.document.components.size)
    }

    @Test
    fun aTickFrequencyChangeKeepsThePendingMatrixPlacement() {
        val state = emptyEditor()
        state.place(ComponentKind.NOT)
        state.togglePlacementMatrix()
        state.tap(GridPoint(100, 200))
        val placement = assertNotNull(state.pendingMatrixPlacement)
        val document = state.document
        // Simulate > Tick frequency while the matrix dialog waits: the main circuit's document is replaced.
        assertTrue(state.setTickFrequency(8.0))
        assertNotSame(document, state.document)
        assertSame(placement, state.pendingMatrixPlacement)
        assertTrue(state.placementMatrixEnabled)
        assertTrue(state.confirmMatrixPlacement(placement, 2, 1, 50, 70, "INV"))
        assertEquals(listOf("INV_X0_Y0", "INV_X1_Y0"), state.document.components.map { it.label })
        assertTrue("""<a name="simulationFrequency" val="8.0"/>""" in state.exportXml())
        // Any other change of the document still drops a pending placement.
        state.place(ComponentKind.NOT)
        state.togglePlacementMatrix()
        state.tap(GridPoint(300, 200))
        assertNotNull(state.pendingMatrixPlacement)
        state.undo()
        assertNull(state.pendingMatrixPlacement)
    }

    @Test
    fun invalidOrOccupiedLabelsLeaveTheMatrixUnplaced() {
        val state = emptyEditor()
        val existing = state.componentRegistry.create(
            ComponentKind.LED, "prototype", GridPoint(0, 0), label = "led_X1_Y2",
        )
        state.place(existing)
        state.tap(GridPoint(10, 20))
        state.place(existing)
        val before = state.project
        state.togglePlacementMatrix()
        state.tap(GridPoint(100, 200))
        val placement = assertNotNull(state.pendingMatrixPlacement)
        assertFalse(state.confirmMatrixPlacement(placement, 2, 3, 50, 70, "LED"))
        assertFalse(state.confirmMatrixPlacement(placement, 2, 3, 50, 70, "Invalid_"))
        assertEquals(before, state.project)
        assertTrue(state.confirmMatrixPlacement(placement, 2, 3, 50, 70, "NewLed"))
        state.undo()
        assertEquals(before, state.project)
    }
}
