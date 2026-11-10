package dev.logisim.mobile

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PropertyInspectorSessionTest {
    private fun circuit(name: String = "main") = CircuitDocument(name = name, id = name, components = listOf(
        CircuitComponent("one", ComponentKind.AND, GridPoint(100, 100), "A"),
        CircuitComponent("two", ComponentKind.AND, GridPoint(300, 100), "B"),
    ))

    private fun editor(project: CircuitProject = CircuitProject(listOf(circuit()))) =
        EditorState(CircCodec.writeProject(project)).also { it.selectComponent("one") }

    private fun session(editor: EditorState) = PropertyInspectorSession(editor, editor.propertyInspectorContext())

    private fun width(session: PropertyInspectorSession, editor: EditorState): PropertyValueEdit {
        session.open(PropertyEditorModel.fields(editor.componentRegistry, session.context.components).single { it.name == "width" })
        return assertNotNull(session.editing)
    }

    @Test
    fun labelDraftSelectionAndCompositionSurviveTheInlineToSheetHandoff() {
        val editor = editor()
        val session = session(editor)
        val original = editor.exportXml()
        session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.INLINE))
        session.label = TextFieldValue("Re", TextRange(1, 2), TextRange(0, 2))
        session.focus.focusChanged(InspectorPresentation.INLINE, true)
        val transition = session.focus.presentationChange(InspectorPresentation.SHEET)
        session.focus.focusChanged(InspectorPresentation.INLINE, false)
        assertTrue(session.focus.commitPresentation(transition))
        assertEquals(InspectorPresentation.SHEET, session.focus.focusRequest?.presentation)
        assertEquals(TextFieldValue("Re", TextRange(1, 2), TextRange(0, 2)), session.label)
        session.focus.focusChanged(InspectorPresentation.INLINE, false)
        assertNotNull(session.focus.focusRequest, "The disposed or background host cannot consume the new host's request")
        session.focus.focusChanged(InspectorPresentation.SHEET, true)
        session.focus.completeFocus(assertNotNull(session.focus.focusRequest))
        assertNull(session.focus.focusRequest)
        assertEquals(original, editor.exportXml())
        assertFalse(editor.canUndo)
    }

    @Test
    fun sheetToInlineRestoresOnlyAnActuallyFocusedLabel() {
        val session = session(editor())
        session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.SHEET))
        session.focus.focusChanged(InspectorPresentation.SHEET, true)
        assertTrue(session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.INLINE)))
        assertEquals(InspectorPresentation.INLINE, session.focus.focusRequest?.presentation)
        session.focus.focusChanged(InspectorPresentation.INLINE, true)
        session.focus.completeFocus(assertNotNull(session.focus.focusRequest))
        session.focus.focusChanged(InspectorPresentation.INLINE, false)
        assertFalse(session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.SHEET)))
        assertNull(session.focus.focusRequest)
    }

    @Test
    fun rapidResizeRetargetsAnUnconsumedFocusRequest() {
        val session = session(editor())
        session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.SHEET))
        session.focus.focusChanged(InspectorPresentation.SHEET, true)
        assertTrue(session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.INLINE)))
        val inline = assertNotNull(session.focus.focusRequest)
        session.focus.focusChanged(InspectorPresentation.SHEET, false)
        assertTrue(session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.SHEET)))
        assertEquals(InspectorPresentation.SHEET, session.focus.focusRequest?.presentation)
        session.focus.completeFocus(inline)
        assertNotNull(session.focus.focusRequest, "Completion from the abandoned host cannot consume the new request")
        session.focus.dismissPresentation(InspectorPresentation.SHEET)
        assertNull(session.focus.focusRequest)
        assertFalse(session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.INLINE)))
    }

    @Test
    fun anExplicitSheetAlreadyOwningFocusDoesNotRequestItAgainOnCompactResize() {
        val session = session(editor())
        session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.INLINE))
        session.focus.focusChanged(InspectorPresentation.SHEET, true)
        assertFalse(session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.SHEET)))
        assertNull(session.focus.focusRequest)
    }

    @Test
    fun invalidPropertyDraftSurvivesResizeWithoutChangingTheDocument() {
        val editor = editor()
        val session = session(editor)
        val before = editor.exportXml()
        val edit = width(session, editor)
        edit.value = TextFieldValue("0", TextRange(0, 1), TextRange(0, 1))
        session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.INLINE))
        assertFalse(session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.SHEET)))
        assertSame(edit, session.editing)
        assertEquals(TextFieldValue("0", TextRange(0, 1), TextRange(0, 1)), edit.value)
        assertIs<PropertyEditResult.Invalid>(session.validation(edit))
        assertFalse(session.applyValue(editor, session, edit))
        assertSame(edit, session.editing)
        assertEquals(before, editor.exportXml())
        assertFalse(editor.canUndo)
    }

    @Test
    fun aValidPropertyAppliesOnceAndRemainsOneUndoAction() {
        val editor = editor()
        val before = editor.exportXml()
        val session = session(editor)
        val edit = width(session, editor)
        edit.value = TextFieldValue("04", TextRange(2))
        assertTrue(session.applyValue(editor, session, edit))
        assertEquals(4, editor.selected?.width)
        assertNull(session.editing)
        assertFalse(session.applyValue(editor, session, edit))
        editor.undo()
        assertEquals(before, editor.exportXml())
    }

    @Test
    fun labelApplyAfterHandoffUsesTheSameSelectionAndCanBeUndone() {
        val editor = editor()
        val before = editor.exportXml()
        val session = session(editor)
        session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.INLINE))
        session.focus.focusChanged(InspectorPresentation.INLINE, true)
        session.label = TextFieldValue("Reset", TextRange(5))
        assertTrue(session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.SHEET)))
        assertTrue(session.applyLabel(editor, session))
        assertEquals("Reset", editor.selected?.label)
        assertEquals("B", editor.document.components.single { it.id == "two" }.label)
        assertFalse(session.applyLabel(editor, session))
        editor.undo()
        assertEquals(before, editor.exportXml())
    }

    @Test
    fun cancelDoesNotCommitEitherDraftOrHistory() {
        val editor = editor()
        val before = editor.exportXml()
        val session = session(editor)
        val edit = width(session, editor)
        edit.value = TextFieldValue("4")
        session.cancel(edit)
        assertNull(session.editing)
        assertEquals(before, editor.exportXml())
        assertFalse(editor.canUndo)
    }

    @Test
    fun aRetainedDialogCannotApplyOrCancelANewerDialog() {
        val editor = editor()
        val session = session(editor)
        val old = width(session, editor)
        old.value = TextFieldValue("4")
        session.cancel(old)
        val current = width(session, editor)
        current.value = TextFieldValue("8")
        assertFalse(session.applyValue(editor, session, old))
        session.cancel(old)
        assertSame(current, session.editing)
        assertEquals(1, editor.selected?.width)
    }

    @Test
    fun aRetainedLabelCannotEditAnotherSelectionOrAReplacementSession() {
        val editor = editor()
        val old = session(editor)
        old.label = TextFieldValue("WRONG")
        editor.selectComponent("two")
        val other = session(editor)
        val before = editor.exportXml()
        assertFalse(old.applyLabel(editor, other))
        assertEquals(before, editor.exportXml())
        editor.selectComponent("one")
        val replacement = session(editor)
        assertEquals(old.context, replacement.context)
        assertFalse(old.applyLabel(editor, replacement), "Returning to the same IDs does not revive a disposed session")
        assertEquals("A", editor.selected?.label)
    }

    @Test
    fun retainedDraftsRejectAnotherCircuitWithTheSameComponentIds() {
        val editor = editor(CircuitProject(listOf(circuit(), circuit("other"))))
        val old = session(editor)
        val edit = width(old, editor)
        edit.value = TextFieldValue("4")
        editor.selectCircuit("other")
        editor.selectComponent("one")
        val current = session(editor)
        val before = editor.exportXml()
        assertFalse(old.applyValue(editor, current, edit))
        assertEquals(before, editor.exportXml())
        assertNull(current.editing)
    }

    @Test
    fun projectReplacementRejectsTheRetainedDraftWithTheSameIds() {
        val editor = editor()
        val old = session(editor)
        val edit = width(old, editor)
        edit.value = TextFieldValue("4")
        assertTrue(editor.importXml(CircCodec.writeProject(CircuitProject(listOf(circuit())))))
        editor.selectComponent("one")
        val before = editor.exportXml()
        assertFalse(old.applyValue(editor, session(editor), edit))
        assertEquals(before, editor.exportXml())
    }

    @Test
    fun instanceChangesRejectTheRetainedDraft() {
        val child = circuit("child")
        val main = CircuitDocument(name = "main", id = "root", components = listOf(
            CircuitComponent("first", ComponentKind.SUBCIRCUIT, GridPoint(100, 100), circuitName = "child"),
            CircuitComponent("second", ComponentKind.SUBCIRCUIT, GridPoint(300, 100), circuitName = "child"),
        ))
        val editor = editor(CircuitProject(listOf(main, child)))
        editor.navigate(CircuitNavigationTarget(child.id, child.name, listOf("first")))
        editor.selectComponent("one")
        val old = session(editor)
        val edit = width(old, editor)
        edit.value = TextFieldValue("4")
        editor.navigate(CircuitNavigationTarget(child.id, child.name, listOf("second")))
        editor.selectComponent("one")
        val before = editor.exportXml()
        assertFalse(old.applyValue(editor, session(editor), edit))
        assertEquals(before, editor.exportXml())
    }

    @Test
    fun anotherEditorCannotUseTheSessionAndInactiveEditingCannotCommit() {
        val owner = editor()
        val session = session(owner)
        val edit = width(session, owner)
        edit.value = TextFieldValue("4")
        val other = editor()
        assertFalse(session.applyValue(other, session, edit))
        owner.setInteractionEnabled(false)
        assertFalse(session.applyValue(owner, session, edit))
        assertEquals(1, owner.selected?.width)
        assertEquals(1, other.selected?.width)
    }

    @Test
    fun openingAPropertyDoesNotTransferLabelFocusOverTheStableDialog() {
        val editor = editor()
        val session = session(editor)
        session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.INLINE))
        session.focus.focusChanged(InspectorPresentation.INLINE, true)
        width(session, editor)
        assertFalse(session.focus.commitPresentation(session.focus.presentationChange(InspectorPresentation.SHEET)))
        assertNull(session.focus.focusRequest)
        assertNotNull(session.editing)
    }

    @Test
    fun disposedSessionCannotBeRevivedThroughItsOldOwner() {
        val editor = editor()
        val session = session(editor)
        session.label = TextFieldValue("WRONG")
        val before = editor.exportXml()
        session.close()
        assertFalse(session.applyLabel(editor, session))
        session.open(PropertyEditorModel.fields(editor.componentRegistry, session.context.components).first { !it.readOnly })
        assertNull(session.editing)
        assertEquals(before, editor.exportXml())
    }
}
