package dev.logisim.mobile.core

object ExtraIoKinds {
    val BinaryToBcd = ComponentKind("BIN_TO_BCD")
    val BcdToSevenSegment = ComponentKind("BCD_TO_SEVEN_SEGMENT")
    val Switch = ComponentKind("SWITCH")
    val Buzzer = ComponentKind("BUZZER")
    val Slider = ComponentKind("SLIDER")
    val Oscilloscope = ComponentKind("DIGITAL_OSCILLOSCOPE")
    val PlaRom = ComponentKind("PLA_ROM")
    val TwoWaySwitch = ComponentKind("TWO_WAY_SWITCH")
    val TwoPinLed = ComponentKind("TWO_PIN_LED")
    val Ne555 = ComponentKind("NE555")
    val all = setOf(BinaryToBcd, BcdToSevenSegment, Switch, Buzzer, Slider, Oscilloscope, PlaRom, TwoWaySwitch, TwoPinLed, Ne555)
}

sealed interface ExtraIoEvent : ComponentEvent {
    data object Toggle : ExtraIoEvent
    data class SetSwitch(val closed: Boolean) : ExtraIoEvent
    data class MoveSlider(val position: Int) : ExtraIoEvent
    data object ClearTrace : ExtraIoEvent
}

data class ExtraSwitchState(val closed: Boolean = false) : ComponentState

object ExtraIoAttributes {
    val Facing = AttributeKey.direction()
    val Width = AttributeKey.int("width", 1, 1..64)
    val Label = AttributeKey.text("label")
    val LabelLocation = AttributeKey.choice("labelloc", "north", setOf("center", "north", "south", "east", "west"))
    val LabelFont = AttributeKey.text("labelfont", "SansSerif bold 16")
    val LabelColor = color("labelcolor", "#000000")
    val LabelVisible = AttributeKey.boolean("labelvisible", true)
    val Color = color("color", "#ffffff")
    val BinaryWidth = AttributeKey.int("binvalue", 9, 4..13)
    val SliderWidth = AttributeKey.int("width", 8, 1..8)
    val SliderDirection = AttributeKey.choice("Direction", "left_to_right", setOf("left_to_right", "right_to_left"))
    val Radix = AttributeKey.choice("radix", "2", setOf("2", "8", "10signed", "10unsigned", "16", "float"))
    val SwitchMode = AttributeKey.choice("mode", "1in2out", setOf("1in2out", "2in1out"))
    val Bipolar = AttributeKey.boolean("bipolar", false)
    val OnColor = color("color", "#f00000").labeled("attribute_label_on_color")
    val OffColor = color("offcolor", "#404040")
    val ReverseColor = color("reversecolor", "#00f000")
    val Inputs = AttributeKey.int("inputs", 4, 1..32)
    val ProductTerms = AttributeKey.int("and", 4, 1..32)
    val Outputs = AttributeKey.int("outputs", 4, 1..32)
    val Contents = AttributeKey.text("Contents")
    val Select = AttributeKey.choice("Select", "low", setOf("low", "high")).labeled("attribute_label_select_active_level")
    val ScopeInputs = AttributeKey.int("inputs", 3, 1..32)
    val ScopeStates = AttributeKey.int("nState", 10, 4..35)
    val ScopeEdges = AttributeKey.choice("frontlines", "rising", setOf("no", "rising", "falling", "both"))
    val ScopeClock = AttributeKey.boolean("showclock", true)
    val ScopeColor = color("color", "#00d0d0").labeled("attribute_label_border_color")
    val TimerMode = AttributeKey.choice("mode", "astable", setOf("astable", "monostable"))
    val Resistance = AttributeKey("r1", 71660, { parseNe555Quantity(it, resistance = true) }, Int::toString) {
        it in 1000..10_000_000
    }.entered(AttributeEntry.RESISTANCE, presets = listOf(1000, 10_000, 71_660, 100_000, 1_000_000, 10_000_000)) // NE555.R1_PRESETS
    val Capacitance = AttributeKey("c1", 10, { parseNe555Quantity(it, resistance = false) }, Int::toString) {
        it in 10..1000
    }.entered(AttributeEntry.CAPACITANCE, presets = listOf(10, 100, 1000)) // NE555.C1_PRESETS
    val VolumeWidth = AttributeKey.int("vol_width", 7, 1..64)
    val FrequencyUnit = AttributeKey.choice("freq_measure", "Hz", setOf("Hz", "dHz"))
    val Waveform = AttributeKey.choice("waveform", "Sine", BuzzerWaveform.entries.map { it.name }.toSet())
    val Channels = AttributeKey.int("channel", 3, 1..3)
    val SmoothLevel = AttributeKey.int("smooth_level", 2, 0..10)
    val SmoothWidth = AttributeKey.int("smooth_width", 2, 1..10)
    val SelectLocation = AttributeKey.choice("selloc", "bl", setOf("bl", "tr"))

    internal fun normalizeNe555Attributes(attributes: MutableMap<String, String>) {
        for (key in listOf(Resistance, Capacitance)) {
            val raw = attributes[key.name] ?: continue
            val value = key.parse(raw) ?: continue
            if (key.validate(value)) attributes[key.name] = key.format(value)
        }
    }

    private val decimalQuantity = Regex("""([+-]?)([0-9]*)(?:\.([0-9]*))?(?:[eE]([+-]?[0-9]+))?""")

    /** NE555 stores whole ohms or microfarads. Scale decimal digits exactly as in Evolution. */
    private fun parseNe555Quantity(raw: String, resistance: Boolean): Int? {
        if (raw.length > 80) return null
        var text = raw.trim { it <= ' ' }.replace(" ", "").replace("\u00a0", "")
            .replace('µ', 'u').replace('μ', 'u')
        if (resistance) text = text.replace("Ω", "")
        val unitSuffix = if (resistance) {
            listOf("ohms", "ohm").firstOrNull { text.lowercase().endsWith(it) }
        } else {
            listOf("farads", "farad").firstOrNull { text.lowercase().endsWith(it) }
                ?: "F".takeIf { text.endsWith(it) }
        }
        if (unitSuffix != null) text = text.dropLast(unitSuffix.length)
        val prefixLength = if (text.lowercase().endsWith("meg")) 3 else 1
        val prefixPower = if (prefixLength == 3) 6 else when (text.lastOrNull()) {
            'f' -> -15
            'p' -> -12
            'n' -> -9
            'u' -> -6
            'm' -> -3
            'k' -> 3
            'M' -> 6
            else -> null
        }
        if (prefixPower != null) text = text.dropLast(prefixLength)

        val (sign, whole, fraction, exponent) = decimalQuantity.matchEntire(text)?.destructured ?: return null
        if (sign == "-" || whole.isEmpty() && fraction.isEmpty()) return null
        val exponentPower = if (exponent.isEmpty()) 0 else exponent.toIntOrNull() ?: return null
        // A plain decimal is already in the stored unit; a suffix or exponent is absolute.
        val unitPower = if (!resistance && (unitSuffix != null || prefixPower != null || exponent.isNotEmpty())) 6 else 0
        val places = exponentPower.toLong() + (prefixPower ?: 0) + unitPower - fraction.length
        val digits = (whole + fraction).trimStart('0')
        if (digits.isEmpty()) return 0
        if (places >= 0) {
            if (digits.length + places > 10) return null
            return (digits + "0".repeat(places.toInt())).toIntOrNull()
        }
        val keep = digits.length + places
        if (keep <= 0 || digits.drop(keep.toInt()).any { it != '0' }) return null
        return digits.take(keep.toInt()).toIntOrNull()
    }

    private fun color(name: String, default: String) = AttributeKey.text(name, default) {
        it.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))
    }
}

/** The two active BFH components and eight active I/O-Extra components. */
object ExtraIoComponents {
    private val a = ExtraIoAttributes
    private val labels = listOf(a.Label, a.LabelLocation, a.LabelFont, a.LabelColor, a.LabelVisible)

    fun definitions(): List<ComponentDefinition> = listOf(
        definition(ExtraIoKinds.BinaryToBcd, "Binary_to_BCD_converter", "#BFH-Praktika", listOf(a.BinaryWidth), false, true,
            layout = { component, attrs ->
                val width = attrs[a.BinaryWidth]
                val digits = when (width) { in 4..6 -> 2; in 7..9 -> 3; else -> 4 }
                shape(component, ComponentBounds(-30, -20, digits * 60 - 30, 20),
                    listOf(port("binary", -30, 0, PortDirection.INPUT, width)) +
                        (0 until digits).map { index -> port("digit$index", (digits - index - 1) * 60, -20, PortDirection.OUTPUT, 4) })
            },
            behavior = behavior { context ->
                val digits = ExtraIoLogic.binaryToBcd(input(context, "binary", context.attributes[a.BinaryWidth]), context.attributes[a.BinaryWidth])
                ComponentEvaluation(digits.mapIndexed { index, value -> "digit$index" to value }.toMap())
            }),
        definition(ExtraIoKinds.BcdToSevenSegment, "BCD_to_7_Segment_decoder", "#BFH-Praktika", emptyList(), false, true,
            layout = { component, _ ->
                val points = listOf(20 to 0, 30 to 0, 20 to 60, 10 to 60, 0 to 60, 10 to 0, 0 to 0)
                shape(component, ComponentBounds(-10, -20, 40, 80), points.mapIndexed { index, (x, y) ->
                    port("segment${'a' + index}", x, y, PortDirection.OUTPUT)
                } + port("bcd", 10, 80, PortDirection.INPUT, 4))
            },
            behavior = behavior { context -> ComponentEvaluation(ExtraIoLogic.bcdToSegments(input(context, "bcd", 4))
                .mapIndexed { index, value -> "segment${'a' + index}" to value }.toMap()) }),
        definition(ExtraIoKinds.Switch, "Switch", "#Input/Output-Extra", listOf(a.Facing, a.Width, a.Color) + labels, true,
            layout = { component, attrs -> shape(component, ComponentBounds(-20, -15, 0, 15), listOf(
                port("in", -20, 0, PortDirection.INPUT, attrs[a.Width]), port("out", 0, 0, PortDirection.OUTPUT, attrs[a.Width])), true) },
            behavior = switchBehavior(false)),
        definition(ExtraIoKinds.TwoWaySwitch, "TwoWaySwitch", "#Input/Output-Extra", listOf(a.SwitchMode, a.Facing, a.Width, a.Color) + labels, true,
            layout = ::twoWayLayout, behavior = switchBehavior(true)),
        definition(ExtraIoKinds.Slider, "Slider", "#Input/Output-Extra", listOf(a.Facing, a.SliderWidth, a.SliderDirection, a.Radix, a.Color) + labels, true,
            layout = { component, attrs ->
                val bounds = when (attrs[a.Facing]) {
                    Direction.EAST -> ComponentBounds(-275, -15, 0, 15)
                    Direction.WEST -> ComponentBounds(0, -15, 275, 15)
                    Direction.NORTH -> ComponentBounds(-137, 0, 138, 30)
                    Direction.SOUTH -> ComponentBounds(-137, -30, 138, 0)
                }
                shape(component, bounds, listOf(port("out", 0, 0, PortDirection.OUTPUT, attrs[a.SliderWidth])))
            }, behavior = object : ComponentBehavior {
                override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState =
                    ExtraSliderState().direction(attributes[a.SliderDirection] == "right_to_left")
                override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                    var state = (context.state as? ExtraSliderState ?: ExtraSliderState()).direction(context.attributes[a.SliderDirection] == "right_to_left")
                    context.events.filterIsInstance<ExtraIoEvent.MoveSlider>().forEach { state = state.move(it.position) }
                    return ComponentEvaluation(mapOf("out" to state.value(context.attributes[a.SliderWidth])), state)
                }
            }),
        definition(ExtraIoKinds.TwoPinLed, "TWOPINLED", "#Input/Output-Extra",
            listOf(AttributeKey.direction(default = Direction.WEST), a.OnColor, a.OffColor, a.Bipolar, a.ReverseColor) +
                labels.filterNot { it.name == "labelloc" } + AttributeKey.choice("labelloc", "east", setOf("center", "north", "south", "east", "west")), true,
            layout = { component, attrs ->
                val anode = when (attrs[a.Facing]) { Direction.WEST -> -10 to 0; Direction.EAST -> 10 to 0; Direction.NORTH -> 0 to 10; Direction.SOUTH -> 0 to -10 }
                shape(component, ComponentBounds(-10, -10, 10, 10), listOf(
                    port("anode", anode.first, anode.second, PortDirection.INPUT), port("cathode", -anode.first, -anode.second, PortDirection.INPUT)))
            }, behavior = behavior { context -> ComponentEvaluation(emptyMap(), ExtraIoLogic.twoPinLed(
                bit(context, "anode"), bit(context, "cathode"), context.attributes[a.Bipolar])) }),
        definition(ExtraIoKinds.PlaRom, "PlaRom", "#Input/Output-Extra",
            listOf(a.Inputs, a.ProductTerms, a.Outputs, a.Contents, a.Select) + labels, false, true,
            layout = { component, attrs ->
                PlaRomTable.decode(attrs[a.Inputs], attrs[a.ProductTerms], attrs[a.Outputs], attrs[a.Contents])
                shape(component, ComponentBounds(0, -30, 60, 30), listOf(port("in", 0, 0, PortDirection.INPUT, attrs[a.Inputs]),
                    port("out", 60, 0, PortDirection.OUTPUT, attrs[a.Outputs]), port("select", 30, 30, PortDirection.INPUT)))
            }, behavior = behavior { context ->
                val attrs = context.attributes
                val table = PlaRomTable.decode(attrs[a.Inputs], attrs[a.ProductTerms], attrs[a.Outputs], attrs[a.Contents])
                ComponentEvaluation(mapOf("out" to table.evaluate(input(context, "in", attrs[a.Inputs]), bit(context, "select"), attrs[a.Select] == "high")), delay = 10)
            }),
        definition(ExtraIoKinds.Oscilloscope, "Digital Oscilloscope", "#Input/Output-Extra",
            listOf(a.ScopeInputs, a.ScopeStates, a.ScopeEdges, a.ScopeClock, a.ScopeColor) + labels, false,
            layout = { component, attrs ->
                val count = attrs[a.ScopeInputs]
                val showClock = attrs[a.ScopeClock]
                val top = if (showClock) -42 else -10
                val height = if (showClock) (count + 1) * 30 + 32 else count * 30 + 30
                shape(component, ComponentBounds(0, top, attrs[a.ScopeStates] * 30 + 35, top + height),
                    listOf(port("clock", 0, 0, PortDirection.INPUT)) +
                        (0 until count).map { port("in$it", 0, 30 * (it + 1), PortDirection.INPUT) } +
                        listOf(port("enable", 20, 30 * count + 20, PortDirection.INPUT), port("clear", 30, 30 * count + 20, PortDirection.INPUT)))
            }, behavior = behavior { context ->
                val count = context.attributes[a.ScopeInputs]
                val states = context.attributes[a.ScopeStates]
                var state = (context.state as? ExtraOscilloscopeState ?: ExtraOscilloscopeState(count, states)).resize(count, states)
                if (ExtraIoEvent.ClearTrace in context.events) return@behavior ComponentEvaluation(emptyMap(), ExtraOscilloscopeState(count, states), delay = 0)
                state = state.sample(bit(context, "clock"), (0 until count).map { bit(context, "in$it") }, bit(context, "enable"), bit(context, "clear"))
                ComponentEvaluation(emptyMap(), state, delay = 0)
            }),
        definition(ExtraIoKinds.Ne555, "NE555", "#Input/Output-Extra", listOf(a.TimerMode, a.Resistance, a.Capacitance) + labels, false,
            layout = { component, attrs -> shape(component, ComponentBounds(-80, -60, 80, 60),
                listOf(port("out", 80, 0, PortDirection.OUTPUT)) + if (attrs[a.TimerMode] == "monostable")
                    listOf(port("trigger", -80, 10, PortDirection.INPUT)) else emptyList()) },
            behavior = behavior { context ->
                val state = (context.state as? Ne555State ?: Ne555State()).advance(context.elapsedTimeMillis,
                    context.autoPropagating, context.attributes[a.TimerMode] == "monostable", bit(context, "trigger"),
                    context.attributes[a.Resistance], context.attributes[a.Capacitance])
                ComponentEvaluation(mapOf("out" to LogicVector.fromBit(if (state.outputHigh) LogicValue.HIGH else LogicValue.LOW)),
                    state, delay = 0, nextWakeupMillis = if (context.autoPropagating) context.elapsedTimeMillis + 10 else null)
            }),
        definition(ExtraIoKinds.Buzzer, "Buzzer", "#Input/Output-Extra",
            listOf(AttributeKey.direction(default = Direction.WEST), a.SelectLocation, a.FrequencyUnit, a.VolumeWidth,
                a.Waveform, a.Channels, a.SmoothLevel, a.SmoothWidth) + labels, true,
            layout = ::buzzerLayout, behavior = behavior { context ->
                ComponentEvaluation(emptyMap(), BuzzerTone.fromInputs(input(context, "frequency", 14), bit(context, "enable"),
                    input(context, "volume", context.attributes[a.VolumeWidth]), input(context, "duty", 8),
                    context.attributes[a.FrequencyUnit] == "dHz", BuzzerWaveform.valueOf(context.attributes[a.Waveform]),
                    context.attributes[a.Channels], context.attributes[a.SmoothLevel], context.attributes[a.SmoothWidth]), delay = 0)
            }),
    )

    private fun switchBehavior(twoWay: Boolean) = object : ComponentBehavior {
        override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState = ExtraSwitchState()
        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
            var state = context.state as? ExtraSwitchState ?: ExtraSwitchState()
            context.events.forEach { event -> state = when (event) {
                ExtraIoEvent.Toggle -> state.copy(closed = !state.closed)
                is ExtraIoEvent.SetSwitch -> state.copy(closed = event.closed)
                else -> state
            } }
            val width = context.attributes[a.Width]
            val outputs = when {
                !twoWay -> mapOf("out" to ExtraIoLogic.switch(input(context, "in", width), state.closed))
                context.attributes[a.SwitchMode] == "1in2out" -> ExtraIoLogic.twoWaySwitch(input(context, "common", width), state.closed)
                    .mapIndexed { index, value -> "branch$index" to value }.toMap()
                else -> mapOf("common" to ExtraIoLogic.twoWaySelect(input(context, "branch0", width), input(context, "branch1", width), state.closed))
            }
            return ComponentEvaluation(outputs, state)
        }
    }

    private fun twoWayLayout(component: CircuitComponent, attrs: AttributeValues): ComponentLayout {
        val oneToTwo = attrs[a.SwitchMode] == "1in2out"
        val commonX = if (oneToTwo) -20 else 20
        val pairX = -commonX
        val common = if (oneToTwo) PortDirection.INPUT else PortDirection.OUTPUT
        val pair = if (oneToTwo) PortDirection.OUTPUT else PortDirection.INPUT
        val points = when (attrs[a.Facing]) {
            Direction.EAST -> listOf(commonX to 0, pairX to -10, pairX to 10)
            Direction.WEST -> listOf(-commonX to 0, -pairX to -10, -pairX to 10)
            Direction.SOUTH -> listOf(0 to -commonX, -10 to -pairX, 10 to -pairX)
            Direction.NORTH -> listOf(0 to commonX, -10 to pairX, 10 to pairX)
        }
        val bounds = rotatedBounds(component, ComponentBounds(-20, -15, 20, 15))
        return shape(component, bounds, points.mapIndexed { index, (x, y) ->
            port(if (index == 0) "common" else "branch${index - 1}", x, y, if (index == 0) common else pair, attrs[a.Width])
        })
    }

    private fun buzzerLayout(component: CircuitComponent, attrs: AttributeValues): ComponentLayout {
        val facing = attrs[a.Facing]
        val horizontal = facing == Direction.EAST || facing == Direction.WEST
        val bounds = when (facing) {
            Direction.EAST -> ComponentBounds(-40, -20, 0, 20)
            Direction.WEST -> ComponentBounds(0, -20, 40, 20)
            Direction.NORTH -> ComponentBounds(-20, 0, 20, 40)
            Direction.SOUTH -> ComponentBounds(-20, -40, 20, 0)
        }
        val selectBottom = attrs[a.SelectLocation] == "bl"
        val dutyX = if (horizontal) if (facing == Direction.EAST) -20 else 20 else if (selectBottom) -20 else 20
        val dutyY = if (horizontal) if (selectBottom) 20 else -20 else if (facing == Direction.SOUTH) -20 else 20
        return shape(component, bounds, listOf(
            port("frequency", if (horizontal) 0 else -10, if (horizontal) -10 else 0, PortDirection.INPUT, 14),
            port("enable", 0, 0, PortDirection.INPUT),
            port("volume", if (horizontal) 0 else 10, if (horizontal) 10 else 0, PortDirection.INPUT, attrs[a.VolumeWidth]),
            port("duty", dutyX, dutyY, PortDirection.INPUT, 8),
        ))
    }

    private fun definition(kind: ComponentKind, fileName: String, library: String, keys: List<AttributeKey<*>>,
                           rotates: Boolean, combinational: Boolean = false,
                           layout: (CircuitComponent, AttributeValues) -> ComponentLayout,
                           behavior: ComponentBehavior): ComponentDefinition {
        val schema = AttributeSchema(keys)
        return ComponentDefinition(kind, fileName, library, schema.defaults, schema, layout, behavior,
            supportsRotation = rotates, isCombinational = combinational)
    }

    private fun behavior(evaluate: (ComponentEvaluationContext) -> ComponentEvaluation) = object : ComponentBehavior {
        override fun evaluate(context: ComponentEvaluationContext) = evaluate.invoke(context)
    }

    private fun input(context: ComponentEvaluationContext, name: String, width: Int) = context.inputs[name] ?: LogicVector.unknown(width)
    private fun bit(context: ComponentEvaluationContext, name: String) = input(context, name, 1).bit(0)
    private fun port(name: String, x: Int, y: Int, direction: PortDirection, width: Int = 1) =
        ComponentTerminal(name, GridPoint(x, y), direction, width)

    private fun shape(component: CircuitComponent, bounds: ComponentBounds, ports: List<ComponentTerminal>, rotated: Boolean = false): ComponentLayout {
        fun position(point: GridPoint): GridPoint {
            val local = if (!rotated) point else rotate(point, component.facing)
            return GridPoint(component.position.x + local.x, component.position.y + local.y)
        }
        val box = if (rotated) rotatedBounds(component, bounds) else bounds
        return ComponentLayout(ports.map { it.copy(position = position(it.position)) }, ComponentBounds(
            component.position.x + box.left, component.position.y + box.top,
            component.position.x + box.right, component.position.y + box.bottom))
    }

    private fun rotatedBounds(component: CircuitComponent, bounds: ComponentBounds): ComponentBounds {
        val points = listOf(GridPoint(bounds.left, bounds.top), GridPoint(bounds.right, bounds.bottom)).map { rotate(it, component.facing) }
        return ComponentBounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }

    private fun rotate(point: GridPoint, facing: String): GridPoint = when (facing) {
        "west" -> GridPoint(-point.x, -point.y)
        "north" -> GridPoint(point.y, -point.x)
        "south" -> GridPoint(-point.y, point.x)
        else -> point
    }
}
