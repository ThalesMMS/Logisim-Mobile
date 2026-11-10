package dev.logisim.mobile.core

/** The five components registered by the desktop Plexers library. */
object PlexersLibrary {
    val Multiplexer = ComponentKind.MULTIPLEXER
    val Demultiplexer = ComponentKind("DEMULTIPLEXER")
    val Decoder = ComponentKind.DECODER
    val PriorityEncoder = ComponentKind("PRIORITY_ENCODER")
    val BitSelector = ComponentKind("BIT_SELECTOR")

    private val facing = AttributeKey.direction()
    private val width = AttributeKey.int("width", 1, 1..64)
    private val selectorDataWidth = AttributeKey.int("width", 8, 1..64)
    private val select = AttributeKey.int("select", 1, 1..8)
    private val prioritySelect = AttributeKey.int("select", 3, 1..8)
    private val group = AttributeKey.int("group", 1, 1..64)
    private val size = AttributeKey.choice("size", "40", setOf("20", "40"))
    private val location = AttributeKey.choice("selloc", "bl", setOf("bl", "tr"))
    private val enable = AttributeKey.boolean("enable", false)
    private val decoderEnable = AttributeKey.boolean("enable", true)
    private val tristate = AttributeKey.boolean("tristate", false)
    private val disabled = AttributeKey.choice("disabled", "0", setOf("0", "Z"))

    val definitions: List<ComponentDefinition> = listOf(
        definition(Multiplexer, "Multiplexer", listOf(facing, width, select, size, location, enable, disabled),
            ::muxLayout, Behavior.MUX),
        definition(Demultiplexer, "Demultiplexer", listOf(facing, width, select, location, enable, tristate, disabled),
            ::demuxLayout, Behavior.DEMUX),
        definition(Decoder, "Decoder", listOf(facing, select, location, decoderEnable, tristate, disabled),
            ::decoderLayout, Behavior.DECODER),
        definition(PriorityEncoder, "Priority Encoder", listOf(facing, prioritySelect, disabled),
            ::priorityLayout, Behavior.PRIORITY),
        definition(BitSelector, "BitSelector", listOf(facing, selectorDataWidth, group, location),
            ::selectorLayout, Behavior.BIT_SELECTOR),
    )

    fun install(registry: ComponentRegistry): ComponentRegistry = registry.withDefinitions(definitions)

    private fun definition(
        kind: ComponentKind,
        fileName: String,
        keys: List<AttributeKey<*>>,
        layout: (CircuitComponent, AttributeValues) -> ComponentLayout,
        behavior: ComponentBehavior,
    ): ComponentDefinition {
        val schema = AttributeSchema(keys)
        return ComponentDefinition(kind, fileName, "#Plexers", schema.defaults, schema, layout, behavior,
            defaultsForVersion = { version ->
                buildMap {
                    when (kind) {
                        Multiplexer, Demultiplexer -> put("enable", versionAtMost(version, 3, 6, 1).toString())
                        Decoder -> put("enable", (!versionAtMost(version, 2, 6, 3)).toString())
                    }
                    if (disabled in keys && versionAtMost(version, 2, 7, 1)) put("disabled", "Z")
                }
            }, isCombinational = true)
    }

    private fun versionAtMost(raw: String, major: Int, minor: Int, patch: Int): Boolean {
        val parts = raw.substringBefore('-').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for ((index, maximum) in listOf(major, minor, patch).withIndex()) {
            val actual = parts.getOrElse(index) { 0 }
            if (actual != maximum) return actual < maximum
        }
        return true
    }

    private enum class Behavior : ComponentBehavior {
        MUX, DEMUX, DECODER, PRIORITY, BIT_SELECTOR;

        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
            val attrs = context.attributes
            val disabledMode = if (attrs.string("disabled") == "Z") PlexerDisabledOutput.FLOATING else PlexerDisabledOutput.ZERO
            fun input(name: String, bits: Int) = context.inputs[name] ?: LogicVector.unknown(bits)
            val outputs = when (this) {
                MUX -> {
                    val bits = attrs[width]
                    val count = 1 shl attrs[select]
                    mapOf("out" to PlexersLogic.multiplexer((0 until count).map { input("in$it", bits) },
                        input("select", attrs[select]), if (attrs[enable]) input("enable", 1) else null,
                        "enable" in context.connectedInputs, disabledMode))
                }
                DEMUX -> {
                    val count = 1 shl attrs[select]
                    PlexersLogic.demultiplexer(input("data", attrs[width]), input("select", attrs[select]), count,
                        if (attrs[enable]) input("enable", 1) else null, "enable" in context.connectedInputs,
                        attrs[tristate], disabledMode).mapIndexed { index, value -> "out$index" to value }.toMap()
                }
                DECODER -> {
                    val count = 1 shl attrs[select]
                    PlexersLogic.decoder(input("select", attrs[select]), count,
                        if (attrs[decoderEnable]) input("enable", 1) else null, "enable" in context.connectedInputs,
                        attrs[tristate], disabledMode).mapIndexed { index, value -> "out$index" to value }.toMap()
                }
                PRIORITY -> {
                    val bits = attrs[prioritySelect]
                    val encoded = PlexersLogic.priorityEncoder((0 until (1 shl bits)).map { input("in$it", 1) }, bits,
                        input("enable", 1), disabledMode)
                    mapOf("out" to encoded.value, "enableOut" to encoded.enableOut, "groupSelect" to encoded.groupSelect)
                }
                BIT_SELECTOR -> mapOf("out" to PlexersLogic.bitSelector(input("data", attrs[selectorDataWidth]),
                    input("select", PlexersLogic.bitSelectorSelectBits(attrs[selectorDataWidth], attrs[group])), attrs[group]))
            }
            return ComponentEvaluation(outputs, context.state, delay = 3)
        }
    }

    private fun muxLayout(component: CircuitComponent, attrs: AttributeValues): ComponentLayout {
        val dir = attrs[facing]
        val n = 1 shl attrs[select]
        val narrow = attrs[size] == "20"
        val w = if (n == 2) if (narrow) 20 else 30 else if (narrow) 20 else 40
        val s = if (narrow) 10 else 20
        val m = if (attrs[location] == "bl") 1 else -1
        val k = if (n == 2) 20 else 5 * n
        val ports = Ports(component.position)
        for (i in 0 until n) {
            val t = if (n == 2) -10 + 20 * i else -5 * n + 10 * i
            val point = if (dir.horizontal) GridPoint(-dir.dx * w, t) else GridPoint(t, -dir.dy * w)
            ports.input("in$i", point, attrs[width])
        }
        var sel = if (dir.horizontal) GridPoint(-dir.dx * s, m * k) else GridPoint(-m * k, -dir.dy * s)
        if (narrow && n > 2) {
            if (!dir.horizontal && m == 1) sel = GridPoint(sel.x - 10, sel.y)
            else if (dir.horizontal && m == -1) sel = GridPoint(sel.x, sel.y - 10)
        }
        ports.input("select", sel, attrs[select])
        if (attrs[enable]) ports.input("enable", GridPoint(sel.x + dir.dx * 10, sel.y + dir.dy * 10), 1)
        ports.output("out", GridPoint(0, 0), attrs[width])
        val base = if (n == 2) ComponentBounds(-w, -20, 0, 20) else {
            val adjust = if (narrow && dir in setOf(Direction.WEST, Direction.SOUTH)) 5 else 0
            ComponentBounds(-w, -5 * n - 10 + adjust, 0, 5 * n + 10 + adjust - if (narrow) 5 else 0)
        }
        return ports.layout(rotatedBounds(base, dir, component.position))
    }

    private fun demuxLayout(component: CircuitComponent, attrs: AttributeValues): ComponentLayout {
        val dir = attrs[facing]
        val n = 1 shl attrs[select]
        val m = if (attrs[location] == "bl") 1 else -1
        val w = if (n == 2) 30 else 40
        val k = if (n == 2) 20 else 5 * n
        val ports = Ports(component.position)
        for (i in 0 until n) {
            val t = if (n == 2) -10 + 20 * i else -5 * n + 10 * i
            ports.output("out$i", if (dir.horizontal) GridPoint(dir.dx * w, t) else GridPoint(t, dir.dy * w), attrs[width])
        }
        val sel = if (dir.horizontal) GridPoint(dir.dx * 20, m * k) else GridPoint(-m * k, dir.dy * 20)
        ports.input("select", sel, attrs[select])
        if (attrs[enable]) ports.input("enable", GridPoint(sel.x - dir.dx * 10, sel.y - dir.dy * 10), 1)
        ports.input("data", GridPoint(0, 0), attrs[width])
        val base = if (n == 2) ComponentBounds(0, -25, 30, 25) else ComponentBounds(0, -5 * n - 10, 40, 5 * n + 10)
        return ports.layout(rotatedBounds(base, dir, component.position))
    }

    private fun decoderLayout(component: CircuitComponent, attrs: AttributeValues): ComponentLayout {
        val dir = attrs[facing]
        val n = 1 shl attrs[select]
        val topRight = attrs[location] == "tr"
        val ports = Ports(component.position)
        for (i in 0 until n) {
            val start = if (n == 2) {
                if (dir.horizontal) if (topRight) 10 else -30 else if (topRight) -30 else 10
            } else if (dir.horizontal) if (topRight) 0 else -10 * n else if (topRight) -10 * n else 0
            val t = start + (if (n == 2) 20 else 10) * i
            val depth = if (n == 2) 10 else 20
            ports.output("out$i", if (dir.horizontal) GridPoint(dir.dx * depth, t) else GridPoint(t, dir.dy * depth), 1)
        }
        ports.input("select", GridPoint(0, 0), attrs[select])
        if (attrs[decoderEnable]) ports.input("enable", GridPoint(-dir.dx * 10, -dir.dy * 10), 1)
        val reversed = (dir in setOf(Direction.WEST, Direction.NORTH)) != topRight
        val base = if (n == 2) {
            val top = (if (reversed) 0 else -40) - 5
            ComponentBounds(-20, top, 10, top + 50)
        } else {
            val top = if (reversed) -10 else -10 * n - 10
            ComponentBounds(-20, top, 20, top + 10 * n + 20)
        }
        return ports.layout(rotatedBounds(base, dir, component.position))
    }

    private fun priorityLayout(component: CircuitComponent, attrs: AttributeValues): ComponentLayout {
        val dir = attrs[facing]
        val n = 1 shl attrs[prioritySelect]
        val ports = Ports(component.position)
        for (i in 0 until n) {
            val t = -5 * n + 10 + 10 * i
            ports.input("in$i", if (dir.horizontal) GridPoint(-dir.dx * 40, t) else GridPoint(t, -dir.dy * 40), 1)
        }
        ports.output("out", GridPoint(0, 0), attrs[prioritySelect])
        ports.input("enable", if (dir.horizontal) GridPoint(-dir.dx * 20, 5 * n + 10) else GridPoint(5 * n + 10, -dir.dy * 20), 1)
        ports.output("enableOut", if (dir.horizontal) GridPoint(-dir.dx * 20, -5 * n) else GridPoint(-5 * n, -dir.dy * 20), 1)
        ports.output("groupSelect", if (dir.horizontal) GridPoint(0, 10) else GridPoint(10, 0), 1)
        val bounds = when (dir) {
            Direction.EAST -> ComponentBounds(-40, -5 * n, 0, 5 * n + 10)
            Direction.WEST -> ComponentBounds(0, -5 * n, 40, 5 * n + 10)
            Direction.NORTH -> ComponentBounds(-5 * n, 0, 5 * n + 10, 40)
            Direction.SOUTH -> ComponentBounds(-5 * n, -40, 5 * n + 10, 0)
        }
        return ports.layout(translatedBounds(bounds, component.position))
    }

    private fun selectorLayout(component: CircuitComponent, attrs: AttributeValues): ComponentLayout {
        val dir = attrs[facing]
        val bottomLeft = attrs[location] == "bl"
        val ports = Ports(component.position)
        ports.output("out", GridPoint(0, 0), attrs[group])
        ports.input("data", GridPoint(-dir.dx * 30, -dir.dy * 30), attrs[selectorDataWidth])
        val sel = when (dir) {
            Direction.EAST -> GridPoint(-10, if (bottomLeft) 10 else -10)
            Direction.WEST -> GridPoint(10, if (bottomLeft) -10 else 10)
            Direction.NORTH -> GridPoint(if (bottomLeft) -10 else 10, 10)
            Direction.SOUTH -> GridPoint(if (bottomLeft) -10 else 10, -10)
        }
        ports.input("select", sel, PlexersLogic.bitSelectorSelectBits(attrs[selectorDataWidth], attrs[group]))
        return ports.layout(rotatedBounds(ComponentBounds(-30, -15, 0, 15), dir, component.position))
    }

    private class Ports(private val anchor: GridPoint) {
        private val values = mutableListOf<ComponentTerminal>()
        fun input(name: String, point: GridPoint, width: Int) = add(name, point, width, PortDirection.INPUT)
        fun output(name: String, point: GridPoint, width: Int) = add(name, point, width, PortDirection.OUTPUT)
        private fun add(name: String, point: GridPoint, width: Int, direction: PortDirection) {
            values += ComponentTerminal(name, GridPoint(anchor.x + point.x, anchor.y + point.y), direction, width)
        }
        fun layout(bounds: ComponentBounds) = ComponentLayout(values.toList(), bounds)
    }

    private val Direction.dx get() = when (this) { Direction.EAST -> 1; Direction.WEST -> -1; else -> 0 }
    private val Direction.dy get() = when (this) { Direction.SOUTH -> 1; Direction.NORTH -> -1; else -> 0 }
    private val Direction.horizontal get() = this == Direction.EAST || this == Direction.WEST

    private fun translatedBounds(bounds: ComponentBounds, anchor: GridPoint) = ComponentBounds(
        anchor.x + bounds.left, anchor.y + bounds.top, anchor.x + bounds.right, anchor.y + bounds.bottom,
    )

    private fun rotatedBounds(bounds: ComponentBounds, dir: Direction, anchor: GridPoint): ComponentBounds {
        val corners = listOf(GridPoint(bounds.left, bounds.top), GridPoint(bounds.right, bounds.top),
            GridPoint(bounds.left, bounds.bottom), GridPoint(bounds.right, bounds.bottom)).map {
            when (dir) {
                Direction.EAST -> it
                Direction.WEST -> GridPoint(-it.x, -it.y)
                Direction.NORTH -> GridPoint(it.y, -it.x)
                Direction.SOUTH -> GridPoint(-it.y, it.x)
            }
        }
        return ComponentBounds(anchor.x + corners.minOf { it.x }, anchor.y + corners.minOf { it.y },
            anchor.x + corners.maxOf { it.x }, anchor.y + corners.maxOf { it.y })
    }
}
