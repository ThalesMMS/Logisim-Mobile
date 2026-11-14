package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.TraceSignalReference
import dev.logisim.mobile.core.TraceSignals
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.localization.Localizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** C64, C160: the trace UI tells apart signals with the same name by instance path and location, never by component id. */
class TraceSignalNamesTest {
    private val half = CircuitDocument("half", listOf(
        CircuitComponent("c7", ComponentKind.INPUT, GridPoint(100, 100), "A"),
        CircuitComponent("c8", ComponentKind.INPUT, GridPoint(100, 200)),
    ))
    private val main = CircuitDocument("main", listOf(
        CircuitComponent("c1", ComponentKind.INPUT, GridPoint(40, 40), "B"),
        CircuitComponent("c2", ComponentKind.INPUT, GridPoint(40, 80), "B"),
        CircuitComponent("c3", ComponentKind.SUBCIRCUIT, GridPoint(300, 100), "Sub1", circuitName = "half"),
        CircuitComponent("c4", ComponentKind.SUBCIRCUIT, GridPoint(300, 200), "Sub1", circuitName = "half"),
        // Stacked on one point with one label: only an ordinal can tell them apart.
        CircuitComponent("c11", ComponentKind.SUBCIRCUIT, GridPoint(500, 100), "Twin", circuitName = "half"),
        CircuitComponent("c12", ComponentKind.SUBCIRCUIT, GridPoint(500, 100), "Twin", circuitName = "half"),
        CircuitComponent("c5", ComponentKind.INPUT, GridPoint(500, 500), "IN"),
        CircuitComponent("c9", ComponentKind.INPUT, GridPoint(600, 600), "S"),
        CircuitComponent("c10", ComponentKind.INPUT, GridPoint(600, 600), "S"),
    ))
    private val project = CircuitProject(listOf(main, half))
    private val signals = TraceSignals.fromProject(project)
    private val names = traceSignalNames(project, signals, Localizer(AppLanguage.PORTUGUESE))
    private fun name(path: String, id: String) = names.of(signals.single { it.reference == TraceSignalReference(path, id) })

    @Test
    fun aUniqueNameStaysShort() {
        assertEquals(TraceSignalName("IN", "main", nested = false), name("", "c5"))
        assertEquals("IN", name("", "c5").title)
    }

    @Test
    fun sameLabelsAreToldApartByLocation() {
        assertEquals("B (40,40)", name("", "c1").title)
        assertEquals("B (40,80)", name("", "c2").title)
        assertEquals(TraceSignalName("A", "main › Sub1 (300,100)", nested = true), name("c3", "c7"))
        assertEquals("A · main › Sub1 (300,200)", name("c4", "c7").title)
        // Unlabeled pins in same-labeled instances.
        assertEquals("Entrada (100,200) · main › Sub1 (300,100)", name("c3", "c8").title)
        assertEquals("Entrada (100,200) · main › Sub1 (300,200)", name("c4", "c8").title)
        assertEquals(listOf("main", "Sub1 (300,100)"), names.path("c3"))
    }

    @Test
    fun stackedSignalsAndInstancesAreNumberedInAStableOrder() {
        assertEquals("S (600,600) nº 2", name("", "c9").title)
        assertEquals("S (600,600) nº 1", name("", "c10").title)
        assertEquals("main › Twin (500,100) nº 1", name("c11", "c7").instance)
        assertEquals("main › Twin (500,100) nº 2", name("c12", "c7").instance)
        // The order of the recorder does not change a name.
        val reversed = traceSignalNames(project, signals.reversed(), Localizer(AppLanguage.PORTUGUESE))
        for (signal in signals) assertEquals(names.of(signal), reversed.of(signal))
    }

    @Test
    fun everyNameIsDistinctAndHasNoId() {
        val shown = signals.map { names.of(it) }
        assertEquals(signals.size, shown.map { it.title }.distinct().size)
        assertEquals(signals.size, shown.map { "${it.label}, ${it.instance}" }.distinct().size)
        val ids = (main.components + half.components).map { it.id }
        for (name in shown) for (id in ids) {
            assertFalse(Regex("(^|[^A-Za-z0-9])$id([^A-Za-z0-9]|$)").containsMatchIn(name.title), name.title)
        }
    }
}
