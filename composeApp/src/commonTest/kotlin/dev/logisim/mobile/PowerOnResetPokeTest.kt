package dev.logisim.mobile

import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.WiringLibrary
import dev.logisim.mobile.core.layout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PowerOnResetPokeTest {
    @Test
    fun pokeRearmsEitherPolarityOnlyInTheTargetInstance() {
        val child = CircuitDocument("child", listOf(
            CircuitComponent("falling", WiringLibrary.PowerOnReset, GridPoint(100, 100),
                attributes = mapOf("porTransition" to "1", "PorHighDuration" to "1")),
            CircuitComponent("rising", WiringLibrary.PowerOnReset, GridPoint(100, 200),
                attributes = mapOf("porTransition" to "2", "PorHighDuration" to "1")),
        ))
        val main = CircuitDocument("main", listOf(
            CircuitComponent("first", ComponentKind.SUBCIRCUIT, GridPoint(100, 100), circuitName = child.name),
            CircuitComponent("second", ComponentKind.SUBCIRCUIT, GridPoint(300, 100), circuitName = child.name),
        ))
        val state = EditorState(CircCodec.writeProject(CircuitProject(listOf(main, child), sourceVersion = "5.1.0-dev")))
        val (first, second) = state.document.components
        state.viewSubcircuitInstance(first.id)
        val (falling, rising) = state.document.components
        val xml = state.exportXml()

        fun levels(instanceId: String): String {
            val outputs = state.rootSimulation.circuitStates.getValue(instanceId).componentValues
            return outputs.getValue(falling.id).display + outputs.getValue(rising.id).display
        }

        fun assertLevels(target: String) {
            assertEquals(target, levels(first.id))
            assertEquals("01", levels(second.id))
        }

        var elapsed = 1_000L
        advanceTime(state, elapsed)
        assertLevels("01")
        assertNull(state.rootSimulation.nextWakeupMillis)

        for ((part, activeLevels) in listOf(falling to "11", rising to "00")) {
            val bounds = part.layout(state.componentRegistry).bounds
            val point = GridPoint((bounds.left + bounds.right) / 2, (bounds.top + bounds.bottom) / 2)
            for (mode in listOf(EditorMode.SELECT, EditorMode.EDIT)) {
                state.tap(point, mode)
                assertLevels("01")
                assertNull(state.rootSimulation.nextWakeupMillis)
            }

            repeat(2) {
                state.tap(point, EditorMode.INTERACT)
                assertLevels(activeLevels)
                assertEquals(elapsed + 1_000, state.rootSimulation.nextWakeupMillis)
                advanceTime(state, elapsed + 999)
                assertLevels(activeLevels)
                elapsed += 1_000
                advanceTime(state, elapsed)
                assertLevels("01")
                assertNull(state.rootSimulation.nextWakeupMillis)
            }
        }

        assertEquals(0L, state.rootSimulation.ticks)
        assertEquals(xml, state.exportXml())
        assertFalse(state.canUndo)
    }

    private fun advanceTime(state: EditorState, elapsedTimeMillis: Long) {
        val snapshot = state.captureRunSnapshot().copy(elapsedTimeMillis = elapsedTimeMillis)
        state.publishRun(snapshot, advanceSimulation(snapshot, SimulationStep.SETTLE, state.componentRegistry).state)
    }
}
