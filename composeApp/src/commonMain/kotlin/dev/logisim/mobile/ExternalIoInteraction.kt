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

/** Returns an event only for a visible PortIO pin, in absolute circuit coordinates. */
fun externalIoEventAt(component: CircuitComponent, point: GridPoint): ComponentEvent? {
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
    if (x < 0 || y < 0 || x % 10 >= 6 || y % 10 >= 6 || y / 10 > 1) return null
    val index = x / 10 * 2 + y / 10
    return if (index < externalIoPinCount(component)) PortIoTogglePin(index) else null
}
