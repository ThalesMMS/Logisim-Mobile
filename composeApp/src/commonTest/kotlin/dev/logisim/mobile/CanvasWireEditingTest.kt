package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.inputPorts
import dev.logisim.mobile.core.outputPorts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasWireEditingTest {
    private val registry = ComponentCatalog.registry

    // A phone fit view: 0.85 dp per circuit unit at density 2.625.
    private val touch = canvasPointerTolerance(PointerType.Touch, 2.625f, 0.85f * 2.625f)
    private val mouse = canvasPointerTolerance(PointerType.Mouse, 2.625f, 0.85f * 2.625f)

    private val stylus = canvasPointerTolerance(PointerType.Stylus, 2.625f, 0.85f * 2.625f)

    private fun CircuitDocument.editStart(point: Offset, tolerance: CanvasPointerTolerance): GridPoint? =
        editToolWireStart(point, tolerance.wire, registry, true, emptySet(), false, tolerance.editStart)

    private fun CircuitDocument.wireEnd(point: Offset, tolerance: CanvasPointerTolerance, from: GridPoint? = null): GridPoint? =
        editToolConnectionPoint(point, canvasPoint(point, true), tolerance.wire, registry, tolerance.terminal, from = from)

    private fun Offset.distanceTo(point: GridPoint) = (this - Offset(point.x.toFloat(), point.y.toFloat())).getDistance()

    @Test
    fun aStubFromAPortKeepsItsGridPointBesideANeighbourPortOnePitchAway() {
        val gate = registry.create(ComponentKind.AND, "and", GridPoint(200, 100), mapOf("inputs" to "5"))
        val inputs = gate.inputPorts(registry).sortedBy { it.y }
        assertEquals(10, inputs[1].y - inputs[0].y)
        val document = CircuitDocument(components = listOf(gate))
        // Ten units left of inputs[1], its neighbours are 14.1 units away, within the finger's 20-unit port reach.
        val stub = Offset(inputs[1].x - 10f, inputs[1].y.toFloat())
        assertTrue(stub.distanceTo(inputs[0]) < touch.terminal && stub.distanceTo(inputs[2]) < touch.terminal)
        assertNull(document.wireEnd(stub, touch, from = inputs[1]))
        assertNull(document.wireEnd(stub, mouse, from = inputs[1]))
        // Turned towards the neighbour and released beside it, nearer to it than to the start, it is still a stub:
        // a port within a fingertip of the start is joined only from the precise radius.
        val beside = Offset(inputs[1].x - 10f, inputs[0].y.toFloat())
        assertTrue(beside.distanceTo(inputs[0]) < beside.distanceTo(inputs[1]))
        assertNull(document.wireEnd(beside, touch, from = inputs[1]))
        assertEquals(inputs[0], document.wireEnd(Offset(inputs[0].x - 5f, inputs[0].y + 2f), touch, from = inputs[1]))
        // Zoomed out to 0.7 dp per unit, the 24-unit reach also spans a 20-unit stub's 22.4 units to the neighbour.
        val zoomedOut = canvasPointerTolerance(PointerType.Touch, 2.625f, 0.7f * 2.625f)
        val twenty = Offset(inputs[1].x - 20f, inputs[1].y.toFloat())
        assertTrue(twenty.distanceTo(inputs[0]) < zoomedOut.terminal)
        assertNull(document.wireEnd(twenty, zoomedOut, from = inputs[1]))
        // A long drag still reaches the port from a fingertip away, where the mouse does not.
        val far = GridPoint(inputs[1].x - 200, inputs[1].y + 40)
        val near = Offset(inputs[1].x - 14f, inputs[1].y + 3f)
        assertEquals(inputs[1], document.wireEnd(near, touch, from = far))
        assertNull(document.wireEnd(near, mouse, from = far))
    }

    @Test
    fun aDraggedEndReachesAPortBeyondThePreciseRadiusOnlyNearerToItThanToTheStart() {
        val gate = registry.create(ComponentKind.AND, "and", GridPoint(200, 300))
        val not = registry.create(ComponentKind.NOT, "not", GridPoint(260, 300))
        val document = CircuitDocument(components = listOf(gate, not))
        val output = gate.outputPorts(registry).single()
        val input = not.inputPorts(registry).single()
        assertEquals(30, input.x - output.x)
        // Twelve units out of the output, 18 short of the NOT input: a stub, not a 30-unit wire to the input.
        assertNull(document.wireEnd(Offset(output.x + 12f, output.y.toFloat()), touch, from = output))
        // Twelve units short of the input, nearer to it than to the start: the finger reaches it.
        assertEquals(input, document.wireEnd(Offset(output.x + 18f, output.y.toFloat()), touch, from = output))
        assertNull(document.wireEnd(Offset(output.x + 18f, output.y.toFloat()), mouse, from = output))
    }

    @Test
    fun aStubFromADefaultSplitterFanOutEndKeepsItsGridPoint() {
        val splitter = registry.create(ComponentKind.SPLITTER, "split", GridPoint(200, 100))
        val ends = (splitter.inputPorts(registry) + splitter.outputPorts(registry))
            .distinct().filter { it != splitter.position }.sortedBy { it.y }
        assertEquals(2, ends.size)
        assertEquals(10, ends[1].y - ends[0].y)
        assertEquals(ends[0].x, ends[1].x)
        val document = CircuitDocument(components = listOf(splitter))
        for ((end, other) in listOf(ends[0] to ends[1], ends[1] to ends[0])) {
            val stub = Offset(end.x + 10f, end.y.toFloat())
            assertTrue(stub.distanceTo(other) < touch.terminal, "$end")
            assertNull(document.wireEnd(stub, touch, from = end), "$end")
        }
        // A wire drawn to a fan-out end from afar still reaches it from a fingertip away.
        assertEquals(ends[1], document.wireEnd(Offset(ends[1].x + 13f, ends[1].y + 2f), touch,
            from = GridPoint(ends[1].x + 200, ends[1].y)))
    }

    @Test
    fun aStylusPressOutsideItsHaloBesideAPortStartsARubberBandNotAWire() {
        val gate = registry.create(ComponentKind.AND, "and", GridPoint(200, 100))
        val document = CircuitDocument(components = listOf(gate))
        val output = gate.outputPorts(registry).single()
        assertEquals(gate.bounds(registry).right, output.x)
        // Twelve units past the output's edge: beyond the stylus's 8 dp halo, within its 17 dp port reach.
        val press = Offset(output.x + 12f, output.y.toFloat())
        assertTrue(12f > stylus.hit && 12f < stylus.terminal, "$stylus")
        assertEquals(CanvasHit.Empty, document.hit(press, stylus.hit, registry))
        assertNull(document.editStart(press, stylus))
        // A finger there is within its 22 dp halo and starts the wire from the port.
        assertEquals(output, document.editStart(press, touch))
        // Within the stylus's halo, past the port's edge, the stylus starts it too.
        assertEquals(output, document.editStart(Offset(output.x + 9f, output.y.toFloat()), stylus))
        // A stylus-drawn wire's end still reaches the port from 17 dp.
        assertEquals(output, document.wireEnd(press, stylus, from = GridPoint(output.x + 200, output.y)))
    }

    @Test
    fun aShortStubIsNotPulledBackOntoTheDragsOwnStart() {
        val gate = registry.create(ComponentKind.AND, "and", GridPoint(200, 300))
        val wire = WireSegment(GridPoint(100, 100), GridPoint(200, 100))
        val document = CircuitDocument(components = listOf(gate), wires = listOf(wire))
        val output = gate.outputPorts(registry).single()
        // Ten units from the output its own port is within the finger's radius; as the start it is no end.
        val stub = Offset(output.x + 10f, output.y.toFloat())
        assertEquals(output, document.wireEnd(stub, touch))
        assertNull(document.wireEnd(stub, touch, from = output))
        assertNull(document.wireEnd(Offset(output.x + 20f, output.y + 2f), touch, from = output))
        // From a wire end, a release rounding to the next grid point is a stub there, for the mouse too.
        val past = Offset(205f, 101f)
        assertEquals(wire.to, document.wireEnd(past, mouse))
        assertNull(document.wireEnd(past, mouse, from = wire.to))
        assertNull(document.wireEnd(past, touch, from = wire.to))
        // Other connections still attract the end.
        assertEquals(wire.to, document.wireEnd(past, touch, from = GridPoint(205, 140)))
    }

    @Test
    fun aFingerTenDpBesideAnAndOutputStartsAWireWhereTheMouseDoesNot() {
        val gate = registry.create(ComponentKind.AND, "and", GridPoint(200, 100))
        val document = CircuitDocument(components = listOf(gate))
        val output = gate.outputPorts(registry).single()
        val tenDp = Offset(output.x + 10f / 0.85f, output.y.toFloat())
        assertEquals(output, document.editStart(tenDp, touch))
        assertNull(document.editStart(tenDp, mouse))
        val twelveUnits = Offset(output.x + 12f, output.y + 3f)
        assertEquals(output, document.editStart(twelveUnits, touch))
        assertNull(document.editToolWireStart(twelveUnits, 8f, registry, true, emptySet(), false))
        // Inside the body, 12 units from the output, the Edit tool still drags the gate.
        val body = gate.bounds(registry)
        assertTrue(output.x - 12 > body.left)
        assertNull(document.editStart(Offset(output.x - 12f, output.y.toFloat()), touch))
        // Within the mouse radius the port is reached from inside the body too, as before.
        assertEquals(output, document.editStart(Offset(output.x - 5f, output.y.toFloat()), touch))
    }

    @Test
    fun aDraggedWireEndsOnTheNearestOfAdjacentPorts() {
        val gate = registry.create(ComponentKind.AND, "and", GridPoint(200, 100), mapOf("inputs" to "5"))
        val inputs = gate.inputPorts(registry).sortedBy { it.y }
        assertEquals(10, inputs[2].y - inputs[1].y)
        val document = CircuitDocument(components = listOf(gate))
        val beside = Offset(inputs[1].x - 10f, inputs[1].y + 3f)
        assertEquals(inputs[1], document.wireEnd(beside, touch))
        assertEquals(inputs[2], document.wireEnd(Offset(inputs[2].x - 10f, inputs[2].y - 2f), touch))
        assertNull(document.wireEnd(beside, mouse))
    }

    @Test
    fun aParallelWireOnePitchAwayKeepsItsOwnGridPointsAtBothEnds() {
        val document = CircuitDocument(wires = listOf(WireSegment(GridPoint(100, 100), GridPoint(200, 100))))
        // Wire ends keep the precise radius: a wire drawn 10 units below neither retraces nor joins this one.
        for (x in listOf(100f, 150f, 200f, 210f)) {
            assertNull(document.wireEnd(Offset(x, 110f), touch), "x = $x")
            assertNull(document.wireEnd(Offset(x, 110f), mouse), "x = $x")
        }
        // On the wire, or within the precise radius of an end, it still connects.
        assertEquals(GridPoint(150, 100), document.wireEnd(Offset(151f, 104f), touch))
        assertEquals(GridPoint(200, 100), document.wireEnd(Offset(205f, 104f), touch))
    }

    @Test
    fun aFingerReachesAPortOnlyPastTheBodyEdgeItSitsOn() {
        val gate = registry.create(ComponentKind.NOT, "not", GridPoint(200, 100))
        val document = CircuitDocument(components = listOf(gate))
        val output = gate.outputPorts(registry).single()
        val body = gate.bounds(registry)
        assertEquals(body.right, output.x)
        // Just above the small body's right end, within the finger's port radius, the Edit tool drags the gate...
        val above = Offset(output.x - 4f, body.top - 2f)
        val distance = (above - Offset(output.x.toFloat(), output.y.toFloat())).getDistance()
        assertTrue(distance > touch.wire && distance < touch.terminal, "distance $distance")
        assertNull(document.editStart(above, touch))
        // ...while past the right edge, as far from the port, it starts a wire there.
        assertEquals(output, document.editStart(Offset(output.x + 4f, above.y), touch))
        // A dragged wire's end reaches the port from either side.
        assertEquals(output, document.wireEnd(above, touch))
    }

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
