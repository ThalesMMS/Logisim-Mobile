package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

internal data class CanvasGridPlan(val first: Offset, val spacing: Float, val columns: Int, val rows: Int)

/** Work in screen coordinates so very small fitted scales never overflow a world-coordinate Int. */
internal fun canvasGridPlan(
    view: CircuitViewport,
    size: IntSize,
    density: Float,
    maximumDots: Int = 16_000,
): CanvasGridPlan? {
    require(maximumDots >= 4)
    if (size.width <= 0 || size.height <= 0 || !density.isFinite() || density <= 0 ||
        !view.scale.isFinite() || view.scale <= 0 || !view.pan.x.isFinite() || !view.pan.y.isFinite()) return null
    var spacing = 10.0 * view.scale
    while (spacing < 7.0 * density) spacing *= 2.0
    fun extent(pixels: Int): Long = ceil(pixels / spacing).coerceAtMost((Int.MAX_VALUE - 1).toDouble()).toLong() + 1L
    while (extent(size.width) * extent(size.height) > maximumDots) spacing *= 2.0
    fun phase(pixels: Float): Double = ((pixels.toDouble() % spacing) + spacing) % spacing
    val x = phase(view.pan.x)
    val y = phase(view.pan.y)
    if (spacing > Float.MAX_VALUE) return null
    fun count(pixels: Int, first: Double): Int = if (first > pixels) 0 else ((pixels - first) / spacing).toInt() + 1
    return CanvasGridPlan(Offset(x.toFloat(), y.toFloat()), spacing.toFloat(), count(size.width, x), count(size.height, y))
}
