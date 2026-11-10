package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message

// Port of the BLIF circuit simulator in Logisim Evolution d58a61e0:
// std/hdl/{BlifParser, DenseLogicCircuit, DenseLogicCircuitBuilder, BlifCircuitComponent}.java.
// The parser, the cell and gate numbering, the update queue and the sequential script follow
// the desktop code, so the same source produces the same values in the same order.

/** A BLIF source that Evolution's parser rejects. The message is the desktop parser's message. */
class BlifParseException(message: String) : RuntimeException(message)

data class BlifPort(val name: String, val direction: PortDirection, val width: Int)

/** Result of Evolution's `BlifParser.parse()` and `compile()` for one source. */
class BlifCompilation(
    val name: String,
    val inputs: List<BlifPort>,
    val outputs: List<BlifPort>,
    val circuit: DenseLogicCircuit,
    /** Cell of each input bit ("x." symbols), or -1. */
    val inputCells: List<IntArray>,
    /** Cell of each output bit ("o." symbols), or -1. */
    val outputCells: List<IntArray>,
)

object BlifParser {
    private const val RECOMMENDED_LOGIC_LIBRARY_URL = "https://github.com/YosysHQ/yosys/tree/main/examples/cmos"

    private data class Gate(val type: Int, val a: String, val b: String, val y: String)
    private data class Mux(val a: String, val b: String, val s: String, val y: String)
    private data class Dff(val c: String, val d: String, val q: String)
    private data class Dffsr(val c: String, val d: String, val q: String, val s: String, val r: String)
    private data class Latch(val d: String, val e: String, val q: String)

    /** Parses and compiles [source]; throws [BlifParseException] where Evolution reports a parse error. */
    fun compile(source: String): BlifCompilation = try {
        compileUnchecked(source)
    } catch (error: BlifParseException) {
        throw error
    } catch (error: RuntimeException) {
        // Evolution catches every exception of parse() and compile() and shows its message.
        throw BlifParseException(error.message ?: error::class.simpleName.orEmpty())
    }

    private fun compileUnchecked(source: String): BlifCompilation {
        val inputSet = mutableSetOf<String>()
        val outputSet = mutableSetOf<String>()
        val gates = mutableListOf<Gate>()
        val muxes = mutableListOf<Mux>()
        val dff = mutableListOf<Dff>()
        val dffsr = mutableListOf<Dffsr>()
        val latches = mutableListOf<Latch>()
        val pullups = mutableListOf<String>()
        val pulldowns = mutableListOf<String>()
        var name: String? = null
        for (line in javaSplit(source, '\n')) {
            val actual = javaTrim(line)
            if (!actual.startsWith(".")) continue
            val words = javaSplit(actual, ' ')
            fun word(index: Int): String =
                words.getOrNull(index) ?: throw BlifParseException("Index $index out of bounds for length ${words.size}")
            when (words[0]) {
                ".inputs" -> for (i in 1 until words.size) inputSet += words[i]
                ".outputs" -> for (i in 1 until words.size) outputSet += words[i]
                ".model" -> if (words.size > 1) {
                    if (name != null) throw BlifParseException("many tops (try 'synth -flatten -top $name')")
                    name = words[1]
                }
                ".names" -> if (words.size != 2 || words[1] !in setOf("\$true", "\$false", "\$undef")) {
                    throw BlifParseException("odd .names (try 'write_blif -icells -conn out.blif')")
                }
                ".conn" -> gates += Gate(DenseLogicCircuit.GATE_BUS, word(1), word(1), word(2))
                ".subckt", ".gate" -> {
                    val type = word(1)
                    val pins = HashMap<String, String>()
                    for (i in 2 until words.size) {
                        val assignment = words[i]
                        val eq = assignment.indexOf('=')
                        if (eq < 0) throw BlifParseException("begin 0, end -1, length ${assignment.length}")
                        pins[assignment.substring(0, eq)] = assignment.substring(eq + 1)
                    }
                    // Java concatenates a missing pin as "null".
                    fun pin(key: String) = pins[key] ?: "null"
                    when (type) {
                        "BUF" -> gates += Gate(DenseLogicCircuit.GATE_BUS, pin("A"), pin("A"), pin("Y"))
                        "NOT" -> gates += Gate(DenseLogicCircuit.GATE_NOR, pin("A"), pin("A"), pin("Y"))
                        "MUX" -> muxes += Mux(pin("A"), pin("B"), pin("S"), pin("Y"))
                        "PULLUP" -> pullups += pin("Y")
                        "PULLDOWN" -> pulldowns += pin("Y")
                        "DFF" -> dff += Dff(pin("C"), pin("D"), pin("Q"))
                        "DFFSR" -> dffsr += Dffsr(pin("C"), pin("D"), pin("Q"), pin("S"), pin("R"))
                        "DLATCH" -> latches += Latch(pin("D"), pin("E"), pin("Q"))
                        else -> {
                            val gateType = DenseLogicCircuit.GATE_TYPE_NAMES.indexOf(type)
                            if (gateType < 0) throw BlifParseException("unknown gate $type\ntry basing your script on $RECOMMENDED_LOGIC_LIBRARY_URL")
                            gates += Gate(gateType, pin("A"), pin("B"), pin("Y"))
                        }
                    }
                }
            }
        }
        inputSet.removeAll(outputSet)
        val inputs = nameSet(inputSet.sorted(), PortDirection.INPUT)
        val outputs = nameSet(outputSet.sorted(), PortDirection.OUTPUT)

        val builder = DenseLogicCircuitBuilder()
        val symbols = builder.symbolTable
        symbols["i.\$false"] = DenseLogicCircuit.LEV_LOW
        symbols["o.\$false"] = DenseLogicCircuit.LEV_LOW
        symbols["i.\$true"] = DenseLogicCircuit.LEV_HIGH
        symbols["o.\$true"] = DenseLogicCircuit.LEV_HIGH
        symbols["i.\$undef"] = DenseLogicCircuit.LEV_NONE
        symbols["o.\$undef"] = DenseLogicCircuit.LEV_NONE
        for (port in inputs + outputs) for (i in 0 until port.width) {
            val pin = pinSymbol(port, i)
            val externalInput = builder.addCell(true)
            val internalInput = builder.addCell(false)
            val externalOutput = builder.addCell(false)
            symbols["x.$pin"] = externalInput
            symbols["i.$pin"] = internalInput
            symbols["o.$pin"] = externalOutput
            builder.attachGate(DenseLogicCircuit.GATE_BUS, externalInput, externalOutput, internalInput)
        }
        fun newWire(pin: String): Int = builder.addCell(false).also { symbols["i.$pin"] = it; symbols["o.$pin"] = it }
        fun input(pin: String): Int = symbols["i.$pin"] ?: newWire(pin)
        fun output(pin: String): Int = symbols["o.$pin"] ?: newWire(pin)
        for (gate in gates) {
            val a = input(gate.a)
            val b = input(gate.b)
            builder.attachGate(gate.type, a, b, output(gate.y))
        }
        for (mux in muxes) {
            val a = input(mux.a)
            val b = input(mux.b)
            val s = input(mux.s)
            val y = output(mux.y)
            builder.attachGate(DenseLogicCircuit.GATE_TRISI, a, s, y)
            builder.attachGate(DenseLogicCircuit.GATE_TRIS, b, s, y)
        }
        for (ff in dff) {
            val c = input(ff.c)
            val d = input(ff.d)
            val q = output(ff.q)
            builder.attachBuffer(builder.addDff(c, d), q)
        }
        for (latch in latches) {
            val d = input(latch.d)
            val e = input(latch.e)
            val q = output(latch.q)
            builder.attachBuffer(builder.addLatch(d, e), q)
        }
        for (ff in dffsr) {
            val c = input(ff.c)
            val d = input(ff.d)
            val q = output(ff.q)
            val s = input(ff.s)
            val r = input(ff.r)
            builder.attachBuffer(builder.addDffsr(c, d, s, r), q)
        }
        for (pin in pullups) builder.setCellPull(output(pin), DenseLogicCircuit.LEV_HIGH)
        for (pin in pulldowns) builder.setCellPull(output(pin), DenseLogicCircuit.LEV_LOW)
        val circuit = builder.build()
        fun cells(ports: List<BlifPort>, prefix: String) = ports.map { port ->
            IntArray(port.width) { circuit.symbolTable["$prefix${pinSymbol(port, it)}"] ?: -1 }
        }
        return BlifCompilation(name.orEmpty(), inputs, outputs, circuit, cells(inputs, "x."), cells(outputs, "o."))
    }

    private fun pinSymbol(port: BlifPort, index: Int) = if (port.width == 1) port.name else "${port.name}[$index]"

    /** Evolution's parseNameSet: scalars in name order, then each bus (highest index + 1 bits). */
    private fun nameSet(names: List<String>, direction: PortDirection): List<BlifPort> {
        val ports = mutableListOf<BlifPort>()
        val maxBits = mutableMapOf<String, Int>()
        for (name in names) {
            val left = name.lastIndexOf('[')
            val index = if (name.endsWith("]") && left != -1) parseUnsignedInt(name.substring(left + 1, name.length - 1)) else null
            if (index == null) { ports += BlifPort(name, direction, checkedWidth(1)); continue }
            val prefix = name.substring(0, left)
            val existing = maxBits[prefix]
            maxBits[prefix] = if (existing != null && existing > index) existing else index
        }
        for ((prefix, last) in maxBits.entries.sortedBy { it.key }) {
            val width = last + 1
            ports += if (width == 1) BlifPort("$prefix[0]", direction, 1) else BlifPort(prefix, direction, checkedWidth(width))
        }
        return ports
    }

    private fun checkedWidth(width: Int): Int = when {
        width < 0 -> throw BlifParseException("width $width must be positive")
        width > 64 -> throw BlifParseException("width $width must be at most 64")
        width == 0 -> throw BlifParseException("width 0 is not supported")
        else -> width
    }

    /** Java's Integer.parseUnsignedInt for ASCII digits: null where it throws, wrapped above 2^31 - 1. */
    private fun parseUnsignedInt(text: String): Int? {
        val digits = text.removePrefix("+")
        if (digits.isEmpty() || digits.length < text.length - 1 || digits.any { it !in '0'..'9' }) return null
        val value = digits.trimStart('0').ifEmpty { "0" }.takeIf { it.length <= 10 }?.toLong() ?: return null
        return if (value > 0xFFFF_FFFFL) null else value.toInt()
    }

    /** String.split(String) in Java for a one-character literal: trailing empty strings are removed. */
    internal fun javaSplit(text: String, separator: Char): List<String> {
        if (text.isEmpty()) return listOf("")
        val parts = text.split(separator).toMutableList()
        while (parts.size > 1 && parts.last().isEmpty()) parts.removeAt(parts.lastIndex)
        if (parts.size == 1 && parts[0].isEmpty()) return emptyList()
        return parts
    }

    /** String.trim() in Java removes every character up to U+0020. */
    internal fun javaTrim(text: String): String = text.trim { it <= ' ' }
}

class DenseLogicCircuit internal constructor(
    private val cellPull: ByteArray,
    private val cellUpdateNotifiesGate: Array<IntArray>,
    private val gateTypes: ByteArray,
    private val gateCellA: IntArray,
    private val gateCellB: IntArray,
    private val gateCellO: IntArray,
    private val sequentialScript: IntArray,
    sequentialDataSize: Int,
    val symbolTable: Map<String, Int>,
) {
    val cellCount: Int = cellPull.size
    val gateCount: Int = gateTypes.size
    private val updateQueueSize = gateCount + 1
    private val auxDataSize = updateQueueSize + sequentialDataSize

    fun newCells(): ByteArray = cellPull.copyOf()

    fun newAuxData(): IntArray {
        val data = IntArray(auxDataSize)
        if (gateCount != 0) {
            for (i in 0 until gateCount - 1) data[i] = i + 1
            data[gateCount - 1] = -1
            data[gateCount] = 0
        } else {
            data[gateCount] = -1
        }
        return data
    }

    fun setCell(cell: Int, value: Int, cells: ByteArray, aux: IntArray) {
        if (cells[cell].toInt() == value) return
        cells[cell] = value.toByte()
        markUpdate(cell, aux)
    }

    fun simulate(cells: ByteArray, aux: IntArray) {
        simulateCombinatorial(cells, aux)
        simulateSequential(cells, aux)
        simulateCombinatorial(cells, aux)
    }

    private fun markUpdate(cell: Int, aux: IntArray) {
        for (gate in cellUpdateNotifiesGate[cell]) {
            if (aux[gate] == -1) {
                aux[gate] = aux[gateCount]
                aux[gateCount] = gate
            }
        }
    }

    private fun simulateCombinatorial(cells: ByteArray, aux: IntArray) {
        var remaining = gateCount.toLong() * gateCount
        while (remaining > 0) {
            remaining--
            val gate = aux[gateCount]
            if (gate == -1) break
            aux[gateCount] = aux[gate]
            aux[gate] = -1
            val a = cells[gateCellA[gate]].toInt()
            val b = cells[gateCellB[gate]].toInt()
            var out = when (gateTypes[gate].toInt()) {
                GATE_BUS -> a or b
                GATE_TRIS -> if (b == LEV_HIGH) a else LEV_NONE
                GATE_TRISI -> if (b != LEV_HIGH) a else LEV_NONE
                GATE_AND -> level(a == LEV_HIGH && b == LEV_HIGH)
                GATE_OR -> level(a == LEV_HIGH || b == LEV_HIGH)
                GATE_XOR -> level((a == LEV_HIGH) xor (b == LEV_HIGH))
                GATE_NAND -> level(!(a == LEV_HIGH && b == LEV_HIGH))
                GATE_NOR -> level(!(a == LEV_HIGH || b == LEV_HIGH))
                GATE_NXOR -> level(!((a == LEV_HIGH) xor (b == LEV_HIGH)))
                GATE_ANDNOT -> level(a == LEV_HIGH && b != LEV_HIGH)
                GATE_ORNOT -> level(a == LEV_HIGH || b != LEV_HIGH)
                else -> throw IllegalStateException("invalid gate opcode ${gateTypes[gate]}")
            }
            val outCell = gateCellO[gate]
            if (out == LEV_NONE) out = cellPull[outCell].toInt()
            if (out != cells[outCell].toInt()) {
                cells[outCell] = out.toByte()
                markUpdate(outCell, aux)
            }
        }
    }

    private fun simulateSequential(cells: ByteArray, aux: IntArray) {
        var ptr = 0
        val script = sequentialScript
        while (ptr < script.size) {
            when (val op = script[ptr++]) {
                SQOP_DFF -> {
                    val d = script[ptr++]
                    val c = script[ptr++]
                    val q = script[ptr++]
                    val x = updateQueueSize + script[ptr++]
                    val old = aux[x]
                    val new = cells[c].toInt()
                    aux[x] = new
                    val source = if (new == LEV_HIGH && new != old) d else q
                    store(q, cells[source].toInt(), cells, aux)
                }
                SQOP_LATCH -> {
                    val d = script[ptr++]
                    val e = script[ptr++]
                    val q = script[ptr++]
                    if (cells[e].toInt() == LEV_HIGH) store(q, cells[d].toInt(), cells, aux)
                }
                else -> throw IllegalStateException("invalid seq. opcode $op")
            }
        }
    }

    private fun store(q: Int, source: Int, cells: ByteArray, aux: IntArray) {
        val value = if (source == LEV_HIGH) LEV_HIGH else LEV_LOW
        if (cells[q].toInt() != value) {
            cells[q] = value.toByte()
            markUpdate(q, aux)
        }
    }

    companion object {
        const val LEV_NONE = 0
        const val LEV_LOW = 1
        const val LEV_HIGH = 2
        const val LEV_ERR = 3
        const val LEV_COUNT = 4
        const val GATE_BUS = 0
        const val GATE_TRIS = 1
        const val GATE_TRISI = 2
        const val GATE_AND = 3
        const val GATE_OR = 4
        const val GATE_XOR = 5
        const val GATE_NAND = 6
        const val GATE_NOR = 7
        const val GATE_NXOR = 8
        const val GATE_ANDNOT = 9
        const val GATE_ORNOT = 10
        val GATE_TYPE_NAMES = listOf("BUS", "TRIS", "TRISI", "AND", "OR", "XOR", "NAND", "NOR", "NXOR", "ANDNOT", "ORNOT")
        const val SQOP_DFF = 0
        const val SQOP_LATCH = 1
        private fun level(high: Boolean) = if (high) LEV_HIGH else LEV_LOW

        fun levelOf(value: LogicValue): Int = when (value) {
            LogicValue.LOW -> LEV_LOW
            LogicValue.HIGH -> LEV_HIGH
            LogicValue.ERROR -> LEV_ERR
            LogicValue.UNKNOWN -> LEV_NONE
        }

        fun valueOf(level: Int): LogicValue = when (level) {
            LEV_LOW -> LogicValue.LOW
            LEV_HIGH -> LogicValue.HIGH
            LEV_ERR -> LogicValue.ERROR
            else -> LogicValue.UNKNOWN
        }
    }
}

internal class DenseLogicCircuitBuilder {
    private inner class CellInfo(val index: Int, val externallyDriven: Boolean) {
        var currentDrivingGate: GateInfo? = null
        var pull: Int = DenseLogicCircuit.LEV_NONE
        val notifiesTheseGates = mutableSetOf<Int>()
    }

    private inner class GateInfo(val index: Int, val type: Int, var pinA: CellInfo, var pinB: CellInfo) {
        var pinOutput: CellInfo? = null

        init {
            pinA.notifiesTheseGates += index
            pinB.notifiesTheseGates += index
        }

        fun replacePinB(cell: CellInfo) {
            if (pinA !== pinB) pinB.notifiesTheseGates -= index
            pinB = cell
            pinB.notifiesTheseGates += index
        }

        fun setOutput(cell: CellInfo?): GateInfo {
            pinOutput?.let { it.currentDrivingGate = null; pinOutput = null }
            if (cell != null) {
                if (cell.externallyDriven) throw BlifParseException("Cannot set driving gate for an externally driven cell!")
                if (cell.currentDrivingGate != null) throw BlifParseException("setOutput does not insert bus gates automatically!")
                cell.currentDrivingGate = this
            }
            pinOutput = cell
            return this
        }
    }

    private val gates = mutableListOf<GateInfo>()
    private val cells = mutableListOf<CellInfo>()
    private val sequentialScript = mutableListOf<Int>()
    private var sequentialDataSize = 0
    val symbolTable = HashMap<String, Int>()

    init {
        for (level in 0 until DenseLogicCircuit.LEV_COUNT) cells += CellInfo(level, true).also { it.pull = level }
    }

    fun attachGate(type: Int, a: Int, b: Int, o: Int) {
        val out = cells[o]
        val old = out.currentDrivingGate
        val buffer = a == b && type == DenseLogicCircuit.GATE_BUS
        if (old == null) {
            addGate(type, cells[a], cells[b]).setOutput(out)
        } else if (old.pinA === old.pinB && old.type == DenseLogicCircuit.GATE_BUS) {
            if (buffer) old.replacePinB(cells[b]) else {
                val newOut = addCellInternal(false)
                addGate(type, cells[a], cells[b]).setOutput(newOut)
                old.replacePinB(newOut)
            }
        } else {
            val oldOut = addCellInternal(false)
            old.setOutput(oldOut)
            if (buffer) {
                addGate(DenseLogicCircuit.GATE_BUS, oldOut, cells[a]).setOutput(out)
            } else {
                val newOut = addCellInternal(false)
                addGate(type, cells[a], cells[b]).setOutput(newOut)
                addGate(DenseLogicCircuit.GATE_BUS, oldOut, newOut).setOutput(out)
            }
        }
    }

    fun attachBuffer(from: Int, to: Int) = attachGate(DenseLogicCircuit.GATE_BUS, from, from, to)

    fun addCell(external: Boolean): Int = addCellInternal(external).index

    fun setCellPull(cell: Int, pull: Int) { cells[cell].pull = pull }

    fun addDff(c: Int, d: Int): Int {
        val q = addCellInternal(true).index
        val x = sequentialDataSize++
        sequentialScript += listOf(DenseLogicCircuit.SQOP_DFF, d, c, q, x)
        setCellPull(q, DenseLogicCircuit.LEV_LOW)
        return q
    }

    fun addDffsr(c: Int, d: Int, s: Int, r: Int): Int {
        val q = addCellInternal(true).index
        val x = sequentialDataSize++
        sequentialScript += listOf(DenseLogicCircuit.SQOP_DFF, d, c, q, x,
            DenseLogicCircuit.SQOP_LATCH, DenseLogicCircuit.LEV_HIGH, s, q,
            DenseLogicCircuit.SQOP_LATCH, DenseLogicCircuit.LEV_LOW, r, q)
        setCellPull(q, DenseLogicCircuit.LEV_LOW)
        return q
    }

    fun addLatch(d: Int, e: Int): Int {
        val q = addCellInternal(true).index
        sequentialScript += listOf(DenseLogicCircuit.SQOP_LATCH, d, e, q)
        setCellPull(q, DenseLogicCircuit.LEV_LOW)
        return q
    }

    fun build(): DenseLogicCircuit = DenseLogicCircuit(
        ByteArray(cells.size) { cells[it].pull.toByte() },
        Array(cells.size) { cells[it].notifiesTheseGates.sorted().toIntArray() },
        ByteArray(gates.size) { gates[it].type.toByte() },
        IntArray(gates.size) { gates[it].pinA.index },
        IntArray(gates.size) { gates[it].pinB.index },
        IntArray(gates.size) { checkNotNull(gates[it].pinOutput).index },
        sequentialScript.toIntArray(),
        sequentialDataSize,
        symbolTable.toMap(),
    )

    private fun addCellInternal(external: Boolean): CellInfo = CellInfo(cells.size, external).also { cells += it }

    private fun addGate(type: Int, a: CellInfo, b: CellInfo): GateInfo = GateInfo(gates.size, type, a, b).also { gates += it }
}

/** Cells and update queue of one BLIF instance, as Evolution's BlifCircuitState. */
class BlifCircuitState internal constructor(
    val source: String,
    internal val compilation: BlifCompilation?,
    internal val cells: ByteArray,
    internal val aux: IntArray,
    /** Inputs of the last propagation; null before the first one. */
    internal val inputs: Map<String, LogicVector>? = null,
    internal val outputs: Map<String, LogicVector> = emptyMap(),
    /** Time at which the outputs set by the last propagation reach the ports and propagate the component again. */
    internal val outputsLandAt: Long? = null,
) : ComponentState {
    override fun equals(other: Any?): Boolean = other is BlifCircuitState && source == other.source &&
        compilation === other.compilation && cells.contentEquals(other.cells) && aux.contentEquals(other.aux) &&
        inputs == other.inputs && outputs == other.outputs && outputsLandAt == other.outputsLandAt

    override fun hashCode(): Int = (source.hashCode() * 31 + cells.contentHashCode()) * 31 + aux.contentHashCode()

    companion object {
        internal fun fresh(source: String, compilation: BlifCompilation?) = BlifCircuitState(source, compilation,
            compilation?.circuit?.newCells() ?: ByteArray(0), compilation?.circuit?.newAuxData() ?: IntArray(0))
    }
}

/** Native BLIF simulation, as BlifCircuitComponent.propagate in Evolution. No companion is involved. */
object BlifCircuitBehavior : ComponentBehavior {
    private var cache: Pair<String, Result<BlifCompilation>>? = null

    /** Compiles [source] once per distinct text; the compiled circuit is immutable. */
    fun compile(source: String): Result<BlifCompilation> {
        cache?.let { (key, value) -> if (key == source) return value }
        val result = runCatching { BlifParser.compile(source) }
            .onFailure { if (it !is BlifParseException) throw it }
        cache = source to result
        return result
    }

    internal fun source(component: CircuitComponent): String = component.attributes["content"] ?: HdlIpTemplates.BLIF

    private val template: BlifCompilation by lazy { BlifParser.compile(HdlIpTemplates.BLIF) }

    /** The circuit Evolution simulates for [source]: its own, or the bundled template when it does not parse. */
    internal fun compilation(source: String): BlifCompilation =
        (if (HdlIpDeclarations.blifAccepted(source)) compile(source).getOrNull() else null) ?: template

    override fun initialState(component: CircuitComponent, attributes: AttributeValues): ComponentState {
        val source = source(component)
        return BlifCircuitState.fresh(source, compilation(source))
    }

    override fun evaluate(context: ComponentEvaluationContext): ComponentEvaluation {
        val source = source(context.component)
        val previous = context.state as? BlifCircuitState
        val state = if (previous != null && previous.source == source) previous
            else initialState(context.component, context.attributes) as BlifCircuitState
        val design = HdlIpLibrary.parse(context.component)
        val compiled = checkNotNull(state.compilation)
        val notices = design.diagnostics.map { message(Messages.HdlIpDiagnostic, "code" to it.code, "detail" to it.notice) }
        if (design.diagnostics.any { it.code == "duplicate-port" }) {
            return ComponentEvaluation(outputs = emptyMap(), state = state,
                diagnostics = notices.map { it.resolve(AppLanguage.ENGLISH) }, notices = notices)
        }
        val inputs = compiled.inputs.associate { port -> port.name to context.inputs[port.name] }
            .filterValues { it != null }.mapValues { it.value!! }
        // Evolution propagates a component when a value at one of its ports changes. The Mobile simulator
        // evaluates every round, so a propagation runs only for new inputs or when the outputs of the last
        // one land. Without this, a BLIF whose update queue never empties would advance on every round.
        val due = state.inputs == null || state.inputs != inputs ||
            state.outputsLandAt != null && context.eventTime >= state.outputsLandAt
        if (!due) return ComponentEvaluation(outputs = state.outputs, state = state, repeatedOutputs = emptySet(),
            diagnostics = notices.map { it.resolve(AppLanguage.ENGLISH) }, notices = notices)
        val cells = state.cells.copyOf()
        val aux = state.aux.copyOf()
        val circuit = compiled.circuit
        compiled.inputs.forEachIndexed { index, port ->
            val value = inputs[port.name]
            val pins = compiled.inputCells[index]
            for (bit in 0 until port.width) {
                val cell = pins[bit]
                if (cell == -1) continue
                val level = if (value == null || bit >= value.width) DenseLogicCircuit.LEV_NONE
                    else DenseLogicCircuit.levelOf(value.bit(bit))
                circuit.setCell(cell, level, cells, aux)
            }
        }
        circuit.simulate(cells, aux)
        val outputs = compiled.outputs.withIndex().associate { (index, port) ->
            val pins = compiled.outputCells[index]
            port.name to LogicVector.fromBits(List(port.width) { bit ->
                if (pins[bit] == -1) LogicValue.UNKNOWN else DenseLogicCircuit.valueOf(cells[pins[bit]].toInt())
            })
        }
        val landing = if (state.inputs != null && outputs == state.outputs) null else context.eventTime + 1
        return ComponentEvaluation(outputs = outputs,
            state = BlifCircuitState(source, compiled, cells, aux, inputs, outputs, landing),
            diagnostics = notices.map { it.resolve(AppLanguage.ENGLISH) }, notices = notices)
    }
}
