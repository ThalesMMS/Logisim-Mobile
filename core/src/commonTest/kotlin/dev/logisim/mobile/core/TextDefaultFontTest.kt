package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** A Text without a font: Evolution's SansSerif bold 16, 2.7.1's plain 12, never written back (#346). */
class TextDefaultFontTest {
    private val registry = ComponentCatalog.registry.withDefinitions(AnnotationLibrary.definitions())

    private fun text(source: String): Pair<CircuitProject, CircuitComponent> {
        val xml = """<project source="$source" version="1.0"><lib desc="#Base" name="1"/><main name="main"/>
            <circuit name="main"><comp lib="1" loc="(300,100)" name="Text"><a name="text" val="Old note"/></comp></circuit></project>"""
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project
        return project to project.mainCircuit.components.single()
    }

    @Test
    fun evolutionDrawsBold16AndSavesNoFont() {
        val (project, note) = text("5.1.0dev")
        val font = assertIs<VisualPrimitive.Text>(AnnotationVisuals.item(note).primitives.single()).font
        assertEquals(VisualFont("SansSerif", 16.0, true, false), font)
        assertEquals(ComponentBounds(258, 84, 343, 100), AnnotationLibrary.bounds(note))
        assertFalse("\"font\"" in CircCodec.writeProject(project, registry))
    }

    @Test
    fun original271DrawsPlain12AndSavesNoFont() {
        val (project, note) = text("2.7.1")
        val font = assertIs<VisualPrimitive.Text>(AnnotationVisuals.item(note).primitives.single()).font
        assertEquals(VisualFont("SansSerif", 12.0, false, false), font)
        assertFalse("\"font\"" in CircCodec.writeProject(project, registry))
    }
}
