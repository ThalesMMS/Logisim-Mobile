package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PerformanceCircuitsTest {
    @Test
    fun generatedBanksExerciseEveryOutputBeforeAndAfterInputChanges() {
        for (factory in listOf(PerformanceCircuits::independentGates, PerformanceCircuits::hierarchicalGates)) {
            val case = factory(8)
            val simulator = CircuitSimulator()
            var state = SimulationState()
            for (high in listOf(true, false, true)) {
                state = simulator.settle(case.project, state, vectorInputs = case.inputs(high))
                assertFalse(state.oscillating)
                case.observedOutputs.forEach { assertEquals(case.expected(high), state.vectorValues[it]) }
            }
        }
    }

    @Test
    fun growthCountsAreExplicitAndHierarchiesUseIndependentInstances() {
        val flat = PerformanceCircuits.independentGates(32)
        val nested = PerformanceCircuits.hierarchicalGates(32)
        assertEquals(128, flat.project.mainCircuit.components.size)
        assertEquals(32, flat.project.mainCircuit.wires.size)
        assertEquals(128, nested.project.mainCircuit.components.size)
        assertEquals(32, nested.project.mainCircuit.wires.size)
        val state = CircuitSimulator().settle(nested.project, vectorInputs = nested.inputs(true))
        assertEquals(32, state.circuitStates.size)
        assertEquals(32, nested.observedOutputs.size)
    }

    @Test
    fun exportedCorporaPreserveTheirSignalPaths() {
        for (factory in listOf(PerformanceCircuits::independentGates, PerformanceCircuits::hierarchicalGates)) {
            val original = factory(8)
            val project = assertIs<CircProjectImportResult.Success>(
                CircCodec.readProject(CircCodec.writeProject(original.project))).project
            val restored = original.rebound(project)
            val state = CircuitSimulator().settle(project, vectorInputs = restored.inputs(true))
            assertFalse(state.oscillating)
            restored.observedOutputs.forEach { assertEquals(restored.expected(true), state.vectorValues[it]) }
        }
    }
}
