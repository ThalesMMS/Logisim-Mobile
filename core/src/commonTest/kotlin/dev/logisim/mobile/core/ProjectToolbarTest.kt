package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectToolbarTest {
    @Test
    fun editingKeepsUnsupportedEntriesAndToolAttributesWhenReopened() {
        val project = read("""
            <project source="2.7.1">
              <lib name="0" desc="#Base"/>
              <lib name="1" desc="#Wiring"/>
              <toolbar extension="preserve">
                <tool lib="0" name="Poke Tool"/>
                <!-- preserved note -->
                <sep/>
                <tool lib="1" name="Pin"><a name="output" val="true"/><a name="width" val="4"/></tool>
                <tool lib="0" name="Menu Tool"><future-tool/></tool>
              </toolbar>
              <mappings/>
              <circuit name="main"/>
            </project>
        """.trimIndent())
        val entries = ProjectToolbar.read(project)
        assertEquals(listOf("Poke Tool", "sep", "Pin", "Menu Tool"), entries.map { it.name })
        assertTrue(entries[1].isSeparator)
        assertEquals(ProjectToolbarBinding.Mode.INTERACT, ProjectToolbar.binding(entries[0], project, ComponentCatalog.registry))
        assertNull(ProjectToolbar.binding(entries[3], project, ComponentCatalog.registry))

        val edited = ProjectToolbar.apply(project, listOf(entries[2], entries[1], entries[3],
            ProjectToolbarItem.Tool("#Gates", "AND Gate"), entries[0]))
        val xml = CircCodec.writeProject(edited)
        assertContains(xml, "<!-- preserved note -->")
        assertContains(xml, """<toolbar extension="preserve">""")
        assertContains(xml, """<tool lib="0" name="Menu Tool"><future-tool/></tool>""")
        assertContains(xml, "<mappings/>")

        val reopened = read(xml)
        val after = ProjectToolbar.read(reopened)
        assertEquals(listOf("Pin", "sep", "Menu Tool", "AND Gate", "Poke Tool"), after.map { it.name })
        assertTrue(after[1].isSeparator)
        val pin = assertIs<ProjectToolbarBinding.Place>(ProjectToolbar.binding(after[0], reopened, ComponentCatalog.registry))
        assertEquals(ComponentKind.OUTPUT, pin.component.kind)
        assertEquals("4", pin.component.attributes["width"])
        val gate = assertIs<ProjectToolbarBinding.Place>(ProjectToolbar.binding(after[3], reopened, ComponentCatalog.registry))
        assertEquals(ComponentKind.AND, gate.component.kind)
        assertContains(reopened.libraries.values, "#Gates")
    }

    @Test
    fun emptyDesktopToolbarCanBeEditedAndEmptiedAgain() {
        val project = read("""
            <project source="2.7.1"><toolbar/><circuit name="main"/></project>
        """.trimIndent())
        val inserted = ProjectToolbar.apply(project, listOf(
            ProjectToolbarItem.Tool("#Base", "Wiring Tool"), ProjectToolbarItem.Separator,
        ))
        val reopened = read(CircCodec.writeProject(inserted))
        val entries = ProjectToolbar.read(reopened)
        assertEquals(ProjectToolbarBinding.Mode.WIRE, ProjectToolbar.binding(entries.first(), reopened, ComponentCatalog.registry))
        assertTrue(entries.last().isSeparator)
        val cleared = ProjectToolbar.apply(reopened, emptyList())
        assertTrue(ProjectToolbar.read(read(CircCodec.writeProject(cleared))).isEmpty())
    }

    @Test
    fun configuredLocalAndExternalPrototypesSurviveBundleAndPlacement() {
        val root = """
            <project source="5.1.0"><lib name="w" desc="#Wiring"/>
            <lib name="ext" desc="file#child.circ"/>
            <toolbar><tool lib="w" name="Pin"><future-tool/></tool></toolbar>
            <main name="main"/><circuit name="main"/><circuit name="Local"/></project>
        """.trimIndent()
        val external = """
            <project source="5.1.0"><lib name="w" desc="#Wiring"/>
            <circuit name="External"><comp lib="w" name="Pin" loc="(20,20)">
            <a name="width" val="8"/></comp></circuit></project>
        """.trimIndent()
        val resolver = CircuitLibraryResolver { _, _ -> CircuitLibrarySource("external", external) }
        val loaded = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(root, "root", resolver)).resolved
        val project = loaded.project
        val externalName = project.circuits.single { it.name !in setOf("main", "Local") }.name
        val pin = ProjectToolbar.withToolAttributes(ProjectToolbar.read(project).single(), mapOf("width" to "8"))
        val toolbar = ProjectToolbar.apply(project, listOf(
            pin, ProjectToolbarItem.Tool(null, "Local", mapOf("label" to "child")),
            ProjectToolbarItem.Tool(null, externalName, mapOf("facing" to "west")),
        ))
        val gesture = ProjectMouseGesture(ProjectMouseButton.SECONDARY)
        val mapped = ProjectMouseMappings.set(toolbar, gesture, ProjectMouseTool(null, externalName))
        val configured = ProjectMouseMappings.withToolAttributes(mapped, gesture, mapOf("label" to "mapped"))
        val bundle = CircLibraryCodec.bundle(loaded, configured)
        val rootXml = bundle.text(bundle.mainFile)
        assertContains(rootXml, "<future-tool/>")
        val reopened = assertIs<CircLibraryImportResult.Success>(CircLibraryCodec.read(bundle)).resolved.project
        val tools = ProjectToolbar.read(reopened).map {
            assertIs<ProjectToolbarBinding.Place>(ProjectToolbar.binding(it, reopened, ComponentCatalog.registry)).component
        }
        val mouse = assertIs<ProjectMouseAction.Place>(ProjectMouseMappings.read(reopened)[gesture]?.action).prototype
        val editor = CircuitEditor(reopened.mainCircuit)
        (tools + mouse).forEachIndexed { index, template -> editor.add(template, GridPoint(index * 100, 100)) }
        val (placedPin, placedLocal, placedExternal, placedMapped) = editor.document.components
        assertEquals(8, placedPin.width)
        assertEquals("Local", placedLocal.circuitName)
        assertEquals("child", placedLocal.label)
        assertEquals(placedExternal.circuitName, placedMapped.circuitName)
        assertEquals("west", placedExternal.attributes["facing"])
        assertEquals(8, placedExternal.explicitPorts.single().width)
        assertEquals("mapped", placedMapped.label)
    }

    private fun read(xml: String): CircuitProject = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
}
