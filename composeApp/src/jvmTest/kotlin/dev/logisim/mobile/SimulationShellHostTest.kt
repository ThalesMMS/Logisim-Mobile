package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import dev.logisim.mobile.core.IoButtonState
import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The editor shell around a running simulation (W2-03): navigating into an instance keeps the run (C58), warnings are
 * reachable on short windows through one dialog (C67), the Signals strip names, holds and announces its pins (C68),
 * Reset can be undone (C69), and Simulation options, mouse mappings and the project toolbar keep their drafts (X3).
 * Each scenario writes what it observed to `behavior-<scenario>.txt` beside its screenshots before asserting.
 */
@OptIn(ExperimentalTestApi::class)
class SimulationShellHostTest {
    private val instanceFixture = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Clock" logisim-mobile-id="clk"><a name="label" val="CLK"/></comp>
    <comp lib="0" loc="(200,100)" name="Pin" logisim-mobile-id="q"><a name="facing" val="west"/><a name="type" val="output"/><a name="label" val="Q"/></comp>
    <wire from="(100,100)" to="(200,100)"/>
    <comp loc="(300,300)" name="inner" logisim-mobile-id="sub"><a name="label" val="S"/></comp>
  </circuit>
  <circuit name="inner">
    <comp lib="0" loc="(100,100)" name="Clock" logisim-mobile-id="iclk"><a name="label" val="ICLK"/></comp>
    <comp lib="0" loc="(200,100)" name="Pin" logisim-mobile-id="iq"><a name="facing" val="west"/><a name="type" val="output"/><a name="label" val="IQ"/></comp>
    <wire from="(100,100)" to="(200,100)"/>
  </circuit>
</project>
"""

    /** A propagation limit of 1 cannot settle a gate, so the simulation reports a warning (AnalysisSimulationOptionsTest). */
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

    private val signalsFixture = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#I/O" name="5"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="in1"/>
    <comp lib="0" loc="(100,200)" name="Pin" logisim-mobile-id="in2"/>
    <comp lib="0" loc="(100,300)" name="Pin" logisim-mobile-id="data"><a name="width" val="8"/><a name="label" val="D"/></comp>
    <comp lib="5" loc="(100,400)" name="Button" logisim-mobile-id="btn"><a name="label" val="B"/></comp>
    <comp lib="0" loc="(300,100)" name="Pin" logisim-mobile-id="out"><a name="facing" val="west"/><a name="type" val="output"/></comp>
    <wire from="(100,100)" to="(300,100)"/>
  </circuit>
</project>
"""

    private val emptyFixture = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Gates" name="1"/>
  <main name="main"/>
  <circuit name="main"><comp lib="1" loc="(200,100)" name="NOT Gate"/></circuit>
</project>
"""

    private val resetFixture = """<?xml version="1.0" encoding="UTF-8"?>
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

    private fun ComposeUiTest.app(state: EditorState, xml: String, language: AppLanguage = AppLanguage.ENGLISH) {
        val header = EditorWorkspaceHeader("", "").also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
        }
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) { App(initialXml = xml, language = language, workspaceHeader = header) }
            }
        }
        waitForIdle()
        // The short fixtures leave attributes to their version defaults, which the import report lists on open.
        runOnIdle { state.dismissImportReport() }
        waitForIdle()
    }

    private fun observe(scenario: String, vararg facts: Pair<String, Any?>) {
        HostScreenshots.directory.mkdirs()
        File(HostScreenshots.directory, "behavior-$scenario.txt")
            .writeText(facts.joinToString("\n", postfix = "\n") { (name, value) -> "$name\t$value" })
    }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.description(tag: String): String? = if (!exists(tag)) null else
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    private fun ComposeUiTest.tap(tag: String) {
        runCatching { onNodeWithTag(tag).performScrollTo() }
        // The semantics click: a mouse click on a chrome button can land on its hover tooltip instead.
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.texts(): List<String> = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
        .fetchSemanticsNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun ComposeUiTest.waitFor(timeout: Long = 5_000, condition: () -> Boolean): Boolean =
        runCatching { waitUntil(timeoutMillis = timeout, condition = condition) }.isSuccess

    private val pause = message(Messages.ActionPause).resolve(AppLanguage.ENGLISH)

    // C58: double-tapping into an instance keeps the run; only a circuit outside the running one pauses, and says so.
    @Test
    fun navigatingIntoAnInstanceKeepsTheRun() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(instanceFixture)
        app(state, instanceFixture)
        tap("tool-run")
        waitFor { description("tool-run") == pause }
        runOnIdle { state.viewSubcircuitInstance("sub") }
        waitForIdle()
        val ticks = state.simulation.ticks
        val path = state.simulation.instancePath
        val advanced = waitFor(3_000) { state.simulation.ticks >= ticks + 3 }
        val running = description("tool-run") == pause
        screenshot("instance-view-running")
        runOnIdle { state.selectCircuit("inner") }
        waitForIdle()
        val pausedOutside = description("tool-run") != pause
        val status = runOnIdle { state.displayedStatusText.resolve(AppLanguage.ENGLISH) }
        screenshot("other-circuit-paused")
        observe("navigation-run", "instancePath" to path, "runningInInstance" to running, "instanceTicksAdvanced" to advanced,
            "pausedOnOtherCircuit" to pausedOutside, "status" to status)
        assertEquals("sub", path)
        assertTrue(running, "Entering the instance paused the run")
        assertTrue(advanced, "The instance view froze while running")
        assertTrue(pausedOutside)
        assertTrue(status.startsWith("Simulation paused"), status)
    }

    // C67: a short landscape window shows a warning button; it, the banner and the menu open the same explained dialog.
    @Test
    fun warningsAreReachableOnShortWindows() = runDesktopComposeUiTest(800, 380) {
        val state = EditorState(warningFixture)
        app(state, warningFixture)
        val notices = state.simulation.notices.map { it.resolve(AppLanguage.ENGLISH) }.distinct()
        screenshot("warnings-landscape")
        val label = dev.logisim.mobile.core.localization.LocalizedText(Messages.A11ySimulationWarningCount,
            mapOf("count" to notices.size.toString()), quantity = notices.size.toLong()).resolve(AppLanguage.ENGLISH)
        val indicator = onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()
        var explained = false
        if (indicator) {
            onAllNodesWithContentDescription(label).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            waitForIdle()
            screenshot("warnings-landscape-dialog")
            explained = texts().any { it == message(Messages.SimulationWarningsExplanation).resolve(AppLanguage.ENGLISH) }
        }
        observe("warnings-landscape", "notices" to notices, "indicator" to indicator, "dialogExplains" to explained)
        assertTrue(notices.isNotEmpty(), "The fixture must report a warning")
        assertTrue(indicator, "No warning indicator on a 380 dp high window")
        assertTrue(explained)
        assertTrue(texts().containsAll(notices))
    }

    @Test
    fun theMenuOpensTheExplainedWarningsDialog() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(warningFixture)
        app(state, warningFixture)
        screenshot("warnings-portrait-banner")
        tap("editor-project-toolbar-overflow")
        tap("menu-title-simulate")
        tap("menu-diagnostics")
        screenshot("warnings-portrait-menu-dialog")
        val explanation = message(Messages.SimulationWarningsExplanation).resolve(AppLanguage.ENGLISH)
        val explained = texts().any { it == explanation }
        observe("warnings-menu", "dialogExplains" to explained)
        assertTrue(explained, "The Simulate menu's warnings dialog lacks the explanation")
    }

    // C68: the strip tells unlabeled pins apart, announces multi-bit inputs as editors and holds a Button.
    @Test
    fun signalsStripNamesHoldsAndAnnounces() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(signalsFixture)
        app(state, signalsFixture)
        tap("tool-signals")
        screenshot("signals-strip")
        fun buttonText(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().let { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        }
        val first = buttonText("signal-input-in1")
        val second = buttonText("signal-input-in2")
        val data = buttonText("signal-input-data")
        runCatching { onNodeWithTag("signal-input-btn").performScrollTo() }
        onNodeWithTag("signal-input-btn").performTouchInput { down(center) }
        val held = waitFor(3_000) { (state.rootSimulation.componentStates["btn"] as? IoButtonState)?.pressed == true }
        mainClock.advanceTimeBy(600)
        val stillHeld = (state.rootSimulation.componentStates["btn"] as? IoButtonState)?.pressed == true
        screenshot("signals-strip-button-held")
        onNodeWithTag("signal-input-btn").performTouchInput { up() }
        val released = waitFor(3_000) { (state.rootSimulation.componentStates["btn"] as? IoButtonState)?.pressed == false }
        observe("signals-strip", "in1" to first, "in2" to second, "data" to data, "buttonHeld" to held,
            "buttonStillHeldAfter600ms" to stillHeld, "released" to released)
        assertTrue(first != second, "Unlabeled inputs read the same: $first / $second")
        assertTrue(data?.startsWith("Edit value") == true, "8-bit input announced as $data")
        assertTrue(held && stillHeld, "The Button was not held")
        assertTrue(released)
    }

    @Test
    fun signalsStripExplainsAnEmptyCircuit() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(emptyFixture)
        app(state, emptyFixture)
        tap("tool-signals")
        screenshot("signals-empty")
        val empty = texts().any { it == "No pins, probes or displays in this circuit" }
        observe("signals-empty", "emptyState" to empty)
        assertTrue(empty, "The Signals strip shows nothing for a circuit without pins")
    }

    @Test
    fun signalsSheetWrapsOnAShortWindow() = runDesktopComposeUiTest(800, 380) {
        val state = EditorState(signalsFixture)
        app(state, signalsFixture)
        tap("tool-signals")
        screenshot("signals-sheet-landscape")
        val bounds = listOf("signal-input-in1", "signal-input-btn").map { onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        observe("signals-sheet", "bounds" to bounds)
        bounds.forEach { assertTrue(it.right <= 800f && it.left >= 0f, "Chip outside the sheet: $it") }
    }

    // C69: Reset can be undone from the snackbar, which restores the poked input.
    @Test
    fun resetOffersUndo() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(resetFixture)
        app(state, resetFixture)
        runOnIdle { state.toggle("a") }
        val poked = waitFor { state.rootSimulation.componentValues["a"] == LogicValue.HIGH }
        tap("tool-reset")
        val reset = waitFor { state.rootSimulation.componentValues["a"] == LogicValue.LOW }
        screenshot("reset-snackbar")
        val undo = exists("simulation-reset-undo")
        var restored = false
        if (undo) {
            tap("simulation-reset-undo")
            restored = waitFor { state.rootSimulation.componentValues["a"] == LogicValue.HIGH }
            screenshot("reset-undone")
        }
        observe("reset-undo", "poked" to poked, "reset" to reset, "undoOffered" to undo, "restored" to restored)
        assertTrue(poked && reset)
        assertTrue(undo, "Reset offers no undo")
        assertTrue(restored)
    }

    // X3: the mouse-mapping detour keeps the typed options.
    @Test
    fun simulationOptionsSurviveTheMouseMappingDetour() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(resetFixture)
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        SimulationOptionsPanel(state.project, onApply = {}, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        waitForIdle()
        onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("5000")
        waitForIdle()
        onNodeWithText(message("mouse_mapping_title").resolve(AppLanguage.ENGLISH)).performClick()
        waitForIdle()
        screenshot("options-mouse-mappings")
        onNodeWithText(message(Messages.ActionCancel).resolve(AppLanguage.ENGLISH)).performClick()
        waitForIdle()
        screenshot("options-after-mouse-mappings")
        val limit = onAllNodes(hasSetTextAction()).onFirst().fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text
        observe("options-detour", "limitAfterReturn" to limit)
        assertEquals("5000", limit)
    }

    // X3: dismissing the project toolbar editor with unapplied changes asks first.
    @Test
    fun projectToolbarDraftAsksBeforeDismissal() = runDesktopComposeUiTest(390, 844) {
        val xml = HostScreenshots.fixture("fixtures/user-scenarios/editing-not.circ")
        val state = EditorState(xml)
        app(state, xml)
        tap("editor-project-toolbar-overflow")
        tap("menu-title-project")
        tap("menu-project-tool-project-toolbar")
        onNodeWithText(message("project_toolbar_add_separator").resolve(AppLanguage.ENGLISH)).performClick()
        waitForIdle()
        onNodeWithTag("editor-sheet-scrim").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        screenshot("toolbar-dismiss-confirmation")
        val confirmation = exists("sheet-discard-dialog")
        val sheet = exists("editor-tool-sheet")
        observe("toolbar-dismiss", "confirmation" to confirmation, "sheetStillOpen" to sheet)
        assertTrue(confirmation, "Dismissal discarded the toolbar draft without asking")
        assertTrue(sheet)
        // Keep editing leaves the draft; Apply from the confirmation writes what the panel's own Apply writes.
        val original = state.project
        tap("sheet-discard-keep")
        assertTrue(exists("editor-tool-sheet"))
        onNodeWithTag("editor-sheet-scrim").performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        tap("sheet-discard-apply")
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        val expected = dev.logisim.mobile.core.ProjectToolbar.apply(original,
            dev.logisim.mobile.core.ProjectToolbar.read(original) + dev.logisim.mobile.core.ProjectToolbarItem.Separator)
        assertEquals(dev.logisim.mobile.core.CircCodec.writeProject(expected, state.componentRegistry),
            dev.logisim.mobile.core.CircCodec.writeProject(state.project, state.componentRegistry))
        assertTrue(!exists("editor-tool-sheet"), "Apply from the confirmation closes the sheet")
        assertNotNull(state)
    }
}
