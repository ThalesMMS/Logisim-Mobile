package dev.logisim.mobile

import dev.logisim.mobile.core.AppearanceEdits
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.IoDipState
import dev.logisim.mobile.core.IoInputEvent
import dev.logisim.mobile.core.IoKinds
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.SymbolAppearance
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.inputPorts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DebugInstanceSimulationTest {
    @Test
    fun pokingABoundInputCopiesTheCurrentInstanceAndLeavesItsParentIntact() {
        val state = editorWithBoundInput()
        val parentInput = state.document.components.single { it.kind == ComponentKind.INPUT }.id
        state.toggle(parentInput)
        val instance = state.document.components.single { it.kind == ComponentKind.SUBCIRCUIT }
        val child = state.project.circuit("child")!!
        val dip = child.components.single { it.kind == IoKinds.DipSwitch }.id
        state.navigate(CircuitNavigationTarget(child.id, child.name, listOf(instance.id)))
        assertTrue(state.sendComponentEvent(dip, IoInputEvent.ToggleSwitch(0)))
        val live = state.rootSimulation
        val xml = state.exportXml()
        val pin = child.components.single { it.kind == ComponentKind.INPUT }
        assertEquals(LogicValue.HIGH, state.simulation.componentValues[pin.id])
        assertEquals(1uL, assertIs<IoDipState>(state.simulation.componentStates[dip]).positions)

        state.tap(pin.position, EditorMode.INTERACT)
        assertTrue(state.canConfirmDebugCopy)
        state.cancelDebugCopy()
        assertFalse(state.isDebuggingInstance)
        assertEquals(LogicValue.HIGH, state.simulation.componentValues[pin.id])

        state.tap(pin.position, EditorMode.INTERACT)
        state.confirmDebugCopy()
        assertTrue(state.isDebuggingInstance)
        assertEquals(LogicValue.LOW, state.simulation.componentValues[pin.id])
        assertEquals(1uL, assertIs<IoDipState>(state.simulation.componentStates[dip]).positions)
        state.tap(pin.position, EditorMode.INTERACT)
        assertEquals(LogicValue.HIGH, state.simulation.componentValues[pin.id])
        assertTrue(state.sendComponentEvent(dip, IoInputEvent.ToggleSwitch(1)))
        assertEquals(3uL, assertIs<IoDipState>(state.simulation.componentStates[dip]).positions)
        state.tick()
        assertEquals(live.ticks + 1, state.simulation.ticks)
        assertSame(live, state.rootSimulation)
        assertEquals(xml, state.exportXml())

        val pendingCopyRun = state.captureRunSnapshot()
        state.returnToLiveInstance()
        assertFalse(state.isDebuggingInstance)
        assertEquals("Instância main / child · child.", state.status)
        assertFalse(state.isCurrentRunSnapshot(pendingCopyRun))
        assertSame(live.circuitStates.getValue(instance.id), state.simulation)
        assertEquals(1uL, assertIs<IoDipState>(state.simulation.componentStates[dip]).positions)
    }

    private fun editorWithBoundInput(): EditorState {
        val child = CircuitDocument("child", listOf(
            CircuitComponent("input", ComponentKind.INPUT, GridPoint(20, 40)),
            CircuitComponent("dip", IoKinds.DipSwitch, GridPoint(100, 100), attributes =
                mapOf("number" to "4", "resetbehavior" to "last", "positions" to "0")),
        ))
        val parentInput = CircuitComponent("drive", ComponentKind.INPUT, GridPoint(20, 40))
        val main = CircuitDocument("main", listOf(
            parentInput,
            CircuitComponent("childInstance", ComponentKind.SUBCIRCUIT, GridPoint(100, 40), circuitName = child.name),
        ))
        val project = AppearanceEdits.apply(CircuitProject(listOf(main, child)), child.name, SymbolAppearance.classic(child))
        val instance = project.mainCircuit.components.single { it.kind == ComponentKind.SUBCIRCUIT }
        val connection = WireSegment(parentInput.position, instance.inputPorts().single())
        return EditorState(CircCodec.writeProject(project.withCircuit(project.mainCircuit.copy(wires = listOf(connection)))))
    }
}
