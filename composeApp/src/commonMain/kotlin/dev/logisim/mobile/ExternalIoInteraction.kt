package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.ComponentEvent
import dev.logisim.mobile.core.ExternalIoComponents
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.PortIoTogglePin
import dev.logisim.mobile.core.relativePoint

internal fun externalIoPinCount(component: CircuitComponent): Int = component.attributeInt("number", 8).coerceIn(1, 64)

internal fun externalIoPinBounds(component: CircuitComponent, index: Int): ComponentBounds {
    val x = 7 + index / 2 * 10
    val y = 25 + index % 2 * 10
    val first = component.relativePoint(GridPoint(x, y))
    val last = component.relativePoint(GridPoint(x + 6, y + 6))
    return ComponentBounds(minOf(first.x, last.x), minOf(first.y, last.y),
        maxOf(first.x, last.x), maxOf(first.y, last.y))
}

/**
 * Returns an event only for a visible PortIO pin, in absolute circuit coordinates. [tolerance] (0 for the mouse)
 * lets a fingertip reach the nearest pin; pins are 6 units wide on a 10-unit pitch, so the reach is at most 2 units.
 */
fun externalIoEventAt(component: CircuitComponent, point: GridPoint, tolerance: Int = 0): ComponentEvent? {
    if (component.kind != ExternalIoComponents.PortIo) return null
    val dx = point.x - component.position.x
    val dy = point.y - component.position.y
    val local = when (component.facing) {
        "west" -> GridPoint(-dx, -dy)
        "north" -> GridPoint(-dy, dx)
        "south" -> GridPoint(dy, -dx)
        else -> GridPoint(dx, dy)
    }
    val x = local.x - 7
    val y = local.y - 25
    if (tolerance <= 0) {
        if (x < 0 || y < 0 || x % 10 >= 6 || y % 10 >= 6 || y / 10 > 1) return null
        val index = x / 10 * 2 + y / 10
        return if (index < externalIoPinCount(component)) PortIoTogglePin(index) else null
    }
    val reach = minOf(tolerance, 2)
    // Pin `index` covers 0..5 from (10 * (index / 2), 10 * (index % 2)).
    fun gap(offset: Int, start: Int) = maxOf(start - offset, offset - (start + 5), 0)
    return (0 until externalIoPinCount(component)).map { index ->
        index to (gap(x, index / 2 * 10) to gap(y, index % 2 * 10))
    }.filter { (_, gaps) -> gaps.first <= reach && gaps.second <= reach }
        .minByOrNull { (_, gaps) -> gaps.first * gaps.first + gaps.second * gaps.second }
        ?.let { PortIoTogglePin(it.first) }
}
