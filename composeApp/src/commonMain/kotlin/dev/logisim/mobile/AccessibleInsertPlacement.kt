package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.CircuitDocument
import dev.logisim.mobile.core.ComponentBounds
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.CreationDefaults
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.bounds
import dev.logisim.mobile.core.createNew
import dev.logisim.mobile.core.layout
import kotlin.math.abs
import kotlin.math.max

/** Where the Explorer's Insert puts a new component (X1). */
enum class AccessibleInsertPosition { RIGHT_OF_SELECTED, BELOW_SELECTED, NEXT_FREE, CUSTOM }

/** Space kept free around a suggested component, in world units: one grid step beside its neighbours. */
private const val InsertGap = 10
/** How many grid steps a suggestion scans before it gives up. */
private const val InsertScanSteps = 200

/**
 * A grid point for a new [kind] that a blind user can choose without coordinates: just right of or below [selected]
 * (its bounds' far edge snapped up to the grid, plus 20), or the free spot nearest [origin] (100, 100 by default).
 * A spot is free when the new component's bounds, one grid step apart, overlap no existing component's, no wire
 * crosses them, and none of its terminals lies on a wire or on another terminal: CircuitNetworks joins a terminal
 * anywhere along a wire, so a free spot connects nothing. A taken spot moves on along the row, the column or the
 * rings around [origin]. [defaults] are the creation defaults the insert applies, which can change a pin's shape.
 * Returns null for [AccessibleInsertPosition.CUSTOM], a missing selection, or no free spot within reach. The point
 * goes through the same CircuitEditor.add as a typed one, so the saved file is unchanged.
 */
fun accessibleInsertSuggestion(
    document: CircuitDocument,
    registry: ComponentRegistry,
    kind: ComponentKind,
    position: AccessibleInsertPosition,
    selected: CircuitComponent? = null,
    origin: GridPoint? = null,
    defaults: CreationDefaults = CreationDefaults(),
): GridPoint? {
    val prototype = runCatching { registry.createNew(kind, "insert-preview", GridPoint(0, 0), defaults) }.getOrNull() ?: return null
    val shape = runCatching { prototype.bounds(registry) }.getOrNull() ?: return null
    val terminals = runCatching { prototype.layout(registry).ports.map { it.position } }.getOrDefault(emptyList())
    val occupied = document.components.mapNotNull { runCatching { it.bounds(registry) }.getOrNull() }
    val ports = document.components.flatMapTo(mutableSetOf()) { component ->
        runCatching { component.layout(registry).ports.map { it.position } }.getOrDefault(emptyList())
    }
    fun at(point: GridPoint) = ComponentBounds(shape.left + point.x, shape.top + point.y, shape.right + point.x, shape.bottom + point.y)
    fun crosses(wire: WireSegment, bounds: ComponentBounds): Boolean =
        minOf(wire.from.x, wire.to.x) <= bounds.right && maxOf(wire.from.x, wire.to.x) >= bounds.left &&
            minOf(wire.from.y, wire.to.y) <= bounds.bottom && maxOf(wire.from.y, wire.to.y) >= bounds.top
    fun free(point: GridPoint): Boolean {
        val bounds = at(point)
        if (bounds.left < 0 || bounds.top < 0) return false
        if (occupied.any { other ->
                bounds.left < other.right + InsertGap && other.left < bounds.right + InsertGap &&
                    bounds.top < other.bottom + InsertGap && other.top < bounds.bottom + InsertGap
            }) return false
        if (document.wires.any { crosses(it, bounds) }) return false
        return terminals.none { terminal ->
            val placed = GridPoint(terminal.x + point.x, terminal.y + point.y)
            placed in ports || document.wires.any { it.contains(placed) }
        }
    }
    fun snapUp(value: Int) = (value + 9).floorDiv(10) * 10
    fun snap(value: Int) = (value + 5).floorDiv(10) * 10
    return when (position) {
        AccessibleInsertPosition.CUSTOM -> null
        AccessibleInsertPosition.RIGHT_OF_SELECTED -> {
            val chosen = selected ?: return null
            val target = runCatching { chosen.bounds(registry) }.getOrNull() ?: return null
            val start = GridPoint(snapUp(target.right + 20 - shape.left), snap(chosen.position.y))
            (0 until InsertScanSteps).asSequence().map { GridPoint(start.x + it * 10, start.y) }.firstOrNull(::free)
        }
        AccessibleInsertPosition.BELOW_SELECTED -> {
            val chosen = selected ?: return null
            val target = runCatching { chosen.bounds(registry) }.getOrNull() ?: return null
            val start = GridPoint(snap(chosen.position.x), snapUp(target.bottom + 20 - shape.top))
            (0 until InsertScanSteps).asSequence().map { GridPoint(start.x, start.y + it * 10) }.firstOrNull(::free)
        }
        AccessibleInsertPosition.NEXT_FREE -> {
            val center = (origin ?: GridPoint(100, 100)).let { GridPoint(snap(it.x), snap(it.y)) }
            // Rings of grid points around the origin, nearest first, scanning each ring row by row.
            (0 until InsertScanSteps / 4).asSequence().flatMap { ring ->
                (-ring..ring).asSequence().flatMap { dy -> (-ring..ring).asSequence().map { dx -> dx to dy } }
                    .filter { (dx, dy) -> max(abs(dx), abs(dy)) == ring }
                    .map { (dx, dy) -> GridPoint(center.x + dx * 10, center.y + dy * 10) }
            }.firstOrNull(::free)
        }
    }
}
