package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The phone simulation controls (#C19, C20, C53, C54, C61), the input value dialog (C52) and the default frequency
 * preference (C103), driven through the whole editor at 360x780 and 390x844 dp (and a #239 case at 320x568, 200%).
 * Each scenario writes what it observed to `behavior-<scenario>.txt` beside its screenshots (`-PuiScreenshots=<dir>`)
 * before asserting, so a run on the original code records the flaw.
 */
@OptIn(ExperimentalTestApi::class)
class SimulationControlsHostTest {
    private val fixture = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(120,120)" name="Pin">
      <a name="facing" val="east"/><a name="type" val="input"/><a name="width" val="8"/>
      <a name="appearance" val="NewPins"/><a name="label" val="A"/>
      <a name="behavior" val="simple"/><a name="initial" val="0x0"/>
      <a name="labelfont" val="SansSerif bold 16"/><a name="labelloc" val="west"/><a name="radix" val="10signed"/>
    </comp>
    <comp lib="0" loc="(300,120)" name="Pin">
      <a name="facing" val="west"/><a name="type" val="output"/><a name="width" val="8"/>
      <a name="appearance" val="NewPins"/><a name="label" val="Y"/>
      <a name="behavior" val="simple"/><a name="initial" val="0x0"/>
      <a name="labelfont" val="SansSerif bold 16"/><a name="labelloc" val="east"/><a name="radix" val="10signed"/>
    </comp>
    <wire from="(120,120)" to="(300,120)"/>
  </circuit>
</project>
"""

    /** Preferences kept in memory, as a device stores them. */
    private class MemoryPreferences(preferences: EditorPreferences) : PreferencesStorage {
        private var stored: String? = EditorPreferencesCodec.write(preferences)
        override fun load() = stored
        override fun save(serialized: String) { stored = serialized }
    }

    private fun ComposeUiTest.app(state: EditorState, language: AppLanguage, fontScale: Float = 1f,
                                  openProjects: (() -> Unit)? = null, preferences: EditorPreferences? = null) {
        val header = EditorWorkspaceHeader("", "", onOpenProjects = openProjects).also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
        }
        val storage = preferences?.let(::MemoryPreferences)
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAppLanguage provides language,
                LocalDensity provides Density(density.density, fontScale)) {
                LogisimTheme(dark = false) {
                    App(initialXml = fixture, language = language, workspaceHeader = header, preferencesStorage = storage)
                }
            }
        }
        waitForIdle()
    }

    private fun observe(scenario: String, vararg facts: Pair<String, Any?>) {
        HostScreenshots.directory.mkdirs()
        File(HostScreenshots.directory, "behavior-$scenario.txt")
            .writeText(facts.joinToString("\n", postfix = "\n") { (name, value) -> "$name\t$value" })
    }

    private fun text(key: String, language: AppLanguage, vararg arguments: Pair<String, Any?>) =
        message(key, *arguments).resolve(language)

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.displayed(tag: String) = exists(tag) && runCatching { onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    private fun ComposeUiTest.description(tag: String): String? = if (!exists(tag)) null else
        onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /** The tool buttons drawn in the window, in the order the tree lists them. */
    private fun ComposeUiTest.visibleTools(): List<String> = onAllNodes(SemanticsMatcher("tool button") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tool-") == true
    }).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        .filter { displayed(it) }

    private fun ComposeUiTest.textsShown(pattern: Regex): List<String> = onAllNodes(SemanticsMatcher("text") {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> pattern.matches(text.text) } == true
    }).fetchSemanticsNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        .filter { pattern.matches(it) }

    private fun EditorState.component(label: String) = document.components.first { it.label == label }

    private fun EditorState.screen(component: CircuitComponent): Offset {
        val bounds = component.bounds(componentRegistry)
        val x = (bounds.left + bounds.right) / 2f
        val y = (bounds.top + bounds.bottom) / 2f
        return Offset(x * viewport.view.scale + viewport.view.pan.x, y * viewport.view.scale + viewport.view.pan.y)
    }

    private fun frequencyLines(xml: String) = xml.lines().map(String::trim)
        .filter { "simulationFrequency" in it || "tickFrequencyHz" in it }

    // C19, C61: Run, Tick and Reset stay on the bar; one overflow names the simulation controls.
    private fun simulationBar(width: Int, height: Int, language: AppLanguage, name: String, fontScale: Float = 1f) =
        runDesktopComposeUiTest(width, height) {
            val state = EditorState(fixture)
            app(state, language, fontScale)
            screenshot("sim-bar-$name")
            val tools = visibleTools()
            val overflow = description("editor-simulation-toolbar-overflow")
            val canvasHeight = onNodeWithTag("editor-canvas").fetchSemanticsNode().size.height
            val tickShown = displayed("tool-step")
            val chipWidth = if (displayed("tool-frequency")) onNodeWithTag("tool-frequency").fetchSemanticsNode().size.width else null
            repeat(3) { if (exists("tool-step")) tap("tool-step") }
            // Steps are computed off the main thread; give the queue time to publish all three.
            runCatching { waitUntil(timeoutMillis = 5_000) { state.simulation.ticks.toLong() >= 3 } }
            waitForIdle()
            val counter = textsShown(Regex("""\D+ \d+"""))
            val statusLines = textLayout("editor-status").lineCount
            screenshot("sim-bar-$name-after-3-ticks")
            observe("sim-bar-$name", "visibleTools" to tools, "overflowDescription" to overflow,
                "tickVisibleWithoutOverflow" to tickShown, "resetVisibleWithoutOverflow" to displayed("tool-reset"),
                "ticks" to state.simulation.ticks, "counterTexts" to counter, "canvasHeightPx" to canvasHeight,
                "fontScale" to fontScale, "frequencyChipWidthPx" to chipWidth, "statusLines" to statusLines)
            listOf("tool-run", "tool-step", "tool-reset").forEach { onNodeWithTag(it).assertIsDisplayed() }
            assertTrue(tickShown)
            overflow?.let { described ->
                for (other in AppLanguage.entries.filter { it.isAvailable }) {
                    assertFalse(text(Messages.SimulationOptionsTitle, other) in described, described)
                }
                assertTrue(text("simulation_controls_title", language) in described, described)
            }
            assertEquals(3L, state.simulation.ticks.toLong())
            onNodeWithText(text("simulation_ticks", language, "count" to 3)).assertIsDisplayed()
            // #239: the bars leave the circuit most of the window.
            assertTrue(canvasHeight > height / 3, "canvas $canvasHeight px of $height")
            // A wrapped hint stays within the 48 dp row Signals and the zoom already take: 11 sp lines, 1.25 em apart.
            assertTrue(statusLines * 11f * 1.25f * fontScale <= 48f, "$statusLines status lines at $fontScale")
        }

    @Test fun simulationBarAt360() = simulationBar(360, 780, AppLanguage.ENGLISH, "360")
    @Test fun simulationBarAt390() = simulationBar(390, 844, AppLanguage.PORTUGUESE, "390")
    @Test fun simulationBarAt320LargeFont() = simulationBar(320, 568, AppLanguage.PORTUGUESE, "320-font200", fontScale = 2f)

    // C20, C53, C54: the frequency control is a picker; choosing keeps the run, the selection and the undo history.
    private fun frequencyPicker(width: Int, height: Int, language: AppLanguage, name: String) =
        runDesktopComposeUiTest(width, height) {
            val state = EditorState(fixture)
            app(state, language)
            val input = state.component("A")
            state.select(input.id)
            waitForIdle()
            tap("tool-run")
            val pause = text(Messages.ActionPause, language)
            runCatching { waitUntil(timeoutMillis = 5_000) { description("tool-run") == pause } }
            val runningBefore = description("tool-run")
            val undoBefore = state.undoHistory.map { it.label.resolve(AppLanguage.ENGLISH) }
            val xmlBefore = frequencyLines(state.exportXml())
            val revisionBefore = state.revision
            var opened = "chip"
            if (!displayed("tool-frequency") && exists("editor-simulation-toolbar-overflow")) {
                // The original bar folds the frequency behind its expand button.
                tap("editor-simulation-toolbar-overflow")
                opened = "fold"
            }
            val chipDescription = description("tool-frequency")
            tap("tool-frequency")
            val menuShown = exists("tick-frequency-2.0")
            screenshot("frequency-menu-$name")
            val offered = listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, 64.0, 512.0, 1000.0, 2000.0)
                .filter { exists("tick-frequency-$it") }
            if (menuShown) {
                onNodeWithTag("tick-frequency-2.0").performScrollTo().performClick()
                waitForIdle()
            }
            // The chip's visible label: the text merged into the clickable chip.
            val chipAfter = if (exists("tool-frequency")) onNodeWithTag("tool-frequency").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text } else null
            screenshot("frequency-chosen-$name")
            val runAfter = description("tool-run")
            val undoAfter = state.undoHistory.map { it.label.resolve(AppLanguage.ENGLISH) }
            val xmlAfter = frequencyLines(state.exportXml())
            observe("frequency-$name", "openedThrough" to opened, "chipDescriptionBefore" to chipDescription,
                "menuOfFrequencies" to menuShown, "offered" to offered,
                "runBefore" to runningBefore, "runAfter" to runAfter,
                "frequencyAfter" to dev.logisim.mobile.core.SimulationProjectOptions.read(state.project).tickFrequencyHz,
                "chipTextAfter" to chipAfter, "undoBefore" to undoBefore, "undoAfter" to undoAfter,
                "selectionAfter" to state.selectedIds, "revisionBefore" to revisionBefore, "revisionAfter" to state.revision,
                "xmlFrequencyBefore" to xmlBefore, "xmlFrequencyAfter" to xmlAfter)
            File(HostScreenshots.directory, "frequency-$name-after.circ").writeText(state.exportXml())
            assertEquals("chip", opened, "the frequency is on the bar, not behind a fold")
            assertTrue(menuShown)
            assertEquals(text(Messages.ActionPause, language), runningBefore)
            assertEquals(text("simulation_tick_frequency_value", language, "value" to "4 Hz"), chipDescription)
            assertTrue(listOf(0.25, 0.5, 1.0, 2.0, 4.0, 1000.0).all { it in offered }, "$offered")
            assertFalse(2000.0 in offered, "the run loop does not sustain 2 kHz")
            assertEquals(2.0, dev.logisim.mobile.core.SimulationProjectOptions.read(state.project).tickFrequencyHz)
            assertEquals(text("simulation_tick_frequency_value", language, "value" to "2 Hz"), description("tool-frequency"))
            assertNotNull(chipAfter)
            assertTrue("2 Hz" in chipAfter, chipAfter)
            // Still running: the controller restarted at the new rate instead of pausing.
            assertEquals(text(Messages.ActionPause, language), runAfter)
            assertEquals(undoBefore, undoAfter)
            assertEquals(setOf(input.id), state.selectedIds)
            assertTrue(state.revision > revisionBefore, "the change must be saved")
            assertTrue(xmlAfter.any { it == """<a name="simulationFrequency" val="2.0"/>""" }, "$xmlAfter")
            tap("tool-run")
        }

    @Test fun frequencyPickerAt360() = frequencyPicker(360, 780, AppLanguage.ENGLISH, "360")
    @Test fun frequencyPickerAt390() = frequencyPicker(390, 844, AppLanguage.PORTUGUESE, "390")

    // C52: the value dialog follows the pin's radix, selects the old value, focuses and applies on Done.
    private fun signedInput(width: Int, height: Int, language: AppLanguage, name: String) =
        runDesktopComposeUiTest(width, height) {
            val state = EditorState(fixture)
            app(state, language)
            val input = state.component("A")
            state.setInputValue(input.id, 0xFDuL)
            runCatching { waitUntil(timeoutMillis = 5_000) { state.simulation.vectorValues[input.id]?.toULongOrNull() == 0xFDuL } }
            waitForIdle()
            onNodeWithTag("editor-canvas").performTouchInput { click(state.screen(input)) }
            waitForIdle()
            screenshot("input-dialog-$name")
            val field = onNode(hasSetTextAction())
            val config = field.fetchSemanticsNode().config
            val initial = config.getOrNull(SemanticsProperties.EditableText)?.text
            val selection = config.getOrNull(SemanticsProperties.TextSelectionRange)
            val focused = config.getOrNull(SemanticsProperties.Focused)
            val supporting = textsShown(Regex(".*(-128|127|bits).*"))
            val title = textsShown(Regex(".*\\bA\\b.*"))
            field.performTextInput("7")
            waitForIdle()
            val typed = field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
            screenshot("input-dialog-$name-typed")
            val ime = runCatching { field.performImeAction() }
            runCatching { waitUntil(timeoutMillis = 5_000) { state.simulation.vectorValues[input.id]?.toULongOrNull() == 7uL } }
            waitForIdle()
            val applied = state.simulation.vectorValues[input.id]
            observe("input-$name", "initialText" to initial, "selection" to selection, "focused" to focused,
                "supportingTexts" to supporting, "titles" to title, "typed" to typed,
                "imeAction" to (ime.exceptionOrNull()?.message?.lineSequence()?.firstOrNull() ?: "applied"),
                "vectorAfter" to applied?.toULongOrNull(), "dialogOpen" to runCatching { onNode(hasSetTextAction()).assertExists() }.isSuccess)
            assertEquals("-3", initial)
            assertEquals(TextRange(0, 2), selection)
            assertEquals(true, focused)
            assertEquals("7", typed)
            assertTrue(ime.isSuccess, "${ime.exceptionOrNull()}")
            assertEquals(LogicVector.of(8, 7uL), applied)
            assertTrue(onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty(), "Done closes the dialog")
        }

    @Test fun signedInputAt360() = signedInput(360, 780, AppLanguage.ENGLISH, "360")
    @Test fun signedInputAt390() = signedInput(390, 844, AppLanguage.PORTUGUESE, "390")

    // C103: the default frequency is chosen from the same list and applies at once.
    private fun preferencesFrequency(width: Int, height: Int, language: AppLanguage, name: String) =
        runDesktopComposeUiTest(width, height) {
            var preferences by mutableStateOf(EditorPreferences())
            val changes = mutableListOf<Double>()
            setContent {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    LogisimTheme(dark = false) {
                        Surface(Modifier.fillMaxSize()) {
                            EditorPreferencesPanel(preferences, { next ->
                                if (next.defaultTickFrequencyHz != preferences.defaultTickFrequencyHz) changes += next.defaultTickFrequencyHz
                                preferences = next
                            }, {}, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
            waitForIdle()
            val row = if (exists("preferences-default-frequency")) onNodeWithTag("preferences-default-frequency")
                else onNodeWithText(text(Messages.PreferencesDefaultFrequency, language))
            row.performScrollTo()
            waitForIdle()
            screenshot("preferences-frequency-$name")
            row.performClick()
            waitForIdle()
            val eight = onAllNodesWithText("8 Hz").fetchSemanticsNodes().isNotEmpty()
            screenshot("preferences-frequency-$name-menu")
            if (eight) {
                onNodeWithText("8 Hz").performScrollTo().performClick()
                waitForIdle()
            }
            val textFields = onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
            screenshot("preferences-frequency-$name-chosen")
            observe("preferences-frequency-$name", "menuOffers8Hz" to eight, "changes" to changes,
                "stored" to preferences.defaultTickFrequencyHz, "textFieldsInPanel" to textFields)
            assertTrue(eight)
            assertEquals(listOf(8.0), changes)
            assertEquals(8.0, preferences.defaultTickFrequencyHz)
            assertEquals(0, textFields, "the default frequency is chosen from the list, not typed")
        }

    @Test fun preferencesFrequencyAt360() = preferencesFrequency(360, 780, AppLanguage.ENGLISH, "360")
    @Test fun preferencesFrequencyAt390() = preferencesFrequency(390, 844, AppLanguage.PORTUGUESE, "390")

    // C54: a stored rate outside Evolution's list stays checked as custom and round-trips unchanged.
    @Test
    fun anImportedFastRateStaysCheckedAsCustom() = runDesktopComposeUiTest(390, 844) {
        val fast = fixture.replace("""  <circuit name="main">""", """  <circuit name="main">
    <a name="simulationFrequency" val="4000.0"/>""")
        val state = EditorState(fast)
        val header = EditorWorkspaceHeader("", "").also {
            it.session = WorkspaceEditorSessionBinding(state, state.exportViewState(), retain = {}, open = { _, _, _ -> false })
        }
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) {
                LogisimTheme(dark = false) { App(initialXml = fast, language = AppLanguage.ENGLISH, workspaceHeader = header) }
            }
        }
        waitForIdle()
        val xml = state.exportXml()
        val description = description("tool-frequency")
        tap("tool-frequency")
        val custom = onNodeWithTag("tick-frequency-4000.0").fetchSemanticsNode().config
        // The checked rate is the last entry, below the fold: the menu opens with it in view.
        onNodeWithTag("tick-frequency-4000.0").assertIsDisplayed()
        screenshot("frequency-custom-4khz-menu")
        observe("frequency-custom", "chipDescription" to description,
            "customSelected" to custom.getOrNull(SemanticsProperties.Selected),
            "customText" to custom.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
        assertEquals(true, custom.getOrNull(SemanticsProperties.Selected))
        assertTrue(onAllNodesWithText("4 kHz (custom)").fetchSemanticsNodes().isNotEmpty())
        assertFalse(exists("tick-frequency-2000.0"))
        assertEquals("Tick frequency, 4 kHz", description)
        // Choosing the checked entry again changes nothing: the imported value round-trips.
        onNodeWithTag("tick-frequency-4000.0").performClick()
        waitForIdle()
        assertEquals(xml, state.exportXml())
        assertEquals(0, state.revision)
    }

    // C54: above the 1 kHz the run loop sustains, the chip shows the measured rate. Rendered alone: a host test cannot
    // idle while the editor's 1 ms loop runs.
    @Test
    fun aRateAboveTheSustainedOneShowsTheMeasuredRate() = runDesktopComposeUiTest(390, 200) {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Column {
                            TickFrequencyChip(TickFrequencyControl(4000.0, true, 812.4) {}, androidx.compose.ui.unit.Dp(80f))
                            TickFrequencyChip(TickFrequencyControl(512.0, true, 498.0) {}, androidx.compose.ui.unit.Dp(80f), tag = "sustained")
                        }
                    }
                }
            }
        }
        waitForIdle()
        screenshot("frequency-measured-chip")
        val measured = onAllNodesWithTag("tool-frequency-measured", useUnmergedTree = true).fetchSemanticsNodes()
        observe("frequency-measured", "description" to description("tool-frequency"), "measuredLabels" to measured.size)
        assertEquals("Tick frequency, 4 kHz. Measured: 812 ticks/s", description("tool-frequency"))
        // One measured label: a sustained rate shows none.
        assertEquals(1, measured.size)
        assertEquals("Tick frequency, 512 Hz", description("sustained"))
    }

    // Simulation options: the same list in an exposed drop-down; a rate chosen there stays one undoable project edit.
    @Test
    fun simulationOptionsOfferTheSameRates() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture.replace("""  <circuit name="main">""", """  <circuit name="main">
    <a name="simulationFrequency" val="3.0"/>"""))
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        SimulationOptionsPanel(state.project, onApply = { state.applyProject(it) }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        waitForIdle()
        onNodeWithTag("simulation-options-frequency").performClick()
        waitForIdle()
        screenshot("simulation-options-frequency-menu")
        val custom = onNodeWithTag("simulation-options-frequency-3.0").fetchSemanticsNode().config
        assertEquals(true, custom.getOrNull(SemanticsProperties.Selected))
        onNodeWithTag("simulation-options-frequency-8.0").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText(text("simulation_options_apply", AppLanguage.ENGLISH)).performScrollTo().performClick()
        waitForIdle()
        screenshot("simulation-options-frequency-applied")
        assertEquals(8.0, dev.logisim.mobile.core.SimulationProjectOptions.read(state.project).tickFrequencyHz)
        assertEquals(listOf("Edit project"), state.undoHistory.map { it.label.resolve(AppLanguage.ENGLISH) })
        state.undo()
        assertEquals(3.0, dev.logisim.mobile.core.SimulationProjectOptions.read(state.project).tickFrequencyHz)
    }

    private fun ComposeUiTest.bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().let { node ->
        node.positionInRoot.x to node.positionInRoot.x + node.size.width
    }

    private fun ComposeUiTest.verticalBounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().let { node ->
        node.positionInRoot.y to node.positionInRoot.y + node.size.height
    }

    private fun ComposeUiTest.textLayout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private val headerSimulationControls = listOf("tool-run", "tool-event", "tool-step", "tool-tick-cycle", "tool-frequency",
        "tool-automatic-ticks", "tool-reset", "editor-simulation-toolbar-overflow")

    // C19 on short landscape windows, where a side toolbar puts the controls in the header: Undo and Redo keep the right
    // edge, Run, Tick and Reset stay on the bar, and a growing count never changes which controls the bar shows. Every
    // control keeps its 48 dp target, the title its 48 dp Open projects target, and the count stays whole: as "Ticks N",
    // or as N alone (read as "Ticks N") where the header has no room for the word.
    private fun headerControls(width: Int, height: Int, name: String, ticks: Long, fontScale: Float = 1f,
                               preferences: EditorPreferences? = null, language: AppLanguage = AppLanguage.ENGLISH) =
        runDesktopComposeUiTest(width, height) {
            val state = EditorState(fixture)
            var projectsOpened = 0
            app(state, language, fontScale, openProjects = { projectsOpened++ }, preferences = preferences)
            screenshot("sim-header-$name")
            val before = headerSimulationControls.filter { displayed(it) }
            val widthsBefore = before.associateWith { onNodeWithTag(it).fetchSemanticsNode().size.width }
            val header = bounds("editor-project-toolbar")
            val headerRows = verticalBounds("editor-project-toolbar")
            val redoBefore = bounds("tool-redo")
            val titleBefore = bounds("editor-header-title")
            state.publishRun(state.captureRunSnapshot(), state.simulation.copy(ticks = ticks))
            waitForIdle()
            screenshot("sim-header-$name-$ticks-ticks")
            val after = headerSimulationControls.filter { displayed(it) }
            val widthsAfter = after.associateWith { onNodeWithTag(it).fetchSemanticsNode().size.width }
            val redoAfter = bounds("tool-redo")
            val title = bounds("editor-header-title")
            val group = bounds("editor-simulation-toolbar")
            val groupRows = verticalBounds("editor-simulation-toolbar")
            val counter = bounds("editor-simulation-ticks")
            val counterLayout = textLayout("editor-simulation-ticks")
            val counterText = text("simulation_ticks", language, "count" to ticks)
            val shown = counterLayout.layoutInput.text.text
            val counterDescription = description("editor-simulation-ticks")
            // The whole count on its one line: nothing wrapped past maxLines, nothing ellipsized or wider than the label.
            val counterWhole = counterLayout.lineCount == 1 && !counterLayout.multiParagraph.didExceedMaxLines &&
                !counterLayout.isLineEllipsized(0) && counterLayout.getLineEnd(0) == shown.length &&
                counterLayout.multiParagraph.getLineRight(0) <= counterLayout.size.width + 0.5f
            observe("sim-header-$name", "headerPx" to header, "redoBeforePx" to redoBefore, "redoAfterPx" to redoAfter,
                "groupPx" to group, "counterPx" to counter, "counterShown" to shown, "counterDescription" to counterDescription,
                "counterWhole" to counterWhole, "titleBeforePx" to titleBefore, "titlePx" to title,
                "controlsBefore" to before, "controlsAfter" to after, "controlWidthsBeforePx" to widthsBefore,
                "controlWidthsAfterPx" to widthsAfter, "ticks" to state.simulation.ticks, "fontScale" to fontScale)
            listOf("tool-run", "tool-step", "tool-reset").forEach { onNodeWithTag(it).assertIsDisplayed() }
            // The controls are in the header row, not a bar of their own.
            assertTrue(groupRows.first >= headerRows.first && groupRows.second <= headerRows.second, "group $groupRows in $headerRows")
            // The header row's 4 dp end padding is all that follows Redo (host tests run at density 1).
            assertTrue(kotlin.math.abs(header.second - 4f - redoBefore.second) <= 1f, "redo $redoBefore in $header")
            assertEquals(redoBefore, redoAfter)
            assertEquals(before, after)
            // Every control the bar shows keeps the 48 dp touch target, whatever the count.
            assertTrue((widthsBefore.values + widthsAfter.values).all { it >= 48 }, "$widthsBefore $widthsAfter")
            assertTrue("editor-simulation-toolbar-overflow" in after || after.size == headerSimulationControls.size - 1, "$after")
            // The title keeps its 48 dp circuit picker target.
            assertTrue(title.second - title.first >= 48f, "title $title")
            assertTrue(counterWhole, "the count is shown whole: $shown")
            if (shown == counterText) assertEquals(null, counterDescription)
            else {
                assertEquals(ticks.toString(), shown)
                assertEquals(counterText, counterDescription)
            }
            // The counter and the bar are one tagged node, which automation reads the count from.
            assertTrue(counter.first >= group.first && counter.second <= group.second, "counter $counter in $group")
            val run = bounds("tool-run")
            assertTrue(run.first >= group.first && run.second <= group.second, "run $run in $group")
            assertTrue(title.second <= group.first, "title $title before $group")
            // The title opens the circuit picker (C27), whose Projects… entry returns to the project list.
            onNodeWithTag("editor-header-title").performClick()
            waitForIdle()
            assertEquals(0, projectsOpened)
            onNodeWithTag("menu-circuit-picker-projects").performClick()
            assertEquals(1, projectsOpened)
        }

    @Test fun shortLandscapeHeaderKeepsUndoAtTheEdge844() = headerControls(844, 390, "844x390", ticks = 123_456_789)
    @Test fun shortLandscapeHeaderKeepsUndoAtTheEdge915() = headerControls(915, 412, "915x412", ticks = 123_456_789)
    @Test fun narrowHeaderAtLargeFontKeepsItsControls() = headerControls(640, 400, "640x400-font200", ticks = 8_888_888, fontScale = 2f)
    // 600 dp at 200 %: the bar, the counter's reserve and the title do not all fit; the bar keeps its 48 dp buttons.
    @Test fun header600AtLargeFontKeepsTouchTargets() = headerControls(600, 400, "600x400-font200", ticks = 8_888_888, fontScale = 2f)
    // A tablet with a side toolbar keeps the menu bar beside the controls in the header: the bar leaves the title and the
    // counter their room, so seven digits never squeeze the title below its target.
    @Test fun tabletHeaderWithMenuBarKeepsTheTitle() = headerControls(1024, 768, "1024x768-left-toolbar", ticks = 8_888_888,
        preferences = EditorPreferences(toolbarPlacement = EditorToolbarPlacement.LEFT))
    // R0708-1: in Portuguese at 200% the menu bar no longer fits beside the controls, so it folds into the compact menu
    // and the title and counter keep their room.
    @Test fun tabletHeaderAtLargeFontFoldsTheMenuBar() = headerControls(1024, 768, "1024x768-left-toolbar-pt-font200",
        ticks = 8_888_888, fontScale = 2f, preferences = EditorPreferences(toolbarPlacement = EditorToolbarPlacement.LEFT),
        language = AppLanguage.PORTUGUESE)

    // C61: the Simulate menu lists the bar's actions in its order, Reset apart, then the rates under a heading.
    @Test
    fun theSimulateMenuFollowsTheBarOrder() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(fixture)
        app(state, AppLanguage.ENGLISH)
        tap("editor-project-toolbar-overflow")
        tap("menu-title-simulate")
        screenshot("simulate-menu")
        val expected = listOf("menu-run", "menu-event", "menu-step", "menu-tick-cycle", "menu-automatic-ticks", "menu-reset",
            "menu-simulate-frequency", "menu-frequency-0.25")
        val tags = onAllNodes(SemanticsMatcher("menu entry") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("menu-") == true
        }).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
        val heading = onNodeWithTag("menu-simulate-frequency").fetchSemanticsNode().config
        val autoTick = onNodeWithTag("menu-automatic-ticks").fetchSemanticsNode()
        val reset = onNodeWithTag("menu-reset").fetchSemanticsNode()
        val gap = reset.positionInRoot.y - (autoTick.positionInRoot.y + autoTick.size.height)
        observe("simulate-menu", "tags" to tags, "headingText" to heading.getOrNull(SemanticsProperties.Text)?.joinToString { it.text },
            "heading" to heading.contains(SemanticsProperties.Heading), "gapBeforeResetPx" to gap)
        assertEquals(expected, tags.filter { it in expected })
        assertTrue(heading.contains(SemanticsProperties.Heading))
        assertEquals(text("simulation_tick_frequency", AppLanguage.ENGLISH), heading.getOrNull(SemanticsProperties.Text)?.joinToString { it.text })
        // A divider (1 dp and 4 dp above and below) sets Reset apart.
        assertTrue(gap >= 8f, "gap $gap")
        assertEquals(text(Messages.A11yResetSimulation, AppLanguage.ENGLISH),
            reset.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString())
    }

    // The full simulation controls of a wide window, in Evolution's order.
    @Test
    fun tabletShowsEverySimulationControl() = runDesktopComposeUiTest(1180, 820) {
        val state = EditorState(fixture)
        app(state, AppLanguage.ENGLISH)
        screenshot("sim-bar-tablet")
        val tools = visibleTools()
        observe("sim-bar-tablet", "visibleTools" to tools)
        val expected = listOf("tool-run", "tool-event", "tool-step", "tool-tick-cycle", "tool-frequency", "tool-automatic-ticks", "tool-reset")
        expected.forEach { onNodeWithTag(it).assertIsDisplayed() }
        assertEquals(expected, tools.filter { it in expected })
    }
}
