package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

internal sealed interface HdlJsonValue {
    data class Object(val values: Map<String, HdlJsonValue>) : HdlJsonValue
    data class Array(val values: List<HdlJsonValue>) : HdlJsonValue
    data class StringValue(val value: String) : HdlJsonValue
    data class Number(val text: String) : HdlJsonValue
    data class BooleanValue(val value: Boolean) : HdlJsonValue
    object Null : HdlJsonValue
}

/** The synthesis boundary accepts bounded JSON, without platform parsing or numeric coercion. */
internal class HdlJsonParser(private val source: String) {
    private var offset = 0
    private var nodes = 0

    init { requireLocalized(source.length <= 32 * 1024 * 1024) { message(Messages.HdlImportJsonSizeLimit) } }

    fun parse(): HdlJsonValue {
        val result = value(0)
        whitespace()
        requireLocalized(offset == source.length) { message(Messages.HdlImportJsonSuffix, "offset" to offset) }
        return result
    }

    private fun value(depth: Int): HdlJsonValue {
        requireLocalized(depth <= 64 && ++nodes <= 2_000_000) { message(Messages.HdlImportJsonStructureLimit) }
        whitespace()
        requireLocalized(offset < source.length) { message(Messages.HdlImportJsonValueIncomplete) }
        return when (source[offset]) {
            '{' -> objectValue(depth)
            '[' -> arrayValue(depth)
            '"' -> HdlJsonValue.StringValue(string())
            't' -> { literal("true"); HdlJsonValue.BooleanValue(true) }
            'f' -> { literal("false"); HdlJsonValue.BooleanValue(false) }
            'n' -> { literal("null"); HdlJsonValue.Null }
            '-', in '0'..'9' -> number()
            else -> throw LocalizedArgumentException(message(Messages.HdlImportJsonTokenInvalid, "offset" to offset))
        }
    }

    private fun objectValue(depth: Int): HdlJsonValue.Object {
        offset++
        whitespace()
        val result = linkedMapOf<String, HdlJsonValue>()
        if (take('}')) return HdlJsonValue.Object(result)
        while (true) {
            whitespace()
            requireLocalized(source.getOrNull(offset) == '"') { message(Messages.HdlImportJsonKeyExpected, "offset" to offset) }
            val key = string()
            requireLocalized(key !in result) { message(Messages.HdlImportJsonKeyDuplicate, "key" to key) }
            whitespace()
            requireLocalized(take(':')) { message(Messages.HdlImportJsonColonExpected, "offset" to offset) }
            result[key] = value(depth + 1)
            whitespace()
            if (take('}')) break
            requireLocalized(take(',')) { message(Messages.HdlImportJsonCommaExpected, "offset" to offset) }
        }
        return HdlJsonValue.Object(result)
    }

    private fun arrayValue(depth: Int): HdlJsonValue.Array {
        offset++
        whitespace()
        val result = mutableListOf<HdlJsonValue>()
        if (take(']')) return HdlJsonValue.Array(result)
        while (true) {
            result += value(depth + 1)
            whitespace()
            if (take(']')) break
            requireLocalized(take(',')) { message(Messages.HdlImportJsonCommaExpected, "offset" to offset) }
        }
        return HdlJsonValue.Array(result)
    }

    private fun string(): String {
        requireLocalized(take('"')) { message(Messages.HdlImportJsonStringExpected, "offset" to offset) }
        return buildString {
            while (true) {
                requireLocalized(offset < source.length) { message(Messages.HdlImportJsonStringIncomplete) }
                val c = source[offset++]
                if (c == '"') break
                requireLocalized(c.code >= 32) { message(Messages.HdlImportJsonControlCharacter) }
                if (c != '\\') append(c) else {
                    requireLocalized(offset < source.length) { message(Messages.HdlImportJsonEscapeIncomplete) }
                    when (val escape = source[offset++]) {
                        '"', '\\', '/' -> append(escape)
                        'b' -> append('\b')
                        'f' -> append('\u000c')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        'u' -> {
                            requireLocalized(offset + 4 <= source.length) { message(Messages.HdlImportJsonUnicodeIncomplete) }
                            val code = source.substring(offset, offset + 4).toIntOrNull(16)
                                ?: throw LocalizedArgumentException(message(Messages.HdlImportJsonUnicodeInvalid))
                            append(code.toChar())
                            offset += 4
                        }
                        else -> throw LocalizedArgumentException(message(Messages.HdlImportJsonEscapeInvalid, "escape" to escape))
                    }
                }
            }
        }
    }

    private fun number(): HdlJsonValue.Number {
        val start = offset
        take('-')
        requireLocalized(offset < source.length) { message(Messages.HdlImportJsonNumberIncomplete) }
        if (!take('0')) {
            requireLocalized(source[offset] in '1'..'9') { message(Messages.HdlImportJsonNumberInvalid) }
            while (source.getOrNull(offset) in '0'..'9') offset++
        }
        if (take('.')) {
            requireLocalized(source.getOrNull(offset) in '0'..'9') { message(Messages.HdlImportJsonFractionInvalid) }
            while (source.getOrNull(offset) in '0'..'9') offset++
        }
        if (source.getOrNull(offset) in listOf('e', 'E')) {
            offset++
            if (source.getOrNull(offset) in listOf('+', '-')) offset++
            requireLocalized(source.getOrNull(offset) in '0'..'9') { message(Messages.HdlImportJsonExponentInvalid) }
            while (source.getOrNull(offset) in '0'..'9') offset++
        }
        return HdlJsonValue.Number(source.substring(start, offset))
    }

    private fun literal(text: String) {
        requireLocalized(source.startsWith(text, offset)) { message(Messages.HdlImportJsonLiteralInvalid, "offset" to offset) }
        offset += text.length
    }

    private fun take(c: Char): Boolean = if (source.getOrNull(offset) == c) { offset++; true } else false
    private fun whitespace() { while (source.getOrNull(offset) in listOf(' ', '\r', '\n', '\t')) offset++ }
}

internal fun HdlJsonValue.objectValues(): Map<String, HdlJsonValue> =
    (this as? HdlJsonValue.Object)?.values ?: throw LocalizedArgumentException(message(Messages.HdlImportJsonObjectExpected))
internal fun HdlJsonValue.arrayValues(): List<HdlJsonValue> =
    (this as? HdlJsonValue.Array)?.values ?: throw LocalizedArgumentException(message(Messages.HdlImportJsonArrayExpected))
internal fun HdlJsonValue.stringValue(): String = when (this) {
    is HdlJsonValue.StringValue -> value
    is HdlJsonValue.Number -> text
    else -> throw LocalizedArgumentException(message(Messages.HdlImportJsonTextOrNumberExpected))
}
