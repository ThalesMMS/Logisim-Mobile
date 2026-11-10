package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CircFormatProfileTest {
    @Test
    fun appliesSourceSpecificMemoryAppearanceWithoutWritingImplicitDefaults() {
        for ((version, expected) in listOf("3.8.0" to "logisim_evolution", "3.8.0-HC" to "classic", "5.1.0-dev" to "logisim_evolution")) {
            val imported = read(version, "Register", "#Memory")
            val component = imported.project.mainCircuit.components.single()
            assertEquals(expected, component.attributes["appearance"])
            assertTrue(imported.migrations.any { it.rule == "version-defaults" })
            val editedAppearance = if (expected == "classic") "logisim_evolution" else "classic"
            val editedCircuit = imported.project.mainCircuit.copy(components = listOf(
                component.copy(attributes = component.attributes + ("appearance" to editedAppearance))))
            val edited = imported.project.withCircuit(editedCircuit)
            for ((project, writtenAppearance) in listOf(imported.project to null, edited to editedAppearance)) {
                val xml = CircXmlParser(CircCodec.writeProject(project)).parse()
                val register = xml.children.single { it.name == "circuit" }.children
                    .single { it.name == "comp" && it.attributes["name"] == "Register" }
                val appearances = register.children.filter { it.name == "a" && it.attributes["name"] == "appearance" }
                assertEquals(listOfNotNull(writtenAppearance), appearances.map { it.attributes.getValue("val") }, version)
            }
        }
    }

    @Test
    fun selectsHistoricalPlexerEnableDefaults() {
        assertEquals("true", read("3.6.1", "Multiplexer", "#Plexers").project.mainCircuit.components.single().attributes["enable"])
        assertEquals("false", read("3.6.2", "Multiplexer", "#Plexers").project.mainCircuit.components.single().attributes["enable"])
        assertEquals("false", read("2.6.3", "Decoder", "#Plexers").project.mainCircuit.components.single().attributes["enable"])
        assertEquals("true", read("2.6.4", "Decoder", "#Plexers").project.mainCircuit.components.single().attributes["enable"])
    }

    @Test
    fun derivesLegacyPinBehaviorWithoutDeletingOriginalFields() {
        val imported = read("3.8.0", "Pin", "#Wiring", "<a name=\"tristate\" val=\"true\"/><a name=\"pull\" val=\"down\"/><a name=\"output\" val=\"false\"/>")
        val component = imported.project.mainCircuit.components.single()
        assertEquals("pulldown", component.attributes["behavior"])
        assertEquals("input", component.attributes["type"])
        val written = CircCodec.writeProject(imported.project)
        assertFalse(written.contains("name=\"behavior\""))
        assertFalse(written.contains("name=\"type\""))
        assertTrue(written.contains("name=\"pull\" val=\"down\""))
        assertEquals(component.attributes, assertIs<CircProjectImportResult.Success>(CircCodec.readProject(written)).project.mainCircuit.components.single().attributes)
    }

    @Test
    fun convertsLegacyLibraryToolsAndPreservesHistoricalTerminalsAfterSaving() {
        val xml = """
            <project source="2.0.3" version="1.0">
              <lib name="0" desc="#Base"><tool name="Text Tool"/></lib>
              <lib name="1" desc="#Gates"><tool name="Constant"/></lib>
              <lib name="2" desc="#Plexers"/>
              <main name="Top"/>
              <toolbar><tool lib="0" name="Select Tool"/><tool lib="0" name="Wiring Tool"/></toolbar>
              <circuit name="Child">
                <a name="label" val="Child label"/>
                <comp lib="0" name="Pin" loc="(100,100)"/>
                <comp lib="0" name="Splitter" loc="(200,150)"/>
                <comp lib="1" name="Constant" loc="(100,150)"/>
                <comp lib="2" name="Decoder" loc="(300,100)"/>
              </circuit>
              <circuit name="Top"><comp name="Child" loc="(200,200)"/></circuit>
            </project>
        """.trimIndent()
        val imported = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml))
        assertTrue(imported.migrations.any { it.rule == "legacy-project-converted" && it.circuit == "Top" })
        val saved = CircCodec.writeProject(imported.project)
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(saved))
        assertFalse(reopened.migrations.any { it.rule == "legacy-project-converted" })
        for (project in listOf(imported.project, reopened.project)) {
            assertEquals("2.7.1", project.sourceVersion)
            assertEquals("Top", project.mainCircuitName)
            assertEquals("Child", project.mainCircuit.components.single().circuitName)
            val child = project.circuit("Child")!!
            assertEquals("Child label", child.attributes["clabel"])
            assertEquals("false", child.components.last().attributes["enable"])
            val splitter = child.components.single { it.kind == ComponentKind.SPLITTER }
            assertEquals(listOf(GridPoint(200, 150), GridPoint(220, 140), GridPoint(220, 150)), splitter.splitterPorts())
            assertEquals("#Wiring", project.libraries[child.components.first().xmlAttributes["lib"]])
            val toolbar = project.extraXml.map { CircXmlParser(it).parse() }.single { it.name == "toolbar" }
            val edit = toolbar.children.single()
            assertEquals("Edit Tool", edit.attributes["name"])
            assertEquals("#Base", project.libraries[edit.attributes["lib"]])
        }
    }

    @Test
    fun reportsMalformedSourceVersions() {
        val result = CircCodec.readProject("<project source=\"not-a-version\"><circuit name=\"main\"/></project>")
        assertIs<CircProjectImportResult.Failure>(result)
        assertEquals("#FPArithmetic", CircFormatProfile.parse("3.9.0").library("#Arithmetic", "FPAdder"))
        assertEquals("#Arithmetic", CircFormatProfile.parse("4.1.0").library("#Arithmetic", "FPAdder"))
    }

    private fun read(version: String, component: String, library: String, attrs: String = "") =
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject("<project source=\"$version\"><lib name=\"0\" desc=\"$library\"/><circuit name=\"main\"><comp lib=\"0\" name=\"$component\" loc=\"(100,100)\">$attrs</comp></circuit></project>"))
}
