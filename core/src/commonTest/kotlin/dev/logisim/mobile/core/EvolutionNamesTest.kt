package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #321: Evolution d58a61e0 name rules (SyntaxChecker, XmlReader.labelVHDLInvalid and generateValidVHDLLabel). */
class EvolutionNamesTest {
    @Test
    fun entryRulesFollowSyntaxChecker() {
        for (accepted in listOf("A", "main", "Minha_primeira_porta_AND", "x1_y2", "DFF", "")) {
            assertNull(EvolutionNames.entryProblem(accepted), accepted)
        }
        val refused = mapOf(
            "1abc" to "evolution_name_starts_with_digit",
            "Visão" to "evolution_name_invalid_character",
            "a b" to "evolution_name_invalid_character",
            "_a" to "evolution_name_invalid_character",
            "a__b" to "evolution_name_double_underscore",
            "a_" to "evolution_name_ends_with_underscore",
            "Signal" to "evolution_name_vhdl_keyword",
            "AND" to "evolution_name_vhdl_keyword",
        )
        for ((value, key) in refused) assertEquals(key, EvolutionNames.entryProblem(value)?.key, value)
        assertEquals(" ", EvolutionNames.entryProblem("a b")!!.arguments["character"])
        assertEquals("ã", EvolutionNames.entryProblem("Visão")!!.arguments["character"])
    }

    @Test
    fun labelsAndCircuitNamesHaveEvolutionsExtraChecks() {
        assertEquals("evolution_label_matches_component", EvolutionNames.labelProblem("pin", "Pin")?.key)
        assertNull(EvolutionNames.labelProblem("A", "Pin"))
        val circuit = CircuitDocument(name = "top", components = listOf(
            CircuitComponent("a", ComponentKind.INPUT, GridPoint(0, 0), "Data")))
        assertEquals("evolution_circuit_name_matches_pin", EvolutionNames.circuitNameProblem("data", circuit)?.key)
        assertNull(EvolutionNames.circuitNameProblem("other", circuit))
    }

    @Test
    fun loadRenameMatchesGenerateValidVhdlLabel() {
        // Expected values computed by Evolution's XmlReader.generateValidVHDLLabel(label, "abcd1234").
        val expected = mapOf(
            "Visão → ‫اختبار‬" to "Vis_o_abcd1234",
            "E2E — ligação longa → entrada / ‫مرحبا‬" to "E2E_liga_o_longa_entrada_abcd1234",
            "Minha primeira porta AND" to "Minha_primeira_porta_AND_abcd1234",
            "  padded  " to "padded",
            "9a!b" to "L_9aNOT_b_abcd1234",
            "~x😀y" to "NOT_x_y_abcd1234",
            "a_" to "a_abcd1234",
        )
        for ((initial, renamed) in expected) assertEquals(renamed, EvolutionNames.validLabel(initial, "abcd1234"), initial)
        assertTrue(EvolutionNames.renamedOnLoad("a b"))
        assertTrue(EvolutionNames.renamedOnLoad("a__b"))
        // XmlReader.labelVHDLInvalid ignores keywords: Evolution opens "AND" unchanged.
        assertFalse(EvolutionNames.renamedOnLoad("AND"))
        assertEquals("Vis_o_a6e3837c", EvolutionNames.validLabel("Visão → ‫اختبار‬"))
    }

    @Test
    fun openingRenamesNamesAndLabelsLikeEvolution() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project source="3.8.0" version="1.0">
              <lib name="0" desc="#Wiring"/>
              <main name="Visão geral"/>
              <circuit name="Visão geral">
                <a name="circuit" val="Visão geral"/>
                <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="entrada 1"/></comp>
                <comp lib="0" loc="(200,100)" name="Tunnel"><a name="label" val="entrada 1"/></comp>
                <comp loc="(300,100)" name="sub ok"/>
              </circuit>
              <circuit name="sub ok">
                <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="AND"/></comp>
              </circuit>
            </project>
        """.trimIndent()
        val result = CircCodec.readProject(xml) as CircProjectImportResult.Success
        val project = result.project
        val top = EvolutionNames.validLabel("Visão geral")
        val sub = EvolutionNames.validLabel("sub ok")
        val label = EvolutionNames.validLabel("entrada 1")
        assertEquals(listOf(top, sub), project.circuits.map { it.name })
        assertEquals(top, project.mainCircuitName)
        assertEquals("Visão geral", project.mainCircuit.id, "the Mobile identity stays")
        assertEquals(listOf(label, label, ""), project.mainCircuit.components.map { it.label })
        assertEquals(sub, project.mainCircuit.components.last().circuitName)
        assertEquals("AND", project.circuit(sub)!!.components.single().label)
        assertEquals(setOf("evolution-circuit-renamed", "evolution-label-renamed"), result.migrations.map { it.rule }.filter { it.startsWith("evolution-") }.toSet())

        // A second save and open keeps every name: they are already valid.
        val saved = CircCodec.writeProject(project)
        val reopened = CircCodec.readProject(saved) as CircProjectImportResult.Success
        assertEquals(project.circuits.map { it.name }, reopened.project.circuits.map { it.name })
        assertTrue(reopened.migrations.none { it.rule.startsWith("evolution-") })
        assertEquals(saved, CircCodec.writeProject(reopened.project))
    }

    @Test
    fun originalLogisimFilesKeepTheirNames() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project source="2.7.1" version="1.0">
              <lib name="0" desc="#Wiring"/>
              <main name="Visão geral"/>
              <circuit name="Visão geral">
                <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="A'"/></comp>
              </circuit>
            </project>
        """.trimIndent()
        val project = (CircCodec.readProject(xml) as CircProjectImportResult.Success).project
        assertEquals("Visão geral", project.mainCircuitName)
        assertEquals("A'", project.mainCircuit.components.single().label)
        assertFalse(project.followsEvolutionNames())
        assertNotNull(DemoCircuits.andGate().takeIf { doc ->
            EvolutionNames.entryProblem(doc.name) == null && doc.components.none { EvolutionNames.renamedOnLoad(it.label) }
        })
    }
}
