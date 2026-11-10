package dev.logisim.mobile.core

sealed interface CompanionJson
data class CompanionJsonObject(val fields: Map<String, CompanionJson>) : CompanionJson {
    fun string(name: String): String = (fields[name] as? CompanionJsonString)?.value
        ?: throw CompanionProtocolException("invalid-response", "Expected string field: $name")
    fun boolean(name: String): Boolean = (fields[name] as? CompanionJsonBoolean)?.value
        ?: throw CompanionProtocolException("invalid-response", "Expected boolean field: $name")
    fun long(name: String): Long = (fields[name] as? CompanionJsonNumber)?.value?.toLongOrNull()
        ?: throw CompanionProtocolException("invalid-response", "Expected integer field: $name")
    fun obj(name: String): CompanionJsonObject = fields[name] as? CompanionJsonObject
        ?: throw CompanionProtocolException("invalid-response", "Expected object field: $name")
    fun array(name: String): List<CompanionJson> = (fields[name] as? CompanionJsonArray)?.values
        ?: throw CompanionProtocolException("invalid-response", "Expected array field: $name")
}
data class CompanionJsonArray(val values: List<CompanionJson>) : CompanionJson
data class CompanionJsonString(val value: String) : CompanionJson {
    init { requireValidCompanionString(value) }
}
data class CompanionJsonBoolean(val value: Boolean) : CompanionJson
data object CompanionJsonNull : CompanionJson
data class CompanionJsonNumber(val value: String) : CompanionJson {
    init {
        require(NUMBER.matches(value) && value.toDoubleOrNull()?.isFinite() == true) { "Use a finite JSON number." }
    }
    constructor(value: Long) : this(value.toString())
    constructor(value: Double) : this(value.toString())
    companion object { internal val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?") }
}

object CompanionJsonCodec {
    fun parseObject(text: String): CompanionJsonObject = parse(text) as? CompanionJsonObject
        ?: throw CompanionProtocolException("invalid-json", "Expected a JSON object.")

    fun parse(text: String): CompanionJson = try {
        Reader(text).read()
    } catch (error: CompanionProtocolException) {
        throw error
    } catch (_: IllegalArgumentException) {
        throw CompanionProtocolException("invalid-json", "Malformed companion JSON.")
    }

    fun write(value: CompanionJson): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: CompanionJson) {
        when (value) {
            is CompanionJsonObject -> {
                append('{')
                value.fields.entries.sortedBy { it.key }.forEachIndexed { index, (key, item) ->
                    if (index > 0) append(',')
                    appendString(key); append(':'); appendValue(item)
                }
                append('}')
            }
            is CompanionJsonArray -> { append('['); value.values.forEachIndexed { index, item ->
                if (index > 0) append(','); appendValue(item)
            }; append(']') }
            is CompanionJsonString -> appendString(value.value)
            is CompanionJsonNumber -> append(value.value)
            is CompanionJsonBoolean -> append(if (value.value) "true" else "false")
            CompanionJsonNull -> append("null")
        }
    }

    private fun StringBuilder.appendString(text: String) {
        requireValidCompanionString(text)
        append('"')
        for (char in text) when (char) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (char.code < 32) append("\\u" + char.code.toString(16).padStart(4, '0')) else append(char)
        }
        append('"')
    }

    private class Reader(private val text: String) {
        private var offset = 0
        fun read(): CompanionJson { val value = value(0); space(); require(offset == text.length); return value }
        private fun space() { while (offset < text.length && text[offset] in " \r\n\t") offset++ }
        private fun take(char: Char): Boolean { space(); return if (text.getOrNull(offset) == char) { offset++; true } else false }
        private fun value(depth: Int): CompanionJson {
            require(depth <= 48); space()
            return when (text.getOrNull(offset)) {
                '{' -> {
                    offset++; val fields = linkedMapOf<String, CompanionJson>()
                    if (!take('}')) {
                        do {
                            space(); val key = string(); require(key !in fields); require(take(':'))
                            fields[key] = value(depth + 1)
                        } while (take(','))
                        require(take('}'))
                    }
                    CompanionJsonObject(fields)
                }
                '[' -> {
                    offset++; val values = mutableListOf<CompanionJson>()
                    if (!take(']')) { do { values += value(depth + 1) } while (take(',')); require(take(']')) }
                    CompanionJsonArray(values)
                }
                '"' -> CompanionJsonString(string())
                't' -> literal("true", CompanionJsonBoolean(true))
                'f' -> literal("false", CompanionJsonBoolean(false))
                'n' -> literal("null", CompanionJsonNull)
                else -> {
                    val start = offset
                    while (offset < text.length && text[offset] in "-+0123456789.eE") offset++
                    CompanionJsonNumber(text.substring(start, offset))
                }
            }
        }
        private fun literal(word: String, value: CompanionJson): CompanionJson {
            require(text.startsWith(word, offset)); offset += word.length; return value
        }
        private fun string(): String {
            require(take('"'))
            return buildString {
                while (true) {
                    val char = text.getOrNull(offset++) ?: throw IllegalArgumentException()
                    if (char == '"') break
                    require(char.code >= 32)
                    if (char != '\\') append(char) else {
                        when (val escaped = text.getOrNull(offset++) ?: throw IllegalArgumentException()) {
                            '"', '\\', '/' -> append(escaped)
                            'b' -> append('\b'); 'f' -> append('\u000c'); 'n' -> append('\n')
                            'r' -> append('\r'); 't' -> append('\t')
                            'u' -> {
                                require(offset + 4 <= text.length)
                                val digits = text.substring(offset, offset + 4)
                                require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                                append(digits.toInt(16).toChar()); offset += 4
                            }
                            else -> throw IllegalArgumentException()
                        }
                    }
                }
            }.also(::requireValidCompanionString)
        }
    }
}

private fun requireValidCompanionString(text: String) {
    var index = 0
    while (index < text.length) {
        val code = text[index++].code
        if (code in 0xD800..0xDBFF) {
            require(index < text.length && text[index++].code in 0xDC00..0xDFFF) { "Unpaired JSON surrogate." }
        } else require(code !in 0xDC00..0xDFFF) { "Unpaired JSON surrogate." }
    }
}

/** Compare decoded JSON values without treating numeric spelling as a parameter change. */
internal fun CompanionJson.sameValue(other: CompanionJson): Boolean = when {
    this is CompanionJsonNumber && other is CompanionJsonNumber ->
        value == other.value || decimalValue(value)?.let { it == decimalValue(other.value) } == true
    this is CompanionJsonObject && other is CompanionJsonObject ->
        fields.keys == other.fields.keys && fields.all { (key, value) -> value.sameValue(other.fields.getValue(key)) }
    this is CompanionJsonArray && other is CompanionJsonArray ->
        values.size == other.values.size && values.indices.all { values[it].sameValue(other.values[it]) }
    else -> this == other
}

private fun decimalValue(source: String): String? {
    val parts = source.lowercase().split('e')
    val mantissa = parts[0]
    val digits = mantissa.removePrefix("-").replace(".", "").trimStart('0')
    if (digits.isEmpty()) return "0"
    val exponent = if (parts.size == 1) 0L else parts[1].toLongOrNull() ?: return null
    val fraction = mantissa.substringAfter('.', "").length
    val trailingZeros = digits.length - digits.trimEnd('0').length
    val adjustment = trailingZeros.toLong() - fraction.toLong()
    if ((adjustment > 0 && exponent > Long.MAX_VALUE - adjustment) ||
        (adjustment < 0 && exponent < Long.MIN_VALUE - adjustment)) return null
    return (if (mantissa.startsWith('-')) "-" else "") + digits.trimEnd('0') + "e" + (exponent + adjustment)
}
