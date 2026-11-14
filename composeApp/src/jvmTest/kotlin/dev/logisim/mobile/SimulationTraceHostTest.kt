package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.TraceRecorder
import dev.logisim.mobile.core.TraceSignal
import dev.logisim.mobile.core.TraceSignalReference
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.localization.LocalAppLanguage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Signal recording (W2-03): the trace keeps the simulation running and steps it from the panel (C57), buses show
 * their values with a time ruler and the table keeps its columns aligned (C62), the waveform pinches, drags and sets
 * either cursor (C63), the picker lists loggable signals by circuit without internal keys (C64), and a RAM range is
 * added in one hexadecimal entry (X6). Each scenario writes what it observed to `behavior-<scenario>.txt` beside its
 * screenshots (`-PuiScreenshots=<dir>`) before asserting, so a run on the original code records the flaw.
 */
@OptIn(ExperimentalTestApi::class)
class SimulationTraceHostTest {
    private val clockFixture = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Clock" logisim-mobile-id="clk"><a name="label" val="CLK"/></comp>
    <comp lib="0" loc="(200,100)" name="Pin" logisim-mobile-id="q"><a name="facing" val="west"/><a name="type" val="output"/><a name="label" val="Q"/></comp>
    <wire from="(100,100)" to="(200,100)"/>
  </circuit>
</project>
"""

    /** A pin and a gate in main, and a register inside an instance of `inner`. */
    private val pickerFixture = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="a"><a name="label" val="A"/></comp>
    <comp lib="1" loc="(200,100)" name="AND Gate" logisim-mobile-id="g"/>
    <comp loc="(300,300)" name="inner" logisim-mobile-id="alu"/>
  </circuit>
  <circuit name="inner">
    <comp lib="4" loc="(300,100)" name="Register" logisim-mobile-id="reg"><a name="label" val="R"/></comp>
  </circuit>
</project>
"""

    private val ramFixture = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="4" loc="(200,300)" name="RAM" logisim-mobile-id="mem">
      <a name="addrWidth" val="8"/><a name="dataWidth" val="8"/><a name="label" val="MEM"/>
    </comp>
  </circuit>
</project>
"""

    private fun ComposeUiTest.show(language: AppLanguage = AppLanguage.ENGLISH, content: @Composable () -> Unit) {
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                LogisimTheme(dark = false) { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
        waitForIdle()
    }

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

    private fun ComposeUiTest.retainedSamples(): Int? = texts().firstNotNullOfOrNull {
        Regex("""^(\d+)/\d+ samples""").find(it)?.groupValues?.get(1)?.toInt()
    }

    private fun ComposeUiTest.windowSpan(): Long? = texts().firstNotNullOfOrNull {
        Regex("""^(\d+) to (\d+) """).find(it)?.groupValues?.let { (_, start, end) -> end.toLong() - start.toLong() }
    }

    private val pause = message(Messages.ActionPause).resolve(AppLanguage.ENGLISH)

    // C57: opening Signal recording keeps the run going, and the panel runs, pauses and ticks the clock itself.
    @Test
    fun traceKeepsTheSimulationRunningAndStepsItFromThePanel() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(clockFixture)
        app(state, clockFixture)
        runOnIdle { state.select("clk") }
        tap("tool-run")
        waitFor { description("tool-run") == pause }
        tap("editor-project-toolbar-overflow")
        // Signal recording sits in the Simulate menu, as in Evolution (W2-02).
        tap("menu-title-simulate")
        tap("menu-project-tool-trace")
        val ticksAtOpen = state.rootSimulation.ticks
        val advanced = waitFor(3_000) { state.rootSimulation.ticks >= ticksAtOpen + 3 }
        val runningAfterOpen = description("tool-run") == pause
        val samplesWhileRunning = retainedSamples()
        screenshot("trace-open-while-running")
        val panelControls = listOf("trace-sim-run", "trace-sim-tick", "trace-sim-tick-full").associateWith { exists(it) }
        var ticksAfterPanelSteps: Long? = null
        var samplesBefore: Int? = null
        var samplesAfter: Int? = null
        if (panelControls.values.all { it }) {
            tap("trace-sim-run")
            waitFor { description("tool-run") != pause }
            val before = state.rootSimulation.ticks
            samplesBefore = retainedSamples()
            tap("trace-sim-tick")
            waitFor { state.rootSimulation.ticks >= before + 1 }
            tap("trace-sim-tick")
            waitFor { state.rootSimulation.ticks >= before + 2 }
            waitForIdle()
            ticksAfterPanelSteps = state.rootSimulation.ticks - before
            samplesAfter = retainedSamples()
            screenshot("trace-panel-ticked")
        }
        observe("trace-run-controls", "runningAfterOpen" to runningAfterOpen, "ticksAdvancedWhileOpen" to advanced,
            "samplesWhileRunning" to samplesWhileRunning, "panelControls" to panelControls,
            "ticksFromTwoPanelTicks" to ticksAfterPanelSteps, "samplesBefore" to samplesBefore, "samplesAfter" to samplesAfter)
        assertTrue(runningAfterOpen, "Opening Signal recording paused the simulation")
        assertTrue(advanced, "The clock did not tick while the trace was open")
        assertTrue(panelControls.values.all { it }, "The trace panel has no simulation controls: $panelControls")
        assertEquals(2L, ticksAfterPanelSteps)
        assertEquals(samplesBefore!! + 2, samplesAfter)
    }

    private val counterSignals = listOf(
        TraceSignal(TraceSignalReference("", "clk"), "CLK", 1),
        TraceSignal(TraceSignalReference("", "count"), "COUNT", 4),
        TraceSignal(TraceSignalReference("", "bus_a"), "DATA_BUS_A", 8),
        TraceSignal(TraceSignalReference("", "bus_b"), "DATA_BUS_B", 8),
        TraceSignal(TraceSignalReference("", "bus_c"), "ADDRESS_BUS", 8),
    )

    private fun counterSample(time: Long) = SimulationState(vectorValues = mapOf(
        "clk" to if (time % 2 == 0L) LogicVector.high(1) else LogicVector.low(1),
        "count" to LogicVector.of(4, (time / 2).toULong() and 0xFuL),
        "bus_a" to LogicVector.of(8, (0x40uL + time.toULong())),
        "bus_b" to if (time < 4) LogicVector.unknown(8) else LogicVector.of(8, 0xA5uL),
        "bus_c" to LogicVector.of(8, (time * 16).toULong() and 0xFFuL),
    ))

    private fun counterRecorder(): TraceRecorder = TraceRecorder(counterSignals).also { recorder ->
        for (time in 0L..19L) recorder.capture(counterSample(time), time)
    }

    private val emptyProject = CircuitProject(listOf(dev.logisim.mobile.core.CircuitDocument("main")))

    // C62: buses show their values and a time ruler; the table's header and rows scroll together.
    @Test
    fun busValuesTimeRulerAndAlignedTable() = runDesktopComposeUiTest(390, 844) {
        val recorder = counterRecorder()
        counterSignals.forEachIndexed { index, signal ->
            if (signal.width > 1) recorder.setRadix(signal.reference, dev.logisim.mobile.core.TraceRadix.HEXADECIMAL)
        }
        show { TracePanel(emptyProject, counterSample(19), recorder, onExport = {}, sampleTime = 19) }
        screenshot("trace-timeline-bus-values")
        val ruler = exists("trace-time-ruler")
        onNodeWithText("Table").performClick()
        waitForIdle()
        screenshot("trace-table")
        // A row is one TalkBack node, so its cells are found in the unmerged tree.
        val grid = exists("trace-table-header") &&
            onAllNodesWithTag("trace-table-cell-0-3", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        var aligned: Pair<Float, Float>? = null
        var scrolled = false
        if (grid) {
            val header = onNodeWithTag("trace-table-header-3")
            val cell = onNodeWithTag("trace-table-cell-0-3", useUnmergedTree = true)
            val before = header.fetchSemanticsNode().positionInRoot.x
            onNodeWithTag("trace-table-header").performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f) }
            waitForIdle()
            val headerX = header.fetchSemanticsNode().positionInRoot.x
            aligned = headerX to cell.fetchSemanticsNode().positionInRoot.x
            scrolled = headerX < before
            screenshot("trace-table-scrolled")
        }
        // A sideways swipe on the rows scrolls the header with them.
        var rowSwipe: Pair<Float, Float>? = null
        if (grid) {
            onNodeWithTag("trace-table-row-2").performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f) }
            waitForIdle()
            rowSwipe = onNodeWithTag("trace-table-header-3").fetchSemanticsNode().positionInRoot.x to
                onNodeWithTag("trace-table-cell-0-3", useUnmergedTree = true).fetchSemanticsNode().positionInRoot.x
        }
        observe("trace-bus-table", "timeRuler" to ruler, "gridTable" to grid, "headerAndRowX" to aligned, "scrolled" to scrolled,
            "headerAndRowXAfterRowSwipe" to rowSwipe)
        assertTrue(ruler, "No time ruler above the waveforms")
        assertTrue(grid, "The table is one tab-separated text per row")
        assertTrue(scrolled)
        assertEquals(aligned!!.first, aligned.second, "Header and row columns misaligned after a horizontal scroll")
        assertTrue(rowSwipe!!.first > aligned.first, "A swipe on the rows did not scroll the table: $aligned -> $rowSwipe")
        assertEquals(rowSwipe.first, rowSwipe.second, "Header and row columns misaligned after a swipe on the rows")
    }

    // C63: pinch zooms the waveform, the slider moves the chosen cursor, and TalkBack reaches cursor B.
    @Test
    fun waveformPinchAndCursorB() = runDesktopComposeUiTest(390, 844) {
        val recorder = counterRecorder()
        show { TracePanel(emptyProject, counterSample(19), recorder, onExport = {}, sampleTime = 19) }
        onNodeWithText("Whole period").performClick()
        waitForIdle()
        val spanBefore = windowSpan()
        val waveforms = exists("trace-waveforms")
        var spanAfter: Long? = null
        if (waveforms) {
            onNodeWithTag("trace-waveforms").performTouchInput {
                pinch(center - Offset(40f, 0f), center - Offset(160f, 0f), center + Offset(40f, 0f), center + Offset(160f, 0f))
            }
            waitForIdle()
            spanAfter = windowSpan()
        }
        screenshot("trace-pinched")
        val targetB = exists("trace-cursor-target-b")
        if (targetB) {
            tap("trace-cursor-target-b")
            // Not the window's start, which the slider shows while cursor B is unset: the same value is no change.
            onNodeWithTag("trace-cursor-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(17f) }
            waitForIdle()
        }
        val actions = if (waveforms) onNodeWithTag("trace-waveforms").fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label } else emptyList()
        screenshot("trace-cursor-b")
        observe("trace-gestures", "spanBefore" to spanBefore, "spanAfterPinch" to spanAfter, "cursorBToggle" to targetB,
            "cursorB" to recorder.cursorB, "customActions" to actions)
        assertTrue(waveforms, "No gesture area around the waveforms")
        assertTrue(spanAfter != null && spanBefore != null && spanAfter < spanBefore, "Pinch did not zoom: $spanBefore -> $spanAfter")
        assertTrue(targetB, "No A/B cursor toggle")
        assertEquals(17L, recorder.cursorB)
        assertTrue(actions.any { it.startsWith("Set cursor B") }, "No TalkBack action for cursor B: $actions")
    }

    // C64: the picker lists loggable signals under their circuit, without internal keys; Show all restores the rest.
    @Test
    fun pickerListsLoggableSignalsByInstance() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(pickerFixture)
        val recorder = TraceRecorder()
        show { TracePanel(state.project, state.rootSimulation, recorder, onExport = {}) }
        onNodeWithText("Select signals").performClick()
        waitForIdle()
        screenshot("trace-picker-default")
        val shown = texts()
        val keys = shown.filter { Regex("""(^|\W)(a|g|alu|reg|alu/reg)$""").containsMatchIn(it) && !it.contains(' ') }
        val rows = listOf("a", "g", "alu/reg").associateWith { exists("trace-signal-$it") }
        val groups = exists("trace-group-main") && exists("trace-group-alu")
        var afterShowAll: Map<String, Boolean>? = null
        if (exists("trace-show-all")) {
            tap("trace-show-all")
            afterShowAll = listOf("a", "g", "alu/reg").associateWith { exists("trace-signal-$it") }
            screenshot("trace-picker-all")
        }
        observe("trace-picker", "rawKeysShown" to keys, "defaultRows" to rows, "groupHeaders" to groups, "afterShowAll" to afterShowAll,
            "texts" to shown)
        assertTrue(keys.isEmpty(), "Internal keys shown: $keys")
        assertEquals(mapOf("a" to true, "g" to false, "alu/reg" to true), rows)
        assertTrue(groups, "No circuit headers")
        assertEquals(mapOf("a" to true, "g" to true, "alu/reg" to true), afterShowAll)
    }

    // X6: a hexadecimal range adds every RAM cell in one confirmation.
    @Test
    fun ramCellRangeIsAddedInOneHexEntry() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(ramFixture)
        val recorder = TraceRecorder()
        show { TracePanel(state.project, state.rootSimulation, recorder, onExport = {}) }
        onNodeWithText("Select signals").performClick()
        waitForIdle()
        onNodeWithText("Add RAM cell").performClick()
        waitForIdle()
        screenshot("trace-ram-range-dialog")
        val field = onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size
        onAllNodes(hasSetTextAction()).onLast().performTextInput("10-13")
        waitForIdle()
        screenshot("trace-ram-range-entered")
        val keyShown = texts().any { it.contains("· mem") }
        if (exists("trace-ram-add")) tap("trace-ram-add")
        else onAllNodesWithText("Add RAM cell").onLast().performClick()
        waitForIdle()
        val addresses = recorder.signals.mapNotNull { it.reference.ramAddress }
        observe("trace-ram-range", "fields" to field, "rawKeyShown" to keyShown, "addresses" to addresses.map { "0x" + it.toString(16) })
        assertEquals((0x10L..0x13L).toList(), addresses)
        assertTrue(!keyShown, "The dialog shows the RAM's internal key")
    }
}
