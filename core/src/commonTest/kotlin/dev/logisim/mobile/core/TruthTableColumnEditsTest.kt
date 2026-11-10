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
}
