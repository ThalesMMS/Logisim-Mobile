package dev.logisim.mobile.core

import dev.logisim.mobile.core.TruthCell.DONT_CARE
import dev.logisim.mobile.core.TruthCell.ONE
import dev.logisim.mobile.core.TruthCell.ZERO
import dev.logisim.mobile.core.localization.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Adding and removing variables follows Evolution's TruthTable.addInputForOutput and removeInputForOutput. */
class TruthTableStructureEditsTest {
    private fun table(expression: String, vararg inputs: String) =
        TruthTable.fromExpressions(mapOf("Y" to BooleanExpressionParser.parse(expression)), inputs = inputs.toList())

    private fun column(table: TruthTable, output: Int = 0) = table.rows.map { it[output] }

    @Test
    fun removingAnInputMergesRowPairsAndMarksDisagreementsAsDontCare() {
        // Y = A & B without B: A=0 rows agree on 0, A=1 rows disagree (0 then 1).
        val removed = table("A & B", "A", "B").withInputRemoved(1)
        assertEquals(listOf("A"), removed.inputs)
        assertEquals(listOf(ZERO, DONT_CARE), column(removed))
        // Without A: B=0 agrees on 0, B=1 disagrees.
        assertEquals(listOf(ZERO, DONT_CARE), column(table("A & B", "A", "B").withInputRemoved(0)))
        // An input the function ignores leaves the function intact.
        assertEquals(column(table("A | C", "A", "C")), column(table("A | C", "A", "B", "C").withInputRemoved(1)))
    }

    @Test
    fun addingAnInputDuplicatesEachRowInMostSignificantFirstOrder() {
        val original = TruthTable(listOf("A", "B"), listOf("Y"), listOf(ZERO, ONE, DONT_CARE, ZERO).map { listOf(it) })
        // New first column: the old table repeats under X=0 and X=1.
        assertEquals(listOf(ZERO, ONE, DONT_CARE, ZERO, ZERO, ONE, DONT_CARE, ZERO),
            column(original.withInputAdded("X", 0)))
        // New middle column (A, X, B): each A half repeats its B rows under X=0 and X=1.
        val middle = original.withInputAdded("X", 1)
        assertEquals(listOf("A", "X", "B"), middle.inputs)
        assertEquals(listOf(ZERO, ONE, ZERO, ONE, DONT_CARE, ZERO, DONT_CARE, ZERO), column(middle))
        // New last column: each row is doubled in place.
        assertEquals(listOf(ZERO, ZERO, ONE, ONE, DONT_CARE, DONT_CARE, ZERO, ZERO),
            column(original.withInputAdded("X", 2)))
        // Every row keeps the value of the assignment it came from.
        for (row in middle.rows.indices) {
            val assignment = middle.assignment(row)
            val parent = original.rows.indices.single { original.assignment(it) == assignment - "X" }
            assertEquals(original.rows[parent], middle.rows[row])
        }
        // Removing the added input gives the original back.
        listOf(0, 1, 2).forEach { assertEquals(original, original.withInputAdded("X", it).withInputRemoved(it)) }
    }

    @Test
    fun oneInputTableEdgeCases() {
        val single = TruthTable(listOf("A"), listOf("Y"), listOf(listOf(ONE), listOf(ZERO)))
        val none = single.withInputRemoved(0)
        assertEquals(emptyList(), none.inputs)
        assertEquals(listOf(listOf(DONT_CARE)), none.rows)
        assertEquals(TruthTable(listOf("B"), listOf("Y"), listOf(listOf(DONT_CARE), listOf(DONT_CARE))), none.withInputAdded("B"))
        val constant = TruthTable(listOf("A"), listOf("Y"), listOf(listOf(ONE), listOf(ONE)))
        assertEquals(listOf(listOf(ONE)), constant.withInputRemoved(0).rows)
    }

    @Test
    fun dontCarePropagatesThroughRemovalAndAddition() {
        val original = TruthTable(listOf("A", "B"), listOf("Y", "Z"), listOf(
            listOf(DONT_CARE, ONE), listOf(DONT_CARE, ONE), listOf(DONT_CARE, ZERO), listOf(ONE, ZERO),
        ))
        val removed = original.withInputRemoved(1)
        // A=0: both outputs agree. A=1: Y is x then 1, so x; Z agrees on 0.
        assertEquals(listOf(listOf(DONT_CARE, ONE), listOf(DONT_CARE, ZERO)), removed.rows)
        assertEquals(listOf(DONT_CARE, DONT_CARE, DONT_CARE, DONT_CARE), column(removed.withInputAdded("C")))
    }

    @Test
    fun outputsAreAddedAsDontCareAndTheLastOutputCannotBeRemoved() {
        val original = table("A & B", "A", "B")
        val added = original.withOutputAdded("Z", 0)
        assertEquals(listOf("Z", "Y"), added.outputs)
        assertEquals(List(4) { DONT_CARE }, column(added, 0))
        assertEquals(column(original), column(added, 1))
        assertEquals(original, added.withOutputRemoved(0))
        assertEquals(listOf("Z"), added.withOutputRemoved(1).outputs)
        assertFailsWith<IllegalArgumentException> { original.withOutputRemoved(0) }
        assertFailsWith<IllegalArgumentException> { original.withOutputAdded("Y") }
        assertFailsWith<IllegalArgumentException> { original.withOutputAdded(" ") }
        assertFailsWith<IllegalArgumentException> {
            original.withOutputAdded("Z", limits = AnalysisLimits(maxOutputBits = 1))
        }
    }

    @Test
    fun inputNamesAndTheInputLimitAreEnforced() {
        val original = table("A & B", "A", "B")
        assertFailsWith<IllegalArgumentException> { original.withInputAdded("A") }
        assertFailsWith<IllegalArgumentException> { original.withInputAdded("") }
        val limit = assertFailsWith<AnalysisLimitException> { original.withInputAdded("C", limits = AnalysisLimits(maxInputBits = 2)) }
        assertEquals(Messages.AnalysisInputBitsLimit, limit.text.key)
        var wide = original
        for (name in listOf("C", "D", "E", "F", "G", "H", "I", "J", "K", "L")) wide = wide.withInputAdded(name)
        assertEquals(12, wide.inputs.size)
        assertFailsWith<AnalysisLimitException> { wide.withInputAdded("M") }
    }

    @Test
    fun replacingOutputsKeepsTheOthersAndAppendsNewOnes() {
        val original = TruthTable(listOf("A", "B"), listOf("Y", "Z"), listOf(
            listOf(ZERO, DONT_CARE), listOf(ONE, ZERO), listOf(ONE, ONE), listOf(ZERO, ONE),
        ))
        val defined = TruthTable.fromExpressions(linkedMapOf(
            "Z" to BooleanExpressionParser.parse("A & B"), "W" to BooleanExpressionParser.parse("!A"),
        ), inputs = listOf("A", "B"))
        val merged = original.withOutputsReplaced(defined)
        assertEquals(listOf("Y", "Z", "W"), merged.outputs)
        assertEquals(column(original, 0), column(merged, 0))
        assertEquals(listOf(ZERO, ZERO, ZERO, ONE), column(merged, 1))
        assertEquals(listOf(ONE, ONE, ZERO, ZERO), column(merged, 2))
    }
}
