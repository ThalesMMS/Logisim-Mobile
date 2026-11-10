package dev.logisim.mobile

import dev.logisim.mobile.core.AnalysisCancelledException
import dev.logisim.mobile.core.CircuitAnalyzer
import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.CircuitProject
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.TruthCell
import dev.logisim.mobile.core.TruthTable
import dev.logisim.mobile.core.WireSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EvolutionTruthTableFileTest {
    @Test
    fun xorUsesEvolutionHeadersAndBitOrderInBothFormats() {
        val xor = TruthTable(
            listOf("A", "B"), listOf("Y"), listOf(
                listOf(TruthCell.ZERO), listOf(TruthCell.ONE), listOf(TruthCell.ONE), listOf(TruthCell.ZERO),
            ),
        )
        val csv = EvolutionTruthTableFile.write(xor, EvolutionTableFormat.CSV)
        assertEquals("truth-table.csv", csv.name)
        assertEquals("text/csv", csv.mimeType)
        assertEquals("\"A\",\"B\",\"|\",\"Y\"\n0,0,\"|\",0\n0,1,\"|\",1\n1,0,\"|\",1\n1,1,\"|\",0\n", csv.bytes.decodeToString())
        val txt = EvolutionTruthTableFile.write(xor, EvolutionTableFormat.TXT)
        assertEquals("A B | Y\n0 0 | 0\n0 1 | 1\n1 0 | 1\n1 1 | 0\n", txt.bytes.decodeToString())
        for (asset in listOf(csv, txt)) assertEquals(xor, EvolutionTruthTableFile.read(asset).table)
    }

    @Test
    fun busAndDontCareRowsRoundTripAcrossFormatsWithOriginalVariableOrder() {
        val imported = EvolutionTruthTableFile.read(asset("bus.txt", """
            # Exported truth table
            Flag Data[3..0] | Result[1..0] Valid
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            1 - | 1x 1
            0 x | 0X -
        """.trimIndent()))
        val table = imported.table
        assertEquals(listOf("Flag", "Data[3]", "Data[2]", "Data[1]", "Data[0]"), table.inputs)
        assertEquals(listOf("Result[1]", "Result[0]", "Valid"), table.outputs)
        assertEquals(listOf(TruthCell.ZERO, TruthCell.DONT_CARE, TruthCell.DONT_CARE), table.rows.first())
        assertEquals(listOf(TruthCell.ONE, TruthCell.DONT_CARE, TruthCell.ONE), table.rows.last())
        val csv = imported.export(table, EvolutionTableFormat.CSV)
        assertEquals(
            "\"Flag\",\"Data[3..0]\",,,,\"|\",\"Result[1..0]\",,\"Valid\"\n" +
                "1,x,x,x,x,\"|\",1,x,1\n0,x,x,x,x,\"|\",0,x,x\n",
            csv.bytes.decodeToString(),
        )
        val reopened = EvolutionTruthTableFile.read(csv)
        assertEquals(table, reopened.table)
        assertEquals(
            "Flag Data[3..0] | Result[1..0] Valid\n1 xxxx | 1x 1\n0 xxxx | 0x x\n",
            reopened.export(table, EvolutionTableFormat.TXT).bytes.decodeToString(),
        )
        val updated = table.withCell(0, 0, TruthCell.ONE)
        val exportedEdit = imported.export(updated, EvolutionTableFormat.TXT)
        assertEquals("1 xxxx | 1x 1", exportedEdit.bytes.decodeToString().lineSequence().drop(1).first())
        assertEquals(updated, EvolutionTruthTableFile.read(exportedEdit).table)
    }

    @Test
    fun acceptsBitIndexHeadersAndRejectsIncompleteOrMalformedFiles() {
        val allDontCare = EvolutionTruthTableFile.read(asset("dont-care.txt", "A | Y\n---\n- | -\n"))
        assertEquals(List(2) { listOf(TruthCell.DONT_CARE) }, allDontCare.table.rows)
        assertEquals("A | Y\nx | x\n", allDontCare.export(allDontCare.table, EvolutionTableFormat.TXT).bytes.decodeToString())
        val valid = asset("bits.csv", "D:1,D:0,|,Q[2],\nX,-,|,0,1\n")
        val table = EvolutionTruthTableFile.read(valid).table
        assertEquals(listOf("D[1]", "D[0]"), table.inputs)
        assertEquals(listOf("Q[1]", "Q[0]"), table.outputs)
        for (invalid in listOf(
            asset("missing.txt", "A | Y\n0 | 1\n"),
            asset("duplicate.txt", "A | Y\nx | 0\n1 | 1\n"),
            asset("bad-hex.txt", "A[4..0] | Y\nFx | 1\n"),
            asset("bad-header.csv", "D[1..0],D,|,Y\nx,x,|,1\n"),
            asset("bad-order.csv", "D:1,D:2,|,Y\nx,x,|,1\n"),
            asset("bad-quote.csv", "A,|,\"Y\n-,|,1\n"),
        )) assertFailsWith<IllegalArgumentException>(invalid.name) { EvolutionTruthTableFile.read(invalid) }
        assertFailsWith<AnalysisCancelledException> { EvolutionTruthTableFile.read(valid, cancelled = { true }) }
    }

    @Test
    fun pagedCircuitExportUsesEvolutionBusHeadersAndRowOrder() {
        val from = GridPoint(100, 100)
        val to = GridPoint(180, 100)
        val bus = mapOf("width" to "2")
        val source = CircuitAnalyzer.pagedTruthTable(CircuitProject(listOf(
            CircuitDocument("main", components = listOf(
                CircuitComponent("input", ComponentKind.INPUT, from, "A", bus),
                CircuitComponent("output", ComponentKind.OUTPUT, to, "Y", bus),
            ), wires = listOf(WireSegment(from, to))),
        )))
        val csv = EvolutionTableEncoder(source.inputs, source.outputs, EvolutionTableFormat.CSV)
        val txt = EvolutionTableEncoder(source.inputs, source.outputs, EvolutionTableFormat.TXT)
        fun exported(encoder: EvolutionTableEncoder) =
            encoder.header() + encoder.rows(source, 0, 1) { false } + encoder.rows(source, 1, 3) { false }
        assertEquals(
            "\"A[1..0]\",,\"|\",\"Y[1..0]\",\n0,0,\"|\",0,0\n0,1,\"|\",0,1\n1,0,\"|\",1,0\n1,1,\"|\",1,1\n",
            exported(csv),
        )
        assertEquals("A[1..0] | Y[1..0]\n00 | 00\n01 | 01\n10 | 10\n11 | 11\n", exported(txt))
    }

    @Test
    fun csvExportRejectsNamesThatEvolutionCannotReopen() {
        fun table(input: String) = TruthTable(
            listOf(input), listOf("Y"), listOf(listOf(TruthCell.ZERO), listOf(TruthCell.ONE)),
        )
        for (name in listOf("_A", "Ç", "A__", "A_", "SIGNAL", "module")) {
            assertFailsWith<IllegalArgumentException>(name) {
                EvolutionTruthTableFile.write(table(name), EvolutionTableFormat.CSV)
            }
        }
        val valid = table("Module")
        val csv = EvolutionTruthTableFile.write(valid, EvolutionTableFormat.CSV)
        assertEquals(valid, EvolutionTruthTableFile.read(csv).table)
        val txt = table("signal")
        assertEquals(txt, EvolutionTruthTableFile.read(EvolutionTruthTableFile.write(txt, EvolutionTableFormat.TXT)).table)
    }

    private fun asset(name: String, text: String) = DocumentAsset(name, "application/octet-stream", text.encodeToByteArray())
}
