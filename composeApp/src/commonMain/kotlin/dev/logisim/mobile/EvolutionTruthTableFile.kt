package dev.logisim.mobile

import dev.logisim.mobile.core.AnalysisCancelledException
import dev.logisim.mobile.core.AnalysisLimits
import dev.logisim.mobile.core.PagedTruthTable
import dev.logisim.mobile.core.TruthCell
import dev.logisim.mobile.core.TruthTable
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import kotlin.text.CharacterCodingException

internal enum class EvolutionTableFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"),
    TXT("txt", "text/plain"),
}

internal const val MAX_EVOLUTION_TABLE_FILE_BYTES = 8 * 1024 * 1024

internal class EvolutionTruthTableFile private constructor(
    val table: TruthTable,
    private val originalRows: List<FileRow>,
) {
    fun export(current: TruthTable, format: EvolutionTableFormat, cancelled: () -> Boolean = { false }): DocumentAsset {
        val encoder = EvolutionTableEncoder(current.inputs, current.outputs, format)
        val rows = when {
            originalRows.isEmpty() || current.inputs != table.inputs ->
                current.rows.indices.asSequence().map { current.fileRow(it) }
            current.rows === table.rows -> originalRows.asSequence()
            else -> originalRows.asSequence().flatMap { row ->
                val covered = buildList { row.forEachAssignment { add(it) } }
                val outputsForRow = current.rows[covered.first()]
                if (covered.all { current.rows[it] == outputsForRow }) {
                    sequenceOf(row.copy(outputs = outputsForRow))
                } else covered.asSequence().map { current.fileRow(it) }
            }
        }
        val text = buildString {
            append(encoder.header())
            for (row in rows) {
                if (cancelled()) throw AnalysisCancelledException()
                encoder.appendRow(this, row.inputs, row.outputs)
                if (length > MAX_EVOLUTION_TABLE_FILE_BYTES) invalidFile()
            }
        }
        return DocumentAsset(encoder.name, encoder.mimeType, text.encodeToByteArray())
    }

    companion object {
        fun read(
            asset: DocumentAsset,
            limits: AnalysisLimits = AnalysisLimits(),
            cancelled: () -> Boolean = { false },
        ): EvolutionTruthTableFile {
            if (asset.size !in 1..MAX_EVOLUTION_TABLE_FILE_BYTES) invalidFile()
            val text = try {
                asset.bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                invalidFile()
            }
            return readEditable(EvolutionTableRecords(asset.name, text.lineSequence(), limits, cancelled), limits, cancelled)
        }

        fun write(table: TruthTable, format: EvolutionTableFormat, cancelled: () -> Boolean = { false }): DocumentAsset =
            EvolutionTruthTableFile(table, emptyList()).export(table, format, cancelled)

        fun importLines(
            name: String,
            lines: Sequence<String>,
            openSpool: (recordSize: Int) -> AnalysisTableSpool,
            cancelled: () -> Boolean = { false },
        ): ImportedAnalysisTable {
            val editableLimits = AnalysisLimits()
            val records = EvolutionTableRecords(name, lines, editableLimits.copy(maxInputBits = 20), cancelled)
            return if (records.inputs.size <= editableLimits.maxInputBits) {
                ImportedAnalysisTable.Editable(readEditable(records, editableLimits, cancelled))
            } else ImportedAnalysisTable.Paged(readPaged(records, openSpool, cancelled))
        }

        private fun readEditable(
            records: EvolutionTableRecords,
            limits: AnalysisLimits,
            cancelled: () -> Boolean,
        ): EvolutionTruthTableFile {
            val assignments = arrayOfNulls<List<TruthCell>>(1 shl records.inputs.size)
            val sourceRows = mutableListOf<FileRow>()
            var work = 0
            for ((line, row) in records.rows()) {
                row.forEachAssignment { assignment ->
                    if (cancelled()) throw AnalysisCancelledException()
                    if (++work > limits.maxSearchSteps || assignments[assignment] != null) invalidFile(line)
                    assignments[assignment] = row.outputs
                }
                sourceRows += row
            }
            if (sourceRows.isEmpty() || assignments.any { it == null }) invalidFile()
            return EvolutionTruthTableFile(TruthTable(records.inputs, records.outputs, assignments.map { checkNotNull(it) }), sourceRows)
        }

        private fun readPaged(
            records: EvolutionTableRecords,
            openSpool: (Int) -> AnalysisTableSpool,
            cancelled: () -> Boolean,
        ): ImportedPagedTruthTable {
            val rowIndex = IntArray(1 shl records.inputs.size) { -1 }
            val record = ByteArray((records.inputs.size + records.outputs.size + 3) / 4)
            val spool = openSpool(record.size)
            try {
                var recordCount = 0
                var covered = 0
                var overrides: IntArray? = null
                val firstRecord = ByteArray(record.size)
                var cachedFirstRecord = -1
                for ((line, row) in records.rows()) {
                    var checkedFirstRecord = -1
                    row.forEachAssignment { assignment ->
                        if (cancelled()) throw AnalysisCancelledException()
                        val first = rowIndex[assignment]
                        if (first == -1) {
                            rowIndex[assignment] = recordCount
                            covered++
                        } else {
                            // Evolution checks overlaps against the first covering row, then writes in source order.
                            if (first != checkedFirstRecord) {
                                if (first != cachedFirstRecord) {
                                    spool.read(first, firstRecord)
                                    cachedFirstRecord = first
                                }
                                if (row.outputs.withIndex().any { (column, value) ->
                                        val original = firstRecord.cellAt(records.inputs.size + column)
                                        original != TruthCell.DONT_CARE && value != TruthCell.DONT_CARE && original != value
                                    }) invalidFile(line)
                                checkedFirstRecord = first
                            }
                            val last = overrides ?: IntArray(rowIndex.size).also { overrides = it }
                            last[assignment] = recordCount
                        }
                    }
                    row.packInto(record)
                    spool.append(record)
                    recordCount++
                }
                if (covered != rowIndex.size) invalidFile()
                overrides?.forEachIndexed { assignment, last ->
                    if (cancelled()) throw AnalysisCancelledException()
                    if (last != 0) rowIndex[assignment] = last
                }
                if (cancelled()) throw AnalysisCancelledException()
                spool.seal()
                return ImportedPagedTruthTable(records.inputs, records.outputs, rowIndex, recordCount, spool)
            } catch (failure: Throwable) {
                runCatching(spool::close)
                throw failure
            }
        }
    }
}

private class EvolutionTableRecords(
    name: String,
    lines: Sequence<String>,
    limits: AnalysisLimits,
    cancelled: () -> Boolean,
) {
    private val format = EvolutionTableFormat.entries.singleOrNull { name.endsWith(".${it.extension}", true) }
        ?: invalidFile()
    private val fieldLimit = limits.maxInputBits + limits.maxOutputBits + 2
    private val tokens = lines.withIndex()
        .map { (index, content) ->
            val line = index + 1
            if (cancelled()) throw AnalysisCancelledException()
            val text = if (index == 0) content.removePrefix("\uFEFF") else content
            val fields = when (format) {
                EvolutionTableFormat.CSV -> if (text.isBlank()) emptyList() else csvFields(text, line, fieldLimit)
                EvolutionTableFormat.TXT -> textFields(text, fieldLimit)
            }
            if (fields.size == fieldLimit) invalidFile(line)
            line to fields
        }
        .filter { it.second.isNotEmpty() }
        .iterator()
    private val separator: Int
    private val fieldCount: Int
    private val inputVariables: List<FileVariable>
    private val outputVariables: List<FileVariable>
    val inputs: List<String>
    val outputs: List<String>

    init {
        if (!tokens.hasNext()) invalidFile()
        val (line, header) = tokens.next()
        fieldCount = header.size
        separator = header.indexOf("|")
        if (separator <= 0 || separator == header.lastIndex || header.lastIndexOf("|") != separator) invalidFile(line)
        inputVariables = readVariables(header.subList(0, separator), format, line, limits.maxInputBits)
        outputVariables = readVariables(header.subList(separator + 1, header.size), format, line, limits.maxOutputBits)
        if ((inputVariables + outputVariables).map { it.name.lowercase() }.distinct().size != inputVariables.size + outputVariables.size) {
            invalidFile(line)
        }
        inputs = inputVariables.flatMap { it.bitNames() }
        outputs = outputVariables.flatMap { it.bitNames() }
    }

    fun rows(): Sequence<Pair<Int, FileRow>> = tokens.asSequence().map { (line, fields) ->
        if (fields.size != fieldCount) invalidFile(line)
        line to when (format) {
            EvolutionTableFormat.CSV -> FileRow(
                fields.subList(0, separator).map { bit(it.singleOrNull(), line) },
                fields.subList(separator + 1, fields.size).map { bit(it.singleOrNull(), line) },
            )
            EvolutionTableFormat.TXT -> {
                if (fields[separator] != "|") invalidFile(line)
                FileRow(
                    inputVariables.flatMapIndexed { index, variable -> readTextBits(fields[index], variable.width, line) },
                    outputVariables.flatMapIndexed { index, variable ->
                        readTextBits(fields[separator + 1 + index], variable.width, line)
                    },
                )
            }
        }
    }
}

internal sealed interface ImportedAnalysisTable {
    data class Editable(val file: EvolutionTruthTableFile) : ImportedAnalysisTable
    data class Paged(val table: ImportedPagedTruthTable) : ImportedAnalysisTable
}

internal class ImportedPagedTruthTable(
    override val inputs: List<String>,
    override val outputs: List<String>,
    private val rowIndex: IntArray,
    val recordCount: Int,
    private val spool: AnalysisTableSpool,
) : PagedTruthTable {
    override fun readRows(firstRow: Int, count: Int, cancelled: () -> Boolean): List<List<TruthCell>> {
        require(firstRow in 0..rowCount && count in 0..(rowCount - firstRow))
        val packed = ByteArray((inputs.size + outputs.size + 3) / 4)
        var previous = -1
        var output = emptyList<TruthCell>()
        return List(count) { offset ->
            if (cancelled()) throw AnalysisCancelledException()
            val record = rowIndex[firstRow + offset]
            if (record != previous) {
                spool.read(record, packed)
                output = List(outputs.size) { packed.cellAt(inputs.size + it) }
                previous = record
            }
            output
        }
    }

    fun appendRecords(
        target: StringBuilder,
        encoder: EvolutionTableEncoder,
        firstRecord: Int,
        count: Int,
        cancelled: () -> Boolean,
    ) {
        require(firstRecord in 0..recordCount && count in 0..(recordCount - firstRecord))
        val packed = ByteArray((inputs.size + outputs.size + 3) / 4)
        for (record in firstRecord until firstRecord + count) {
            if (cancelled()) throw AnalysisCancelledException()
            spool.read(record, packed)
            encoder.appendRow(target, List(inputs.size) { packed.cellAt(it) }, List(outputs.size) { packed.cellAt(inputs.size + it) })
        }
    }

    fun close() = spool.close()
}

private fun ByteArray.cellAt(index: Int): TruthCell = TruthCell.entries[(this[index / 4].toInt() ushr (index % 4 * 2)) and 3]

private data class FileVariable(val name: String, val width: Int) {
    fun header(): String = if (width == 1) name else "$name[${width - 1}..0]"
    fun bitNames(): List<String> = if (width == 1) listOf(name) else (width - 1 downTo 0).map { "$name[$it]" }
}

private data class FileRow(val inputs: List<TruthCell>, val outputs: List<TruthCell>) {
    fun packInto(record: ByteArray) {
        record.fill(0)
        for (index in 0 until inputs.size + outputs.size) {
            val cell = if (index < inputs.size) inputs[index] else outputs[index - inputs.size]
            val offset = index / 4
            record[offset] = (record[offset].toInt() or (cell.ordinal shl (index % 4 * 2))).toByte()
        }
    }

    fun forEachAssignment(visit: (Int) -> Unit) {
        var base = 0
        var wildcards = 0
        for ((index, bit) in inputs.withIndex()) {
            val flag = 1 shl (inputs.lastIndex - index)
            when (bit) {
                TruthCell.ZERO -> Unit
                TruthCell.ONE -> base = base or flag
                TruthCell.DONT_CARE -> wildcards = wildcards or flag
            }
        }
        var value = 0
        while (true) {
            visit(base or value)
            if (value == wildcards) break
            value = (value - wildcards) and wildcards
        }
    }
}

private fun TruthTable.fileRow(row: Int) = FileRow(inputs.indices.map { bit ->
    if (input(row, bit)) TruthCell.ONE else TruthCell.ZERO
}, rows[row])

private val variableWidth = Regex("(.+)\\[([0-9]+)(\\.\\.0)?]")
private val bitName = Regex("(.+)\\[([0-9]+)]")
private val colonBitName = Regex("(.+):([0-9]+)")
private val textVariableName = Regex("[A-Za-z][A-Za-z0-9_]*")
private val textSeparator = Regex("[-~_=][- ~_=]*")
private val textWhitespace = Regex("\\s+")

// CSV import uses the HDL naming rules in Evolution's SyntaxChecker and CorrectLabel.
private val evolutionVhdlKeywords = """
    abs access after alias all and architecture array assert attribute begin block body buffer bus
    case component configuration constant disconnect downto else elsif end endcase endgenerate endif
    endprocess entity exit file for function generate generic group guarded if impure in inertial
    inout integer is label library linkage literal loop map mod nand new next nor not null of on
    open or others out package port postponed procedure process pure range record register reject
    rem report return rol ror select severity shared signal sla sll sra srl subtype then to
    transport type unaffected units until use variable wait when while with xnor xor
""".trim().split(textWhitespace).toSet()

private val evolutionVerilogKeywords = """
    always and assign automatic begin buf bufif0 bufif1 case casex casez cell cmos config deassign
    default defparam disable edge else end endcase endconfig endfunction endgenerate endmodule
    endprimitive endspecify endtable endtask event for force forever fork function generate genvar
    highz0 highz1 if ifnone incdir include initial inout input instance integer join large liblist
    library localparam macromodule medium module nand negedge nmos nor noshowcancelled not notif0
    notif1 or output parameter pmos posedge primitive pull0 pull1 pulldown pullup
    pulsestyle_ondetect pulsestyle_onevent rcmos real realtime reg release repeat rnmos rpmos rtran
    rtranif0 rtranif1 scalared showcancelled signed small specify specparam strong0 strong1 supply0
    supply1 table task time tran tranif0 tranif1 tri tri0 tri1 triand trior trireg unsigned use
    vectored wait wand weak0 weak1 while wire wor xnor xor
""".trim().split(textWhitespace).toSet()

private fun validName(name: String, format: EvolutionTableFormat): Boolean {
    if (!textVariableName.matches(name)) return false
    return format == EvolutionTableFormat.TXT ||
        ("__" !in name && !name.endsWith('_') && name.lowercase() !in evolutionVhdlKeywords &&
            name !in evolutionVerilogKeywords)
}

private fun readVariables(fields: List<String>, format: EvolutionTableFormat, line: Int, maximumBits: Int): List<FileVariable> {
    val variables = mutableListOf<FileVariable>()
    var fieldIndex = 0
    var usedBits = 0
    while (fieldIndex < fields.size) {
        val field = fields[fieldIndex]
        val colon = if (format == EvolutionTableFormat.CSV) colonBitName.matchEntire(field) else null
        val bracket = variableWidth.matchEntire(field)
        val variable = when {
            colon != null -> {
                val (name, highest) = colon.destructured
                val index = highest.toIntOrNull()?.takeIf { it in 0 until maximumBits } ?: invalidFile(line)
                if (index >= fields.size - fieldIndex || (0..index).any { offset ->
                        fields[fieldIndex + offset] != "$name:${index - offset}"
                    }) invalidFile(line)
                fieldIndex += index
                FileVariable(name, index + 1)
            }
            bracket != null -> {
                val (name, number, range) = bracket.destructured
                val index = number.toIntOrNull()?.takeIf { it in 0..maximumBits } ?: invalidFile(line)
                val width = if (range.isNotEmpty()) index + 1 else {
                    if (format != EvolutionTableFormat.CSV) invalidFile(line)
                    index
                }
                if (width !in 2..maximumBits) invalidFile(line)
                if (format == EvolutionTableFormat.CSV) {
                    if (width > fields.size - fieldIndex || (1 until width).any { fields[fieldIndex + it].isNotEmpty() }) {
                        invalidFile(line)
                    }
                    fieldIndex += width - 1
                }
                FileVariable(name, width)
            }
            else -> FileVariable(field, 1)
        }
        usedBits += variable.width
        if (usedBits > maximumBits || !validName(variable.name, format)) invalidFile(line)
        variables += variable
        fieldIndex++
    }
    return variables
}

private fun variables(names: List<String>, format: EvolutionTableFormat): List<FileVariable> {
    if (names.isEmpty()) invalidFile()
    val variables = mutableListOf<FileVariable>()
    var index = 0
    while (index < names.size) {
        val field = names[index]
        val match = bitName.matchEntire(field)
        val variable = if (match == null) FileVariable(field, 1) else {
            val (name, highest) = match.destructured
            val bit = highest.toIntOrNull()?.takeIf { it in 1 until names.size - index } ?: invalidFile()
            if ((0..bit).any { names[index + it] != "$name[${bit - it}]" }) invalidFile()
            index += bit
            FileVariable(name, bit + 1)
        }
        if (!validName(variable.name, format)) invalidFile()
        variables += variable
        index++
    }
    return variables
}

private fun csvFields(text: String, line: Int, limit: Int): List<String> = text.split(',', limit = limit).map { field ->
    val value = field.trim()
    if (value.startsWith('"') && value.endsWith('"') && value.length >= 2) {
        value.substring(1, value.lastIndex).also { if ('"' in it) invalidFile(line) }
    } else value.also { if ('"' in it) invalidFile(line) }
}

private fun textFields(text: String, limit: Int): List<String> {
    val content = text.substringBefore('#').trim()
    return if (content.isBlank() || textSeparator.matches(content)) emptyList() else content.split(textWhitespace, limit = limit)
}

private fun bit(value: Char?, line: Int): TruthCell = when (value) {
    '0' -> TruthCell.ZERO
    '1' -> TruthCell.ONE
    'x', 'X', '-' -> TruthCell.DONT_CARE
    else -> invalidFile(line)
}

private fun readTextBits(value: String, width: Int, line: Int): List<TruthCell> {
    if (value.length == width) return value.map { bit(it, line) }
    if (value.length != (width + 3) / 4) invalidFile(line)
    val firstWidth = (width - 1) % 4 + 1
    return value.flatMapIndexed { index, digit ->
        val bits = if (index == 0) firstWidth else 4
        if (digit in "xX-") List(bits) { TruthCell.DONT_CARE } else {
            val number = digit.digitToIntOrNull(16)?.takeIf { it < 1 shl bits } ?: invalidFile(line)
            (bits - 1 downTo 0).map { if (number and (1 shl it) == 0) TruthCell.ZERO else TruthCell.ONE }
        }
    }
}

internal class EvolutionTableEncoder(
    inputBits: List<String>,
    outputBits: List<String>,
    private val format: EvolutionTableFormat,
) {
    private val inputs = variables(inputBits, format)
    private val outputs = variables(outputBits, format)
    val name = "truth-table.${format.extension}"
    val mimeType = format.mimeType

    init {
        val names = (inputs + outputs).map { it.name.lowercase() }
        if (names.distinct().size != names.size) invalidFile()
    }

    fun header(): String = when (format) {
        EvolutionTableFormat.CSV -> {
            fun fields(variables: List<FileVariable>) =
                variables.flatMap { variable -> listOf("\"${variable.header()}\"") + List(variable.width - 1) { "" } }
            (fields(inputs) + "\"|\"" + fields(outputs)).joinToString(",") + "\n"
        }
        EvolutionTableFormat.TXT ->
            inputs.joinToString(" ") { it.header() } + " | " + outputs.joinToString(" ") { it.header() } + "\n"
    }

    fun appendRow(target: StringBuilder, input: List<TruthCell>, output: List<TruthCell>) {
        when (format) {
            EvolutionTableFormat.CSV -> {
                input.forEach { target.append(it.symbol).append(',') }
                target.append("\"|\",")
                target.appendLine(output.joinToString(",") { it.symbol })
            }
            EvolutionTableFormat.TXT -> {
                target.append(formatTextBits(input, inputs)).append(" | ")
                target.appendLine(formatTextBits(output, outputs))
            }
        }
    }

    fun rows(source: PagedTruthTable, firstRow: Int, count: Int, cancelled: () -> Boolean): String =
        buildString {
            if (source is ImportedPagedTruthTable) {
                source.appendRecords(this, this@EvolutionTableEncoder, firstRow, count, cancelled)
            } else source.readRows(firstRow, count, cancelled).forEachIndexed { index, output ->
                if (cancelled()) throw AnalysisCancelledException()
                val input = source.inputs.indices.map { column ->
                    if (source.input(firstRow + index, column)) TruthCell.ONE else TruthCell.ZERO
                }
                appendRow(this, input, output)
            }
        }
}

private fun formatTextBits(bits: List<TruthCell>, variables: List<FileVariable>): String {
    var index = 0
    return variables.joinToString(" ") { variable ->
        buildString { repeat(variable.width) { append(bits[index++].symbol) } }
    }
}

private fun invalidFile(line: Int? = null): Nothing = throw LocalizedArgumentException(
    if (line == null) message("analysis_table_file_invalid")
    else message("analysis_table_file_invalid_line", "line" to line),
)
