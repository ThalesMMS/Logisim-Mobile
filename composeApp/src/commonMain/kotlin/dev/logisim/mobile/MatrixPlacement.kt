package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

internal class PendingMatrixPlacement(
    val document: CircuitDocument,
    val template: CircuitComponent,
    val origin: GridPoint,
    bounds: ComponentBounds,
    val defaultBaseLabel: String,
) {
    val minDx = minimumSpacing(bounds.width)
    val minDy = minimumSpacing(bounds.height)

    fun validSize(value: Int?) = value != null && value in 1..49
    fun validSpacing(value: Int?, minimum: Int) = value != null && value in minimum..MAX_SPACING && value % 10 == 0

    /** [value] moved by one 10-unit step ([up] or down) and clamped to [minimum]..990; an unreadable value restarts at [minimum]. */
    fun steppedSpacing(value: Int?, minimum: Int, up: Boolean): Int {
        val current = value?.let { (it / 10) * 10 } ?: return minimum
        return (current + if (up) 10 else -10).coerceIn(minimum, MAX_SPACING)
    }

    fun components(columns: Int, rows: Int, dx: Int, dy: Int, baseLabel: String): List<CircuitComponent>? {
        if (!validSize(columns) || !validSize(rows) || !validSpacing(dx, minDx) || !validSpacing(dy, minDy)) return null
        if (origin.x.toLong() + (columns - 1) * dx > Int.MAX_VALUE ||
            origin.y.toLong() + (rows - 1) * dy > Int.MAX_VALUE) return null
        val label = baseLabel.trim()
        return buildList {
            for (column in 0 until columns) {
                for (row in 0 until rows) {
                    add(template.copy(
                        position = GridPoint(origin.x + column * dx, origin.y + row * dy),
                        label = matrixLabel(label, column, row),
                    ))
                }
            }
        }
    }

    private fun minimumSpacing(span: Int): Int = (((span.toLong() + 19) / 10) * 10).coerceIn(10, MAX_SPACING.toLong()).toInt()

    private companion object {
        const val MAX_SPACING = 990
    }
}

private fun matrixLabel(base: String, column: Int, row: Int) = if (base.isEmpty()) "" else "${base}_X${column}_Y${row}"

/**
 * Why [baseLabel] cannot name a [columns] x [rows] matrix of [kind] in [document], or null: it must be a valid label,
 * and none of the generated `<base>_X<c>_Y<r>` names may be taken. An empty base leaves the copies unlabelled (X9).
 */
internal fun matrixLabelProblem(
    document: CircuitDocument, registry: ComponentRegistry, kind: ComponentKind, baseLabel: String, columns: Int, rows: Int,
): LocalizedText? {
    val label = baseLabel.trim()
    if (label.isEmpty()) return null
    if (!PlacementLabels.isValid(label)) return message(Messages.MatrixPlaceInvalidLabel)
    val unavailable = PlacementLabels.unavailable(document, registry, kind)
    for (column in 0 until columns.coerceIn(0, 49)) for (row in 0 until rows.coerceIn(0, 49)) {
        if (matrixLabel(label, column, row).lowercase() in unavailable) return message(Messages.MatrixPlaceInvalidLabel)
    }
    return null
}
