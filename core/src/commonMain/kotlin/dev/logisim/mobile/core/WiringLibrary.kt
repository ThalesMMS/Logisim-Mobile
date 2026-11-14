package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText

import kotlin.math.max

internal const val ORIGINAL_TRANSISTOR_ATTRIBUTE = "logisim-mobile-original-transistor"
object ClockPoke : ComponentEvent

/** File identities and runtime definitions for the desktop Wiring library. */
/** The value Evolution writes for a splitter bit that reaches no output. */
const val SPLITTER_BIT_NONE = "none"

object WiringLibrary {
    val PullResistor = ComponentKind("PULL_RESISTOR")
    val PowerOnReset = ComponentKind("POWER_ON_RESET")
    val NoConnect = ComponentKind("NO_CONNECT")
    val Transistor = ComponentKind("TRANSISTOR")
    val TransmissionGate = ComponentKind("TRANSMISSION_GATE")

    private val width = CommonAttributes.Width
    private val facing = CommonAttributes.Facing
    private val label = CommonAttributes.Label
    private val font = AttributeKey.text("labelfont", "SansSerif bold 16")
    private val labelLocation = AttributeKey.choice("labelloc", "west", setOf("center", "north", "south", "east", "west"))
    private val appearance = AttributeKey.choice("appearance", "classic", setOf("classic", "NewPins"))
    private val radix = AttributeKey.choice("radix", "2", setOf("2", "8", "10signed", "10unsigned", "16", "float"))
    private val pinType = AttributeKey.choice("type", "input", setOf("input", "output"))
    private val pinBehavior = AttributeKey.choice("behavior", "simple", setOf("simple", "tristate", "pullup", "pulldown"))
    private val initial = AttributeKey("initial", 0uL, ::parseHexLong, { "0x${it.toString(16)}" })
    private val value = AttributeKey("value", ULong.MAX_VALUE, ::parseHexLong, { "0x${it.toString(16)}" })
    private val highDuration = AttributeKey.int("highDuration", 1, 1..Int.MAX_VALUE)
    private val lowDuration = AttributeKey.int("lowDuration", 1, 1..Int.MAX_VALUE)
    private val phaseOffset = AttributeKey.int("phaseOffset", 0, 0..Int.MAX_VALUE)
    private val pull = AttributeKey.choice("pull", "0", setOf("0", "1", "X"))
    private val transistorType = AttributeKey.choice("type", "p", setOf("p", "n"))
    private val selectLocation = AttributeKey.choice("selloc", "tr", setOf("tr", "bl"))
    private val inputWidth = AttributeKey.int("in_width", 8, 1..64)
    private val outputWidth = AttributeKey.int("out_width", 16, 1..64)
    private val extensionType = AttributeKey.choice("type", "sign", setOf("zero", "one", "sign", "input"))
    private val porSize = AttributeKey.choice("porsize", "3", setOf("1", "2", "3"))
    private val porTransition = AttributeKey.choice("porTransition", "1", setOf("1", "2"))
    private val porDuration = AttributeKey.int("PorHighDuration", 2, 1..10)
    private val incoming = AttributeKey.int("incoming", 2, 1..64)
    private val fanout = AttributeKey.int("fanout", 2, 1..64)
    private val spacing = AttributeKey.int("spacing", 1, 1..9)
    private val splitterAppearance = AttributeKey.choice("appear", "left", setOf("left", "right", "center", "legacy"))
    private val bitEnds = (0 until 64).map { bit -> AttributeKey.text("bit$bit", "") }
    private object InvertedClockPhase : ComponentState

    fun definitions(): List<ComponentDefinition> = listOf(
        pinDefinition(output = false),
        pinDefinition(output = true),
        definition(ComponentKind.PROBE, "Probe", listOf(facing, radix, label, labelLocation, font, appearance),
            layout = { component, attributes -> probeLayout(component, attributes, 1) }),
        definition(ComponentKind.TUNNEL, "Tunnel", listOf(AttributeKey.direction(default = Direction.WEST), width, label, font),
            layout = ::tunnelLayout),
        definition(PullResistor, "Pull Resistor", listOf(AttributeKey.direction(default = Direction.SOUTH), pull),
            layout = ::pullLayout, pullDrivers = { _, attributes ->
                mapOf("pin" to LogicVector.fromBit(when (attributes[pull]) {
                    "1" -> LogicValue.HIGH
                    "X" -> LogicValue.ERROR
                    else -> LogicValue.LOW
                }))
            }),
        definition(ComponentKind.CLOCK, "Clock", listOf(facing, highDuration, lowDuration, phaseOffset, label, labelLocation, font, appearance),
            extraDefaults = mapOf("appearance" to "NewPins"), layout = { component, attributes ->
                ComponentLayout(listOf(terminal(component, "out", 0, 0, PortDirection.OUTPUT, 1)),
                    pinBounds(component, attributes, 1, isPin = true))
            }, behavior = behavior { context ->
                val attrs = context.attributes
                val poked = context.events.count { it === ClockPoke } % 2 != 0
                val inverted = (context.state === InvertedClockPhase) xor poked
                val waveform = WiringValues.clock(context.ticks, attrs[highDuration], attrs[lowDuration], attrs[phaseOffset])
                val output = context.inputs["drive"]?.resize(1) ?: if (inverted) waveform.inverted() else waveform
                ComponentEvaluation(mapOf("out" to output), state = if (inverted) InvertedClockPhase else EmptyComponentState)
            }, combinational = false).copy(defaultsForVersion = { mapOf("appearance" to "classic") }),
        definition(PowerOnReset, "POR", listOf(facing, porSize, porTransition, porDuration),
            layout = ::porLayout, behavior = PorBehavior, combinational = false),
        definition(ComponentKind.CONSTANT, "Constant", listOf(facing, width, value),
            layout = ::constantLayout, behavior = behavior { context ->
                ComponentEvaluation(mapOf("out" to LogicVector.of(context.attributes[width], context.attributes[value])))
            }, combinational = true),
        definition(ComponentKind.POWER, "Power", listOf(AttributeKey.direction(default = Direction.NORTH), width),
            layout = { component, attrs -> supplyLayout(component, attrs, 15) },
            behavior = behavior { ComponentEvaluation(mapOf("out" to LogicVector.high(it.attributes[width]))) }, combinational = true),
        definition(ComponentKind.GROUND, "Ground", listOf(AttributeKey.direction(default = Direction.SOUTH), width),
            layout = { component, attrs -> supplyLayout(component, attrs, 14) },
            behavior = behavior { ComponentEvaluation(mapOf("out" to LogicVector.low(it.attributes[width]))) }, combinational = true),
        definition(NoConnect, "NoConnect", listOf(width), supportsRotation = false,
            layout = { component, attrs ->
                ComponentLayout(listOf(terminal(component, "pin", 0, 0, PortDirection.INOUT, attrs[width])),
                    translated(component, ComponentBounds(-5, -5, 5, 5)))
            }),
        definition(Transistor, "Transistor", listOf(facing, width, transistorType, selectLocation),
            layout = { component, attrs -> transistorLayout(component, attrs, transmission = false) },
            behavior = behavior { context ->
                ComponentEvaluation(mapOf("out" to WiringValues.transistor(
                    context.input("in", context.attributes[width]),
                    context.input("gate", 1),
                    pType = context.attributes[transistorType] == "p",
                    original = context.attributes.raw[ORIGINAL_TRANSISTOR_ATTRIBUTE] == "true",
                )))
            }, combinational = true).copy(defaultsForVersion = { version ->
                if (CircFormatProfile.parse(version).originalLogisim) mapOf(ORIGINAL_TRANSISTOR_ATTRIBUTE to "true")
                else emptyMap()
            }),
        definition(TransmissionGate, "Transmission Gate", listOf(facing, width, selectLocation),
            layout = { component, attrs -> transistorLayout(component, attrs, transmission = true) },
            behavior = behavior { context ->
                ComponentEvaluation(mapOf("out" to WiringValues.transmissionGate(
                    context.input("in", context.attributes[width]), context.input("gateP", 1), context.input("gateN", 1))))
            }, combinational = true),
        definition(ComponentKind.BIT_EXTENDER, "Bit Extender", listOf(inputWidth, outputWidth, extensionType), supportsRotation = false,
            layout = ::extenderLayout, behavior = behavior { context ->
                ComponentEvaluation(mapOf("out" to WiringValues.extend(context.input("in", context.attributes[inputWidth]),
                    context.attributes[outputWidth], context.attributes[extensionType], context.input("extend", 1))))
            }, combinational = true),
        definition(ComponentKind.SPLITTER, "Splitter", listOf(facing, incoming, fanout, spacing, splitterAppearance) + bitEnds,
            layout = ::splitterLayout, presentAttribute = ::presentSplitterAttribute),
    )

    fun reconfigure(component: CircuitComponent, updates: Map<String, String>): CircuitComponent {
        return ComponentRegistry(definitions()).reconfigure(component, updates)
    }

    private fun reconfigureAttributes(component: CircuitComponent, updates: Map<String, String>): Map<String, String> {
        var source = component
        val changed = if (component.kind == ComponentKind.CONSTANT && "width" in updates && "value" !in updates) {
            val oldWidth = component.attributeInt("width", 1)
            val newWidth = updates.getValue("width").toIntOrNull()
                ?: throw IllegalArgumentException("A largura deve ser um número inteiro.")
            val oldValue = parseHexLong(component.attributes["value"] ?: "0xffffffffffffffff")
                ?: throw IllegalArgumentException("Valor inteiro inválido.")
            val extended = WiringValues.extend(LogicVector.of(oldWidth, oldValue), newWidth, "sign")
            updates + ("value" to "0x${extended.toULongOrNull()!!.toString(16)}")
        } else if (component.kind == ComponentKind.SPLITTER && ("fanout" in updates || "incoming" in updates)) {
            val newFanout = updates["fanout"]?.toIntOrNull() ?: component.attributeInt("fanout", 2)
            val newIncoming = updates["incoming"]?.toIntOrNull() ?: component.attributeInt("incoming", 2)
            require(newFanout in 1..64 && newIncoming in 1..64) { "Splitter: largura e fanout devem estar entre 1 e 64." }
            val withoutBits = component.attributes.filterNot { it.key.matches(Regex("bit\\d+")) }
            source = component.copy(attributes = withoutBits)
            val mapping = if ("incoming" in updates) {
                component.copy(attributes = withoutBits + mapOf("fanout" to newFanout.toString(), "incoming" to newIncoming.toString())).splitterBitEnds()
            } else component.splitterBitEnds().map { it?.coerceAtMost(newFanout - 1) }
            mapping.mapIndexed { bit, branch -> "bit$bit" to (branch?.toString() ?: "none") }.toMap() + updates
        } else updates
        return source.attributes + changed
    }

    private fun pinDefinition(output: Boolean): ComponentDefinition = definition(
        if (output) ComponentKind.OUTPUT else ComponentKind.INPUT, "Pin",
        listOf(facing, pinType, width, pinBehavior, label, labelLocation, font, radix, appearance, initial),
        extraDefaults = mapOf("type" to if (output) "output" else "input"),
        matches = { attrs -> (attrs["type"] == "output" || attrs["type"] == null && attrs["output"] == "true") == output },
        layout = { component, attributes ->
            ComponentLayout(listOf(terminal(component, if (output) "in" else "out", 0, 0,
                if (output) PortDirection.INPUT else PortDirection.OUTPUT, attributes[width])),
                pinBounds(component, attributes, attributes[width], isPin = true))
        },
        behavior = if (output) null else PinBehavior,
    )

    private fun definition(
        kind: ComponentKind,
        fileName: String,
        keys: List<AttributeKey<*>>,
        layout: (CircuitComponent, AttributeValues) -> ComponentLayout,
        behavior: ComponentBehavior? = null,
        extraDefaults: Map<String, String> = emptyMap(),
        supportsRotation: Boolean = true,
        matches: (Map<String, String>) -> Boolean = { true },
        pullDrivers: ((CircuitComponent, AttributeValues) -> Map<String, LogicVector>)? = null,
        combinational: Boolean = true,
        presentAttribute: (CircuitComponent, String) -> AttributePresentation? = { _, _ -> AttributePresentation() },
    ): ComponentDefinition {
        val schema = AttributeSchema(keys)
        val defaults = schema.defaults.filterNot { (name, _) -> name.startsWith("bit") } + extraDefaults
        return ComponentDefinition(kind, fileName, "#Wiring", defaults, schema,
            layout, behavior, supportsRotation, matches, pullDrivers, isCombinational = combinational,
            reconfigureAttributes = ::reconfigureAttributes, presentAttribute = presentAttribute)
    }

    /**
     * Evolution's SplitterAttributes: Bit 0..incoming-1, each a choice of None or an output branch, showing the
     * effective branch of a bit the file leaves at its default distribution. Writes stay "none" or the index.
     */
    private fun presentSplitterAttribute(component: CircuitComponent, name: String): AttributePresentation? {
        val bit = name.removePrefix("bit").takeIf { name.startsWith("bit") }?.toIntOrNull() ?: return AttributePresentation()
        val ends = component.splitterBitEnds()
        if (bit >= ends.size) return null
        val fanout = component.attributeInt("fanout", 2).coerceIn(1, 64)
        val branches = (0 until fanout).map(Int::toString)
        return AttributePresentation(ends[bit]?.toString() ?: SPLITTER_BIT_NONE, listOf(SPLITTER_BIT_NONE) + branches,
            mapOf(SPLITTER_BIT_NONE to LocalizedText("splitter_bit_none", fallback = "None")) +
                branches.associateWith { LocalizedText("splitter_bit_output", mapOf("n" to it), fallback = it) })
    }

    private fun behavior(evaluate: (ComponentEvaluationContext) -> ComponentEvaluation) = object : ComponentBehavior {
        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation = evaluate(context)
    }

    private data class PinState(val intended: LogicVector) : ComponentState
    private object PinBehavior : ComponentBehavior {
        override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState =
            PinState(WiringValues.initialPin(attributes[width], effectivePinBehavior(attributes), attributes[initial]))

        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
            val state = context.state as? PinState ?: initialState(context.component, context.attributes) as PinState
            val behavior = effectivePinBehavior(context.attributes)
            val fill = if (behavior == "pullup") LogicValue.HIGH else LogicValue.LOW
            val intended = (context.inputs["drive"] ?: state.intended).resize(context.attributes[width], fill)
            return ComponentEvaluation(mapOf("out" to WiringValues.pinDrive(intended, behavior)), PinState(intended))
        }
    }

    private data class PorState(val pulse: PowerOnResetPulse? = null) : ComponentState
    object PowerOnResetTriggered : ComponentEvent
    private object PorBehavior : ComponentBehavior {
        override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState = PorState()
        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
            val old = (context.state as? PorState)?.pulse
            val pulse = if (old == null || PowerOnResetTriggered in context.events) PowerOnResetPulse(
                context.elapsedTimeMillis, context.attributes[porDuration] * 1000L, context.attributes[porTransition] == "1",
            ) else old
            return ComponentEvaluation(mapOf("out" to pulse.output(context.elapsedTimeMillis)), PorState(pulse), delay = 0,
                nextWakeupMillis = pulse.deadlineMillis.takeIf { it > context.elapsedTimeMillis })
        }
    }

    private fun effectivePinBehavior(attributes: AttributeValues): String = attributes.raw["behavior"] ?: when {
        attributes.raw["pull"] == "up" -> "pullup"
        attributes.raw["pull"] == "down" -> "pulldown"
        attributes.raw["tristate"] == "true" -> "tristate"
        else -> attributes[pinBehavior]
    }

    private fun ComponentEvaluationContext.input(name: String, width: Int) = inputs[name] ?: LogicVector.unknown(width)

    /** Probe body width follows the observed value; its electrical terminal stays width-inferred. */
    fun probeLayout(component: CircuitComponent, attributes: AttributeValues, observedWidth: Int): ComponentLayout =
        ComponentLayout(listOf(terminal(component, "in", 0, 0, PortDirection.INPUT, 0)),
            pinBounds(component, attributes, observedWidth.coerceIn(1, 64), isPin = false))

    private fun pinBounds(component: CircuitComponent, attrs: AttributeValues, bits: Int, isPin: Boolean): ComponentBounds {
        val format = if (isPin && bits == 1) "2" else attrs.string("radix") ?: "2"
        val digits = when (format) {
            "16" -> (bits + 3) / 4
            "8" -> (bits + 2) / 3
            "10signed" -> (1uL shl (bits - 1)).toString().length + 1
            "10unsigned" -> (if (bits == 64) ULong.MAX_VALUE else (1uL shl bits) - 1uL).toString().length
            "float" -> if (bits == 64) 24 else 12
            else -> bits
        }
        var bodyWidth = if (format == "2") {
            if (digits < 2) 20 else minOf(digits, 8) * 10
        } else if (digits < 2) 20 else digits * 8
        var bodyHeight = if (format == "2") max(1, (digits + 7) / 8) * 20 else 20
        val modern = attrs[appearance] == "NewPins"
        if (modern) bodyWidth += if (digits == 1) 20 else 25
        bodyWidth = ((bodyWidth + 9) / 10) * 10
        val direction = Direction.parse(attrs.string("facing") ?: "east") ?: Direction.EAST
        if (modern && isPin && direction in setOf(Direction.NORTH, Direction.SOUTH)) {
            val oldWidth = bodyWidth
            bodyWidth = bodyHeight
            bodyHeight = oldWidth
        }
        val bounds = when (direction) {
            Direction.EAST -> ComponentBounds(-bodyWidth, -bodyHeight / 2, 0, bodyHeight - bodyHeight / 2)
            Direction.WEST -> ComponentBounds(0, -bodyHeight / 2, bodyWidth, bodyHeight - bodyHeight / 2)
            Direction.NORTH -> ComponentBounds(-bodyWidth / 2, 0, bodyWidth - bodyWidth / 2, bodyHeight)
            Direction.SOUTH -> ComponentBounds(-bodyWidth / 2, -bodyHeight, bodyWidth - bodyWidth / 2, 0)
        }
        return translated(component, bounds)
    }

    private fun pullLayout(component: CircuitComponent, attrs: AttributeValues): ComponentLayout {
        val bounds = when (Direction.parse(attrs.string("facing") ?: "south")) {
            Direction.EAST -> ComponentBounds(-42, -18, 0, 6)
            Direction.WEST -> ComponentBounds(0, -18, 42, 6)
            Direction.NORTH -> ComponentBounds(-8, 0, 18, 44)
            else -> ComponentBounds(-8, -44, 18, 0)
        }
        return ComponentLayout(listOf(terminal(component, "pin", 0, 0, PortDirection.INOUT, 0)), translated(component, bounds))
    }

    private fun supplyLayout(component: CircuitComponent, attrs: AttributeValues, length: Int) = ComponentLayout(
        listOf(terminal(component, "out", 0, 0, PortDirection.OUTPUT, attrs[width])),
        rotatedBounds(component, Direction.parse(attrs.string("facing")!!)!!, ComponentBounds(0, -8, length, 8)),
    )

    private fun constantLayout(component: CircuitComponent, attrs: AttributeValues): ComponentLayout {
        val bits = attrs[width]
        val bodyWidth = 7 + 7 * ((bits + 3) / 4 + if (bits == 1) 0 else 2)
        val bounds = when (attrs[facing]) {
            Direction.EAST -> ComponentBounds(-bodyWidth, -8, 0, 8)
            Direction.WEST -> ComponentBounds(0, -8, bodyWidth, 8)
            Direction.NORTH -> ComponentBounds(-bodyWidth / 2, 0, bodyWidth - bodyWidth / 2, 16)
            Direction.SOUTH -> ComponentBounds(-bodyWidth / 2, -16, bodyWidth - bodyWidth / 2, 0)
        }
        return ComponentLayout(listOf(terminal(component, "out", 0, 0, PortDirection.OUTPUT, bits)), translated(component, bounds))
    }

    private fun porLayout(component: CircuitComponent, attrs: AttributeValues): ComponentLayout {
        val wide = when (attrs[porSize]) { "1" -> 40; "2" -> 20; else -> 200 }
        val height = if (attrs[porSize] == "2") 20 else 40
        return ComponentLayout(listOf(terminal(component, "out", 0, 0, PortDirection.OUTPUT, 1)),
            rotatedBounds(component, attrs[facing], ComponentBounds(-wide, -height / 2, 0, height / 2)))
    }

    private fun extenderLayout(component: CircuitComponent, attrs: AttributeValues): ComponentLayout {
        val ports = mutableListOf(terminal(component, "out", 0, 0, PortDirection.OUTPUT, attrs[outputWidth]),
            terminal(component, "in", -40, 0, PortDirection.INPUT, attrs[inputWidth]))
        if (attrs[extensionType] == "input") ports += terminal(component, "extend", -20, -20, PortDirection.INPUT, 1)
        return ComponentLayout(ports, translated(component, ComponentBounds(-40, -20, 0, 20)))
    }

    private fun transistorLayout(component: CircuitComponent, attrs: AttributeValues, transmission: Boolean): ComponentLayout {
        val direction = attrs[facing]
        val dx = when (direction) { Direction.EAST -> -1; Direction.WEST -> 1; else -> 0 }
        val dy = when (direction) { Direction.NORTH -> 1; Direction.SOUTH -> -1; else -> 0 }
        val flip = (direction in setOf(Direction.NORTH, Direction.WEST)) == (attrs[selectLocation] == "tr")
        val a = GridPoint(20 * (dx - dy), 20 * (dx + dy))
        val b = GridPoint(20 * (dx + dy), 20 * (-dx + dy))
        val ports = mutableListOf(terminal(component, "out", 0, 0, PortDirection.OUTPUT, attrs[width]),
            terminal(component, "in", 40 * dx, 40 * dy, PortDirection.INPUT, attrs[width]))
        if (transmission) {
            val p = if (flip) b else a
            val n = if (flip) a else b
            ports += terminal(component, "gateP", p.x, p.y, PortDirection.INPUT, 1)
            ports += terminal(component, "gateN", n.x, n.y, PortDirection.INPUT, 1)
        } else {
            val gate = if (flip) b else a
            ports += terminal(component, "gate", gate.x, gate.y, PortDirection.INPUT, 1)
        }
        val bounds = if (transmission) rotatedBounds(component, direction, ComponentBounds(-40, -20, 0, 20)) else {
            val delta = if (attrs[selectLocation] == "tr") 20 else 0
            translated(component, when (direction) {
                Direction.NORTH -> ComponentBounds(-20 + delta, 0, delta, 40)
                Direction.SOUTH -> ComponentBounds(-20 + delta, -40, delta, 0)
                Direction.WEST -> ComponentBounds(0, -delta, 40, 20 - delta)
                Direction.EAST -> ComponentBounds(-40, -delta, 0, 20 - delta)
            })
        }
        return ComponentLayout(ports, bounds)
    }

    private fun tunnelLayout(component: CircuitComponent, attrs: AttributeValues): ComponentLayout {
        val fontSize = fontSize(attrs[font])
        val textWidth = max(10, fontSize * component.label.ifBlank { attrs[label] }.length / 2)
        val textHeight = max(10, fontSize)
        val box = when (Direction.parse(attrs.string("facing")!!)!!) {
            Direction.WEST -> ComponentBounds(2, -textHeight / 2 - 3, 8 + textWidth, textHeight - textHeight / 2 + 3)
            Direction.EAST -> ComponentBounds(-textWidth - 8, -textHeight / 2 - 3, -2, textHeight - textHeight / 2 + 3)
            Direction.NORTH -> ComponentBounds(-textWidth / 2 - 3, 2, textWidth - textWidth / 2 + 3, textHeight + 8)
            Direction.SOUTH -> ComponentBounds(-textWidth / 2 - 3, -textHeight - 8, textWidth - textWidth / 2 + 3, -2)
        }
        return ComponentLayout(listOf(terminal(component, "pin", 0, 0, PortDirection.INOUT, attrs[width])),
            translated(component, ComponentBounds(minOf(box.left, 0), minOf(box.top, 0), maxOf(box.right, 1), maxOf(box.bottom, 1))))
    }

    private fun splitterLayout(component: CircuitComponent, attrs: AttributeValues): ComponentLayout {
        val normalized = component.copy(attributes = attrs.raw.filterNot { it.key.startsWith("bit") && it.value.isEmpty() } + mapOf("incoming" to attrs[incoming].toString(),
            "fanout" to attrs[fanout].toString(), "spacing" to attrs[spacing].toString(), "appear" to attrs[splitterAppearance],
            "facing" to attrs[facing].serialized))
        val mapping = normalized.splitterBitEnds()
        val activeBits = (0 until attrs[incoming]).map { "bit$it" }.toSet()
        attrs.raw.filterKeys { it in activeBits }.values.forEach { raw ->
            require(raw == "none" || raw.isEmpty() || raw.toIntOrNull() in 0 until attrs[fanout]) { "A saída do bit está fora do fanout." }
        }
        val ports = normalized.splitterPorts().mapIndexed { index, point ->
            ComponentTerminal(if (index == 0) "in" else "out${index - 1}", point, PortDirection.INOUT,
                if (index == 0) attrs[incoming] else mapping.count { it == index - 1 })
        }
        val points = ports.map { it.position }
        return ComponentLayout(ports, ComponentBounds(points.minOf { it.x }, points.minOf { it.y },
            points.maxOf { it.x } + 1, points.maxOf { it.y } + 1))
    }

    private fun terminal(component: CircuitComponent, name: String, x: Int, y: Int, direction: PortDirection, width: Int) =
        ComponentTerminal(name, GridPoint(component.position.x + x, component.position.y + y), direction, width)

    private fun translated(component: CircuitComponent, bounds: ComponentBounds) = ComponentBounds(
        component.position.x + bounds.left, component.position.y + bounds.top,
        component.position.x + bounds.right, component.position.y + bounds.bottom,
    )

    private fun rotatedBounds(component: CircuitComponent, direction: Direction, bounds: ComponentBounds): ComponentBounds {
        fun rotate(x: Int, y: Int) = when (direction) {
            Direction.EAST -> GridPoint(x, y)
            Direction.NORTH -> GridPoint(y, -x)
            Direction.WEST -> GridPoint(-x, -y)
            Direction.SOUTH -> GridPoint(-y, x)
        }
        val corners = listOf(rotate(bounds.left, bounds.top), rotate(bounds.right, bounds.top),
            rotate(bounds.left, bounds.bottom), rotate(bounds.right, bounds.bottom))
        return translated(component, ComponentBounds(corners.minOf { it.x }, corners.minOf { it.y },
            corners.maxOf { it.x }, corners.maxOf { it.y }))
    }

    private fun fontSize(font: String): Int = Regex("(\\d+)(?:\\D*)$").find(font)?.groupValues?.get(1)?.toIntOrNull() ?: 16

    private fun parseHexLong(value: String): ULong? {
        val raw = value.lowercase()
        val negative = raw.startsWith('-')
        val magnitude = if (negative) raw.drop(1) else raw
        val (digits, radix) = when {
            magnitude.startsWith("0x") -> magnitude.drop(2) to 16
            magnitude.startsWith("0b") -> magnitude.drop(2) to 2
            magnitude.length > 1 && magnitude.startsWith('0') -> magnitude.drop(1) to 8
            else -> magnitude to 10
        }
        return if (negative) "-$digits".toLongOrNull(radix)?.toULong() else digits.toULongOrNull(radix)
    }
}
