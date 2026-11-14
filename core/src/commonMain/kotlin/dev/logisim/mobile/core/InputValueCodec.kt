package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

/** The keyboard a value field opens: digits only, or text where a sign, hexadecimal letters or x must be typeable. */
enum class InputValueKeyboard { NUMBER, SIGNED, HEXADECIMAL, TEXT }

/** The result of reading a typed input value. */
sealed interface InputValueParse {
    data class Value(val vector: LogicVector) : InputValueParse
    data class Invalid(val error: LocalizedText) : InputValueParse
}

/**
 * The value editor of an input pin, after Evolution's Pin.EditDecimal and Pin.EditFloat: the current value is shown
 * in the pin's radix (2, 8, 16, 10signed, 10unsigned or float), typed text is read in that radix or with a 0x/0b
 * prefix, signed decimals take −2^(w−1)…2^(w−1)−1 and the others 0…2^w−1, and x (or ???) is accepted only by a
 * tri-state pin. A float radix needs one of the desktop's floating-point widths (8, 16, 32, 64 bits); on other widths
 * Evolution only shows NaN, so the editor reads and writes the unsigned integer instead.
 */
class InputValueCodec(val width: Int, radix: String, val tristate: Boolean) {
    init {
        require(width in 1..64) { "Unsupported width: $width" }
    }

    val radix: String = when {
        radix == "float" && width !in FloatingPointNumbers.widths -> "10unsigned"
        radix in radixes -> radix
        else -> "2"
    }

    private val mask: ULong = if (width == 64) ULong.MAX_VALUE else (1uL shl width) - 1uL
    private val signedMinimum: Long = if (width == 64) Long.MIN_VALUE else -(1L shl (width - 1))
    private val signedMaximum: Long = if (width == 64) Long.MAX_VALUE else (1L shl (width - 1)) - 1

    /** The smallest and largest values, written in the pin's radix. */
    val minimum: String get() = if (radix == "10signed") signedMinimum.toString() else "0"
    val maximum: String get() = when (radix) {
        "2" -> mask.toString(2)
        "8" -> mask.toString(8)
        "16" -> mask.toString(16).uppercase()
        "10signed" -> signedMaximum.toString()
        else -> mask.toString()
    }

    /** The radix's display name: Evolution's RadixOption names. */
    val radixName: LocalizedText get() = message(when (radix) {
        "8" -> "preferences_octal"
        "16" -> "preferences_hexadecimal"
        "10signed" -> "preferences_signed_decimal"
        "10unsigned" -> "preferences_unsigned_decimal"
        "float" -> "preferences_floating_point"
        else -> "preferences_binary"
    })

    /** What the field accepts, shown under it while the text is valid. */
    val hint: LocalizedText get() = when {
        radix == "float" -> message(if (tristate) "input_value_float_hint_unknown" else "input_value_float_hint")
        tristate -> message("input_value_range_unknown", "min" to minimum, "max" to maximum)
        else -> message("input_value_range", "min" to minimum, "max" to maximum)
    }

    val keyboard: InputValueKeyboard get() = when {
        radix == "16" -> InputValueKeyboard.HEXADECIMAL
        tristate -> InputValueKeyboard.TEXT
        radix == "10signed" || radix == "float" -> InputValueKeyboard.SIGNED
        else -> InputValueKeyboard.NUMBER
    }

    /** [vector] as the editor shows it; an undefined value is x, a partly undefined one its bits. */
    fun format(vector: LogicVector): String {
        val value = if (vector.width == width) vector else vector.resize(width)
        if (value.isUnknown) return "x"
        val number = value.toULongOrNull() ?: return (if (radix == "2") "" else "0b") + bits(value)
        return when (radix) {
            "8" -> number.toString(8)
            "16" -> number.toString(16).uppercase().padStart((width + 3) / 4, '0')
            "10signed" -> signed(number).toString()
            "10unsigned" -> number.toString()
            "float" -> FloatingPointNumbers.decode(value).let { if (width == 64) it.toString() else it.toFloat().toString() }
            else -> number.toString(2).padStart(width, '0')
        }
    }

    fun parse(text: String): InputValueParse {
        val source = text.trim().filterNot { it == '_' || it.isWhitespace() }
        if (source.isEmpty()) return invalid(message("input_value_error_empty"))
        if (source.equals("x", ignoreCase = true) || source == "?" || source == "???") {
            return if (tristate) InputValueParse.Value(LogicVector.unknown(width))
            else invalid(message("input_value_error_unknown_simple"))
        }
        val lower = source.lowercase()
        if (lower.startsWith("0x") && !(radix == "float" && 'p' in lower)) return digits(source.drop(2), 16)
        if (lower.startsWith("0b")) return digits(source.drop(2), 2)
        return when (radix) {
            "2" -> digits(source, 2)
            "8" -> digits(source, 8)
            "16" -> digits(source, 16)
            "10signed" -> decimal(source, signed = true)
            "10unsigned" -> decimal(source, signed = false)
            else -> float(source)
        }
    }

    private fun digits(body: String, base: Int): InputValueParse {
        val unknownDigit = { char: Char -> base == 2 && tristate && (char == 'x' || char == 'X' || char == '?') }
        if (body.isEmpty() || body.any { it.digitToIntOrNull(base) == null && !unknownDigit(it) }) return invalid(message(when (base) {
            2 -> if (tristate) "input_value_error_binary_unknown" else "input_value_error_binary"
            8 -> "input_value_error_octal"
            else -> "input_value_error_hexadecimal"
        }))
        val significant = body.trimStart('0')
        if (base == 2) {
            if (significant.length > width) return outOfRange()
            var value = 0uL
            var unknown = 0uL
            for (char in significant) {
                value = value shl 1
                unknown = unknown shl 1
                if (char == '1') value = value or 1uL else if (char != '0') unknown = unknown or 1uL
            }
            return InputValueParse.Value(LogicVector.fromMasks(width, value, unknown, 0uL))
        }
        if (significant.isEmpty()) return InputValueParse.Value(LogicVector.low(width))
        val number = significant.toULongOrNull(base) ?: return outOfRange()
        return if (number > mask) outOfRange() else InputValueParse.Value(LogicVector.of(width, number))
    }

    private fun decimal(source: String, signed: Boolean): InputValueParse {
        if (!DecimalInteger.matches(source)) return invalid(message("input_value_error_decimal"))
        val unsigned = source.removePrefix("+")
        if (signed) {
            val number = unsigned.toLongOrNull() ?: return outOfRange()
            if (number < signedMinimum || number > signedMaximum) return outOfRange()
            return InputValueParse.Value(LogicVector.of(width, number.toULong() and mask))
        }
        if (unsigned.startsWith("-")) {
            return if (unsigned.drop(1).all { it == '0' }) InputValueParse.Value(LogicVector.low(width)) else outOfRange()
        }
        val number = unsigned.toULongOrNull() ?: return outOfRange()
        return if (number > mask) outOfRange() else InputValueParse.Value(LogicVector.of(width, number))
    }

    private fun float(source: String): InputValueParse {
        val number = when (source.lowercase()) {
            "inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY
            "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
            "nan" -> Double.NaN
            // A decimal comma, as a Portuguese keyboard types it.
            else -> FloatingPointNumbers.parse(if ('.' !in source && source.count { it == ',' } == 1) source.replace(',', '.') else source)
        } ?: return invalid(message("input_value_error_float"))
        return InputValueParse.Value(FloatingPointNumbers.encode(width, number))
    }

    private fun outOfRange() = invalid(message("input_value_error_range", "min" to minimum, "max" to maximum))

    private fun invalid(error: LocalizedText) = InputValueParse.Invalid(error)

    private fun signed(number: ULong): Long =
        if (width == 64) number.toLong() else (number shl (64 - width)).toLong() shr (64 - width)

    private fun bits(value: LogicVector) = buildString(width) {
        for (index in width - 1 downTo 0) append(when (value.bit(index)) {
            LogicValue.LOW -> '0'
            LogicValue.HIGH -> '1'
            LogicValue.UNKNOWN -> 'x'
            LogicValue.ERROR -> 'E'
        })
    }

    companion object {
        /** Evolution's RadixOption values for a pin. */
        val radixes: List<String> = listOf("2", "8", "16", "10signed", "10unsigned", "float")
        private val DecimalInteger = Regex("[+-]?[0-9]+")

        fun format(vector: LogicVector, radix: String): String = InputValueCodec(vector.width, radix, tristate = true).format(vector)

        fun parse(text: String, width: Int, radix: String, tristate: Boolean): InputValueParse =
            InputValueCodec(width, radix, tristate).parse(text)
    }
}
