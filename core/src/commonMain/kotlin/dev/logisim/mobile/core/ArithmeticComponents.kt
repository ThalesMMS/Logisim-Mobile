package dev.logisim.mobile.core

internal const val ORIGINAL_DIVIDER_ATTRIBUTE = "logisim-mobile-original-divider"
internal const val ORIGINAL_MULTIPLIER_ATTRIBUTE = "logisim-mobile-original-multiplier"

internal fun CircuitComponent.multiplierMode(width: Int): String {
    val mode = attributes["mode"] ?: "unsigned"
    // Logisim 2.7.1 widens Java int values to long before multiplying and adding the carry.
    return if (width == 32 && mode == "unsigned" && attributes[ORIGINAL_MULTIPLIER_ATTRIBUTE] == "true") {
        "twosComplement"
    } else mode
}

/** The thirteen active desktop Arithmetic entries, with one schema and layout per entry. */
object ArithmeticComponents {
    fun definitions(): List<ComponentDefinition> = ArithmeticOperation.entries.map(::definition)

    private fun definition(operation: ArithmeticOperation): ComponentDefinition {
        val width = AttributeKey.int("width", if (operation == ArithmeticOperation.EXPONENTIATOR) 32 else 8, 1..64)
        val mode = AttributeKey.choice("mode", if (operation == ArithmeticOperation.COMPARATOR) "twosComplement" else "unsigned",
            if (operation == ArithmeticOperation.MULTIPLIER) setOf("unsigned", "twosComplement", "signedUnsigned")
            else setOf("unsigned", "twosComplement"))
        val count = AttributeKey.int("inputs", 1, 1..64)
        val shift = AttributeKey.choice("shift", "ll", setOf("ll", "lr", "ar", "rl", "rr"))
        val finder = AttributeKey.choice("type", "low1", setOf("low1", "high1", "low0", "high0"))
        val modes = setOf(ArithmeticOperation.MULTIPLIER, ArithmeticOperation.DIVIDER,
            ArithmeticOperation.EXPONENTIATOR, ArithmeticOperation.COMPARATOR, ArithmeticOperation.MIN_MAX)
        val schema = AttributeSchema(buildList {
            add(width)
            if (operation in modes) add(mode)
            if (operation == ArithmeticOperation.BIT_ADDER) add(count)
            if (operation == ArithmeticOperation.SHIFTER) add(shift)
            if (operation == ArithmeticOperation.BIT_FINDER) add(finder)
        })
        val layout: (CircuitComponent, AttributeValues) -> ComponentLayout = { component, attributes ->
            layout(operation, component.position, attributes[width],
                if (operation == ArithmeticOperation.BIT_ADDER) attributes[count] else 1)
        }
        return ComponentDefinition(
            kind = ComponentKind(operation.name), fileName = operation.fileName, library = "#Arithmetic",
            defaultAttributes = schema.defaults, schema = schema, layout = layout, supportsRotation = false,
            isCombinational = true,
            defaultsForVersion = { version ->
                if (!CircFormatProfile.parse(version).originalLogisim) emptyMap()
                else when (operation) {
                    ArithmeticOperation.DIVIDER -> mapOf(ORIGINAL_DIVIDER_ATTRIBUTE to "true")
                    ArithmeticOperation.MULTIPLIER -> mapOf(ORIGINAL_MULTIPLIER_ATTRIBUTE to "true")
                    else -> emptyMap()
                }
            },
            behavior = object : ComponentBehavior {
                override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                    val attributes = context.attributes
                    val w = attributes[width]
                    val inputCount = if (operation == ArithmeticOperation.BIT_ADDER) attributes[count] else 1
                    val geometry = layout(context.component, attributes)
                    val values = geometry.inputs.map { context.inputs[it.name] ?: LogicVector.unknown(it.width) }
                    val selectedMode = when {
                        operation == ArithmeticOperation.MULTIPLIER -> context.component.multiplierMode(w)
                        operation in modes -> attributes[mode]
                        else -> "unsigned"
                    }
                    val results = if (operation == ArithmeticOperation.DIVIDER && w == 32 && selectedMode == "unsigned" &&
                        attributes.raw[ORIGINAL_DIVIDER_ATTRIBUTE] == "true") {
                        ArithmeticLogic.divideOriginal32(values)
                    } else {
                        ArithmeticLogic.evaluate(operation, w, values, mode = selectedMode,
                            shift = if (operation == ArithmeticOperation.SHIFTER) attributes[shift] else "ll",
                            finder = if (operation == ArithmeticOperation.BIT_FINDER) attributes[finder] else "low1")
                    }
                    return ComponentEvaluation(geometry.outputs.mapIndexed { index, port -> port.name to results[index] }.toMap(),
                        state = context.state, delay = ArithmeticLogic.delay(operation, w, inputCount))
                }
            },
        )
    }

    private fun layout(operation: ArithmeticOperation, origin: GridPoint, width: Int, inputCount: Int): ComponentLayout {
        fun input(name: String, x: Int, y: Int, bits: Int = width) =
            ComponentTerminal(name, GridPoint(origin.x + x, origin.y + y), PortDirection.INPUT, bits)
        fun output(name: String, x: Int, y: Int, bits: Int = width) =
            ComponentTerminal(name, GridPoint(origin.x + x, origin.y + y), PortDirection.OUTPUT, bits)
        val binary = listOf(input("a", -40, -10), input("b", -40, 10))
        val ports = when (operation) {
            ArithmeticOperation.ADDER -> binary + listOf(output("result", 0, 0), input("carryIn", -20, -20, 1), output("carryOut", -20, 20, 1))
            ArithmeticOperation.SUBTRACTOR -> binary + listOf(output("result", 0, 0), input("borrowIn", -20, -20, 1), output("borrowOut", -20, 20, 1))
            ArithmeticOperation.MULTIPLIER -> binary + listOf(output("low", 0, 0), input("carryIn", -20, -20), output("high", -20, 20))
            ArithmeticOperation.DIVIDER -> binary + listOf(output("quotient", 0, 0), input("upper", -20, -20), output("remainder", -20, 20))
            ArithmeticOperation.NEGATOR -> listOf(input("data", -40, 0), output("result", 0, 0))
            ArithmeticOperation.EXPONENTIATOR -> listOf(input("base", -40, -10), input("exponent", -40, 10), output("low", 0, 0), output("high", -20, 20))
            ArithmeticOperation.SQUARE_ROOT -> listOf(input("data", -40, 0), output("root", 0, 0), input("upper", -20, -20), output("remainder", -20, 20))
            ArithmeticOperation.ABSOLUTE -> listOf(input("data", -40, 0), output("result", 0, 0), output("overflow", -20, 20, 1))
            ArithmeticOperation.COMPARATOR -> binary + listOf(output("greater", 0, -10, 1), output("equal", 0, 0, 1), output("less", 0, 10, 1))
            ArithmeticOperation.MIN_MAX -> binary + listOf(output("minimum", 0, -10), output("maximum", 0, 10))
            ArithmeticOperation.SHIFTER -> listOf(input("data", -40, -10), input("distance", -40, 10, ArithmeticLogic.indexWidth(width)), output("result", 0, 0))
            ArithmeticOperation.BIT_FINDER -> listOf(output("present", -20, 20, 1), output("index", 0, 0, ArithmeticLogic.indexWidth(width)), input("data", -40, 0))
            ArithmeticOperation.BIT_ADDER -> {
                val firstY = when (inputCount) { 1 -> 0; 2, 3 -> -10; else -> -10 * ((inputCount - 1) / 2) }
                val spacing = if (inputCount == 2) 20 else 10
                listOf(output("count", 0, 0, ArithmeticLogic.countWidth(width * inputCount))) +
                    (0 until inputCount).map { input("data$it", -40, firstY + it * spacing) }
            }
        }
        val top = if (operation == ArithmeticOperation.BIT_ADDER && inputCount >= 4) -10 * ((inputCount - 1) / 2) - 5 else -20
        val height = if (operation == ArithmeticOperation.BIT_ADDER) maxOf(40, inputCount * 10) else 40
        return ComponentLayout(ports, ComponentBounds(origin.x - 40, origin.y + top, origin.x, origin.y + top + height))
    }
}
