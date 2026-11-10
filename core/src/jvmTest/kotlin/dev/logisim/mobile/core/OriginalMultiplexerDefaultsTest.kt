package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OriginalMultiplexerDefaultsTest {
    private val registry = ComponentCatalog.registry
    private val simulator = CircuitSimulator(registry = registry)
    private val originalXml = assertNotNull(javaClass.getResourceAsStream("/original-logisim/mux-disabled.circ"))
        .bufferedReader().use { it.readText() }

    @Test
    fun originalMultiplexerFloatsWhenDisabledAfterImportAndRoundTrip() {
        assertFalse(originalXml.contains("name=\"disabled\""))
        val project = read(originalXml)
        assertEquals("Z", multiplexer(project).attributes["disabled"])
        assertEquals("Z", multiplexer(project).implicitAttributes["disabled"])
        assertOutput(project, "?")

        val saved = CircCodec.writeProject(project)
        assertFalse(saved.contains("name=\"disabled\""))
        val reopened = read(saved)
        assertEquals("2.7.1", reopened.sourceVersion)
        assertEquals("Z", multiplexer(reopened).attributes["disabled"])
        assertOutput(reopened, "?")
    }

    @Test
    fun explicitDisabledModesOverrideBothOriginalAndCurrentDefaults() {
        for (version in listOf("2.7.1", "3.8.0", "5.1.0-dev")) {
            for ((disabled, expected) in listOf("0" to "0", "Z" to "?")) {
                val project = read(fixture(version, "<a name=\"enable\" val=\"true\"/><a name=\"disabled\" val=\"$disabled\"/>"))
                assertEquals(disabled, multiplexer(project).attributes["disabled"])
                assertFalse("disabled" in multiplexer(project).implicitAttributes)
                assertOutput(project, expected)
                val saved = CircCodec.writeProject(project)
                assertTrue(saved.contains("name=\"disabled\" val=\"$disabled\""))
                assertOutput(read(saved), expected)
            }
        }
    }

    @Test
    fun originalDisabledMultiplexerReleasesTheOutputToAPullResistor() {
        val project = read(originalXml)
        val pulled = project.withCircuit(project.mainCircuit.copy(components = project.mainCircuit.components +
            registry.create(WiringLibrary.PullResistor, "pull-up", GridPoint(300, 150), mapOf("pull" to "1"))))
        assertOutput(pulled, "1")
        assertOutput(read(CircCodec.writeProject(pulled)), "1")
    }

    @Test
    fun evolutionAndNewMultiplexersKeepCurrentDefaults() {
        for (version in listOf("2.7.2", "3.0.0", "3.8.0", "5.1.0-dev")) {
            val project = read(fixture(version, "<a name=\"enable\" val=\"true\"/>"))
            assertEquals("0", multiplexer(project).attributes["disabled"])
            assertOutput(project, "0")
            assertOutput(read(CircCodec.writeProject(project)), "0")
        }
        val newMultiplexer = registry.create(PlexersLibrary.Multiplexer, "new-mux", GridPoint(210, 150))
        assertEquals("0", newMultiplexer.attributes["disabled"])
        assertEquals("false", newMultiplexer.attributes["enable"])
    }

    private fun fixture(version: String, attributes: String): String = originalXml
        .replace("source=\"2.7.1\"", "source=\"$version\"")
        .replace("name=\"Multiplexer\"/>", "name=\"Multiplexer\">$attributes</comp>")

    private fun read(xml: String): CircuitProject =
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml, registry)).project

    private fun multiplexer(project: CircuitProject): CircuitComponent =
        project.mainCircuit.components.single { it.kind == PlexersLibrary.Multiplexer }

    private fun assertOutput(project: CircuitProject, expected: String) {
        val state = simulator.settle(project)
        val output = project.mainCircuit.components.single { it.label == "Y" }
        assertEquals(expected, state.vectorValues.getValue(output.id).display)
        assertTrue(state.diagnostics.isEmpty(), state.diagnostics.toString())
        assertFalse(state.oscillating)
    }
}
