package dev.logisim.mobile.core

internal object WiringValues {
    fun initialPin(width: Int, behavior: String, initial: ULong): LogicVector =
        if (behavior == "tristate") LogicVector.unknown(width) else LogicVector.of(width, initial)

    fun pinDrive(value: LogicVector, behavior: String): LogicVector {
        val pull = when (behavior) {
            "pullup" -> LogicValue.HIGH
            "pulldown" -> LogicValue.LOW
            else -> return value
        }
        return LogicVector.fromBits((0 until value.width).map { bit ->
            value.bit(bit).let { if (it == LogicValue.UNKNOWN) pull else it }
        })
    }

    fun extend(input: LogicVector, outputWidth: Int, type: String, extension: LogicVector = LogicVector.unknown(1)): LogicVector {
        val bit = when (type) {
            "zero" -> LogicValue.LOW
            "one" -> LogicValue.HIGH
            "sign" -> input.bit(input.width - 1)
            "input" -> if (extension.width == 1) extension.bit(0) else LogicValue.ERROR
            else -> throw IllegalArgumentException("Unknown bit extension: $type")
        }
        return input.resize(outputWidth, bit)
    }

    fun transistor(input: LogicVector, gate: LogicVector, pType: Boolean, original: Boolean = false): LogicVector {
        val control = if (gate.width == 1) gate.bit(0) else LogicValue.ERROR
        if (control == LogicValue.UNKNOWN || control == LogicValue.ERROR) return uncertainGate(input)
        val enabled = if (pType) LogicValue.LOW else LogicValue.HIGH
        if (control != enabled) return LogicVector.unknown(input.width)
        if (original) return input
        return LogicVector.fromBits((0 until input.width).map { bit ->
            input.bit(bit).let { if (it == enabled) LogicValue.UNKNOWN else it }
        })
    }

    fun transmissionGate(input: LogicVector, pGate: LogicVector, nGate: LogicVector): LogicVector {
        val p = if (pGate.width == 1) pGate.bit(0) else LogicValue.ERROR
        val n = if (nGate.width == 1) nGate.bit(0) else LogicValue.ERROR
        val pDefined = p == LogicValue.LOW || p == LogicValue.HIGH
        val nDefined = n == LogicValue.LOW || n == LogicValue.HIGH
        return if (pDefined && nDefined && p != n) {
            if (p == LogicValue.HIGH) LogicVector.unknown(input.width) else input
        } else uncertainGate(input)
    }

    fun clock(ticks: Long, highDuration: Int, lowDuration: Int, phase: Int): LogicVector {
        require(highDuration > 0 && lowDuration > 0)
        val cycle = highDuration.toLong() + lowDuration
        val position = ((ticks % cycle) + (phase.toLong() % cycle) + cycle) % cycle
        return LogicVector.fromBit(if (position < lowDuration) LogicValue.LOW else LogicValue.HIGH)
    }

    private fun uncertainGate(input: LogicVector): LogicVector = LogicVector.fromBits(
        (0 until input.width).map { if (input.bit(it) == LogicValue.UNKNOWN) LogicValue.UNKNOWN else LogicValue.ERROR },
    )
}

internal data class PowerOnResetPulse(
    val startedAtMillis: Long,
    val durationMillis: Long,
    val startsHigh: Boolean,
) {
    init { require(startedAtMillis >= 0 && durationMillis > 0) }
    val deadlineMillis: Long get() = if (Long.MAX_VALUE - startedAtMillis < durationMillis) Long.MAX_VALUE else startedAtMillis + durationMillis
    fun output(nowMillis: Long): LogicVector {
        val high = if (nowMillis < deadlineMillis) startsHigh else !startsHigh
        return LogicVector.fromBit(if (high) LogicValue.HIGH else LogicValue.LOW)
    }
}
