package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.inputPorts
import dev.logisim.mobile.core.outputPorts
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
    fun thePlaceToolDragsItsGhostInsteadOfPanning() {
        assertEquals(CanvasDragAction.PLACE, canvasDragAction(EditorMode.PLACE, CanvasHit.Empty, false, placing = true))
        assertEquals(CanvasDragAction.PLACE, canvasDragAction(EditorMode.PLACE, CanvasHit.Component("pin"), false, placing = true))
        // A paste, a secondary button or a middle-button pan is not a placement.
        assertEquals(CanvasDragAction.PAN, canvasDragAction(EditorMode.PLACE, CanvasHit.Empty, false))
        assertEquals(CanvasDragAction.SELECT_AREA, canvasDragAction(EditorMode.EDIT, CanvasHit.Empty, false, placing = true))

        // The release commits once; a second finger cancels the placement.
        val drag = CanvasGestureMachine(Offset(20f, 30f), CanvasDragAction.PLACE)
        drag.move(Offset(80f, 90f), 8f, 1)
        assertEquals(CanvasGestureCompletion.Drag(CanvasDragAction.PLACE, Offset(20f, 30f), Offset(80f, 90f)), drag.finish())
        val pinch = CanvasGestureMachine(Offset(20f, 30f), CanvasDragAction.PLACE)
        pinch.move(Offset(80f, 90f), 8f, 1)
        pinch.move(Offset(90f, 90f), 8f, 2)
        assertNull(pinch.finish())
    }

    @Test
    fun touchAndStylusReachTerminalsFromAFingertipAwayWhileTheMouseKeepsEvolutionsRadius() {
        // A phone fit view: 0.85 dp per circuit unit at density 2.625.
        val density = 2.625f
        val scale = 0.85f * density
        val mouse = canvasPointerTolerance(PointerType.Mouse, density, scale)
        assertEquals(8f, mouse.wire)
        assertEquals(mouse.wire, mouse.terminal)
        assertEquals(0f, mouse.poke)
        val touch = canvasPointerTolerance(PointerType.Touch, density, scale)
        assertEquals(22f / 0.85f, touch.hit, 1e-3f)
        assertEquals(8f, touch.wire)
        assertEquals(CanvasTouchWireToleranceDp / 0.85f, touch.terminal, 1e-3f)
        assertEquals(touch.hit, touch.poke)
        val pen = canvasPointerTolerance(PointerType.Stylus, density, scale)
        assertEquals(touch.terminal, pen.terminal)
        assertEquals(8f / 0.85f, pen.poke, 1e-3f)
        // Zoomed far in, the terminal radius never drops below the wire radius.
        val zoomed = canvasPointerTolerance(PointerType.Touch, density, 4f * density)
        assertEquals(zoomed.wire, zoomed.terminal)
    }

    @Test
    fun aWireBesideAnInputOutranksTheInputsTouchHalo() {
        val registry = ComponentCatalog.registry
        val pin = registry.create(ComponentKind.INPUT, "in", GridPoint(100, 100))
        val wire = WireSegment(GridPoint(100, 100), GridPoint(170, 100))
        val document = CircuitDocument(components = listOf(pin), wires = listOf(wire))
        val bounds = pin.bounds(registry)
        val tolerance = 22f / 0.85f
        // Evolution's PokeTool: a tap on the wire next to the pin pokes the wire, not the pin.
        assertEquals(CanvasHit.Wire(wire), document.hit(Offset(bounds.right + 12f, 100f), tolerance, registry))
        // Inside the pin, and on its port (on the bounds edge), it is the pin.
        assertEquals(CanvasHit.Component("in"), document.hit(Offset(bounds.right - 5f, 104f), tolerance, registry))
        assertEquals(CanvasHit.Component("in"), document.hit(Offset(100f, 100f), tolerance, registry))
        // Beside the pin, away from the wire, the 22 dp halo still reaches it.
        assertEquals(CanvasHit.Component("in"), document.hit(Offset(bounds.left - 12f, 100f), tolerance, registry))
        assertEquals(CanvasHit.Component("in"), document.hit(Offset(bounds.left + 5f, bounds.top - 6f), tolerance, registry))
        assertEquals(CanvasHit.Empty, document.hit(Offset(bounds.left - 40f, 100f), tolerance, registry))
    }

    @Test
    fun aShortWireBetweenTwoGatesCanBeSelected() {
        val registry = ComponentCatalog.registry
        val first = registry.create(ComponentKind.NOT, "first", GridPoint(200, 100))
        val output = first.outputPorts(registry).single()
        val reach = first.position.x - first.inputPorts(registry).single().x
        val second = registry.create(ComponentKind.NOT, "second", GridPoint(output.x + 20 + reach, 100))
        val wire = WireSegment(output, second.inputPorts(registry).single())
        assertEquals(20, wire.to.x - wire.from.x)
        val document = CircuitDocument(components = listOf(first, second), wires = listOf(wire))
        val tolerance = 22f / 0.85f
        assertEquals(CanvasHit.Wire(wire), document.hit(Offset(output.x + 10f, 100f), tolerance, registry))
        assertEquals(CanvasHit.Wire(wire), document.hit(Offset(output.x + 10f, 106f), tolerance, registry))
        assertEquals(CanvasHit.Component("first"), document.hit(Offset(output.x.toFloat(), 100f), tolerance, registry))
        assertEquals(CanvasHit.Component("second"), document.hit(Offset(wire.to.x.toFloat(), 100f), tolerance, registry))
    }

    @Test
    fun deleteAndCancelHaveDedicatedCanvasKeys() {
        assertEquals(CanvasCommand.DELETE, fixedCanvasShortcut(Key.Backspace))
        assertEquals(CanvasCommand.CANCEL, fixedCanvasShortcut(Key.Escape))
        assertNull(fixedCanvasShortcut(Key.DirectionLeft))
    }
}
