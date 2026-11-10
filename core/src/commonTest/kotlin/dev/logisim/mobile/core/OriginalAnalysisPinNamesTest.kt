package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OriginalAnalysisPinNamesTest {
    @Test
    fun analyzesDuplicateLabelsInCanvasOrderWithoutChangingTheCircuit() {
        val project = loadCircuit("Data bit", "Data bit", "Y", "Z")
        val labels = project.mainCircuit.components.map { it.label }
        val table = CircuitAnalyzer.truthTable(project)

        assertEquals(listOf("DataBit", "DataBit2"), table.inputs)
        assertEquals(listOf("Y", "Z"), table.outputs)
        assertEquals(listOf("00", "01", "10", "11"), table.rows.map { row -> row.joinToString("") { it.symbol } })
        assertEquals(listOf("Data bit", "Z", "Data bit", "Y"), labels)
        assertEquals(labels, project.mainCircuit.components.map { it.label })
    }

    @Test
    fun choosesDefaultsAfterReservingExplicitNamesAcrossInputAndOutputPins() {
        val table = CircuitAnalyzer.truthTable(loadCircuit("", "123!", "a", "x"))

        assertEquals(listOf("b", "c"), table.inputs)
        assertEquals(listOf("a", "x"), table.outputs)
        assertEquals(listOf("00", "01", "10", "11"), table.rows.map { row -> row.joinToString("") { it.symbol } })
    }

    private fun loadCircuit(topInput: String, bottomInput: String, topOutput: String, bottomOutput: String): CircuitProject {
        val xml = """
            <project source="2.7.1" version="1.0">
              <lib name="0" desc="#Wiring"/>
              <main name="main"/>
              <circuit name="main">
                <comp lib="0" loc="(100,200)" name="Pin"><a name="label" val="$bottomInput"/></comp>
                <comp lib="0" loc="(200,200)" name="Pin">
                  <a name="facing" val="west"/><a name="output" val="true"/><a name="label" val="$bottomOutput"/>
                </comp>
                <comp lib="0" loc="(100,100)" name="Pin"><a name="label" val="$topInput"/></comp>
                <comp lib="0" loc="(200,100)" name="Pin">
                  <a name="facing" val="west"/><a name="output" val="true"/><a name="label" val="$topOutput"/>
                </comp>
                <wire from="(100,100)" to="(200,100)"/>
                <wire from="(100,200)" to="(200,200)"/>
              </circuit>
            </project>
        """.trimIndent()
        return assertIs<CircProjectImportResult.Success>(CircCodec.readProject(xml)).project
    }
}
