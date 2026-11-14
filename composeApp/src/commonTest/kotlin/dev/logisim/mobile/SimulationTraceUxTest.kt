package dev.logisim.mobile

import dev.logisim.mobile.core.LogicValue
import dev.logisim.mobile.core.LogicVector
import dev.logisim.mobile.core.MemoryCellsChanged
import dev.logisim.mobile.core.MemoryComponents
import dev.logisim.mobile.core.SimulationProjectOptions
import dev.logisim.mobile.core.TraceFrame
import dev.logisim.mobile.core.TraceRadix
import dev.logisim.mobile.core.TraceWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Model logic behind W2-03: reset undo (C69), navigation (C58), the options draft (X3) and the waveform helpers (C62). */
class SimulationTraceUxTest {
    private val ramProject = """<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="a"><a name="label" val="A"/></comp>
    <comp lib="4" loc="(300,300)" name="RAM" logisim-mobile-id="mem"><a name="addrWidth" val="4"/><a name="dataWidth" val="8"/></comp>
    <comp loc="(400,100)" name="inner" logisim-mobile-id="sub"/>
  </circuit>
  <circuit name="inner">
    <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="x"/>
  </circuit>
</project>
"""

    private fun EditorState.ramWord(address: Long): ULong? =
        rootSimulation.componentStates["mem"]?.let(MemoryComponents::image)?.get(address)

    @Test
    fun undoResetRestoresPokedInputsAndSessionMemory() {
        val state = EditorState(ramProject)
        assertTrue(state.sendComponentEvent("mem", MemoryCellsChanged(mapOf(3L to 0x42uL))))
        state.toggle("a")
        assertEquals(0x42uL, state.ramWord(3))
        assertEquals(LogicValue.HIGH, state.rootSimulation.componentValues["a"])

        state.resetSimulation()
        assertEquals(0uL, state.ramWord(3) ?: 0uL)
        assertEquals(LogicValue.LOW, state.rootSimulation.componentValues["a"])
        assertNotNull(state.undoableReset)

        assertTrue(state.undoReset())
        assertEquals(0x42uL, state.ramWord(3))
        assertEquals(LogicValue.HIGH, state.rootSimulation.componentValues["a"])
        assertNull(state.undoableReset)
        assertFalse(state.undoReset())
        // The restored drives stay in force: the next settle keeps the input high.
        state.tick()
        assertEquals(LogicValue.HIGH, state.rootSimulation.componentValues["a"])
    }

    @Test
    fun resetUndoExpiresOnTicksInputsEditsAndNavigation() {
        val state = EditorState(ramProject)
        state.toggle("a")
        state.resetSimulation()
        state.tick()
        assertNull(state.undoableReset)
        assertFalse(state.undoReset())

        state.resetSimulation()
        state.toggle("a")
        assertNull(state.undoableReset)

        state.resetSimulation()
        state.move("a", dev.logisim.mobile.core.GridPoint(120, 100))
        assertNull(state.undoableReset, "An edit changes the revision the reset belonged to")

        state.resetSimulation()
        state.viewSubcircuitInstance("sub")
        assertNull(state.undoableReset)

        state.selectCircuit("main")
        state.resetSimulation()
        state.setSimulationRunning(true)
        assertNull(state.undoableReset)
    }

    @Test
    fun anOvertakenResetLetsGoOfTheSimulationItReplaced() {
        val state = EditorState(ramProject)
        assertTrue(state.sendComponentEvent("mem", MemoryCellsChanged(mapOf(3L to 0x42uL))))
        state.resetSimulation()
        assertTrue(state.holdsResetUndo)
        state.move("a", dev.logisim.mobile.core.GridPoint(120, 100))
        // The bottom bar reads it after every edit; the stale reset then drops the RAM image it kept.
        assertNull(state.undoableReset)
        assertFalse(state.holdsResetUndo, "The overtaken reset still holds the replaced simulation")
        assertFalse(state.undoReset())
    }

    @Test
    fun theTraceWindowOpensOnTheRecordingAndFollowsItsNewestSample() {
        // Nothing recorded yet, or a short recording from the origin: the default window from zero.
        assertEquals(TraceWindow(0, 20), traceInitialWindow(null, null))
        assertEquals(TraceWindow(0, 20), traceInitialWindow(0, 10))
        // A recording opened after a run starts at its first sample, or shows its last span with room after it.
        assertEquals(TraceWindow(45, 65), traceInitialWindow(45, 45))
        assertEquals(TraceWindow(82, 102), traceInitialWindow(0, 100))

        val window = TraceWindow(0, 20)
        assertEquals(window, traceFollowWindow(window, previousLast = 10, first = 0, last = 15), "Still fits")
        assertEquals(TraceWindow(5, 25), traceFollowWindow(window, previousLast = 15, first = 0, last = 23),
            "The newest sample was in view, so the window scrolls with it and keeps its span")
        assertEquals(TraceWindow(51, 61), traceFollowWindow(TraceWindow(30, 40), previousLast = 39, first = 0, last = 60),
            "A zoomed window keeps its span while following")
        val elsewhere = TraceWindow(2, 12)
        assertEquals(elsewhere, traceFollowWindow(elsewhere, previousLast = 30, first = 0, last = 40),
            "A window moved away from the newest sample stays where the user put it")
        assertEquals(TraceWindow(40, 60), traceFollowWindow(window, previousLast = null, first = 40, last = 40),
            "A recording that just began opens on it")
        assertEquals(TraceWindow(0, 20), traceFollowWindow(TraceWindow(500, 520), previousLast = 519, first = 0, last = 3),
            "A recording reset to an earlier time opens afresh")
        assertEquals(window, traceFollowWindow(window, previousLast = 10, first = 0, last = null))
    }

    @Test
    fun onlyACircuitOutsideTheRunningOneCountsAsLeavingIt() {
        val state = EditorState(ramProject)
        assertFalse(state.viewsOutsideRunningCircuit())
        state.viewSubcircuitInstance("sub")
        assertEquals(listOf("sub"), state.activeInstancePath)
        assertFalse(state.viewsOutsideRunningCircuit(), "An instance of the running circuit keeps the run")
        state.selectCircuit("inner")
        assertTrue(state.viewsOutsideRunningCircuit())
        state.selectCircuit("main")
        assertFalse(state.viewsOutsideRunningCircuit())
    }

    @Test
    fun optionsDraftTracksItsChangesAndResult() {
        val initial = SimulationProjectOptions()
        val draft = SimulationOptionsDraft(initial)
        assertFalse(draft.dirty)
        assertEquals(initial, draft.result())
        draft.limitText = "5000"
        draft.frequency = 8.0
        assertTrue(draft.dirty)
        assertEquals(initial.copy(propagationLimit = 5000, tickFrequencyHz = 8.0), draft.result())
        draft.limitText = "0"
        assertNull(draft.result())
    }

    private fun frames(vararg values: Pair<Long, Long?>) = values.map { (time, value) ->
        TraceFrame(time, listOf(value?.let { LogicVector.of(8, it.toULong()) }))
    }

    @Test
    fun busSegmentsMergeRepeatedValuesAndLabelWhatFits() {
        val window = TraceWindow(0, 100)
        val segments = traceSegments(frames(0L to 1L, 10L to 1L, 20L to 2L, 22L to 3L, 50L to null), 0, window, 1_000f)
        assertEquals(listOf(0f to 200f, 200f to 220f, 220f to 500f, 500f to 1_000f), segments.map { it.x0 to it.x1 })
        // Seven pixels a character, four pixels of padding at each end.
        val labels = busSegmentLabels(segments, TraceRadix.HEXADECIMAL::format, measure = { it.length * 7f }, padding = 4f)
        assertEquals(listOf("0x01", "…", "0x03"), labels.map { it.text })
        assertEquals(listOf(false, true, false), labels.map { it.elided })
        val narrow = busSegmentLabels(segments, TraceRadix.HEXADECIMAL::format, measure = { it.length * 7f }, padding = 8f)
        assertEquals(listOf("0x01", "0x03"), narrow.map { it.text }, "A segment narrower than an ellipsis stays blank")
    }

    @Test
    fun rulerTicksUseRoundStepsWithinTheWindow() {
        assertEquals(listOf(0L, 5L, 10L, 15L, 20L), traceRulerTicks(TraceWindow(0, 20), width = 360f, minSpacing = 60f))
        assertEquals(listOf(1_000L, 1_200L, 1_400L), traceRulerTicks(TraceWindow(950, 1_450), width = 400f, minSpacing = 120f))
        assertEquals((3L..7L).toList(), traceRulerTicks(TraceWindow(3, 7), width = 400f, minSpacing = 10f))
    }

    @Test
    fun tableColumnsFitTheWidestValue() {
        assertEquals("00000000", traceValueSample(8, TraceRadix.BINARY))
        assertEquals("0x00", traceValueSample(8, TraceRadix.HEXADECIMAL))
        assertEquals(TraceRadix.HEXADECIMAL.format(LogicVector.of(8, 0xFFuL)).length, traceValueSample(8, TraceRadix.HEXADECIMAL).length)
        assertEquals("255".length, traceValueSample(8, TraceRadix.DECIMAL).length)
        assertEquals(ULong.MAX_VALUE.toString().length, traceValueSample(64, TraceRadix.DECIMAL).length)
    }
}
