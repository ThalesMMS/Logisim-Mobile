package dev.logisim.mobile

import dev.logisim.mobile.core.AnalysisCancelledException
import dev.logisim.mobile.core.TruthCell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PagedEvolutionTruthTableImportTest {
    @Test
    fun readsTwentyBitWildcardCsvWithOnePhysicalRecord() {
        val spool = TestAnalysisSpool()
        val header = (listOf("\"A[19..0]\"") + List(19) { "" } + listOf("\"|\"", "\"Y[1..0]\"", "")).joinToString(",")
        val row = (List(20) { "-" } + listOf("\"|\"", "-", "1")).joinToString(",")
        val imported = EvolutionTruthTableFile.importLines("wide.csv", sequenceOf(header, row), { spool })
        val table = assertIs<ImportedAnalysisTable.Paged>(imported).table
        assertEquals(1_048_576, table.rowCount)
        assertEquals(1, spool.records.size)
        assertEquals(listOf("A[19]", "A[0]"), listOf(table.inputs.first(), table.inputs.last()))
        assertEquals(listOf("Y[1]", "Y[0]"), table.outputs)
        val expected = listOf(TruthCell.DONT_CARE, TruthCell.ONE)
        assertEquals(listOf(expected), table.readRows(0, 1))
        assertEquals(listOf(expected), table.readRows(1_048_575, 1))
        val encoder = EvolutionTableEncoder(table.inputs, table.outputs, EvolutionTableFormat.TXT)
        val exported = encoder.rows(table, 0, table.recordCount) { false }
        assertEquals("xxxxxxxxxxxxxxxxxxxx | x1\n", exported)
        assertFalse(spool.closed)
        table.close()
        assertTrue(spool.closed)
    }

    @Test
    fun readsUnsortedExpandedThirteenBitTextIntoPages() {
        val bytes = buildString {
            append("A[12..0] | Y[19..0]\r\n")
            for (assignment in 8191 downTo 0) {
                append(assignment.toString(2).padStart(13, '0')).append(" | ")
                append(assignment.toString(2).padStart(20, '0')).append("\r\n")
            }
        }.encodeToByteArray()
        val input = object : AnalysisTableInput {
            override val name = "wide.txt"
            private var position = 0
            override fun read(buffer: ByteArray): Int {
                if (position == bytes.size) return -1
                val count = minOf(7, bytes.size - position)
                bytes.copyInto(buffer, startIndex = position, endIndex = position + count)
                position += count
                return count
            }
            override fun close() = Unit
        }
        val file = TestSpoolFile(8192 * 9)
        val imported = EvolutionTruthTableFile.importLines(input.name, input.lines { false }, {
            BufferedAnalysisTableSpool(it, file)
        })
        val table = assertIs<ImportedAnalysisTable.Paged>(imported).table
        assertEquals(8192, table.recordCount)
        val boundary = table.readRows(127, 2)
        assertEquals(List(13) { TruthCell.ZERO } + List(7) { TruthCell.ONE }, boundary[0])
        assertEquals(List(12) { TruthCell.ZERO } + TruthCell.ONE + List(7) { TruthCell.ZERO }, boundary[1])
        assertEquals(
            listOf(List(7) { TruthCell.ZERO } + List(13) { TruthCell.ONE }),
            table.readRows(8191, 1),
        )
        table.close()
        assertTrue(file.closed)
    }

    @Test
    fun compatibleOverlapsMatchTheFirstCoveringRowAndApplyInFileOrder() {
        val header = "A[12..0] | Y[1..0]"
        val rows = listOf("------------- | x1", "0000000000000 | 11", "0000000000000 | 01",
            "1111111111111 | 1x")
        val imported = EvolutionTruthTableFile.importLines("overlap.txt", (listOf(header) + rows).asSequence(), { TestAnalysisSpool() })
        val table = assertIs<ImportedAnalysisTable.Paged>(imported).table
        assertEquals(listOf(listOf(TruthCell.ZERO, TruthCell.ONE), listOf(TruthCell.DONT_CARE, TruthCell.ONE)),
            table.readRows(0, 2))
        assertEquals(listOf(listOf(TruthCell.ONE, TruthCell.DONT_CARE)), table.readRows(8191, 1))
        val encoder = EvolutionTableEncoder(table.inputs, table.outputs, EvolutionTableFormat.TXT)
        assertEquals("xxxxxxxxxxxxx | x1\n0000000000000 | 11\n0000000000000 | 01\n1111111111111 | 1x\n",
            encoder.rows(table, 0, table.recordCount) { false })
        table.close()
    }

    @Test
    fun keepsMoreThanThirtyTwoCompatibleTwentyBitWildcardRows() {
        val wildcard = "x".repeat(20)
        val rows = sequence {
            yield("A[19..0] | Y")
            repeat(33) { yield("$wildcard | x") }
            yield("$wildcard | 1")
        }
        val imported = EvolutionTruthTableFile.importLines("overlap.txt", rows, { TestAnalysisSpool() })
        val table = assertIs<ImportedAnalysisTable.Paged>(imported).table
        assertEquals(34, table.recordCount)
        assertEquals(listOf(listOf(TruthCell.ONE)), table.readRows(0, 1))
        assertEquals(listOf(listOf(TruthCell.ONE)), table.readRows(1_048_575, 1))
        table.close()
    }

    @Test
    fun checksOverlapsAgainstFlushedAndPendingSpoolRecords() {
        val source = sequence {
            yield("A[12..0] | Y[79..0]")
            for (assignment in 0..8191) {
                yield("${assignment.toString(2).padStart(13, '0')} | ${"x".repeat(80)}")
            }
            yield("0000000000000 | ${"1".repeat(80)}")
            yield("1111111111111 | ${"0".repeat(80)}")
        }
        val file = TestSpoolFile(8194 * 24)
        val imported = EvolutionTruthTableFile.importLines("wide.txt", source, { BufferedAnalysisTableSpool(it, file) })
        val table = assertIs<ImportedAnalysisTable.Paged>(imported).table
        assertEquals(8194, table.recordCount)
        assertEquals(listOf(List(80) { TruthCell.ONE }, List(80) { TruthCell.DONT_CARE }), table.readRows(0, 2))
        assertEquals(listOf(List(80) { TruthCell.ZERO }), table.readRows(8191, 1))
        table.close()
    }

    @Test
    fun releasesStagedDataAfterIncompleteConflictingOrCancelledImport() {
        val header = "A[12..0] | Y"
        val wildcard = "------------- | 1"
        for (rows in listOf(listOf("0000000000000 | 1"), listOf(wildcard, "0000000000000 | 0"))) {
            val spool = TestAnalysisSpool()
            assertFailsWith<IllegalArgumentException> {
                EvolutionTruthTableFile.importLines("wide.txt", (listOf(header) + rows).asSequence(), { spool })
            }
            assertTrue(spool.closed)
        }

        val spool = TestAnalysisSpool()
        var checks = 0
        assertFailsWith<AnalysisCancelledException> {
            EvolutionTruthTableFile.importLines("wide.txt", sequenceOf(header, wildcard), { spool }) { ++checks > 200 }
        }
        assertTrue(spool.closed)
    }

    private class TestSpoolFile(size: Int) : AnalysisSpoolFile {
        private val bytes = ByteArray(size)
        var closed = false
        override fun write(offset: Int, bytes: ByteArray, count: Int) {
            check(!closed)
            bytes.copyInto(this.bytes, offset, endIndex = count)
        }
        override fun read(offset: Int, bytes: ByteArray, count: Int) {
            check(!closed)
            this.bytes.copyInto(bytes, startIndex = offset, endIndex = offset + count)
        }
        override fun close() {
            closed = true
        }
    }

    private class TestAnalysisSpool : AnalysisTableSpool {
        val records = mutableListOf<ByteArray>()
        var closed = false
        override fun append(record: ByteArray) {
            check(!closed)
            records += record.copyOf()
        }
        override fun seal() = Unit
        override fun read(index: Int, record: ByteArray) {
            check(!closed)
            records[index].copyInto(record)
        }
        override fun close() {
            closed = true
        }
    }
}
