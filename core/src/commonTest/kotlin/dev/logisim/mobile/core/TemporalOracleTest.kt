package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class TemporalOracleTest {
    @Test
    fun settlingMatchesEveryDesktopTransitionAndTimestamp() = compare(singleEvent = false)

    @Test
    fun steppingThePersistentQueueMatchesTheDesktopBatches() = compare(singleEvent = true)

    @Test
    fun repeatedDrainDoesNotInventAnotherTransition() {
        val case = temporalOracleCases.single { it.id == "ram-feedback" }
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(case.xml)).project
        assertEquals(CircuitTemporalTrace.run(project, case.operations),
            CircuitTemporalTrace.run(project, case.operations + "\nD\n"))
    }

    @Test
    fun steppingHonorsTheTotalBatchBudget() {
        val case = temporalOracleCases.single { it.id == "ram-feedback" }
        val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(case.xml)).project
        val limited = case.operations.replaceFirst("\nD\n", "\nD\t1\n")
        assertFailsWith<IllegalStateException> { CircuitTemporalTrace.run(project, limited, singleEvent = true) }
    }

    private fun compare(singleEvent: Boolean) {
        assertEquals(setOf("derived-clock-feedback", "gate-input-tail", "ram-feedback", "register-input-tail",
            "simultaneous-registers", "subcircuit-ripple"),
            temporalOracleCases.map { it.id }.toSet())
        for (case in temporalOracleCases) {
            val project = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(case.xml), case.id).project
            // The proof must exercise the actual registered libraries, never the legacy fallback.
            // Tunnels, output pins and subcircuit instances only join or observe networks.
            val passive = setOf(ComponentKind.TUNNEL, ComponentKind.OUTPUT, ComponentKind.SUBCIRCUIT)
            for (component in project.circuits.flatMap { it.components }.filter { it.kind !in passive }) {
                assertNotNull(ComponentCatalog.registry.requireDefinition(component.kind).behavior,
                    "${case.id}: ${component.kind} has no registered evaluator")
            }
            val expected = case.expected.lineSequence().filter { it.startsWith("E\t") }.map { line ->
                val fields = line.split('\t')
                TemporalProbeValue(fields[1], fields[2].toLong(), fields[3], fields[4])
            }.toList()
            assertEquals(expected, CircuitTemporalTrace.run(project, case.operations, singleEvent), case.id)
        }
    }
}
