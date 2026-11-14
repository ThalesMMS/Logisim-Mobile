package dev.logisim.mobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.CircCodec
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.IoKinds
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.localizedAttributeName
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W3-02 (C7, C8, C9, C14, C15, C155): the inspector's editing flow. A typed label is applied by the keyboard's Done
 * key, on focus loss, before the selection changes and when the sheet closes, and survives a move; a mixed value
 * cannot be wiped by one tap; options are radio items in one group; rows keep 48 dp targets; the docked inspector
 * keeps rows beside a large I/O panel; the tool-defaults dialog uses the inspector's pickers. Screenshots go to
 * `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class InspectorEditingFlowHostTest {
    private val en = AppLanguage.ENGLISH
    private fun text(key: String, vararg arguments: Pair<String, Any?>) = message(key, *arguments).resolve(en)
    private fun plural(key: String, count: Int) = LocalizedText(key, mapOf("count" to count.toString()), count.toLong()).resolve(en)

    private val pins = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="A"/><a name="futureattr" val="kept"/></comp>
    <comp lib="0" loc="(100,200)" name="Pin"><a name="label" val="B"/></comp>
    <comp lib="0" loc="(300,100)" name="Splitter"/>
  </circuit>
</project>"""

    private val roms = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="4" loc="(200,100)" name="ROM"><a name="addrWidth" val="4"/><a name="dataWidth" val="8"/><a name="contents">addr/data: 4 8
1 2 3
</a><a name="label" val="R1"/></comp>
    <comp lib="4" loc="(200,300)" name="ROM"><a name="addrWidth" val="4"/><a name="dataWidth" val="8"/><a name="contents">addr/data: 4 8
ff ee
</a><a name="label" val="R2"/></comp>
  </circuit>
</project>"""

    private fun EditorState.component(label: String) = document.components.first { it.label == label }

    /** The inspector with its value dialog, beside another text field that can take focus. */
    private fun ComposeUiTest.inspector(state: EditorState) {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides en) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        val session = rememberPropertyInspectorSession(state)
                        Column {
                            var other by remember { mutableStateOf("") }
                            OutlinedTextField(other, { other = it }, modifier = Modifier.fillMaxWidth().testTag("other-field"))
                            Box {
                                TypedPropertyInspector(state.componentRegistry, session.context.components, session,
                                    InspectorPresentation.SHEET, onLabel = { session.applyLabel(state, session) }, onLabelFocusChanged = {
                                        session.labelFocusChanged(state, session, InspectorPresentation.SHEET, it, null)
                                    },
                                    onOpenField = { session.open(it) },
                                    onChooseOption = { field, value -> session.applyOption(state, session, field, value) },
                                    onClearLabels = { session.clearLabels(state, session) })
                                PropertyInspectorDialog(session, onClear = { session.clearValue(state, session, it) }) {
                                    session.applyValue(state, session, it)
                                }
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    private class MemoryPreferences(preferences: EditorPreferences) : PreferencesStorage {
        private var stored: String? = EditorPreferencesCodec.write(preferences)
        override fun load() = stored
        override fun save(serialized: String) { stored = serialized }
    }

    private fun ComposeUiTest.app(state: EditorState, xml: String) {
        val header = EditorWorkspaceHeader("Project", "Saved").also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
        }
        val storage = MemoryPreferences(EditorPreferences(themeMode = EditorThemeMode.LIGHT))
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides en) {
                LogisimTheme(dark = false) {
                    App(initialXml = xml, language = en, workspaceHeader = header, preferencesStorage = storage)
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.stateOf(tag: String): String? =
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun ComposeUiTest.labelText(): String =
        onNodeWithTag("inspector-label").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    // --- C7: a mixed value is not wiped by one tap ---

    @Test
    fun mixedLabelsShowAPlaceholderAndOnlyAnExplicitClearEmptiesThem() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pins)
        state.select(state.component("A").id)
        state.select(state.component("B").id, additive = true)
        inspector(state)
        val before = state.exportXml()
        onNodeWithText(text(Messages.InspectorMixedLabels), useUnmergedTree = true).assertExists()
        onNodeWithText(text(Messages.ActionApply)).assertIsNotEnabled()
        onNodeWithTag("inspector-label").performImeAction()
        waitForIdle()
        assertEquals(before, state.exportXml(), "Done on the untouched mixed label changes nothing")
        screenshot("mixed-labels")
        // Emptying both labels takes the explicit action that names how many components it changes.
        onNodeWithTag("inspector-label-clear-all").assert(hasText(plural("inspector_clear_for_all", 2)))
            .performClick()
        waitForIdle()
        assertEquals(listOf("", ""), state.document.components.filter { it.kind == ComponentKind.INPUT }.map { it.label })
        assertEquals(1, state.undoHistory.size)
    }

    @Test
    fun mixedContentsDialogShowsDifferentValuesAndAppliesOnlyAfterAnEdit() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(roms)
        state.select(state.component("R1").id)
        state.select(state.component("R2").id, additive = true)
        inspector(state)
        val before = state.exportXml()
        onNodeWithTag("inspector-row-contents").performClick()
        waitForIdle()
        val dialog = onAllNodes(isDialog()).fetchSemanticsNodes()
        assertEquals(1, dialog.size)
        assertTrue(onAllNodes(hasText(text(Messages.InspectorMixedValues)) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty(), "The dialog says the values differ")
        val apply = onAllNodesWithText(text(Messages.ActionApply)).filterToOne(hasAnyAncestor(isDialog()))
        apply.assertIsNotEnabled()
        // An empty memory image is no value Evolution reads back, so ROM contents offer no "Clear for all".
        onNodeWithTag("inspector-value-clear-all").assertDoesNotExist()
        screenshot("mixed-contents-dialog")
        onNodeWithTag("inspector-value-field").performTextReplacement("addr/data: 4 8\n7\n")
        waitForIdle()
        apply.assertIsEnabled()
        assertEquals(before, state.exportXml())
        apply.performClick()
        waitForIdle()
        // A change to several components says how many and offers Undo beside the status line.
        assertEquals(plural("inspector_multi_edit_status", 2), state.statusText.resolve(en))
    }

    private val notes = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Base" name="1"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="1" loc="(220,160)" name="Text"><a name="text" val="first"/></comp>
    <comp lib="1" loc="(220,260)" name="Text"><a name="text" val="second"/></comp>
  </circuit>
</project>"""

    @Test
    fun mixedPlainTextIsClearedOnlyByTheExplicitAction() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(notes)
        state.document.components.forEachIndexed { index, note -> state.select(note.id, additive = index > 0) }
        inspector(state)
        onNodeWithTag("inspector-row-text").performClick()
        waitForIdle()
        onAllNodesWithText(text(Messages.ActionApply)).filterToOne(hasAnyAncestor(isDialog())).assertIsNotEnabled()
        onNodeWithTag("inspector-value-clear-all").assert(hasText(plural("inspector_clear_for_all", 2))).assertIsEnabled()
        screenshot("mixed-text-dialog")
        onNodeWithTag("inspector-value-clear-all").performClick()
        waitForIdle()
        assertTrue(onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        assertEquals(listOf("", ""), state.document.components.map { it.attributes["text"].orEmpty() })
        assertEquals(1, state.undoHistory.size)
    }

    @Test
    fun multiComponentEditOffersUndoInTheStatusLine() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pins)
        app(state, pins)
        state.select(state.component("A").id)
        state.select(state.component("B").id, additive = true)
        waitForIdle()
        val before = state.exportXml()
        onNodeWithTag("tool-properties").performClick()
        waitForIdle()
        onNodeWithTag("inspector-row-labelloc").performClick()
        waitForIdle()
        onNodeWithTag("inspector-option-labelloc-north").performClick()
        waitForIdle()
        assertEquals(listOf("north", "north"), state.document.components.filter { it.kind == ComponentKind.INPUT }.map { it.attributes["labelloc"] })
        // The open sheet covers the status line, so the Undo is in the sheet and stays there while it is open.
        assertEquals(1, onAllNodesWithTag("inspector-multi-edit-undo").fetchSemanticsNodes().size)
        assertTrue(onAllNodesWithText(plural("inspector_multi_edit_status", 2)).fetchSemanticsNodes()
            .any { it.config.getOrNull(SemanticsProperties.TestTag) != "editor-status" }, "The sheet says how many changed")
        screenshot("multi-edit-undo-in-sheet")
        mainClock.advanceTimeBy(10_000)
        waitForIdle()
        assertEquals(1, onAllNodesWithTag("inspector-multi-edit-undo").fetchSemanticsNodes().size,
            "The timeout does not run while the sheet hides the status line")
        onNodeWithTag("editor-sheet-scrim").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertTrue(onAllNodesWithTag("editor-inspector-sheet").fetchSemanticsNodes().isEmpty())
        assertEquals(plural("inspector_multi_edit_status", 2), state.statusText.resolve(en))
        onNodeWithTag("inspector-multi-edit-undo")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(plural("inspector_multi_edit_undo", 2))))
        screenshot("multi-edit-undo")
        onNodeWithTag("inspector-multi-edit-undo").performClick()
        waitForIdle()
        assertEquals(before, state.exportXml())
        assertTrue(onAllNodesWithTag("inspector-multi-edit-undo").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun undoInTheSheetWithALabelDraftPendingKeepsRedo() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pins)
        app(state, pins)
        state.select(state.component("A").id)
        state.select(state.component("B").id, additive = true)
        waitForIdle()
        val before = state.exportXml()
        onNodeWithTag("tool-properties").performClick()
        waitForIdle()
        onNodeWithTag("inspector-row-labelloc").performClick()
        waitForIdle()
        onNodeWithTag("inspector-option-labelloc-north").performClick()
        waitForIdle()
        val turned = state.exportXml()
        onNodeWithTag("inspector-label").performTextReplacement("X")
        waitForIdle()
        // Activated without moving the focus, as a tap on a touch screen or TalkBack's double tap does: the field keeps
        // its draft while the Undo runs.
        onNodeWithTag("inspector-multi-edit-undo").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        screenshot("undo-with-label-draft")
        assertEquals(before, state.exportXml(), "The draft typed before Undo is not applied after it")
        assertTrue(state.canRedo, "Redo of the label location survives")
        assertEquals(text("inspector_label_draft_discarded", "label" to "X"), state.statusText.resolve(en))
        state.redo()
        assertEquals(turned, state.exportXml())
    }

    @Test
    fun openingAnEditorToolFromTheSheetAppliesTheTypedLabel() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(roms)
        app(state, roms)
        state.select(state.component("R1").id)
        waitForIdle()
        onNodeWithTag("tool-properties").performClick()
        waitForIdle()
        onNodeWithTag("inspector-label").performTextReplacement("R9")
        // A tap that leaves the focus in the label field, as on a touch screen or with TalkBack.
        onNodeWithTag("inspector-row-contents").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        screenshot("open-tool-with-label-draft")
        assertTrue(onAllNodesWithTag("editor-inspector-sheet").fetchSemanticsNodes().isEmpty(), "The tool replaces the sheet")
        assertEquals(listOf("R9", "R2"), state.document.components.map { it.label },
            "The label typed in the sheet is applied when the tool opens, as when the sheet closes")
        assertEquals(1, state.undoHistory.size)
    }

    // --- C9, C155: a typed label is never silently discarded ---

    @Test
    fun keyboardDoneAppliesTheLabelAsOneUndoableEdit() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pins)
        state.select(state.component("A").id)
        inspector(state)
        onNodeWithTag("inspector-label").performTextReplacement("Q1")
        onNodeWithTag("inspector-label").performImeAction()
        waitForIdle()
        assertEquals("Q1", state.selected?.label)
        assertEquals(1, state.undoHistory.size)
    }

    @Test
    fun focusLossAppliesTheTypedLabel() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pins)
        state.select(state.component("A").id)
        inspector(state)
        onNodeWithTag("inspector-label").performClick()
        onNodeWithTag("inspector-label").performTextReplacement("Q2")
        onNodeWithTag("other-field").performClick()
        waitForIdle()
        assertEquals("Q2", state.selected?.label)
        assertEquals(1, state.undoHistory.size)
    }

    @Test
    fun focusLossAfterTheSessionIsRecreatedStillAppliesTheLabel() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pins)
        state.select(state.component("A").id)
        inspector(state)
        onNodeWithTag("inspector-label").performClick()
        waitForIdle()
        // Moving the pin recreates the session while the same field keeps the focus.
        state.moveSelected(GridPoint(10, 0))
        waitForIdle()
        onNodeWithTag("inspector-label").performTextReplacement("Q6")
        waitForIdle()
        onNodeWithTag("other-field").performClick()
        waitForIdle()
        assertEquals("Q6", state.selected?.label)
    }

    @Test
    fun movingTheComponentKeepsTheLabelDraft() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pins)
        state.select(state.component("A").id)
        inspector(state)
        onNodeWithTag("inspector-label").performTextReplacement("Q3")
        state.moveSelected(GridPoint(10, 0))
        waitForIdle()
        assertTrue(labelText() == "Q3" || state.selected?.label == "Q3", "The draft survives the move or has been applied")
    }

    @Test
    fun selectingAnotherComponentAppliesTheDraftToTheOneItWasTypedFor() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pins)
        state.select(state.component("A").id)
        inspector(state)
        val first = state.selected!!.id
        onNodeWithTag("inspector-label").performTextReplacement("Q4")
        state.select(state.component("B").id)
        waitForIdle()
        assertEquals("Q4", state.document.components.single { it.id == first }.label)
        assertEquals("B", state.selected?.label)
        assertEquals("B", labelText())
    }

    @Test
    fun closingTheSheetAppliesTheTypedLabel() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pins)
        app(state, pins)
        state.select(state.component("A").id)
        waitForIdle()
        onNodeWithTag("tool-properties").performClick()
        waitForIdle()
        onNodeWithTag("inspector-label").performTextReplacement("Q5")
        waitForIdle()
        onNodeWithTag("editor-sheet-scrim").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertTrue(onAllNodesWithTag("editor-inspector-sheet").fetchSemanticsNodes().isEmpty())
        assertEquals("Q5", state.selected?.label)
        assertEquals(1, state.undoHistory.size)
    }

    @Test
    fun aSplitterHasNoLabelFieldAloneOrInAMixedSelection() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pins)
        val splitter = state.document.components.single { it.kind == ComponentKind.SPLITTER }
        state.select(splitter.id)
        inspector(state)
        assertTrue(onAllNodesWithTag("inspector-label").fetchSemanticsNodes().isEmpty())
        state.select(state.component("A").id, additive = true)
        waitForIdle()
        assertTrue(onAllNodesWithTag("inspector-label").fetchSemanticsNodes().isEmpty(),
            "A label typed for a pin and a splitter would be lost on the splitter in Evolution")
        screenshot("mixed-pin-splitter")
    }

    // --- C14, C15: semantics and targets ---

    @Test
    fun rowsAnnounceRoleAndValueAndOptionsAreRadioItemsInOneGroup() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pins)
        state.select(state.component("A").id)
        var typography: Typography? = null
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides en) {
                LogisimTheme(dark = false) {
                    typography = MaterialTheme.typography
                    Surface(Modifier.fillMaxSize()) {
                        val session = rememberPropertyInspectorSession(state)
                        Box {
                            TypedPropertyInspector(state.componentRegistry, session.context.components, session,
                                InspectorPresentation.SHEET, onLabel = {}, onLabelFocusChanged = {},
                                onOpenField = { session.open(it) },
                                onChooseOption = { field, value -> session.applyOption(state, session, field, value) })
                            PropertyInspectorDialog(session) { session.applyValue(state, session, it) }
                        }
                    }
                }
            }
        }
        waitForIdle()
        fun role(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)
        assertEquals(Role.DropdownList, role("inspector-row-labelloc"))
        assertEquals(Role.Button, role("inspector-row-width"))
        assertEquals("West", stateOf("inspector-row-labelloc"))
        // A read-only row is one stop that says its value.
        assertEquals("kept", stateOf("inspector-row-futureattr"))
        // Every target in the inspector is at least 48 dp tall.
        val minimum = with(density) { 48.dp.toPx() } - 0.5f
        val small = onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.boundsInRoot.height < minimum }
        assertEquals(emptyList(), small.map { it.config.getOrNull(SemanticsProperties.TestTag) ?: it.config.toString() })
        // Names and values follow the type scale.
        val name = onNode(hasText(text("attribute_label_labelloc")), useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(typography!!.bodyMedium.fontSize, layout(name).layoutInput.style.fontSize)

        onNodeWithTag("inspector-row-labelloc").performClick()
        waitForIdle()
        val radios = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(5, radios.size, "One radio stop per option, with no extra unlabeled radio")
        val group = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
        listOf("north", "south", "east", "west", "center").forEach {
            onNodeWithTag("inspector-option-labelloc-$it").assert(hasAnyAncestor(group))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, it == "west"))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
        screenshot("labelloc-radio-menu")
    }

    @Test
    fun theValueDialogsChoicesAreSingleRadioStops() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pins)
        state.select(state.component("A").id)
        lateinit var session: PropertyInspectorSession
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides en) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        session = rememberPropertyInspectorSession(state)
                        PropertyInspectorDialog(session) { session.applyValue(state, session, it) }
                    }
                }
            }
        }
        waitForIdle()
        session.open(PropertyEditorModel.fields(state.componentRegistry, session.context.components).single { it.name == "labelloc" })
        waitForIdle()
        val radios = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(5, radios.size)
        assertTrue(radios.all { it.config.getOrNull(SemanticsProperties.Selected) != null })
        assertTrue(radios.all { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().isNotEmpty() ||
            node.children.any { child -> child.config.getOrNull(SemanticsProperties.Text).orEmpty().isNotEmpty() } },
            "Each radio is read with its option name")
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).fetchSemanticsNodes().single()
        screenshot("value-dialog-radios")
    }

    @Test
    fun longValuesEndWithAnEllipsis() = runDesktopComposeUiTest(390, 844) {
        val long = "A very long annotation text that does not fit on two lines of the narrow value column of the table " +
            "and keeps going"
        val state = EditorState("""<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Base" name="6"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="6" loc="(200,100)" name="Text"><a name="text" val="$long"/></comp>
  </circuit>
</project>""")
        state.select(state.document.components.single().id)
        inspector(state)
        val value = onNode(hasText(long, substring = true) and hasAnyAncestor(hasTestTag("inspector-row-text")), useUnmergedTree = true)
            .fetchSemanticsNode()
        val result = layout(value)
        assertTrue(result.lineCount <= 2)
        assertTrue(result.hasVisualOverflow && result.isLineEllipsized(result.lineCount - 1))
    }

    private fun layout(node: SemanticsNode): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    @Test
    fun theDockedInspectorKeepsAttributeRowsBesideAMatrixKeypad() = runDesktopComposeUiTest(1024, 768) {
        val keypad = CircuitComponent("keypad", IoKinds.MatrixKeypad, GridPoint(200, 200))
        val xml = CircCodec.writeProject(CircuitProject(listOf(CircuitDocument(name = "main", id = "main", components = listOf(keypad)))))
        val state = EditorState(xml)
        app(state, xml)
        state.select("keypad")
        waitForIdle()
        val inline = onNodeWithTag("editor-inspector-inline").fetchSemanticsNode().boundsInRoot
        val row = with(density) { 47.dp.toPx() }
        val visible = onAllNodes(SemanticsMatcher("inspector row") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("inspector-row-") == true
        }).fetchSemanticsNodes().filter { node ->
            val bounds = node.boundsInRoot
            bounds.height >= row && bounds.top >= inline.top && bounds.bottom <= inline.bottom + 1
        }
        screenshot("tablet-1024-matrix-keypad")
        assertTrue(visible.size >= 3, "Visible attribute rows: ${visible.size} in ${inline.height} px")
    }

    @Test
    fun theDockedLibraryKeepsItsCategoriesBesideAGateInspector() = runDesktopComposeUiTest(1024, 768) {
        val gate = CircuitComponent("gate", ComponentKind.AND, GridPoint(200, 200))
        val xml = CircCodec.writeProject(CircuitProject(listOf(CircuitDocument(name = "main", id = "main", components = listOf(gate)))))
        val state = EditorState(xml)
        app(state, xml)
        state.select("gate")
        waitForIdle()
        val list = onNodeWithTag("library-list").fetchSemanticsNode().boundsInRoot
        val inline = onNodeWithTag("editor-inspector-inline").fetchSemanticsNode().boundsInRoot
        val row = with(density) { 47.dp.toPx() }
        // Manage libraries and at least two 48 dp rows (categories or the selected component's group) stay in view.
        val libraryRows = list.height / row
        val rows = onAllNodes(SemanticsMatcher("inspector row") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("inspector-row-") == true
        }).fetchSemanticsNodes().count { it.boundsInRoot.height >= row && it.boundsInRoot.top >= inline.top && it.boundsInRoot.bottom <= inline.bottom + 1 }
        screenshot("tablet-1024-and-gate-library")
        println("docked split 1024x768: library list=$list libraryRows=$libraryRows inspector=$inline rows=$rows")
        assertTrue(libraryRows >= 3f, "Library rows shown beside the inspector: $libraryRows in $list")
        assertTrue(rows >= 3, "Visible attribute rows: $rows in $inline")
    }

    // --- C8: the project tool defaults use the inspector's pickers ---

    private fun ComposeUiTest.defaults(prototype: CircuitComponent, onApply: (Map<String, String>) -> Unit = {}) {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides en) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        ProjectToolDefaultsDialog(prototype, dev.logisim.mobile.core.ComponentCatalog.registry, onApply, onDismiss = {})
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.defaultRows(): List<String> = onAllNodes(SemanticsMatcher("tool default") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("project-tool-default-") == true
    }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }

    @Test
    fun andGateDefaultsArePickedNotTyped() = runDesktopComposeUiTest(390, 844) {
        var applied: Map<String, String>? = null
        defaults(CircuitComponent("p", ComponentKind.AND, GridPoint(0, 0))) { applied = it }
        val rows = defaultRows()
        assertTrue(rows.size <= 10, "Rows: $rows")
        assertTrue(onAllNodesWithText("negate10", substring = true).fetchSemanticsNodes().isEmpty())
        screenshot("tool-defaults-and")
        // An option row is one TalkBack stop that says its name and value: no read-only text field stop beside it.
        val inFacing = hasAnyAncestor(hasTestTag("project-tool-default-facing"))
        val dropdowns = onAllNodes(inFacing and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList))
            .fetchSemanticsNodes()
        assertEquals(1, dropdowns.size, "one drop-down stop for Facing")
        assertEquals(listOf(localizedAttributeName("facing").resolve(en)), dropdowns.single().config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(text(Messages.DirectionEast), dropdowns.single().config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue(onAllNodes(inFacing and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .fetchSemanticsNodes().isEmpty(), "no read-only text field stop for Facing")
        onNodeWithTag("project-tool-default-facing").performClick()
        waitForIdle()
        val group = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
        onNodeWithTag("option-facing-north").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(hasAnyAncestor(group)).performClick()
        waitForIdle()
        onNodeWithTag("project-tool-default-size").performClick()
        waitForIdle()
        onNodeWithTag("option-size-70").performClick()
        waitForIdle()
        onNodeWithText(text(Messages.ActionApply)).performClick()
        waitForIdle()
        assertEquals(mapOf("facing" to "north", "size" to "70"), applied)
    }

    @Test
    fun narrowPhoneShowsTheDefaultsAndTheMixedDialogWhole() = runDesktopComposeUiTest(360, 780) {
        defaults(CircuitComponent("p", ComponentKind.AND, GridPoint(0, 0)))
        val width = with(density) { 360.dp.toPx() }
        val rows = onAllNodes(SemanticsMatcher("tool default") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("project-tool-default-") == true
        }).fetchSemanticsNodes()
        assertTrue(rows.all { it.boundsInRoot.left >= 0f && it.boundsInRoot.right <= width })
        screenshot("tool-defaults-and-360")
    }

    @Test
    fun narrowPhoneMixedDialog() = runDesktopComposeUiTest(360, 780) {
        val state = EditorState(roms)
        state.select(state.component("R1").id)
        state.select(state.component("R2").id, additive = true)
        inspector(state)
        onNodeWithTag("inspector-row-contents").performClick()
        waitForIdle()
        onNodeWithTag("inspector-value-clear-all").assertDoesNotExist()
        screenshot("mixed-contents-dialog-360")
    }

    @Test
    fun toolsWithoutALabelOfferNoLabelDefault() = runDesktopComposeUiTest(390, 844) {
        defaults(CircuitComponent("p", ComponentKind.SPLITTER, GridPoint(0, 0)))
        assertTrue("project-tool-default-label" !in defaultRows(), "Rows: ${defaultRows()}")
    }

    @Test
    fun subcircuitLabelVisibilityIsASwitchAndItsFontUsesTheChooser() = runDesktopComposeUiTest(390, 844) {
        var applied: Map<String, String>? = null
        defaults(CircuitComponent("p", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), circuitName = "half")) { applied = it }
        onNodeWithTag("project-tool-default-labelvisible")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        onNodeWithTag("project-tool-default-labelfont").performClick()
        waitForIdle()
        onNodeWithTag("project-tool-default-labelfont-style-b").performScrollTo().performClick() // bold off: plain
        waitForIdle()
        screenshot("tool-defaults-subcircuit-font")
        onNodeWithTag("project-tool-default-labelvisible").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText(text(Messages.ActionApply)).performClick()
        waitForIdle()
        assertEquals(mapOf("labelfont" to "SansSerif plain 16", "labelvisible" to "false"), applied)
    }

    @Test
    fun ledColorsArePickedFromSwatches() = runDesktopComposeUiTest(390, 844) {
        var applied: Map<String, String>? = null
        val led = CircuitComponent("p", IoKinds.Led, GridPoint(0, 0))
        defaults(led) { applied = it }
        // The color row is one TalkBack stop, a button that says the field's name and its value, not an edit box
        // followed by a second unnamed button.
        val color = ProjectToolDefaults(led, dev.logisim.mobile.core.ComponentCatalog.registry).fields(emptyMap()).single { it.name == "color" }
        val name = color.label.resolve(en)
        onNodeWithTag("project-tool-default-color")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(name)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, color.localizedValue().resolve(en)))
        assertTrue(onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and hasText(name), useUnmergedTree = true)
            .fetchSemanticsNodes().isEmpty(), "The read-only color field is not a second stop")
        onNodeWithTag("project-tool-default-color").performClick()
        waitForIdle()
        onNodeWithTag("color-swatch-#00f000").performClick()
        waitForIdle()
        screenshot("tool-defaults-led-color")
        onNodeWithText(text(Messages.ActionApply)).performClick()
        waitForIdle()
        assertEquals("#00f000", applied?.get("color"))
    }
}
