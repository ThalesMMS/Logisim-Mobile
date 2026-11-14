package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** C160: trace signals are named by instance labels and their own label, as Evolution's chronogram does, not by ids. */
class TraceSignalDisplayNameTest {
    private val half = CircuitDocument("half", listOf(
        CircuitComponent("c7", ComponentKind.INPUT, GridPoint(100, 100), "A"),
        CircuitComponent("c8", ComponentKind.AND, GridPoint(200, 100)),
    ))
    private val main = CircuitDocument("main", listOf(
        CircuitComponent("c3", ComponentKind.SUBCIRCUIT, GridPoint(300, 100), "Sub1", circuitName = "half"),
        CircuitComponent("c4", ComponentKind.SUBCIRCUIT, GridPoint(300, 200), circuitName = "half"),
        CircuitComponent("c5", ComponentKind.INPUT, GridPoint(40, 40), "IN"),
    ))
    private val signals = TraceSignals.fromProject(CircuitProject(listOf(main, half)))

    private fun TraceSignal.shown(language: AppLanguage = AppLanguage.ENGLISH) = displayText.resolve(language)

    @Test
    fun aComponentInsideASubcircuitIsNamedByTheInstanceLabelPath() {
        val inside = signals.single { it.reference == TraceSignalReference("c3", "c7") }
        assertEquals("Sub1 / A", inside.shown())
        assertEquals("Sub1 / A", inside.shown(AppLanguage.PORTUGUESE))
        // The key keeps the ids: it is the identity of the selection, not its name.
        assertEquals("c3/c7", inside.reference.key)
    }

    @Test
    fun unlabeledInstancesAndComponentsUseTheirKindAndLocation() {
        assertEquals("half (300,200) / A", signals.single { it.reference == TraceSignalReference("c4", "c7") }.shown())
        assertEquals("Sub1 / AND (200,100)", signals.single { it.reference == TraceSignalReference("c3", "c8") }.shown())
        val top = signals.single { it.reference == TraceSignalReference("", "c5") }
        assertNull(top.instanceText)
        assertEquals("IN", top.shown())
        for (signal in signals) for (id in listOf("c3", "c4", "c5", "c7", "c8")) {
            assertFalse(Regex("\\b$id\\b").containsMatchIn(signal.shown()), signal.shown())
        }
    }

    @Test
    fun theLocatedNameAddsEveryPartsLocationWithoutIds() {
        val inside = signals.single { it.reference == TraceSignalReference("c3", "c7") }
        assertEquals("Sub1 (300,100) / A (100,100)", inside.locatedText.resolve(AppLanguage.ENGLISH))
        assertEquals("A (100,100)", inside.locatedLabelText.resolve(AppLanguage.ENGLISH))
        assertEquals("Sub1 (300,100)", inside.locatedInstanceText?.resolve(AppLanguage.ENGLISH))
        // An unlabeled component already carries its location, so it is not repeated.
        assertEquals("half (300,200) / AND (200,100)",
            signals.single { it.reference == TraceSignalReference("c4", "c8") }.locatedText.resolve(AppLanguage.ENGLISH))
        assertEquals("IN (40,40)", signals.single { it.reference == TraceSignalReference("", "c5") }.locatedText.resolve(AppLanguage.ENGLISH))
    }

    @Test
    fun aRamCellKeepsItsInstancePath() {
        val ramHolder = CircuitDocument("mem", listOf(CircuitComponent("r1", ComponentKind.RAM, GridPoint(0, 0), "RAM0")))
        val top = CircuitDocument("main", listOf(CircuitComponent("m1", ComponentKind.SUBCIRCUIT, GridPoint(0, 0), "Bank", circuitName = "mem")))
        val ram = TraceSignals.fromProject(CircuitProject(listOf(top, ramHolder))).single { it.reference.componentId == "r1" }
        assertEquals("Bank / RAM0[0x3]", TraceSignals.ramCell(ram, 3)!!.shown())
        assertEquals("Bank (0,0) / RAM0 (0,0)[0x3]", TraceSignals.ramCell(ram, 3)!!.locatedText.resolve(AppLanguage.ENGLISH))
    }
}
