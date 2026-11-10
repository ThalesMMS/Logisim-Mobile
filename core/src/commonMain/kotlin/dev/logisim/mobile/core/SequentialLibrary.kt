package dev.logisim.mobile.core

object FlipFlopPoked : ComponentEvent
data class SequentialHexEntered(val digits: List<Int>) : ComponentEvent
data class ShiftRegisterStagePoked(val stage: Int, val digit: Int? = null) : ComponentEvent

/** The eight non-addressed storage devices registered by the desktop Memory library. */
object SequentialLibrary {
    val TFlipFlop = ComponentKind("T_FLIP_FLOP")
    val JKFlipFlop = ComponentKind("JK_FLIP_FLOP")
    val SRFlipFlop = ComponentKind("SR_FLIP_FLOP")
    val ShiftRegister = ComponentKind("SHIFT_REGISTER")
    val Random = ComponentKind("RANDOM")

    private enum class Device(val kind: ComponentKind, val fileName: String) {
        D(ComponentKind.D_FLIP_FLOP, "D Flip-Flop"),
        T(TFlipFlop, "T Flip-Flop"),
        JK(JKFlipFlop, "J-K Flip-Flop"),
        SR(SRFlipFlop, "S-R Flip-Flop"),
        REGISTER(ComponentKind.REGISTER, "Register"),
        COUNTER(ComponentKind.COUNTER, "Counter"),
        SHIFT(ShiftRegister, "Shift Register"),
        RANDOM(Random, "Random"),
    }

    private val Width = AttributeKey.int("width", 8, 1..64)
    private val ShiftWidth = AttributeKey.int("width", 1, 1..64)
    private val Length = AttributeKey.int("length", 8, 1..64)
    private val Parallel = AttributeKey.boolean("parallel", true)
    private val Appearance = AttributeKey.choice("appearance", "classic", setOf("classic", "evolution", "logisim_evolution", "fpga"))
    private val Trigger = AttributeKey.choice("trigger", "rising", setOf("rising", "falling", "high", "low"))
    private val EdgeTrigger = AttributeKey.choice("trigger", "rising", setOf("rising", "falling"))
    private val Seed = AttributeKey.int("seed", 0)
    private val Goal = AttributeKey.choice("ongoal", "wrap", setOf("wrap", "stay", "continue", "load"))
    private val Maximum = AttributeKey("max", 255uL, ::parseUnsigned, { "0x${it.toString(16)}" })

    val definitions: List<ComponentDefinition> = Device.entries.map { device ->
        val keys = buildList {
            add(Appearance)
            add(CommonAttributes.Label)
            add(if (device in setOf(Device.D, Device.SR, Device.REGISTER)) Trigger else EdgeTrigger)
            when (device) {
                Device.REGISTER, Device.COUNTER, Device.RANDOM -> add(Width)
                Device.SHIFT -> { add(ShiftWidth); add(Length); add(Parallel) }
                else -> Unit
            }
            if (device == Device.COUNTER) { add(Maximum); add(Goal) }
            if (device == Device.RANDOM) add(Seed)
        }
        val schema = AttributeSchema(keys)
        ComponentDefinition(
            kind = device.kind,
            fileName = device.fileName,
            library = "#Memory",
            defaultAttributes = schema.defaults - "max",
            schema = schema,
            layout = { component, attributes -> layout(device, component, attributes) },
            behavior = Behavior(device),
            supportsRotation = false,
            reconfigureAttributes = if (device == Device.COUNTER) ::reconfigureCounter else { component, updates -> component.attributes + updates },
        )
    }

    private data class State(
        val value: StoredValue? = null,
        val stages: StoredStages? = null,
        val random: RandomSequence? = null,
        val originalRandom: OriginalRandomSequence? = null,
        val counterCarry: LogicValue = LogicValue.LOW,
        val counterInputs: Map<String, LogicVector> = emptyMap(),
        val counterAttributes: Map<String, String> = emptyMap(),
    ) : ComponentState

    private class Behavior(private val device: Device) : ComponentBehavior {
        override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
            val attributes = context.attributes
            val width = if (device in setOf(Device.D, Device.T, Device.JK, Device.SR)) 1 else attributes[Width]
            val unknownStartup = context.options["memoryStartupUnknown"] == "true"
            fun initial() = if (unknownStartup) LogicVector.unknown(width) else LogicVector.low(width)
            val state = context.state as? State ?: State()
            fun input(name: String, bits: Int = 1): LogicVector =
                context.inputs[name]?.resize(bits) ?: LogicVector.unknown(bits)
            fun bit(name: String) = input(name).bit(0)
            val clock = bit("clock")
            val trigger = attributes.string("trigger") ?: "rising"
            val oldValue = state.value ?: StoredValue(initial())
            return when (device) {
                Device.D, Device.T, Device.JK, Device.SR -> {
                    val names = when (device) {
                        Device.D -> listOf("d")
                        Device.T -> listOf("t")
                        Device.JK -> listOf("j", "k")
                        else -> listOf("s", "r")
                    }
                    val operation = when (device) {
                        Device.D -> FlipFlopOperation.D
                        Device.T -> FlipFlopOperation.T
                        Device.JK -> FlipFlopOperation.JK
                        else -> FlipFlopOperation.SR
                    }
                    val poked = context.events.fold(oldValue) { value, event ->
                        if (event == FlipFlopPoked) value.copy(value = value.value.inverted()) else value
                    }
                    val next = SequentialLogic.flipFlop(poked, operation, names.map(::bit), clock,
                        bit("clear"), bit("preset"), trigger, bit("enable"))
                    ComponentEvaluation(mapOf("q" to next.value, "nq" to complement(next.value)), State(value = next), 5)
                }
                Device.REGISTER -> {
                    val next = SequentialLogic.register(appendHex(oldValue, width, context.events), input("data", width), clock,
                        bit("clear"), bit("enable"), trigger)
                    ComponentEvaluation(mapOf("q" to next.value), State(value = next), 8)
                }
                Device.COUNTER -> {
                    // Carry is computed before output truncation; an output event must not sample it again.
                    val edited = appendHex(oldValue, width, context.events)
                    if (edited == oldValue && state.value != null &&
                        state.counterInputs == context.inputs && state.counterAttributes == context.component.attributes) {
                        return ComponentEvaluation(mapOf("q" to state.value.value, "carry" to LogicVector.fromBit(state.counterCarry)), state, 8)
                    }
                    val maximum = counterMaximum(context.component.attributes, width)
                    val transition = SequentialLogic.counter(edited, input("data", width), clock,
                        bit("clear"), bit("load"), bit("enable"), bit("up"), maximum, attributes[Goal], trigger,
                        original = context.component.isOriginalSequential)
                    val next = transition.state
                    val diagnostics = if (next.value.isError && !oldValue.value.isFullyDefined)
                        listOf("Counter captured an undefined stored value; the result is an error.") else emptyList()
                    ComponentEvaluation(mapOf("q" to next.value, "carry" to LogicVector.fromBit(transition.carry)),
                        State(value = next, counterCarry = transition.carry, counterInputs = context.inputs.toMap(),
                            counterAttributes = context.component.attributes.toMap()), 8, diagnostics = diagnostics)
                }
                Device.SHIFT -> {
                    val length = attributes[Length]
                    val old = state.stages?.let { saved ->
                        StoredStages(List(length) { index -> saved.values.getOrNull(index)?.resize(width) ?: LogicVector.low(width) }, saved.clock)
                    } ?: StoredStages(List(length) { initial() })
                    val parallel = if (attributes[Parallel]) List(length) { input("parallel$it", width) } else null
                    val edited = if (parallel != null && width <= 4) editStages(old, width, context.events) else old
                    val next = SequentialLogic.shiftRegister(edited, input("serial", width), parallel, clock,
                        bit("clear"), bit("shift"), bit("load"), trigger)
                    val outputs = buildMap {
                        put("q", next.values.first())
                        if (parallel != null) {
                            val count = if (isClassic(attributes)) length else length - 1
                            repeat(count) { put("stage$it", next.values[length - 1 - it]) }
                        }
                    }
                    ComponentEvaluation(outputs, State(stages = next), 4)
                }
                Device.RANDOM -> {
                    val seed = attributes[Seed]
                    val seedOverride = context.options["randomSeed"]?.let(::parseUnsigned)
                    if (context.component.isOriginalSequential) {
                        fun restart(previous: OriginalRandomSequence? = null) = if (seed != 0) {
                            OriginalRandomSequence(seed)
                        } else {
                            OriginalRandomSequence.unseeded(seedOverride, previous?.initialSeed)
                        }
                        val previous = state.originalRandom
                        val old = previous ?: restart()
                        val resetHeld = bit("reset") == LogicValue.HIGH
                        val next = when {
                            resetHeld -> if (seed != 0 || previous != null && !old.resetHeld) restart(old) else old
                            SequentialLogic.triggered(old.clock, clock, trigger) && bit("enable") != LogicValue.LOW -> old.next()
                            else -> old
                        }.copy(clock = clock, resetHeld = resetHeld)
                        return ComponentEvaluation(mapOf("q" to LogicVector.of(width, next.value.toULong())),
                            State(originalRandom = next), 4)
                    }
                    val explicitSeed = seed.toUInt().toULong()
                    val implicitSeed = seedOverride ?: context.elapsedTimeMillis.toULong()
                    val old = state.random ?: RandomSequence.seeded(if (explicitSeed != 0uL) explicitSeed else implicitSeed)
                    val next = SequentialLogic.random(old, explicitSeed, implicitSeed, clock,
                        bit("reset"), bit("enable"), trigger)
                    ComponentEvaluation(mapOf("q" to LogicVector.of(width, next.value)), State(random = next), 4)
                }
            }
        }
    }

    private fun appendHex(stored: StoredValue, width: Int, events: List<ComponentEvent>): StoredValue {
        val digits = events.filterIsInstance<SequentialHexEntered>().flatMap { it.digits }.filter { it in 0..15 }
        if (digits.isEmpty()) return stored
        val number = digits.fold(stored.value.toULongOrNull() ?: 0uL) { value, digit -> (value shl 4) or digit.toULong() }
        return stored.copy(value = LogicVector.of(width, number))
    }

    private fun editStages(stored: StoredStages, width: Int, events: List<ComponentEvent>): StoredStages {
        val edits = events.filterIsInstance<ShiftRegisterStagePoked>()
        if (edits.isEmpty()) return stored
        val values = stored.values.toMutableList()
        for ((stage, digit) in edits) {
            if (stage !in values.indices) continue
            val index = values.lastIndex - stage
            if (digit == null && width == 1) {
                values[index] = LogicVector.fromBit(if (values[index].bit(0) == LogicValue.LOW) LogicValue.HIGH else LogicValue.LOW)
            } else if (digit != null && digit in 0 until (1 shl width)) {
                values[index] = LogicVector.of(width, digit.toULong())
            }
        }
        return stored.copy(values = values)
    }

    private fun layout(device: Device, component: CircuitComponent, attributes: AttributeValues): ComponentLayout {
        val classic = isClassic(attributes)
        val original = classic && component.isOriginalSequential
        val width = if (device in setOf(Device.D, Device.T, Device.JK, Device.SR)) 1 else attributes[Width]
        val ports = mutableListOf<ComponentTerminal>()
        fun port(name: String, x: Int, y: Int, direction: PortDirection = PortDirection.INPUT, bits: Int = 1) {
            ports += ComponentTerminal(name, GridPoint(component.position.x + x, component.position.y + y), direction, bits)
        }
        val bounds = when (device) {
            Device.D, Device.T, Device.JK, Device.SR -> {
                val inputs = when (device) {
                    Device.D -> listOf("d")
                    Device.T -> listOf("t")
                    Device.JK -> listOf("j", "k")
                    else -> listOf("s", "r")
                }
                if (classic) {
                    if (inputs.size == 1) { port(inputs[0], -40, 20); port("clock", -40, 0) }
                    else { port(inputs[0], -40, 0); port(inputs[1], -40, 20); port("clock", -40, 10) }
                    port("q", 0, 0, PortDirection.OUTPUT); port("nq", 0, 20, PortDirection.OUTPUT)
                    port("clear", -10, 30); port("preset", -30, 30)
                    if (original) port("enable", -20, 30)
                    ComponentBounds(-40, -10, 0, 30)
                } else {
                    inputs.forEachIndexed { index, name -> port(name, -10, 10 + 20 * index) }
                    port("clock", -10, 50); port("q", 50, 10, PortDirection.OUTPUT); port("nq", 50, 50, PortDirection.OUTPUT)
                    port("clear", 20, 60); port("preset", 20, 0)
                    ComponentBounds(-10, 0, 50, 60)
                }
            }
            Device.REGISTER, Device.COUNTER -> {
                val counter = device == Device.COUNTER
                if (classic) {
                    port("data", -30, 0, bits = width); port("clock", -20, 20)
                    if (counter) port("load", -30, -10)
                    if (!counter) port("enable", -30, 10)
                    port("clear", -10, 20)
                    if (counter) {
                        port("enable", -30, 10)
                        if (!original) port("up", -20, -20)
                    }
                    port("q", 0, 0, PortDirection.OUTPUT, width)
                    if (counter) port("carry", 0, 10, PortDirection.OUTPUT)
                    ComponentBounds(-30, -20, 0, 20)
                } else if (!counter) {
                    port("data", 0, 30, bits = width); port("clock", 0, 70); port("enable", 0, 50); port("clear", 30, 90)
                    port("q", 60, 30, PortDirection.OUTPUT, width)
                    ComponentBounds(0, 0, 60, 90)
                } else {
                    val right = 190 + ((width - 8) / 5) * 10
                    val dataY = if (width == 1) 120 else 110
                    port("data", 0, dataY, bits = width); port("clock", 0, 80); port("load", 0, 30)
                    port("clear", 0, 20); port("enable", 0, 70); port("up", 0, 50)
                    port("q", right, dataY, PortDirection.OUTPUT, width); port("carry", right, 50, PortDirection.OUTPUT)
                    val bitsPerRow = if (width <= 8) 1 else if (width <= 32) 4 else 8
                    ComponentBounds(0, 0, right, 110 + 20 * ((width + bitsPerRow - 1) / bitsPerRow))
                }
            }
            Device.SHIFT -> {
                val length = attributes[Length]
                val parallel = attributes[Parallel]
                if (classic) {
                    val right = if (parallel) 20 + 10 * length else 30
                    port("serial", 0, 0, bits = width); port("shift", 0, -10); port("clock", 0, 10); port("clear", 10, 20)
                    port("q", right, 0, PortDirection.OUTPUT, width)
                    if (parallel) {
                        port("load", 10, -20)
                        repeat(length) { index ->
                            port("parallel$index", 20 + 10 * index, -20, bits = width)
                            port("stage$index", 20 + 10 * index, 20, PortDirection.OUTPUT, width)
                        }
                    }
                    ComponentBounds(0, -20, right, 20)
                } else {
                    port("serial", 0, 80, bits = width); port("shift", 0, 40); port("clock", 0, 50); port("clear", 0, 20)
                    port("q", 120, 70 + 20 * length, PortDirection.OUTPUT, width)
                    if (parallel) {
                        port("load", 0, 30)
                        repeat(length) { index ->
                            port("parallel$index", 0, 90 + 20 * index, bits = width)
                            if (index < length - 1) port("stage$index", 120, 90 + 20 * index, PortDirection.OUTPUT, width)
                        }
                    }
                    ComponentBounds(0, 0, 120, 80 + 20 * length)
                }
            }
            Device.RANDOM -> if (original) {
                port("q", 0, 0, PortDirection.OUTPUT, width); port("clock", -30, -10)
                port("enable", -30, 10); port("reset", -20, 20)
                ComponentBounds(-30, -20, 0, 20)
            } else if (classic) {
                port("q", 40, 20, PortDirection.OUTPUT, width); port("clock", 10, 40)
                port("enable", 0, 30); port("reset", 30, 40)
                ComponentBounds(0, 0, 40, 40)
            } else {
                port("q", 80, 80, PortDirection.OUTPUT, width); port("clock", 0, 50)
                port("enable", 0, 40); port("reset", 0, 30)
                ComponentBounds(0, 0, 80, 90)
            }
        }
        return ComponentLayout(ports, ComponentBounds(bounds.left + component.position.x,
            bounds.top + component.position.y, bounds.right + component.position.x, bounds.bottom + component.position.y))
    }

    private fun isClassic(attributes: AttributeValues) = attributes[Appearance] == "classic"
    private fun mask(width: Int): ULong = if (width == 64) ULong.MAX_VALUE else (1uL shl width) - 1uL

    /** Evolution loads a missing maximum as its 0xFF attribute default, limited to the counter width. */
    internal fun counterMaximum(attributes: Map<String, String>, width: Int): ULong =
        (attributes["max"]?.let(::parseUnsigned) ?: 0xFFuL) and mask(width)

    // Evolution's CounterAttributes widens the maximum to the full mask and masks it when the width shrinks.
    private fun reconfigureCounter(component: CircuitComponent, updates: Map<String, String>): Map<String, String> {
        val merged = component.attributes + updates
        val newWidth = updates["width"]?.toIntOrNull() ?: return merged
        if ("max" in updates) return merged
        val oldWidth = component.attributes["width"]?.toIntOrNull() ?: 8
        if (newWidth == oldWidth || newWidth !in 1..64) return merged
        val maximum = if (newWidth < oldWidth) counterMaximum(component.attributes, oldWidth) and mask(newWidth) else mask(newWidth)
        return merged + ("max" to "0x${maximum.toString(16)}")
    }
    private fun complement(value: LogicVector) = value.inverted()

    private fun parseUnsigned(raw: String): ULong? {
        val value = raw.trim().lowercase()
        if (value.startsWith('-')) return parseUnsigned(value.drop(1))?.let { 0uL - it }
        return when {
            value.startsWith("0x") -> value.drop(2).toULongOrNull(16)
            value.startsWith("0b") -> value.drop(2).toULongOrNull(2)
            value.length > 1 && value.startsWith('0') -> value.drop(1).toULongOrNull(8)
            else -> value.toULongOrNull()
        }
    }
}
