package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CrossProfileDefaultsTest {
    @Test
    fun copiedGateKeepsItsInputCountAfterExport() {
        val source = project("2.7.1", "AND Gate", "#Gates")
        val restored = copyAndReopen(source, "5.1.0-dev")
        assertEquals("5", restored.attributes["inputs"])
        assertEquals(6, ComponentCatalog.registry.layout(restored).ports.size)
    }

    @Test
    fun copiedMultiplexerKeepsItsEnableAndFloatingOutput() {
        val source = project("2.7.1", "Multiplexer", "#Plexers")
        val restored = copyAndReopen(source, "5.1.0-dev")
        assertEquals("true", restored.attributes["enable"])
        assertEquals("Z", restored.attributes["disabled"])
        assertEquals(ComponentCatalog.registry.layout(source.mainCircuit.components.single()),
            ComponentCatalog.registry.layout(restored))
    }

    @Test
    fun copiedRamKeepsItsInterfaceInBothSourceProfiles() {
        for ((version, destination) in listOf("2.7.1" to "5.1.0-dev", "5.1.0-dev" to "2.7.1")) {
            val source = project(version, "RAM", "#Memory", "<a name=\"bus\" val=\"asynch\"/>")
            val restored = copyAndReopen(source, destination)
            val original = source.mainCircuit.components.single()
            assertEquals(MemoryConfiguration.fromAttributes("RAM", original.attributes),
                MemoryConfiguration.fromAttributes("RAM", restored.attributes))
            assertEquals(ComponentCatalog.registry.layout(original), ComponentCatalog.registry.layout(restored))
        }
    }

    @Test
    fun mergedMultiplexerWritesItsImplicitEnableInBothDirections() {
        assertMergedAttribute(project("3.6.1", "Multiplexer", "#Plexers"), "5.1.0-dev", "enable", "true")
        assertMergedAttribute(project("5.1.0-dev", "Multiplexer", "#Plexers"), "3.6.1", "enable", "false")
    }

    @Test
    fun mergedMultiplexerWritesEnableWhenOnlyTheSchemaSuppliesIt() {
        val source = project("5.1.0-dev", "Multiplexer", "#Plexers")
        val circuit = source.mainCircuit
        val mux = circuit.components.single()
        val schemaDefault = mux.copy(attributes = mux.attributes - "enable", implicitAttributes = mux.implicitAttributes - "enable")
        val implicitSource = source.withCircuit(circuit.copy(components = listOf(schemaDefault)))
        assertMergedAttribute(implicitSource, "3.6.1", "enable", "false")
    }

    @Test
    fun mergeRetainsExplicitValuesAndOmitsSameProfileDefaults() {
        val implicit = project("3.6.1", "Multiplexer", "#Plexers")
        assertMergedAttribute(implicit, "3.6.1", "enable", null)
        val explicit = project("3.6.1", "Multiplexer", "#Plexers", "<a name=\"enable\" val=\"false\"/>")
        assertMergedAttribute(explicit, "5.1.0-dev", "enable", "false")
    }

    @Test
    fun mergedHolyCrossRegisterWritesItsClassicAppearance() {
        val source = project("3.8.0-HC", "Register", "#Memory")
        assertMergedAttribute(source, "5.1.0-dev", "appearance", "classic")
    }

    private fun assertMergedAttribute(source: CircuitProject, hostVersion: String, attribute: String, writtenValue: String?) {
        val host = CircuitProject(listOf(CircuitDocument(name = "Host")), sourceVersion = hostVersion)
        val merged = ProjectCircuitMerge.copySelected(host, source, setOf(source.mainCircuitName))
        val saved = CircCodec.writeProject(merged)
        val root = CircXmlParser(saved).parse()
        assertEquals(hostVersion, root.attributes["source"])
        assertEquals("Host", root.children.single { it.name == "main" }.attributes["name"])
        val child = root.children.single { it.name == "circuit" && it.attributes["name"] == source.mainCircuitName }
        assertEquals(source.sourceVersion, child.attributes["logisim-mobile-source"])
        val component = child.children.single { it.name == "comp" }
        assertEquals(listOfNotNull(writtenValue), component.children
            .filter { it.name == "a" && it.attributes["name"] == attribute }
            .map { it.attributes.getValue("val") })

        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(saved)).project
        val restored = reopened.circuits.single { it.name == source.mainCircuitName }.components.single()
        assertEquals(ComponentCatalog.registry.layout(source.mainCircuit.components.single()),
            ComponentCatalog.registry.layout(restored))
        assertEquals(saved, CircCodec.writeProject(reopened))
    }

    private fun copyAndReopen(source: CircuitProject, version: String): CircuitComponent {
        val original = source.mainCircuit.components.single()
        val target = CircuitEditor(source.mainCircuit.copy(components = emptyList()))
        target.paste(CircuitEditor(source.mainCircuit).copy(setOf(original.id)), GridPoint(0, 0))
        val destination = source.copy(sourceVersion = version).withCircuit(target.document)
        return assertIs<CircProjectImportResult.Success>(CircCodec.readProject(CircCodec.writeProject(destination)))
            .project.mainCircuit.components.single()
    }

    private fun project(version: String, name: String, library: String, attributes: String = ""): CircuitProject =
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject("""
            <project source="$version"><lib name="0" desc="$library"/><main name="main"/>
              <circuit name="main"><comp lib="0" name="$name" loc="(200,200)">$attributes</comp></circuit>
            </project>
        """.trimIndent())).project
}
