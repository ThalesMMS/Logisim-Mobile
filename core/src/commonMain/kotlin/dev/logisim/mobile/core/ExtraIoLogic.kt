package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Algorithms shared by the BFH and I/O-Extra components. */
object ExtraIoLogic {
    fun binaryToBcd(input: LogicVector, binaryWidth: Int): List<LogicVector> {
        requireLocalized(binaryWidth in 4..13) { message("extra_io_binary_width_invalid") }
        val digits = when (binaryWidth) { in 4..6 -> 2; in 7..9 -> 3; else -> 4 }
        var value = input.resize(binaryWidth).toULongOrNull()?.toInt() ?: -1
        val result = MutableList(digits) { LogicVector.low(4) }
        var divisor = when (digits) { 2 -> 10; 3 -> 100; else -> 1000 }
        for (index in digits - 1 downTo 0) {
            val digit = value / divisor
            result[index] = LogicVector.of(4, digit.toULong())
            value -= digit * divisor
            divisor /= 10
        }
        return result
    }

    fun bcdToSegments(input: LogicVector): List<LogicVector> {
        val masks = intArrayOf(0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f)
        val digit = input.toULongOrNull()?.toInt()
        if (digit == null || digit !in masks.indices) return List(7) { LogicVector.unknown(1) }
        return List(7) { LogicVector.of(1, ((masks[digit] shr it) and 1).toULong()) }
    }

    fun switch(input: LogicVector, closed: Boolean): LogicVector =
        if (closed) input else LogicVector.unknown(input.width)

    fun twoWaySwitch(input: LogicVector, second: Boolean): List<LogicVector> =
        if (second) listOf(LogicVector.unknown(input.width), input)
        else listOf(input, LogicVector.unknown(input.width))

    fun twoWaySelect(first: LogicVector, second: LogicVector, selectSecond: Boolean): LogicVector =
        if (selectSecond) second else first

    fun twoPinLed(anode: LogicValue, cathode: LogicValue, bipolar: Boolean): TwoPinLedState = when {
        anode == LogicValue.HIGH && cathode == LogicValue.LOW -> TwoPinLedState.FORWARD
        bipolar && anode == LogicValue.LOW && cathode == LogicValue.HIGH -> TwoPinLedState.REVERSE
        else -> TwoPinLedState.OFF
    }
}

enum class TwoPinLedState : ComponentState { OFF, FORWARD, REVERSE }

data class ExtraSliderState(val position: Int = 0, val rightToLeft: Boolean = false) : ComponentState {
    init { requireLocalized(position in 0..255) { message("extra_io_slider_position_invalid") } }

    fun move(position: Int): ExtraSliderState = copy(position = position.coerceIn(0, 255))

    fun direction(rightToLeft: Boolean): ExtraSliderState =
        if (this.rightToLeft == rightToLeft) this
        else copy(position = 255 - position, rightToLeft = rightToLeft)

    fun value(width: Int): LogicVector {
        requireLocalized(width in 1..8) { message("extra_io_slider_width_invalid") }
        val value = if (rightToLeft) 255 - position else position
        return LogicVector.of(width, (value shr (8 - width)).toULong())
    }
}

/** The contents use the desktop's row-major input/AND matrix followed by AND/output cells. */
data class PlaRomTable(
    val inputCount: Int,
    val termCount: Int,
    val outputCount: Int,
    val literals: List<List<Int>>,
    val connections: List<List<Boolean>>,
) {
    init {
        requireLocalized(inputCount in 1..32 && termCount in 1..32 && outputCount in 1..32) { message("extra_io_pla_dimensions_invalid") }
        requireLocalized(literals.size == termCount && literals.all { row -> row.size == inputCount && row.all { it in 0..2 } }) { message("extra_io_pla_literals_invalid") }
        requireLocalized(connections.size == termCount && connections.all { it.size == outputCount }) { message("extra_io_pla_connections_invalid") }
    }

    fun evaluate(input: LogicVector, chipSelect: LogicValue, activeHigh: Boolean): LogicVector {
        if (chipSelect == if (activeHigh) LogicValue.LOW else LogicValue.HIGH) {
            return LogicVector.unknown(outputCount)
        }
        val terms = literals.map { row ->
            var result = LogicValue.HIGH
            var connected = false
            for ((index, literal) in row.withIndex()) {
                if (literal == 0) continue
                connected = true
                val bit = input.bit(inputCount - 1 - index)
                if (bit == LogicValue.UNKNOWN || bit == LogicValue.ERROR) result = LogicValue.ERROR
                else if ((literal == 1 && bit == LogicValue.HIGH) || (literal == 2 && bit == LogicValue.LOW)) {
                    result = LogicValue.LOW
                    break
                }
            }
            if (connected) result else LogicValue.ERROR
        }
        val outputs = (0 until outputCount).map { column ->
            val selected = (0 until termCount).filter { connections[it][column] }.map { terms[it] }
            when {
                selected.isEmpty() -> LogicValue.ERROR
                LogicValue.HIGH in selected -> LogicValue.HIGH
                selected.all { it == LogicValue.LOW } -> LogicValue.LOW
                else -> LogicValue.ERROR
            }
        }
        return LogicVector.fromBits(outputs.reversed())
    }

    fun withLiteral(term: Int, input: Int, value: Int): PlaRomTable {
        requireLocalized(term in 0 until termCount && input in 0 until inputCount && value in 0..2) { message("extra_io_pla_literal_edit_invalid") }
        return copy(literals = literals.mapIndexed { row, cells ->
            if (row != term) cells else cells.mapIndexed { column, old -> if (column == input) value else old }
        })
    }

    fun withConnection(term: Int, output: Int, value: Boolean): PlaRomTable {
        requireLocalized(term in 0 until termCount && output in 0 until outputCount) { message("extra_io_pla_connection_edit_invalid") }
        return copy(connections = connections.mapIndexed { row, cells ->
            if (row != term) cells else cells.mapIndexed { column, old -> if (column == output) value else old }
        })
    }

    fun resize(inputs: Int, terms: Int, outputs: Int): PlaRomTable = empty(inputs, terms, outputs).copy(
        literals = List(terms) { row -> List(inputs) { column -> literals.getOrNull(row)?.getOrNull(column) ?: 0 } },
        connections = List(terms) { row -> List(outputs) { column -> connections.getOrNull(row)?.getOrNull(column) ?: false } },
    )

    fun encode(): String {
        val values = literals.flatten() + connections.flatten().map { if (it) 1 else 0 }
        if (values.all { it == 0 }) return ""
        return buildList {
            var start = 0
            while (start < values.size) {
                var end = start + 1
                while (end < values.size && values[end] == values[start]) end++
                val count = end - start
                if (count >= 3) add("${values[start]}*$count") else repeat(count) { add(values[start].toString()) }
                start = end
            }
        }.joinToString(" ")
    }

    companion object {
        fun empty(inputs: Int, terms: Int, outputs: Int): PlaRomTable = PlaRomTable(
            inputs, terms, outputs, List(terms) { List(inputs) { 0 } }, List(terms) { List(outputs) { false } },
        )

        fun decode(inputs: Int, terms: Int, outputs: Int, contents: String): PlaRomTable {
            val table = empty(inputs, terms, outputs)
            if (contents.isBlank()) return table
            val size = inputs * terms + outputs * terms
            val values = mutableListOf<Int>()
            for (token in contents.trim().split(Regex("\\s+"))) {
                val parts = token.split('*')
                requireLocalized(parts.size in 1..2) { message("extra_io_pla_cell_invalid") }
                val value = parts[0].toIntOrNull() ?: throw LocalizedArgumentException(message("extra_io_pla_cell_invalid"))
                val count = if (parts.size == 1) 1 else parts[1].toIntOrNull()
                    ?: throw LocalizedArgumentException(message("extra_io_pla_repeat_invalid"))
                requireLocalized(count > 0 && count <= size - values.size) { message("extra_io_pla_contents_exceeded") }
                repeat(count) { values += value }
            }
            while (values.size < size) values += 0
            requireLocalized(values.take(inputs * terms).all { it in 0..2 } && values.drop(inputs * terms).all { it in 0..1 }) {
                message("extra_io_pla_value_invalid")
            }
            return table.copy(
                literals = List(terms) { row -> List(inputs) { column -> values[row * inputs + column] } },
                connections = List(terms) { row -> List(outputs) { column -> values[inputs * terms + row * outputs + column] == 1 } },
            )
        }
    }
}

data class ExtraOscilloscopeState(
    val inputCount: Int,
    val stateCount: Int,
    val samples: List<List<LogicValue>> = emptyList(),
    val lastClock: LogicValue = LogicValue.UNKNOWN,
    val clockNumber: Int = stateCount,
) : ComponentState {
    init { requireLocalized(inputCount in 1..32 && stateCount in 4..35) { message("extra_io_scope_dimensions_invalid") } }

    fun resize(inputs: Int, states: Int): ExtraOscilloscopeState = ExtraOscilloscopeState(
        inputs, states,
        samples.takeLast(states * 2).map { sample -> List(inputs + 1) { sample.getOrNull(it) ?: LogicValue.UNKNOWN } },
        lastClock, clockNumber + states - stateCount,
    )

    fun sample(clock: LogicValue, inputs: List<LogicValue>, enable: LogicValue, clear: LogicValue): ExtraOscilloscopeState {
        requireLocalized(inputs.size == inputCount) { message("extra_io_scope_inputs_invalid") }
        if (clear == LogicValue.HIGH) return ExtraOscilloscopeState(inputCount, stateCount)
        if (clock == LogicValue.UNKNOWN || enable == LogicValue.LOW) return this
        if (lastClock == LogicValue.UNKNOWN) return copy(lastClock = clock)
        fun sampled(value: LogicValue) = if (value == LogicValue.LOW || value == LogicValue.HIGH) value else LogicValue.UNKNOWN
        val values = (listOf(clock) + inputs).map(::sampled)
        if (clock != lastClock) {
            val full = samples.size == stateCount * 2
            return copy(
                samples = (samples + listOf(values)).takeLast(stateCount * 2),
                lastClock = clock,
                clockNumber = if (full && clock == LogicValue.HIGH) (clockNumber + 1).let { if (it < 100) it else 1 } else clockNumber,
            )
        }
        if (samples.isEmpty()) return this
        return copy(samples = samples.dropLast(1) + listOf(listOf(samples.last()[0]) + inputs.map(::sampled)))
    }
}

data class Ne555State(
    val capacitorVoltage: Double = 0.0,
    val outputHigh: Boolean = true,
    val discharging: Boolean = true,
    val running: Boolean = false,
    val waiting: Boolean = false,
    val activeElapsedSeconds: Double = 0.0,
    val lastTimeMillis: Long? = null,
) : ComponentState {
    fun advance(timeMillis: Long, autoPropagating: Boolean, monostable: Boolean, trigger: LogicValue,
                resistanceOhms: Int = 71660, capacitanceMicrofarads: Int = 10): Ne555State {
        requireLocalized(resistanceOhms in 1000..10_000_000 && capacitanceMicrofarads in 10..1000) { message("extra_io_timer_parameters_invalid") }
        val dt = if (autoPropagating && lastTimeMillis != null)
            ((timeMillis - lastTimeMillis).coerceAtLeast(0) / 1000.0).coerceAtMost(0.1) else 0.0
        var next = copy(lastTimeMillis = timeMillis)
        val capacitance = capacitanceMicrofarads * 1e-6
        fun charge(voltage: Double, target: Double, resistance: Double): Double =
            voltage + (target - voltage) * (1.0 - exp(-dt / (resistance * capacitance))).coerceIn(0.0, 1.0 - 1e-6)
        if (!monostable) {
            if (!next.running && !next.waiting) next = next.copy(capacitorVoltage = 5.0 / 3, outputHigh = true, discharging = false, running = true)
            val voltage = charge(next.capacitorVoltage, if (next.outputHigh) 5.0 else 0.0,
                resistanceOhms.toDouble() + if (next.outputHigh) 1000.0 else 0.0)
            next = next.copy(capacitorVoltage = voltage)
            if (next.outputHigh && voltage >= 10.0 / 3) next = next.copy(outputHigh = false, discharging = true)
            else if (!next.outputHigh && voltage <= 5.0 / 3) next = next.copy(outputHigh = true, discharging = false)
            return next
        }
        if (!next.running && !next.waiting) {
            return next.copy(outputHigh = trigger == LogicValue.LOW, discharging = trigger != LogicValue.LOW,
                capacitorVoltage = 0.0, running = trigger == LogicValue.LOW, activeElapsedSeconds = 0.0)
        }
        if (next.running) {
            val elapsed = next.activeElapsedSeconds + dt
            val voltage = next.capacitorVoltage + (5.0 - next.capacitorVoltage) * (1 - exp(-dt / (resistanceOhms * capacitance)))
            val done = voltage >= 10.0 / 3 || elapsed >= 1.1 * resistanceOhms * capacitance
            return next.copy(capacitorVoltage = voltage, activeElapsedSeconds = elapsed,
                outputHigh = !done, discharging = done, running = !done, waiting = done)
        }
        val voltage = charge(next.capacitorVoltage, 0.0, 1000.0)
        return next.copy(capacitorVoltage = voltage, outputHigh = false, discharging = true,
            waiting = trigger == LogicValue.LOW || voltage >= 0.1)
    }
}

enum class BuzzerWaveform { Sine, Square, Triangle, Sawtooth, Noise }

data class BuzzerTone(
    val enabled: Boolean,
    val frequencyHz: Int,
    val amplitude: Double,
    val waveform: BuzzerWaveform = BuzzerWaveform.Sine,
    val duty: Int = 128,
    val channels: Int = 3,
    val smoothLevel: Int = 2,
    val smoothWidth: Int = 2,
) : ComponentState {
    init {
        requireLocalized(amplitude.isFinite() && amplitude in 0.0..32767.0) { message("extra_io_buzzer_amplitude_invalid") }
        requireLocalized(duty in 0..255 && channels in 1..3 && smoothLevel in 0..10 && smoothWidth in 1..10) { message("extra_io_buzzer_parameters_invalid") }
    }

    val audible: Boolean get() = enabled && frequencyHz in 20..20000 && amplitude > 0

    fun pcm(noise: () -> Double = { Random.nextDouble() }): BuzzerPcm {
        if (!audible) return BuzzerPcm(44100, ShortArray(0))
        val sampleRate = ceil(44100.0 / frequencyHz).toInt() * frequencyHz
        val cycle = (sampleRate / frequencyHz).coerceAtLeast(1)
        val values = DoubleArray(4 * cycle) { index ->
            // Evolution computes sample time before multiplying by frequency. This affects waveform edges.
            val phase = (index.toDouble() / sampleRate) * frequencyHz
            when (waveform) {
                BuzzerWaveform.Sine -> sin(phase * 2 * PI)
                BuzzerWaveform.Square -> if (phase % 1 < duty / 256.0) 1.0 else -1.0
                BuzzerWaveform.Triangle -> asin(sin(phase * 2 * PI)) * 2 / PI
                BuzzerWaveform.Sawtooth -> 2 * (phase % 1) - 1
                BuzzerWaveform.Noise -> noise().coerceIn(0.0, 1.0) * 2 - 1
            }
        }
        if (waveform != BuzzerWaveform.Sine && smoothLevel > 0) {
            val filtered = DoubleArray(values.size)
            repeat(smoothLevel) {
                var sum = 0.0
                for (index in values.indices) {
                    if (index > 2 * smoothWidth) {
                        filtered[index - smoothWidth - 1] = (sum - values[index - smoothWidth - 1]) / (2 * smoothWidth)
                        sum -= values[index - 2 * smoothWidth - 1]
                    }
                    sum += values[index]
                }
                for (index in smoothWidth until values.size - smoothWidth) values[index] = filtered[index]
            }
        }
        val pcm = ShortArray(sampleRate * 2)
        repeat(sampleRate) { frame ->
            val sample = (values[2 * cycle + frame % cycle] * amplitude).roundToInt().coerceIn(-32768, 32767).toShort()
            if (channels and 1 != 0) pcm[frame * 2] = sample
            if (channels and 2 != 0) pcm[frame * 2 + 1] = sample
        }
        return BuzzerPcm(sampleRate, pcm)
    }

    companion object {
        fun fromInputs(frequency: LogicVector, enable: LogicValue, volume: LogicVector, duty: LogicVector,
                       decihertz: Boolean = false, waveform: BuzzerWaveform = BuzzerWaveform.Sine,
                       channels: Int = 3, smoothLevel: Int = 2, smoothWidth: Int = 2): BuzzerTone {
            val hz = frequency.toULongOrNull()?.toInt()?.let { if (decihertz) it / 10 else it } ?: 440
            val maximum = if (volume.width == 64) ULong.MAX_VALUE.toDouble() else ((1uL shl volume.width) - 1uL).toDouble()
            val amplitude = volume.toULongOrNull()?.and(0xffffffffuL)?.toDouble()?.times(32767.0)?.div(maximum) ?: 0.5
            return BuzzerTone(enable == LogicValue.HIGH, hz, amplitude, waveform,
                duty.toULongOrNull()?.toInt()?.coerceIn(0, 255) ?: 128, channels, smoothLevel, smoothWidth)
        }
    }
}

data class BuzzerPcm(val sampleRate: Int, val stereoSamples: ShortArray) {
    fun wav(): ByteArray {
        val dataSize = stereoSamples.size * 2
        val bytes = ByteArray(44 + dataSize)
        fun text(offset: Int, value: String) { value.forEachIndexed { i, c -> bytes[offset + i] = c.code.toByte() } }
        fun little(offset: Int, value: Int, count: Int = 4) { repeat(count) { bytes[offset + it] = (value ushr (8 * it)).toByte() } }
        text(0, "RIFF"); little(4, 36 + dataSize); text(8, "WAVE"); text(12, "fmt "); little(16, 16)
        little(20, 1, 2); little(22, 2, 2); little(24, sampleRate); little(28, sampleRate * 4)
        little(32, 4, 2); little(34, 16, 2); text(36, "data"); little(40, dataSize)
        stereoSamples.forEachIndexed { index, value -> little(44 + index * 2, value.toInt(), 2) }
        return bytes
    }
}
