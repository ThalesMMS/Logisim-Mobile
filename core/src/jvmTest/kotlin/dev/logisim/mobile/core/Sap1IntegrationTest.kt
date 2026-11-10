package dev.logisim.mobile.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Sap1IntegrationTest {
    private fun fixture(): String = sequenceOf(
        File("../examples/sap1.circ"),
        File("examples/sap1.circ"),
    ).first { it.isFile }.readText()

    @Test
    fun originalProjectRetainsAllCircuitsAndRomPrograms() {
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(fixture())).project
        assertEquals("3.8.0", project.sourceVersion)
        assertEquals("main", project.mainCircuitName)
        assertEquals(listOf("main", "CP", "Acumulador", "Somador_Subtrator", "Registrador_B_6a147718",
            "REM", "REGISTRADOR_DE_INSTRUCOES_2d63c7b0"), project.circuits.map { it.name })
        assertEquals(156, project.circuits.sumOf { it.components.size })
        assertEquals(318, project.circuits.sumOf { it.wires.size })
        assertEquals(3, project.mainCircuit.components.count { it.kind == ComponentKind.ROM })
        val saved = CircCodec.writeProject(project)
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(saved)).project
        assertEquals(project.circuits.map { it.name }, reopened.circuits.map { it.name })
        assertEquals(project.circuits.map { it.wires }, reopened.circuits.map { it.wires })
        assertEquals(project.mainCircuit.components.filter { it.kind == ComponentKind.ROM }.map { it.attributes["contents"] },
            reopened.mainCircuit.components.filter { it.kind == ComponentKind.ROM }.map { it.attributes["contents"] })
    }

    @Test
    fun originalSap1RunsTwoPlusThreePlusFourMinusOneAndDisplaysEight() {
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(fixture())).project
        val reset = project.mainCircuit.components.single { it.label == "Reset" }
        val display = project.mainCircuit.components.single {
            it.kind == ComponentKind.REGISTER && it.position == GridPoint(600, 340)
        }
        val simulator = CircuitSimulator()
        var state = simulator.settle(project)
        state = simulator.settle(project, state, mapOf(reset.id to LogicValue.HIGH))
        state = simulator.settle(project, state, mapOf(reset.id to LogicValue.LOW))
        assertEquals(0uL, state.vectorValues.getValue(display.id).toULongOrNull())
        repeat(160) { index ->
            state = simulator.settle(project, state, tick = true)
            assertFalse(state.oscillating, "SAP-1 did not settle at tick ${index + 1}: ${state.diagnostics}")
            val expected = if (index + 1 < 56) 0uL else 8uL
            assertEquals(expected, state.vectorValues.getValue(display.id).toULongOrNull(),
                "SAP-1 display at tick ${index + 1}")
        }
        assertEquals(160L, state.ticks)
        assertEquals(6, state.circuitStates.size)
        assertTrue(state.circuitStates.values.all { it.diagnostics.isEmpty() }, state.diagnostics.joinToString())
    }
}
