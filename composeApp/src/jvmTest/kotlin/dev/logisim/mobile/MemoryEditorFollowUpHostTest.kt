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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.MemoryImage
import dev.logisim.mobile.core.MemoryImageCodec
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.localization.LocalAppLanguage
import dev.logisim.mobile.localization.rememberLocalizer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W2-10 follow-ups of the memory editor sheet on the Skia host, a 4-bit address / 8-bit data ROM at phone size:
 * word entry does not make the selection summary speak after every word (R09-2), and Restore edits writes back every
 * word an edit, Fill or Paste wrote, also where it wrote the value the word already had (R09-3).
 * Screenshots and semantics dumps (what TalkBack reads) go to `-PuiScreenshots=<dir>`.
 */
@OptIn(ExperimentalTestApi::class)
class MemoryEditorFollowUpHostTest {
    private fun xml(contents: String) = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0dev" version="1.0">
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="4" loc="(200,100)" name="ROM">
      <a name="addrWidth" val="4"/>
      <a name="dataWidth" val="8"/>
      <a name="contents">$contents</a>
    </comp>
  </circuit>
</project>"""

    private class Host(val state: EditorState, val tool: EditorTool) {
        val open: MutableState<Boolean> = mutableStateOf(true)
    }

    private fun EditorState.rom() = document.components.first { it.kind == ComponentKind.ROM }

    private fun host(contents: String = "addr/data: 4 8\n0a 0b 0c\n"): Host {
        val state = EditorState(xml(contents))
        state.select(state.rom().id)
        return Host(state, memoryEditorTool(state.componentRegistry, null))
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

    /** App.kt's tool sheet: a title, the header Close and the tool content. */
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

    private fun ComposeUiTest.dump(name: String) {
        val roots = onAllNodes(isRoot())
        val text = (0 until roots.fetchSemanticsNodes().size).joinToString("\n\n") { roots[it].printToString() }
        HostScreenshots.directory.mkdirs()
        File(HostScreenshots.directory, "$name-semantics.txt").writeText(text)
    }

    private fun cell(address: String) = SemanticsMatcher("memory cell $address") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Address $address,") } == true
    }
    private fun cellValue(address: String, value: String) = SemanticsMatcher("memory cell $address = $value") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == "Address $address, value $value" } == true
    }
    private fun inWordDialog(title: String) = hasAnyAncestor(hasAnyDescendant(hasText(title)))
    private fun button(label: String) = hasText(label) and hasClickAction()
    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    private val silent = SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion)
    private fun ComposeUiTest.summary() = onNodeWithTag("memory-selection-summary")

    /**
     * R09-2: each word committed in the word dialog selects that word, which changes the selection summary behind the
     * dialog. The summary is not a live region then, so TalkBack reads only the dialog's next field; a selection the
     * user makes (long press, tap to extend, Clear selection, Go) is still announced.
     */
    @Test
    fun wordEntryKeepsTheSelectionSummaryQuietWhileSelectingStillAnnounces() = runDesktopComposeUiTest(390, 844) {
        val host = host()
        show(host)
        onNode(cell("0")).performScrollTo().performClick()
        waitForIdle()
        listOf("11", "22", "33").forEachIndexed { index, word ->
            val field = onNode(hasSetTextAction() and inWordDialog("Address $index"))
            field.performTextInput(word)
            field.performImeAction()
            waitForIdle()
            if (index == 0) {
                screenshot("phone-word-entry-summary")
                dump("phone-word-entry-summary")
            }
            // The summary now names the word just written, without asking TalkBack to read it out.
            summary().assert(hasText("Selected 0x$index–0x$index (1 word)")).assert(silent)
        }
        onNodeWithTag("memory-word-change").performClick()
        waitForIdle()
        assertTrue(onAllNodes(hasTestTag("memory-word-title")).fetchSemanticsNodes().isEmpty())
        // Closing the dialog does not turn the summary of the last word into an announcement either.
        summary().assert(hasText("Selected 0x3–0x3 (1 word)")).assert(silent)
        onNode(cellValue("2", "33")).assertExists()

        // Selections the user makes are announced as before.
        onNode(cell("4")).performScrollTo().performTouchInput { longClick() }
        waitForIdle()
        summary().assert(hasText("Selected 0x4–0x4 (1 word) · Tap a cell to extend the selection.")).assert(polite)
        onNode(cell("6")).performScrollTo().performClick()
        waitForIdle()
        summary().assert(hasText("Selected 0x4–0x6 (3 words) · Tap a cell to extend the selection.")).assert(polite)
        screenshot("phone-selection-summary-announced")
        dump("phone-selection-summary-announced")
        onNodeWithTag("memory-clear-selection").performScrollTo().performClick()
        waitForIdle()
        summary().assert(polite)
        onNodeWithTag("memory-address").performScrollTo().performTextReplacement("9")
        onNode(button("Go")).performScrollTo().performClick()
        waitForIdle()
        summary().assert(hasText("Selected 0x9–0x9 (1 word)")).assert(polite)
    }

    private fun ComposeUiTest.replaceRomContents(host: Host, contents: String) = runOnIdle {
        val rom = host.state.rom()
        host.state.applyDocument(host.state.document.copy(components = host.state.document.components.map {
            if (it.id == rom.id) it.copy(attributes = it.attributes + ("contents" to contents)) else it
        }))
        host.state.select(rom.id)
    }

    /**
     * R09-3: the user fills 0x0–0xf with 00 while only words 0–3 held other values, closes the sheet, and the memory
     * changes underneath (word 5 = 0x33). Restore edits writes every word the Fill wrote, so word 5 is 00 again.
     */
    @Test
    fun restoreWritesBackEveryWordAFillWroteEvenAtItsOldValue() = runDesktopComposeUiTest(390, 844) {
        val host = host("addr/data: 4 8\n0a 0b 0c 11\n")
        show(host)
        onNodeWithTag("memory-address").performTextReplacement("0")
        onNodeWithTag("memory-range-end").performTextReplacement("f")
        onNode(button("Select range")).performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("memory-fill-value").performTextReplacement("00")
        onNode(button("Fill")).performScrollTo().performClick()
        waitForIdle()
        listOf("0", "3", "5").forEach { onNode(cellValue(it, "00")).assertExists() }
        onNodeWithTag("editor-tool-close").performClick()
        waitForIdle()

        // The memory changes while the sheet is closed: word 5, which the Fill wrote as 00, now holds 0x33.
        replaceRomContents(host, "addr/data: 4 8\n0a 0b 0c 11 00 33\n")
        runOnIdle { host.open.value = true }
        waitForIdle()
        onNodeWithTag("memory-conflict-banner").assertIsDisplayed()
        onNode(cellValue("5", "33")).assertExists()
        screenshot("phone-fill-conflict")
        onNodeWithTag("memory-conflict-banner-confirm").performClick()
        waitForIdle()
        onNode(cell("5")).performScrollTo()
        screenshot("phone-fill-restored")
        for (address in 0..15) onNode(cellValue(address.toString(16), "00")).assertExists()
        onNodeWithTag("memory-unapplied-banner").performScrollTo().assertIsDisplayed()
        onNodeWithTag("memory-apply").performClick()
        waitForIdle()
        assertEquals(MemoryImageCodec.encodeCirc(MemoryImage(4, 8)), host.state.rom().attributes["contents"])
    }

    /** R09-3 with a Portuguese phone at 360 dp: the conflict banner and its two actions fit after a Fill. */
    @Test
    fun portugueseFillConflictBannerFitsANarrowPhone() = runDesktopComposeUiTest(360, 800) {
        val host = host("addr/data: 4 8\n0a 0b 0c 11\n")
        show(host, AppLanguage.PORTUGUESE)
        onNodeWithTag("memory-address").performTextReplacement("0")
        onNodeWithTag("memory-range-end").performTextReplacement("f")
        onNode(button("Selecionar faixa")).performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("memory-fill-value").performTextReplacement("00")
        onNode(button("Preencher")).performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("editor-tool-close").performClick()
        waitForIdle()
        replaceRomContents(host, "addr/data: 4 8\n0a 0b 0c 11 00 33\n")
        runOnIdle { host.open.value = true }
        waitForIdle()
        onNodeWithTag("memory-conflict-banner").assertIsDisplayed()
        screenshot("phone360-fill-conflict-pt")
        onNodeWithTag("memory-conflict-banner-confirm").assertIsDisplayed().performClick()
        waitForIdle()
        onNode(SemanticsMatcher("célula 7") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith("Endereço 7,") } == true
        }).performScrollTo()
        screenshot("phone360-fill-restored-pt")
        onNode(SemanticsMatcher("célula 5 = 00") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == "Endereço 5, valor 00" } == true
        }).assertExists()
    }
}
