package dev.logisim.mobile.core

/** Per-instance TTL state; physical pin numbers are one-based. */
data class TtlState(
    val data: Map<String, LogicVector> = emptyMap(),
    val clocks: Map<String, LogicValue> = emptyMap(),
)

data class TtlResult(
    val outputs: Map<Int, LogicValue>,
    val state: TtlState = TtlState(),
    val delay: Long = 1,
    val nextWakeupMillis: Long? = null,
    val outputDelays: Map<Int, Long> = emptyMap(),
)

data class TtlChipSpec(
    val model: String,
    val pinCount: Int,
    val outputPins: Set<Int>,
    val inoutPins: Set<Int> = emptySet(),
    val unusedPins: Set<Int> = emptySet(),
    val pinNames: Map<Int, String> = emptyMap(),
    val gndPin: Int = pinCount / 2,
    val vccPin: Int = pinCount,
    val height: Int = 60,
) {
    val signalPins: List<Int> get() = (1..pinCount).filter { it != gndPin && it != vccPin && it !in unusedPins }
    val inputPins: List<Int> get() = signalPins.filter { it !in outputPins }
    val drivenPins: Set<Int> get() = outputPins + inoutPins
}

internal fun Map<Int, LogicValue>.pin(number: Int): LogicValue = get(number) ?: LogicValue.UNKNOWN
internal fun LogicValue.ttlNot(): LogicValue = when (this) {
    LogicValue.LOW -> LogicValue.HIGH
    LogicValue.HIGH -> LogicValue.LOW
    else -> LogicValue.ERROR
}
internal infix fun LogicValue.ttlAnd(other: LogicValue): LogicValue = when {
    this == LogicValue.LOW || other == LogicValue.LOW -> LogicValue.LOW
    this == LogicValue.HIGH && other == LogicValue.HIGH -> LogicValue.HIGH
    else -> LogicValue.ERROR
}
internal infix fun LogicValue.ttlOr(other: LogicValue): LogicValue = when {
    this == LogicValue.HIGH || other == LogicValue.HIGH -> LogicValue.HIGH
    this == LogicValue.LOW && other == LogicValue.LOW -> LogicValue.LOW
    else -> LogicValue.ERROR
}
internal infix fun LogicValue.ttlXor(other: LogicValue): LogicValue = when {
    this !in listOf(LogicValue.LOW, LogicValue.HIGH) || other !in listOf(LogicValue.LOW, LogicValue.HIGH) -> LogicValue.ERROR
    this != other -> LogicValue.HIGH
    else -> LogicValue.LOW
}
internal fun Boolean.ttlValue(): LogicValue = if (this) LogicValue.HIGH else LogicValue.LOW
