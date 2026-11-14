package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.logisim.mobile.core.GridPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** C39: new annotations start at the grid point in the middle of the canvas in view. */
class ViewportCenterTest {
    private val xml = """<project source="4.1.0" version="1.0"><main name="main"/><circuit name="main"/></project>"""

    @Test
    fun anUnmeasuredCanvasHasNoCentre() {
        assertNull(EditorState(xml).visibleCenter)
    }

    @Test
    fun theCentreFollowsPanAndZoomOnTheGrid() {
        val state = EditorState(xml)
        // 400 x 600 px at scale 2, panned so world (1000, 2000) is at the top left.
        state.updateViewport(CanvasViewportState(CircuitViewport(2f, Offset(-2000f, -4000f)), IntSize(400, 600),
            initialized = true, fitted = false))
        assertEquals(GridPoint(1100, 2150), state.visibleCenter)
        state.updateViewport(CanvasViewportState(CircuitViewport(1f, Offset(13f, -7f)), IntSize(390, 643),
            initialized = true, fitted = false))
        // World (182, 328.5) snaps to the nearest grid point.
        assertEquals(GridPoint(180, 330), state.visibleCenter)
    }

    @Test
    fun theToolContextOffersTheCentreToTheAnnotationPanel() {
        val state = EditorState(xml)
        state.updateViewport(CanvasViewportState(CircuitViewport(1f, Offset(-500f, -500f)), IntSize(200, 200),
            initialized = true, fitted = false))
        val context = EditorToolContext(state.project, state.document, state.simulation, emptyList(), {}, {}, {}, {}, {},
            visibleCenter = state::visibleCenter)
        assertEquals(GridPoint(600, 600), context.visibleCenter())
        // Read when asked, so the context does not carry a stale value or subscribe its builder to the viewport.
        state.updateViewport(CanvasViewportState(CircuitViewport(1f, Offset(-100f, -100f)), IntSize(200, 200),
            initialized = true, fitted = false))
        assertEquals(GridPoint(200, 200), context.visibleCenter())
    }
}
