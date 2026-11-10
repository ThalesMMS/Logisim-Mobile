package dev.logisim.mobile.core

/** One terminal width that WidthIncompatibilityData records at a point. */
data class WidthIncompatibilityPoint(val position: GridPoint, val width: Int)

/**
 * Evolution's WidthIncompatibilityData for one wire bundle whose terminals disagree on width:
 * the points and widths that determine it, plus the wires CircuitWires paints in the error color.
 */
data class WidthIncompatibility(
    val points: List<WidthIncompatibilityPoint>,
    val wires: Set<WireSegment>,
    val netPoints: Set<GridPoint>,
) {
    /** getCommonBitWidth(): the most frequent width, or null when two widths tie. */
    val commonWidth: Int? get() {
        val counts = mutableMapOf<Int, Int>()
        var best: Int? = null
        var bestCount = 0
        for (point in points) {
            val count = counts.getOrElse(point.width) { 0 } + 1
            counts[point.width] = count
            if (count > bestCount) { bestCount = count; best = point.width }
            else if (count == bestCount) best = null
        }
        return best
    }

    /** CanvasPainter's caption for the point at [index]: its width and the next width at the same point. */
    fun caption(index: Int): String {
        val point = points[index]
        val other = points.drop(index + 1).firstOrNull { it.position == point.position }
        return if (other == null) "${point.width}" else "${point.width}/${other.width}"
    }

    /** CanvasPainter draws each point once, at its first entry. */
    fun isFirstAt(index: Int): Boolean = points.take(index).none { it.position == points[index].position }
}

/** Circuit.getWidthIncompatibilityData() for the nets of this circuit. */
fun CircuitTopology.widthIncompatibilities(): List<WidthIncompatibility> = nets.filter { it.declaredWidths.size > 1 }.map { net ->
    val points = net.terminals.filter { it.declaredWidth > 0 }
        .map { WidthIncompatibilityPoint(it.position, it.declaredWidth) }.distinct()
    WidthIncompatibility(points, net.wires.toSet(), net.points.toSet())
}

/** Circuit.getWireSet(wire): every wire of the bundle a poked wire belongs to. */
fun CircuitTopology.wireSet(wire: WireSegment): Set<WireSegment> =
    (netAt(wire.from) ?: netAt(wire.to))?.wires?.toSet()?.takeIf { wire in it } ?: setOf(wire)
