package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AnnotationCircRoundTripTest {
    private val registry get() = ComponentCatalog.registry.withDefinitions(AnnotationLibrary.definitions())

    @Test fun textAndImageKeepAllDesktopAndUnknownAttributesThroughTheCodec() {
        val pixel = VisualPng.encode(2, 1, intArrayOf(0xffff0000.toInt(), 0x8000ff00.toInt()))
        var document = AnnotationEdits.insertText(CircuitDocument("main"), "text", GridPoint(100, 100), "  A & B\nlinha  ", AnnotationFont("SansSerif", 18, bold = true), "#12345678")
        document = AnnotationEdits.insertImage(document, "image", GridPoint(100, 200), EmbeddedImage.fromBytes(pixel, "image/png"), 120, 80, "cover", "Author & source")
        document = AnnotationEdits.group(document, setOf("text", "image"), "group-1")
        val written = CircCodec.write(document, registry)
        val reopened = assertIs<CircImportResult.Success>(CircCodec.read(written, registry)).document
        assertEquals(document.components.map { it.kind }, reopened.components.map { it.kind })
        assertEquals(document.components.map { it.attributes }, reopened.components.map { it.attributes })
        assertEquals(document.components.map { AnnotationLibrary.bounds(it) }, reopened.components.map { AnnotationLibrary.bounds(it) })
        assertTrue(EmbeddedImage.fromDataUri(reopened.components.last().attributes.getValue("image")).bytes.contentEquals(pixel))
        val settled = CircuitSimulator(registry = registry).settle(reopened)
        assertTrue(settled.notices.isEmpty(), settled.notices.toString())
        assertTrue(settled.portVectors.isEmpty())
        assertTrue(!settled.oscillating)
    }

    @Test fun customAppearanceReloadKeepsNorthAnchorGeometryForEachInstanceFacing() {
        val child = CircuitDocument("child", components = listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(20, 30), attributes = mapOf("width" to "8", "facing" to "east")),
            CircuitComponent("y", ComponentKind.OUTPUT, GridPoint(90, 30), attributes = mapOf("width" to "8", "facing" to "west")),
        ))
        val appearance = SymbolAppearance(SymbolAnchor(GridPoint(50, 50), "north"), listOf(SymbolPort("a", GridPoint(50, 80)), SymbolPort("y", GridPoint(50, 50))), listOf(
            SymbolObject.Shape("body", "rect", mapOf("x" to "40", "y" to "50", "width" to "30", "height" to "30", "fill" to "none", "stroke" to "#000000")),
            SymbolObject.Shape("label", "text", mapOf("x" to "80", "y" to "90", "font-size" to "12"), "A & B"),
        ))
        val main = CircuitDocument("main", components = listOf("east", "north", "west", "south").mapIndexed { index, facing ->
            CircuitComponent("instance-$index", ComponentKind.SUBCIRCUIT, GridPoint(200 + index * 100, 100), attributes = mapOf("facing" to facing), circuitName = "child")
        })
        val project = AppearanceEdits.apply(CircuitProject(listOf(main, child), "main", sourceVersion = "5.1.0-dev"), "child", appearance)
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(project, registry), registry)).project
        assertEquals(project.mainCircuit.components.map { it.inputPorts(registry) }, reopened.mainCircuit.components.map { it.inputPorts(registry) })
        assertEquals(project.mainCircuit.components.map { it.outputPorts(registry) }, reopened.mainCircuit.components.map { it.outputPorts(registry) })
        assertEquals(project.mainCircuit.components.map { it.bounds(registry) }, reopened.mainCircuit.components.map { it.bounds(registry) })
        assertEquals(project.circuit("child")!!.extraXml, reopened.circuit("child")!!.extraXml)
    }

    @Test fun inactiveCustomGeometrySurvivesAClassicModeRoundTrip() {
        val child = CircuitDocument("child", listOf(CircuitComponent("a", ComponentKind.INPUT, GridPoint(20, 30))))
        val main = CircuitDocument("main", listOf(CircuitComponent("instance", ComponentKind.SUBCIRCUIT, GridPoint(200, 100), circuitName = "child")))
        val custom = AppearanceEdits.apply(CircuitProject(listOf(main, child), "main"), "child", SymbolAppearance.classic(child).movePort("a", GridPoint(-20, 15)))
        val classic = AppearanceEdits.useClassic(custom, "child")
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(classic, registry), registry)).project
        assertEquals(classic.mainCircuit.components.single().inputPorts(registry), reopened.mainCircuit.components.single().inputPorts(registry))
        assertEquals(classic.circuit("child")!!.extraXml, reopened.circuit("child")!!.extraXml)
        assertEquals("classic", reopened.circuit("child")!!.attributes["appearance"])
    }

    @Test fun registeredDefinitionsOwnAttributeSemanticsAndKeepUnknownAttributes() {
        val kind = ComponentKind("DIMENSIONED_TEST")
        val customRegistry = registry.withAdditional(listOf(ComponentDefinition(kind, "Dimensioned", "#Base", schema = AttributeSchema(listOf(
            AttributeKey.int("width", 100, 1..10_000), AttributeKey.int("inputs", 100, 1..128), AttributeKey.int("addrWidth", 64, 1..128),
        )), layout = { component, _ -> ComponentLayout(emptyList(), ComponentBounds(component.position.x, component.position.y, component.position.x + component.attributeInt("width", 100), component.position.y + 10)) })))
        fun xml(width: Int = 512, facing: String = "east") = """<project source="5.1.0-dev" version="1.0"><lib desc="#Base" name="0"/><main name="main"/><circuit name="main"><comp lib="0" name="Dimensioned" loc="(100,100)"><a name="width" val="$width"/><a name="inputs" val="96"/><a name="addrWidth" val="64"/><a name="dataWidth" val="future-owned-string"/><a name="facing" val="$facing"/></comp></circuit></project>"""
        val document = assertIs<CircImportResult.Success>(CircCodec.read(xml(), customRegistry)).document
        assertEquals("512", document.components.single().attributes["width"])
        assertEquals("96", document.components.single().attributes["inputs"])
        assertEquals("64", document.components.single().attributes["addrWidth"])
        assertEquals("future-owned-string", document.components.single().attributes["dataWidth"])
        val reloaded = assertIs<CircImportResult.Success>(CircCodec.read(CircCodec.write(document, customRegistry), customRegistry)).document
        assertEquals(document.components.single().attributes, reloaded.components.single().attributes)
        assertIs<CircImportResult.Failure>(CircCodec.read(xml(width = 10_001), customRegistry))
        assertIs<CircImportResult.Failure>(CircCodec.read(xml(facing = "diagonal"), customRegistry))
    }
}
