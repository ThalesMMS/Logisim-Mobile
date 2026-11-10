package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

data class KarnaughCell(val row: Int, val column: Int, val truthRow: Int, val value: TruthCell, val groups: List<Int>)

/** CoverInfo: a rectangle of cells, in K-map columns and rows. */
data class KarnaughArea(val column: Int, val row: Int, val width: Int, val height: Int)

/** KMapGroupInfo: the rectangles of one implicant; [colorIndex] selects KMAP1..16_COLOR like CoverColor.getNext(). */
data class KarnaughGroup(val index: Int, val areas: List<KarnaughArea>) {
    val colorIndex: Int get() = index % KARNAUGH_GROUP_COLORS
}

const val KARNAUGH_GROUP_COLORS = 16

/** KMapGroupInfo.build: scan left to right and top down, growing a rectangle right or down. */
internal fun karnaughAreas(covered: List<List<Boolean>>): List<KarnaughArea> {
    class Cover(val column: Int, val row: Int, var width: Int = 1, var height: Int = 1) {
        fun merge(col: Int, r: Int): Boolean {
            val inColumns = col >= column && col < column + width
            val inRows = r >= row && r < row + height
            if (inColumns) {
                if (inRows) return true
                if (r >= row && r <= row + height) { height++; return true }
                return false
            }
            if (inRows && col >= column && col <= column + width) { width++; return true }
            return false
        }
    }
    val areas = mutableListOf<Cover>()
    var current: Cover? = null
    for ((r, cells) in covered.withIndex()) for ((col, inside) in cells.withIndex()) {
        if (inside) {
            current?.let { if (it.merge(col, r)) continue; if (it !in areas) areas += it }
            current = areas.firstOrNull { it.merge(col, r) } ?: Cover(col, r)
        } else {
            current?.let { if (it !in areas) areas += it }
            current = null
        }
    }
    current?.let { if (it !in areas) areas += it }
    return areas.map { KarnaughArea(it.column, it.row, it.width, it.height) }
}

data class KarnaughMap(
    val rowVariables: List<String>,
    val columnVariables: List<String>,
    val rowLabels: List<String>,
    val columnLabels: List<String>,
    val cells: List<List<KarnaughCell>>,
) {
    /** KarnaughMapGroups.covers: one entry per implicant, in the order CoverColor assigns colors. */
    val groups: List<KarnaughGroup> get() {
        val count = cells.flatten().maxOfOrNull { cell -> cell.groups.maxOrNull() ?: -1 }?.plus(1) ?: 0
        return (0 until count).map { index ->
            KarnaughGroup(index, karnaughAreas(cells.map { row -> row.map { index in it.groups } }))
        }
    }

    companion object {
        fun from(table: TruthTable, output: Int = 0, cover: MinimizedBooleanFunction? = null): KarnaughMap {
            require(output in table.outputs.indices)
            if (table.inputs.size > 6) throw AnalysisLimitException(message(Messages.AnalysisKarnaughLimit))
            val rowBits = table.inputs.size / 2
            val columnBits = table.inputs.size - rowBits
            fun gray(value: Int) = value xor (value shr 1)
            fun labels(bits: Int) = (0 until (1 shl bits)).map {
                if (bits == 0) "" else gray(it).toString(2).padStart(bits, '0')
            }
            return KarnaughMap(table.inputs.take(rowBits), table.inputs.drop(rowBits), labels(rowBits), labels(columnBits),
                (0 until (1 shl rowBits)).map { row ->
                    (0 until (1 shl columnBits)).map { column ->
                        val truthRow = (gray(row) shl columnBits) or gray(column)
                        KarnaughCell(row, column, truthRow, table.rows[truthRow][output],
                            cover?.implicants?.mapIndexedNotNull { index, term -> index.takeIf { term.covers(truthRow) } }.orEmpty())
                    }
                })
        }
    }
}
