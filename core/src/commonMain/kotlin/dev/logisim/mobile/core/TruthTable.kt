package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedStateException

enum class TruthCell(val symbol: String) {
    ZERO("0"), ONE("1"), DONT_CARE("x");
    fun next(): TruthCell = when (this) { ZERO -> ONE; ONE -> DONT_CARE; DONT_CARE -> ZERO }
}

data class AnalysisLimits(
    val maxInputBits: Int = 12,
    val maxOutputBits: Int = 256,
    val maxMinimizationInputBits: Int = 6,
    val maxSearchSteps: Int = 1_000_000,
    val maxGeneratedComponents: Int = 4_096,
) {
    init {
        require(maxInputBits in 0..20 && maxMinimizationInputBits in 0..20)
        require(maxOutputBits in 1..256 && maxSearchSteps > 0 && maxGeneratedComponents > 0)
    }
}

class AnalysisLimitException(text: LocalizedText) : LocalizedArgumentException(text) {
    constructor(message: String) : this(LocalizedText.literal(message))
}
class AnalysisCancelledException : LocalizedStateException(message(Messages.AnalysisCancelled))

internal class AnalysisBudget(
    private val limit: Int,
    private val cancelled: () -> Boolean,
) {
    private var work = 0
    fun step() {
        if (cancelled()) throw AnalysisCancelledException()
        if (++work > limit) throw AnalysisLimitException(LocalizedText(Messages.AnalysisOperationLimit, quantity = limit.toLong()))
    }
}

data class TruthTable(
    val inputs: List<String>,
    val outputs: List<String>,
    val rows: List<List<TruthCell>>,
) {
    init {
        requireLocalized(inputs.size <= 20) { message(Messages.AnalysisTableInputsLimit) }
        requireLocalized(outputs.isNotEmpty() && outputs.size <= 256) { message(Messages.AnalysisOutputRange) }
        requireLocalized(inputs.all { it.isNotBlank() } && inputs.distinct().size == inputs.size) { message(Messages.AnalysisUniqueInputs) }
        requireLocalized(outputs.all { it.isNotBlank() } && outputs.distinct().size == outputs.size) { message(Messages.AnalysisUniqueOutputs) }
        requireLocalized(rows.size == 1 shl inputs.size) { LocalizedText(Messages.AnalysisRowsRequired, quantity = (1 shl inputs.size).toLong()) }
        requireLocalized(rows.all { it.size == outputs.size }) { LocalizedText(Messages.AnalysisRowOutputsRequired, quantity = outputs.size.toLong()) }
    }

    fun input(row: Int, column: Int): Boolean {
        require(row in rows.indices && column in inputs.indices)
        return row and (1 shl (inputs.lastIndex - column)) != 0
    }

    fun assignment(row: Int): Map<String, Boolean> = inputs.mapIndexed { column, name -> name to input(row, column) }.toMap()

    fun withCell(row: Int, output: Int, value: TruthCell): TruthTable {
        require(row in rows.indices && output in outputs.indices)
        return copy(rows = rows.mapIndexed { index, values ->
            if (index == row) values.mapIndexed { column, old -> if (column == output) value else old } else values
        })
    }

    /**
     * Every output cell of a marked block set to [value], as TableTabCaret.doKey fills the marked cells when 0, 1 or x
     * is typed. [columns] are table columns, the inputs first and then the outputs; input columns are not editable and
     * are left out, so a block that starts on an input changes only the outputs it covers.
     */
    fun withBlock(rows: IntRange, columns: IntRange, value: TruthCell): TruthTable {
        require(!rows.isEmpty() && rows.first >= 0 && rows.last < this.rows.size)
        require(!columns.isEmpty() && columns.first >= 0 && columns.last < inputs.size + outputs.size)
        val outputs = maxOf(columns.first, inputs.size) - inputs.size..columns.last - inputs.size
        if (outputs.isEmpty()) return this
        return copy(rows = this.rows.mapIndexed { index, values ->
            if (index in rows) values.mapIndexed { output, old -> if (output in outputs) value else old } else values
        })
    }

    /** Move an input bit to a new position while retaining each assignment's outputs. */
    fun withInputColumn(column: Int, name: String, destination: Int = column): TruthTable {
        val order = inputs.indices.moveColumn(column, destination)
        val names = order.map { if (it == column) name else inputs[it] }
        val reorderedRows = if (column == destination) rows else List(rows.size) { newRow ->
            val originalRow = order.foldIndexed(0) { newColumn, row, originalColumn ->
                if (input(newRow, newColumn)) row or (1 shl (inputs.lastIndex - originalColumn)) else row
            }
            rows[originalRow]
        }
        return copy(inputs = names, rows = reorderedRows)
    }

    /** Move an output name and its cells together. */
    fun withOutputColumn(column: Int, name: String, destination: Int = column): TruthTable {
        val order = outputs.indices.moveColumn(column, destination)
        val names = order.map { if (it == column) name else outputs[it] }
        val reorderedRows = if (column == destination) rows else rows.map { values -> order.map { values[it] } }
        return copy(outputs = names, rows = reorderedRows)
    }

    /**
     * Insert input [name] at column [index] (TruthTable.addInputForOutput): the rows double, and both rows of each
     * pair that differs only in the new bit keep the outputs of the row they came from.
     */
    fun withInputAdded(name: String, index: Int = inputs.size, limits: AnalysisLimits = AnalysisLimits()): TruthTable {
        require(index in 0..inputs.size)
        requireLocalized(name.isNotBlank() && name !in inputs) { message(Messages.AnalysisUniqueInputs) }
        validateSize(inputs.size + 1, outputs.size, limits)
        // input() reads column c from bit (inputs.lastIndex - c), so the new column is bit (inputs.size - index).
        val bit = inputs.size - index
        val low = (1 shl bit) - 1
        return TruthTable(inputs.toMutableList().apply { add(index, name) }, outputs, List(rows.size * 2) { row ->
            rows[((row shr (bit + 1)) shl bit) or (row and low)]
        })
    }

    /**
     * Remove the input at [column] (TruthTable.removeInputForOutput): each pair of rows that differs only in that
     * bit becomes one row, keeping a cell both rows share and marking a cell they disagree on as don't care.
     */
    fun withInputRemoved(column: Int): TruthTable {
        require(column in inputs.indices)
        val bit = inputs.lastIndex - column
        val low = (1 shl bit) - 1
        return TruthTable(inputs.filterIndexed { index, _ -> index != column }, outputs, List(rows.size / 2) { row ->
            val zero = ((row shr bit) shl (bit + 1)) or (row and low)
            val first = rows[zero]
            val second = rows[zero or (1 shl bit)]
            if (first == second) first else first.mapIndexed { output, value -> if (value == second[output]) value else TruthCell.DONT_CARE }
        })
    }

    /** Insert output [name] at [index]; Evolution fills a new output column with its DEFAULT_ENTRY, don't care. */
    fun withOutputAdded(
        name: String,
        index: Int = outputs.size,
        limits: AnalysisLimits = AnalysisLimits(),
        value: TruthCell = TruthCell.DONT_CARE,
    ): TruthTable {
        require(index in 0..outputs.size)
        requireLocalized(name.isNotBlank() && name !in outputs) { message(Messages.AnalysisUniqueOutputs) }
        validateSize(inputs.size, outputs.size + 1, limits)
        return copy(outputs = outputs.toMutableList().apply { add(index, name) },
            rows = rows.map { values -> values.toMutableList().apply { add(index, value) } })
    }

    /** Remove the output at [column]; a table keeps at least one output. */
    fun withOutputRemoved(column: Int): TruthTable {
        require(column in outputs.indices)
        requireLocalized(outputs.size > 1) { message(Messages.AnalysisOutputRange) }
        return copy(outputs = outputs.filterIndexed { index, _ -> index != column },
            rows = rows.map { values -> values.filterIndexed { index, _ -> index != column } })
    }

    /**
     * Replace the columns of the outputs [defined] names and append the outputs it adds, keeping every other output
     * (ExpressionTab edits one output's column at a time). [defined] must use this table's inputs in this order.
     */
    fun withOutputsReplaced(defined: TruthTable, limits: AnalysisLimits = AnalysisLimits()): TruthTable {
        require(defined.inputs == inputs)
        val added = defined.outputs.filter { it !in outputs }
        validateSize(inputs.size, outputs.size + added.size, limits)
        val source = defined.outputs.withIndex().associate { (index, name) -> name to index }
        val names = outputs + added
        return TruthTable(inputs, names, rows.indices.map { row ->
            names.mapIndexed { column, name -> source[name]?.let { defined.rows[row][it] } ?: rows[row][column] }
        })
    }

    companion object {
        fun fromExpressions(
            expressions: Map<String, BooleanExpression>,
            inputs: List<String> = expressions.values.flatMap { it.variables() }.distinct(),
            limits: AnalysisLimits = AnalysisLimits(),
            cancelled: () -> Boolean = { false },
        ): TruthTable {
            validateSize(inputs.size, expressions.size, limits)
            requireLocalized(expressions.values.all { inputs.containsAll(it.variables()) }) { message(Messages.AnalysisUnlistedVariables) }
            val budget = AnalysisBudget(limits.maxSearchSteps, cancelled)
            val rows = (0 until (1 shl inputs.size)).map { row ->
                budget.step()
                val assignment = inputs.mapIndexed { column, name ->
                    name to (row and (1 shl (inputs.lastIndex - column)) != 0)
                }.toMap()
                expressions.values.map { if (it.evaluateChecked(assignment, budget)) TruthCell.ONE else TruthCell.ZERO }
            }
            return TruthTable(inputs, expressions.keys.toList(), rows)
        }

        internal fun validateSize(inputCount: Int, outputCount: Int, limits: AnalysisLimits) {
            if (inputCount > limits.maxInputBits) throw AnalysisLimitException(
                message(Messages.AnalysisInputBitsLimit, "bits" to limits.maxInputBits, "rows" to (1 shl limits.maxInputBits)))
            if (outputCount !in 1..limits.maxOutputBits) throw AnalysisLimitException(
                LocalizedText(Messages.AnalysisOutputBitsLimit, quantity = limits.maxOutputBits.toLong()))
        }
    }
}

private fun IntRange.moveColumn(column: Int, destination: Int): List<Int> {
    require(column in this && destination in this)
    return toMutableList().apply { add(destination, removeAt(column)) }
}

private fun BooleanExpression.evaluateChecked(values: Map<String, Boolean>, budget: AnalysisBudget): Boolean {
    budget.step()
    return when (this) {
        is BooleanExpression.Constant -> value
        is BooleanExpression.Variable -> values[name] ?: throw LocalizedArgumentException(message(Messages.AnalysisInputUndefined, "name" to name))
        is BooleanExpression.Not -> !expression.evaluateChecked(values, budget)
        is BooleanExpression.Binary -> {
            val a = left.evaluateChecked(values, budget)
            val b = right.evaluateChecked(values, budget)
            when (operator) {
                BooleanOperator.AND -> a && b
                BooleanOperator.OR -> a || b
                BooleanOperator.XOR -> a != b
                BooleanOperator.XNOR -> a == b
            }
        }
    }
}

interface PagedTruthTable {
    val inputs: List<String>
    val outputs: List<String>
    val rowCount: Int get() = 1 shl inputs.size

    fun input(row: Int, column: Int): Boolean {
        require(row in 0 until rowCount && column in inputs.indices)
        return row and (1 shl (inputs.lastIndex - column)) != 0
    }

    fun readRows(firstRow: Int, count: Int, cancelled: () -> Boolean = { false }): List<List<TruthCell>>
}

/** A circuit truth table whose rows are evaluated only when requested. */
class CircuitTruthTable internal constructor(
    override val inputs: List<String>,
    override val outputs: List<String>,
    private val evaluate: (IntRange, () -> Boolean, (Int, List<TruthCell>) -> Unit) -> Unit,
) : PagedTruthTable {
    override fun readRows(firstRow: Int, count: Int, cancelled: () -> Boolean): List<List<TruthCell>> =
        buildList { evaluate(rowRange(firstRow, count), cancelled) { _, values -> add(values) } }

    fun csvHeader(): String = (inputs + outputs).joinToString(",") { value ->
        if (value.any { it in ",\"\r\n" }) "\"${value.replace("\"", "\"\"")}\"" else value
    } + "\n"

    fun csvRows(firstRow: Int, count: Int, cancelled: () -> Boolean = { false }): String = buildString {
        evaluate(rowRange(firstRow, count), cancelled) { row, values ->
            for (column in inputs.indices) {
                append(if (input(row, column)) '1' else '0')
                append(',')
            }
            appendLine(values.joinToString(",") { it.symbol })
        }
    }

    private fun rowRange(firstRow: Int, count: Int): IntRange {
        require(firstRow in 0..rowCount && count in 0..(rowCount - firstRow))
        return firstRow until firstRow + count
    }
}

/** Evaluates each row from a fresh simulation state; storage and clocks are rejected. */
object CircuitAnalyzer {
    fun truthTable(
        project: CircuitProject,
        circuitName: String = project.mainCircuitName,
        limits: AnalysisLimits = AnalysisLimits(),
        cancelled: () -> Boolean = { false },
        registry: ComponentRegistry? = null,
        simulator: CircuitSimulator? = null,
        options: Map<String, String>? = null,
    ): TruthTable {
        val source = pagedTruthTable(project, circuitName, limits, registry, simulator, options)
        return TruthTable(source.inputs, source.outputs, source.readRows(0, source.rowCount, cancelled))
    }

    fun pagedTruthTable(
        project: CircuitProject,
        circuitName: String = project.mainCircuitName,
        limits: AnalysisLimits = AnalysisLimits(maxInputBits = 20),
        registry: ComponentRegistry? = null,
        simulator: CircuitSimulator? = null,
        options: Map<String, String>? = null,
    ): CircuitTruthTable {
        val circuit = project.circuit(circuitName) ?: throw LocalizedArgumentException(message(Messages.AnalysisMissingCircuit, "name" to circuitName))
        val inspected = mutableSetOf<String>()
        fun inspect(name: String, ancestry: Set<String>) {
            requireLocalized(name !in ancestry) { message(Messages.AnalysisRecursiveCircuit, "name" to name) }
            if (!inspected.add(name)) return
            val document = requireNotNull(project.circuit(name))
            requireLocalized(document.components.all { component ->
                component.kind == ComponentKind.SUBCIRCUIT ||
                    if (registry == null) component.kind in legacyCombinationalKinds
                    else registry.requireDefinition(component.kind).isCombinational
            }) { message(Messages.AnalysisTemporalCircuit) }
            document.components.filter { it.kind == ComponentKind.SUBCIRCUIT }.forEach {
                inspect(requireNotNull(it.circuitName), ancestry + name)
            }
        }
        inspect(circuitName, emptySet())
        val original = CircFormatProfile.parse(project.sourceVersion).originalLogisim
        val originalNames = if (original) canonicalPinNames(circuit) else emptyMap()
        val pins = if (original) originalNames.keys else circuit.components.filter {
            it.kind == ComponentKind.INPUT || it.kind == ComponentKind.OUTPUT
        }
        val inputPins = pins.filter { it.kind == ComponentKind.INPUT }
        val outputPins = pins.filter { it.kind == ComponentKind.OUTPUT }
        TruthTable.validateSize(inputPins.sumOf { it.width }, outputPins.sumOf { it.width }, limits)
        val inputNames = inputPins.flatMap { pin -> names(pin, originalNames[pin]) }
        val outputNames = outputPins.flatMap { pin -> names(pin, originalNames[pin]) }
        requireLocalized(inputNames.distinct().size == inputNames.size && outputNames.distinct().size == outputNames.size) {
            message(Messages.AnalysisDistinctPins)
        }
        val active = project.copy(mainCircuitName = circuitName)
        val settings = if (simulator == null || options == null) SimulationProjectOptions.read(project) else null
        val simulationOptions = options ?: requireNotNull(settings).asMap()
        return CircuitTruthTable(inputNames, outputNames) { range, cancelled, emit ->
            val budget = AnalysisBudget(limits.maxSearchSteps, cancelled)
            val engine = simulator ?: CircuitSimulator(
                propagationLimit = requireNotNull(settings).propagationLimit,
                registry = registry ?: ComponentCatalog.registry,
                cancellationCheck = { if (cancelled()) throw AnalysisCancelledException() },
            )
            for (row in range) {
                budget.step()
                var remaining = inputNames.size
                val stimulus = inputPins.associate { pin ->
                    remaining -= pin.width
                    pin.id to LogicVector.of(pin.width, (row.toULong() shr remaining) and bitMask(pin.width))
                }
                val state = engine.settle(active, vectorInputs = stimulus, options = simulationOptions)
                requireLocalized(!state.oscillating && state.diagnostics.isEmpty()) {
                    message(Messages.AnalysisCircuitFailed, "reason" to LocalizedText.join(state.notices, ", "))
                }
                emit(row, outputPins.flatMap { pin ->
                    val value = state.vectorValues[pin.id] ?: LogicVector.unknown(pin.width)
                    (pin.width - 1 downTo 0).map { bit ->
                        when (value.bit(bit)) {
                            LogicValue.LOW -> TruthCell.ZERO
                            LogicValue.HIGH -> TruthCell.ONE
                            LogicValue.UNKNOWN -> TruthCell.DONT_CARE
                            LogicValue.ERROR -> throw LocalizedArgumentException(message(Messages.AnalysisUndefinedOutput, "name" to pin.label.ifBlank { pin.id }, "row" to row))
                        }
                    }
                })
            }
        }
    }

    private fun names(pin: CircuitComponent, originalName: String?): List<String> {
        val name = originalName ?: pin.label.ifBlank { pin.id }
        return if (pin.width == 1) listOf(name) else (pin.width - 1 downTo 0).map { "$name[$it]" }
    }

    internal fun canonicalPinNames(
        circuit: CircuitDocument,
        label: (CircuitComponent) -> String = { it.label },
    ): Map<CircuitComponent, String> {
        val pins = circuit.components.filter { it.kind == ComponentKind.INPUT || it.kind == ComponentKind.OUTPUT }
            .sortedWith(compareBy({ it.position.y }, { it.position.x }))
        val names = mutableMapOf<CircuitComponent, String>()
        val used = mutableSetOf<String>()
        for (pin in pins) {
            val base = originalPinLabel(label(pin))
            if (base.isEmpty()) continue
            var name = base
            var suffix = 2
            while (!used.add(name)) name = base + suffix++
            names[pin] = name
        }
        // Reserve all explicit labels before choosing defaults, across inputs and outputs.
        val inputDefaults = listOf("a", "b", "c", "d", "e", "f", "g", "h")
        val outputDefaults = listOf("x", "y", "z", "u", "v", "w", "s", "t")
        var suffix = 2
        for (pin in pins) {
            if (pin in names) continue
            val defaults = if (pin.kind == ComponentKind.INPUT) inputDefaults else outputDefaults
            var name = defaults.firstOrNull { it !in used }
            if (name == null) {
                do { name = "x" + suffix++ } while (name in used)
            }
            used.add(name)
            names[pin] = name
        }
        return pins.associateWith { names.getValue(it) }
    }

    private fun originalPinLabel(label: String): String = buildString {
        val leading = StringBuilder()
        var afterSpace = false
        for (character in label) {
            when {
                character.isLetter() || character.category in originalIdentifierStartCategories -> {
                    append(if (afterSpace) character.titlecaseChar() else character)
                    afterSpace = false
                }
                character.isDigit() || character.category in originalIdentifierPartCategories ||
                    character in '\u0000'..'\u0008' || character in '\u000e'..'\u001b' ||
                    character in '\u007f'..'\u009f' -> {
                    if (isEmpty()) leading.append(character) else append(character)
                    afterSpace = false
                }
                character.isWhitespace() && character !in "\u00a0\u2007\u202f" -> afterSpace = true
            }
        }
        if (isNotEmpty()) append(leading)
    }

    private val originalIdentifierStartCategories = setOf(
        CharCategory.LETTER_NUMBER, CharCategory.CURRENCY_SYMBOL, CharCategory.CONNECTOR_PUNCTUATION,
    )
    private val originalIdentifierPartCategories = setOf(
        CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.FORMAT,
    )

    private val legacyCombinationalKinds = setOf(
        ComponentKind.INPUT, ComponentKind.OUTPUT, ComponentKind.AND, ComponentKind.OR, ComponentKind.XOR,
        ComponentKind.NAND, ComponentKind.NOR, ComponentKind.XNOR, ComponentKind.NOT, ComponentKind.BUFFER,
        ComponentKind.CONSTANT, ComponentKind.TUNNEL, ComponentKind.SPLITTER, ComponentKind.CONTROLLED_BUFFER,
        ComponentKind.CONTROLLED_INVERTER, ComponentKind.ROM, ComponentKind.ADDER, ComponentKind.SUBTRACTOR,
        ComponentKind.MULTIPLEXER, ComponentKind.DECODER, ComponentKind.HEX_DISPLAY,
        ComponentKind.SEVEN_SEGMENT_DISPLAY, ComponentKind.LED, ComponentKind.TEXT, ComponentKind.PROBE,
        ComponentKind.POWER, ComponentKind.GROUND, ComponentKind.BIT_EXTENDER,
    )
    private fun bitMask(width: Int): ULong = if (width == 64) ULong.MAX_VALUE else (1uL shl width) - 1uL
}
