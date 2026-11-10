package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OriginalSplitterImportTest {
    @Test
    fun omittedBitsMatchOriginalBranchWidthsAtTheirCoordinates() {
        val legacy = read(splitterProject("2.7.1"))
        val south = legacy.mainCircuit.components[0]
        val west = legacy.mainCircuit.components[1]
        assertEquals(listOf(0, 1, 2, 3, 2, 2, 3, 3), south.splitterBitEnds())
        assertEquals(listOf(3, 3, 1, 1), (360..390 step 10).map { south.portWidths()[GridPoint(it, 620)] })
        assertEquals(listOf(1, 1, 3, 3), (580..610 step 10).map { west.portWidths()[GridPoint(580, it)] })
    }

    @Test
    fun explicitMappingsSurviveAlongsideOriginalDefaults() {
        val legacy = read(splitterProject("2.7.1", """
            <a name="bit1" val="none"/>
            <a name="bit6" val="0"/>
        """.trimIndent()))
        val mapping = listOf(0, null, 2, 3, 2, 2, 0, 3)
        assertEquals(mapping, legacy.mainCircuit.components.first().splitterBitEnds())

        val xml = CircCodec.writeProject(legacy)
        assertTrue(xml.contains("""<a name="bit1" val="none"/>"""))
        assertTrue(xml.contains("""<a name="bit6" val="0"/>"""))
        assertEquals(mapping, read(xml).mainCircuit.components.first().splitterBitEnds())
    }

    @Test
    fun exportPreservesMobileAuthoredSplittersInBothProfiles() {
        val splitter = CircuitComponent("split", ComponentKind.SPLITTER, GridPoint(400, 600),
            attributes = mapOf("incoming" to "8", "fanout" to "4"))
        val project = CircuitProject(listOf(CircuitDocument(components = listOf(splitter))))
        val mapping = listOf(0, 0, 1, 1, 2, 2, 3, 3)
        assertEquals(mapping, splitter.splitterBitEnds())

        for (source in listOf("2.7.1", "5.1.0-dev")) {
            val export = CircCodec.writeProject(project.copy(sourceVersion = source))
            assertEquals(mapping, read(export).mainCircuit.components.single().splitterBitEnds())
        }
    }

    private fun read(xml: String): CircuitProject =
        assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project

    private fun splitterProject(source: String, extra: String = "") = """
        <project version="1.0" source="$source">
          <lib name="0" desc="#Wiring"/>
          <main name="main"/>
          <circuit name="main">
            <comp lib="0" loc="(400,600)" name="Splitter">
              <a name="facing" val="south"/>
              <a name="incoming" val="8"/>
              <a name="fanout" val="4"/>
              <a name="appear" val="right"/>
              $extra
            </comp>
            <comp lib="0" loc="(600,600)" name="Splitter">
              <a name="facing" val="west"/>
              <a name="incoming" val="8"/>
              <a name="fanout" val="4"/>
              <a name="appear" val="center"/>
            </comp>
          </circuit>
        </project>
    """.trimIndent()
}
