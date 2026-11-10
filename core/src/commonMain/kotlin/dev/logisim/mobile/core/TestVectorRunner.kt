package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.LocalizedStateException
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

enum class VectorRunStatus {
    READY, RUNNING, PAUSED, COMPLETED, CANCELLED;

    val notice: LocalizedText
        get() = message(when (this) {
            READY -> Messages.VectorReady
            RUNNING -> Messages.VectorRunning
            PAUSED -> Messages.VectorPaused
            COMPLETED -> Messages.VectorCompleted
            CANCELLED -> Messages.VectorCancelled
        })
}

data class VectorDifference(
    val column: String,
    val expected: String,
    val observed: String,
    val oscillating: Boolean = false,
) {
    val notice: LocalizedText get() = message(if (oscillating) Messages.VectorDifferenceOscillating else Messages.VectorDifference,
        "column" to column, "expected" to expected, "observed" to observed)
}

data class VectorRowResult(
    val rowIndex: Int,
    val lineNumber: Int,
    val set: Int,
    val sequence: Int,
    val observed: List<LogicVector>,
    val differences: List<VectorDifference>,
) {
    val passed: Boolean get() = differences.isEmpty()
}

class VectorBindingException(notice: LocalizedText) : LocalizedArgumentException(notice) {
    constructor(detail: String) : this(LocalizedText.literal(detail))
}

data class VectorReplay(val circuitName: String, val projectXml: String, val vectorText: String, val resultsCsv: String)

/** Each call to [advance] executes one row, allowing the UI to pause or cancel between rows. */
class TestVectorRunSession(
    project: CircuitProject,
    val circuitName: String,
    val table: VectorTable,
    simulator: CircuitSimulator? = null,
) {
    private val circuit = project.circuit(circuitName)
        ?: throw VectorBindingException(message(Messages.VectorMissingCircuit, "circuit" to circuitName))
    private val testProject = project.copy(mainCircuitName = circuitName)
    private val settings = SimulationProjectOptions.read(project)
    private val options = settings.asMap()
    private val simulator = simulator ?: CircuitSimulator(propagationLimit = settings.propagationLimit)
    private val bindings: List<CircuitComponent> = table.columns.map { column ->
        val found = circuit.components.filter { component ->
            when (component.kind) {
                ComponentKind.CLOCK -> component.label == column.name ||
                    (component.label.isBlank() && column.name.equals("<clk>", ignoreCase = true))
                ComponentKind.INPUT, ComponentKind.OUTPUT -> component.label == column.name
                else -> false
            }
        }
        if (found.isEmpty()) throw VectorBindingException(message(Messages.VectorUnboundColumn, "column" to column.name, "circuit" to circuitName))
        if (found.size != 1) throw VectorBindingException(message(Messages.VectorAmbiguousColumn, "column" to column.name))
        val component = found.single()
        val width = if (component.kind == ComponentKind.CLOCK) 1 else component.width
        if (width != column.width) throw VectorBindingException(message(Messages.VectorBindingWidth, "column" to column.name, "actual" to width, "expected" to column.width))
        component
    }
    private val completedRows = mutableListOf<VectorRowResult>()
    val results: List<VectorRowResult> get() = completedRows.toList()
    var state: SimulationState = this.simulator.settle(testProject, options = options)
        private set
    var status: VectorRunStatus = if (table.rows.isEmpty()) VectorRunStatus.COMPLETED else VectorRunStatus.READY
        private set
    val passedCount: Int get() = completedRows.count { it.passed }
    val failedCount: Int get() = completedRows.size - passedCount
    val completedCount: Int get() = completedRows.size
    private var previousSet = -1
    private var previousSequence = 0

    init {
        table.rows.forEach { row ->
            if (row.values.size != table.columns.size) throw VectorBindingException(message(Messages.VectorRowValueCount, "line" to row.lineNumber))
            row.values.forEachIndexed { index, cell ->
                if (bindings[index].kind == ComponentKind.INPUT && cell == VectorCell.DontCare) {
                    throw VectorBindingException(message(Messages.VectorInputValue, "line" to row.lineNumber, "column" to table.columns[index].name))
                }
                if (cell is VectorCell.Value && cell.value.width != table.columns[index].width) {
                    throw VectorBindingException(message(Messages.VectorRowWidth, "line" to row.lineNumber, "column" to table.columns[index].name))
                }
            }
        }
    }

    fun pause() {
        if (status == VectorRunStatus.READY || status == VectorRunStatus.RUNNING) status = VectorRunStatus.PAUSED
    }

    fun resume() {
        if (status == VectorRunStatus.PAUSED) status = VectorRunStatus.READY
    }

    fun cancel() {
        if (status != VectorRunStatus.COMPLETED) status = VectorRunStatus.CANCELLED
    }

    fun reset() {
        completedRows.clear()
        previousSet = -1
        previousSequence = 0
        state = simulator.settle(testProject, options = options)
        status = if (table.rows.isEmpty()) VectorRunStatus.COMPLETED else VectorRunStatus.READY
    }

    fun advance(): VectorRowResult? {
        if (status in setOf(VectorRunStatus.PAUSED, VectorRunStatus.CANCELLED, VectorRunStatus.COMPLETED)) return null
        status = VectorRunStatus.RUNNING
        val index = completedRows.size
        val row = table.rows[index]
        val reset = row.sequence == 0 || previousSequence == 0 || row.set != previousSet
        if (reset) state = simulator.settle(testProject, options = options)
        else if (!state.oscillating) state = simulator.settle(testProject, state, tick = true, options = options)
        previousSet = row.set
        previousSequence = row.sequence
        if (!state.oscillating) {
            val inputs = bindings.mapIndexedNotNull { column, component ->
                if (component.kind != ComponentKind.INPUT) null else component.id to when (val cell = row.values[column]) {
                    is VectorCell.Value -> cell.value
                    VectorCell.Floating -> LogicVector.unknown(table.columns[column].width)
                    VectorCell.DontCare -> throw LocalizedStateException(message(Messages.VectorInputDcInternal))
                }
            }.toMap()
            state = simulator.settle(testProject, state, vectorInputs = inputs, options = options)
        }
        val observed = bindings.mapIndexed { column, component ->
            state.vectorValues[component.id] ?: LogicVector.unknown(table.columns[column].width)
        }
        val differences = bindings.mapIndexedNotNull { column, component ->
            if (component.kind == ComponentKind.INPUT) return@mapIndexedNotNull null
            val expected = row.values[column]
            if (expected == VectorCell.DontCare) return@mapIndexedNotNull null
            val actual = observed[column]
            val matches = when (expected) {
                VectorCell.DontCare -> true
                VectorCell.Floating -> actual.isUnknown
                is VectorCell.Value -> compatible(expected.value, actual)
            }
            if (matches && !state.oscillating) null else VectorDifference(table.columns[column].name,
                expected.displayText(), actual.display, state.oscillating)
        }
        val result = VectorRowResult(index, row.lineNumber, row.set, row.sequence, observed, differences)
        completedRows += result
        if (completedRows.size == table.rows.size) status = VectorRunStatus.COMPLETED
        return result
    }

    fun run(maxRows: Int = Int.MAX_VALUE): List<VectorRowResult> {
        requireLocalized(maxRows >= 0) { message(Messages.VectorRowLimit) }
        val added = mutableListOf<VectorRowResult>()
        repeat(minOf(maxRows, table.rows.size - completedRows.size)) {
            val next = advance() ?: return added
            added += next
        }
        return added
    }

    fun exportCsv(): String = buildString {
        append("circuit,row,line,set,seq,result")
        for (column in table.columns) {
            append(',').append(csv("${column.name}[${column.width}] expected"))
            append(',').append(csv("${column.name}[${column.width}] observed"))
        }
        append(",differences\n")
        for (result in completedRows) {
            append(csv(circuitName)).append(',').append(result.rowIndex + 1).append(',').append(result.lineNumber)
                .append(',').append(result.set).append(',').append(result.sequence)
                .append(',').append(if (result.passed) "PASS" else "FAIL")
            for (index in table.columns.indices) {
                append(',').append(csv(table.rows[result.rowIndex].values[index].displayText()))
                append(',').append(result.observed[index].display)
            }
            append(',').append(csv(result.differences.joinToString("; ") {
                "${it.column}: ${it.expected} != ${it.observed}" + if (it.oscillating) " (OSCILLATING)" else ""
            })).append('\n')
        }
    }

    fun exportReplay(): VectorReplay = VectorReplay(circuitName, CircCodec.writeProject(testProject),
        TestVectorFormat.write(table), exportCsv())

    companion object {
        fun compatible(expected: LogicVector, observed: LogicVector): Boolean = expected.width == observed.width &&
            expected.errorMask == observed.errorMask &&
            expected.value == (observed.value and expected.unknownMask.inv()) &&
            expected.unknownMask == (observed.unknownMask or expected.unknownMask)

        private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""
    }
}

fun VectorCell.displayText(): String = when (this) {
    is VectorCell.Value -> value.display
    VectorCell.DontCare -> "<DC>"
    VectorCell.Floating -> "<float>"
}
