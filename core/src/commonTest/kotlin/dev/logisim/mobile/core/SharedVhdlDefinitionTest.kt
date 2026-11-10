package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedVhdlDefinitionTest {
    private val source = """
        -- Source roundtrips with XML operators <, > and &.
        library ieee;
        use ieee.std_logic_1164.all;
        entity inv is port(a : in std_logic; y : out std_logic); end entity;
        architecture rtl of inv is begin y <= not a; end architecture;
    """.trimIndent()

    @Test fun evolutionInstancesUseSharedSourceAndKeepTheirWireEndpoints() {
        val project = read(evolutionProject())
        assertEquals(listOf("inv"), project.sharedVhdlDefinitions().map { it.name })
        for ((index, instance) in project.mainCircuit.components.withIndex()) {
            val row = 100 + index * 100
            assertEquals(source, HdlIpLibrary.parse(instance).source)
            assertEquals(GridPoint(80, row), instance.layout().ports.single { it.name == "a" }.position)
            assertEquals(GridPoint(300, row), instance.layout().ports.single { it.name == "y" }.position)
        }

        val editedSource = source.replace("not a", "a")
        val edited = project.updateSharedVhdlSource("inv", editedSource)
        val xml = CircCodec.writeProject(edited)
        assertEquals(1, Regex("<vhdl\\b").findAll(xml).count())
        // Evolution's toolbox order: the shared entity follows the circuits, where Evolution appends it.
        assertTrue(xml.indexOf("<vhdl") > xml.lastIndexOf("</circuit>"))
        assertEquals(2, Regex("<comp[^>]* name=\"inv\"").findAll(xml).count())
        assertFalse("name=\"content\"" in xml)
        assertFalse("#HDL-IP" in xml)
        assertTrue("future=\"preserved\"" in xml)
        val reopened = read(xml)
        assertEquals(editedSource, reopened.sharedVhdlDefinitions().single().source)
        assertEquals(listOf(editedSource, editedSource), reopened.mainCircuit.components.map { HdlIpLibrary.parse(it).source })
        assertEquals(project.mainCircuit.wires, reopened.mainCircuit.wires)
        assertEquals(listOf("first", "second"), reopened.mainCircuit.components.map { it.label })
    }

    @Test fun newDefinitionAndImportedToolbarProduceReferencesToOneDefinition() {
        val (created, definition) = CircuitProject(listOf(CircuitDocument("main"))).addSharedVhdlDefinition(source)
        val first = definition.instance("first", GridPoint(300, 100))
        val imported = read(evolutionProject())
        val item = ProjectToolbar.read(imported).single()
        val tool = assertIs<ProjectToolbarBinding.Place>(ProjectToolbar.binding(item, imported, ComponentCatalog.registry))
        val second = tool.component.copy(id = "second", position = GridPoint(300, 200))
        val withInstances = created.withCircuit(created.mainCircuit.copy(components = listOf(first, second)))
        val reopened = read(CircCodec.writeProject(withInstances))
        assertEquals(source, reopened.sharedVhdlDefinitions().single().source)
        assertEquals(listOf("inv", "inv"), reopened.mainCircuit.components.map { reopened.sharedVhdlDefinition(it)?.name })
        assertFailsWith<IllegalArgumentException> { reopened.updateSharedVhdlSource("inv", source.replace("inv", "new_name")) }
        assertFailsWith<IllegalArgumentException> { reopened.addSharedVhdlDefinition(source) }

        val detached = read(CircCodec.write(reopened.mainCircuit.copy(fileMetadata = null)))
        assertEquals(emptyList(), detached.sharedVhdlDefinitions())
        assertEquals(source, HdlIpLibrary.parse(detached.mainCircuit.components.first()).source)
        assertEquals(first.layout(), detached.mainCircuit.components.first().layout())
    }

    private fun read(xml: String): CircuitProject = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project

    private fun evolutionProject(): String = """
        <project source="5.1.0dev" version="1.0">
          <main name="main"/>
          <toolbar><tool name="inv"/></toolbar>
          <vhdl name="inv" appearance="logisim_evolution" future="preserved"><![CDATA[$source]]></vhdl>
          <circuit name="main">
            <comp name="inv" loc="(300,100)"><a name="label" val="first"/></comp>
            <comp name="inv" loc="(300,200)"><a name="label" val="second"/></comp>
            <wire from="(40,100)" to="(80,100)"/><wire from="(300,100)" to="(340,100)"/>
            <wire from="(40,200)" to="(80,200)"/><wire from="(300,200)" to="(340,200)"/>
          </circuit>
        </project>
    """.trimIndent()
}
