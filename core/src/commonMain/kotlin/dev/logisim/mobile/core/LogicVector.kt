package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/** A Logisim value with four possible states per bit. Bit zero is the least significant bit. */
class LogicVector private constructor(
    val width: Int,
    val value: ULong,
    val unknownMask: ULong,
    val errorMask: ULong,
) {
    val knownMask: ULong get() = mask(width) and (unknownMask or errorMask).inv()
    val isFullyDefined: Boolean get() = unknownMask == 0uL && errorMask == 0uL
    val isError: Boolean get() = errorMask != 0uL
    val isUnknown: Boolean get() = unknownMask == mask(width)
    val display: String get() = buildString(width) {
        for (index in width - 1 downTo 0) append(bit(index).display)
    }

    fun hexadecimalDisplay(): String = buildString {
        append("0x")
        for (start in ((width - 1) / 4) * 4 downTo 0 step 4) {
            val digitMask = 15uL shl start
            append(when {
                errorMask and digitMask != 0uL -> 'E'
                unknownMask and digitMask != 0uL -> '?'
                else -> "0123456789ABCDEF"[((value shr start) and 15uL).toInt()]
            })
        }
    }

    fun bit(index: Int): LogicValue {
        requireLocalized(index in 0 until width) { message(Messages.ErrorBitIndex, "index" to index) }
        val selected = 1uL shl index
        return when {
            errorMask and selected != 0uL -> LogicValue.ERROR
            unknownMask and selected != 0uL -> LogicValue.UNKNOWN
            value and selected != 0uL -> LogicValue.HIGH
            else -> LogicValue.LOW
        }
    }

    fun toULongOrNull(): ULong? = if (isFullyDefined) value else null

    /** Gate inversion follows desktop Value.not(): undefined inputs become errors. */
    fun inverted(): LogicVector = fromMasks(width, value.inv(), 0uL, errorMask or unknownMask)
    fun not(): LogicVector = inverted()

    infix fun and(other: LogicVector): LogicVector {
        val outputWidth = maxOf(width, other.width)
        val a = resize(outputWidth)
        val b = other.resize(outputWidth)
        val zeros = (a.knownMask and a.value.inv()) or (b.knownMask and b.value.inv())
        val errors = (a.errorMask or b.errorMask or a.unknownMask or b.unknownMask) and zeros.inv()
        return fromMasks(outputWidth, a.value and b.value, 0uL, errors)
    }

    infix fun or(other: LogicVector): LogicVector {
        val outputWidth = maxOf(width, other.width)
        val a = resize(outputWidth)
        val b = other.resize(outputWidth)
        val ones = a.value or b.value
        val errors = (a.errorMask or b.errorMask or a.unknownMask or b.unknownMask) and ones.inv()
        return fromMasks(outputWidth, ones, 0uL, errors)
    }

    infix fun xor(other: LogicVector): LogicVector {
        val outputWidth = maxOf(width, other.width)
        val a = resize(outputWidth)
        val b = other.resize(outputWidth)
        return fromMasks(outputWidth, a.value xor b.value, 0uL,
            a.errorMask or b.errorMask or a.unknownMask or b.unknownMask)
    }

    /** Combines drivers, treating UNKNOWN as an undriven bit, as desktop Value.combine does. */
    fun combine(other: LogicVector): LogicVector {
        val outputWidth = maxOf(width, other.width)
        val a = resize(outputWidth, LogicValue.UNKNOWN)
        val b = other.resize(outputWidth, LogicValue.UNKNOWN)
        val disagree = (a.value xor b.value) and a.knownMask and b.knownMask
        return fromMasks(outputWidth, a.value or b.value, a.unknownMask and b.unknownMask,
            a.errorMask or b.errorMask or disagree)
    }

    /** Matches desktop Value.controls; components apply their undefined-enable policy separately. */
    fun controls(data: LogicVector): LogicVector {
        if (width == 1) return when (bit(0)) {
            LogicValue.LOW -> unknown(data.width)
            LogicValue.HIGH, LogicValue.UNKNOWN -> data
            LogicValue.ERROR -> error(data.width)
        }
        if (width != data.width) return error(data.width)
        val disabled = knownMask and value.inv()
        val enabled = value or unknownMask
        return fromMasks(width, enabled and data.value,
            disabled or data.unknownMask,
            errorMask or (data.errorMask and disabled.inv()))
    }

    fun resize(newWidth: Int, fill: LogicValue = LogicValue.LOW): LogicVector {
        requireWidth(newWidth)
        if (newWidth == width) return this
        if (newWidth < width) return fromMasks(newWidth, value, unknownMask, errorMask)
        val added = mask(newWidth) and mask(width).inv()
        return fromMasks(newWidth,
            value or if (fill == LogicValue.HIGH) added else 0uL,
            unknownMask or if (fill == LogicValue.UNKNOWN) added else 0uL,
            errorMask or if (fill == LogicValue.ERROR) added else 0uL)
    }

    fun resized(newWidth: Int, fill: LogicValue = LogicValue.LOW): LogicVector = resize(newWidth, fill)

    fun slice(start: Int, width: Int): LogicVector {
        requireWidth(width)
        requireLocalized(start >= 0 && start + width <= this.width) { message(Messages.ErrorBitSlice) }
        return fromMasks(width, value shr start, unknownMask shr start, errorMask shr start)
    }

    /** Appends [low] below this value. */
    fun concat(low: LogicVector): LogicVector {
        requireLocalized(width + low.width <= 64) { message(Messages.ErrorBitConcat) }
        return fromMasks(width + low.width,
            (value shl low.width) or low.value,
            (unknownMask shl low.width) or low.unknownMask,
            (errorMask shl low.width) or low.errorMask)
    }

    fun withBit(index: Int, bit: LogicValue): LogicVector {
        requireLocalized(index in 0 until width) { message(Messages.ErrorBitIndex, "index" to index) }
        val selected = 1uL shl index
        return fromMasks(width,
            (value and selected.inv()) or if (bit == LogicValue.HIGH) selected else 0uL,
            (unknownMask and selected.inv()) or if (bit == LogicValue.UNKNOWN) selected else 0uL,
            (errorMask and selected.inv()) or if (bit == LogicValue.ERROR) selected else 0uL)
    }

    override fun equals(other: Any?): Boolean = other is LogicVector && width == other.width &&
        value == other.value && unknownMask == other.unknownMask && errorMask == other.errorMask

    override fun hashCode(): Int = (((width * 31 + value.hashCode()) * 31 +
        unknownMask.hashCode()) * 31 + errorMask.hashCode())

    override fun toString(): String = display

    companion object {
        fun low(width: Int): LogicVector = fromMasks(width, 0uL, 0uL, 0uL)
        fun high(width: Int): LogicVector = of(width, mask(width))
        fun unknown(width: Int): LogicVector = fromMasks(width, 0uL, mask(width), 0uL)
        fun error(width: Int): LogicVector = fromMasks(width, 0uL, 0uL, mask(width))
        fun of(width: Int, value: ULong): LogicVector = fromMasks(width, value, 0uL, 0uL)
        fun fromBit(bit: LogicValue): LogicVector = repeat(bit, 1)

        fun repeat(bit: LogicValue, width: Int): LogicVector = when (bit) {
            LogicValue.LOW -> low(width)
            LogicValue.HIGH -> high(width)
            LogicValue.UNKNOWN -> unknown(width)
            LogicValue.ERROR -> error(width)
        }

        /** Input bits are ordered least significant first. */
        fun fromBits(bits: List<LogicValue>): LogicVector {
            requireWidth(bits.size)
            var value = 0uL
            var unknown = 0uL
            var error = 0uL
            bits.forEachIndexed { index, bit ->
                val selected = 1uL shl index
                when (bit) {
                    LogicValue.LOW -> Unit
                    LogicValue.HIGH -> value = value or selected
                    LogicValue.UNKNOWN -> unknown = unknown or selected
                    LogicValue.ERROR -> error = error or selected
                }
            }
            return fromMasks(bits.size, value, unknown, error)
        }

        fun fromMasks(width: Int, value: ULong, unknownMask: ULong, errorMask: ULong): LogicVector {
            val mask = mask(width)
            val error = errorMask and mask
            val unknown = unknownMask and mask and error.inv()
            return LogicVector(width, value and mask and unknown.inv() and error.inv(), unknown, error)
        }

        fun resolve(drivers: Iterable<LogicVector>, width: Int): LogicVector {
            requireWidth(width)
            return drivers.fold(unknown(width)) { result, driver ->
                result.combine(driver.resize(width, LogicValue.UNKNOWN))
            }
        }

        private fun requireWidth(width: Int) {
            requireLocalized(width in 1..64) { message(Messages.ErrorValueWidth) }
        }

        private fun mask(width: Int): ULong {
            requireWidth(width)
            return if (width == 64) ULong.MAX_VALUE else (1uL shl width) - 1uL
        }
    }
}
