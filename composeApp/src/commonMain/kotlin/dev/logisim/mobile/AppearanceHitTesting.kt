package dev.logisim.mobile

import androidx.compose.ui.geometry.Offset
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.SymbolAppearance
import dev.logisim.mobile.core.SymbolObject
import kotlin.math.hypot

internal fun appearancePreviewHit(
    appearance: SymbolAppearance,
    position: Offset,
    origin: Offset,
    zoom: Float,
    targetRadiusPx: Float,
    selected: String? = null,
): String? {
    val world = (position - origin) / zoom
    val tolerance = targetRadiusPx / zoom
    val anchorDistance = hypot(world.x - appearance.anchor.position.x, world.y - appearance.anchor.position.y)
    if (selected == "anchor" && anchorDistance < tolerance) return "anchor"
    appearance.ports.minByOrNull { hypot(world.x - it.position.x, world.y - it.position.y) }?.let {
        if (hypot(world.x - it.position.x, world.y - it.position.y) < tolerance) return "port-${it.pinId}"
    }
    if (anchorDistance < tolerance) return "anchor"
    for (obj in appearance.objects.asReversed()) {
        val (id, bounds) = when (obj) {
            is SymbolObject.Shape -> obj.id to SymbolAppearance.shapeBounds(obj)
            is SymbolObject.Led -> obj.id to ComponentBounds(obj.position.x, obj.position.y,
                obj.position.x + 2 * obj.radius, obj.position.y + 2 * obj.radius)
            is SymbolObject.Opaque -> continue
        }
        if (world.x in (bounds.left - tolerance)..(bounds.right + tolerance) &&
            world.y in (bounds.top - tolerance)..(bounds.bottom + tolerance)) return id
    }
    return null
}
