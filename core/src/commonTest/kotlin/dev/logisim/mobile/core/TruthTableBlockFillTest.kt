package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** TableTabCaret.doKey fills the marked block: input columns are left out, and only the block's outputs change. */
class TruthTableBlockFillTest {
    private val table = TruthTable(listOf("A", "B"), listOf("X", "Y", "Z"), List(4) { List(3) { TruthCell.ZERO } })

    @Test
    fun aBlockThatStartsOnAnInputChangesOnlyTheOutputsItCovers() {
        // Table columns: A=0, B=1, X=2, Y=3, Z=4. Columns 1..3 cover input B and outputs X and Y.
        val filled = table.withBlock(2..3, 1..3, TruthCell.DONT_CARE)
        for (row in 0..3) for (output in 0..2) {
            val expected = if (row >= 2 && output <= 1) TruthCell.DONT_CARE else TruthCell.ZERO
            assertEquals(expected, filled.rows[row][output], "row $row output $output")
        }
        assertEquals(table.inputs, filled.inputs)
        assertEquals(table.outputs, filled.outputs)
    }

    @Test
    fun aBlockOfOutputsIsFilledAndOneOfInputsChangesNothing() {
        val filled = table.withBlock(0..0, 4..4, TruthCell.ONE)
        assertEquals(listOf(TruthCell.ZERO, TruthCell.ZERO, TruthCell.ONE), filled.rows[0])
        assertEquals(table.rows.drop(1), filled.rows.drop(1))
        assertSame(table, table.withBlock(0..3, 0..1, TruthCell.ONE))
    }

    @Test
    fun theBlockMustLieInsideTheTable() {
        assertFailsWith<IllegalArgumentException> { table.withBlock(0..4, 2..2, TruthCell.ONE) }
        assertFailsWith<IllegalArgumentException> { table.withBlock(-1..0, 2..2, TruthCell.ONE) }
        assertFailsWith<IllegalArgumentException> { table.withBlock(0..0, 2..5, TruthCell.ONE) }
        assertFailsWith<IllegalArgumentException> { table.withBlock(0..0, 3..2, TruthCell.ONE) }
    }
}
