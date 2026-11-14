package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Signal recording picker's model (C64) and its RAM address entry (X6). */
class TraceSignalPickerTest {
    private val project = (CircCodec.readProject("""<?xml version="1.0" encoding="UTF-8"?>
<project source="5.1.0-dev" version="1.0">
  <lib desc="#Wiring" name="0"/>
  <lib desc="#Gates" name="1"/>
  <lib desc="#Memory" name="4"/>
  <main name="main"/>
  <circuit name="main">
    <comp lib="0" loc="(100,100)" name="Pin" logisim-mobile-id="a"><a name="label" val="A"/></comp>
    <comp lib="1" loc="(200,100)" name="AND Gate" logisim-mobile-id="g"/>
    <comp lib="0" loc="(150,250)" name="Clock" logisim-mobile-id="clk"/>
    <comp loc="(120,40)" name="ALU" logisim-mobile-id="alu"/>
    <comp loc="(300,40)" name="ALU" logisim-mobile-id="alu2"><a name="label" val="Second"/></comp>
  </circuit>
  <circuit name="ALU">
    <comp lib="4" loc="(300,100)" name="Register" logisim-mobile-id="reg"><a name="label" val="R"/></comp>
    <comp lib="1" loc="(200,100)" name="OR Gate" logisim-mobile-id="or"/>
  </circuit>
</project>""") as CircProjectImportResult.Success).project

    private val available = TraceSignals.fromProject(project)

    @Test
    fun loggableComponentsAreGroupedByInstanceWithHumanPaths() {
        val groups = TraceSignalPicker.groups(project, available, emptyList(), showAll = false)
        assertEquals(listOf("", "alu", "alu2"), groups.map { it.instancePath })
        assertEquals(listOf(listOf("main"), listOf("main", "ALU(120,40)"), listOf("main", "Second")), groups.map { it.path })
        assertEquals(listOf(listOf("a", "clk"), listOf("reg"), listOf("reg")), groups.map { group -> group.signals.map { it.reference.componentId } })
    }

    @Test
    fun showAllRestoresEveryComponentAndSelectionsStayListed() {
        val all = TraceSignalPicker.groups(project, available, emptyList(), showAll = true)
        assertEquals(available.map { it.reference }.toSet(), all.flatMap { group -> group.signals.map { it.reference } }.toSet())
        // A gate chosen in an older session stays in the default list, as does a RAM cell outside the catalogue.
        val gate = available.single { it.reference.componentId == "g" }
        val kept = TraceSignalPicker.groups(project, available, listOf(gate), showAll = false)
        assertTrue(kept.single { it.instancePath.isEmpty() }.signals.any { it.reference == gate.reference })
    }

    @Test
    fun theFilterSeesLabelsAndInstancePaths() {
        val second = TraceSignalPicker.groups(project, available, emptyList(), showAll = false) { _, path -> "Second" in path }
        assertEquals(listOf("alu2"), second.map { it.instancePath })
    }

    @Test
    fun loggableKindsFollowEvolutionsInstanceLoggers() {
        val registry = ComponentCatalog.registry
        fun kind(name: String) = project.circuits.flatMap { it.components }.single { it.id == name }
        assertEquals(mapOf("a" to true, "g" to false, "clk" to true, "alu" to false, "reg" to true, "or" to false),
            listOf("a", "g", "clk", "alu", "reg", "or").associateWith { TraceLoggable.isLoggable(kind(it), registry) })
    }

    @Test
    fun siblingInstancesWithTheSameLabelAreToldApartByLocation() {
        val half = CircuitDocument("half", listOf(CircuitComponent("p", ComponentKind.INPUT, GridPoint(100, 100), "A")))
        val main = CircuitDocument("main", listOf(
            CircuitComponent("s1", ComponentKind.SUBCIRCUIT, GridPoint(300, 100), "Sub1", circuitName = "half"),
            CircuitComponent("s2", ComponentKind.SUBCIRCUIT, GridPoint(300, 200), "Sub1", circuitName = "half"),
            CircuitComponent("s3", ComponentKind.SUBCIRCUIT, GridPoint(300, 300), "Other", circuitName = "half"),
        ))
        val names = TraceSignalPicker.instanceNames(CircuitProject(listOf(main, half)))
        assertEquals(listOf("main", "Sub1 (300,100)"), names["s1"])
        assertEquals(listOf("main", "Sub1 (300,200)"), names["s2"])
        // A label used once stays short.
        assertEquals(listOf("main", "Other"), names["s3"])
    }

    @Test
    fun ramAddressesAreHexadecimalWithRanges() {
        val range = assertIs<TraceRamCellEntry.Addresses>(TraceRamCells.parse("1F0-1F7", addressBits = 12))
        assertEquals(8L, range.count)
        assertEquals((0x1F0L..0x1F7L).toList(), range.addresses(256))
        assertEquals(listOf(16L), assertIs<TraceRamCellEntry.Addresses>(TraceRamCells.parse("0x10", 8)).addresses(256))
        assertEquals(listOf(0x10L, 0x20L, 0x21L), assertIs<TraceRamCellEntry.Addresses>(TraceRamCells.parse("10, 20-21", 8)).addresses(256))
        assertEquals(TraceRamCellEntry.OutOfRange(0xFF), TraceRamCells.parse("100", 8))
        assertEquals(TraceRamCellEntry.Invalid, TraceRamCells.parse("1G", 8))
        assertEquals(TraceRamCellEntry.Invalid, TraceRamCells.parse("20-10", 8))
        assertEquals(TraceRamCellEntry.Empty, TraceRamCells.parse("  ", 8))
        // A whole large memory parses without listing every address; the caller takes what fits.
        val whole = assertIs<TraceRamCellEntry.Addresses>(TraceRamCells.parse("0-FFFFFF", 24))
        assertEquals(0x1000000L, whole.count)
        assertEquals(256, whole.addresses(256).size)
    }
}
