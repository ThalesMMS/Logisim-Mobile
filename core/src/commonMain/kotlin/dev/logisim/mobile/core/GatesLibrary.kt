package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*

internal const val ORIGINAL_CONTROLLED_GATE_ATTRIBUTE = "logisim-mobile-original-controlled-gate"

/** The thirteen file identities registered by the desktop Gates library. */
object GatesLibrary {
    val OddParity = ComponentKind("ODD_PARITY")
    val EvenParity = ComponentKind("EVEN_PARITY")
    val Pla = ComponentKind("PLA")

    private val width = CommonAttributes.Width
    private val facing = CommonAttributes.Facing
    private val inputs = AttributeKey.int("inputs", 2, 2..64)
    private val size = AttributeKey("size", 50, String::toIntOrNull, Int::toString) { it in setOf(30, 50, 70) }
    private val unarySize = AttributeKey("size", 30, String::toIntOrNull, Int::toString) { it in setOf(20, 30) }
    private val output = AttributeKey.choice("out", "01", setOf("01", "0Z", "Z1"))
    private val xor = AttributeKey.choice("xor", "1", setOf("1", "odd"))
    private val control = AttributeKey.choice("control", "right", setOf("left", "right"))
    private val inputWidth = AttributeKey.int("in_width", 2, 1..64)
    private val outputWidth = AttributeKey.int("out_width", 2, 1..64)
    private val table = AttributeKey.text("table")
    private val label = CommonAttributes.Label
    private val font = AttributeKey.text("labelfont", "SansSerif bold 16")
    private val labelLocation = AttributeKey.choice("labelloc", "north", setOf("north", "south", "east", "west", "center"))
    private val negations = List(64) { AttributeKey.boolean("negate$it", false) }

    fun definitions(): List<ComponentDefinition> = listOf(
        unary(ComponentKind.NOT, "NOT Gate", GateOperation.NOT, sized = true),
        unary(ComponentKind.BUFFER, "Buffer", GateOperation.BUFFER),
        gate(ComponentKind.AND, "AND Gate", GateOperation.AND),
        gate(ComponentKind.OR, "OR Gate", GateOperation.OR),
        gate(ComponentKind.NAND, "NAND Gate", GateOperation.NAND),
        gate(ComponentKind.NOR, "NOR Gate", GateOperation.NOR),
        gate(ComponentKind.XOR, "XOR Gate", GateOperation.XOR),
        gate(ComponentKind.XNOR, "XNOR Gate", GateOperation.XNOR),
        gate(OddParity, "Odd Parity", GateOperation.ODD_PARITY),
        gate(EvenParity, "Even Parity", GateOperation.EVEN_PARITY),
        unary(ComponentKind.CONTROLLED_BUFFER, "Controlled Buffer", GateOperation.CONTROLLED_BUFFER, controlled = true),
        unary(ComponentKind.CONTROLLED_INVERTER, "Controlled Inverter", GateOperation.CONTROLLED_INVERTER, sized = true, controlled = true),
        ComponentDefinition(Pla, "PLA", "#Gates",
            schema = AttributeSchema(listOf(facing, inputWidth, outputWidth, table, label, labelLocation, font)),
            layout = ::plaLayout,
            behavior = object : ComponentBehavior {
                override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                    val program = plaProgram(context.attributes)
                    val value = program.evaluate(context.inputs["in"] ?: LogicVector.unknown(program.inputWidth))
                    return ComponentEvaluation(mapOf("out" to value), context.state, delay = 1,
                        diagnostics = program.diagnostics, notices = program.notices)
                }
            }, isCombinational = true, reconfigureAttributes = ::reconfigurePla),
    )

    /** Evolution's PlaTable parse defines both widths from the rows; a table without rows reads as 2x2. */
    internal fun normalizePlaFileAttributes(attributes: MutableMap<String, String>) {
        val program = PlaProgram.parse(attributes["table"].orEmpty())
        val (inputs, outputs) = if (program.rows.isEmpty()) 2 to 2 else program.inputWidth to program.outputWidth
        attributes["in_width"] = inputs.toString()
        attributes["out_width"] = outputs.toString()
    }

    // Like PlaTable.setInSize/setOutSize, a width edit resizes the stored rows so the table keeps the widths.
    private fun reconfigurePla(component: CircuitComponent, updates: Map<String, String>): Map<String, String> {
        val merged = component.attributes + updates
        if ("table" in updates || ("in_width" !in updates && "out_width" !in updates)) return merged
        val program = PlaProgram.parse(merged["table"].orEmpty())
        val resized = program.resized(merged["in_width"]?.toIntOrNull() ?: program.inputWidth,
            merged["out_width"]?.toIntOrNull() ?: program.outputWidth)
        return merged + ("table" to resized.serialize())
    }

    private fun gate(kind: ComponentKind, fileName: String, operation: GateOperation): ComponentDefinition {
        val isXor = operation in setOf(GateOperation.XOR, GateOperation.XNOR)
        val schema = AttributeSchema(listOf(facing, width, size, inputs, output, label, font) +
            (if (isXor) listOf(xor) else emptyList()) + negations)
        return ComponentDefinition(kind, fileName, "#Gates", defaultAttributes = mapOf("inputs" to "2"), schema = schema,
            layout = { component, attributes -> gateLayout(component, attributes, operation) },
            behavior = object : ComponentBehavior {
                override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                    val attributes = context.attributes
                    val count = attributes[inputs]
                    val values = List(count) { index ->
                        if ("in$index" in context.connectedInputs) context.inputs["in$index"]
                            ?: LogicVector.unknown(attributes[width]) else null
                    }
                    val configuration = configuration(attributes, context.options["gateUndefined"] == "error",
                        if (isXor) attributes[xor] == "odd" else false)
                    return ComponentEvaluation(mapOf("out" to GatesLogic.evaluate(operation, configuration, values)),
                        context.state, delay = 1)
                }
            }, defaultsForVersion = { version ->
                if (CircFormatProfile.parse(version).atMost(2, 7, 1)) mapOf("inputs" to "5") else emptyMap()
            }, isCombinational = true)
    }

    private fun unary(
        kind: ComponentKind,
        fileName: String,
        operation: GateOperation,
        sized: Boolean = false,
        controlled: Boolean = false,
    ): ComponentDefinition {
        val schema = AttributeSchema(listOf(facing, width, label, font) +
            (if (sized) listOf(unarySize) else emptyList()) +
            (if (controlled) listOf(control) else listOf(output)))
        return ComponentDefinition(kind, fileName, "#Gates", schema = schema,
            layout = { component, attributes -> unaryLayout(component, attributes, sized, controlled) },
            behavior = object : ComponentBehavior {
                override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
                    val originalControl = controlled && context.attributes.raw[ORIGINAL_CONTROLLED_GATE_ATTRIBUTE] == "true"
                    val config = GateConfiguration(width = context.attributes[width],
                        outputMode = if (controlled) GateOutputMode.NORMAL else outputMode(context.attributes[output]),
                        undefinedIsError = originalControl || context.options["gateUndefined"] == "error")
                    val values = listOf(context.inputs["in"], context.inputs["enable"])
                    return ComponentEvaluation(mapOf("out" to GatesLogic.evaluate(operation, config, values)),
                        context.state, delay = 1)
                }
            }, defaultsForVersion = { version ->
                if (controlled && CircFormatProfile.parse(version).originalLogisim) {
                    mapOf(ORIGINAL_CONTROLLED_GATE_ATTRIBUTE to "true")
                } else emptyMap()
            }, isCombinational = true)
    }

    private fun configuration(attributes: AttributeValues, errorIfUndefined: Boolean, odd: Boolean): GateConfiguration =
        GateConfiguration(attributes[width], attributes[inputs],
            (0 until attributes[inputs]).filter { attributes[negations[it]] }.toSet(),
            outputMode(attributes[output]), odd, errorIfUndefined)

    private fun outputMode(value: String): GateOutputMode = when (value) {
        "0Z" -> GateOutputMode.LOW_OR_FLOATING
        "Z1" -> GateOutputMode.FLOATING_OR_HIGH
        else -> GateOutputMode.NORMAL
    }

    private fun gateLayout(component: CircuitComponent, attributes: AttributeValues, operation: GateOperation): ComponentLayout {
        val count = attributes[inputs]
        val extent = attributes[size]
        require(extent in setOf(30, 50, 70)) { "Invalid gate size: $extent" }
        val inverted = operation in setOf(GateOperation.NAND, GateOperation.NOR, GateOperation.XNOR)
        val xorBonus = operation in setOf(GateOperation.XOR, GateOperation.XNOR)
        val depth = extent + if (inverted) 10 else 0
        val axis = depth + if (xorBonus) 10 else 0
        val negate = (0 until count).map { attributes[negations[it]] }
        val height = maxOf(extent, 10 * if (count % 2 == 0) count + 1 else count)
        val (start, distance, lower) = when {
            count <= 3 && extent < 40 -> Triple(-5, 10, 10)
            count <= 3 && (extent < 60 || count <= 2) -> Triple(-10, 20, 20)
            count <= 3 -> Triple(-15, 30, 30)
            count == 4 && extent >= 60 -> Triple(-5, 20, 0)
            else -> Triple(-5, 10, 10)
        }
        val ports = listOf(ComponentTerminal("out", component.position, PortDirection.OUTPUT, attributes[width])) +
            (0 until count).map { index ->
                var y = if (count % 2 == 1) start * (count - 1) + distance * index
                else start * count + distance * index + if (index >= count / 2) lower else 0
                if (count % 2 == 0 && count == 4 && extent >= 60) y -= 10
                val x = -(axis + if (negate[index]) 10 else 0)
                ComponentTerminal("in$index", component.absolute(attributes[facing], x, y, mirrorWestInputs = true),
                    PortDirection.INPUT, attributes[width])
            }
        val width = axis + if (negate.any { it }) 10 else 0
        return ComponentLayout(ports, component.orientedBounds(attributes[facing], -width, -height / 2, 0, height / 2))
    }

    private fun unaryLayout(component: CircuitComponent, attributes: AttributeValues, sized: Boolean, controlled: Boolean): ComponentLayout {
        val depth = if (sized) attributes[unarySize].also { require(it in setOf(20, 30)) } else 20
        val direction = attributes[facing]
        val ports = mutableListOf(ComponentTerminal("out", component.position, PortDirection.OUTPUT, attributes[width]),
            ComponentTerminal("in", component.absolute(direction, -depth, 0), PortDirection.INPUT, attributes[width]))
        if (controlled) {
            ports += ComponentTerminal("enable", component.absolute(direction, -(depth - 10),
                if (attributes[control] == "left") -10 else 10), PortDirection.INPUT, 1)
        }
        val halfHeight = if (controlled) 10 else 9
        return ComponentLayout(ports, component.orientedBounds(direction, -depth, -halfHeight, 0, halfHeight))
    }

    private fun plaProgram(attributes: AttributeValues): PlaProgram {
        val program = PlaProgram.parse(attributes[table])
        return program.resized(attributes.raw["in_width"]?.toIntOrNull() ?: program.inputWidth,
            attributes.raw["out_width"]?.toIntOrNull() ?: program.outputWidth)
    }

    private fun plaLayout(component: CircuitComponent, attributes: AttributeValues): ComponentLayout {
        val program = plaProgram(attributes)
        val direction = attributes[facing]
        return ComponentLayout(listOf(
            ComponentTerminal("in", component.position, PortDirection.INPUT, program.inputWidth),
            ComponentTerminal("out", component.absolute(direction, 50, 0), PortDirection.OUTPUT, program.outputWidth),
        ), component.orientedBounds(direction, 0, -25, 50, 25))
    }

    private fun CircuitComponent.absolute(direction: Direction, x: Int, y: Int, mirrorWestInputs: Boolean = false): GridPoint {
        val point = when (direction) {
            Direction.EAST -> GridPoint(x, y)
            Direction.WEST -> GridPoint(-x, if (mirrorWestInputs) y else -y)
            Direction.NORTH -> GridPoint(y, -x)
            Direction.SOUTH -> GridPoint(if (mirrorWestInputs) y else -y, x)
        }
        return GridPoint(position.x + point.x, position.y + point.y)
    }

    private fun CircuitComponent.orientedBounds(direction: Direction, left: Int, top: Int, right: Int, bottom: Int): ComponentBounds {
        val corners = listOf(absolute(direction, left, top), absolute(direction, left, bottom),
            absolute(direction, right, top), absolute(direction, right, bottom))
        return ComponentBounds(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }
}
