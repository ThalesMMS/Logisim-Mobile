package dev.logisim.mobile.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** The desktop FP8, binary16, binary32 and binary64 encodings. */
object FloatingPointNumbers {
    val widths: Set<Int> = setOf(8, 16, 32, 64)

    fun decode(value: LogicVector): Double {
        if (!value.isFullyDefined) return Double.NaN
        return when (value.width) {
            8 -> decodeSmall(value.value.toInt(), 4, 3).toDouble()
            16 -> decodeSmall(value.value.toInt(), 5, 10).toDouble()
            32 -> Float.fromBits(value.value.toInt()).toDouble()
            64 -> Double.fromBits(value.value.toLong())
            else -> Double.NaN
        }
    }

    fun encode(width: Int, number: Double): LogicVector {
        require(width in widths) { "Unsupported floating point width: $width." }
        val bits = when (width) {
            8 -> encodeSmall(number.toFloat(), 4, 3).toULong()
            16 -> encodeSmall(number.toFloat(), 5, 10).toULong()
            32 -> number.toFloat().toBits().toUInt().toULong()
            else -> number.toBits().toULong()
        }
        return LogicVector.of(width, bits)
    }

    fun parse(text: String): Double? {
        val source = text.trim()
        val decimal = if (source.lastOrNull() in setOf('f', 'F', 'd', 'D') && source.any(Char::isDigit))
            source.dropLast(1) else source
        decimal.toDoubleOrNull()?.let { return it }
        val match = Regex("([+-]?)0[xX]([0-9a-fA-F]+)(?:\\.([0-9a-fA-F]*))?[pP]([+-]?[0-9]+)[fFdD]?")
            .matchEntire(source) ?: return null
        val whole = match.groupValues[2].toULongOrNull(16)?.toDouble() ?: return null
        val fraction = match.groupValues[3].foldIndexed(0.0) { index, sum, char ->
            sum + char.digitToInt(16) * 16.0.pow(-index - 1)
        }
        val significand = whole + fraction
        if (significand == 0.0) return if (match.groupValues[1] == "-") -0.0 else 0.0
        val magnitude = significand * 2.0.pow(match.groupValues[4].toIntOrNull() ?: return null)
        return if (match.groupValues[1] == "-") -magnitude else magnitude
    }

    /** Java Math.round uses ties toward positive infinity; Kotlin round uses ties to even. */
    fun roundToLong(number: Double): Long {
        if (number.isNaN()) return 0
        if (abs(number) >= 4503599627370496.0) return number.toLong()
        val lower = floor(number)
        return (if (number - lower >= 0.5) lower + 1.0 else lower).toLong()
    }

    private fun decodeSmall(bits: Int, exponentBits: Int, fractionBits: Int): Float {
        val sign = bits and (1 shl (exponentBits + fractionBits))
        val exponentMask = (1 shl exponentBits) - 1
        val exponent = (bits ushr fractionBits) and exponentMask
        val fraction = bits and ((1 shl fractionBits) - 1)
        val bias = (1 shl (exponentBits - 1)) - 1
        if (exponent == 0) {
            val magnitude = fraction.toFloat() * 2.0.pow(1 - bias - fractionBits).toFloat()
            return if (sign != 0) -magnitude else magnitude
        }
        val floatSign = if (sign != 0) Int.MIN_VALUE else 0
        val floatExponent = if (exponent == exponentMask) 0xff else exponent - bias + 127
        return Float.fromBits(floatSign or (floatExponent shl 23) or (fraction shl (23 - fractionBits)))
    }

    private fun encodeSmall(number: Float, exponentBits: Int, fractionBits: Int): Int {
        val bits = number.toRawBits()
        val sign = (bits ushr 31) shl (exponentBits + fractionBits)
        val exponentMask = (1 shl exponentBits) - 1
        val encodedInfinity = exponentMask shl fractionBits
        val shift = 23 - fractionBits
        val floatFraction = bits and 0x7fffff
        if (number.isNaN()) {
            val payload = if (fractionBits == 3) (floatFraction and 0x700000) ushr 20
                else (floatFraction ushr 13) or ((floatFraction and 0x1ff0) ushr 4) or (floatFraction and 0xf)
            return sign or encodedInfinity or payload
        }
        val bias = (1 shl (exponentBits - 1)) - 1
        val magnitude = abs(number)
        val maximumExponent = bias
        val overflow = if (fractionBits == 3) 256f else 65520f
        if (magnitude >= overflow) return sign or encodedInfinity
        if (magnitude <= 2.0.pow(1 - bias - fractionBits - 1).toFloat()) return sign
        var exponent = ((bits ushr 23) and 0xff) - 127
        var delta = 0
        var significand = floatFraction
        if (exponent < 1 - bias) {
            delta = 1 - bias - exponent
            exponent = -bias
            significand = significand or 0x800000
        }
        val discard = shift + delta
        var retained = significand ushr discard
        val half = 1 shl (discard - 1)
        val remainder = significand and ((1 shl discard) - 1)
        if (remainder > half || remainder == half && retained and 1 != 0) retained++
        check(exponent <= maximumExponent)
        return sign or (((exponent + bias) shl fractionBits) + retained)
    }
}

/** Exact dyadic arithmetic for the desktop's one-rounding Math.fma operation. */
internal object FloatingPointExact {
    private data class Dyadic(val negative: Boolean, val significand: ULong, val exponent: Int)

    fun fma(a: Double, b: Double, c: Double): Double {
        // Evolution's Math.fma runs as the AArch64 FMADD intrinsic: the first signaling NaN, else the first
        // quiet NaN, in addend-first order, is returned quiet with its payload.
        val operands = listOf(c, a, b)
        val quiet = 1L shl 51
        (operands.firstOrNull { it.isNaN() && it.toRawBits() and quiet == 0L } ?: operands.firstOrNull { it.isNaN() })
            ?.let { return Double.fromBits(it.toRawBits() or quiet) }
        val x = decompose(a)
        val y = decompose(b)
        val z = decompose(c)
        val negative = x.negative xor y.negative
        if (a.isInfinite() || b.isInfinite()) {
            if (a == 0.0 || b == 0.0) return Double.NaN
            if (c.isInfinite() && negative != z.negative) return Double.NaN
            return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        }
        if (c.isInfinite()) return c
        val productExponent = x.exponent + y.exponent
        val exponent = minOf(productExponent, z.exponent)
        val product = Magnitude.of(x.significand).times(y.significand).shiftLeft(productExponent - exponent)
        val addend = Magnitude.of(z.significand).shiftLeft(z.exponent - exponent)
        val comparison = product.compareTo(addend)
        val result = if (negative == z.negative) product + addend
            else if (comparison >= 0) product - addend else addend - product
        val sign = if (negative == z.negative || comparison > 0) negative else if (comparison < 0) z.negative else false
        return result.toDouble(exponent, sign)
    }

    private fun decompose(number: Double): Dyadic {
        val bits = number.toRawBits().toULong()
        val exponent = ((bits shr 52) and 0x7ffuL).toInt()
        val fraction = bits and 0xfffffffffffffuL
        return Dyadic(bits shr 63 != 0uL,
            fraction or if (exponent == 0) 0uL else 0x10000000000000uL,
            if (exponent == 0) -1074 else exponent - 1075)
    }

    private class Magnitude private constructor(private val words: LongArray) : Comparable<Magnitude> {
        private val length = words.indexOfLast { it != 0L } + 1
        private val bitLength: Int get() = if (length == 0) 0 else (length - 1) * 32 +
            32 - words[length - 1].toInt().countLeadingZeroBits()
        private fun word(index: Int) = words.getOrElse(index) { 0L }
        private fun bit(index: Int): Boolean = index >= 0 && word(index / 32) and (1L shl (index % 32)) != 0L

        override fun compareTo(other: Magnitude): Int {
            if (length != other.length) return length.compareTo(other.length)
            for (i in length - 1 downTo 0) if (word(i) != other.word(i)) return word(i).compareTo(other.word(i))
            return 0
        }

        operator fun plus(other: Magnitude): Magnitude {
            val result = LongArray(maxOf(length, other.length) + 1)
            var carry = 0L
            for (i in result.indices) {
                val value = word(i) + other.word(i) + carry
                result[i] = value and 0xffffffffL
                carry = value ushr 32
            }
            return Magnitude(result)
        }

        operator fun minus(other: Magnitude): Magnitude {
            require(this >= other)
            val result = LongArray(length)
            var borrow = 0L
            for (i in result.indices) {
                val value = word(i) - other.word(i) - borrow
                result[i] = value and 0xffffffffL
                borrow = if (value < 0) 1 else 0
            }
            return Magnitude(result)
        }

        fun shiftLeft(bits: Int): Magnitude {
            if (length == 0) return this
            val result = LongArray(length + bits / 32 + 1)
            val extra = bits % 32
            for (i in 0 until length) {
                val shifted = word(i) shl extra
                result[i + bits / 32] = result[i + bits / 32] or (shifted and 0xffffffffL)
                result[i + bits / 32 + 1] = shifted ushr 32
            }
            return Magnitude(result)
        }

        fun times(other: ULong): Magnitude {
            var result = of(0uL)
            for (bit in 0 until 64) if ((other shr bit) and 1uL != 0uL) result += shiftLeft(bit)
            return result
        }

        private fun rounded(shift: Int): ULong {
            var value = 0uL
            for (i in 0 until 64) if (bit(i + shift)) value = value or (1uL shl i)
            if (shift > 0 && bit(shift - 1)) {
                val sticky = (0 until shift - 1).any(::bit)
                if (sticky || value and 1uL != 0uL) value++
            }
            return value
        }

        fun toDouble(exponent: Int, negative: Boolean): Double {
            val sign = if (negative) 0x8000000000000000uL else 0uL
            if (length == 0) return Double.fromBits(sign.toLong())
            var topExponent = bitLength - 1 + exponent
            if (topExponent > 1023) return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            val quantum = maxOf(topExponent - 52, -1074)
            var significand = rounded(quantum - exponent)
            if (significand >= 0x20000000000000uL) {
                significand = significand shr 1
                topExponent++
            }
            if (topExponent > 1023) return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            val bits = if (significand < 0x10000000000000uL) significand else
                ((maxOf(topExponent, -1022) + 1023).toULong() shl 52) or (significand and 0xfffffffffffffuL)
            return Double.fromBits((sign or bits).toLong())
        }

        companion object {
            fun of(value: ULong) = Magnitude(longArrayOf(
                (value and 0xffffffffuL).toLong(), (value shr 32).toLong()))
        }
    }
}
