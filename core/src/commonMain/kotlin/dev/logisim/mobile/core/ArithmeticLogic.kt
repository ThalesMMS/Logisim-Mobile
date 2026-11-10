package dev.logisim.mobile.core

enum class ArithmeticOperation(val fileName: String) {
    ADDER("Adder"), SUBTRACTOR("Subtractor"), MULTIPLIER("Multiplier"), DIVIDER("Divider"),
    NEGATOR("Negator"), EXPONENTIATOR("Exponentiator"), SQUARE_ROOT("SquareRoot"),
    ABSOLUTE("Absolute"), COMPARATOR("Comparator"), MIN_MAX("MinMax"), SHIFTER("Shifter"),
    BIT_ADDER("BitAdder"), BIT_FINDER("BitFinder"),
}

/** Arithmetic behavior follows the desktop component propagation, including undefined inputs. */
object ArithmeticLogic {
    fun evaluate(
        operation: ArithmeticOperation,
        width: Int,
        inputs: List<LogicVector>,
        mode: String = if (operation == ArithmeticOperation.COMPARATOR) "twosComplement" else "unsigned",
        shift: String = "ll",
        finder: String = "low1",
    ): List<LogicVector> {
        require(width in 1..64)
        fun input(index: Int, bits: Int = width) = inputs.getOrNull(index)?.resize(bits) ?: LogicVector.unknown(bits)
        val a = input(0)
        val b = input(1)
        return when (operation) {
            ArithmeticOperation.ADDER -> add(a, b, input(2, 1))
            ArithmeticOperation.SUBTRACTOR -> {
                val borrow = input(2, 1).let { if (it.isUnknown) LogicVector.low(1) else it }
                val sum = add(a, b.inverted(), borrow.inverted())
                listOf(sum[0], sum[1].inverted())
            }
            ArithmeticOperation.MULTIPLIER -> multiply(a, b, input(2), mode)
            ArithmeticOperation.DIVIDER -> divide(a, b, input(2), mode == "unsigned")
            ArithmeticOperation.NEGATOR -> listOf(negate(a, invertTail = true))
            ArithmeticOperation.EXPONENTIATOR -> power(a, b, mode == "unsigned")
            ArithmeticOperation.SQUARE_ROOT -> squareRoot(a, input(1))
            ArithmeticOperation.ABSOLUTE -> if (a.isFullyDefined) {
                val magnitude = if (a.bit(width - 1) == LogicValue.HIGH) 0uL - a.value else a.value
                val value = LogicVector.of(width, magnitude)
                listOf(value, LogicVector.fromBit(value.bit(width - 1)))
            } else listOf(negate(a, invertTail = false), LogicVector.error(1))
            ArithmeticOperation.COMPARATOR -> compare(a, b, mode == "unsigned")
            ArithmeticOperation.MIN_MAX -> if (!a.isFullyDefined || !b.isFullyDefined) {
                listOf(LogicVector.error(width), LogicVector.error(width))
            } else if (compareKnown(a, b, mode == "unsigned") <= 0) listOf(a, b) else listOf(b, a)
            ArithmeticOperation.SHIFTER -> listOf(shift(a, inputs.getOrNull(1), shift))
            ArithmeticOperation.BIT_ADDER -> listOf(bitAdder(width, inputs))
            ArithmeticOperation.BIT_FINDER -> bitFinder(a, finder)
        }
    }

    fun delay(operation: ArithmeticOperation, width: Int, inputCount: Int = 1): Long = when (operation) {
        ArithmeticOperation.MULTIPLIER, ArithmeticOperation.DIVIDER, ArithmeticOperation.SQUARE_ROOT -> width.toLong() * (width + 2)
        ArithmeticOperation.SHIFTER -> width.toLong() * 3
        ArithmeticOperation.SUBTRACTOR -> (width + 4).toLong()
        ArithmeticOperation.BIT_ADDER -> countWidth(width * inputCount).toLong()
        ArithmeticOperation.BIT_FINDER -> indexWidth(width).toLong()
        else -> (width + 2).toLong()
    }

    fun indexWidth(width: Int): Int = (32 - (width - 1).countLeadingZeroBits()).coerceAtLeast(1)
    fun countWidth(maximum: Int): Int = 32 - maximum.countLeadingZeroBits()

    private fun add(a: LogicVector, b: LogicVector, carry: LogicVector) = SimulationComponents.add(a, b, carry)

    private fun multiply(a: LogicVector, b: LogicVector, carryInput: LogicVector, mode: String): List<LogicVector> {
        val width = a.width
        val carry = if (carryInput.isUnknown) LogicVector.low(width) else carryInput
        val unsignedA = mode == "unsigned"
        val unsignedB = mode != "twosComplement"
        val product = number(a, unsignedA) * number(b, unsignedB) + number(carry, unsignedA && unsignedB)
        if (a.isFullyDefined && b.isFullyDefined && carry.isFullyDefined) return split(product, width)
        val values = listOf(a, b, carry)
        val firstUnknown = values.minOf { firstBit(it) { bit -> bit == LogicValue.UNKNOWN || bit == LogicValue.ERROR } }
        val firstError = values.minOf { firstBit(it) { bit -> bit == LogicValue.ERROR } }
        val low = LogicVector.fromBits((0 until width).map { bit ->
            when {
                // The desktop uses an int mask here, including its sign extension at bit 31.
                bit < firstUnknown -> if (product.low and (1 shl bit).toLong().toULong() != 0uL) LogicValue.HIGH else LogicValue.LOW
                bit < firstError -> LogicValue.UNKNOWN
                else -> LogicValue.ERROR
            }
        })
        return listOf(low, if (firstError < width) LogicVector.error(width) else LogicVector.unknown(width))
    }

    internal fun divideOriginal32(inputs: List<LogicVector>): List<LogicVector> {
        val (a, b, upper) = inputs
        return divide(a.resize(32), b.resize(32), upper.resize(32), unsigned = true, original32 = true)
    }

    private fun divide(
        a: LogicVector, b: LogicVector, upperInput: LogicVector, unsigned: Boolean, original32: Boolean = false,
    ): List<LogicVector> {
        val width = a.width
        val hasUpper = !upperInput.isUnknown
        val upper = if (hasUpper) upperInput else LogicVector.low(width)
        if (!a.isFullyDefined || !b.isFullyDefined || !upper.isFullyDefined) return undefined(width, a, b, upper)
        if (original32) {
            // Logisim 2.7.1 divides a signed long and adjusts negative remainders to be nonnegative.
            val numerator = (upper.value.toLong() shl width) or a.value.toLong()
            val denominator = b.value.toLong().coerceAtLeast(1L)
            return listOf(
                LogicVector.of(width, numerator.floorDiv(denominator).toULong()),
                LogicVector.of(width, numerator.mod(denominator).toULong()),
            )
        }
        val numerator = if (hasUpper) (number(upper, unsigned) shl width) or UInt128(0uL, a.value) else number(a, unsigned)
        var denominator = number(b, unsigned)
        if (denominator == UInt128.ZERO) denominator = UInt128.ONE
        val negativeNumerator = !unsigned && numerator.isNegative
        val negativeDenominator = !unsigned && denominator.isNegative
        val (quotient, remainder) = (if (negativeNumerator) -numerator else numerator)
            .divide(if (negativeDenominator) -denominator else denominator)
        return listOf(
            LogicVector.of(width, (if (negativeNumerator != negativeDenominator) -quotient else quotient).low),
            LogicVector.of(width, (if (negativeNumerator) -remainder else remainder).low),
        )
    }

    private fun power(a: LogicVector, b: LogicVector, unsigned: Boolean): List<LogicVector> {
        val width = a.width
        // Exponentiator.propagate converts its undefined/error results to known -1.
        if (!a.isFullyDefined || !b.isFullyDefined) return listOf(LogicVector.high(width), LogicVector.high(width))
        if (a.value == 1uL) return listOf(LogicVector.of(width, 1uL), LogicVector.low(width))
        val signedSmall = width <= 32 && !unsigned
        val signedExponent = if (signedSmall) signedValue(b) else b.value.toInt().toLong().coerceAtLeast(0)
        if (signedSmall && signedExponent < 0) return listOf(LogicVector.low(width), LogicVector.low(width))
        var exponent = signedExponent.toULong()
        var base = number(a, unsigned)
        var result = UInt128.ONE
        while (exponent != 0uL) {
            if (exponent and 1uL != 0uL) result *= base
            exponent = exponent shr 1
            if (exponent != 0uL) base *= base
        }
        return split(result, width)
    }

    private fun squareRoot(a: LogicVector, upperInput: LogicVector): List<LogicVector> {
        val width = a.width
        val upper = if (upperInput.isUnknown) LogicVector.low(width) else upperInput
        if (!a.isFullyDefined || !upper.isFullyDefined) return undefined(width, a, upper)
        val value = (UInt128(0uL, upper.value) shl width) or UInt128(0uL, a.value)
        var root = UInt128.ZERO
        var remainder = UInt128.ZERO
        for (pair in 63 downTo 0) {
            remainder = (remainder shl 2) or UInt128(0uL, (if (value.bit(pair * 2 + 1)) 2uL else 0uL) or
                (if (value.bit(pair * 2)) 1uL else 0uL))
            val trial = (root shl 2) + UInt128.ONE
            root = root shl 1
            if (remainder >= trial) {
                remainder -= trial
                root += UInt128.ONE
            }
        }
        return listOf(LogicVector.of(width, root.low), LogicVector.of(width, remainder.low))
    }

    private fun negate(value: LogicVector, invertTail: Boolean): LogicVector {
        if (value.isFullyDefined) return LogicVector.of(value.width, 0uL - value.value)
        val bits = MutableList(value.width) { value.bit(it) }
        var fill = LogicValue.LOW
        var position = 0
        while (position < bits.size) {
            when (bits[position]) {
                LogicValue.LOW -> bits[position] = fill
                LogicValue.HIGH -> {
                    if (fill != LogicValue.LOW) bits[position] = fill
                    position++
                    break
                }
                LogicValue.ERROR -> fill = LogicValue.ERROR
                LogicValue.UNKNOWN -> if (fill == LogicValue.LOW) fill = bits[position] else bits[position] = fill
            }
            position++
        }
        if (invertTail) for (i in position until bits.size) {
            if (bits[i] == LogicValue.HIGH) bits[i] = LogicValue.LOW
            else if (bits[i] == LogicValue.LOW) bits[i] = LogicValue.HIGH
        }
        return LogicVector.fromBits(bits)
    }

    private fun compare(a: LogicVector, b: LogicVector, unsigned: Boolean): List<LogicVector> {
        for (position in a.width - 1 downTo 0) {
            var left = a.bit(position)
            var right = b.bit(position)
            if (!unsigned && position == a.width - 1 && left != right) {
                val old = left; left = right; right = old
            }
            val undefined = when {
                left == LogicValue.ERROR || right == LogicValue.ERROR -> LogicValue.ERROR
                left == LogicValue.UNKNOWN || right == LogicValue.UNKNOWN -> LogicValue.UNKNOWN
                else -> null
            }
            if (undefined != null) return List(3) { LogicVector.fromBit(undefined) }
            if (left != right) return listOf(LogicVector.fromBit(left), LogicVector.low(1), LogicVector.fromBit(right))
        }
        return listOf(LogicVector.low(1), LogicVector.high(1), LogicVector.low(1))
    }

    private fun compareKnown(a: LogicVector, b: LogicVector, unsigned: Boolean): Int =
        if (unsigned) a.value.compareTo(b.value) else signedValue(a).compareTo(signedValue(b))

    private fun shift(value: LogicVector, distance: LogicVector?, mode: String): LogicVector {
        if (distance == null || !distance.isFullyDefined) return LogicVector.error(value.width)
        val width = value.width
        val amount = distance.value.toInt()
        if (amount == 0) return value
        val rotate = mode == "rl" || mode == "rr"
        val count = if (rotate) amount % width else amount.coerceAtMost(width)
        return LogicVector.fromBits((0 until width).map { index ->
            val source = when (mode) {
                "lr", "ar" -> index + count
                "rr" -> (index + count) % width
                "rl" -> (index - count + width) % width
                else -> index - count
            }
            when {
                source in 0 until width -> value.bit(source)
                mode == "ar" -> value.bit(width - 1)
                else -> LogicValue.LOW
            }
        })
    }

    private fun bitAdder(width: Int, inputs: List<LogicVector>): LogicVector {
        var minimum = 0
        var maximum = 0
        for (input in inputs) for (bit in 0 until width) {
            if (input.bit(bit) == LogicValue.HIGH) minimum++
            if (input.bit(bit) != LogicValue.LOW) maximum++
        }
        var varying = 0
        for (count in minimum + 1..maximum) varying = varying or (minimum xor count)
        return LogicVector.fromBits((0 until countWidth(width * inputs.size)).map { bit ->
            when {
                varying and (1 shl bit) != 0 -> LogicValue.ERROR
                minimum and (1 shl bit) != 0 -> LogicValue.HIGH
                else -> LogicValue.LOW
            }
        })
    }

    private fun bitFinder(input: LogicVector, mode: String): List<LogicVector> {
        val want = if (mode.endsWith('0')) LogicValue.LOW else LogicValue.HIGH
        val positions = if (mode.startsWith("high")) input.width - 1 downTo 0 else 0 until input.width
        for (position in positions) {
            val bit = input.bit(position)
            if (bit == want) return listOf(LogicVector.high(1), LogicVector.of(indexWidth(input.width), position.toULong()))
            if (bit == LogicValue.UNKNOWN || bit == LogicValue.ERROR) return listOf(LogicVector.error(1), LogicVector.error(indexWidth(input.width)))
        }
        return listOf(LogicVector.low(1), LogicVector.low(indexWidth(input.width)))
    }

    private fun undefined(width: Int, vararg values: LogicVector): List<LogicVector> =
        List(2) { if (values.any { it.isError }) LogicVector.error(width) else LogicVector.unknown(width) }

    private fun firstBit(value: LogicVector, predicate: (LogicValue) -> Boolean): Int =
        (0 until value.width).firstOrNull { predicate(value.bit(it)) } ?: value.width

    private fun signedValue(value: LogicVector): Long = (value.value shl (64 - value.width)).toLong() shr (64 - value.width)

    private fun number(value: LogicVector, unsigned: Boolean): UInt128 {
        val signed = !unsigned && value.bit(value.width - 1) == LogicValue.HIGH
        return if (signed) UInt128(ULong.MAX_VALUE, value.value or (mask(value.width).inv())) else UInt128(0uL, value.value)
    }

    private fun split(value: UInt128, width: Int) = listOf(LogicVector.of(width, value.low), LogicVector.of(width, (value shr width).low))
    private fun mask(width: Int) = if (width == 64) ULong.MAX_VALUE else (1uL shl width) - 1uL
}

/** Fixed-width arithmetic is sufficient because every component publishes at most 128 bits. */
private data class UInt128(val high: ULong, val low: ULong) : Comparable<UInt128> {
    val isNegative get() = high shr 63 != 0uL
    override fun compareTo(other: UInt128): Int = high.compareTo(other.high).takeIf { it != 0 } ?: low.compareTo(other.low)
    operator fun plus(other: UInt128): UInt128 {
        val sum = low + other.low
        return UInt128(high + other.high + if (sum < low) 1uL else 0uL, sum)
    }
    operator fun unaryMinus() = UInt128(high.inv(), low.inv()) + ONE
    operator fun minus(other: UInt128) = this + -other
    infix fun or(other: UInt128) = UInt128(high or other.high, low or other.low)
    infix fun shl(amount: Int): UInt128 = when {
        amount == 0 -> this
        amount < 64 -> UInt128((high shl amount) or (low shr (64 - amount)), low shl amount)
        amount < 128 -> UInt128(low shl (amount - 64), 0uL)
        else -> ZERO
    }
    infix fun shr(amount: Int): UInt128 = when {
        amount == 0 -> this
        amount < 64 -> UInt128(high shr amount, (low shr amount) or (high shl (64 - amount)))
        amount < 128 -> UInt128(0uL, high shr (amount - 64))
        else -> ZERO
    }
    fun bit(position: Int): Boolean = if (position < 64) low and (1uL shl position) != 0uL else high and (1uL shl (position - 64)) != 0uL
    private fun withBit(position: Int) = if (position < 64) copy(low = low or (1uL shl position)) else copy(high = high or (1uL shl (position - 64)))
    operator fun times(other: UInt128): UInt128 {
        val a0 = low and 0xffffffffuL
        val a1 = low shr 32
        val b0 = other.low and 0xffffffffuL
        val b1 = other.low shr 32
        val first = a0 * b0
        val middle = a1 * b0 + (first shr 32)
        val upper = (middle and 0xffffffffuL) + a0 * b1
        val productHigh = a1 * b1 + (middle shr 32) + (upper shr 32)
        return UInt128(productHigh + high * other.low + low * other.high, low * other.low)
    }
    fun divide(divisor: UInt128): Pair<UInt128, UInt128> {
        require(divisor != ZERO)
        var quotient = ZERO
        var remainder = ZERO
        for (position in 127 downTo 0) {
            remainder = (remainder shl 1) or if (bit(position)) ONE else ZERO
            if (remainder >= divisor) {
                remainder -= divisor
                quotient = quotient.withBit(position)
            }
        }
        return quotient to remainder
    }
    companion object {
        val ZERO = UInt128(0uL, 0uL)
        val ONE = UInt128(0uL, 1uL)
    }
}
