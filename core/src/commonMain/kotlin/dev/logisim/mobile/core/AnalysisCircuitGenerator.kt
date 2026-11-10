package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/** Builds a new editable circuit; callers insert or replace it as one editor history operation. */
object AnalysisCircuitGenerator {
    fun generate(
        table: TruthTable,
        name: String = message(Messages.AnalysisTitle).resolve(dev.logisim.mobile.core.localization.systemAppLanguage()),
        form: NormalForm = NormalForm.SOP,
        limits: AnalysisLimits = AnalysisLimits(),
        nandOnly: Boolean = false,
        cancelled: () -> Boolean = { false },
    ): CircuitDocument {
        val expressions = table.outputs.indices.associate { output ->
            table.outputs[output] to BooleanMinimizer.minimize(table, output, form, limits, cancelled).expression
        }
        return generateExpressions(table.inputs, expressions, name, limits, nandOnly, cancelled)
    }

    fun generateExpressions(
        inputs: List<String>,
        outputs: Map<String, BooleanExpression>,
        name: String = message(Messages.AnalysisTitle).resolve(dev.logisim.mobile.core.localization.systemAppLanguage()),
        limits: AnalysisLimits = AnalysisLimits(),
        nandOnly: Boolean = false,
        cancelled: () -> Boolean = { false },
    ): CircuitDocument {
        TruthTable.validateSize(inputs.size, outputs.size, limits)
        requireLocalized(inputs.distinct().size == inputs.size && inputs.all { it.isNotBlank() }) { message(Messages.AnalysisUniqueInputs) }
        requireLocalized(outputs.values.all { inputs.containsAll(it.variables()) }) { message(Messages.AnalysisUndeclaredInputs) }
        val components = mutableListOf<CircuitComponent>()
        val wires = mutableListOf<WireSegment>()
        val nodes = mutableMapOf<BooleanExpression, Signal>()
        val variables = mutableMapOf<String, Signal>()
        var nextId = 0
        var nextNode = 0
        var maximumX = 300
        fun add(kind: ComponentKind, point: GridPoint, label: String = "", attributes: Map<String, String> = emptyMap()): CircuitComponent {
            if (cancelled()) throw AnalysisCancelledException()
            if (components.size >= limits.maxGeneratedComponents) throw AnalysisLimitException(
                LocalizedText(Messages.AnalysisComponentLimit, quantity = limits.maxGeneratedComponents.toLong()))
            return CircuitComponent("analysis-${++nextId}", kind, point, label, attributes).also(components::add)
        }
        fun tunnel(point: GridPoint, net: String) = add(ComponentKind.TUNNEL, point, net, mapOf("width" to "1"))
        for ((index, variable) in inputs.withIndex()) {
            val point = GridPoint(80, 80 + index * 70)
            val input = add(ComponentKind.INPUT, point, variable, mapOf("width" to "1", "facing" to "east"))
            val net = "__analysis_input_$index"
            tunnel(GridPoint(point.x + 40, point.y), net)
            wires += WireSegment(input.position, GridPoint(point.x + 40, point.y))
            variables[variable] = Signal(net, 0)
        }
        fun emit(kind: ComponentKind, inputs: List<Signal>, attributes: Map<String, String> = emptyMap()): Signal {
            val depth = (inputs.maxOfOrNull { it.depth } ?: 0) + 1
            val position = GridPoint(300 + depth * 180, 80 + nextNode++ * 90)
            maximumX = maxOf(maximumX, position.x)
            val component = add(kind, position, attributes = mapOf("width" to "1", "facing" to "east",
                "size" to "50", "inputs" to "2") + attributes)
            component.inputPorts().zip(inputs).forEach { (port, signal) ->
                val source = GridPoint(port.x - 40, port.y)
                tunnel(source, signal.net)
                wires += WireSegment(source, port)
            }
            val net = "__analysis_node_$nextNode"
            val destination = GridPoint(position.x + 40, position.y)
            tunnel(destination, net)
            wires += WireSegment(position, destination)
            return Signal(net, depth)
        }
        fun nand(left: Signal, right: Signal) = emit(ComponentKind.NAND, listOf(left, right))
        fun invert(input: Signal) = nand(input, input)
        fun nandBinary(operator: BooleanOperator, left: Signal, right: Signal): Signal = when (operator) {
            BooleanOperator.AND -> invert(nand(left, right))
            BooleanOperator.OR -> nand(invert(left), invert(right))
            BooleanOperator.XOR, BooleanOperator.XNOR -> {
                val pair = nand(left, right)
                val xor = nand(nand(left, pair), nand(right, pair))
                if (operator == BooleanOperator.XOR) xor else invert(xor)
            }
        }
        fun build(expression: BooleanExpression): Signal {
            nodes[expression]?.let { return it }
            val signal = when (expression) {
                is BooleanExpression.Variable -> return requireNotNull(variables[expression.name])
                is BooleanExpression.Constant -> emit(ComponentKind.CONSTANT, emptyList(),
                    mapOf("value" to if (expression.value) "1" else "0"))
                is BooleanExpression.Not -> {
                    val input = build(expression.expression)
                    if (nandOnly) invert(input) else emit(ComponentKind.NOT, listOf(input), mapOf("size" to "30"))
                }
                is BooleanExpression.Binary -> {
                    val left = build(expression.left)
                    val right = build(expression.right)
                    if (nandOnly) nandBinary(expression.operator, left, right) else {
                        val kind = when (expression.operator) {
                            BooleanOperator.AND -> ComponentKind.AND
                            BooleanOperator.OR -> ComponentKind.OR
                            BooleanOperator.XOR -> ComponentKind.XOR
                            BooleanOperator.XNOR -> ComponentKind.XNOR
                        }
                        emit(kind, listOf(left, right))
                    }
                }
            }
            nodes[expression] = signal
            return signal
        }
        val resultSignals = outputs.mapValues { build(it.value) }
        for ((index, output) in outputs.keys.withIndex()) {
            val position = GridPoint(maximumX + 240, 80 + index * 80)
            add(ComponentKind.OUTPUT, position, output, mapOf("width" to "1", "facing" to "west", "type" to "output"))
            val source = GridPoint(position.x - 40, position.y)
            tunnel(source, resultSignals.getValue(output).net)
            wires += WireSegment(source, position)
        }
        return CircuitDocument(name = name, components = components, wires = wires)
    }

    fun verify(
        table: TruthTable,
        generated: CircuitDocument,
        limits: AnalysisLimits = AnalysisLimits(),
        cancelled: () -> Boolean = { false },
    ): Boolean {
        val observed = CircuitAnalyzer.truthTable(CircuitProject(listOf(generated)), limits = limits, cancelled = cancelled)
        if (observed.inputs != table.inputs || observed.outputs != table.outputs) return false
        return table.rows.indices.all { row -> table.outputs.indices.all { output ->
            val actual = observed.rows[row][output]
            actual != TruthCell.DONT_CARE && (table.rows[row][output] == TruthCell.DONT_CARE || table.rows[row][output] == actual)
        } }
    }

    private data class Signal(val net: String, val depth: Int)
}
