package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.WireSegment
import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasInteractionTest {
    @Test
    fun aShortPressCreatesExactlyOneTap() {
        val gesture = CanvasGestureMachine(Offset(20f, 30f), CanvasDragAction.SELECT_AREA)
        gesture.move(Offset(22f, 32f), touchSlop = 8f, pointerCount = 1)
        assertEquals(CanvasGestureCompletion.Tap(Offset(22f, 32f)), gesture.finish())
        assertNull(gesture.finish())
    }

    @Test
    fun dragCommitsOnlyOnReleaseAndPreservesItsFirstPoint() {
        val gesture = CanvasGestureMachine(Offset(20f, 30f), CanvasDragAction.MOVE_SELECTION)
        assertEquals(CanvasGesturePhase.DRAGGING, gesture.move(Offset(40f, 30f), 8f, 1))
        gesture.move(Offset(50f, 60f), 8f, 1)
        assertEquals(CanvasGestureCompletion.Drag(CanvasDragAction.MOVE_SELECTION,
            Offset(20f, 30f), Offset(50f, 60f)), gesture.finish())
    }

    @Test
    fun addingASecondFingerCancelsAnEditForTheRestOfTheGesture() {
        val gesture = CanvasGestureMachine(Offset.Zero, CanvasDragAction.MOVE_SELECTION)
        gesture.move(Offset(20f, 0f), 8f, 1)
        assertEquals(CanvasGesturePhase.TRANSFORMING, gesture.move(Offset(30f, 0f), 8f, 2))
        assertEquals(CanvasGesturePhase.TRANSFORMING, gesture.move(Offset(40f, 0f), 8f, 1))
        assertNull(gesture.finish())
    }

    @Test
    fun aFingerCannotTurnAPenOrMouseDragIntoAPinchOrDelayItsRelease() {
        for (type in listOf(PointerType.Stylus, PointerType.Eraser, PointerType.Mouse)) {
            val owner = pointerChange(Offset.Zero, pressed = true, consumed = false, type = type)
            val finger = pointerChange(Offset(80f, 20f), pressed = true, consumed = false, id = 1)
            val destination = Offset(50f, 20f)
            val gesture = CanvasGestureMachine(owner.position, CanvasDragAction.MOVE_SELECTION)
            assertNull(PointerEvent(listOf(finger)).forCanvasGesture(owner))
            val moved = pointerChange(destination, pressed = true, consumed = false, type = type)
            val movement = requireNotNull(PointerEvent(listOf(moved, finger)).forCanvasGesture(owner))
            assertEquals(CanvasGesturePhase.DRAGGING, gesture.move(movement, owner.id, 8f))

            val released = pointerChange(destination, pressed = false, consumed = false, type = type)
            val release = requireNotNull(PointerEvent(listOf(released, finger)).forCanvasGesture(owner))
            assertTrue(release.changes.none { it.pressed })
            gesture.move(release, owner.id, 8f)
            assertEquals(CanvasGestureCompletion.Drag(CanvasDragAction.MOVE_SELECTION, Offset.Zero, destination), gesture.finish())
        }
    }

    @Test
    fun aTouchPinchUsesOnlyFingersEvenWhenThePenIsDown() {
        val finger = pointerChange(Offset.Zero, pressed = true, consumed = false)
        val secondFinger = pointerChange(Offset(20f, 0f), pressed = true, consumed = false, id = 1)
        val pen = pointerChange(Offset(100f, 0f), pressed = true, consumed = false, id = 2, type = PointerType.Stylus)
        val event = requireNotNull(PointerEvent(listOf(finger, secondFinger, pen)).forCanvasGesture(finger))

        assertEquals(listOf(finger.id, secondFinger.id), event.changes.map { it.id })
        val gesture = CanvasGestureMachine(finger.position, CanvasDragAction.MOVE_SELECTION)
        assertEquals(CanvasGesturePhase.TRANSFORMING, gesture.move(event, finger.id, 8f))
        assertNull(gesture.finish())
    }

    @Test
    fun focusLossOrEscapePreventsCommit() {
        val gesture = CanvasGestureMachine(Offset.Zero, CanvasDragAction.SELECT_AREA)
        gesture.move(Offset(50f, 60f), 8f, 1)
        gesture.cancel()
        assertEquals(CanvasGesturePhase.CANCELLED, gesture.move(Offset(100f, 120f), 8f, 1))
        assertNull(gesture.finish())
    }

    @Test
    fun consumedTerminalReleaseDoesNotCommitADrag() {
        val gesture = CanvasGestureMachine(Offset.Zero, CanvasDragAction.MOVE_SELECTION)
        gesture.move(pointerEvent(Offset(50f, 20f), pressed = true, consumed = false), PointerId(0), 8f)
        gesture.move(pointerEvent(Offset(50f, 20f), pressed = false, consumed = true), PointerId(0), 8f)
        assertNull(gesture.finish())
        assertNull(gesture.finish())
    }

    @Test
    fun anUnconsumedTerminalReleaseCommitsTheDrag() {
        val gesture = CanvasGestureMachine(Offset.Zero, CanvasDragAction.MOVE_SELECTION)
        gesture.move(pointerEvent(Offset(50f, 20f), pressed = true, consumed = false), PointerId(0), 8f)
        gesture.move(pointerEvent(Offset(50f, 20f), pressed = false, consumed = false), PointerId(0), 8f)
        assertEquals(CanvasGestureCompletion.Drag(CanvasDragAction.MOVE_SELECTION,
            Offset.Zero, Offset(50f, 20f)), gesture.finish())
    }

    @Test
    fun aConsumedPressedMoveStillAllowsTheUnconsumedRelease() {
        val gesture = CanvasGestureMachine(Offset.Zero, CanvasDragAction.MOVE_SELECTION)
        assertEquals(CanvasGesturePhase.DRAGGING,
            gesture.move(pointerEvent(Offset(50f, 20f), pressed = true, consumed = true), PointerId(0), 8f))
        gesture.move(pointerEvent(Offset(50f, 20f), pressed = false, consumed = false), PointerId(0), 8f)
        assertEquals(CanvasGestureCompletion.Drag(CanvasDragAction.MOVE_SELECTION,
            Offset.Zero, Offset(50f, 20f)), gesture.finish())
    }

    @Test
    fun aConsumedTerminalReleaseDoesNotCreateATap() {
        val gesture = CanvasGestureMachine(Offset.Zero, CanvasDragAction.SELECT_AREA)
        gesture.move(pointerEvent(Offset.Zero, pressed = false, consumed = true), PointerId(0), 8f)
        assertNull(gesture.finish())
    }

    private fun pointerEvent(position: Offset, pressed: Boolean, consumed: Boolean): PointerEvent =
        PointerEvent(listOf(pointerChange(position, pressed, consumed)))

    private fun pointerChange(
        position: Offset, pressed: Boolean, consumed: Boolean, id: Long = 0, type: PointerType = PointerType.Touch,
    ): PointerInputChange = PointerInputChange(
        id = PointerId(id), uptimeMillis = 100L, position = position, pressed = pressed, pressure = 1f,
        previousUptimeMillis = 100L, previousPosition = position, previousPressed = true,
        isInitiallyConsumed = consumed, type = type,
    )

    @Test
    fun selectionModeDragsWiresAndSelectsEmptyAreas() {
        val wire = CanvasHit.Wire(WireSegment(GridPoint(0, 0), GridPoint(100, 0)))
        assertEquals(CanvasDragAction.MOVE_SELECTION, canvasDragAction(EditorMode.SELECT, wire, false))
        assertEquals(CanvasDragAction.SELECT_AREA, canvasDragAction(EditorMode.SELECT, CanvasHit.Empty, false))
        assertEquals(CanvasDragAction.PAN, canvasDragAction(EditorMode.MOVE, CanvasHit.Empty, false))
        assertEquals(CanvasDragAction.SELECT_AREA, canvasDragAction(EditorMode.MOVE, CanvasHit.Empty, true))
        assertEquals(CanvasDragAction.PAN, canvasDragAction(EditorMode.WIRE, CanvasHit.Empty, true))
    }

    @Test
    fun deleteAndCancelHaveDedicatedCanvasKeys() {
        assertEquals(CanvasCommand.DELETE, fixedCanvasShortcut(Key.Backspace))
        assertEquals(CanvasCommand.CANCEL, fixedCanvasShortcut(Key.Escape))
        assertNull(fixedCanvasShortcut(Key.DirectionLeft))
    }
}
