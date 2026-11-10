package dev.logisim.mobile

import dev.logisim.mobile.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EditorIoInteractionGateTest {
    @Test
    fun aLockedEditorRejectsNewInputWithoutRequestingOrRunningSimulation() {
        val state = fixture()
        var requests = 0
        state.onSimulationRequested = { snapshot, step ->
            requests++
            state.publishRun(snapshot, advanceSimulation(snapshot, step).state)
            SimulationRequestResult.ACCEPTED
        }
        state.setInteractionEnabled(false)
        val before = state.rootSimulation
        val xml = state.exportXml()
        val revision = state.revision

        assertFalse(state.sendComponentEvent(state.id(IoKinds.Button), IoInputEvent.Press(true)))
        assertFalse(state.sendComponentEvent(state.id(IoKinds.DipSwitch), IoInputEvent.ToggleSwitch(0)))
        assertFalse(state.sendComponentEvent(state.id(IoKinds.Joystick), IoInputEvent.MoveJoystick(10, 5)))
        assertFalse(state.sendComponentEvent(state.id(IoKinds.Keyboard), IoInputEvent.InsertText("A")))
        assertFalse(state.sendComponentEvent(state.id(IoKinds.MatrixKeypad), IoInputEvent.KeypadKey(0, 0, true)))
        assertSame(before, state.rootSimulation)
        assertEquals(0, requests)
        assertEquals(xml, state.exportXml())
        assertEquals(revision, state.revision)

        state.setInteractionEnabled(true)
        assertTrue(state.injectEvents(mapOf(
            state.id(IoKinds.Button) to listOf(IoInputEvent.Press(true)),
            state.id(IoKinds.DipSwitch) to listOf(IoInputEvent.ToggleSwitch(0)),
        )))
        assertEquals(1, requests)
        assertTrue(assertIs<IoButtonState>(state.rootSimulation.componentStates[state.id(IoKinds.Button)]).pressed)
        assertEquals(1uL, assertIs<IoDipState>(state.rootSimulation.componentStates[state.id(IoKinds.DipSwitch)]).positions)
    }

    @Test
    fun mixingANewInputWithAReleaseRejectsTheWholeBatch() {
        val state = fixture()
        val button = state.id(IoKinds.Button)
        val dip = state.id(IoKinds.DipSwitch)
        assertTrue(state.sendComponentEvent(button, IoInputEvent.Press(true)))
        state.setInteractionEnabled(false)
        val before = state.rootSimulation
        var requests = 0
        state.onSimulationRequested = { snapshot, step ->
            requests++
            state.publishRun(snapshot, advanceSimulation(snapshot, step).state)
            SimulationRequestResult.ACCEPTED
        }

        assertFalse(state.injectEvents(mapOf(
            button to listOf(IoInputEvent.Cancel),
            dip to listOf(IoInputEvent.ToggleSwitch(0)),
        )))
        assertSame(before, state.rootSimulation)
        assertEquals(0, requests)
        assertTrue(assertIs<IoButtonState>(state.rootSimulation.componentStates[button]).pressed)
        assertTrue(state.sendComponentEvent(button, IoInputEvent.Cancel))
        assertFalse(assertIs<IoButtonState>(state.rootSimulation.componentStates[button]).pressed)
        assertEquals(0uL, assertIs<IoDipState>(state.rootSimulation.componentStates[dip]).positions)
        assertEquals(1, requests)
    }

    @Test
    fun releasesAndCancellationClearHeldInputsWhileTheEditorIsLocked() {
        val state = fixture()
        val button = state.id(IoKinds.Button)
        val joystick = state.id(IoKinds.Joystick)
        val keypad = state.id(IoKinds.MatrixKeypad)
        assertTrue(state.injectEvents(mapOf(
            button to listOf(IoInputEvent.Press(true)),
            joystick to listOf(IoInputEvent.MoveJoystick(10, 5)),
            keypad to listOf(IoInputEvent.KeypadKey(1, 2, true)),
        )))
        assertEquals(setOf(6), assertIs<IoKeypadState>(state.rootSimulation.componentStates[keypad]).pressed)
        state.setInteractionEnabled(false)

        assertTrue(state.injectEvents(mapOf(
            button to listOf(IoInputEvent.Press(false)),
            joystick to listOf(IoInputEvent.Cancel),
            keypad to listOf(IoInputEvent.KeypadKey(1, 2, false)),
        )))
        state.setInteractionEnabled(true)
        assertFalse(assertIs<IoButtonState>(state.rootSimulation.componentStates[button]).pressed)
        assertEquals(IoJoystickState(), assertIs<IoJoystickState>(state.rootSimulation.componentStates[joystick]))
        assertEquals(emptySet(), assertIs<IoKeypadState>(state.rootSimulation.componentStates[keypad]).pressed)
        assertTrue(state.sendComponentEvent(button, IoInputEvent.Press(true)))
        assertTrue(assertIs<IoButtonState>(state.rootSimulation.componentStates[button]).pressed)
    }

    private fun fixture(): EditorState = EditorState(null).also { state ->
        state.newDocument()
        listOf(IoKinds.Button, IoKinds.DipSwitch, IoKinds.Joystick, IoKinds.Keyboard, IoKinds.MatrixKeypad)
            .forEachIndexed { index, kind -> assertTrue(state.insertComponent(kind, GridPoint(index * 200, 100))) }
    }

    private fun EditorState.id(kind: ComponentKind): String = document.components.single { it.kind == kind }.id
}
