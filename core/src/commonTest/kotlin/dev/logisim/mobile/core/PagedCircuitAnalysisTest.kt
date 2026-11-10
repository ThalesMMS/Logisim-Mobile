package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PagedCircuitAnalysisTest {
    @Test
    fun evaluatesThirteenBitCircuitAcrossAPageBoundary() {
        val source = CircuitAnalyzer.pagedTruthTable(busProject(13))
        assertEquals(8_192, source.rowCount)
        assertEquals(listOf("A[12]", "A[0]"), listOf(source.inputs.first(), source.inputs.last()))
        assertEquals(listOf(
            List(6) { TruthCell.ZERO } + List(7) { TruthCell.ONE },
            List(5) { TruthCell.ZERO } + TruthCell.ONE + List(7) { TruthCell.ZERO },
        ), source.readRows(127, 2))
    }

    @Test
    fun jumpsToEndOfTwentyBitCircuitAndExportsTheRequestedRows() {
        val source = CircuitAnalyzer.pagedTruthTable(busProject(20))
        assertEquals(1_048_576, source.rowCount)
        assertEquals(List(19) { TruthCell.ONE } + TruthCell.ZERO, source.readRows(1_048_574, 1).single())
        assertEquals(List(20) { TruthCell.ONE }, source.readRows(1_048_575, 1).single())
        assertEquals(List(40) { "1" }.joinToString(",") + "\n",
            source.csvRows(1_048_575, 1))
        assertEquals("A[19]", source.csvHeader().substringBefore(','))
        assertEquals("Y[0]\n", source.csvHeader().substringAfterLast(','))
    }

    @Test
    fun cancelsAnEvaluationAndReadsAnotherPageFromTheSameSource() {
        val source = CircuitAnalyzer.pagedTruthTable(busProject(20))
        var checks = 0
        assertFailsWith<AnalysisCancelledException> {
            source.csvRows(0, 2_048) { ++checks == 40 }
        }
        assertEquals(List(20) { TruthCell.ZERO }, source.readRows(0, 1).single())
    }

    @Test
    fun twentyOneInputBitsExceedTheLimit() {
        assertFailsWith<AnalysisLimitException> { CircuitAnalyzer.pagedTruthTable(busProject(21)) }
    }

    private fun busProject(width: Int): CircuitProject {
        val left = GridPoint(100, 100)
        val right = GridPoint(180, 100)
        val attributes = mapOf("width" to width.toString())
        return CircuitProject(listOf(CircuitDocument("main", components = listOf(
            CircuitComponent("input", ComponentKind.INPUT, left, "A", attributes),
            CircuitComponent("output", ComponentKind.OUTPUT, right, "Y", attributes),
        ), wires = listOf(WireSegment(left, right)))))
    }
}
