package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.bounds
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircuitViewportTest {
    @Test
    fun restoredViewKeepsItsSavedPanWhenTheFirstCanvasSizeArrives() {
        val restored = CanvasViewportState(CircuitViewport(0.5f, Offset(20f, 30f)), initialized = true, fitted = false)
        val measured = restored.resized(CircuitDocument(), IntSize(600, 300), 24f, 64f)
        assertEquals(restored.view, measured.view)
        assertEquals(IntSize(600, 300), measured.size)
    }

    @Test
    fun manuallyPositionedViewKeepsTheSameWorldCenterAfterResize() {
        val original = CanvasViewportState(
            view = CircuitViewport(0.5f, Offset(20f, 30f)),
            size = IntSize(400, 200), initialized = true, fitted = false,
        )
        val resized = original.resized(CircuitDocument(), IntSize(600, 300), 24f, 64f)
        assertEquals(Offset(360f, 140f), resized.view.world(Offset(300f, 150f)))
        assertEquals(0.5f, resized.view.scale)
        assertEquals(Offset(120f, 80f), resized.view.pan)
    }

    @Test
    fun fitSelectionExcludesUnselectedDistantGeometry() {
        val selected = CircuitComponent("chosen", ComponentKind.INPUT, GridPoint(-100, -100))
        val other = CircuitComponent("other", ComponentKind.INPUT, GridPoint(5000, 5000))
        val document = CircuitDocument(components = listOf(selected, other))
        val state = CanvasViewportState(size = IntSize(600, 400))
        val fitted = state.fit(document, 24f, 64f, selectedIds = setOf("chosen"), selectionOnly = true)
        val chosenScreen = Offset(-100f, -100f) * fitted.view.scale + fitted.view.pan
        val otherScreen = Offset(5000f, 5000f) * fitted.view.scale + fitted.view.pan
        assertTrue(chosenScreen.x in 24f..576f && chosenScreen.y in 24f..376f)
        assertTrue(otherScreen.x > 600f && otherScreen.y > 400f)
        assertTrue(!fitted.fitted)
    }

    @Test
    fun fitEmptySelectionUsesTheWholeCircuit() {
        val document = CircuitDocument(wires = listOf(WireSegment(GridPoint(-300, 0), GridPoint(900, 0))))
        val state = CanvasViewportState(size = IntSize(600, 400))
        val all = state.fit(document, 24f, 64f)
        val emptySelection = state.fit(document, 24f, 64f, selectionOnly = true)
        assertEquals(all.view, emptySelection.view)
    }

    @Test
    fun fitIncludesDistantWiresAndComponentsInPhoneCanvas() {
        val document = CircuitDocument(
            components = listOf(CircuitComponent("input", ComponentKind.INPUT, GridPoint(100, 100))),
            wires = listOf(
                WireSegment(GridPoint(-300, -500), GridPoint(2100, -500)),
                WireSegment(GridPoint(2100, -500), GridPoint(2100, 3000)),
            ),
        )
        val view = fittedViewport(document, IntSize(360, 500), 24f, 64f)
        val visible = document.wires.flatMap { listOf(it.from, it.to) } + document.components.flatMap {
            val bounds = it.bounds()
            listOf(GridPoint(bounds.left, bounds.top), GridPoint(bounds.right, bounds.bottom))
        }
        for (point in visible) {
            val screen = Offset(point.x.toFloat(), point.y.toFloat()) * view.scale + view.pan
            assertTrue(screen.x in 23.9f..336.1f, "x=${screen.x} for $point")
            assertTrue(screen.y in 23.9f..412.1f, "y=${screen.y} for $point")
        }
    }

    @Test
    fun fitIncludesHundredThousandUnitWiresAndNegativeCoordinates() {
        val document = CircuitDocument(wires = listOf(
            WireSegment(GridPoint(-50_000, -75_000), GridPoint(50_000, -75_000)),
            WireSegment(GridPoint(50_000, -75_000), GridPoint(50_000, 75_000)),
        ))
        val size = IntSize(360, 500)
        val view = fittedViewport(document, size, 24f, 64f)
        assertTrue(view.scale < 0.04f)
        assertPhonePointsVisible(document, view, size)
    }

    @Test
    fun fitIncludesWiresAcrossTheEntireIntegerCoordinateRange() {
        val document = CircuitDocument(wires = listOf(
            WireSegment(GridPoint(Int.MIN_VALUE, Int.MIN_VALUE), GridPoint(Int.MAX_VALUE, Int.MIN_VALUE)),
            WireSegment(GridPoint(Int.MAX_VALUE, Int.MIN_VALUE), GridPoint(Int.MAX_VALUE, Int.MAX_VALUE)),
        ))
        val size = IntSize(360, 500)
        val view = fittedViewport(document, size, 24f, 64f)
        assertTrue(view.scale > 0f && view.scale.isFinite())
        assertTrue(view.pan.x.isFinite() && view.pan.y.isFinite())
        assertPhonePointsVisible(document, view, size)
    }

    @Test
    fun fitWireSelectionIncludesItsLargeExtentAndExcludesUnselectedGeometry() {
        val selected = WireSegment(GridPoint(-50_000, -75_000), GridPoint(50_000, -75_000))
        val other = WireSegment(GridPoint(1_000_000, 1_000_000), GridPoint(2_000_000, 1_000_000))
        val document = CircuitDocument(wires = listOf(selected, other))
        val size = IntSize(360, 500)
        val state = CanvasViewportState(size = size).fit(
            document, 24f, 64f, selectedWires = setOf(selected), selectionOnly = true,
        )
        assertPhonePointsVisible(document.copy(wires = listOf(selected)), state.view, size)
        val otherScreen = Offset(other.from.x.toFloat(), other.from.y.toFloat()) * state.view.scale + state.view.pan
        assertTrue(otherScreen.x > size.width && otherScreen.y > size.height)
        assertTrue(!state.fitted)
    }

    @Test
    fun shortLandscapeUsesTheGutterBesideTheCircuitForControls() {
        val document = CircuitDocument(wires = listOf(
            WireSegment(GridPoint(0, 0), GridPoint(900, 0)),
            WireSegment(GridPoint(900, 0), GridPoint(900, 900)),
        ))
        val view = fittedViewport(document, IntSize(1600, 340), 63f, 189f)
        assertTrue(view.scale > 0.2f, "Landscape should use the available height: ${view.scale}")
        val drawingRight = 900 * view.scale + view.pan.x
        assertTrue(drawingRight < 1600 - 189 * (204f / 72f))
    }

    @Test
    fun wideCircuitFitsBesideControlsWhenCanvasHasNoRoomAboveThem() {
        val document = CircuitDocument(components = listOf(
            CircuitComponent("input", ComponentKind.INPUT, GridPoint(0, 80)),
        ), wires = listOf(
            WireSegment(GridPoint(0, 0), GridPoint(900, 0)),
            WireSegment(GridPoint(900, 0), GridPoint(900, 100)),
        ))
        val previous = CircuitViewport(4f, Offset(-2000f, -2000f))
        val view = fittedViewport(document, IntSize(600, 100), 24f, 72f, previous)
        assertTrue(view != previous)
        assertTrue(900 * view.scale + view.pan.x <= 396f)
        assertTrue(view.pan.y >= 23.9f)
        assertTrue(100 * view.scale + view.pan.y <= 76.1f)
    }

    @Test
    fun transientSmallSizePreservesTheLastUsableViewport() {
        val previous = CircuitViewport(0.7f, Offset(120f, 30f))
        val view = fittedViewport(CircuitDocument(), IntSize(1600, 20), 63f, 189f, previous)
        assertTrue(view == previous)
    }

    @Test
    fun zoomKeepsWorldPointUnderTheGestureAnchor() {
        val view = CircuitViewport(0.6f, Offset(35f, -24f))
        val anchor = Offset(160f, 240f)
        val before = view.world(anchor)
        val after = view.zoomed(2f, anchor).world(anchor)
        assertTrue(abs(before.x - after.x) < 0.001f)
        assertTrue(abs(before.y - after.y) < 0.001f)
    }

    @Test
    fun zoomAfterFittingExtremeCoordinatesDoesNotJumpToTheOldMinimum() {
        val document = CircuitDocument(wires = listOf(
            WireSegment(GridPoint(Int.MIN_VALUE, 0), GridPoint(Int.MAX_VALUE, 0)),
        ))
        val view = fittedViewport(document, IntSize(360, 500), 24f, 64f)
        val anchor = Offset(120f, 200f)
        val before = view.world(anchor)
        val zoomed = view.zoomed(1.1f, anchor)
        assertEquals(view.scale * 1.1f, zoomed.scale)
        val after = before * zoomed.scale + zoomed.pan
        assertTrue(abs(anchor.x - after.x) < 0.001f)
        assertTrue(abs(anchor.y - after.y) < 0.001f)
        assertTrue(zoomed.scale < 0.04f)
    }

    @Test
    fun extremeZoomRequestsStayFinite() {
        val zoomed = CircuitViewport().zoomed(100000f, Offset(150f, 200f))
        assertTrue(zoomed.scale == 12f)
        assertTrue(zoomed.pan.x.isFinite() && zoomed.pan.y.isFinite())
        val smaller = zoomed.zoomed(0.00000001f, Offset(150f, 200f))
        assertEquals(12f * 0.00000001f, smaller.scale)
        assertTrue(smaller.pan.x.isFinite() && smaller.pan.y.isFinite())
        val smallest = smaller.zoomed(0.00000001f, Offset(150f, 200f))
        assertEquals(1e-9f, smallest.scale)
        assertTrue(smallest.pan.x.isFinite() && smallest.pan.y.isFinite())
    }

    private fun assertPhonePointsVisible(document: CircuitDocument, view: CircuitViewport, size: IntSize) {
        val points = document.wires.flatMap { listOf(it.from, it.to) }
        for (point in points) {
            val screen = Offset(point.x.toFloat(), point.y.toFloat()) * view.scale + view.pan
            assertTrue(screen.x in 23.9f..(size.width - 23.9f), "x=${screen.x} for $point")
            assertTrue(screen.y in 23.9f..(size.height - 64f - 23.9f), "y=${screen.y} for $point")
        }
    }
}
