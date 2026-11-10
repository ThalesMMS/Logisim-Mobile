package dev.logisim.mobile.core

internal enum class FlipFlopOperation { D, T, JK, SR }

internal data class StoredValue(
    val value: LogicVector,
    val clock: LogicValue = LogicValue.LOW,
)

internal data class StoredStages(
    val values: List<LogicVector>,
    val clock: LogicValue = LogicValue.LOW,
)

internal data class CounterTransition(val state: StoredValue, val carry: LogicValue)

internal data class OriginalRandomSequence(
    val seed: Long,
    val value: UInt,
    val clock: LogicValue = LogicValue.LOW,
    val initialSeed: Long = seed,
    val resetHeld: Boolean = false,
) {
    constructor(seed: Int) : this(seed.toLong(), seed.toUInt())

    fun next(): OriginalRandomSequence {
        // Logisim 2.7.1 displays the configured seed before the first step.
        val nextSeed = (seed * MULTIPLIER + 0xBL) and MASK
        return copy(seed = nextSeed, value = (nextSeed ushr 12).toUInt())
    }

    companion object {
        private const val MULTIPLIER = 0x5DEECE66DL
        private const val MASK = 0xFFFFFFFFFFFFL

        fun unseeded(overrideSeed: ULong?, previousInitialSeed: Long? = null): OriginalRandomSequence {
            val candidate = (overrideSeed?.toLong() ?: (SystemEpochClock.epochMillis() xor MULTIPLIER)) and MASK
            val start = if (candidate == previousInitialSeed) (candidate + MULTIPLIER) and MASK else candidate
            return OriginalRandomSequence(start, start.toUInt())
        }
    }
}

internal data class RandomSequence(
    val seed: ULong,
    val words: List<ULong>,
    val clock: LogicValue = LogicValue.LOW,
    val reset: LogicValue = LogicValue.UNKNOWN,
) {
    val value: ULong get() = rotateLeft(words[0] + words[3], 23) + words[0]

    fun next(): RandomSequence {
        val next = words.toMutableList()
        val shifted = next[1] shl 17
        next[2] = next[2] xor next[0]
        next[3] = next[3] xor next[1]
        next[1] = next[1] xor next[2]
        next[0] = next[0] xor next[3]
        next[2] = next[2] xor shifted
        next[3] = rotateLeft(next[3], 45)
        return copy(words = next)
    }

    companion object {
        fun seeded(seed: ULong): RandomSequence {
            var mixed = seed
            return RandomSequence(seed, List(4) {
                mixed += 0x9E3779B97F4A7C15uL
                var value = mixed
                value = (value xor (value shr 30)) * 0xBF58476D1CE4E5B9uL
                value = (value xor (value shr 27)) * 0x94D049BB133111EBuL
                value xor (value shr 31)
            })
        }
    }
}

private fun rotateLeft(value: ULong, shift: Int): ULong =
    (value shl shift) or (value shr (64 - shift))

internal object SequentialLogic {
    fun triggered(old: LogicValue, clock: LogicValue, trigger: String): Boolean = when (trigger) {
        "falling" -> old == LogicValue.HIGH && clock == LogicValue.LOW
        "high" -> clock == LogicValue.HIGH
        "low" -> clock == LogicValue.LOW
        else -> old == LogicValue.LOW && clock == LogicValue.HIGH
    }

    fun flipFlop(
        previous: StoredValue,
        operation: FlipFlopOperation,
        inputs: List<LogicValue>,
        clock: LogicValue,
        clear: LogicValue,
        preset: LogicValue,
        trigger: String,
        enable: LogicValue,
    ): StoredValue {
        val old = previous.value.bit(0)
        val next = when {
            clear == LogicValue.HIGH -> LogicValue.LOW
            preset == LogicValue.HIGH -> LogicValue.HIGH
            enable == LogicValue.LOW || !triggered(previous.clock, clock, trigger) -> old
            else -> {
                val first = inputs.firstOrNull() ?: LogicValue.UNKNOWN
                val second = inputs.getOrNull(1) ?: LogicValue.UNKNOWN
                val computed = when (operation) {
                    FlipFlopOperation.D -> first
                    FlipFlopOperation.T -> if (first == LogicValue.HIGH) {
                        if (old == LogicValue.UNKNOWN) LogicValue.HIGH else old.inverted()
                    } else if (old == LogicValue.UNKNOWN) LogicValue.LOW else old
                    FlipFlopOperation.JK, FlipFlopOperation.SR -> when {
                        first == LogicValue.LOW && second == LogicValue.LOW -> old
                        first == LogicValue.LOW && second == LogicValue.HIGH -> LogicValue.LOW
                        first == LogicValue.HIGH && second == LogicValue.LOW -> LogicValue.HIGH
                        first == LogicValue.HIGH && second == LogicValue.HIGH ->
                            if (operation == FlipFlopOperation.JK) old.inverted() else LogicValue.ERROR
                        else -> LogicValue.UNKNOWN
                    }
                }
                // The desktop retains the current state when the next bit is not definite.
                if (computed == LogicValue.LOW || computed == LogicValue.HIGH) computed else old
            }
        }
        return StoredValue(LogicVector.fromBit(next), clock)
    }

    fun register(
        previous: StoredValue,
        data: LogicVector,
        clock: LogicValue,
        clear: LogicValue,
        enable: LogicValue,
        trigger: String,
    ): StoredValue = StoredValue(when {
        clear == LogicValue.HIGH -> LogicVector.low(data.width)
        triggered(previous.clock, clock, trigger) && enable != LogicValue.LOW -> data
        else -> previous.value.resize(data.width)
    }, clock)

    fun counter(
        previous: StoredValue,
        data: LogicVector,
        clock: LogicValue,
        clear: LogicValue,
        load: LogicValue,
        enable: LogicValue,
        up: LogicValue,
        maximum: ULong,
        onGoal: String,
        trigger: String,
        original: Boolean = false,
    ): CounterTransition {
        val old = previous.value.resize(data.width)
        val counting = enable != LogicValue.LOW
        val loading = load == LogicValue.HIGH && (!original || !counting)
        val upwards = if (original) load != LogicValue.HIGH || !counting else up != LogicValue.LOW
        fun loaded(): LogicVector {
            val raw = data.toULongOrNull() ?: if (original) 0uL else ULong.MAX_VALUE
            // Logisim 2.7.1 stores a 32-bit counter's data and limit as signed integers.
            val aboveMaximum = if (original && data.width == 32) raw.toInt() > maximum.toInt() else raw > maximum
            return LogicVector.of(data.width, if (aboveMaximum) raw and maximum else raw)
        }
        val next = when {
            clear == LogicValue.HIGH -> LogicVector.low(data.width)
            !triggered(previous.clock, clock, trigger) ->
                if (old.isUnknown) LogicVector.high(data.width) else old
            loading -> loaded()
            !old.isFullyDefined -> LogicVector.error(data.width)
            !counting -> old
            else -> {
                val value = old.toULongOrNull()!!
                val atGoal = value == if (upwards) maximum else 0uL
                when {
                    atGoal && onGoal == "stay" -> old
                    atGoal && onGoal == "load" -> loaded()
                    atGoal && onGoal != "continue" -> LogicVector.of(data.width, if (upwards) 0uL else maximum)
                    else -> LogicVector.of(data.width, if (upwards) value + 1uL else value - 1uL)
                }
            }
        }
        val raw = if (!triggered(previous.clock, clock, trigger) && old.isUnknown) ULong.MAX_VALUE else next.toULongOrNull()
        val carry = if (clear != LogicValue.HIGH && raw != null && raw == if (upwards) maximum else 0uL)
            LogicValue.HIGH else LogicValue.LOW
        return CounterTransition(StoredValue(next, clock), carry)
    }

    fun shiftRegister(
        previous: StoredStages,
        serial: LogicVector,
        parallel: List<LogicVector>?,
        clock: LogicValue,
        clear: LogicValue,
        shift: LogicValue,
        load: LogicValue,
        trigger: String,
    ): StoredStages {
        val length = previous.values.size
        val next = when {
            clear == LogicValue.HIGH -> List(length) { LogicVector.low(serial.width) }
            !triggered(previous.clock, clock, trigger) -> previous.values
            parallel != null && load == LogicValue.HIGH -> parallel.asReversed()
            shift != LogicValue.LOW -> previous.values.drop(1) + serial
            else -> previous.values
        }
        return StoredStages(next.map { it.resize(serial.width) }, clock)
    }

    fun random(
        previous: RandomSequence,
        seed: ULong,
        implicitSeed: ULong,
        clock: LogicValue,
        reset: LogicValue,
        enable: LogicValue,
        trigger: String,
    ): RandomSequence {
        var effectiveSeed = previous.seed
        if (previous.reset == LogicValue.LOW && reset == LogicValue.HIGH) {
            effectiveSeed = if (seed != 0uL) seed else {
                if (implicitSeed == previous.seed) implicitSeed + 0x9E3779B97F4A7C15uL else implicitSeed
            }
        }
        val next = when {
            reset == LogicValue.HIGH -> RandomSequence.seeded(effectiveSeed)
            triggered(previous.clock, clock, trigger) && enable != LogicValue.LOW -> previous.next()
            else -> previous
        }
        return next.copy(seed = effectiveSeed, clock = clock, reset = reset)
    }
}
