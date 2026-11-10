package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.GridPoint

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
    fun validSpacing(value: Int?, minimum: Int) = value != null && value in minimum..990 && value % 10 == 0

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
                        label = if (label.isEmpty()) "" else "${label}_X${column}_Y${row}",
                    ))
                }
            }
        }
    }

    private fun minimumSpacing(span: Int): Int = (((span.toLong() + 19) / 10) * 10).coerceIn(10, 990).toInt()
}
