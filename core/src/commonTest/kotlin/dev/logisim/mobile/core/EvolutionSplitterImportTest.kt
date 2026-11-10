package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EvolutionSplitterImportTest {
    @Test
    fun omittedBitsKeepEvolutionWidthsAndSignalsAfterExport() {
        val project = read(projectXml())
        val exported = CircCodec.writeProject(project)
        assertTrue(exported.contains("""<a name="bit1" val="1"/>"""))
        assertTrue(exported.contains("""<a name="bit2" val="2"/>"""))
        for (candidate in listOf(project, read(exported))) {
            val circuit = candidate.mainCircuit
            val splitter = circuit.components.first()
            assertEquals(listOf(0, 1, 2, 1, 1, 1, 2, 2), splitter.splitterBitEnds())
            assertEquals(mapOf(GridPoint(120, 120) to 8, GridPoint(110, 140) to 3,
                GridPoint(120, 140) to 4, GridPoint(130, 140) to 1), splitter.portWidths())

            val inputId = circuit.components.single { it.label == "InputBus" }.id
            val state = CircuitSimulator().settle(candidate, vectorInputs = mapOf(inputId to LogicVector.of(8, 0xdauL)))
            assertEquals(listOf(6uL, 7uL, 0uL), (110..130 step 10).map { x ->
                state.portVectors.getValue(GridPoint(x, 180)).toULongOrNull()
            })
        }
    }

    @Test
    fun previousMobileExportRetainsItsBalancedOmittedBits() {
        val oldExport = projectXml().replace("""name="Splitter"""", """name="Splitter" logisim-mobile-id="saved-splitter"""")
        val project = read(oldExport)
        val mapping = listOf(0, 0, 0, 1, 1, 1, 2, 2)
        assertEquals(mapping, project.mainCircuit.components.first().splitterBitEnds())

        val saved = CircCodec.writeProject(project)
        assertTrue(saved.contains("""<a name="bit1" val="0"/>"""))
        assertTrue(saved.contains("""<a name="bit2" val="0"/>"""))
        assertEquals(mapping, read(saved).mainCircuit.components.first().splitterBitEnds())
    }

    @Test
    fun omittedBitsAndExplicitOverridesUseTheSameProfileRules() {
        val fourWay = read(projectXml(fanout = 4)).mainCircuit.components.first()
        assertEquals(listOf(0, 1, 2, 3, 2, 2, 3, 3), fourWay.splitterBitEnds())

        val project = read(projectXml(extra = """<a name="bit1" val="none"/><a name="bit6" val="0"/>"""))
        val mapping = listOf(0, null, 2, 1, 1, 1, 0, 2)
        assertEquals(mapping, project.mainCircuit.components.first().splitterBitEnds())
        val exported = CircCodec.writeProject(project)
        assertTrue(exported.contains("""<a name="bit1" val="none"/>"""))
        assertTrue(exported.contains("""<a name="bit6" val="0"/>"""))
        assertEquals(mapping, read(exported).mainCircuit.components.first().splitterBitEnds())
    }

    private fun read(xml: String): CircuitProject =
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project

    private fun projectXml(fanout: Int = 3, extra: String = "") = """
        <project source="5.1.0-dev" version="1.0">
          <lib desc="#Wiring" name="0"/>
          <main name="main"/>
          <circuit name="main">
            <comp lib="0" name="Splitter" loc="(120,120)">
              <a name="incoming" val="8"/><a name="fanout" val="$fanout"/>
              <a name="facing" val="south"/><a name="appear" val="center"/>$extra
            </comp>
            <comp lib="0" name="Pin" loc="(120,80)">
              <a name="facing" val="south"/><a name="width" val="8"/><a name="label" val="InputBus"/>
            </comp>
            <comp lib="0" name="Pin" loc="(110,180)">
              <a name="type" val="output"/><a name="width" val="3"/>
            </comp>
            <comp lib="0" name="Pin" loc="(120,180)">
              <a name="type" val="output"/><a name="width" val="4"/>
            </comp>
            <comp lib="0" name="Pin" loc="(130,180)">
              <a name="type" val="output"/><a name="width" val="1"/>
            </comp>
            <wire from="(120,80)" to="(120,120)"/>
            <wire from="(110,140)" to="(110,180)"/>
            <wire from="(120,140)" to="(120,180)"/>
            <wire from="(130,140)" to="(130,180)"/>
          </circuit>
        </project>
    """.trimIndent()
}
