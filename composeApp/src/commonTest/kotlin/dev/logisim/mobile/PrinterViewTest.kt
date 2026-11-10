package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PrinterViewTest {
    @Test fun togglingPrinterViewPreservesSimulationSelectionAndViewportWhileInputsKeepUpdating() {
        val document = printerFixture()
        val state = EditorState(CircCodec.write(document))
        val input = state.document.components.single { it.label == "Input" }
        val bus = state.document.components.single { it.label == "Bus_input" }
        val output = state.document.components.single { it.label == "Bus_output" }
        val viewport = CanvasViewportState(CircuitViewport(2.5f, Offset(-240f, 75f)), IntSize(800, 600), initialized = true, fitted = false)
        state.updateViewport(viewport)
        state.select(input.id)
        state.toggle(input.id)
        state.setInputValue(bus.id, 0xa5uL)
        val simulation = state.simulation
        val xml = state.exportXml()
        val selection = state.selectedIds
        val reset = state.viewportReset
        assertFalse(state.printerView)
        assertEquals(0xa5uL, simulation.vectorValues[output.id]?.toULongOrNull())

        state.togglePrinterView()

        assertTrue(state.printerView)
        assertSame(simulation, state.simulation)
        assertEquals(viewport, state.viewport)
        assertEquals(selection, state.selectedIds)
        assertEquals(reset, state.viewportReset)
        assertEquals(xml, state.exportXml())
        assertEquals(0, state.revision)

        state.setInputValue(bus.id, 0x3cuL)
        state.toggle(input.id)
        val current = state.simulation
        state.togglePrinterView()

        assertFalse(state.printerView)
        assertSame(current, state.simulation)
        assertEquals(0x3cuL, state.simulation.vectorValues[output.id]?.toULongOrNull())
        assertEquals(LogicValue.LOW, state.simulation.componentValues[input.id])
        assertEquals(viewport, state.viewport)
        assertEquals(selection, state.selectedIds)
        assertEquals(reset, state.viewportReset)
        assertEquals(xml, state.exportXml())
        assertFalse(state.canUndo)
    }

    @Test fun printerWireSceneIsNeutralAndKeepsBusWidthsAndJunctions() {
        val document = printerFixture()
        val simulation = CircuitSimulator().settle(document, vectorInputs = mapOf("input" to LogicVector.high(1), "bus" to LogicVector.error(8)))
        val liveStyle = CircuitDrawingStyle(highContrastSignals = true)
        val live = circuitWireScene(document, simulation, liveStyle)
        val printed = circuitWireScene(document, simulation, liveStyle.copy(printerView = true))
        val restored = circuitWireScene(document, simulation, liveStyle)
        assertEquals(live, restored)
        assertEquals(live.map { it.id to it.bounds }, printed.map { it.id to it.bounds })
        assertNotEquals(live, printed)
        for (item in printed) for (primitive in item.primitives) when (primitive) {
            is VisualPrimitive.Path -> {
                assertEquals(0xff000000u, primitive.paint.stroke)
                assertTrue(primitive.paint.dash.isEmpty())
            }
            is VisualPrimitive.Ellipse -> assertEquals(0xff000000u, primitive.paint.fill)
            else -> error("Unexpected wire primitive")
        }
        val widths = printed.flatMap { it.primitives }.filterIsInstance<VisualPrimitive.Path>().map { it.paint.strokeWidth }
        assertTrue(2.0 in widths)
        assertTrue(4.0 in widths)
        assertTrue(printed.any { it.id.startsWith("junction:") })
        val markers = document.components.flatMap { circuitDrawingTerminalMarkers(it, simulation, ComponentCatalog.registry, true, printerView = true) }
        assertTrue(markers.isNotEmpty())
        assertTrue(markers.all { it.paint.fill == 0xff000000u })
        assertEquals(CircuitSignalPalette.HighContrast.high.toArgb().toUInt(),
            (live.first().primitives.single() as VisualPrimitive.Path).paint.stroke)
    }

    @Test fun printerViewOmitsTerminalMarkersWhereEvolutionDoes() {
        val registry = ComponentCatalog.registry
        val simulation = SimulationState()
        fun markers(kind: ComponentKind, shape: EditorGateShape, printerView: Boolean = true) =
            circuitDrawingTerminalMarkers(CircuitComponent(kind.name, kind, GridPoint(200, 100)), simulation, registry, false,
                printerView = printerView, gateShape = shape)
        // AbstractGate.paintInstance: drawPorts() only when !isPrintView() || shape == rectangular.
        for (kind in listOf(ComponentKind.AND, ComponentKind.OR, ComponentKind.NAND, ComponentKind.NOR, ComponentKind.XOR,
            ComponentKind.XNOR, GatesLibrary.OddParity, GatesLibrary.EvenParity)) {
            assertTrue(markers(kind, EditorGateShape.ANSI).isEmpty(), "$kind ANSI")
            assertTrue(markers(kind, EditorGateShape.IEC).isNotEmpty(), "$kind IEC")
            assertTrue(markers(kind, EditorGateShape.ANSI, printerView = false).isNotEmpty(), "$kind live")
        }
        // ControlledBuffer.paintInstance: ports 0 and 1 only outside the printer view, in every shape.
        for (kind in listOf(ComponentKind.CONTROLLED_BUFFER, ComponentKind.CONTROLLED_INVERTER)) {
            assertTrue(markers(kind, EditorGateShape.IEC).isEmpty(), "$kind printer")
            assertEquals(2, markers(kind, EditorGateShape.ANSI, printerView = false).size, "$kind live")
        }
        // NotGate and Buffer call drawPorts() unconditionally.
        for (kind in listOf(ComponentKind.NOT, ComponentKind.BUFFER)) assertEquals(2, markers(kind, EditorGateShape.ANSI).size, "$kind")
    }

    private fun printerFixture() = CircuitDocument("Printer_view", components = listOf(
        CircuitComponent("input", ComponentKind.INPUT, GridPoint(100, 100), label = "Input"),
        CircuitComponent("led", IoKinds.Led, GridPoint(200, 100), label = "LED"),
        CircuitComponent("output", ComponentKind.OUTPUT, GridPoint(200, 140), label = "Output"),
        CircuitComponent("bus", ComponentKind.INPUT, GridPoint(100, 220), label = "Bus_input", attributes = mapOf("width" to "8")),
        CircuitComponent("bus-output", ComponentKind.OUTPUT, GridPoint(240, 220), label = "Bus_output", attributes = mapOf("width" to "8")),
        CircuitComponent("note", AnnotationLibrary.Text, GridPoint(100, 40), attributes = mapOf("text" to "Printer view fixture")),
    ), wires = listOf(
        WireSegment(GridPoint(100, 100), GridPoint(200, 100)),
        WireSegment(GridPoint(150, 100), GridPoint(150, 140)),
        WireSegment(GridPoint(150, 140), GridPoint(200, 140)),
        WireSegment(GridPoint(100, 220), GridPoint(240, 220)),
    ))
}
