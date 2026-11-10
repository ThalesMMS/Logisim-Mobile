package dev.logisim.mobile

import dev.logisim.mobile.core.CircuitComponent
import dev.logisim.mobile.core.ComponentCatalog
import dev.logisim.mobile.core.ComponentKind
import dev.logisim.mobile.core.ComponentRegistry
import dev.logisim.mobile.core.GridPoint
import dev.logisim.mobile.core.WireSegment
import dev.logisim.mobile.core.inputPorts
import dev.logisim.mobile.core.layout

/** A terminal stroke in circuit coordinates, drawn 3 units wide in the colour of [port]'s value. */
internal data class GateTerminalLine(val port: GridPoint, val start: GridPoint, val end: GridPoint)

/**
 * Location.create(x, y, true): Evolution rounds to the half grid by integer division, `Math.round(x / 5) * 5`, so
 * a translated point moves toward zero to a multiple of 5.
 */
private fun snapHalfGrid(value: Int): Int = value / 5 * 5

/** Location.translate(dir, dist, right) on a snapping location. */
private fun GridPoint.evolutionTranslate(facing: String, dist: Int, right: Int): GridPoint = when (facing) {
    "west" -> GridPoint(snapHalfGrid(x - dist), snapHalfGrid(y - right))
    "south" -> GridPoint(snapHalfGrid(x - right), snapHalfGrid(y + dist))
    "north" -> GridPoint(snapHalfGrid(x + right), snapHalfGrid(y - dist))
    else -> GridPoint(snapHalfGrid(x + dist), snapHalfGrid(y + right))
}

private val ControlledKinds = setOf(ComponentKind.CONTROLLED_BUFFER, ComponentKind.CONTROLLED_INVERTER)
private val InputLineKinds = setOf(ComponentKind.OR, ComponentKind.NOR, ComponentKind.XOR, ComponentKind.XNOR)

internal fun CircuitComponent.isControlledBuffer(): Boolean = kind in ControlledKinds

/** The control terminal of a Controlled Buffer or Inverter (Evolution port 2), which gets no pin marker. */
internal fun CircuitComponent.controlTerminal(registry: ComponentRegistry = ComponentCatalog.registry): GridPoint? =
    if (isControlledBuffer()) inputPorts(registry).getOrNull(1) else null

/**
 * ControlledBuffer.paintInstance: the control input is a 3-wide line from port 2 toward the body,
 * `pt0.translate(facing, 0, ±6)` (+6 with the control on the left), in the colour of the control value.
 */
internal fun controlledBufferControlLine(component: CircuitComponent, registry: ComponentRegistry = ComponentCatalog.registry): GateTerminalLine? {
    val control = component.controlTerminal(registry) ?: return null
    val right = if (component.attributes["control"] == "left") 6 else -6
    return GateTerminalLine(control, control, control.evolutionTranslate(component.facing, 0, right))
}

/**
 * PainterShaped.paintInputLines for the shaped OR, NOR, XOR and XNOR: each input gets a 3-wide line, in the
 * colour of its value, as long as getInputLineLengths finds the input inside the gate's shield. In the printer view
 * ([ends] given) an input only gets its line when `painter.isPortConnected(i + 1)`.
 */
internal fun shapedGateInputLines(
    component: CircuitComponent,
    registry: ComponentRegistry = ComponentCatalog.registry,
    ends: CircuitEnds? = null,
): List<GateTerminalLine> {
    if (component.kind !in InputLineKinds) return emptyList()
    val ports = component.inputPorts(registry)
    val size = component.attributes["size"]?.toIntOrNull() ?: 50
    val lengths = shapedInputLineLengths(ports.size, size)
    return ports.mapIndexedNotNull { index, port ->
        val length = lengths.getOrNull(index) ?: 0
        if (length == 0 || ends?.isConnected(port, component, registry) == false) null else GateTerminalLine(port, port, port.evolutionTranslate(component.facing, length, 0))
    }
}

/**
 * CircuitPoints: how many wire ends and component ends sit at each point. Circuit.isConnected(loc, ignore) holds
 * when anything other than the ignored component has an end there.
 */
internal class CircuitEnds(components: List<CircuitComponent>, wires: List<WireSegment>, registry: ComponentRegistry = ComponentCatalog.registry) {
    private val counts: Map<GridPoint, Int> = buildMap {
        fun add(point: GridPoint) { put(point, (get(point) ?: 0) + 1) }
        for (wire in wires) { add(wire.from); add(wire.to) }
        for (component in components) component.layout(registry).ports.map { it.position }.distinct().forEach(::add)
    }

    fun isConnected(point: GridPoint, ignore: CircuitComponent, registry: ComponentRegistry = ComponentCatalog.registry): Boolean {
        val own = if (ignore.layout(registry).ports.any { it.position == point }) 1 else 0
        return (counts[point] ?: 0) > own
    }
}

/** OrGate.getInputOffset facing east without negated inputs: the vertical offset of input [index]. */
private fun orInputOffsetY(inputs: Int, size: Int, index: Int): Int {
    val (skipStart, skipDist, skipLowerEven) = when {
        inputs <= 3 && size < 40 -> Triple(-5, 10, 10)
        inputs <= 3 && (size < 60 || inputs <= 2) -> Triple(-10, 20, 20)
        inputs <= 3 -> Triple(-15, 30, 30)
        inputs == 4 && size >= 60 -> Triple(-5, 20, 0)
        else -> Triple(-5, 10, 10)
    }
    if (inputs and 1 == 1) return skipStart * (inputs - 1) + skipDist * index
    var dy = skipStart * inputs + skipDist * index
    if (index >= inputs / 2) dy += skipLowerEven
    if (inputs == 4 && size >= 60) dy -= 10
    return dy
}

/** PainterShaped.getInputLineLengths: steps from one unit inside each input until it leaves the shield (at most 14). */
internal fun shapedInputLineLengths(inputs: Int, size: Int): IntArray {
    val first = orInputOffsetY(inputs, size, 0)
    val last = orInputOffsetY(inputs, size, inputs - 1)
    val totalHeight = maxOf(10 + kotlin.math.abs(last - first), size)
    val shield = shieldPolygon(size, totalHeight)
    return IntArray(inputs) { index ->
        val y = orInputOffsetY(inputs, size, index).toDouble()
        var x = -size + 1.0
        var steps = 0
        while (shield.contains(x, y) && steps < 15) {
            steps++
            x += 1.0
        }
        if (steps >= 15) 0 else steps
    }
}

private class Polygon(val points: List<Pair<Double, Double>>) {
    /** Even-odd test; the shield outline is simple, so it agrees with Java2D's non-zero rule. */
    fun contains(x: Double, y: Double): Boolean {
        var inside = false
        var j = points.lastIndex
        for (i in points.indices) {
            val (xi, yi) = points[i]
            val (xj, yj) = points[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}

/** PainterShaped.computeShield as a flattened, implicitly closed polygon. */
private fun shieldPolygon(width: Int, height: Int): Polygon {
    val (baseWidth, control) = when {
        width < 40 -> 30 to -22.0
        width < 60 -> 50 to -37.0
        else -> 70 to -50.0
    }
    val points = mutableListOf<Pair<Double, Double>>()
    fun quad(x0: Double, y0: Double, cx: Double, cy: Double, x1: Double, y1: Double) {
        val steps = 512
        for (step in 1..steps) {
            val t = step.toDouble() / steps
            val u = 1 - t
            points += (u * u * x0 + 2 * u * t * cx + t * t * x1) to (u * u * y0 + 2 * u * t * cy + t * t * y1)
        }
    }
    val baseStart = -baseWidth.toDouble() to -baseWidth / 2.0
    if (height <= width) {
        points += baseStart
        quad(baseStart.first, baseStart.second, control, 0.0, -baseWidth.toDouble(), baseWidth / 2.0)
    } else {
        val dx = minOf(20, (height - width) / 2 / 4)
        val w = width.toDouble()
        points += -w to (-(height / 2)).toDouble()
        quad(-w, (-(height / 2)).toDouble(), -w + dx, (-((width + height) / 4)).toDouble(), -w, (-(width / 2)).toDouble())
        points += baseStart
        quad(baseStart.first, baseStart.second, control, 0.0, -baseWidth.toDouble(), baseWidth / 2.0)
        quad(-baseWidth.toDouble(), baseWidth / 2.0, -w + dx, ((width + height) / 4).toDouble(), -w, (height / 2).toDouble())
    }
    return Polygon(points)
}
