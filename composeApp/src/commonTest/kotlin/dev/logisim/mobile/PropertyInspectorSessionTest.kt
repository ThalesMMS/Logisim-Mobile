package dev.logisim.mobile

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.sharedVhdlDefinitions
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

    // --- W3-02 (C7, C9): a mixed value is not wiped by one tap, and labels exist only where Evolution has them ---

    private fun both(xml: String, vararg labels: String): EditorState = EditorState(xml).also { editor ->
        labels.forEachIndexed { index, label -> editor.select(editor.document.components.first { it.label == label }.id, additive = index > 0) }
    }

    private val twoPins = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="A"/></comp>
    <comp lib="0" loc="(100,200)" name="Pin"><a name="label" val="B"/></comp>
    <comp lib="0" loc="(300,100)" name="Splitter"/>
  </circuit>
</project>"""

    @Test
    fun mixedLabelApplyWithoutTypingChangesNothing() {
        val editor = both(twoPins, "A", "B")
        val session = session(editor)
        val before = editor.exportXml()
        assertEquals("", session.label.text)
        assertFalse(session.labelChanged)
        session.applyLabel(editor, session)
        assertEquals(listOf("A", "B"), editor.document.components.filter { it.kind == ComponentKind.INPUT }.map { it.label })
        assertEquals(before, editor.exportXml())
        assertFalse(editor.canUndo)
    }

    private val twoRoms: String = run {
        val rom = { label: String, contents: String ->
            """<comp lib="4" loc="(200,${if (label == "R1") 100 else 300})" name="ROM"><a name="addrWidth" val="4"/><a name="dataWidth" val="8"/>""" +
                """<a name="contents">addr/data: 4 8
$contents
</a><a name="label" val="$label"/></comp>"""
        }
        """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">${rom("R1", "1 2 3")}${rom("R2", "ff ee")}</circuit>
</project>"""
    }

    @Test
    fun mixedContentsDialogCannotApplyUntilEdited() {
        val editor = both(twoRoms, "R1", "R2")
        val session = session(editor)
        val before = editor.exportXml()
        val field = PropertyEditorModel.fields(editor.componentRegistry, session.context.components).single { it.name == "contents" }
        assertTrue(field.mixed)
        session.open(field)
        val edit = assertNotNull(session.editing)
        assertEquals("", edit.value.text)
        assertFalse(edit.changed)
        assertFalse(session.applyValue(editor, session, edit), "The untouched empty field would erase both ROMs")
        assertEquals(before, editor.exportXml())
        edit.value = TextFieldValue("addr/data: 4 8\n7\n")
        assertTrue(session.applyValue(editor, session, edit))
        assertEquals(2, editor.document.components.count { it.attributes["contents"]?.contains("\n7") == true })
    }

    @Test
    fun mixedSharedVhdlSourceIsUnchangedUntilEdited() {
        fun entity(name: String, body: String) = """<vhdl name="$name"><![CDATA[library ieee;
use ieee.std_logic_1164.all;
entity $name is port(a : in std_logic; y : out std_logic); end entity;
architecture rtl of $name is begin $body end architecture;]]></vhdl>"""
        val editor = both("""<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <main name="main"/>
  ${entity("inv", "y <= not a;")}
  ${entity("buf", "y <= a;")}
  <circuit name="main">
    <comp name="inv" loc="(300,100)"><a name="label" val="first"/></comp>
    <comp name="buf" loc="(300,200)"><a name="label" val="second"/></comp>
  </circuit>
</project>""", "first", "second")
        val session = session(editor)
        val sources = editor.project.sharedVhdlDefinitions().associate { it.name to it.source }
        val before = editor.exportXml()
        val field = PropertyEditorModel.fields(editor.componentRegistry, session.context.components).single { it.name == "content" }
        assertTrue(field.mixed)
        session.open(field)
        val edit = assertNotNull(session.editing)
        assertFalse(session.applyValue(editor, session, edit))
        // Emptying a project-wide shared source is never offered: Evolution refuses it for every instance.
        assertFalse(session.canClearForAll(edit))
        assertFalse(session.clearValue(editor, session, edit))
        assertEquals(sources, editor.project.sharedVhdlDefinitions().associate { it.name to it.source })
        assertEquals(before, editor.exportXml())
    }

    @Test
    fun aSplitterIsWrittenWithoutALabelAttribute() {
        val editor = EditorState(twoPins)
        val xml = CircCodec.writeProject(editor.project, editor.componentRegistry)
        val splitter = Regex("<comp[^>]*name=\"Splitter\"[^>]*>(.*?)</comp>|<comp[^>]*name=\"Splitter\"[^>]*/>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.value
        assertNotNull(splitter)
        assertFalse("name=\"label\"" in splitter, splitter)
        val session = EditorState(twoPins).let { pins ->
            pins.select(pins.document.components.single { it.kind == ComponentKind.SPLITTER }.id)
            pins.select(pins.document.components.first { it.label == "A" }.id, additive = true)
            session(pins)
        }
        assertFalse(session.labelled, "A selection with a splitter has no label field")
    }

    @Test
    fun labelDraftSurvivesAMoveOfTheSelectedComponent() {
        val editor = editor()
        val old = session(editor)
        old.label = TextFieldValue("X", TextRange(1))
        editor.moveSelected(GridPoint(10, 0))
        val moved = session(editor).also { it.adoptDraft(old) }
        assertEquals(TextFieldValue("X", TextRange(1)), moved.label)
        old.retire()
        assertEquals("A", editor.selected?.label, "The draft moved to the new session instead of being applied twice")
        assertTrue(moved.applyLabel(editor, moved))
        assertEquals("X", editor.selected?.label)
    }

    @Test
    fun commitOnDismissAppliesTheTypedLabelAsOneUndoableEdit() {
        val editor = editor()
        val before = editor.exportXml()
        val session = session(editor)
        session.label = TextFieldValue("A")
        assertFalse(session.commitLabelDraft(), "An unchanged label is not applied")
        session.label = TextFieldValue("A1")
        assertTrue(session.commitLabelDraft())
        assertEquals("A1", editor.selected?.label)
        assertEquals(1, editor.undoHistory.size)
        editor.undo()
        assertEquals(before, editor.exportXml())
    }

    @Test
    fun aDraftIsAppliedToItsComponentsWhenTheSelectionChanges() {
        val editor = editor()
        val old = session(editor)
        old.label = TextFieldValue("Q")
        editor.selectComponent("two")
        val other = session(editor).also { it.adoptDraft(old) }
        assertEquals("B", other.label.text)
        old.retire()
        assertEquals("Q", editor.document.components.single { it.id == "one" }.label)
        assertEquals("B", editor.selected?.label)
        assertEquals(setOf("two"), editor.selectedIds)
        assertFalse(old.commitLabelDraft(), "A retired session applies nothing more")
    }

    @Test
    fun aDraftIsNotAppliedInAnotherDocument() {
        val editor = editor()
        val old = session(editor)
        old.label = TextFieldValue("Q")
        assertTrue(editor.importXml(CircCodec.writeProject(CircuitProject(listOf(circuit())))))
        val before = editor.exportXml()
        old.retire()
        assertEquals(before, editor.exportXml())
        // It is not lost silently: the status line says the typed label was not applied.
        assertEquals("inspector_label_draft_discarded", editor.statusText.key)
        assertEquals("Q", editor.statusText.arguments["label"])
    }

    @Test
    fun clearForAllEmptiesEveryLabelAndOffersOneUndo() {
        val editor = both(twoPins, "A", "B")
        val notice = MultiEditUndoNotice()
        val session = PropertyInspectorSession(editor, editor.propertyInspectorContext(), undoNotice = notice)
        assertTrue(session.mixedLabels)
        assertTrue(session.clearLabels(editor, session))
        assertEquals(listOf("", ""), editor.document.components.filter { it.kind == ComponentKind.INPUT }.map { it.label })
        assertEquals(1, editor.undoHistory.size)
        assertEquals(2, notice.undoableCount(editor))
        assertEquals("inspector_multi_edit_status", editor.statusText.key)
        assertTrue(notice.undo(editor))
        assertEquals(listOf("A", "B"), editor.document.components.filter { it.kind == ComponentKind.INPUT }.map { it.label })
        assertNull(notice.undoableCount(editor))
    }

    @Test
    fun romContentsAreNeverClearedForAll() {
        // Evolution's Rom.ContentsAttribute.parse("") gives null and the ROM then fails to load: an empty memory
        // image is not a value Evolution reads back, so "Clear for all" is not offered for it.
        val editor = both(twoRoms, "R1", "R2")
        val session = session(editor)
        session.open(PropertyEditorModel.fields(editor.componentRegistry, session.context.components).single { it.name == "contents" })
        val edit = assertNotNull(session.editing)
        assertFalse(session.canClearForAll(edit))
        assertNull(session.clearForAllCount(edit))
        assertFalse(session.clearValue(editor, session, edit))
        assertSame(edit, session.editing, "A refused clear keeps the dialog open")
        val xml = editor.exportXml()
        val contents = Regex("""<a name="contents">([^<]*)</a>""").findAll(xml).map { it.groupValues[1].trim() }.toList()
        assertEquals(2, contents.size, xml)
        assertTrue(contents.all { it.startsWith("addr/data:") }, "$contents")
        assertFalse(editor.canUndo)
        // One component has no differing value to clear.
        val other = editor()
        val single = session(other)
        single.open(PropertyEditorModel.fields(other.componentRegistry, single.context.components).first { !it.readOnly })
        assertFalse(single.canClearForAll(assertNotNull(single.editing)))
    }

    private val twoNotes = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Base" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="1" loc="(220,160)" name="Text"><a name="text" val="first"/></comp>
    <comp lib="1" loc="(220,260)" name="Text"><a name="text" val="second"/></comp>
  </circuit>
</project>"""

    @Test
    fun clearForAllEmptiesMixedPlainTextOnRequest() {
        val editor = EditorState(twoNotes)
        editor.document.components.forEachIndexed { index, note -> editor.select(note.id, additive = index > 0) }
        val session = session(editor)
        val field = PropertyEditorModel.fields(editor.componentRegistry, session.context.components).single { it.name == "text" }
        assertEquals(PropertyInputKind.TEXT, field.kind)
        session.open(field)
        val edit = assertNotNull(session.editing)
        assertEquals(2, session.clearForAllCount(edit))
        assertTrue(session.clearValue(editor, session, edit))
        assertNull(session.editing)
        assertEquals(listOf("", ""), editor.document.components.map { it.attributes["text"].orEmpty() })
        assertEquals(1, editor.undoHistory.size)
        // The cleared text is read back as empty text.
        val reread = EditorState(editor.exportXml())
        assertEquals(listOf("", ""), reread.document.components.map { it.attributes["text"].orEmpty() })
    }

    private val threePins = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="facing" val="west"/><a name="label" val="A"/></comp>
    <comp lib="0" loc="(100,200)" name="Pin"><a name="label" val="B"/></comp>
    <comp lib="0" loc="(100,300)" name="Pin"/>
  </circuit>
</project>"""

    private fun allPins(): EditorState = EditorState(threePins).also { editor ->
        editor.document.components.forEachIndexed { index, pin -> editor.select(pin.id, additive = index > 0) }
    }

    @Test
    fun multiEditCountsOnlyTheComponentsThatChanged() {
        val editor = allPins()
        val notice = MultiEditUndoNotice()
        val session = PropertyInspectorSession(editor, editor.propertyInspectorContext(), undoNotice = notice)
        val facing = PropertyEditorModel.fields(editor.componentRegistry, session.context.components).single { it.name == "facing" }
        assertTrue(session.applyOption(editor, session, facing, "west"))
        assertEquals(listOf("west", "west", "west"), editor.document.components.map { it.attributes["facing"] })
        assertEquals(2, notice.undoableCount(editor), "One pin already faced west")
        assertEquals(MultiEditUndoNotice.status(2), editor.statusText)
        // "Clear for all" counts the labels it empties, not the pin whose label is already empty.
        val again = PropertyInspectorSession(editor, editor.propertyInspectorContext(), undoNotice = notice)
        assertEquals(2, again.labelClearCount)
        assertTrue(again.clearLabels(editor, again))
        assertEquals(2, notice.undoableCount(editor))
        assertEquals(MultiEditUndoNotice.status(2), editor.statusText)
    }

    @Test
    fun switchingCircuitAppliesTheDraftBeforeLeaving() {
        val editor = editor(CircuitProject(listOf(circuit(), CircuitDocument(name = "sub"))))
        val session = session(editor)
        val unbind = session.bindPendingCommit()
        assertNotNull(editor.commitPendingEdit)
        session.label = TextFieldValue("Q")
        // The docked inspector keeps the focus in the field while the Circuits tab switches the circuit.
        editor.selectCircuit("sub")
        assertEquals("sub", editor.activeCircuitName)
        assertEquals("Q", editor.project.circuit("main")?.components?.single { it.id == "one" }?.label)
        assertEquals(1, editor.undoHistory.size)
        // Retiring afterwards neither applies it again nor reports it as discarded.
        val status = editor.statusText
        unbind()
        session.retire()
        assertEquals(status, editor.statusText)
        assertNull(editor.commitPendingEdit)
        editor.selectCircuit("main")
        assertEquals("Q", editor.document.components.single { it.id == "one" }.label)
    }

    @Test
    fun aSingleComponentEditOffersNoMultiEditUndo() {
        val editor = editor()
        val notice = MultiEditUndoNotice()
        val session = PropertyInspectorSession(editor, editor.propertyInspectorContext(), undoNotice = notice)
        session.label = TextFieldValue("Z")
        assertTrue(session.applyLabel(editor, session))
        assertNull(notice.undoableCount(editor))
    }

    @Test
    fun undoWithALabelDraftPendingDropsTheDraftAndKeepsRedo() {
        // Multi-select two gates, choose a facing, type a label, then Undo (the in-sheet banner or the toolbar).
        val editor = editor()
        editor.select("two", additive = true)
        val before = editor.exportXml()
        val session = session(editor)
        val facing = PropertyEditorModel.fields(editor.componentRegistry, session.context.components).single { it.name == "facing" }
        assertTrue(session.applyOption(editor, session, facing, "north"))
        val turned = editor.exportXml()
        val current = session(editor)
        current.adoptDraft(session)
        current.label = TextFieldValue("X")
        editor.undo()
        // The selection change disposes the inspector, which retires its session.
        current.retire()
        assertEquals(before, editor.exportXml(), "the draft is not applied after the undo")
        assertEquals(listOf("A", "B"), editor.document.components.map { it.label })
        assertTrue(editor.canRedo, "Redo of the facing change survives")
        assertEquals("inspector_label_draft_discarded", editor.statusText.key)
        editor.redo()
        assertEquals(turned, editor.exportXml())
    }

    @Test
    fun aSessionOpenedAfterAnUndoStillAppliesItsLabel() {
        val editor = editor()
        val session = session(editor)
        session.label = TextFieldValue("Q")
        assertTrue(session.applyLabel(editor, session))
        editor.undo()
        editor.selectComponent("one")
        val next = session(editor)
        next.label = TextFieldValue("R")
        assertTrue(next.commitLabelDraft())
        assertEquals("R", editor.document.components.first { it.id == "one" }.label)
    }
}
