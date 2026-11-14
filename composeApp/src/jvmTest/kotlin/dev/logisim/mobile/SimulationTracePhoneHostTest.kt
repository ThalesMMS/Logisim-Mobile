package dev.logisim.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Signal recording's new controls on a 360 dp phone in Portuguese, the longest of the published labels (W2-03):
 * the simulation row, the cursor toggle and the slider keep 48 dp targets and the bus values stay inside their rows.
 */
@OptIn(ExperimentalTestApi::class)
class SimulationTracePhoneHostTest {
    private val signals = listOf(
        TraceSignal(TraceSignalReference("", "clk"), "CLK", 1),
        TraceSignal(TraceSignalReference("", "count"), "CONTADOR", 4, radix = TraceRadix.HEXADECIMAL),
        TraceSignal(TraceSignalReference("", "bus"), "BARRAMENTO_DE_DADOS", 8, radix = TraceRadix.DECIMAL),
    )

    private fun sample(time: Long) = SimulationState(vectorValues = mapOf(
        "clk" to if (time % 2 == 0L) LogicVector.high(1) else LogicVector.low(1),
        "count" to LogicVector.of(4, (time / 2).toULong() and 0xFuL),
        "bus" to if (time < 6) LogicVector.unknown(8) else LogicVector.of(8, (time * 7).toULong() and 0xFFuL),
    ))

    @Test
    fun traceControlsFitA360PhoneInPortuguese() = runDesktopComposeUiTest(360, 780) {
        val recorder = TraceRecorder(signals).also { for (time in 0L..19L) it.capture(sample(time), time) }
        var steps = mutableListOf<Boolean>()
        var running = false
        setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.PORTUGUESE) {
                LogisimTheme(dark = false) {
                    Surface(Modifier.fillMaxSize()) {
                        TracePanel(CircuitProject(listOf(CircuitDocument("main"))), sample(19), recorder, onExport = {}, sampleTime = 19,
                            simulationControls = TraceSimulationControls(running, enabled = true, onRun = { running = true },
                                onPause = { running = false }, onTick = { steps += it }))
                    }
                }
            }
        }
        waitForIdle()
        screenshot("trace-panel-360-pt")
        for (tag in listOf("trace-sim-run", "trace-sim-tick", "trace-sim-tick-full", "trace-cursor-target-a", "trace-cursor-target-b")) {
            onNodeWithTag(tag).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        onNodeWithTag("trace-sim-tick").performSemanticsAction(SemanticsActions.OnClick)
        onNodeWithTag("trace-sim-tick-full").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(false, true), steps)
        // Every waveform row lies within the window.
        onAllNodes(SemanticsMatcher("waveform") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith("Sinal ") } == true
        }).fetchSemanticsNodes().forEach { node ->
            assert(node.boundsInRoot.right <= 360f && node.boundsInRoot.left >= 0f) { node.boundsInRoot.toString() }
        }
        // Table sits past the edge of the sideways-scrolling action row on a 360 dp phone: scroll to it first.
        onNodeWithText("Tabela").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        onNodeWithTag("trace-table-header").assertExists()
        onNodeWithTag("trace-table-row-0").assertExists()
        screenshot("trace-table-360-pt")
    }
}
