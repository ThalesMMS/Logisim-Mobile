package dev.logisim.mobile

import androidx.compose.ui.text.input.TextFieldValue
import dev.logisim.mobile.core.*
import kotlin.test.*

class KeyboardInspectorSessionTest {
    private fun keyboard(id: String = "keyboard") = CircuitComponent(id, IoKinds.Keyboard, GridPoint(100, 100))
    private fun create(project: CircuitProject) = EditorState(CircCodec.writeProject(project))
    private fun session(editor: EditorState) = KeyboardInspectorSession(editor, assertNotNull(editor.keyboardDraftContext()))
    private fun host(session: KeyboardInspectorSession) = KeyboardInspectorSession.Host(InspectorPresentation.INLINE).also(session::attach)

    @Test
    fun attributeChangesKeepContextAndIneligibleCircuitHasNoKeyboardSession() {
        val main = CircuitDocument(id = "main", name = "main", components = listOf(keyboard()))
        val other = main.copy(id = "other", name = "other")
        val editor = create(CircuitProject(listOf(main, other)))
        editor.selectComponent("keyboard")
        val before = assertNotNull(editor.keyboardDraftContext())
        editor.updateSelectedAttribute("buflen", "2")
        assertEquals(before, editor.keyboardDraftContext())
        editor.selectCircuit("other")
        editor.selectComponent("keyboard")
        assertNull(editor.keyboardDraftContext(), "A non-root definition needs a live instance path")
    }

    @Test
    fun anotherInstanceCannotReceiveTheRetainedKeyboardCallback() {
        val child = CircuitDocument(name = "child", id = "child", components = listOf(keyboard()))
        val main = CircuitDocument(name = "main", id = "root", components = listOf(
            CircuitComponent("first", ComponentKind.SUBCIRCUIT, GridPoint(100, 100), circuitName = "child"),
            CircuitComponent("second", ComponentKind.SUBCIRCUIT, GridPoint(300, 100), circuitName = "child"),
        ))
        val editor = create(CircuitProject(listOf(main, child)))
        editor.navigate(CircuitNavigationTarget(child.id, child.name, listOf("first")))
        editor.selectComponent("keyboard")
        val old = session(editor)
        val binding = host(old)
        editor.navigate(CircuitNavigationTarget(child.id, child.name, listOf("second")))
        editor.selectComponent("keyboard")
        val current = session(editor)
        assertNotEquals(old.context, current.context)
        assertFalse(old.isCurrent(binding, old), "Even an old callback carrying its original session checks the live instance")
        assertNull(old.edit(binding, current, TextFieldValue("WRONG")))
        assertEquals("", assertIs<IoKeyboardState>(editor.simulation.componentStates["keyboard"]).buffer)
    }

    @Test
    fun focusingAnotherFieldOrOpeningAPropertyCancelsThePreviousRestoreIntent() {
        val editor = create(CircuitProject(listOf(CircuitDocument(components = listOf(keyboard())))))
        editor.selectComponent("keyboard")
        val keyboard = session(editor)
        val binding = host(keyboard)
        val properties = PropertyInspectorSession(editor, editor.propertyInspectorContext())
        fun pending(focus: InspectorFocusState) {
            focus.commitPresentation(focus.presentationChange(InspectorPresentation.INLINE))
            focus.focusChanged(InspectorPresentation.INLINE, true)
            assertTrue(focus.commitPresentation(focus.presentationChange(InspectorPresentation.SHEET)))
            assertNotNull(focus.focusRequest)
        }
        pending(properties.focus)
        val oldLabelRequest = assertNotNull(properties.focus.focusRequest)
        assertTrue(properties.canRequestLabelFocus(oldLabelRequest))
        keyboard.focusChanged(binding, keyboard, true, properties.focus)
        assertNull(properties.focus.focusRequest, "Keyboard focus must revoke a label request before it can steal focus")
        assertFalse(properties.canRequestLabelFocus(oldLabelRequest))
        pending(keyboard.focus)
        properties.labelFocusChanged(editor, properties, InspectorPresentation.INLINE, true, keyboard.focus)
        assertNull(keyboard.focus.focusRequest, "Label focus must revoke an outstanding keyboard request")
        pending(keyboard.focus)
        properties.open(PropertyEditorModel.fields(editor.componentRegistry, properties.context.components).single { it.name == "buflen" }, keyboard.focus)
        assertNull(keyboard.focus.focusRequest, "An attribute dialog takes precedence over the keyboard restore")
        assertNotNull(properties.editing)
        assertFalse(editor.canUndo)
    }

    @Test
    fun staleFocusCallbacksCannotCancelTheCurrentFieldsRestoreIntent() {
        val editor = create(CircuitProject(listOf(CircuitDocument(components = listOf(keyboard())))))
        editor.selectComponent("keyboard")
        val keyboard = session(editor)
        val binding = host(keyboard)
        val properties = PropertyInspectorSession(editor, editor.propertyInspectorContext())
        properties.focus.commitPresentation(properties.focus.presentationChange(InspectorPresentation.INLINE))
        properties.focus.focusChanged(InspectorPresentation.INLINE, true)
        properties.focus.commitPresentation(properties.focus.presentationChange(InspectorPresentation.SHEET))
        val labelRequest = assertNotNull(properties.focus.focusRequest)
        keyboard.detach(binding)
        keyboard.focusChanged(binding, keyboard, true, properties.focus)
        assertEquals(labelRequest, properties.focus.focusRequest)
        keyboard.focus.commitPresentation(keyboard.focus.presentationChange(InspectorPresentation.INLINE))
        keyboard.focus.focusChanged(InspectorPresentation.INLINE, true)
        keyboard.focus.commitPresentation(keyboard.focus.presentationChange(InspectorPresentation.SHEET))
        val keyboardRequest = assertNotNull(keyboard.focus.focusRequest)
        properties.close()
        properties.labelFocusChanged(editor, properties, InspectorPresentation.INLINE, true, keyboard.focus)
        assertEquals(keyboardRequest, keyboard.focus.focusRequest)
    }

    @Test
    fun disposedHostAndClosedSessionRejectEditsAndControlsWithoutCancelingInput() {
        val editor = create(CircuitProject(listOf(CircuitDocument(components = listOf(keyboard())))))
        editor.selectComponent("keyboard")
        val session = session(editor)
        val oldHost = host(session)
        var requested = 0
        editor.onSimulationRequested = { _, _ -> requested++; SimulationRequestResult.ACCEPTED }
        assertNotNull(session.edit(oldHost, session, TextFieldValue("A")))
        assertEquals(1, requested)
        session.detach(oldHost)
        assertNull(session.edit(oldHost, session, TextFieldValue("B")))
        assertFalse(session.send(oldHost, session, IoInputEvent.InsertText("C")))
        val replacement = host(session)
        assertEquals(1, requested, "Attaching/detaching a field neither resubmits nor cancels an accepted event")
        editor.setInteractionEnabled(false)
        assertNull(session.edit(replacement, session, TextFieldValue("D")))
        editor.setInteractionEnabled(true)
        session.close()
        assertNull(session.edit(replacement, session, TextFieldValue("E")))
        assertFalse(session.send(replacement, session, IoInputEvent.Cancel))
        assertEquals(1, requested)
        assertFalse(editor.canUndo)
    }
}
