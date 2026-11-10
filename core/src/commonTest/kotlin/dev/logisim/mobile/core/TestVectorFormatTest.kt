package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TestVectorFormatTest {
    private fun values(row: VectorRow) = row.values.map { assertIs<VectorCell.Value>(it).value }

    @Test
    fun readsCommentsWidthsAndNumericRadices() {
        val table = TestVectorFormat.read("""
            # Captured adder data
            A[8] B[8] C[8] D[8] E[8]
            -2 0xFF 0o377 1111_1111 0b101 # mixed bases
        """.trimIndent())
        assertEquals(listOf("A", "B", "C", "D", "E"), table.columns.map { it.name })
        assertEquals(listOf(254uL, 255uL, 255uL, 255uL, 5uL), values(table.rows.single()).map { it.toULongOrNull() })
        assertEquals(3, table.rows.single().lineNumber)
    }

    @Test
    fun numericLengthSelectsBinaryOrSignedDecimal() {
        val table = TestVectorFormat.read("A[4] B[8]\n1111 10\n")
        assertEquals(listOf(15uL, 10uL), values(table.rows.single()).map { it.toULongOrNull() })
        val failure = assertFailsWith<VectorFormatException> { TestVectorFormat.read("A[8]\n128\n") }
        assertEquals(2, failure.lineNumber)
        assertTrue(failure.message.orEmpty().contains("A"))
    }

    @Test
    fun readsSpecialCellsCaseInsensitively() {
        val table = TestVectorFormat.read("A[8] B[8] C[8]\n<dC> <FlOaT> 0xFx\n")
        assertEquals(VectorCell.DontCare, table.rows.single().values[0])
        assertEquals(VectorCell.Floating, table.rows.single().values[1])
        val partial = assertIs<VectorCell.Value>(table.rows.single().values[2]).value
        assertEquals(240uL, partial.value)
        assertEquals(15uL, partial.unknownMask)
        assertEquals(0uL, partial.errorMask)
    }

    @Test
    fun keepsUnknownBitsAtWidth64() {
        val table = TestVectorFormat.read("A[64] B[64]\n${"x".repeat(64)} 0x8000_0000_0000_0001\n")
        val row = values(table.rows.single())
        assertEquals(ULong.MAX_VALUE, row[0].unknownMask)
        assertEquals(0uL, row[0].value)
        assertEquals(0x8000000000000001uL, row[1].toULongOrNull())
        assertFailsWith<VectorFormatException> {
            TestVectorFormat.read("A[64]\n0o7777777777777777777777\n")
        }
    }

    @Test
    fun supportsInterleavedSetAndSequenceColumns() {
        val table = TestVectorFormat.read("<SET> A <sEq> B\n0 0 0 1\n1 1 2 0\n1 0 7 1\n2 1 1 0\n")
        assertEquals(listOf("A", "B"), table.columns.map { it.name })
        assertEquals(listOf(0, 1, 1, 2), table.rows.map { it.set })
        assertEquals(listOf(0, 2, 7, 1), table.rows.map { it.sequence })
        assertEquals(listOf(1uL, 0uL), values(table.rows[1]).map { it.toULongOrNull() })
    }

    @Test
    fun rejectsUnorderedSequencesAndMixedMetadata() {
        for (rows in listOf("0 0 1", "0 1 0", "0 1 1\n1 1 1", "0 2 1\n1 1 2", "0 1 -1")) {
            assertFailsWith<VectorFormatException>(rows) { TestVectorFormat.read("A <set> <seq>\n$rows\n") }
        }
    }

    @Test
    fun rejectsDuplicateOrMalformedHeaders() {
        for (header in listOf("A A", "A <set> <SET>", "A <seq> <SEQ>", "A[0]", "A[65]", "A[]", "A[8", "<set> <seq>")) {
            assertFailsWith<VectorFormatException>(header) { TestVectorFormat.read(header) }
        }
    }

    @Test
    fun reportsActualSourceLineForWrongValueCounts() {
        for (row in listOf("0", "0 1 0")) {
            val failure = assertFailsWith<VectorFormatException> { TestVectorFormat.read("# first\nA B\n\n$row\n") }
            assertEquals(4, failure.lineNumber)
        }
    }

    @Test
    fun rejectsIllegalDigitsAndOversizedValues() {
        for (token in listOf("0XFF", "0xGG", "0o9", "0b102", "0x100", "0x10000000000000000", "0x", "<other>", "256")) {
            assertFailsWith<VectorFormatException>(token) { TestVectorFormat.read("A[8]\n$token\n") }
        }
    }

    @Test
    fun writesReproducibleRowsWithoutLosingMasksOrSequences() {
        val parsed = TestVectorFormat.read("A[8] B[8] C[8] <set> <seq>\n0xA5 0xFx <DC> 1 1\n<float> -1 0b10101 1 3\n")
        val output = TestVectorFormat.write(parsed)
        assertEquals("A[8]\tB[8]\tC[8]\t<set>\t<seq>\n10100101\t1111xxxx\t<DC>\t1\t1\n<float>\t11111111\t00010101\t1\t3\n", output)
        val reparsed = TestVectorFormat.read(output)
        assertEquals(parsed.columns, reparsed.columns)
        assertEquals(parsed.rows.map { it.values }, reparsed.rows.map { it.values })
        assertEquals(parsed.rows.map { it.set to it.sequence }, reparsed.rows.map { it.set to it.sequence })
    }

    @Test
    fun refusesToExportUnrepresentableErrorBits() {
        val table = VectorTable(listOf(VectorColumn("Y")), listOf(VectorRow(2,
            listOf(VectorCell.Value(LogicVector.error(1))))))
        assertFailsWith<VectorFormatException> { TestVectorFormat.write(table) }
    }

    @Test
    fun rejectsEmptyFileAndInvalidProgrammaticHeaders() {
        assertFailsWith<VectorFormatException> { TestVectorFormat.read("# only a comment\n\n") }
        assertFailsWith<VectorFormatException> { TestVectorFormat.write(VectorTable(listOf(VectorColumn("two\nlines")), emptyList())) }
    }
}
