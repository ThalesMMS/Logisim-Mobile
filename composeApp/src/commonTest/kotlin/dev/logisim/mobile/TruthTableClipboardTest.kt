package dev.logisim.mobile

import dev.logisim.mobile.core.TruthCell
import dev.logisim.mobile.core.TruthTable
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TruthTableClipboardTest {
    private val blank = TruthTable(listOf("A", "B"), listOf("Y", "Z"), List(4) { List(2) { TruthCell.ZERO } })

    @Test fun copiesTheRectangleInTableOrderEvenWhenSelectingBackwards() {
        val table = blank.withCell(1, 0, TruthCell.ONE).withCell(2, 0, TruthCell.DONT_CARE)
        val block = TruthTableBlock(TruthTableCellPosition(2, 2), TruthTableCellPosition(1, 1))

        // TableTabClip copies Entry.getDescription(): Evolution's default don't-care character is '-'.
        assertEquals("B\tY\n1\t1\n0\t-\n", table.copyTsv(block))
        val custom = dev.logisim.mobile.core.ValueCharacters('T', 'F', 'U', 'E', 'X')
        assertEquals("B\tY\nT\tT\nF\tX\n", table.copyTsv(block, custom))
        // Entry.parse reads the chosen characters back.
        assertEquals(table, blank.pasteTsv("B\tY\nT\tT\nF\tX\n", block, custom))
    }

    @Test fun pastesDesktopStyleHeaderAndInputColumnsIntoTheSelectedAnchor() {
        val anchor = TruthTableBlock(TruthTableCellPosition(0, 0))
        val originalStyle = "A\tB\tY\r\n0\t0\t0\r\n0\t1\t1\r\n1\t0\t1\r\n1\t1\t0\r\n"
        val result = blank.pasteTsv(originalStyle, anchor)

        assertEquals(listOf("0", "1", "1", "0"), result.rows.map { it[0].symbol })
        assertEquals(listOf("0", "0", "0", "0"), result.rows.map { it[1].symbol })
        assertEquals(listOf("A", "B"), result.inputs)
    }

    @Test fun pastesUnlabelledTsvIntoAnExactlySizedSelection() {
        val block = TruthTableBlock(TruthTableCellPosition(2, 3), TruthTableCellPosition(1, 2))
        val result = blank.pasteTsv("1\tx\nX\t1", block)

        assertEquals(listOf("00", "1x", "x1", "00"), result.rows.map { row -> row.joinToString("") { it.symbol } })
    }

    @Test fun rejectsInvalidLateCellsOrDimensionsBeforeReplacingAnyRow() {
        val block = TruthTableBlock(TruthTableCellPosition(1, 2), TruthTableCellPosition(2, 3))
        val invalid = assertFailsWith<LocalizedArgumentException> { blank.pasteTsv("1\t1\n0\t!", block) }
        val ragged = assertFailsWith<LocalizedArgumentException> { blank.pasteTsv("1\t1\n1", block) }
        val wrongSize = assertFailsWith<LocalizedArgumentException> { blank.pasteTsv("1\t1", block) }
        val overflow = assertFailsWith<LocalizedArgumentException> {
            blank.pasteTsv("1\n0", TruthTableBlock(TruthTableCellPosition(3, 3)))
        }

        assertEquals(Messages.AnalysisClipboardSymbols, invalid.text.key)
        listOf(ragged, wrongSize, overflow).forEach { assertEquals(Messages.AnalysisClipboardDimensions, it.text.key) }
        assertEquals(List(4) { "00" }, blank.rows.map { row -> row.joinToString("") { it.symbol } })
    }
}
