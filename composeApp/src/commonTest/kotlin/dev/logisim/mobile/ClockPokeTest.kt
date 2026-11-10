package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.LogicValue
import kotlin.test.Test
import kotlin.test.assertEquals

class ClockPokeTest {
    @Test
    fun pokingOneInstanceInvertsOnlyItsClockWithoutAdvancingTimeOrChangingXml() {
        val state = twoClockInstances("2.7.1")
        val xml = state.exportXml()
        val tapPoint = GridPoint(90, 80)
        assertClocks(state, 0, LogicValue.LOW, LogicValue.LOW)
        state.tap(tapPoint, EditorMode.SELECT)
        assertClocks(state, 0, LogicValue.LOW, LogicValue.LOW)

        state.tap(tapPoint, EditorMode.INTERACT)
        assertClocks(state, 0, LogicValue.HIGH, LogicValue.LOW)
        state.tap(tapPoint, EditorMode.INTERACT)
        assertClocks(state, 0, LogicValue.LOW, LogicValue.LOW)
        state.tap(tapPoint, EditorMode.INTERACT)
        assertClocks(state, 0, LogicValue.HIGH, LogicValue.LOW)

        state.tick()
        assertClocks(state, 1, LogicValue.LOW, LogicValue.HIGH)
        state.tick()
        assertClocks(state, 2, LogicValue.HIGH, LogicValue.LOW)
        assertEquals(xml, state.exportXml())

        state.resetSimulation()
        assertClocks(state, 0, LogicValue.LOW, LogicValue.LOW)
        assertEquals(xml, state.exportXml())
    }

    @Test
    fun evolutionClockPokeStepsAllInstances() {
        val state = twoClockInstances("5.1.0-dev")
        val xml = state.exportXml()

        state.tap(GridPoint(90, 80), EditorMode.INTERACT)
        assertClocks(state, 1, LogicValue.HIGH, LogicValue.HIGH)
        state.tap(GridPoint(90, 80), EditorMode.INTERACT)
        assertClocks(state, 2, LogicValue.LOW, LogicValue.LOW)
        assertEquals(xml, state.exportXml())
    }

    private fun twoClockInstances(source: String): EditorState {
        val clock = CircuitComponent("clock", ComponentKind.CLOCK, GridPoint(100, 80))
        val child = CircuitDocument("child", listOf(clock))
        val main = CircuitDocument("main", listOf(
            CircuitComponent("first", ComponentKind.SUBCIRCUIT, GridPoint(100, 100), circuitName = child.name),
            CircuitComponent("second", ComponentKind.SUBCIRCUIT, GridPoint(100, 200), circuitName = child.name),
        ))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(main, child), sourceVersion = source)))
        val childDocument = state.project.circuit("child")!!
        val first = state.document.components.first()
        state.navigate(CircuitNavigationTarget(childDocument.id, childDocument.name, listOf(first.id)))
        return state
    }

    private fun assertClocks(state: EditorState, ticks: Long, firstValue: LogicValue, secondValue: LogicValue) {
        val (first, second) = state.project.mainCircuit.components
        val clockId = state.document.components.single().id
        val simulation = state.rootSimulation
        assertEquals(ticks, simulation.ticks)
        assertEquals(firstValue, simulation.circuitStates.getValue(first.id).componentValues[clockId])
        assertEquals(secondValue, simulation.circuitStates.getValue(second.id).componentValues[clockId])
    }
}
