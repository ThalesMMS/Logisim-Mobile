package dev.logisim.mobile

import dev.logisim.mobile.core.TruthCell
import dev.logisim.mobile.core.TruthTable
import dev.logisim.mobile.core.ValueCharacters
import dev.logisim.mobile.core.display
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.core.localization.requireNotNullLocalized

internal data class TruthTableCellPosition(val row: Int, val column: Int)

internal data class TruthTableBlock(val first: TruthTableCellPosition, val last: TruthTableCellPosition = first) {
    val rows = minOf(first.row, last.row)..maxOf(first.row, last.row)
    val columns = minOf(first.column, last.column)..maxOf(first.column, last.column)

    fun contains(row: Int, column: Int): Boolean = row in rows && column in columns
}

/** TableTabClip copies Entry.getDescription(), so the clipboard follows the value characters. */
internal fun TruthTable.copyTsv(block: TruthTableBlock, characters: ValueCharacters = ValueCharacters.Default): String = buildString {
    val names = (inputs + outputs).slice(block.columns)
    // An all-symbol header would be indistinguishable from data. Names containing
    // TSV delimiters cannot be written as a single field.
    if (names.any { characters.parseCell(it) == null } && names.none { it.any { char -> char in "\t\r\n" } }) {
        appendLine(names.joinToString("\t"))
    }
    for (row in block.rows) {
        appendLine(block.columns.joinToString("\t") { column ->
            if (column < inputs.size) {
                (if (input(row, column)) TruthCell.ONE else TruthCell.ZERO).display(characters)
            } else rows[row][column - inputs.size].display(characters)
        })
    }
}

internal fun TruthTable.pasteTsv(tsv: String, block: TruthTableBlock, characters: ValueCharacters = ValueCharacters.Default): TruthTable {
    val startRow = block.rows.first
    val startColumn = block.columns.first
    val names = inputs + outputs
    val columnCapacity = names.size - startColumn
    val lines = tsv.removePrefix("\uFEFF").trimEnd('\r', '\n').lineSequence().iterator()
    val first = lines.next().split('\t', limit = columnCapacity + 1).map { it.trim() }
    val width = first.size
    requireLocalized(width <= columnCapacity) { message(Messages.AnalysisClipboardDimensions) }
    val data = mutableListOf<List<TruthCell>>()
    fun readRow(fields: List<String>) {
        requireLocalized(fields.size == width && data.size < rows.size - startRow) {
            message(Messages.AnalysisClipboardDimensions)
        }
        data += fields.map { field ->
            requireNotNullLocalized(characters.parseCell(field)) { message(Messages.AnalysisClipboardSymbols) }
        }
    }

    val destinationHeaders = names.subList(startColumn, startColumn + width).map { it.trim() }
    val hasHeader = first.any { characters.parseCell(it) == null } && first == destinationHeaders
    if (!hasHeader) readRow(first)
    while (lines.hasNext()) readRow(lines.next().split('\t', limit = width + 1).map { it.trim() })
    requireLocalized(data.isNotEmpty() && (block.first == block.last ||
        (block.rows.count() == data.size && block.columns.count() == width))) {
        message(Messages.AnalysisClipboardDimensions)
    }

    // Only replace rows after every field has been parsed and the full block fits.
    return copy(rows = rows.mapIndexed { row, outputs ->
        val pasted = data.getOrNull(row - startRow) ?: return@mapIndexed outputs
        outputs.mapIndexed { output, value ->
            pasted.getOrNull(inputs.size + output - startColumn) ?: value
        }
    })
}
