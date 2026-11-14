package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.IoInputEvent
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.ProjectMouseMappings
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The editor shell's drafts and controls after review (W2-03, round 2): applying a mouse mapping returns to the typed
 * options, and dirty options or mappings ask before the scrim discards them (X3); a Button chip presses and releases
 * for TalkBack, a cancelled touch and the strip leaving the screen, and a read-only chip reveals its component (C68);
 * Reset's Undo announces itself, then expires (C69); the compact warning count draws an icon, not a glyph (C67).
 * Each scenario writes what it observed to `behavior-<scenario>.txt` beside its screenshots before asserting.
 */
@OptIn(ExperimentalTestApi::class)
class SimulationShellDraftsHostTest {
    private val pinFixture = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="a"><a name="label" val="A"/></comp>
    <comp lib="0" loc="(300,100)" name="Pin" logisim-mobile-id="y"><a name="facing" val="west"/><a name="type" val="output"/><a name="label" val="Y"/></comp>
    <wire from="(100,100)" to="(300,100)"/>
  </circuit>
</project>
"""

    private val buttonFixture = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#I/O" name="5"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="5" loc="(100,400)" name="Button" logisim-mobile-id="btn"><a name="label" val="B"/></comp>
    <comp lib="0" loc="(300,100)" name="Pin" logisim-mobile-id="out"><a name="facing" val="west"/><a name="type" val="output"/><a name="label" val="OUT"/></comp>
  </circuit>
</project>
"""

    private val warningFixture = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib name="0" desc="#Wiring"/>
  <lib name="1" desc="#Gates"/>
  <main name="main"/>
  <options><a name="simlimit" val="1"/></options>
  <circuit name="main">
    <comp lib="0" name="Pin" loc="(100,80)"><a name="label" val="A"/></comp>
    <comp lib="1" name="AND Gate" loc="(200,100)"><a name="inputs" val="2"/></comp>
    <comp lib="0" name="Pin" loc="(260,100)"><a name="facing" val="west"/><a name="type" val="output"/><a name="label" val="Y"/></comp>
    <wire from="(100,80)" to="(150,80)"/>
    <wire from="(200,100)" to="(260,100)"/>
  </circuit>
</project>
"""

    private fun ComposeUiTest.show(content: @Composable () -> Unit) {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) {
                LogisimTheme(dark = false) { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.app(state: EditorState, xml: String) {
        val header = EditorWorkspaceHeader("", "").also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
        }
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) {
                LogisimTheme(dark = false) { App(initialXml = xml, language = AppLanguage.ENGLISH, workspaceHeader = header) }
            }
        }
        waitForIdle()
        runOnIdle { state.dismissImportReport() }
        waitForIdle()
    }

    private fun observe(scenario: String, vararg facts: Pair<String, Any?>) {
        HostScreenshots.directory.mkdirs()
        File(HostScreenshots.directory, "behavior-$scenario.txt")
            .writeText(facts.joinToString("\n", postfix = "\n") { (name, value) -> "$name\t$value" })
    }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.tap(tag: String) {
        runCatching { onAllNodesWithTag(tag).onFirst().performScrollTo() }
        onAllNodesWithTag(tag).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.texts(): List<String> = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
        .fetchSemanticsNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun ComposeUiTest.waitFor(timeout: Long = 5_000, condition: () -> Boolean): Boolean =
        runCatching { waitUntil(timeoutMillis = timeout, condition = condition) }.isSuccess

    private fun text(key: String) = message(key).resolve(AppLanguage.ENGLISH)

    private fun ComposeUiTest.limitText(): String? = onAllNodesWithTag("simulation-options-limit").fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text

    private fun ComposeUiTest.chooseWireForThePrimaryButton() {
        onNodeWithText(text("mouse_mapping_tool").replace("{tool}", text("mouse_mapping_default"))).performClick()
        waitForIdle()
        onAllNodesWithText(text(Messages.ActionWire)).onLast().performClick()
        waitForIdle()
    }

    // X3: applying a mouse mapping returns to the options form, with what was typed there.
    @Test
    fun mappingApplyReturnsToTheTypedOptions() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pinFixture)
        var project by mutableStateOf(state.project)
        var mappingsApplied = 0
        show {
            SimulationOptionsPanel(project, onApply = {}, onApplyMouseMappings = { project = it; mappingsApplied++ },
                modifier = Modifier.fillMaxWidth())
        }
        onNodeWithTag("simulation-options-limit").performTextReplacement("5000")
        onNodeWithText(text("mouse_mapping_title")).performClick()
        waitForIdle()
        chooseWireForThePrimaryButton()
        onNodeWithText(text(Messages.ActionApply)).performClick()
        waitForIdle()
        screenshot("options-after-mapping-apply")
        val limit = limitText()
        val mapped = ProjectMouseMappings.read(project, state.componentRegistry).values.map { it.name }
        observe("options-mapping-apply", "mappingsApplied" to mappingsApplied, "limitAfterApply" to limit, "mappings" to mapped)
        assertEquals(1, mappingsApplied)
        assertEquals("5000", limit, "Applying a mapping left or reset the options form")
    }

    // X3: the scrim asks before discarding typed options, and again for an unapplied mapping.
    @Test
    fun dirtyOptionsAndMappingsAskBeforeTheScrimClosesThem() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pinFixture)
        app(state, pinFixture)
        tap("editor-project-toolbar-overflow")
        tap("menu-title-simulate")
        tap("menu-simulation-options")
        onNodeWithTag("simulation-options-limit").performTextReplacement("5000")
        waitForIdle()
        onNodeWithTag("editor-sheet-scrim").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        screenshot("options-dismiss-confirmation")
        val optionsAsk = exists("sheet-discard-dialog")
        val optionsKept = limitText()
        var mappingAsk = false
        if (optionsAsk) {
            tap("sheet-discard-confirm")
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            tap("editor-project-toolbar-overflow")
            tap("menu-title-simulate")
            tap("menu-simulation-options")
            onNodeWithText(text("mouse_mapping_title")).performClick()
            waitForIdle()
            chooseWireForThePrimaryButton()
            onNodeWithTag("editor-sheet-scrim").performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            mainClock.advanceTimeBy(1_000)
            waitForIdle()
            mappingAsk = exists("sheet-discard-dialog")
            screenshot("mapping-dismiss-confirmation")
        }
        observe("options-dismiss", "optionsAsk" to optionsAsk, "limitWhileAsking" to optionsKept, "mappingAsk" to mappingAsk)
        assertTrue(optionsAsk, "The scrim discarded typed options without asking")
        assertEquals("5000", optionsKept)
        assertTrue(mappingAsk, "The scrim discarded an unapplied mapping without asking")
    }

    // C68: TalkBack's activation, a finger sliding off, the window losing focus and the strip leaving the screen each
    // press, then release. (A synthetic cancel() is not delivered to gestures on the desktop host, so sliding off the
    // chip stands for a cancelled touch.)
    @Test
    fun buttonChipAlwaysReleases() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(buttonFixture)
        val events = mutableListOf<String>()
        state.onSimulationRequested = { snapshot, _ ->
            snapshot.events.forEach { (key, list) ->
                list.forEach { event -> if (event is IoInputEvent.Press) events += "$key:${if (event.pressed) "down" else "up"}" }
            }
            SimulationRequestResult.ACCEPTED
        }
        var visible by mutableStateOf(true)
        var focused by mutableStateOf(true)
        val window = object : WindowInfo {
            override val isWindowFocused: Boolean get() = focused
        }
        show {
            CompositionLocalProvider(LocalWindowInfo provides window) {
                if (visible) SignalPanel(state, EditorSignalRadix.HEXADECIMAL, rememberScrollState(), onEditInput = {})
            }
        }
        fun take(): List<String> = events.toList().also { events.clear() }
        onNodeWithTag("signal-input-btn").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        val talkBack = take()
        onNodeWithTag("signal-input-btn").performTouchInput { down(center) }
        waitForIdle()
        val held = take()
        onNodeWithTag("signal-input-btn").performTouchInput { moveBy(Offset(0f, 400f)) }
        waitForIdle()
        val slidOff = take()
        onNodeWithTag("signal-input-btn").performTouchInput { up() }
        waitForIdle()
        take()
        onNodeWithTag("signal-input-btn").performTouchInput { down(center) }
        waitForIdle()
        runOnIdle { focused = false }
        waitForIdle()
        val focusLost = take()
        onNodeWithTag("signal-input-btn").performTouchInput { up() }
        runOnIdle { focused = true }
        waitForIdle()
        take()
        onNodeWithTag("signal-input-btn").performTouchInput { down(center) }
        waitForIdle()
        runOnIdle { visible = false }
        waitForIdle()
        val leftScreen = take()
        observe("signals-button-release", "talkBack" to talkBack, "held" to held, "slidOff" to slidOff,
            "focusLost" to focusLost, "leftScreen" to leftScreen)
        assertEquals(listOf("btn:down", "btn:up"), talkBack)
        assertEquals(listOf("btn:down"), held)
        assertEquals(listOf("btn:up"), slidOff, "Sliding off the Button left it pressed")
        assertEquals(listOf("btn:down", "btn:up"), focusLost, "Losing the window focus left the Button pressed")
        assertEquals(listOf("btn:down", "btn:up"), leftScreen, "The Button stayed pressed after the strip closed")
    }

    // C68: tapping a read-only value selects its component and fits the view on it.
    @Test
    fun readOnlyChipRevealsItsComponent() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(buttonFixture)
        show { SignalPanel(state, EditorSignalRadix.HEXADECIMAL, rememberScrollState(), onEditInput = {}) }
        onNodeWithTag("signal-value-out").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        val selected = state.selectedIds
        val fit = state.fitSelection
        observe("signals-reveal", "selected" to selected, "fitRequested" to fit)
        assertEquals(setOf("out"), selected)
        assertTrue(fit, "The view was not fitted on the component")
    }

    // C69: Undo announces itself as a polite live region, then goes away after its timeout.
    @Test
    fun resetUndoAnnouncesItselfThenExpires() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pinFixture)
        app(state, pinFixture)
        runOnIdle { state.toggle("a") }
        waitFor { state.rootSimulation.componentValues["a"] == LogicValue.HIGH }
        tap("tool-reset")
        waitFor { exists("simulation-reset-undo") }
        val offered = exists("simulation-reset-undo")
        val liveRegion = if (!offered) null else
            onNodeWithTag("simulation-reset-undo").fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion)
        mainClock.advanceTimeBy(ResetUndoMillis - 1_000)
        waitForIdle()
        val beforeTimeout = exists("simulation-reset-undo")
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        val afterTimeout = exists("simulation-reset-undo")
        observe("reset-undo-expiry", "offered" to offered, "liveRegion" to liveRegion, "shownBeforeTimeout" to beforeTimeout,
            "shownAfterTimeout" to afterTimeout)
        assertTrue(offered && beforeTimeout)
        assertTrue(!afterTimeout, "Undo outlived its timeout")
        assertEquals(LiveRegionMode.Polite, liveRegion, "Undo appears without an announcement")
    }

    // C67: the compact warning count is an icon and a number, not a text glyph.
    @Test
    fun compactWarningCountDrawsAnIcon() = runDesktopComposeUiTest(800, 380) {
        val state = EditorState(warningFixture)
        app(state, warningFixture)
        screenshot("warnings-compact-icon")
        val indicator = exists("editor-simulation-warnings")
        val glyphs = texts().filter { it.contains("⚠") }
        // The indicator's own text is hidden from TalkBack, so the unmerged tree is where the icon shows.
        val icon = onAllNodesWithTag("editor-simulation-warnings-icon", useUnmergedTree = true).fetchSemanticsNodes().size
        observe("warnings-compact-icon", "indicator" to indicator, "glyphTexts" to glyphs, "iconNodes" to icon)
        assertTrue(indicator)
        assertTrue(glyphs.isEmpty(), "Hardcoded glyph: $glyphs")
        assertNotEquals(0, icon, "No warning icon")
    }
}
