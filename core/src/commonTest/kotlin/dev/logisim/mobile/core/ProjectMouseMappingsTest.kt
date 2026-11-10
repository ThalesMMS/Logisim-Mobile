package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectMouseMappingsTest {
    @Test
    fun readsPhysicalGesturesAndUsesTheProjectToolAndItsAttributes() {
        val project = readProject(
            """
            <mappings>
              <tool lib="6" map="Button2" name="Menu Tool"/>
              <tool lib="6" map="Shift Ctrl Button3" name="Poke Tool"/>
              <tool lib="0" map="Alt Button1" name="Pin"><a name="width" val="8"/></tool>
              <tool lib="7" map="Shift Button1" name="Poke Tool"/>
              <tool lib="6" map="Meta Button3" name="Select Tool"/>
            </mappings>
            """.trimIndent(),
        )
        val bindings = ProjectMouseMappings.read(project)
        assertEquals(4, bindings.size)
        assertEquals(ProjectMouseAction.Edit(ProjectMouseEditTool.MENU),
            bindings[ProjectMouseGesture(ProjectMouseButton.MIDDLE)]?.action)
        assertEquals(ProjectMouseAction.Edit(ProjectMouseEditTool.POKE),
            bindings[ProjectMouseGesture(ProjectMouseButton.SECONDARY, ctrl = true, shift = true)]?.action)
        val placement = assertIs<ProjectMouseAction.Place>(bindings[ProjectMouseGesture(ProjectMouseButton.PRIMARY, alt = true)]?.action)
        assertEquals(ComponentKind.INPUT, placement.prototype.kind)
        assertEquals(8, placement.prototype.width)
        assertEquals(project, ProjectMouseMappings.set(project,
            ProjectMouseGesture(ProjectMouseButton.PRIMARY, alt = true), ProjectMouseTool("#Wiring", "Pin")))
        assertNull(bindings[ProjectMouseGesture(ProjectMouseButton.PRIMARY, shift = true)]?.action)
    }

    @Test
    fun rebindAndRemovalKeepUnrelatedXmlAndUseTheExistingLibraryId() {
        val project = readProject(
            """
            <mappings metadata="keep">
              <!--retain-->
              <tool lib="6" map="Button1 Shift" name="Select Tool"/>
              <tool lib="7" map="Button3" name="Unknown"><opaque /></tool>
              <future mode="keep"/>
            </mappings>
            """.trimIndent(),
        )
        val gesture = ProjectMouseGesture(ProjectMouseButton.PRIMARY, shift = true)
        val updated = ProjectMouseMappings.set(project, gesture, ProjectMouseEditTool.WIRING.reference)
        val xml = CircCodec.writeProject(updated)
        assertTrue(xml.contains("""<tool lib="6" name="Wiring Tool" map="Shift Button1">"""))
        for (unchanged in listOf("""metadata="keep"""", "<!--retain-->", "<opaque />", """<future mode="keep"/>""")) {
            assertTrue(xml.contains(unchanged), unchanged)
        }
        val reloaded = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
        assertEquals(ProjectMouseAction.Edit(ProjectMouseEditTool.WIRING), ProjectMouseMappings.read(reloaded)[gesture]?.action)
        val removed = ProjectMouseMappings.set(reloaded, gesture, null)
        assertNull(ProjectMouseMappings.read(removed)[gesture])
        assertTrue(CircCodec.writeProject(removed).contains("<opaque />"))
    }

    @Test
    fun aNewBindingCreatesItsLibraryAndFillsAnEmptyMappingsElement() {
        val project = CircuitProject(listOf(CircuitDocument()), extraXml = listOf("<mappings/>"))
        val gesture = ProjectMouseGesture(ProjectMouseButton.MIDDLE, ctrl = true)
        val updated = ProjectMouseMappings.set(project, gesture, ProjectMouseEditTool.SELECT.reference)
        val roundTrip = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(updated))).project
        assertEquals(ProjectMouseAction.Edit(ProjectMouseEditTool.SELECT), ProjectMouseMappings.read(roundTrip)[gesture]?.action)
        assertEquals(listOf("#Base"), roundTrip.libraries.values.toList())
    }

    @Test
    fun editingPrototypeKeepsOriginalMappingAndOpaqueChildren() {
        val project = readProject("""
            <mappings><tool lib="0" map="Shift Button2" name="Pin" extension="keep">
                <a name="width" val="4"/><!--keep--><future/>
            </tool></mappings>
        """.trimIndent())
        val gesture = ProjectMouseGesture(ProjectMouseButton.MIDDLE, shift = true)
        val edited = ProjectMouseMappings.withToolAttributes(project, gesture, mapOf("width" to "8", "label" to "bus"))
        val xml = CircCodec.writeProject(edited)
        assertTrue(xml.contains("""extension="keep""""))
        assertTrue(xml.contains("<!--keep--><future/>"))
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
        val prototype = assertIs<ProjectMouseAction.Place>(ProjectMouseMappings.read(reopened)[gesture]?.action).prototype
        assertEquals(8, prototype.width)
        assertEquals("bus", prototype.label)
    }

    private fun readProject(mappings: String): CircuitProject = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(
        """<project source="2.7.1" version="1.0">
          <lib name="6" desc="#Base"/><lib name="0" desc="#Wiring"/><lib name="7" desc="#Gates"/>
          <main name="main"/>$mappings<circuit name="main"/>
        </project>""",
    )).project
}
