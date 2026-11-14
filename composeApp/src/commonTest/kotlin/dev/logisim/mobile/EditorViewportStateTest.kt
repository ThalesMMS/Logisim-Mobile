package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.WorkspaceViewportKeyFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorViewportStateTest {
    @Test
    fun exactZoomPreservesTheCanvasCenterAndDoesNotChangeTheProject() {
        val state = EditorState(null)
        val size = IntSize(800, 600)
        val center = Offset(400f, 300f)
        state.updateViewport(CanvasViewportState(CircuitViewport(2f, Offset(-80f, 120f)), size, true, true))
        val project = state.project
        val history = state.undoHistory

        for ((input, expectedScale) in listOf("137%" to 1.37f, "137,5" to 1.375f, "100" to 1f, "5" to 0.05f, "1000" to 10f, "1200" to 12f)) {
            state.zoomTo(checkNotNull(parseZoomPercent(input)) / 100f)

            val view = state.viewport.view
            assertEquals(expectedScale, view.scale)
            assertEquals(240f, view.world(center).x, absoluteTolerance = 0.001f)
            assertEquals(90f, view.world(center).y, absoluteTolerance = 0.001f)
            assertFalse(state.viewport.fitted)
            assertEquals(project, state.project)
            assertEquals(history, state.undoHistory)
        }
    }

    @Test
    fun invalidZoomInputCannotSetTheViewport() {
        for (input in listOf("", "0", "4.99%", "1200.00001%", "137%%", "NaN", "Infinity")) {
            assertNull(parseZoomPercent(input), input)
        }
    }

    @Test
    fun toolbarRepositioningAndCompactResizeKeepSelectionAndTheManualWorldCenter() {
        val state = EditorState(null)
        state.selectAll()
        val selected = state.selectedIds
        val wires = state.selectedWires
        assertTrue(selected.isNotEmpty())
        assertTrue(wires.isNotEmpty())
        val project = state.project
        val requests = state.viewportReset
        state.updateViewport(CanvasViewportState(CircuitViewport(2f, Offset(-80f, 120f)), IntSize(800, 600), true, false))
        for (size in listOf(IntSize(671, 648), IntSize(400, 600), IntSize(671, 648), IntSize(800, 600))) {
            state.updateViewport(state.viewport.resized(state.document, size, 24f, 64f))
            assertEquals(Offset(240f, 90f), state.viewport.view.world(Offset(size.width / 2f, size.height / 2f)))
            assertEquals(2f, state.viewport.view.scale)
            assertEquals(selected, state.selectedIds)
            assertEquals(wires, state.selectedWires)
            assertEquals(project, state.project)
            assertEquals(requests, state.viewportReset)
        }
    }

    @Test
    fun navigationRestoresIndependentCircuitViewportsWithoutFittingAgain() {
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(
            CircuitDocument("main"), CircuitDocument("child"),
        ))))
        val first = CanvasViewportState(CircuitViewport(2f, Offset(-75f, 120f)), IntSize(800, 600), true, false)
        state.updateViewport(first)
        val requests = state.viewportReset
        state.selectCircuit("child")
        assertFalse(state.viewport.initialized)
        val second = CanvasViewportState(CircuitViewport(0.5f, Offset(10f, -20f)), IntSize(800, 600), true, false)
        state.updateViewport(second)
        state.selectCircuit("main")
        assertEquals(first, state.viewport)
        assertEquals(requests, state.viewportReset)
        state.selectCircuit("child")
        assertEquals(second, state.viewport)
    }

    @Test
    fun fitSelectionRequiresASelectionAndDoesNotModifyTheDocument() {
        val state = EditorState(null)
        val original = state.project
        val requests = state.viewportReset
        state.fitSelected()
        assertEquals(requests, state.viewportReset)
        state.selectAll()
        state.fitSelected()
        assertTrue(state.fitSelection)
        assertEquals(requests + 1, state.viewportReset)
        assertEquals(original, state.project)
        state.fitViewport()
        assertFalse(state.fitSelection)
    }

    @Test
    fun exportedViewsRestoreDefinitionIdsContainingColonsWithoutLegacyRemapping() {
        val xml = CircCodec.writeProject(CircuitProject(listOf(
            CircuitDocument("main", id = "root:definition"), CircuitDocument("child", id = "child:definition"),
        )))
        val source = EditorState(xml)
        val first = CanvasViewportState(CircuitViewport(2f, Offset(-75f, 120f)), IntSize(800, 600), true, false)
        val second = CanvasViewportState(CircuitViewport(0.5f, Offset(10f, -20f)), IntSize(800, 600), true, false)
        source.updateViewport(first)
        source.selectCircuit("child")
        source.updateViewport(second)
        val saved = source.exportViewState()
        assertEquals(WorkspaceViewportKeyFormat.LENGTH_PREFIXED, saved.viewportKeyFormat)

        val restored = EditorState(xml)
        restored.importViewState(saved)
        assertEquals("child", restored.activeCircuitName)
        assertEquals(second.view, restored.viewport.view)
        restored.selectCircuit("main")
        assertEquals(first.view, restored.viewport.view)
        assertEquals(source.project, restored.project)
    }
}
