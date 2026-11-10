package dev.logisim.mobile.core

/** Absolute circuit coordinates unless stored as CircuitComponent.explicitBounds. */
data class ComponentBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    fun contains(point: GridPoint): Boolean = point.x in left..right && point.y in top..bottom
}

fun CircuitComponent.relativePoint(point: GridPoint): GridPoint {
    val transformed = when (facing) {
        "west" -> GridPoint(-point.x, -point.y)
        "north" -> GridPoint(point.y, -point.x)
        "south" -> GridPoint(-point.y, point.x)
        else -> point
    }
    return GridPoint(position.x + transformed.x, position.y + transformed.y)
}

private fun CircuitComponent.absolute(x: Int, y: Int) = GridPoint(position.x + x, position.y + y)
private fun CircuitComponent.orientedInput(axis: Int, across: Int): GridPoint = when (facing) {
    "west" -> absolute(axis, across)
    "north" -> absolute(across, axis)
    "south" -> absolute(across, -axis)
    else -> absolute(-axis, across)
}

private fun CircuitComponent.isClassic(): Boolean = attributes["appearance"] != "logisim_evolution"

private fun CircuitComponent.gateInputs(): List<GridPoint> {
    val count = attributeInt("inputs", 2).coerceIn(2, 64)
    val size = attributeInt("size", 50)
    val axis = size + (if (kind in setOf(ComponentKind.XOR, ComponentKind.XNOR)) 10 else 0) +
        (if (kind in setOf(ComponentKind.NAND, ComponentKind.NOR, ComponentKind.XNOR)) 10 else 0)
    val (start, distance, lower) = when {
        count <= 3 && size < 40 -> Triple(-5, 10, 10)
        count <= 3 && (size < 60 || count <= 2) -> Triple(-10, 20, 20)
        count <= 3 -> Triple(-15, 30, 30)
        count == 4 && size >= 60 -> Triple(-5, 20, 0)
        else -> Triple(-5, 10, 10)
    }
    return (0 until count).map { index ->
        var y = if (count % 2 == 1) start * (count - 1) + distance * index
        else start * count + distance * index + if (index >= count / 2) lower else 0
        if (count == 4 && size >= 60) y -= 10
        orientedInput(axis + if (attributes["negate$index"] == "true") 10 else 0, y)
    }
}

internal fun CircuitComponent.legacyInputPorts(): List<GridPoint> {
    if (explicitPorts.isNotEmpty()) return explicitPorts.filter { it.direction != PortDirection.OUTPUT }.map { relativePoint(it.position) }
    return when (kind) {
        ComponentKind.INPUT, ComponentKind.CLOCK, ComponentKind.CONSTANT, ComponentKind.POWER,
        ComponentKind.GROUND, ComponentKind.BUTTON, ComponentKind.TEXT -> emptyList()
        ComponentKind.OUTPUT, ComponentKind.TUNNEL, ComponentKind.PROBE, ComponentKind.LED -> listOf(position)
        ComponentKind.NOT -> listOf(orientedInput(attributeInt("size", 30), 0))
        ComponentKind.BUFFER -> listOf(orientedInput(20, 0))
        ComponentKind.CONTROLLED_BUFFER, ComponentKind.CONTROLLED_INVERTER -> {
            val inverter = kind == ComponentKind.CONTROLLED_INVERTER
            val size = if (inverter) attributeInt("size", 30) else 20
            val ctrl = if (attributes["control"] == "left") -10 else 10
            listOf(relativePoint(GridPoint(-size, 0)), relativePoint(GridPoint(-(size - 10), ctrl)))
        }
        ComponentKind.D_FLIP_FLOP -> if (isClassic())
            listOf(absolute(-40, 20), absolute(-40, 0), absolute(-10, 30), absolute(-30, 30))
        else listOf(absolute(-10, 10), absolute(-10, 50), absolute(20, 60), absolute(20, 0))
        // These memory symbols do not expose a facing attribute in Logisim.
        ComponentKind.REGISTER -> if (isClassic())
            listOf(absolute(-30, 0), absolute(-20, 20), absolute(-30, 10), absolute(-10, 20))
        else listOf(absolute(0, 30), absolute(0, 70), absolute(0, 50), absolute(30, 90))
        ComponentKind.COUNTER -> if (isClassic())
            listOf(absolute(-30, 0), absolute(-20, 20), absolute(-30, -10), absolute(-10, 20), absolute(-30, 10), absolute(-20, -20))
        else listOf(absolute(0, if (width == 1) 120 else 110), absolute(0, 80), absolute(0, 30), absolute(0, 20), absolute(0, 70), absolute(0, 50))
        ComponentKind.ROM -> listOf(absolute(0, 10))
        ComponentKind.RAM -> listOf(absolute(0, 10), absolute(0, 60), absolute(0, 20), absolute(0, 30), absolute(0, 50))
        ComponentKind.ADDER, ComponentKind.SUBTRACTOR -> listOf(absolute(-40, -10), absolute(-40, 10), absolute(-20, -20))
        ComponentKind.MULTIPLEXER -> muxInputs()
        ComponentKind.DECODER -> listOf(position) + if (attributes["enable"] != "false") listOf(orientedInput(10, 0)) else emptyList()
        ComponentKind.HEX_DISPLAY -> listOf(position) + if (attributes["dp"] != "false") listOf(absolute(20, 0)) else emptyList()
        ComponentKind.SEVEN_SEGMENT_DISPLAY -> listOf(absolute(10, 0), absolute(30, 0), absolute(30, 60), absolute(10, 60), absolute(0, 60), absolute(0, 0), absolute(20, 0)) +
            if (attributes["dp"] != "false") listOf(absolute(20, 60)) else emptyList()
        ComponentKind.SPLITTER -> splitterPorts()
        ComponentKind.BIT_EXTENDER -> listOf(orientedInput(40, 0)) + if (attributes["type"] == "input") listOf(relativePoint(GridPoint(-20, 20))) else emptyList()
        ComponentKind.SUBCIRCUIT -> emptyList()
        else -> gateInputs()
    }
}

internal fun CircuitComponent.legacyOutputPorts(): List<GridPoint> {
    if (explicitPorts.isNotEmpty()) return explicitPorts.filter { it.direction != PortDirection.INPUT }.map { relativePoint(it.position) }
    return when (kind) {
        ComponentKind.OUTPUT, ComponentKind.PROBE, ComponentKind.HEX_DISPLAY,
        ComponentKind.SEVEN_SEGMENT_DISPLAY, ComponentKind.LED, ComponentKind.TEXT,
        ComponentKind.TUNNEL, ComponentKind.SPLITTER, ComponentKind.SUBCIRCUIT -> emptyList()
        ComponentKind.D_FLIP_FLOP -> if (isClassic()) listOf(position, absolute(0, 20)) else listOf(absolute(50, 10), absolute(50, 50))
        ComponentKind.REGISTER -> listOf(if (isClassic()) position else absolute(60, 30))
        ComponentKind.COUNTER -> if (isClassic()) listOf(position, absolute(0, 10)) else {
            val x = 190 + ((width - 8) / 5) * 10
            listOf(absolute(x, if (width == 1) 120 else 110), absolute(x, 50))
        }
        ComponentKind.ROM, ComponentKind.RAM -> listOf(absolute(240, 60))
        ComponentKind.ADDER, ComponentKind.SUBTRACTOR -> listOf(position, absolute(-20, 20))
        ComponentKind.DECODER -> decoderOutputs()
        else -> listOf(position)
    }
}

internal fun CircuitComponent.legacyOutputPort(): GridPoint? = legacyOutputPorts().firstOrNull()

private fun CircuitComponent.muxInputs(): List<GridPoint> {
    val select = attributeInt("select", 1).coerceIn(1, 6)
    val count = 1 shl select
    val narrow = attributes["size"] == "20"
    val depth = if (narrow) 20 else if (count == 2) 30 else 40
    val data = (0 until count).map { index -> orientedInput(depth, if (count == 2) -10 + index * 20 else -count * 5 + index * 10) }
    val sign = if (attributes["selloc"] == "tr") -1 else 1
    val along = if (narrow) 10 else 20
    val across = sign * if (count == 2) 20 else count * 5
    var selector = when (facing) {
        "west" -> absolute(along, across)
        "north" -> absolute(-across, along)
        "south" -> absolute(-across, -along)
        else -> absolute(-along, across)
    }
    if (narrow && count > 2) selector = when {
        facing in setOf("north", "south") && sign == 1 -> selector.copy(x = selector.x - 10)
        facing in setOf("east", "west") && sign == -1 -> selector.copy(y = selector.y - 10)
        else -> selector
    }
    val enable = when (facing) {
        "west" -> selector.copy(x = selector.x - 10)
        "north" -> selector.copy(y = selector.y - 10)
        "south" -> selector.copy(y = selector.y + 10)
        else -> selector.copy(x = selector.x + 10)
    }
    return data + selector + if (attributes["enable"] == "true") listOf(enable) else emptyList()
}

private fun CircuitComponent.decoderOutputs(): List<GridPoint> {
    val count = 1 shl attributeInt("select", 1).coerceIn(1, 6)
    val topRight = attributes["selloc"] == "tr"
    val distance = if (count == 2) 10 else 20
    val start = if (facing in setOf("north", "south")) {
        if (topRight) -count * 10 else if (count == 2) 10 else 0
    } else {
        if (topRight) if (count == 2) 10 else 0 else if (count == 2) -30 else -count * 10
    }
    return (0 until count).map { index ->
        val across = start + index * if (count == 2) 20 else 10
        when (facing) {
            "west" -> absolute(-distance, across)
            "north" -> absolute(across, -distance)
            "south" -> absolute(across, distance)
            else -> absolute(distance, across)
        }
    }
}

/** Common bus first, then branches in desktop bit-end order. */
fun CircuitComponent.splitterPorts(): List<GridPoint> {
    val count = attributeInt("fanout", 2).coerceIn(1, 64)
    val gap = attributeInt("spacing", 1).coerceIn(1, 9) * 10
    val justify = when (attributes["appear"] ?: "left") { "right" -> 1; "center", "legacy" -> 0; else -> -1 }
    val vertical = facing in setOf("north", "south")
    val m = if (facing == "north" || facing == "east") 1 else -1
    return listOf(position) + (0 until count).map { index ->
        if (vertical) {
            val start = if (justify == 0) gap * ((count + 1) / 2 - 1) else if (m * justify < 0) -10 else 10 + gap * (count - 1)
            absolute(start - index * gap, -m * 20)
        } else {
            val start = if (justify == 0) -gap * (count / 2) else if (m * justify > 0) 10 else -(10 + gap * (count - 1))
            absolute(m * 20, start + index * gap)
        }
    }
}

/** Branch indexes are zero based; null means that the bit is disconnected. */
fun CircuitComponent.splitterBitEnds(): List<Int?> {
    val count = attributeInt("fanout", 2).coerceIn(1, 64)
    val base = width / count
    val extra = width % count
    val defaults = (0 until count).flatMap { index -> List(base + if (index < extra) 1 else 0) { index } }
    return (0 until width).map { index ->
        val raw = attributes["bit$index"]
        if (raw == null) defaults[index] else raw.toIntOrNull()?.takeIf { it in 0 until count }
    }
}

internal fun CircuitComponent.legacyPortWidths(): Map<GridPoint, Int> {
    if (explicitPorts.isNotEmpty()) return explicitPorts.associate { relativePoint(it.position) to it.width }
    if (kind == ComponentKind.SPLITTER) {
        val ports = splitterPorts()
        val mapping = splitterBitEnds()
        return buildMap {
            put(ports.first(), width)
            ports.drop(1).forEachIndexed { index, point ->
                val size = mapping.count { it == index }
                if (size > 0) put(point, size)
            }
        }
    }
    val inputs = legacyInputPorts()
    val outputs = legacyOutputPorts()
    val inputWidths = when (kind) {
        ComponentKind.D_FLIP_FLOP -> List(inputs.size) { 1 }
        ComponentKind.REGISTER, ComponentKind.COUNTER -> List(inputs.size) { if (it == 0) width else 1 }
        ComponentKind.ADDER, ComponentKind.SUBTRACTOR -> listOf(width, width, 1)
        ComponentKind.ROM -> listOf(attributeInt("addrWidth", 8).coerceIn(1, 24))
        ComponentKind.RAM -> listOf(attributeInt("addrWidth", 8).coerceIn(1, 24), width, 1, 1, 1)
        ComponentKind.CONTROLLED_BUFFER, ComponentKind.CONTROLLED_INVERTER -> listOf(width, 1)
        ComponentKind.HEX_DISPLAY -> listOf(4, 1)
        ComponentKind.SEVEN_SEGMENT_DISPLAY -> List(inputs.size) { 1 }
        ComponentKind.DECODER -> listOf(attributeInt("select", 1).coerceIn(1, 6), 1)
        ComponentKind.MULTIPLEXER -> {
            val count = 1 shl attributeInt("select", 1).coerceIn(1, 6)
            List(inputs.size) { if (it < count) width else if (it == count) attributeInt("select", 1) else 1 }
        }
        ComponentKind.BIT_EXTENDER -> listOf(attributeInt("in_width", 8).coerceIn(1, 64), 1)
        else -> List(inputs.size) { width }
    }
    return buildMap {
        inputs.forEachIndexed { index, point -> put(point, inputWidths.getOrElse(index) { width }) }
        outputs.forEachIndexed { index, point ->
            put(point, if (index > 0 && kind in setOf(ComponentKind.ADDER, ComponentKind.SUBTRACTOR, ComponentKind.COUNTER)) 1 else width)
        }
    }
}

internal fun CircuitComponent.legacyPortWidth(point: GridPoint): Int = legacyPortWidths()[point] ?: width

internal fun CircuitComponent.legacyBounds(): ComponentBounds {
    fun translated(box: ComponentBounds) = ComponentBounds(position.x + box.left, position.y + box.top, position.x + box.right, position.y + box.bottom)
    explicitBounds?.let { box ->
        val corners = listOf(GridPoint(box.left, box.top), GridPoint(box.right, box.top), GridPoint(box.left, box.bottom), GridPoint(box.right, box.bottom)).map { relativePoint(it) }
        return ComponentBounds(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }
    return when (kind) {
        ComponentKind.ROM, ComponentKind.RAM -> translated(ComponentBounds(0, 0, 240, 140))
        ComponentKind.HEX_DISPLAY -> translated(ComponentBounds(-15, -60, 25, 0))
        ComponentKind.SEVEN_SEGMENT_DISPLAY -> translated(ComponentBounds(-5, 0, 35, 60))
        ComponentKind.D_FLIP_FLOP -> translated(if (isClassic()) ComponentBounds(-40, -10, 0, 30) else ComponentBounds(-10, 0, 50, 60))
        ComponentKind.REGISTER -> translated(if (isClassic()) ComponentBounds(-30, -20, 0, 20) else ComponentBounds(0, 0, 60, 90))
        ComponentKind.COUNTER -> translated(if (isClassic()) ComponentBounds(-30, -20, 0, 20) else ComponentBounds(0, 0, 190 + ((width - 8) / 5) * 10, 110 + 20 * width))
        ComponentKind.INPUT, ComponentKind.OUTPUT, ComponentKind.PROBE -> {
            val radix = attributes["radix"] ?: "2"
            val digits = when (radix) { "16" -> (width + 3) / 4; "8" -> (width + 2) / 3; "10signed", "10unsigned" -> (width * 0.302).toInt() + 2; else -> width }
            val w = if (radix == "2") maxOf(20, minOf(8, digits) * 10) else ((maxOf(20, digits * 8) + 9) / 10) * 10
            val h = if (radix == "2") maxOf(20, ((digits + 7) / 8) * 20) else 20
            translated(when (facing) {
                "west" -> ComponentBounds(0, -h / 2, w, h / 2)
                "north" -> ComponentBounds(-w / 2, 0, w / 2, h)
                "south" -> ComponentBounds(-w / 2, -h, w / 2, 0)
                else -> ComponentBounds(-w, -h / 2, 0, h / 2)
            })
        }
        ComponentKind.CONSTANT, ComponentKind.CLOCK, ComponentKind.LED, ComponentKind.BUTTON,
        ComponentKind.POWER, ComponentKind.GROUND -> translated(ComponentBounds(-10, -10, 10, 10))
        ComponentKind.TEXT -> translated(ComponentBounds(0, -12, maxOf(20, (attributes["text"] ?: label).length * 7), 8))
        ComponentKind.TUNNEL -> {
            val length = maxOf(20, label.length * 6 + 6)
            translated(when (facing) {
                "east" -> ComponentBounds(-length, -8, 0, 8)
                "west" -> ComponentBounds(0, -8, length, 8)
                "north" -> ComponentBounds(-length / 2, 0, length / 2, 18)
                else -> ComponentBounds(-length / 2, -18, length / 2, 0)
            })
        }
        else -> {
            val ports = legacyInputPorts() + legacyOutputPorts() + position
            var left = ports.minOf { it.x }
            var right = ports.maxOf { it.x }
            var top = ports.minOf { it.y }
            var bottom = ports.maxOf { it.y }
            val gate = kind in setOf(ComponentKind.AND, ComponentKind.OR, ComponentKind.XOR, ComponentKind.NAND, ComponentKind.NOR, ComponentKind.XNOR)
            val half = if (gate) maxOf(attributeInt("size", 50), attributeInt("inputs", 2) * 10) / 2 else 10
            if (facing in setOf("east", "west")) { top = minOf(top, position.y - half); bottom = maxOf(bottom, position.y + half) }
            else { left = minOf(left, position.x - half); right = maxOf(right, position.x + half) }
            ComponentBounds(left, top, right, bottom)
        }
    }
}

/** Adapter for the established geometry while component families move into their definitions. */
internal fun legacyComponentLayout(component: CircuitComponent): ComponentLayout {
    require(component.kind in ComponentKind.entries || component.kind == ComponentKind.SUBCIRCUIT) {
        "Geometria não registrada para ${component.kind.name}."
    }
    if (component.explicitPorts.isNotEmpty()) return ComponentLayout(
        component.explicitPorts.map {
            ComponentTerminal(it.name, component.relativePoint(it.position), it.direction, it.width, it.label)
        },
        component.legacyBounds(),
    )
    val inputs = component.legacyInputPorts()
    val outputs = component.legacyOutputPorts()
    val inputNames = when (component.kind) {
        ComponentKind.D_FLIP_FLOP -> listOf("data", "clock", "reset", "set")
        ComponentKind.REGISTER -> listOf("data", "clock", "enable", "clear")
        ComponentKind.COUNTER -> listOf("data", "clock", "load", "clear", "enable", "up")
        ComponentKind.ROM -> listOf("address")
        ComponentKind.RAM -> listOf("address", "data", "enable", "write", "clock")
        ComponentKind.ADDER, ComponentKind.SUBTRACTOR -> listOf("a", "b", "carryIn")
        ComponentKind.CONTROLLED_BUFFER, ComponentKind.CONTROLLED_INVERTER -> listOf("data", "enable")
        else -> inputs.indices.map { "in$it" }
    }
    val outputNames = when (component.kind) {
        ComponentKind.D_FLIP_FLOP -> listOf("q", "notQ")
        ComponentKind.COUNTER, ComponentKind.ADDER, ComponentKind.SUBTRACTOR -> listOf("out", "carryOut")
        else -> outputs.indices.map { if (it == 0) "out" else "out$it" }
    }
    val ports = inputs.mapIndexed { index, point ->
        ComponentTerminal(inputNames.getOrElse(index) { "in$index" }, point, PortDirection.INPUT, component.legacyPortWidth(point))
    } + outputs.mapIndexed { index, point ->
        ComponentTerminal(outputNames.getOrElse(index) { "out$index" }, point, PortDirection.OUTPUT, component.legacyPortWidth(point))
    }
    return ComponentLayout(ports, component.legacyBounds())
}

@kotlin.jvm.JvmOverloads
fun CircuitComponent.layout(registry: ComponentRegistry = ComponentCatalog.registry): ComponentLayout =
    if (kind == ComponentKind.SUBCIRCUIT) legacyComponentLayout(this) else registry.layout(this)

@kotlin.jvm.JvmOverloads
fun CircuitComponent.inputPorts(registry: ComponentRegistry = ComponentCatalog.registry): List<GridPoint> =
    layout(registry).inputs.map { it.position }

@kotlin.jvm.JvmOverloads
fun CircuitComponent.outputPorts(registry: ComponentRegistry = ComponentCatalog.registry): List<GridPoint> =
    layout(registry).outputs.map { it.position }

@kotlin.jvm.JvmOverloads
fun CircuitComponent.outputPort(registry: ComponentRegistry = ComponentCatalog.registry): GridPoint? =
    outputPorts(registry).firstOrNull()

@kotlin.jvm.JvmOverloads
fun CircuitComponent.portWidths(registry: ComponentRegistry = ComponentCatalog.registry): Map<GridPoint, Int> =
    layout(registry).ports.associate { it.position to it.width }

@kotlin.jvm.JvmOverloads
fun CircuitComponent.portWidth(point: GridPoint, registry: ComponentRegistry = ComponentCatalog.registry): Int =
    portWidths(registry)[point] ?: width

@kotlin.jvm.JvmOverloads
fun CircuitComponent.bounds(registry: ComponentRegistry = ComponentCatalog.registry): ComponentBounds =
    layout(registry).bounds
