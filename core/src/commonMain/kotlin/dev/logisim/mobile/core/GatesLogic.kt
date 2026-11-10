package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.*

enum class GateOperation {
    AND, OR, NAND, NOR, XOR, XNOR, ODD_PARITY, EVEN_PARITY,
    BUFFER, NOT, CONTROLLED_BUFFER, CONTROLLED_INVERTER,
}

enum class GateOutputMode { NORMAL, LOW_OR_FLOATING, FLOATING_OR_HIGH }

data class GateConfiguration(
    val width: Int = 1,
    val inputCount: Int = 2,
    val negatedInputs: Set<Int> = emptySet(),
    val outputMode: GateOutputMode = GateOutputMode.NORMAL,
    val xorOddParity: Boolean = false,
    val undefinedIsError: Boolean = false,
) {
    init {
        require(width in 1..64)
        require(inputCount in 2..64)
        require(negatedInputs.all { it in 0 until inputCount })
    }
}

/** Gate inputs are in desktop port order; null denotes a physically disconnected pin. */
object GatesLogic {
    fun evaluate(
        operation: GateOperation,
        configuration: GateConfiguration,
        inputs: List<LogicVector?>,
    ): LogicVector {
        val width = configuration.width
        fun input(index: Int, bits: Int = width) = inputs.getOrNull(index)
            ?.takeIf { it.width == bits } ?: LogicVector.unknown(bits)
        val result = when (operation) {
            GateOperation.BUFFER, GateOperation.NOT -> {
                val value = if (operation == GateOperation.NOT) input(0).inverted() else input(0)
                // Desktop Buffer.repair returns a fully defined strict-mode value before applying out.
                if (configuration.undefinedIsError && value.isFullyDefined) return value
                repair(value, configuration.undefinedIsError)
            }
            GateOperation.CONTROLLED_BUFFER, GateOperation.CONTROLLED_INVERTER -> {
                return when (input(1, 1).bit(0)) {
                    LogicValue.HIGH -> if (operation == GateOperation.CONTROLLED_INVERTER)
                        input(0).inverted() else input(0)
                    LogicValue.ERROR -> LogicVector.error(width)
                    LogicValue.LOW -> LogicVector.unknown(width)
                    LogicValue.UNKNOWN -> if (configuration.undefinedIsError)
                        LogicVector.error(width) else LogicVector.unknown(width)
                }
            }
            else -> {
                if (configuration.undefinedIsError && (0 until configuration.inputCount).any {
                        inputs.getOrNull(it) == null
                    }) return LogicVector.error(width)
                val connected = (0 until configuration.inputCount).mapNotNull { index ->
                    inputs.getOrNull(index)?.let {
                        val value = if (it.width == width) it else LogicVector.error(width)
                        if (index in configuration.negatedInputs) value.inverted() else value
                    }
                }
                if (connected.isEmpty()) return LogicVector.error(width)
                when (operation) {
                    GateOperation.AND, GateOperation.NAND -> connected.reduce { a, b -> a and b }
                        .let { if (operation == GateOperation.NAND) it.inverted() else it }
                    GateOperation.OR, GateOperation.NOR -> connected.reduce { a, b -> a or b }
                        .let { if (operation == GateOperation.NOR) it.inverted() else it }
                    GateOperation.XOR, GateOperation.XNOR -> {
                        val value = if (configuration.xorOddParity)
                            connected.reduce { a, b -> a xor b } else exactlyOne(connected, width)
                        if (operation == GateOperation.XNOR) value.inverted() else value
                    }
                    GateOperation.ODD_PARITY, GateOperation.EVEN_PARITY -> connected.reduce { a, b -> a xor b }
                        .let { if (operation == GateOperation.EVEN_PARITY) it.inverted() else it }
                }
            }
        }
        return pullOutput(result, configuration.outputMode)
    }

    private fun exactlyOne(inputs: List<LogicVector>, width: Int): LogicVector =
        LogicVector.fromBits((0 until width).map { index ->
            val values = inputs.map { it.bit(index) }
            when {
                values.any { it == LogicValue.UNKNOWN || it == LogicValue.ERROR } -> LogicValue.ERROR
                values.count { it == LogicValue.HIGH } == 1 -> LogicValue.HIGH
                else -> LogicValue.LOW
            }
        })

    private fun repair(value: LogicVector, errorIfUndefined: Boolean): LogicVector =
        if (!errorIfUndefined) value else LogicVector.fromBits((0 until value.width).map {
            when (val bit = value.bit(it)) {
                LogicValue.UNKNOWN -> LogicValue.ERROR
                else -> bit
            }
        })

    fun pullOutput(value: LogicVector, mode: GateOutputMode): LogicVector =
        if (mode == GateOutputMode.NORMAL) value else LogicVector.fromBits((0 until value.width).map {
            when (val bit = value.bit(it)) {
                LogicValue.HIGH -> if (mode == GateOutputMode.LOW_OR_FLOATING) LogicValue.UNKNOWN else bit
                LogicValue.LOW -> if (mode == GateOutputMode.FLOATING_OR_HIGH) LogicValue.UNKNOWN else bit
                else -> bit
            }
        })
}

data class PlaRow(val input: String, val output: String, val comment: String = "")

/** Logisim PLA rows are priority ordered, with the first matching row supplying the output. */
data class PlaProgram(
    val inputWidth: Int,
    val outputWidth: Int,
    val rows: List<PlaRow>,
    val notices: List<LocalizedText> = emptyList(),
) {
    val diagnostics: List<String> get() = notices.map { it.resolve(AppLanguage.ENGLISH) }

    init {
        require(inputWidth in 1..64 && outputWidth in 1..64)
        require(rows.all { it.input.length == inputWidth && it.output.length == outputWidth })
    }

    fun evaluate(input: LogicVector): LogicVector {
        // Value.toLongValue() returns -1 when any bit is unknown or an error.
        val bits = input.toULongOrNull() ?: ULong.MAX_VALUE
        val row = rows.firstOrNull { row ->
            row.input.reversed().withIndex().all { (index, expected) ->
                expected == 'x' || ((bits shr index) and 1uL == 1uL) == (expected == '1')
            }
        }
        var result = 0uL
        row?.output?.reversed()?.forEachIndexed { index, bit -> if (bit == '1') result = result or (1uL shl index) }
        return LogicVector.of(outputWidth, result)
    }

    fun resized(inputWidth: Int, outputWidth: Int): PlaProgram = PlaProgram(
        inputWidth, outputWidth, rows.map {
            it.copy(input = it.input.takeLast(inputWidth).padStart(inputWidth, '0'),
                output = it.output.takeLast(outputWidth).padStart(outputWidth, '0'))
        }, notices,
    )

    fun serialize(): String = rows.joinToString(separator = "\n", postfix = if (rows.isEmpty()) "" else "\n") {
        "${it.input} ${it.output}" + if (it.comment.isBlank()) "" else " # ${it.comment.trim()}"
    }

    companion object {
        fun parse(text: String): PlaProgram {
            var inputWidth = 0
            var outputWidth = 0
            val rows = mutableListOf<PlaRow>()
            val errors = mutableListOf<LocalizedText>()
            for ((index, line) in text.replace("\\n", "\n").lineSequence().withIndex()) {
                val content = line.substringBefore('#').trim()
                val fields = when {
                    "||" in content -> content.split("||", limit = 2).map { it.replace(Regex("[|\\s]"), "") }
                    content.matches(Regex("[01x]+\\s+[01]+")) -> content.split(Regex("\\s+"), limit = 2)
                    else -> continue
                }
                val a = fields[0]
                val b = fields[1]
                if (a.isEmpty() && b.isEmpty()) continue
                if (a.length !in 1..64 || b.length !in 1..64 || a.any { it !in "01x" } || b.any { it !in "01x" }) {
                    errors += message(Messages.GatesPlaRowInvalid, "row" to (index + 1))
                    continue
                }
                if (inputWidth == 0) {
                    inputWidth = a.length
                    outputWidth = b.length
                }
                if (a.length != inputWidth || b.length != outputWidth) {
                    errors += message(Messages.GatesPlaRowWidth, "row" to (index + 1))
                    continue
                }
                rows += PlaRow(a, b, if ('#' in line) line.substringAfter('#').trim() else "")
            }
            return PlaProgram(if (inputWidth == 0) 2 else inputWidth,
                if (outputWidth == 0) 2 else outputWidth, rows, errors)
        }
    }
}

/** Evolution `PlaTable.save` and `PlaTable.parse(File)`: the PLA editor's Export and Import text file. */
object PlaTableFile {
    const val HEADER = "# Logisim PLA program table\n"
    const val MIME_TYPE = "text/plain"
    const val MAX_BYTES = 4 * 1024 * 1024

    /** `PlaTable.EditorDialog.normalizeName`; its replacements are literal, so only blank names change. */
    fun fileName(label: String?): String {
        val name = label?.trim().orEmpty()
        return if (name.isEmpty() || name == "_") "pla.txt" else "$name.txt"
    }

    fun write(program: PlaProgram): String = HEADER + program.serialize()

    /**
     * Reads a saved table for a PLA with [inputWidth] x [outputWidth], like the editor's Import. The first
     * error stops the import, as in Evolution.
     */
    fun read(bytes: ByteArray, inputWidth: Int, outputWidth: Int): PlaProgram {
        if (bytes.size > MAX_BYTES) throw LocalizedArgumentException(message("pla_file_open_error"))
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw LocalizedArgumentException(message("pla_file_open_error"))
        }
        return read(text, inputWidth, outputWidth)
    }

    fun read(text: String, inputWidth: Int, outputWidth: Int): PlaProgram {
        var widths: Pair<Int, Int>? = null
        val rows = mutableListOf<PlaRow>()
        for (line in text.lineSequence()) {
            val io = line.substringBefore('#').let { if ('#' in line) it.trim() else it }
            val compact = io.matches(Regex("[01x]+\\s+[01]+"))
            val (inputs, outputs) = when {
                // Evolution splits at the first space; a tab-only separator reads as a comment line.
                compact -> if (' ' in io) io.substringBefore(' ').trim() to io.substringAfter(' ').trim() else continue
                "||" in io -> io.substringBefore("||").replace(Regex("[|\\s]"), "") to
                    io.substring(io.indexOf("||") + 1).replace(Regex("[|\\s]"), "")
                else -> continue
            }
            if (inputs.isEmpty() && outputs.isEmpty()) continue
            val current = widths
            if (current == null) widths = inputs.length to outputs.length
            else if (inputs.length != current.first)
                throw LocalizedArgumentException(message("pla_row_exact_in_bits", "row" to line, "count" to current.first))
            else if (outputs.length != current.second)
                throw LocalizedArgumentException(message("pla_row_exact_out_bits", "row" to line, "count" to current.second))
            inputs.firstOrNull { it !in "01x" }?.let {
                throw LocalizedArgumentException(message("pla_invalid_input_bit", "row" to inputs, "bit" to it.toString()))
            }
            outputs.firstOrNull { it !in "01x" }?.let {
                throw LocalizedArgumentException(message("pla_invalid_output_bit", "row" to outputs, "bit" to it.toString()))
            }
            rows += PlaRow(inputs, outputs, if ('#' in line) line.substringAfter('#').trim() else "")
        }
        val (foundInputs, foundOutputs) = widths ?: throw LocalizedArgumentException(message("pla_file_no_data"))
        if (foundInputs != inputWidth) throw LocalizedArgumentException(
            message("pla_unexpected_input_width", "expected" to inputWidth, "found" to foundInputs))
        if (foundOutputs != outputWidth) throw LocalizedArgumentException(
            message("pla_unexpected_output_width", "expected" to outputWidth, "found" to foundOutputs))
        return PlaProgram(inputWidth, outputWidth, rows)
    }
}
