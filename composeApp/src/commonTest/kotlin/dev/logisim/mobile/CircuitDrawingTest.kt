package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CircuitDrawingTest {
    @Test fun endpointJunctionsIncludeTJunctionsAndIgnoreUnconnectedCrossingsAndElbows() {
        val wires = listOf(
            WireSegment(GridPoint(0, 0), GridPoint(100, 0)), WireSegment(GridPoint(50, 0), GridPoint(50, 40)),
            WireSegment(GridPoint(100, 0), GridPoint(100, 40)),
            WireSegment(GridPoint(25, -20), GridPoint(25, 20)),
        )
        assertEquals(listOf(GridPoint(50, 0)), circuitDrawingJunctions(wires))
        val junction = circuitWireScene(CircuitDocument("main", wires = wires), SimulationState(), CircuitDrawingStyle()).single { it.id.startsWith("junction:") }
        assertNull((junction.primitives.single() as VisualPrimitive.Ellipse).paint.stroke)
    }

    @Test fun junctionsAtNegativeCoordinatesAndManySeparateBranchesAreStable() {
        val wires = (0 until 1000).flatMap { index -> val y = index * 10 - 5000; listOf(
            WireSegment(GridPoint(-10, y), GridPoint(10, y)), WireSegment(GridPoint(0, y), GridPoint(0, y + 5)),
        ) }
        assertEquals((0 until 1000).map { GridPoint(0, it * 10 - 5000) }, circuitDrawingJunctions(wires))
    }

    @Test fun selectionExportsOnlyExplicitlySelectedComponentsAndWires() {
        val a = CircuitComponent("a", AnnotationLibrary.Text, GridPoint(0, 0))
        val b = CircuitComponent("b", AnnotationLibrary.Text, GridPoint(100, 0))
        val wire = WireSegment(GridPoint(0, 20), GridPoint(100, 20))
        val document = CircuitDocument("main", listOf(a, b), listOf(wire))
        val selected = circuitDrawingSelection(document, setOf("b", circuitDrawingWireId(wire)))
        assertEquals(listOf(b), selected.components)
        assertEquals(listOf(wire), selected.wires)
        assertEquals(document, circuitDrawingSelection(document, null))
        assertTrue(circuitDrawingSelection(document, emptySet()).components.isEmpty())
    }

    @Test fun theSameLabelPrimitiveControlsDrawingAndMeasuredExportBounds() {
        val component = CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0), label = "A long label",
            attributes = mapOf("labelloc" to "south", "labelfont" to "SansSerif bolditalic 18"))
        val label = classicComponentLabel(component, Rect(0f, 0f, 30f, 40f))!!
        // TextField V_TOP 2 units below the body: the baseline sits one ascent (18) lower.
        assertEquals(VisualPoint(15.0, 60.0), label.position)
        assertEquals(VisualTextAlign.CENTER, label.align)
        assertEquals(VisualTextVerticalAlign.BASELINE, label.verticalAlign)
        assertEquals(VisualFont("SansSerif", 18.0, bold = true, italic = true), label.font)
        assertEquals(VisualBounds(-45.0, 48.0, 75.0, 66.0), label.layoutBounds(120.0, 18.0, 12.0))
        assertNull(classicComponentLabel(component.copy(attributes = component.attributes + ("labelvisible" to "false")), Rect(0f, 0f, 30f, 40f)))
        assertEquals(VisualTextVerticalAlign.TOP, classicLabel("side", Offset.Zero).verticalAlign)
    }

    @Test fun wireSceneUsesTheSamePaletteAndWorldStrokeAsTheCanvas() {
        val wire = WireSegment(GridPoint(0, 0), GridPoint(50, 0))
        val scene = circuitWireScene(CircuitDocument("main", wires = listOf(wire)), SimulationState(portValues = mapOf(wire.from to LogicValue.HIGH)), CircuitDrawingStyle(highContrastSignals = true))
        val path = scene.single().primitives.single() as VisualPrimitive.Path
        assertEquals(CircuitSignalPalette.HighContrast.high.toArgb().toUInt(), path.paint.stroke)
        assertEquals(2.0, path.paint.strokeWidth)
        assertEquals(VisualBounds(-1.0, -1.0, 51.0, 1.0), scene.single().bounds)
    }

    @Test fun labelsWithoutFontOrColorUseEvolutionsDefaultsAndTheEditionKeepsItsOwn() {
        fun project(source: String) = assertIs<CircProjectImportResult.Success>(CircCodec.readProject("""
            <project source="$source" version="1.0">
              <lib desc="#Gates" name="1"/>
              <main name="main"/>
              <circuit name="main">
                <comp loc="(300,100)" name="child"><a name="label" val="CELL"/></comp>
                <comp lib="1" loc="(500,100)" name="AND Gate"><a name="label" val="G"/></comp>
              </circuit>
              <circuit name="child"/>
            </project>
        """.trimIndent())).project
        val evolution = project("5.1.0dev")
        val child = evolution.mainCircuit.components.first()
        val label = assertNotNull(classicComponentLabel(child, Rect(240f, 70f, 300f, 130f)))
        // SubcircuitFactory.configureLabel: V_BASELINE 2 units above the body, in StdAttr.DEFAULT_LABEL_FONT and Color.BLUE.
        assertEquals(VisualPoint(270.0, 68.0), label.position)
        assertEquals(VisualTextVerticalAlign.BASELINE, label.verticalAlign)
        assertEquals(VisualFont("SansSerif", 16.0, bold = true), label.font)
        assertEquals(0xFF0000FFu, label.color)
        val gate = evolution.mainCircuit.components.last()
        assertEquals(VisualFont("SansSerif", 16.0, bold = true), assertNotNull(classicComponentLabel(gate, Rect(240f, 70f, 300f, 130f))).font)
        assertTrue("labelfont" !in CircCodec.writeProject(evolution))

        val original = project("2.7.1")
        for (component in original.mainCircuit.components) {
            assertEquals(VisualFont("SansSerif", 12.0), assertNotNull(classicComponentLabel(component, Rect(240f, 70f, 300f, 130f))).font)
        }
        assertTrue("labelfont" !in CircCodec.writeProject(original))
    }

    @Test fun importedButtonAndLedLabelsUseTheFileVersionDefaultAndExplicitPosition() {
        fun labels(source: String, location: String? = null): List<VisualPoint> {
            val labelLocation = location?.let { """<a name="labelloc" val="$it"/>""" }.orEmpty()
            val xml = """
                <project source="$source" version="1.0">
                  <lib desc="#I/O" name="0"/>
                  <main name="main"/>
                  <circuit name="main">
                    <comp lib="0" loc="(100,100)" name="Button"><a name="label" val="Go"/>$labelLocation</comp>
                    <comp lib="0" loc="(200,100)" name="LED"><a name="label" val="On"/>$labelLocation</comp>
                  </circuit>
                </project>
            """.trimIndent()
            val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
            val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(project))).project
            assertEquals(project.mainCircuit.components.map { it.attributes["labelloc"] },
                reopened.mainCircuit.components.map { it.attributes["labelloc"] })
            val bounds = Rect(0f, 0f, 40f, 30f)
            return reopened.mainCircuit.components.zip(listOf("Go", "On")).map { (component, name) ->
                val label = assertNotNull(classicComponentLabel(component, bounds))
                assertEquals(name, label.text)
                if (location == "center" || source == "2.7.1" && location == null) {
                    assertEquals(VisualTextAlign.CENTER, label.align)
                }
                assertEquals(VisualTextVerticalAlign.BASELINE, label.verticalAlign)
                label.position
            }
        }

        // V_CENTER puts the baseline half an ascent below the field: 2.7.1 plain 12, Evolution bold 16.
        val centers = listOf(VisualPoint(20.0, 21.0), VisualPoint(20.0, 21.0))
        assertEquals(centers, labels("2.7.1"))
        assertEquals(centers, labels("2.7.1", "center"))
        assertEquals(listOf(VisualPoint(20.0, 23.0), VisualPoint(20.0, 23.0)), labels("5.1.0-dev", "center"))
        assertEquals(listOf(VisualPoint(-2.0, 23.0), VisualPoint(42.0, 23.0)), labels("5.1.0-dev"))
        assertEquals(listOf(VisualPoint(20.0, 44.0), VisualPoint(20.0, 44.0)), labels("2.7.1", "south"))
    }

    @Test fun customSymbolTerminalMarkersKeepSimulationColorsInTheCompleteScene() {
        val component = CircuitComponent("module", ComponentKind.SUBCIRCUIT, GridPoint(100, 100), circuitName = "child", explicitPorts = listOf(
            ComponentPort("a", GridPoint(-10, 0), PortDirection.INPUT), ComponentPort("y", GridPoint(0, 0), PortDirection.OUTPUT),
        ))
        val state = SimulationState(portValues = mapOf(GridPoint(90, 100) to LogicValue.HIGH, GridPoint(100, 100) to LogicValue.LOW))
        val markers = circuitDrawingTerminalMarkers(component, state, ComponentCatalog.registry, false)
        assertEquals(2, markers.size)
        // Value.getColor(): DEFAULT_TRUE_COLOR and DEFAULT_FALSE_COLOR; drawPinMarker's 4-pixel dot at offset 2.
        assertEquals(0xff00d200u, markers[0].paint.fill)
        assertEquals(0xff006400u, markers[1].paint.fill)
        assertNull(markers[0].paint.stroke)
        assertEquals(VisualBounds(88.0, 98.0, 92.0, 102.0), markers[0].bounds)
    }

    @Test fun imageDrawingPayloadIsReusedUntilTheComponentChanges() {
        val cache = AnnotationImageCache()
        val component = CircuitComponent("image", AnnotationLibrary.Image, GridPoint(10, 20))
        val original = VisualItem("image", VisualBounds(10.0, 20.0, 74.0, 84.0), emptyList())
        assertEquals(original, cache.drawingItem(component) { original })
        assertEquals(original, cache.drawingItem(component) { error("Unchanged image payload was parsed again") })
        assertEquals(original, cache.drawingItem(component.copy()) { error("Equivalent image payload was parsed again") })
        val moved = original.copy(bounds = VisualBounds(20.0, 20.0, 84.0, 84.0))
        assertEquals(moved, cache.drawingItem(component.copy(position = GridPoint(20, 20))) { moved })
        assertEquals(moved, cache.drawingItem(component.copy(position = GridPoint(20, 20))) { error("Updated image payload was parsed again") })
    }
}
