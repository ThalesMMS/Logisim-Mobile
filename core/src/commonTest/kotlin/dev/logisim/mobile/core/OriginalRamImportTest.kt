package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OriginalRamImportTest {
    private val simulator = CircuitSimulator()

    @Test
    fun importAndSavePreserveTheOriginalPortsAndWires() {
        val common = mapOf(
            "address0" to GridPoint(160, 100), "dataOut0_0" to GridPoint(300, 100),
            "chipSelect" to GridPoint(210, 140), "outputEnable0" to GridPoint(250, 140),
            "clear" to GridPoint(270, 140),
        )
        val clock = "clock0" to GridPoint(230, 140)
        val expected = mapOf(
            null to (common + clock),
            "combined" to (common + clock),
            "asynch" to common,
            "separate" to (common + clock + mapOf(
                "writeEnable0" to GridPoint(190, 140), "dataIn0_0" to GridPoint(160, 120),
            )),
        )
        for ((bus, ports) in expected) {
            val imported = read(fixture(bus))
            val saved = CircCodec.writeProject(imported)
            val reopened = read(saved)
            for (project in listOf(imported, reopened)) {
                val ram = project.mainCircuit.components.first()
                val layout = ram.layout()
                assertEquals(ComponentBounds(160, 60, 300, 140), layout.bounds)
                assertEquals(ports, layout.ports.associate { it.name to it.position })
                assertEquals(PortDirection.INOUT, layout.outputs.single().direction)
                assertTrue(layout.ports.all { terminal -> project.mainCircuit.wires.any { it.contains(terminal.position) } })
            }
            assertEquals(imported.mainCircuit.wires, reopened.mainCircuit.wires)
            if (bus == null) assertFalse(saved.contains("name=\"bus\""))
        }
    }

    @Test
    fun combinedRamWritesOnRisingEdgeAndReadsWithoutAClock() {
        val project = read(fixture(null))
        var state = step(project, SimulationState(), drive("data", 9, 4))
        assertEquals(0uL, cell(state))
        state = step(project, state, drive("clock", 1))
        assertEquals(9uL, cell(state))
        assertTrue(output(state).isUnknown)
        state = step(project, state, drive("data", 7, 4))
        assertEquals(9uL, cell(state))
        state = step(project, state, drive("load", 1), "data" to LogicVector.unknown(4))
        assertEquals(9uL, output(state).toULongOrNull())
        assertEquals(9uL, state.portVectors.getValue(GridPoint(360, 100)).toULongOrNull())
        state = step(project, state, drive("addr", 1, 2))
        assertEquals(0uL, output(state).toULongOrNull())
    }

    @Test
    fun asynchronousRamWritesWhileSelectedAndClearsWhileDisabled() {
        val imported = read(fixture("asynch"))
        val project = read(CircCodec.writeProject(imported))
        var state = step(project, SimulationState(), drive("data", 9, 4))
        assertEquals(9uL, cell(state))
        assertTrue(output(state).isUnknown)
        state = step(project, state, drive("addr", 1, 2), drive("data", 7, 4))
        assertEquals(7uL, cell(state, 1))
        state = step(project, state, drive("sel", 0), drive("data", 5, 4))
        assertEquals(7uL, cell(state, 1))
        assertTrue(output(state).isUnknown)
        state = step(project, state, drive("clear", 1))
        assertEquals(0uL, cell(state))
        assertEquals(0uL, cell(state, 1))
    }

    @Test
    fun separateRamUsesStoreAndLoadIndependently() {
        val project = read(fixture("separate"))
        var state = step(project, SimulationState(), drive("store", 0), drive("data", 5, 4), drive("clock", 1))
        assertEquals(0uL, cell(state))
        state = step(project, state, drive("clock", 0), drive("load", 1), "store" to LogicVector.unknown(1))
        state = step(project, state, drive("clock", 1))
        assertEquals(5uL, cell(state))
        assertEquals(5uL, output(state).toULongOrNull())
        state = step(project, state, drive("load", 0))
        assertTrue(output(state).isUnknown)
    }

    @Test
    fun explicitModernDataBusTakesPrecedenceAndEvolutionIgnoresLegacyBus() {
        val explicit = read(fixture("asynch", "<a name=\"databus\" val=\"bidir\"/>")).mainCircuit.components.first()
        assertNull(MemoryComponents.configuration(explicit).originalInterface)
        assertEquals(MemoryBus.SHARED, MemoryComponents.configuration(explicit).bus)
        val evolution = read(fixture("asynch", source = "5.1.0dev")).mainCircuit.components.first()
        assertNull(MemoryComponents.configuration(evolution).originalInterface)
        assertEquals(MemoryBus.SEPARATE, MemoryComponents.configuration(evolution).bus)
    }

    private fun read(xml: String): CircuitProject =
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project

    private fun step(project: CircuitProject, previous: SimulationState, vararg inputs: Pair<String, LogicVector>) =
        simulator.settle(project, previous, vectorInputs = mapOf(
            "addr" to LogicVector.low(2), "sel" to LogicVector.high(1), "load" to LogicVector.low(1),
            "clear" to LogicVector.low(1), "clock" to LogicVector.low(1),
        ) + previous.inputVectors + inputs).also { assertFalse(it.oscillating) }

    private fun drive(name: String, value: Int, bits: Int = 1) = name to LogicVector.of(bits, value.toULong())
    private fun output(state: SimulationState) = state.componentOutputs.getValue("ram").getValue("dataOut0_0")
    private fun cell(state: SimulationState, address: Long = 0) =
        MemoryComponents.image(state.componentStates.getValue("ram"))!![address]

    private fun fixture(bus: String?, extraAttributes: String = "", source: String = "2.7.1"): String {
        fun pin(id: String, x: Int, y: Int, bits: Int = 1) =
            """<comp lib="0" loc="($x,$y)" name="Pin" logisim-mobile-id="$id"><a name="width" val="$bits"/><a name="tristate" val="true"/></comp>"""
        fun wire(x: Int, y: Int, endX: Int, endY: Int) = """<wire from="($x,$y)" to="($endX,$endY)"/>"""
        return """
            <project source="$source" version="1.0">
              <lib desc="#Wiring" name="0"/>
              <lib desc="#Memory" name="1"/>
              <circuit name="main">
                <comp lib="1" loc="(300,100)" name="RAM" logisim-mobile-id="ram">
                  <a name="addrWidth" val="2"/><a name="dataWidth" val="4"/>
                  ${bus?.let { """<a name="bus" val="$it"/>""" }.orEmpty()}
                  $extraAttributes
                </comp>
                ${pin("addr", 100, 100, 2)} ${wire(100, 100, 160, 100)}
                ${pin("sel", 210, 160)} ${wire(210, 160, 210, 140)}
                ${pin("load", 250, 160)} ${wire(250, 160, 250, 140)}
                ${pin("clear", 270, 160)} ${wire(270, 160, 270, 140)}
                ${if (bus != "asynch") pin("clock", 230, 160) + wire(230, 160, 230, 140) else ""}
                ${if (bus == "separate") pin("store", 190, 160) + wire(190, 160, 190, 140) +
                    pin("data", 100, 120, 4) + wire(100, 120, 160, 120) else pin("data", 360, 100, 4)}
                ${wire(300, 100, 360, 100)}
              </circuit>
            </project>
        """.trimIndent()
    }
}
