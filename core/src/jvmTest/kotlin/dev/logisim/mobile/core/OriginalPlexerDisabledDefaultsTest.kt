package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class OriginalPlexerDisabledDefaultsTest {
    private val originalXml = assertNotNull(javaClass.getResourceAsStream("/original-logisim/plexers-disabled.circ"))
        .bufferedReader().use { it.readText() }

    @Test
    fun legacyPlexersReleaseTheirDataOutputsWhenDisabledAfterImportAndRoundTrip() {
        val project = read(originalXml)
        assertOutputs(project, "?", "???")

        val saved = CircCodec.writeProject(project)
        assertFalse(saved.contains("name=\"disabled\""))
        assertOutputs(read(saved), "?", "???")
    }

    @Test
    fun laterSourceDefaultsAndExplicitModesArePreserved() {
        for (version in listOf("2.7.1", "2.7.2", "5.1.0-dev")) {
            val versionXml = originalXml.replace("source=\"2.7.1\"", "source=\"$version\"")
            for (mode in listOf(null, "0", "Z")) {
                val xml = Regex("""name="(Demultiplexer|Decoder|Priority Encoder)"/>""").replace(versionXml) { match ->
                    val name = match.groupValues[1]
                    val enable = if (name == "Demultiplexer") """<a name="enable" val="true"/>""" else ""
                    val disabled = mode?.let { """<a name="disabled" val="$it"/>""" }.orEmpty()
                    """name="$name">$enable$disabled</comp>"""
                }
                val project = read(xml)
                val floating = mode == "Z" || mode == null && version == "2.7.1"
                val bit = if (floating) "?" else "0"
                assertOutputs(project, bit, bit.repeat(3))
                assertOutputs(read(CircCodec.writeProject(project)), bit, bit.repeat(3))
            }
        }
    }

    private fun read(xml: String): CircuitProject =
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project

    private fun assertOutputs(project: CircuitProject, bit: String, priority: String) {
        val state = CircuitSimulator().settle(project)
        val probes = mapOf(
            GridPoint(1280, 790) to bit, // Demultiplexer output 0
            GridPoint(1230, 810) to bit, // Demultiplexer output 1
            GridPoint(1450, 770) to bit, // Decoder output 0
            GridPoint(1410, 790) to bit, // Decoder output 1
            GridPoint(250, 1000) to priority,
            GridPoint(180, 960) to "0", // Priority Encoder cascade enable
            GridPoint(200, 1010) to "0", // Priority Encoder group select
        )
        assertFalse(state.oscillating, state.diagnostics.toString())
        for ((point, expected) in probes) {
            assertEquals(expected, state.portVectors[point]?.display, "${project.sourceVersion} at $point")
        }
    }
}
