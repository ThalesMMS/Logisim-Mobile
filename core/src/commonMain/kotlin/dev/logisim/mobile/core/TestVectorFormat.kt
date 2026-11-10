package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message

data class VectorColumn(val name: String, val width: Int = 1)

sealed interface VectorCell {
    data class Value(val value: LogicVector) : VectorCell
    data object DontCare : VectorCell
    data object Floating : VectorCell
}

data class VectorRow(
    val lineNumber: Int,
    val values: List<VectorCell>,
    val set: Int = 0,
    val sequence: Int = 0,
)

data class VectorTable(val columns: List<VectorColumn>, val rows: List<VectorRow>)

class VectorFormatException(val lineNumber: Int, val detail: LocalizedText) :
    LocalizedArgumentException(message(Messages.VectorErrorLine, "line" to lineNumber, "detail" to detail)) {
    constructor(lineNumber: Int, detail: String) : this(lineNumber, LocalizedText.literal(detail))
}

/** Desktop test-vector text, with a mobile extension for quoted pin labels. */
object TestVectorFormat {
    fun read(text: String): VectorTable {
        val lines = text.lineSequence().mapIndexedNotNull { index, source ->
            tokenize(source, index + 1).takeIf { it.isNotEmpty() }?.let { SourceLine(index + 1, it) }
        }.toList()
        val header = lines.firstOrNull() ?: fail(1, message(Messages.VectorEmptyFile))
        val columns = mutableListOf<VectorColumn>()
        val positions = mutableListOf<HeaderPosition>()
        var hasSet = false
        var hasSequence = false
        for (token in header.tokens) {
            when (if (token.quoted) null else token.text.uppercase()) {
                "<SET>" -> {
                    if (hasSet) fail(header.number, message(Messages.VectorDuplicateSet))
                    hasSet = true
                    positions += HeaderPosition.Set
                }
                "<SEQ>" -> {
                    if (hasSequence) fail(header.number, message(Messages.VectorDuplicateSeq))
                    hasSequence = true
                    positions += HeaderPosition.Sequence
                }
                else -> {
                    val (name, rawWidth) = if (token.quoted) {
                        val suffix = Regex("(?:\\[([0-9]+)\\])?").matchEntire(token.suffix)
                            ?: fail(header.number, message(Messages.VectorBadColumn, "token" to token.source))
                        token.text to suffix.groupValues[1]
                    } else {
                        val match = Regex("([^\\[\\]]+)(?:\\[([0-9]+)\\])?").matchEntire(token.text)
                            ?: fail(header.number, message(Messages.VectorBadColumn, "token" to token.source))
                        match.groupValues[1] to match.groupValues[2]
                    }
                    if (name.isBlank()) fail(header.number, message(Messages.VectorBadPinName, "name" to name))
                    val width = if (rawWidth.isEmpty()) 1 else rawWidth.toIntOrNull()
                        ?: fail(header.number, message(Messages.VectorBadWidth, "token" to token.source))
                    if (width !in 1..64) fail(header.number, message(Messages.VectorWidthRange, "name" to name))
                    if (columns.any { it.name == name }) fail(header.number, message(Messages.VectorDuplicatePin, "name" to name))
                    columns += VectorColumn(name, width)
                    positions += HeaderPosition.Pin(columns.lastIndex)
                }
            }
        }
        if (columns.isEmpty()) fail(header.number, message(Messages.VectorNeedPin))
        val rows = lines.drop(1).map { line ->
            if (line.tokens.size != positions.size) {
                fail(line.number, message(Messages.VectorValueCount, "expected" to positions.size, "actual" to line.tokens.size))
            }
            var set = 0
            var sequence = 0
            val values = mutableListOf<VectorCell>()
            positions.forEachIndexed { index, position ->
                val token = line.tokens[index]
                if (token.quoted) fail(line.number, message(Messages.VectorQuotedData))
                when (position) {
                    HeaderPosition.Set -> set = nonNegativeInt(token.text, "<set>", line.number)
                    HeaderPosition.Sequence -> sequence = nonNegativeInt(token.text, "<seq>", line.number)
                    is HeaderPosition.Pin -> values += parseCell(token.text, columns[position.index], line.number)
                }
            }
            VectorRow(line.number, values, set, sequence)
        }
        validateOrder(rows)
        return VectorTable(columns, rows)
    }

    fun write(table: VectorTable): String {
        if (table.columns.isEmpty()) fail(1, message(Messages.VectorNeedPin))
        if (table.columns.map { it.name }.distinct().size != table.columns.size) fail(1, message(Messages.VectorDuplicateNames))
        table.columns.forEach { column ->
            if (column.name.isBlank() || column.name.any { it == '\r' || it == '\n' }) {
                fail(1, message(Messages.VectorBadPinName, "name" to column.name))
            }
            if (column.width !in 1..64) fail(1, message(Messages.VectorWidthRange, "name" to column.name))
        }
        val sequential = table.rows.any { it.set != 0 || it.sequence != 0 }
        val output = buildString {
            append(table.columns.joinToString("\t") { column ->
                val name = if (column.name.any { it.isWhitespace() || it in "[]#\"\\" }) {
                    "\"${column.name.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                } else column.name
                if (column.width == 1 && column.name.uppercase() !in setOf("<SET>", "<SEQ>")) name
                else "$name[${column.width}]"
            })
            if (sequential) append("\t<set>\t<seq>")
            append('\n')
            table.rows.forEach { row ->
                if (row.values.size != table.columns.size) fail(row.lineNumber, message(Messages.VectorRowPins))
                append(row.values.mapIndexed { index, cell ->
                    when (cell) {
                        VectorCell.DontCare -> "<DC>"
                        VectorCell.Floating -> "<float>"
                        is VectorCell.Value -> {
                            if (cell.value.width != table.columns[index].width) {
                                fail(row.lineNumber, message(Messages.VectorIncompatibleWidth, "name" to table.columns[index].name))
                            }
                            buildString(cell.value.width) {
                                for (bit in cell.value.width - 1 downTo 0) append(when (cell.value.bit(bit)) {
                                    LogicValue.LOW -> '0'
                                    LogicValue.HIGH -> '1'
                                    LogicValue.UNKNOWN -> 'x'
                                    LogicValue.ERROR -> fail(row.lineNumber, message(Messages.VectorErrorBits))
                                })
                            }
                        }
                    }
                }.joinToString("\t"))
                if (sequential) append("\t${row.set}\t${row.sequence}")
                append('\n')
            }
        }
        read(output)
        return output
    }

    private fun parseCell(token: String, column: VectorColumn, line: Int): VectorCell = when (token.uppercase()) {
        "<DC>" -> VectorCell.DontCare
        "<FLOAT>" -> VectorCell.Floating
        else -> VectorCell.Value(parseValue(token, column, line))
    }

    private fun parseValue(token: String, column: VectorColumn, line: Int): LogicVector {
        val cleaned = token.replace("_", "")
        val radix = when {
            cleaned.startsWith("0x") -> 16
            cleaned.startsWith("0o") -> 8
            cleaned.startsWith("0b") || cleaned.length == column.width -> 2
            else -> 10
        }
        if (radix == 10) {
            if (!Regex("-?[0-9]+").matches(cleaned)) fail(line, message(Messages.VectorBadDecimal, "name" to column.name, "token" to token))
            val value = cleaned.toLongOrNull() ?: fail(line, message(Messages.VectorBadDecimal, "name" to column.name, "token" to token))
            if (column.width < 64) {
                val limit = 1L shl (column.width - 1)
                if (value < -limit || value >= limit) fail(line, message(Messages.VectorValueWidth, "token" to token, "width" to column.width, "name" to column.name))
            }
            return LogicVector.of(column.width, value.toULong())
        }
        val digits = if (cleaned.startsWith("0x") || cleaned.startsWith("0o") || cleaned.startsWith("0b"))
            cleaned.drop(2) else cleaned
        if (digits.isEmpty()) fail(line, message(Messages.VectorExpectedDigits, "name" to column.name))
        val bitsPerDigit = when (radix) { 16 -> 4; 8 -> 3; else -> 1 }
        if (digits.dropWhile { it == '0' }.length > (64 + bitsPerDigit - 1) / bitsPerDigit) {
            fail(line, message(Messages.VectorOverflow, "token" to token, "name" to column.name))
        }
        var value = 0uL
        var unknown = 0uL
        for (character in digits) {
            val digit = when (character) {
                'x' -> -1
                in '0'..'9' -> character - '0'
                in 'a'..'f' -> character - 'a' + 10
                in 'A'..'F' -> character - 'A' + 10
                else -> fail(line, message(Messages.VectorBadCharacter, "name" to column.name, "character" to character))
            }
            if (digit >= radix) fail(line, message(Messages.VectorBadDigit, "radix" to radix, "name" to column.name, "character" to character))
            val base = radix.toULong()
            val nextValue = if (digit < 0) 0uL else digit.toULong()
            val nextUnknown = if (digit < 0) (radix - 1).toULong() else 0uL
            if (value > (ULong.MAX_VALUE - nextValue) / base || unknown > (ULong.MAX_VALUE - nextUnknown) / base) {
                fail(line, message(Messages.VectorOverflow, "token" to token, "name" to column.name))
            }
            value = value * base + nextValue
            unknown = unknown * base + nextUnknown
        }
        val mask = if (column.width == 64) ULong.MAX_VALUE else (1uL shl column.width) - 1uL
        if (value and mask.inv() != 0uL) fail(line, message(Messages.VectorValueWidth, "token" to token, "width" to column.width, "name" to column.name))
        // The desktop shifts by 64 and clears this mask. Preserve unspecified 64-bit data.
        return LogicVector.fromMasks(column.width, value, unknown and mask, 0uL)
    }

    private fun validateOrder(rows: List<VectorRow>) {
        var lastSet = 0
        var lastSequence = 0
        rows.forEach { row ->
            if (row.set < lastSet) fail(row.lineNumber, message(Messages.VectorSetOrder))
            if (row.set == lastSet && row.set > 0 && row.sequence <= lastSequence) {
                fail(row.lineNumber, message(Messages.VectorSeqOrder, "set" to row.set))
            }
            if ((row.set == 0) != (row.sequence == 0)) {
                fail(row.lineNumber, message(Messages.VectorMetadataPair))
            }
            lastSet = row.set
            lastSequence = row.sequence
        }
    }

    private fun nonNegativeInt(token: String, column: String, line: Int): Int =
        token.toIntOrNull()?.takeIf { it >= 0 } ?: fail(line, message(Messages.VectorBadMetadata, "column" to column, "token" to token))

    private fun fail(line: Int, detail: LocalizedText): Nothing = throw VectorFormatException(line, detail)

    private fun tokenize(source: String, line: Int): List<Token> {
        val tokens = mutableListOf<Token>()
        var index = 0
        while (index < source.length) {
            while (index < source.length && source[index].isWhitespace()) index++
            if (index == source.length || source[index] == '#') break
            val start = index
            if (source[index] != '"') {
                while (index < source.length && !source[index].isWhitespace() && source[index] != '#') index++
                val text = source.substring(start, index)
                tokens += Token(text, text)
                continue
            }
            index++
            val name = StringBuilder()
            var closed = false
            while (index < source.length) {
                when (val character = source[index++]) {
                    '"' -> { closed = true; break }
                    '\\' -> {
                        if (index == source.length) fail(line, message(Messages.VectorUnterminatedQuote))
                        val escaped = source[index++]
                        if (escaped != '\\' && escaped != '"') {
                            fail(line, message(Messages.VectorInvalidEscape, "escape" to "\\$escaped"))
                        }
                        name.append(escaped)
                    }
                    else -> name.append(character)
                }
            }
            if (!closed) fail(line, message(Messages.VectorUnterminatedQuote))
            val suffixStart = index
            while (index < source.length && !source[index].isWhitespace() && source[index] != '#') index++
            tokens += Token(name.toString(), source.substring(start, index), true, source.substring(suffixStart, index))
        }
        return tokens
    }

    private data class Token(val text: String, val source: String, val quoted: Boolean = false, val suffix: String = "")
    private data class SourceLine(val number: Int, val tokens: List<Token>)
    private sealed interface HeaderPosition {
        data class Pin(val index: Int) : HeaderPosition
        data object Set : HeaderPosition
        data object Sequence : HeaderPosition
    }
}
