package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ExampleResetDriverTest {
    @Test
    fun resetRestoresExampleInputsInsteadOfReplayingTheLastUserStimulus() {
        val project = CircuitProject(listOf(CircuitDocument(components = listOf(
            CircuitComponent("in", ComponentKind.INPUT, GridPoint(0, 0), attributes = mapOf("width" to "8")),
        ))))
        val snapshot = SimulationRunSnapshot(project, SimulationState(),
            vectorInputs = mapOf("in" to LogicVector.of(8, 99uL)),
            resetVectorInputs = mapOf("in" to LogicVector.of(8, 42uL)))
        val reset = advanceSimulation(snapshot, SimulationStep.RESET).state
        assertEquals(42uL, reset.vectorValues.getValue("in").toULongOrNull())
    }
}
