package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.VisualBounds
import dev.logisim.mobile.core.WireSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CircuitLabelViewportTest {
    private val input = CircuitComponent(
        "input", ComponentKind.INPUT, GridPoint(0, 0),
        label = "E2E — ligação longa → entrada / \u202bمرحبا\u202c",
        attributes = mapOf("labelloc" to "west", "labelfont" to "SansSerif bold 18"),
    )
    private val labelEnvelope = VisualBounds(-620.0, -44.0, 30.0, 38.0)
    private val phone = IntSize(360, 500)

    @Test
    fun suppliedLongLabelEnvelopeFitsEvenWhenElectricalGeometryWouldClipIt() {
        val document = CircuitDocument(components = listOf(input))
        val label = requireNotNull(classicComponentLabel(input, classicComponentBounds(input)))
        assertEquals(input.label, label.text)
        val electricalOnly = fittedViewport(document, phone, 24f, 64f)
        val clippedLeft = labelEnvelope.left.toFloat() * electricalOnly.scale + electricalOnly.pan.x
        assertTrue(clippedLeft < 0f)
        val complete = fittedViewport(document, phone, 24f, 64f, contentBounds = labelEnvelope)
        assertVisible(labelEnvelope, complete, phone)
        assertTrue(complete.scale < electricalOnly.scale)
    }

    @Test
    fun firstCanvasMeasurementAndRotationUseTheSuppliedLabelEnvelope() {
        val document = CircuitDocument(components = listOf(input))
        val initial = CanvasViewportState().resized(document, phone, 24f, 64f, contentBounds = labelEnvelope)
        assertTrue(initial.initialized && initial.fitted)
        assertVisible(labelEnvelope, initial.view, phone)
        val landscape = IntSize(900, 300)
        val rotated = initial.resized(document, landscape, 24f, 64f, contentBounds = labelEnvelope)
        assertVisible(labelEnvelope, rotated.view, landscape)
    }

    @Test
    fun restoredManualViewDoesNotRefitWhenMeasuredLabelBoundsArrive() {
        val document = CircuitDocument(components = listOf(input))
        val restored = CanvasViewportState(CircuitViewport(0.7f, Offset(35f, -24f)), initialized = true, fitted = false)
        val measured = restored.resized(document, phone, 24f, 64f, contentBounds = labelEnvelope)
        assertEquals(restored.view, measured.view)
    }

    @Test
    fun selectionEnvelopeExcludesOtherLabelsAndIncludesTheSelectedWire() {
        val other = input.copy(id = "other", position = GridPoint(50_000, 50_000), label = "Unselected label ".repeat(50))
        val selectedWire = WireSegment(GridPoint(0, 0), GridPoint(240, 0))
        val distantWire = WireSegment(GridPoint(50_000, 50_000), GridPoint(60_000, 50_000))
        val document = CircuitDocument(components = listOf(input, other), wires = listOf(selectedWire, distantWire))
        val target = viewportFitDocument(document, setOf(input.id), setOf(selectedWire), selectionOnly = true)
        assertEquals(listOf(input), target.components)
        assertEquals(listOf(selectedWire), target.wires)
        val envelope = labelEnvelope.union(VisualBounds(-5.0, -5.0, 245.0, 5.0))
        val fitted = CanvasViewportState(size = phone).fit(document, 24f, 64f, setOf(input.id), setOf(selectedWire),
            selectionOnly = true, contentBounds = envelope)
        assertVisible(envelope, fitted.view, phone)
        assertTrue(!fitted.fitted)
        val unselectedScreen = Offset(other.position.x.toFloat(), other.position.y.toFloat()) * fitted.view.scale + fitted.view.pan
        assertTrue(unselectedScreen.x > phone.width && unselectedScreen.y > phone.height)
    }

    @Test
    fun wireOnlySelectionDoesNotIncludeAnyComponentLabel() {
        val selectedWire = WireSegment(GridPoint(10_000, 200), GridPoint(12_000, 200))
        val document = CircuitDocument(components = listOf(input), wires = listOf(selectedWire))
        val target = viewportFitDocument(document, selectedWires = setOf(selectedWire), selectionOnly = true)
        assertTrue(target.components.isEmpty())
        assertEquals(listOf(selectedWire), target.wires)
        val envelope = VisualBounds(9_995.0, 195.0, 12_005.0, 205.0)
        val fitted = CanvasViewportState(size = phone).fit(document, 24f, 64f, selectedWires = setOf(selectedWire),
            selectionOnly = true, contentBounds = envelope)
        assertVisible(envelope, fitted.view, phone)
        assertTrue(fitted.view.pan.x < 0f)
    }

    @Test
    fun emptySelectionFallsBackToTheWholeMeasuredDrawing() {
        val document = CircuitDocument(components = listOf(input))
        assertSame(document, viewportFitDocument(document, selectionOnly = true))
        val state = CanvasViewportState(size = phone)
        val all = state.fit(document, 24f, 64f, contentBounds = labelEnvelope)
        val empty = state.fit(document, 24f, 64f, selectionOnly = true, contentBounds = labelEnvelope)
        assertEquals(all.view, empty.view)
        assertVisible(labelEnvelope, empty.view, phone)
    }

    private fun assertVisible(bounds: VisualBounds, view: CircuitViewport, size: IntSize) {
        val corners = listOf(
            Offset(bounds.left.toFloat(), bounds.top.toFloat()), Offset(bounds.right.toFloat(), bounds.top.toFloat()),
            Offset(bounds.left.toFloat(), bounds.bottom.toFloat()), Offset(bounds.right.toFloat(), bounds.bottom.toFloat()),
        ).map { it * view.scale + view.pan }
        val controlsLeft = size.width - 64f * (204f / 72f)
        val controlsTop = size.height - 64f
        for (point in corners) {
            assertTrue(point.x in 23.9f..(size.width - 23.9f), "Clipped horizontal label extent: $point")
            assertTrue(point.y in 23.9f..(size.height - 23.9f), "Clipped vertical label extent: $point")
            assertTrue(point.x <= controlsLeft + 0.1f || point.y <= controlsTop + 0.1f, "Label overlaps zoom controls: $point")
        }
    }
}
