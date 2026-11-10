package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.WireSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CanvasWireEditingTest {
    @Test
    fun splitProjectsToTheChosenWireWithoutChangingItsFixedAxis() {
        val wire = WireSegment(GridPoint(-40, -10), GridPoint(40, -10))
        assertEquals(GridPoint(-10, -10), wireSplitPoint(wire, Offset(-13f, 8f), snapToGrid = true))
        assertEquals(GridPoint(-13, -10), wireSplitPoint(wire, Offset(-13f, 8f), snapToGrid = false))
        assertNull(wireSplitPoint(wire, Offset(70f, -10f), snapToGrid = true))
        assertNull(wireSplitPoint(wire, Offset(-40f, -10f), snapToGrid = false))
    }

    @Test
    fun reversedVerticalSegmentHasTheSameInteriorSplitPoint() {
        val wire = WireSegment(GridPoint(25, 70), GridPoint(25, -30))
        assertEquals(GridPoint(25, 20), wireSplitPoint(wire, Offset(12f, 23f), snapToGrid = true))
    }

    @Test
    fun handlesExcludePinsAndDoNotInventAJunctionAtAnUnmarkedCrossing() {
        val first = WireSegment(GridPoint(0, 0), GridPoint(40, 0))
        val second = WireSegment(GridPoint(40, 0), GridPoint(40, 40))
        val crossing = WireSegment(GridPoint(20, -20), GridPoint(20, 20))
        val document = CircuitDocument(components = listOf(
            CircuitComponent("pin", ComponentKind.INPUT, GridPoint(0, 0)),
        ), wires = listOf(first, second, crossing))
        assertEquals(listOf(GridPoint(40, 0), GridPoint(40, 40)), wireHandlePoints(document, setOf(first, second)))
    }

    @Test
    fun wireHandleHitUsesTheCurrentWorldCoordinatesAndTolerance() {
        val view = CircuitViewport(2f, Offset(100f, 80f))
        val handles = listOf(GridPoint(40, 0), GridPoint(40, 40))
        assertEquals(GridPoint(40, 0), nearestWireHandle(handles, view.world(Offset(182f, 82f)), 8f))
        assertNull(nearestWireHandle(handles, view.world(Offset(220f, 82f)), 8f))
    }

    @Test
    fun cornerMoveIsASeparateGestureAndCancellationProducesNoCommit() {
        val corner = CanvasHit.WireCorner(GridPoint(40, 0))
        assertEquals(CanvasDragAction.MOVE_WIRE_CORNER, canvasDragAction(EditorMode.MOVE, corner, false))
        val gesture = CanvasGestureMachine(Offset(180f, 80f), CanvasDragAction.MOVE_WIRE_CORNER)
        gesture.move(Offset(220f, 120f), 8f, 1)
        gesture.cancel()
        assertNull(gesture.finish())
    }

    @Test
    fun grabbingAHandleOffCenterDoesNotMakeTheCornerJump() {
        assertEquals(GridPoint(60, 20), movedWireHandle(GridPoint(40, 0), Offset(42f, 2f),
            Offset(62f, 22f), snapToGrid = false))
    }
}
