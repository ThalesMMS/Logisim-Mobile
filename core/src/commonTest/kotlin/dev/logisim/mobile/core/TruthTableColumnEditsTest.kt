package dev.logisim.mobile.core

import dev.logisim.mobile.core.TruthCell.DONT_CARE
import dev.logisim.mobile.core.TruthCell.ONE
import dev.logisim.mobile.core.TruthCell.ZERO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TruthTableColumnEditsTest {
    @Test
    fun movingAndRenamingInputsAndOutputsPreservesTheFunction() {
        val original = TruthTable(
            inputs = listOf("A", "B", "C"),
            outputs = listOf("Y", "Z"),
            rows = listOf(
                listOf(ZERO, DONT_CARE), listOf(ONE, ONE), listOf(DONT_CARE, ZERO), listOf(ZERO, ONE),
                listOf(ONE, ZERO), listOf(DONT_CARE, ZERO), listOf(ZERO, ONE), listOf(ONE, DONT_CARE),
            ),
        )

        val edited = original.withInputColumn(0, "X", destination = 2)
            .withOutputColumn(1, "W", destination = 0)

        assertEquals(listOf("B", "C", "X"), edited.inputs)
        assertEquals(listOf("W", "Y"), edited.outputs)
        assertEquals(
            listOf(
                listOf(DONT_CARE, ZERO), listOf(ZERO, ONE), listOf(ONE, ONE), listOf(ZERO, DONT_CARE),
                listOf(ZERO, DONT_CARE), listOf(ONE, ZERO), listOf(ONE, ZERO), listOf(DONT_CARE, ONE),
            ),
            edited.rows,
        )
    }

    @Test
    fun renamingInPlacePreservesCellsAndRejectsDuplicateNames() {
        val original = TruthTable(listOf("A", "B"), listOf("Y"), listOf(ZERO, ONE, DONT_CARE, ZERO).map { listOf(it) })
        val edited = original.withInputColumn(1, "Enable").withOutputColumn(0, "Result")
        assertEquals(listOf("A", "Enable"), edited.inputs)
        assertEquals(listOf("Result"), edited.outputs)
        assertEquals(original.rows, edited.rows)
        assertFailsWith<IllegalArgumentException> { original.withInputColumn(1, "A", destination = 0) }
    }

    @Test
    fun structuralEditsCommuteWithMoves() {
        val original = TruthTable(listOf("A", "B", "C"), listOf("Y"), listOf(
            ZERO, ONE, DONT_CARE, ZERO, ONE, ONE, ZERO, DONT_CARE,
        ).map { listOf(it) })
        // Removing B after moving it first equals removing it in place.
        assertEquals(original.withInputRemoved(1), original.withInputColumn(1, "B", destination = 0).withInputRemoved(0))
        // Removing B from Y = A & B (with C ignored) keeps A=0 -> 0 and marks A=1 as don't care.
        val and = TruthTable.fromExpressions(mapOf("Y" to BooleanExpressionParser.parse("A & B")), inputs = listOf("A", "B"))
        assertEquals(listOf(listOf(ZERO), listOf(DONT_CARE)), and.withInputRemoved(1).rows)
        // An added input can be moved like any other.
        val added = original.withInputAdded("D", 3).withInputColumn(3, "D", destination = 0)
        assertEquals(original.withInputAdded("D", 0), added)
    }
}
