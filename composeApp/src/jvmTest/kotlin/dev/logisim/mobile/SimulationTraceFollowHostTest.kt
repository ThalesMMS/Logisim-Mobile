package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.SimulationState
import dev.logisim.mobile.core.TraceRadix
import dev.logisim.mobile.core.TraceRecorder
import dev.logisim.mobile.core.TraceSignal
import dev.logisim.mobile.core.TraceSignalReference
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.localization.LocalAppLanguage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Signal recording after review (W2-03, round 2): the waveforms follow the newest sample past the first window and
 * open on what was recorded (C57), a state older than the recording never wipes it (C57), the panel's simulation
 * controls follow the document's writability (C57), taps and sideways drags still reach the rows under the gesture
 * area (C63), and a table row is one TalkBack announcement without a scroll of its own (C62). Each scenario writes
 * what it observed to `behavior-<scenario>.txt` beside its screenshots before asserting.
 */
@OptIn(ExperimentalTestApi::class)
class SimulationTraceFollowHostTest {
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
        runCatching { onNodeWithTag(tag).performScrollTo() }
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun ComposeUiTest.texts(): List<String> = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))
        .fetchSemanticsNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun ComposeUiTest.waitFor(timeout: Long = 5_000, condition: () -> Boolean): Boolean =
        runCatching { waitUntil(timeoutMillis = timeout, condition = condition) }.isSuccess

    /** The visible window, from the "start to end" text above the waveforms. */
    private fun ComposeUiTest.window(): LongRange? = texts().firstNotNullOfOrNull {
        Regex("""^(\d+) to (\d+) """).find(it)?.groupValues?.let { (_, start, end) -> start.toLong()..end.toLong() }
    }

    private fun ComposeUiTest.openTrace() {
        tap("editor-project-toolbar-overflow")
        // Signal recording sits in the Simulate menu, as in Evolution (W2-02).
        tap("menu-title-simulate")
        tap("menu-project-tool-trace")
    }

    // C57: ticking from the panel past the first twenty events keeps the newest sample in view.
    @Test
    fun waveformsFollowTicksPastTheFirstWindow() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(clockFixture)
        app(state, clockFixture)
        runOnIdle { state.select("clk") }
        openTrace()
        val initial = window()
        // A full cycle is two half ticks, each at least one propagation event.
        repeat(14) {
            val before = state.rootSimulation.ticks
            tap("trace-sim-tick-full")
            waitFor { state.rootSimulation.ticks > before }
        }
        waitForIdle()
        val last = state.rootSimulation.propagationTime
        val shown = window()
        screenshot("trace-follows-ticks")
        observe("trace-follow", "initialWindow" to initial, "lastSampleTime" to last, "windowAfterTicks" to shown,
            "ticks" to state.rootSimulation.ticks)
        assertTrue(last > 20, "The ticks must pass the first window: $last")
        assertNotNull(shown)
        assertTrue(last in shown, "The newest sample at $last is outside the window $shown")
    }

    // C57: run first, then open the trace and pick a signal: it opens on the newest samples, not on 0 to 20.
    @Test
    fun aTraceOpenedAfterARunShowsTheNewestSamples() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(clockFixture)
        app(state, clockFixture)
        repeat(14) {
            val before = state.rootSimulation.ticks
            tap("tool-tick-cycle")
            waitFor { state.rootSimulation.ticks > before }
        }
        openTrace()
        onNodeWithText("Select signals").performClick()
        waitForIdle()
        tap("trace-signal-clk")
        onNodeWithText("Done").performClick()
        waitForIdle()
        val last = state.rootSimulation.propagationTime
        val shown = window()
        screenshot("trace-opened-after-run")
        observe("trace-open-after-run", "lastSampleTime" to last, "window" to shown)
        assertTrue(last > 20, "The run must pass the first window: $last")
        assertNotNull(shown)
        assertTrue(last in shown, "The recorded sample at $last is outside the window $shown")
    }

    // C57: the tool's hook has already recorded a newer state when the panel's effect runs with an older one.
    @Test
    fun aStateOlderThanTheRecordingNeverClearsIt() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(clockFixture)
        val project = state.project
        val clock = project.mainCircuit.components.first { it.id == "clk" }
        val recorder = TraceRecorder()
        val tool = traceEditorTool(recorder = recorder)
        fun sample(time: Long) = SimulationState(propagationTime = time,
            vectorValues = mapOf("clk" to if (time % 2 == 0L) LogicVector.high(1) else LogicVector.low(1)))
        fun context(simulation: SimulationState) = EditorToolContext(project, project.mainCircuit, simulation, listOf(clock),
            {}, {}, {}, {}, {}, projectSimulation = simulation)
        tool.onSimulation?.invoke(project, sample(0))
        var current by mutableStateOf(context(sample(0)))
        show { tool.content(current) }
        val selected = recorder.signals.map { it.reference.key }
        runOnIdle { for (time in 1L..30L) tool.onSimulation?.invoke(project, sample(time)) }
        val framesBefore = recorder.frames.size
        // A recomposition carries a state older than the hook's newest, as one published during it would leave.
        runOnIdle { current = context(sample(12)) }
        waitForIdle()
        val framesAfter = recorder.frames.size
        val lastAfter = recorder.lastTime
        observe("trace-stale-state", "selected" to selected, "framesBefore" to framesBefore, "framesAfter" to framesAfter,
            "lastTimeAfter" to lastAfter)
        assertEquals(listOf("clk"), selected)
        assertEquals(31, framesBefore)
        assertEquals(framesBefore, framesAfter, "An older state cleared the recording")
        assertEquals(30L, lastAfter)
    }

    // C57: a read-only document disables Run and Tick in the panel, as the toolbar does.
    @Test
    fun readOnlyDocumentDisablesThePanelSimulationControls() = runDesktopComposeUiTest(390, 844) {
        val state = EditorState(clockFixture, initialReadOnly = true)
        app(state, clockFixture)
        openTrace()
        screenshot("trace-read-only")
        val enabled = listOf("tool-run", "trace-sim-run", "trace-sim-tick", "trace-sim-tick-full").associateWith { tag ->
            if (!exists(tag)) null else onNodeWithTag(tag).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled).not()
        }
        observe("trace-read-only", "enabled" to enabled)
        assertEquals(false, enabled["tool-run"], "The toolbar's Run is disabled for a read-only document")
        assertEquals(mapOf("trace-sim-run" to false, "trace-sim-tick" to false, "trace-sim-tick-full" to false),
            enabled.filterKeys { it.startsWith("trace-") })
    }

    private val signals = listOf(
        TraceSignal(TraceSignalReference("", "clk"), "CLK", 1),
        TraceSignal(TraceSignalReference("", "count"), "COUNT", 4, radix = TraceRadix.HEXADECIMAL),
    )

    private fun sample(time: Long) = SimulationState(vectorValues = mapOf(
        "clk" to if (time % 2 == 0L) LogicVector.high(1) else LogicVector.low(1),
        "count" to LogicVector.of(4, (time / 2).toULong() and 0xFuL),
    ))

    private fun recorder() = TraceRecorder(signals).also { for (time in 0L..19L) it.capture(sample(time), time) }

    private val emptyProject = CircuitProject(listOf(CircuitDocument("main")))

    // C63: under the pinch-and-drag area a tap still places cursor A, and a sideways drag pans the window.
    @Test
    fun tapPlacesCursorAAndDragPansTheWindow() = runDesktopComposeUiTest(390, 844) {
        val recorder = recorder()
        show { TracePanel(emptyProject, sample(19), recorder, onExport = {}, sampleTime = 19) }
        val shown = window()!!
        val row = onAllNodes(SemanticsMatcher("waveform") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith("Signal CLK") } == true
        }).onFirst()
        row.performTouchInput { click(Offset(width * 0.5f, height / 2f)) }
        waitForIdle()
        val cursorA = recorder.cursorA
        onNodeWithText("Zoom in time").performClick()
        waitForIdle()
        val zoomed = window()!!
        onNodeWithTag("trace-waveforms").performTouchInput { swipeLeft(startX = right - 20f, endX = left + 60f) }
        waitForIdle()
        val panned = window()!!
        tap("trace-cursor-target-b")
        val gestureHelp = onNodeWithTag("trace-window").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
            ?.joinToString { it.text }
        screenshot("trace-tap-and-pan")
        observe("trace-tap-pan", "window" to shown, "cursorA" to cursorA, "zoomed" to zoomed, "panned" to panned,
            "helpForCursorB" to gestureHelp)
        assertNotNull(cursorA, "A tap on a waveform did not place cursor A")
        val middle = (shown.first + shown.last) / 2
        assertTrue(cursorA in (middle - 2)..(middle + 2), "Cursor A at $cursorA, expected near $middle of $shown")
        assertTrue(panned.first > zoomed.first, "A sideways drag did not pan: $zoomed -> $panned")
        assertEquals(zoomed.last - zoomed.first, panned.last - panned.first, "Panning changed the span")
        assertTrue(gestureHelp?.contains("place cursor B") == true && gestureHelp.contains("cursor A").not(),
            "The gesture help names the wrong cursor: $gestureHelp")
    }

    // C62: a table row reads as one announcement, time first, and only the header scrolls for TalkBack.
    @Test
    fun tableRowsReadAsOneAnnouncement() = runDesktopComposeUiTest(390, 844) {
        val recorder = recorder()
        show { TracePanel(emptyProject, sample(19), recorder, onExport = {}, sampleTime = 19) }
        onNodeWithText("Table").performClick()
        waitForIdle()
        screenshot("trace-table-semantics")
        val row = onAllNodesWithTag("trace-table-row-3").fetchSemanticsNodes().firstOrNull()
        val description = row?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        val scrollers = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange), useUnmergedTree = true)
            .fetchSemanticsNodes()
        // The action rows above the table scroll sideways too; below the header only the header's own scroll may.
        val headerBottom = onNodeWithTag("trace-table-header").fetchSemanticsNode().boundsInRoot.bottom
        val rowScrollers = scrollers.count { it.boundsInRoot.top >= headerBottom }
        val separateCells = onAllNodesWithTag("trace-table-cell-3-1").fetchSemanticsNodes().size
        observe("trace-table-semantics", "row3" to description, "horizontalScrollers" to scrollers.size,
            "rowScrollers" to rowScrollers, "separateCellNodes" to separateCells)
        assertEquals("Time 3 (events): CLK 0, COUNT 0x1", description)
        assertEquals(0, separateCells, "Row cells are announced one by one")
        assertEquals(0, rowScrollers, "Each row exposes its own horizontal scroll")
    }
}
