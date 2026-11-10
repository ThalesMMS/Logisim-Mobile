package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TestVectorQuotedLabelsTest {
    @Test
    fun readsMultiwordLabelsAndWidthsBeforeTrailingComments() {
        val table = TestVectorFormat.read("""
            # Data bus vectors
            "Data In"[8] "Data Out"[8]# output comment
            0xA5 0xAx # row comment
        """.trimIndent())
        assertEquals(listOf(VectorColumn("Data In", 8), VectorColumn("Data Out", 8)), table.columns)
        assertEquals(3, table.rows.single().lineNumber)
        assertEquals(0xA5uL, assertIs<VectorCell.Value>(table.rows.single().values[0]).value.toULongOrNull())
        assertEquals(15uL, assertIs<VectorCell.Value>(table.rows.single().values[1]).value.unknownMask)
    }

    @Test
    fun treatsHashesAndBracketsInsideQuotesAsLiteralNameText() {
        val table = TestVectorFormat.read("""
            "Data # In" "Data Out[8]"[4] # suffix declares four bits
            1 0xF
        """.trimIndent())
        assertEquals(listOf(VectorColumn("Data # In"), VectorColumn("Data Out[8]", 4)), table.columns)
        assertEquals(15uL, assertIs<VectorCell.Value>(table.rows.single().values[1]).value.toULongOrNull())
    }

    @Test
    fun decodesOnlyEscapedQuotesAndBackslashesInNames() {
        val table = TestVectorFormat.read("""
            "Path\\Name" "Quote\"Out"
            0 1
        """.trimIndent())
        assertEquals(listOf("Path\\Name", "Quote\"Out"), table.columns.map { it.name })
    }

    @Test
    fun quotedMetadataNamesRemainPins() {
        val table = TestVectorFormat.read("\"<set>\"[4] <set> <seq>\n0xF 1 1\n")
        assertEquals(listOf(VectorColumn("<set>", 4)), table.columns)
        assertEquals(1, table.rows.single().set)
        assertEquals(1, table.rows.single().sequence)
    }

    @Test
    fun quotesUnsafeProgrammaticNamesAndRoundTripsThemExactly() {
        val table = VectorTable(listOf(VectorColumn("Data In", 8), VectorColumn("Data # Out", 8),
            VectorColumn("Quote\"Out"), VectorColumn("Path\\Name"), VectorColumn("Data[8]", 4)), emptyList())
        val written = TestVectorFormat.write(table)
        assertEquals("\"Data In\"[8]\t\"Data # Out\"[8]\t\"Quote\\\"Out\"\t\"Path\\\\Name\"\t\"Data[8]\"[4]\n", written)
        assertEquals(table.columns, TestVectorFormat.read(written).columns)
    }

    @Test
    fun preservesTheUnquotedDesktopHeaderAndValueSyntax() {
        val table = TestVectorFormat.read("A[8] B[8] Y[8]\n0x10 0o20 0010_0000\n")
        assertEquals("A[8]\tB[8]\tY[8]\n00010000\t00010000\t00100000\n", TestVectorFormat.write(table))
    }

    @Test
    fun rejectsDuplicateNamesAcrossQuotedAndUnquotedColumns() {
        assertFailsWith<VectorFormatException> { TestVectorFormat.read("A \"A\"\n0 0\n") }
        assertFailsWith<VectorFormatException> { TestVectorFormat.read("\"Data In\"[8] \"Data In\"[4]\n0 0\n") }
    }

    @Test
    fun rejectsMalformedSuffixesWithoutReinterpretingTheQuotedName() {
        for (header in listOf("\"Data In\"oops", "\"Data In\"[0]", "\"Data In\"[65]", "\"Data In\"[]", "\"Data In\" [8]")) {
            assertFailsWith<VectorFormatException>(header) { TestVectorFormat.read(header) }
        }
    }

    @Test
    fun quoteFailuresKeepTheirSourceLineAndLocalizedIdentity() {
        val error = assertFailsWith<VectorFormatException> { TestVectorFormat.read("# first\n\n\"Data In\n") }
        assertEquals(3, error.lineNumber)
        assertEquals("vector_unterminated_quote", error.detail.key)
        assertEquals("Line 3: The quoted pin name is not closed.", error.text.resolve(AppLanguage.ENGLISH))
        assertEquals("Linha 3: O nome de pino entre aspas não foi fechado.", error.text.resolve(AppLanguage.PORTUGUESE))
        val escaped = assertFailsWith<VectorFormatException> { TestVectorFormat.read("\"Data\\qIn\"\n0\n") }
        assertEquals("vector_invalid_escape", escaped.detail.key)
        assertEquals("\\q", escaped.detail.arguments["escape"])
    }

    @Test
    fun refusesQuotedDataAndUnclosedNamesBeforeComments() {
        val quotedData = assertFailsWith<VectorFormatException> { TestVectorFormat.read("A\n\"1\"\n") }
        assertEquals("vector_quoted_data", quotedData.detail.key)
        assertFailsWith<VectorFormatException> { TestVectorFormat.read("\"Data # comment does not terminate a quote\n") }
        assertFailsWith<VectorFormatException> { TestVectorFormat.read("\"Data\\") }
    }

    @Test
    fun executesAndReplaysARealBusCircuitWithMultiwordLabels() {
        val input = CircuitComponent("input", ComponentKind.INPUT, GridPoint(10, 10), "Data In",
            attributes = mapOf("width" to "8"))
        val output = CircuitComponent("output", ComponentKind.OUTPUT, GridPoint(40, 10), "Data # Out",
            attributes = mapOf("width" to "8"))
        val circuit = CircuitDocument("Quoted datapath", listOf(input, output),
            listOf(WireSegment(input.position, output.position)))
        val table = TestVectorFormat.read("\"Data In\"[8] \"Data # Out\"[8]\n0xA5 0xAx\n0x3D 0x3D\n<float> <float>\n1 0\n")
        // Only original Logisim projects keep multiword labels; Evolution renames them when it opens a file (#321).
        val runner = TestVectorRunSession(CircuitProject(listOf(circuit), sourceVersion = "2.7.1"), circuit.name, table)
        runner.run()
        assertEquals(3, runner.passedCount)
        assertEquals(1, runner.failedCount)
        assertEquals(VectorDifference("Data # Out", "00000000", "00000001"), runner.results.last().differences.single())
        val replay = runner.exportReplay()
        assertTrue(replay.vectorText.startsWith("\"Data In\"[8]\t\"Data # Out\"[8]\n"))
        val reopened = assertIs<CircProjectImportResult.Success>(CircCodec.readProject(replay.projectXml)).project
        val rerun = TestVectorRunSession(reopened, replay.circuitName, TestVectorFormat.read(replay.vectorText))
        rerun.run()
        assertEquals(runner.results, rerun.results)
    }
}
