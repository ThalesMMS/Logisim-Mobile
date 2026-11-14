package dev.logisim.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.toSize
import dev.logisim.mobile.core.SymbolAppearance
import dev.logisim.mobile.core.SymbolObject
import dev.logisim.mobile.core.SymbolVisuals
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.localization.LocalAppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The appearance editor edits the draft live (C99): a toggle or a valid field changes the preview at once, without an
 * "Update object" button, and undo restores a field's edit in one step. Objects are listed by readable names (C101), and
 * Apply keeps the draft when the editor did not take the change (R04-1). Screenshots go to `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class AppearanceLiveEditsHostTest {
    private var ui: ComposeUiTest? = null

    private fun ComposeUiTest.host(language: AppLanguage = AppLanguage.ENGLISH, content: @Composable () -> Unit) {
        ui = this
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
        waitForIdle()
    }

    /** Scrolls every enclosing scroll container until the node is centered in it. */
    private fun SemanticsNodeInteraction.reveal(): SemanticsNodeInteraction {
        repeat(3) {
            val target = fetchSemanticsNode()
            var parent = target.parent
            while (parent != null) {
                val scroll = parent.config.getOrNull(SemanticsActions.ScrollBy)
                if (scroll != null) {
                    val viewport = androidx.compose.ui.geometry.Rect(parent.positionInRoot, parent.size.toSize())
                    val bounds = androidx.compose.ui.geometry.Rect(fetchSemanticsNode().positionInRoot, target.size.toSize())
                    val vertical = parent.config.contains(SemanticsProperties.VerticalScrollAxisRange)
                    val delta = if (vertical) bounds.center.y - viewport.center.y else bounds.center.x - viewport.center.x
                    if (kotlin.math.abs(delta) > 1f) ui?.runOnIdle { scroll.action?.invoke(if (vertical) 0f else delta, if (vertical) delta else 0f) }
                    ui?.waitForIdle()
                }
                parent = parent.parent
            }
        }
        return this
    }

    private fun toolContext(state: EditorState, notices: MutableList<LocalizedText>? = null, apply: Boolean = true) =
        EditorToolContext(state.project, state.document, state.simulation,
            state.document.components.filter { it.id in state.selectedIds }, { if (apply) state.applyProject(it) }, { state.applyDocument(it) },
            state::selectCircuit, { state.selectComponent(it) }, {}, documentIdentity = state.documentIdentity,
            showNotice = { notices?.add(it) })

    private fun AppearanceDraftHolder.shape(id: String) =
        checkNotNull(draft).objects.filterIsInstance<SymbolObject.Shape>().single { it.id == id }

    @Test
    fun fillAppliesToTheDraftAtOnceAndUndoesInOneStep() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        val drafts = AppearanceDraftHolder()
        val tool = appearanceEditorTool(null, drafts)
        host { Column { tool.content(toolContext(state)) } }
        onNodeWithText("+ Rectangle").reveal().performClick()
        waitForIdle()
        val id = drafts.selectedShapes.single()
        assertEquals("none", drafts.shape(id).attributes["fill"])
        val scene = SymbolVisuals.symbol(checkNotNull(drafts.draft), circuit = state.document)

        onNodeWithTag("appearance-object-fill-enabled").reveal().performClick()
        waitForIdle()
        screenshot("appearance-live-fill")
        // No "Update object": the toggle changed the draft, so the preview scene changed with it.
        assertNotEquals("none", drafts.shape(id).attributes["fill"])
        assertNotEquals(scene, SymbolVisuals.symbol(checkNotNull(drafts.draft), circuit = state.document))
        assertTrue(onAllNodesWithTag("appearance-update-object").fetchSemanticsNodes().isEmpty(), "Update object is gone")
        // The preview stays in view while the properties are edited.
        onNodeWithTag("appearance-preview").assertIsDisplayed()
        onNodeWithTag("appearance-object-fill-enabled").assertIsDisplayed()

        onNodeWithTag("appearance-undo-draft").reveal().performClick()
        waitForIdle()
        assertEquals("none", drafts.shape(id).attributes["fill"])
    }

    @Test
    fun typingACoordinateKeepsTheFieldAndUndoesTheWholeFieldEdit() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        val drafts = AppearanceDraftHolder()
        val tool = appearanceEditorTool(null, drafts)
        host { Column { tool.content(toolContext(state)) } }
        onNodeWithText("+ Rectangle").reveal().performClick()
        waitForIdle()
        val id = drafts.selectedShapes.single()
        val before = drafts.history.size
        val field = onNodeWithTag("appearance-object-x").reveal()
        field.performClick()
        field.performTextClearance()
        waitForIdle()
        // An empty or partial number marks the field and leaves the draft as it was.
        assertEquals("0", drafts.shape(id).attributes["x"])
        field.performTextInput("-")
        waitForIdle()
        assertEquals("0", drafts.shape(id).attributes["x"])
        field.performTextInput("2")
        field.performTextInput("5")
        waitForIdle()
        screenshot("appearance-live-coordinate")
        // Every keystroke landed in the field (no reset of the text or cursor) and the valid value reached the draft.
        assertEquals("-25", field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text)
        assertEquals("-25", drafts.shape(id).attributes["x"])
        assertEquals(before + 1, drafts.history.size, "One undo step for the field edit")
        onNodeWithTag("appearance-undo-draft").reveal().performClick()
        waitForIdle()
        assertEquals("0", drafts.shape(id).attributes["x"])
    }

    @Test
    fun objectsAndPortsAreListedByName() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState("""<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(20,30)" name="Pin"><a name="type" val="input"/></comp>
    <comp lib="0" loc="(100,30)" name="Pin"><a name="type" val="output"/><a name="width" val="8"/><a name="facing" val="west"/></comp>
  </circuit>
</project>""")
        val drafts = AppearanceDraftHolder()
        val tool = appearanceEditorTool(null, drafts)
        host(AppLanguage.ENGLISH) { Column { tool.content(toolContext(state)) } }
        onNodeWithText("+ Ellipse").reveal().performClick()
        waitForIdle()
        onNodeWithText("+ Text").reveal().performClick()
        waitForIdle()
        val ids = checkNotNull(drafts.draft).objects.filterIsInstance<SymbolObject.Shape>().map { it.id }
        screenshot("appearance-object-names")
        // Ids such as "shape-1" stay in the file and the test tags, never in the labels.
        val raw = Regex("shape-\\d+")
        assertTrue(onAllNodes(SemanticsMatcher("shows a shape id") { node ->
            (node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()).any(raw::containsMatchIn)
        }).fetchSemanticsNodes().isEmpty(), "Raw shape ids are shown")
        onNodeWithText("Ellipse 1").reveal().assertIsDisplayed()
        onNodeWithText("Text “main”").reveal().assertIsDisplayed()
        onNodeWithTag("appearance-shape-${ids.last()}").assertIsDisplayed()
        onNodeWithText("Input pin (20, 30)").reveal().assertIsDisplayed()
        onNodeWithText("Output pin (100, 30) [8 bits]").reveal().assertIsDisplayed()
        screenshot("appearance-port-names")
    }

    @Test
    fun applyKeepsTheDraftWhenTheEditorDoesNotTakeIt() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ"))
        val drafts = AppearanceDraftHolder()
        val tool = appearanceEditorTool(null, drafts)
        val notices = mutableListOf<LocalizedText>()
        // A read-only document (or a superseded editor) ignores applyProject without throwing.
        host { Column { tool.content(toolContext(state, notices, apply = false)) } }
        onNodeWithText("+ Rectangle").reveal().performClick()
        waitForIdle()
        val edited = drafts.draft
        onNodeWithTag("appearance-apply").performClick()
        waitForIdle()
        screenshot("appearance-apply-refused")
        assertTrue(notices.none { it.key == "appearance_saved" }, "Reported saved: $notices")
        assertTrue(drafts.dirty, "The unapplied draft was discarded")
        assertEquals(edited, drafts.draft)
        onNodeWithText("The appearance was not applied", substring = true).assertIsDisplayed()
        assertTrue(!SymbolAppearance.isCustom(state.document))
    }
}
