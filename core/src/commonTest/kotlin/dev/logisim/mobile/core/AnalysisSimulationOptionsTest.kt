package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnalysisSimulationOptionsTest {
    @Test
    fun analysisAndVectorsUseTheImportedGatePolicy() {
        val vectors = TestVectorFormat.read("A Y\n0 0\n1 1\n")
        val cases = listOf("ignore" to listOf("0", "1"), "error" to listOf("E", "E"))
        for ((policy, expected) in cases) {
            val project = gateProject(policy)
            val input = project.mainCircuit.components.single { it.label == "A" }
            val output = project.mainCircuit.components.single { it.label == "Y" }
            val settings = SimulationProjectOptions.read(project)
            val simulator = CircuitSimulator(propagationLimit = settings.propagationLimit)
            val live = (0..1).map { bit ->
                simulator.settle(
                    project,
                    vectorInputs = mapOf(input.id to LogicVector.of(1, bit.toULong())),
                    options = settings.asMap(),
                ).vectorValues.getValue(output.id).display
            }
            assertEquals(expected, live)

            val rows = TestVectorRunSession(project, project.mainCircuitName, vectors).run()
            assertEquals(expected, rows.map { it.observed.last().display })
            assertEquals(listOf(policy == "ignore", policy == "ignore"), rows.map { it.passed })

            if (policy == "ignore") {
                assertEquals(listOf(listOf(TruthCell.ZERO), listOf(TruthCell.ONE)), CircuitAnalyzer.truthTable(project).rows)
            } else {
                val error = assertFailsWith<IllegalArgumentException> { CircuitAnalyzer.truthTable(project) }
                assertTrue(error.message.orEmpty().contains("Y"))
            }
        }
    }

    @Test
    fun analysisAndVectorsUseTheImportedPropagationLimit() {
        val project = gateProject("ignore", limit = 1)
        val settings = SimulationProjectOptions.read(project)
        val live = CircuitSimulator(propagationLimit = settings.propagationLimit)
            .settle(project, options = settings.asMap())
        assertTrue(live.oscillating)
        assertFailsWith<IllegalArgumentException> { CircuitAnalyzer.truthTable(project) }
        val table = TestVectorFormat.read("A Y\n0 0\n")
        val row = TestVectorRunSession(project, project.mainCircuitName, table).run().single()
        assertTrue(row.differences.single().oscillating)
    }

    private fun gateProject(policy: String, limit: Int = 256): CircuitProject =
        (CircCodec.readProject("""
            <project source="2.7.1" version="1.0">
              <lib name="0" desc="#Wiring"/>
              <lib name="1" desc="#Gates"/>
              <main name="main"/>
              <options><a name="gateUndefined" val="$policy"/><a name="simlimit" val="$limit"/></options>
              <circuit name="main">
                <comp lib="0" name="Pin" loc="(100,80)"><a name="label" val="A"/></comp>
                <comp lib="1" name="AND Gate" loc="(200,100)"><a name="inputs" val="2"/></comp>
                <comp lib="0" name="Pin" loc="(260,100)"><a name="facing" val="west"/><a name="output" val="true"/><a name="label" val="Y"/></comp>
                <wire from="(100,80)" to="(150,80)"/>
                <wire from="(200,100)" to="(260,100)"/>
              </circuit>
            </project>
        """.trimIndent()) as CircProjectImportResult.Success).project
}
