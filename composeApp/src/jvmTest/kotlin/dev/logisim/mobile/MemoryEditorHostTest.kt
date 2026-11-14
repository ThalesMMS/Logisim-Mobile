package dev.logisim.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.MemoryImage
import dev.logisim.mobile.core.MemoryImageCodec
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.LocalAppLanguage
import dev.logisim.mobile.localization.rememberLocalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The memory editor tool sheet (C51, C55, C56, C65, C66, C71) on the Skia host, a 4-bit address / 8-bit data ROM and
 * RAM. The host mirrors App.kt's tool sheet: a title, the header Close and the tool content, with `closeTool` and
 * `selectComponent` wired as App.kt wires them. Screenshots go to `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class MemoryEditorHostTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="4" loc="(200,100)" name="ROM">
      <a name="addrWidth" val="4"/>
      <a name="dataWidth" val="8"/>
      <a name="contents">addr/data: 4 8
0a 0b 0c
</a>
    </comp>
    <comp lib="4" loc="(200,300)" name="RAM">
      <a name="addrWidth" val="4"/>
      <a name="dataWidth" val="8"/>
    </comp>
  </circuit>
</project>"""

    private class Host(val state: EditorState, val tool: EditorTool) {
        val open: MutableState<Boolean> = mutableStateOf(true)
    }

    private fun EditorState.rom() = document.components.first { it.kind == ComponentKind.ROM }
    private fun EditorState.ram() = document.components.first { it.kind == ComponentKind.RAM }

    private fun host(
        select: (EditorState) -> String? = { it.rom().id },
        files: MemoryEditorFileActions? = null,
        source: String = xml,
    ): Host {
        val state = EditorState(source)
        select(state)?.let(state::select)
        return Host(state, memoryEditorTool(state.componentRegistry, files))
    }

    private fun ComposeUiTest.show(host: Host, language: AppLanguage = AppLanguage.ENGLISH) {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        if (host.open.value) ToolSheet(host)
                    }
                }
            }
        }
        waitForIdle()
    }

    @Composable
    private fun ToolSheet(host: Host) {
        val strings = rememberLocalizer()
        val state = host.state
        val context = EditorToolContext(state.project, state.document, state.simulation,
            state.document.components.filter { it.id in state.selectedIds },
            { state.applyProject(it) }, { state.applyDocument(it) }, state::selectCircuit, { state.selectComponent(it) },
            state::reportStatus, projectSimulation = state.rootSimulation, documentIdentity = state.documentIdentity,
            sendComponentEvent = { id, event -> state.sendComponentEvent(id, event) },
            showNotice = state::reportStatus, closeTool = { host.open.value = false })
        EditorModalSheet(onDismissRequest = { host.open.value = false }, modifier = Modifier.testTag("editor-tool-sheet")) {
            Row(Modifier.fillMaxWidth().heightIn(max = 700.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(strings(host.tool.titleText), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        TextButton(onClick = { host.open.value = false },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("editor-tool-close")) { Text(strings(Messages.ActionClose)) }
                    }
                    host.tool.content(context)
                }
            }
        }
    }

    /** Cells keep the contentDescription "Address {address}, value {value}" before and after the sweep. */
    private fun cell(address: String) = SemanticsMatcher("memory cell $address") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Address $address,") } == true
    }
    private fun cellValue(address: String, value: String) = SemanticsMatcher("memory cell $address = $value") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == "Address $address, value $value" } == true
    }
    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
    private fun inWordDialog(title: String) = hasAnyAncestor(hasAnyDescendant(hasText(title)))
    private fun button(label: String) = hasText(label) and hasClickAction()

    private fun ComposeUiTest.openCell(address: String) {
        onNode(cell(address)).performScrollTo().performClick()
        waitForIdle()
    }

    @Test
    fun invalidWordKeepsTheDialogOpenWithTheErrorInside() = runDesktopComposeUiTest(390, 844) {
        val host = host()
        show(host)
        openCell("1")
        onNode(hasSetTextAction() and inWordDialog("Address 1")).performTextReplacement("1FF")
        onNode(button("Change") and inWordDialog("Address 1")).performClick()
        waitForIdle()
        screenshot("phone-cell-dialog-error")
        onNode(hasText("The value does not fit in 8 bits.") and inWordDialog("Address 1"), useUnmergedTree = true).assertExists()
        onNode(hasSetTextAction() and inWordDialog("Address 1")).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        onNode(cellValue("1", "0b")).assertExists()
    }

    @Test
    fun cellDialogSelectsTheWordStepsWithNextAndKeepsErrorsInside() = runDesktopComposeUiTest(390, 844) {
        val host = host()
        show(host)
        screenshot("phone-memory-editor")
        openCell("0")
        screenshot("phone-cell-dialog")
        val field = onNode(hasSetTextAction() and inWordDialog("Address 0"))
        field.assertIsFocused()
        assertEquals(TextRange(0, 2), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])

        field.performTextInput("12")
        field.performImeAction()
        waitForIdle()
        onNode(hasText("Address 1") and hasTestTag("memory-word-title")).assertExists()
        onNode(cellValue("0", "12")).assertExists()
        val next = onNode(hasSetTextAction() and inWordDialog("Address 1"))
        assertEquals(TextRange(0, 2), next.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        onNodeWithTag("memory-word-previous").assertIsEnabled()
        screenshot("phone-cell-dialog-next")

        next.performTextInput("1FF")
        onNode(button("Change") and inWordDialog("Address 1")).performClick()
        waitForIdle()
        screenshot("phone-cell-dialog-next-error")
        onNode(hasText("The value does not fit in 8 bits.") and inWordDialog("Address 1"), useUnmergedTree = true).assertExists()
        onNode(hasSetTextAction() and inWordDialog("Address 1"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        // The ROM document is untouched until Apply.
        assertEquals("addr/data: 4 8\n0a 0b 0c\n", host.state.rom().attributes["contents"])
    }

    /** A program is typed in one dialog: each IME Next commits a word and moves on, following the grid onto the next page. */
    @Test
    fun wordsAreEnteredContinuouslyAcrossAPageBoundary() = runDesktopComposeUiTest(390, 844) {
        val wide = xml.replace("""<a name="addrWidth" val="4"/>""", """<a name="addrWidth" val="8"/>""")
            .replace("addr/data: 4 8", "addr/data: 8 8")
        val host = host(source = wide)
        show(host)
        onNodeWithTag("memory-next-page").assertIsEnabled()
        onNodeWithTag("memory-address").performTextReplacement("3e")
        onNode(button("Go")).performClick()
        waitForIdle()
        openCell("3e")
        val words = listOf("11", "22", "33", "44")
        words.forEachIndexed { index, word ->
            val address = (0x3e + index).toString(16)
            val field = onNode(hasSetTextAction() and inWordDialog("Address $address"))
            assertEquals(TextRange(0, 2), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
            field.performTextInput(word)
            field.performImeAction()
            waitForIdle()
            onNodeWithTag("memory-word-title").assertExists()
        }
        screenshot("phone-word-stepper-next-page")
        // The dialog never closed: one dialog round trip for four words, and the grid moved to the page of 0x42.
        onNode(hasText("Address 42") and hasTestTag("memory-word-title")).assertExists()
        onNodeWithTag("memory-page-indicator").assert(hasText("0x40–0x7f / 0xff"))
        onNodeWithTag("memory-word-previous").performClick()
        waitForIdle()
        onNode(hasText("Address 41") and hasTestTag("memory-word-title")).assertExists()
        onNodeWithTag("memory-word-change").performClick()
        waitForIdle()
        assertTrue(onAllNodes(hasTestTag("memory-word-title")).fetchSemanticsNodes().isEmpty())
        println("WORD_STEPPER words=${words.size} dialogs=1")
        onNodeWithTag("memory-apply").performClick()
        waitForIdle()
        assertEquals(MemoryImageCodec.encodeCirc(MemoryImage(8, 8, mapOf(0L to 0x0auL, 1L to 0x0buL, 2L to 0x0cuL,
            0x3eL to 0x11uL, 0x3fL to 0x22uL, 0x40L to 0x33uL, 0x41L to 0x44uL))), host.state.rom().attributes["contents"])
    }

    @Test
    fun nextIsDisabledAtTheLastAddress() = runDesktopComposeUiTest(390, 844) {
        val host = host()
        show(host)
        openCell("e")
        // The keyboard offers Next while a next word exists and Done on the last one.
        assertEquals(ImeAction.Next, onNode(hasSetTextAction() and inWordDialog("Address e")).fetchSemanticsNode()
            .config[SemanticsProperties.ImeAction])
        onNode(hasSetTextAction() and inWordDialog("Address e")).performImeAction()
        waitForIdle()
        onNodeWithTag("memory-word-next").assertIsNotEnabled()
        onNodeWithTag("memory-word-previous").assertIsEnabled()
        assertEquals(ImeAction.Done, onNode(hasSetTextAction() and inWordDialog("Address f")).fetchSemanticsNode()
            .config[SemanticsProperties.ImeAction])
        onNode(hasSetTextAction() and inWordDialog("Address f")).performImeAction()
        waitForIdle()
        // The last word commits and closes on Done.
        assertTrue(onAllNodes(hasTestTag("memory-word-title")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun radixPageIndicatorAndPageButtons() = runDesktopComposeUiTest(390, 844) {
        val host = host()
        show(host)
        screenshot("phone-radix-and-pages")
        onNodeWithTag("memory-radix-16").assert(selected)
        onNode(button("Previous")).assertIsNotEnabled()
        onNode(button("Next")).assertIsNotEnabled()
        onNodeWithTag("memory-page-indicator").assert(hasText("0x0–0xf / 0xf"))
        onNodeWithTag("memory-radix-2").performClick()
        waitForIdle()
        // Only the value radix changes; addresses stay in hexadecimal.
        onNode(cellValue("a", "00000000")).assertExists()
        onNode(cellValue("1", "00001011")).assertExists()
        screenshot("phone-radix-binary")
    }

    @Test
    fun longPressAnchorsASelectionThatTapsExtend() = runDesktopComposeUiTest(390, 844) {
        val host = host()
        show(host)
        onNode(cell("2")).performScrollTo().performTouchInput { longClick() }
        waitForIdle()
        onNode(cell("5")).performScrollTo().performClick()
        waitForIdle()
        screenshot("phone-range-selection")
        listOf("2", "3", "4", "5").forEach { onNode(cell(it)).assert(selected) }
        listOf("1", "6").forEach { onNode(cell(it)).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)) }
        assertTrue(onAllNodes(hasTestTag("memory-word-title")).fetchSemanticsNodes().isEmpty())
        // The selection change is announced, and each cell action is listed once under the label of what it does now.
        onNodeWithTag("memory-selection-summary")
            .assert(hasText("Selected 0x2–0x5 (4 words) · Tap a cell to extend the selection."))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        val selecting = onNode(cell("7")).fetchSemanticsNode().config
        assertEquals("Extend selection here", selecting[SemanticsActions.OnClick].label)
        assertEquals("Start selection here", selecting[SemanticsActions.OnLongClick].label)
        assertEquals(listOf("Edit word"), selecting.getOrNull(SemanticsActions.CustomActions)?.map { it.label })
        onNode(cell("7")).performCustomAccessibilityActionWithLabel("Edit word")
        waitForIdle()
        onNode(hasText("Address 7") and hasTestTag("memory-word-title")).assertExists()
        onNodeWithTag("memory-word-change").performClick()
        waitForIdle()
        onNodeWithTag("memory-clear-selection").performClick()
        waitForIdle()
        val editing = onNode(cell("7")).fetchSemanticsNode().config
        assertEquals("Edit word", editing[SemanticsActions.OnClick].label)
        assertEquals("Start selection here", editing[SemanticsActions.OnLongClick].label)
        assertEquals(listOf("Extend selection here"), editing.getOrNull(SemanticsActions.CustomActions)?.map { it.label })
    }

    /** A Go, Select range or Fill error disappears once a later word edit succeeds. */
    @Test
    fun aSuccessfulWordEditClearsAnEarlierError() = runDesktopComposeUiTest(390, 844) {
        val host = host()
        show(host)
        onNodeWithTag("memory-address").performTextReplacement("zz")
        onNode(button("Go")).performClick()
        waitForIdle()
        onNode(hasText("Invalid number: zz.")).assertExists()
        openCell("0")
        onNode(hasSetTextAction() and inWordDialog("Address 0")).performTextInput("12")
        onNodeWithTag("memory-word-change").performClick()
        waitForIdle()
        onNode(cellValue("0", "12")).assertExists()
        assertTrue(onAllNodes(hasText("Invalid number: zz.")).fetchSemanticsNodes().isEmpty())
    }

    /** Paste names the word it writes from; with no selection, Fill and Paste act on the last edited word. */
    @Test
    fun pasteNamesItsTargetAndFillUsesTheLastEditedWord() = runDesktopComposeUiTest(390, 844) {
        val host = host()
        show(host)
        onNodeWithTag("memory-paste").assert(hasText("Paste at 0x0"))
        openCell("3")
        onNode(hasSetTextAction() and inWordDialog("Address 3")).performTextInput("12")
        onNodeWithTag("memory-word-change").performClick()
        waitForIdle()
        onNodeWithTag("memory-paste").assert(hasText("Paste at 0x3"))
        onNodeWithTag("memory-clear-selection").performClick()
        waitForIdle()
        onNodeWithTag("memory-paste").assert(hasText("Paste at 0x3"))
        onNodeWithTag("memory-fill-value").performTextReplacement("ff")
        onNode(button("Fill")).performClick()
        waitForIdle()
        onNode(cellValue("3", "ff")).assertExists()
        onNode(cellValue("2", "0c")).assertExists()
        onNode(cellValue("4", "00")).assertExists()
        onNodeWithTag("memory-address").performTextReplacement("a")
        onNode(button("Go")).performClick()
        waitForIdle()
        onNodeWithTag("memory-paste").assert(hasText("Paste at 0xa"))
        screenshot("phone-paste-target")
    }

    @Test
    fun stagedEditsSurviveClosingAndApplyClosesTheSheet() = runDesktopComposeUiTest(390, 844) {
        val host = host()
        show(host)
        openCell("0")
        onNode(hasSetTextAction() and inWordDialog("Address 0")).performTextReplacement("12")
        onNode(button("Change") and inWordDialog("Address 0")).performClick()
        waitForIdle()
        onNode(cellValue("0", "12")).assertExists()
        screenshot("phone-staged-edit")
        onNodeWithTag("editor-tool-close").performClick()
        waitForIdle()
        assertFalse(host.open.value)
        runOnIdle { host.open.value = true }
        waitForIdle()
        screenshot("phone-reopen-unapplied")
        onNode(cellValue("0", "12")).assertExists()
        onNodeWithTag("memory-unapplied-banner").assertIsDisplayed()

        onNodeWithTag("memory-apply").performClick()
        waitForIdle()
        assertFalse(host.open.value)
        assertEquals(MemoryImageCodec.encodeCirc(MemoryImage(4, 8, mapOf(0L to 0x12uL, 1L to 0x0buL, 2L to 0x0cuL))),
            host.state.rom().attributes["contents"])
        // Applied edits are no longer staged.
        runOnIdle { host.open.value = true }
        waitForIdle()
        assertTrue(onAllNodes(hasTestTag("memory-unapplied-banner")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun editsStagedBeforeTheMemoryChangedWaitForAnExplicitChoice() = runDesktopComposeUiTest(390, 844) {
        val host = host()
        show(host)
        openCell("0")
        onNode(hasSetTextAction() and inWordDialog("Address 0")).performTextInput("12")
        onNodeWithTag("memory-word-change").performClick()
        waitForIdle()
        onNodeWithTag("editor-tool-close").performClick()
        waitForIdle()
        // The ROM changes underneath (another edit, an undo or Load image…) while the sheet is closed.
        replaceRomContents(host, "addr/data: 4 8\n77\n")
        runOnIdle { host.open.value = true }
        waitForIdle()
        screenshot("phone-reopen-conflict")
        onNodeWithTag("memory-conflict-banner").assertIsDisplayed()
        onNode(hasText("Restore edits writes the words you edited over the current contents.", substring = true)).assertExists()
        onNode(cellValue("0", "77")).assertExists()
        onNodeWithTag("memory-conflict-banner-confirm").performClick()
        waitForIdle()
        // Only the edited word comes back; words 1 and 2, cleared underneath, stay as the memory has them now.
        onNode(cellValue("0", "12")).assertExists()
        onNode(cellValue("1", "00")).assertExists()
        onNode(cellValue("2", "00")).assertExists()
        onNodeWithTag("memory-unapplied-banner").assertIsDisplayed()
        onNodeWithTag("memory-unapplied-banner-discard").performClick()
        waitForIdle()
        onNode(cellValue("0", "77")).assertExists()
        assertTrue(onAllNodes(hasTestTag("memory-unapplied-banner")).fetchSemanticsNodes().isEmpty())
    }

    private fun ComposeUiTest.replaceRomContents(host: Host, contents: String) = runOnIdle {
        val rom = host.state.rom()
        host.state.applyDocument(host.state.document.copy(components = host.state.document.components.map {
            if (it.id == rom.id) it.copy(attributes = it.attributes + ("contents" to contents)) else it
        }))
        host.state.select(rom.id)
    }

    /** Restored edits the memory already holds end the old draft, so the conflict does not come back on every reopen. */
    @Test
    fun restoringEditsTheMemoryAlreadyHoldsEndsTheConflict() = runDesktopComposeUiTest(390, 844) {
        val host = host()
        show(host)
        openCell("0")
        onNode(hasSetTextAction() and inWordDialog("Address 0")).performTextInput("12")
        onNodeWithTag("memory-word-change").performClick()
        waitForIdle()
        onNodeWithTag("editor-tool-close").performClick()
        waitForIdle()
        // The same word is written elsewhere (the canvas word editor) while the sheet is closed.
        replaceRomContents(host, "addr/data: 4 8\n12 0b 0c\n")
        runOnIdle { host.open.value = true }
        waitForIdle()
        onNodeWithTag("memory-conflict-banner-confirm").performClick()
        waitForIdle()
        onNode(cellValue("0", "12")).assertExists()
        assertTrue(onAllNodes(hasTestTag("memory-unapplied-banner")).fetchSemanticsNodes().isEmpty())
        onNodeWithTag("editor-tool-close").performClick()
        waitForIdle()
        runOnIdle { host.open.value = true }
        waitForIdle()
        assertTrue(onAllNodes(hasTestTag("memory-conflict-banner")).fetchSemanticsNodes().isEmpty())
        assertTrue(onAllNodes(hasTestTag("memory-unapplied-banner")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun cancelClosesTheSheetAndDropsTheStagedEdits() = runDesktopComposeUiTest(390, 844) {
        val host = host()
        show(host)
        openCell("0")
        onNode(hasSetTextAction() and inWordDialog("Address 0")).performTextInput("12")
        onNodeWithTag("memory-word-change").performClick()
        waitForIdle()
        onNodeWithTag("memory-cancel").performClick()
        waitForIdle()
        assertFalse(host.open.value)
        runOnIdle { host.open.value = true }
        waitForIdle()
        onNode(cellValue("0", "0a")).assertExists()
        assertTrue(onAllNodes(hasTestTag("memory-unapplied-banner")).fetchSemanticsNodes().isEmpty())
        assertEquals("addr/data: 4 8\n0a 0b 0c\n", host.state.rom().attributes["contents"])
    }

    /** The ROM and RAM share their widths, so only the per-memory key keeps the ROM's staged edits out of the RAM. */
    @Test
    fun anotherMemoryOfTheSameWidthsOpensWithItsOwnContents() = runDesktopComposeUiTest(390, 844) {
        val host = host()
        show(host)
        openCell("0")
        onNode(hasSetTextAction() and inWordDialog("Address 0")).performTextInput("12")
        onNodeWithTag("memory-word-change").performClick()
        waitForIdle()
        runOnIdle { host.state.select(host.state.ram().id) }
        waitForIdle()
        onNode(hasText("RAM") and hasAnyAncestor(hasTestTag("memory-editor-panel"))).assertExists()
        onNode(cellValue("0", "00")).assertExists()
        assertTrue(onAllNodes(hasTestTag("memory-unapplied-banner")).fetchSemanticsNodes().isEmpty())
        runOnIdle { host.state.select(host.state.rom().id) }
        waitForIdle()
        onNode(cellValue("0", "12")).assertExists()
        onNodeWithTag("memory-unapplied-banner").assertIsDisplayed()
    }

    /** A labelled memory's detail joins its kind and location through the catalog in each language. */
    @Test
    fun labelledMemoriesShowTheirKindBesideTheLocation() = runDesktopComposeUiTest(390, 844) {
        val labelled = xml.replace("""<a name="addrWidth" val="4"/>
      <a name="dataWidth" val="8"/>
      <a name="contents">""", """<a name="addrWidth" val="4"/>
      <a name="dataWidth" val="8"/>
      <a name="label" val="Boot"/>
      <a name="contents">""")
        show(host(select = { null }, source = labelled), AppLanguage.PORTUGUESE)
        onNode(hasText("Boot")).assertExists()
        onNode(hasText("ROM · Posição (200, 100)")).assertExists()
    }

    @Test
    fun aCircuitWithoutMemoriesSaysSo() = runDesktopComposeUiTest(390, 844) {
        val empty = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <main name="main"/>
  <circuit name="main"/>
</project>"""
        show(host(select = { null }, source = empty))
        onNode(hasText("This circuit has no ROM or RAM.")).assertExists()
        assertTrue(onAllNodes(hasTestTag("memory-editor-panel")).fetchSemanticsNodes().isEmpty())
    }

    /** The canvas word dialog after its extraction: value selected, the error inside, Done applies and closes. */
    @Test
    fun canvasWordDialogSelectsTheValueKeepsErrorsInsideAndClosesOnDone() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(xml)
        val rom = state.rom()
        val open = mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        if (open.value) CanvasMemoryWordEditor(CanvasMemoryWord(rom.id, 1), "0b",
                            onEdit = { state.editMemoryWord(state.accessibilityContext(), rom.id, 1, it) },
                            onDismiss = { open.value = false })
                    }
                }
            }
        }
        waitForIdle()
        val field = onNode(hasSetTextAction() and inWordDialog("Address 1"))
        field.assertIsFocused()
        assertEquals(TextRange(0, 2), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        assertEquals(ImeAction.Done, field.fetchSemanticsNode().config[SemanticsProperties.ImeAction])
        field.performTextInput("1ff")
        onNodeWithTag("memory-word-change").performClick()
        waitForIdle()
        screenshot("phone-canvas-word-dialog-error")
        assertTrue(open.value)
        onNode(hasText("The value does not fit in 8 bits.") and inWordDialog("Address 1"), useUnmergedTree = true).assertExists()
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        field.performTextReplacement("5")
        field.performImeAction()
        waitForIdle()
        assertFalse(open.value)
        assertEquals(0x05uL, memoryEditorImage(state.rom(), state.simulation, state.componentRegistry)[1])
    }

    @Test
    fun withNothingSelectedTheToolListsTheCircuitMemories() = runDesktopComposeUiTest(390, 844) {
        val host = host(select = { null })
        show(host)
        screenshot("phone-no-selection")
        onNode(button("ROM")).assertExists()
        onNode(button("RAM")).performClick()
        waitForIdle()
        assertEquals(setOf(host.state.ram().id), host.state.selectedIds)
        onNodeWithTag("memory-editor-panel").assertExists()
        onNode(hasText("RAM") and hasAnyAncestor(hasTestTag("memory-editor-panel"))).assertExists()
        screenshot("phone-chosen-ram")
    }

    @Test
    fun formatChooserIsARadioListThatScrollsInLandscape() = runDesktopComposeUiTest(800, 360) {
        val host = host()
        show(host)
        screenshot("landscape-memory-editor")
        // The panel scrolls in a short window while Apply stays visible.
        onNode(cell("f")).performScrollTo().assertIsDisplayed()
        onNodeWithTag("memory-apply").assertIsDisplayed()
        screenshot("landscape-memory-editor-scrolled")
        onNodeWithTag("memory-exchange").performScrollTo().performClick()
        waitForIdle()
        screenshot("landscape-format-chooser")
        // The four families are radio rows themselves (the value base segments behind the dialog are radios too).
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        listOf("raw", "hex", "binary", "ascii").forEach {
            onNodeWithTag("memory-format-$it").assert(radio).assert(hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)))
        }
        onNodeWithTag("memory-format-hex").assert(selected)
        // Each option segment names its group for TalkBack, like Evolution's (size), (style) and (endian) rows.
        onNodeWithTag("memory-format-words").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Unit: words")))
        onNodeWithTag("memory-format-plain").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Style: plain")))
        onNode(button("Open file")).performScrollTo().assertIsDisplayed()
        onNodeWithTag("memory-format-bytes").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("memory-format-little").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("memory-save-file").performScrollTo().assertIsDisplayed()
        screenshot("landscape-format-chooser-scrolled")
        onNodeWithTag("memory-format-little").assert(selected)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Byte order: little-endian")))
        onNode(hasText("v3.0 hex bytes addressed little-endian", substring = true)).assertExists()
        onNodeWithTag("memory-exchange-close").assertIsDisplayed()
    }

    @Test
    fun openFileDetectsHeadersAndAsksForAFormatOtherwise() = runDesktopComposeUiTest(390, 844) {
        val assets = FakeAssets()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val files = NativeMemoryImageActions(assets, scope, workerDispatcher = Dispatchers.Unconfined)
        val host = host(files = files)
        show(host)
        onNodeWithTag("memory-exchange").performClick()
        waitForIdle()
        onNode(button("Open file")).performScrollTo().performClick()
        waitForIdle()
        runOnIdle { assets.finish(DocumentAsset("program.hex", "text/plain", "v2.0 raw\n0a 2*0 ff\n".encodeToByteArray())) }
        waitForIdle()
        onNode(cellValue("3", "ff")).assertExists()
        screenshot("phone-open-raw-file")

        onNodeWithTag("memory-exchange").performClick()
        waitForIdle()
        onNode(button("Open file")).performScrollTo().performClick()
        waitForIdle()
        runOnIdle { assets.finish(DocumentAsset("data.bin", "application/octet-stream", byteArrayOf(0x12, 0xab.toByte()))) }
        waitForIdle()
        screenshot("phone-open-needs-format")
        onNodeWithTag("memory-format-choice-binary").performClick()
        waitForIdle()
        onNodeWithTag("memory-format-choice-load").performClick()
        waitForIdle()
        onNode(cellValue("0", "12")).assertExists()
        onNode(cellValue("1", "ab")).assertExists()

        // A header the decoder does not read starts on Binary, as Evolution's HexFormatDialog does: a text format
        // would read that header again.
        onNodeWithTag("memory-exchange").performClick()
        waitForIdle()
        onNode(button("Open file")).performScrollTo().performClick()
        waitForIdle()
        runOnIdle { assets.finish(DocumentAsset("future.hex", "text/plain", "v2.1 raw\n0a\n".encodeToByteArray())) }
        waitForIdle()
        screenshot("phone-open-unsupported-header")
        onNode(hasText("Unsupported memory header.", substring = true)).assertExists()
        onNodeWithTag("memory-format-choice-binary").assert(selected)
        onNodeWithTag("memory-format-choice-load").performClick()
        waitForIdle()
        onNode(cellValue("0", "76")).assertExists()
        onNode(cellValue("1", "32")).assertExists()
    }

    /** Labels of the value base and format option segments fit whole at the 200% font scale the project tracks (#239). */
    private fun ComposeUiTest.assertLabelFits(tag: String) {
        val node = onNode(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        assertNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts)
        val layout = layouts.single()
        val text = layout.layoutInput.text.text
        // Centred text is laid out across the segment and then sized to the text, so only height overflow, an ellipsis
        // or a break inside a word mean the label is cut.
        assertFalse(layout.didOverflowHeight, "$tag label '$text' is taller than its segment")
        assertFalse((0 until layout.lineCount).any(layout::isLineEllipsized), "$tag label '$text' is cut short")
        val breaks = (0 until layout.lineCount - 1).map { layout.getLineEnd(it) }
        assertTrue(breaks.all { text[it - 1] == ' ' || text[it - 1] == '-' }, "$tag label '$text' breaks inside a word at $breaks")
    }

    private fun ComposeUiTest.largeFontPanelAndChooser(language: AppLanguage, suffix: String) {
        val host = host()
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) { if (host.open.value) ToolSheet(host) }
                }
            }
        }
        waitForIdle()
        screenshot("phone-memory-editor-font200$suffix")
        listOf(2, 8, 10, 16).forEach { assertLabelFits("memory-radix-$it") }
        // Apply and Cancel stay on screen, and the grid is reachable by scrolling.
        onNodeWithTag("memory-apply").assertIsDisplayed()
        onNodeWithTag("memory-cancel").assertIsDisplayed()
        onNodeWithTag("memory-paste").performScrollTo()
        screenshot("phone-memory-editor-font200-fields$suffix")
        onNodeWithTag("memory-cell-f").performScrollTo().assertIsDisplayed()
        screenshot("phone-memory-editor-font200-scrolled$suffix")
        onNodeWithTag("memory-exchange").performScrollTo()
        onNodeWithTag("memory-exchange").performScrollTo().performClick()
        waitForIdle()
        screenshot("phone-format-chooser-font200$suffix")
        listOf("words", "bytes", "addressed", "plain").forEach { assertLabelFits("memory-format-$it") }
        onNodeWithTag("memory-format-bytes").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("memory-format-little").performScrollTo()
        screenshot("phone-format-chooser-bytes-font200$suffix")
        listOf("big", "little").forEach { assertLabelFits("memory-format-$it") }
    }

    @Test
    fun portugueseLayoutFitsANarrowPhone() = runDesktopComposeUiTest(360, 800) {
        show(host(), AppLanguage.PORTUGUESE)
        screenshot("phone360-memory-editor-pt")
        listOf(2, 8, 10, 16).forEach { assertLabelFits("memory-radix-$it") }
        onNodeWithTag("memory-apply").assertIsDisplayed()
        onNodeWithTag("memory-exchange").performClick()
        waitForIdle()
        onNodeWithTag("memory-format-bytes").performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("memory-format-little").performScrollTo()
        screenshot("phone360-format-chooser-pt")
        listOf("words", "bytes", "addressed", "plain", "big", "little").forEach { assertLabelFits("memory-format-$it") }
    }

    @Test
    fun largeFontKeepsSegmentLabelsWhole() = runDesktopComposeUiTest(390, 844) {
        largeFontPanelAndChooser(AppLanguage.ENGLISH, "")
    }

    @Test
    fun largeFontKeepsPortugueseSegmentLabelsWhole() = runDesktopComposeUiTest(390, 844) {
        largeFontPanelAndChooser(AppLanguage.PORTUGUESE, "-pt")
    }

    @Test
    fun portugueseLayoutFitsAPhone() = runDesktopComposeUiTest(390, 844) {
        val host = host()
        show(host, AppLanguage.PORTUGUESE)
        screenshot("phone-memory-editor-pt")
        onNodeWithTag("memory-exchange").performClick()
        waitForIdle()
        screenshot("phone-format-chooser-pt")
    }

    private class FakeAssets : DocumentAssetActions {
        private var pending: ((AssetOpenResult) -> Unit)? = null
        override fun open(mimeTypes: List<String>, maximumBytes: Int, onResult: (AssetOpenResult) -> Unit) { pending = onResult }
        override fun create(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = onResult(AssetActionResult.Completed)
        override fun share(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = error("share")
        override fun printPdf(asset: DocumentAsset, onResult: (AssetActionResult) -> Unit) = error("print")
        fun finish(asset: DocumentAsset) {
            val callback = assertNotNull(pending)
            pending = null
            callback(AssetOpenResult.Opened(asset))
        }
    }
}
