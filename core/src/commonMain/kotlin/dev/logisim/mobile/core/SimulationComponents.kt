package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.Messages
import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/** Combinational values in the same order as [CircuitComponent.outputPorts]. */
internal object SimulationComponents {
    private val romImages = mutableMapOf<String, Map<ULong, ULong>>()

    fun combinational(
        component: CircuitComponent,
        inputs: List<LogicVector>,
        connectedInputs: List<LogicVector>,
    ): List<LogicVector>? {
        val width = component.width
        fun input(index: Int, bits: Int = width) =
            inputs.getOrNull(index)?.resized(bits) ?: LogicVector.unknown(bits)
        val value = when (component.kind) {
            ComponentKind.AND, ComponentKind.NAND,
            ComponentKind.OR, ComponentKind.NOR,
            ComponentKind.XOR, ComponentKind.XNOR -> gate(component, connectedInputs)
            ComponentKind.NOT -> invertGate(input(0))
            ComponentKind.BUFFER -> input(0)
            ComponentKind.CONTROLLED_BUFFER, ComponentKind.CONTROLLED_INVERTER -> {
                when (input(1, 1).bit(0)) {
                    LogicValue.HIGH -> if (component.kind == ComponentKind.CONTROLLED_INVERTER)
                        invertGate(input(0)) else input(0)
                    LogicValue.ERROR -> LogicVector.error(width)
                    else -> LogicVector.unknown(width)
                }
            }
            ComponentKind.CONSTANT -> LogicVector.of(width, parseNumber(component.attributes["value"] ?: "1"))
            ComponentKind.POWER -> LogicVector.high(width)
            ComponentKind.GROUND -> LogicVector.low(width)
            ComponentKind.ADDER -> return add(input(0), input(1), input(2, 1))
            ComponentKind.SUBTRACTOR -> return subtract(input(0), input(1), input(2, 1))
            ComponentKind.ROM -> {
                val address = inputs.firstOrNull()
                val outputWidth = component.attributeInt("dataWidth", 8)
                when {
                    address == null -> LogicVector.unknown(outputWidth)
                    address.errorMask != 0uL -> LogicVector.error(outputWidth)
                    address.toULongOrNull() == null -> LogicVector.unknown(outputWidth)
                    else -> {
                        val contents = component.attributes["contents"].orEmpty()
                        val image = romImages.getOrPut(contents) { readRomContents(contents) }
                        LogicVector.of(outputWidth, image[address.toULongOrNull()!!] ?: 0uL)
                    }
                }
            }
            else -> return null
        }
        return listOf(if (component.kind in outputModeKinds) outputMode(value, component.attributes["out"]) else value)
    }

    private val outputModeKinds = setOf(
        ComponentKind.AND, ComponentKind.NAND, ComponentKind.OR, ComponentKind.NOR,
        ComponentKind.XOR, ComponentKind.XNOR, ComponentKind.NOT, ComponentKind.BUFFER,
    )

    private fun gate(component: CircuitComponent, inputs: List<LogicVector>): LogicVector {
        if (inputs.isEmpty()) return LogicVector.error(component.width)
        val values = inputs.map { it.resized(component.width) }
        val result = LogicVector.fromBits((0 until component.width).map { bit ->
            val states = values.map { it.bit(bit) }
            when (component.kind) {
                ComponentKind.AND, ComponentKind.NAND -> when {
                    states.size == 1 -> states.single()
                    LogicValue.LOW in states -> LogicValue.LOW
                    states.all { it == LogicValue.HIGH } -> LogicValue.HIGH
                    else -> LogicValue.ERROR
                }
                ComponentKind.OR, ComponentKind.NOR -> when {
                    states.size == 1 -> states.single()
                    LogicValue.HIGH in states -> LogicValue.HIGH
                    states.all { it == LogicValue.LOW } -> LogicValue.LOW
                    else -> LogicValue.ERROR
                }
                else -> when {
                    states.any { it == LogicValue.UNKNOWN || it == LogicValue.ERROR } -> LogicValue.ERROR
                    component.attributes["xor"] == "odd" ->
                        if (states.count { it == LogicValue.HIGH } % 2 == 1) LogicValue.HIGH else LogicValue.LOW
                    states.count { it == LogicValue.HIGH } == 1 -> LogicValue.HIGH
                    else -> LogicValue.LOW
                }
            }
        })
        return if (component.kind in setOf(ComponentKind.NAND, ComponentKind.NOR, ComponentKind.XNOR))
            invertGate(result) else result
    }

    private fun invertGate(value: LogicVector): LogicVector = LogicVector.fromBits(
        (0 until value.width).map {
            when (value.bit(it)) {
                LogicValue.LOW -> LogicValue.HIGH
                LogicValue.HIGH -> LogicValue.LOW
                else -> LogicValue.ERROR
            }
        },
    )

    private fun outputMode(value: LogicVector, mode: String?): LogicVector = when (mode) {
        "0Z" -> LogicVector.fromBits((0 until value.width).map {
            if (value.bit(it) == LogicValue.HIGH) LogicValue.UNKNOWN else value.bit(it)
        })
        "Z1" -> LogicVector.fromBits((0 until value.width).map {
            if (value.bit(it) == LogicValue.LOW) LogicValue.UNKNOWN else value.bit(it)
        })
        else -> value
    }

    /** A floating carry pin is zero, as on the desktop adder. */
    internal fun add(a: LogicVector, b: LogicVector, carryInput: LogicVector): List<LogicVector> {
        var carry = carryInput.bit(0).let { if (it == LogicValue.UNKNOWN) LogicValue.LOW else it }
        val bits = (0 until a.width).map { index ->
            val aBit = a.bit(index)
            val bBit = b.bit(index)
            when {
                carry == LogicValue.ERROR -> LogicValue.ERROR
                carry == LogicValue.UNKNOWN -> LogicValue.UNKNOWN
                aBit == LogicValue.ERROR || bBit == LogicValue.ERROR -> {
                    carry = LogicValue.ERROR
                    LogicValue.ERROR
                }
                aBit == LogicValue.UNKNOWN || bBit == LogicValue.UNKNOWN -> {
                    carry = LogicValue.UNKNOWN
                    LogicValue.UNKNOWN
                }
                else -> {
                    val sum = listOf(aBit, bBit, carry).count { it == LogicValue.HIGH }
                    carry = if (sum >= 2) LogicValue.HIGH else LogicValue.LOW
                    if (sum % 2 == 1) LogicValue.HIGH else LogicValue.LOW
                }
            }
        }
        return listOf(LogicVector.fromBits(bits), LogicVector.fromBit(carry))
    }

    private fun subtract(a: LogicVector, b: LogicVector, borrow: LogicVector): List<LogicVector> {
        val borrowBit = borrow.bit(0).let { if (it == LogicValue.UNKNOWN) LogicValue.LOW else it }
        val sum = add(a, b.inverted(), LogicVector.fromBit(borrowBit.inverted()))
        return listOf(sum[0], sum[1].inverted())
    }

    internal fun readRomContents(contents: String): Map<ULong, ULong> {
        val normalized = contents.replace("\\n", "\n").replace("\\r", "\r")
        val data = normalized.lineSequence().filterNot {
            it.trimStart().startsWith("addr/data:") || it.trimStart().startsWith("v2.0")
        }.joinToString("\n") { it.substringBefore('#') }
        val words = mutableMapOf<ULong, ULong>()
        var address = 0uL
        for (token in data.split(Regex("\\s+")).filter { it.isNotBlank() }) {
            if (token.endsWith(':')) {
                address = parseNumber(token.dropLast(1), 16)
                continue
            }
            val pieces = token.split('*', limit = 2)
            val count = if (pieces.size == 2) pieces[0].toIntOrNull() ?: 0 else 1
            requireLocalized(count in 0..1_048_576) { message(Messages.ErrorMemoryRepetition) }
            val word = parseNumber(pieces.last(), 16)
            repeat(count) { words[address++] = word }
        }
        return words
    }

    private fun parseNumber(text: String, defaultRadix: Int = 10): ULong {
        val raw = text.trim()
        return when {
            raw.startsWith("0x", ignoreCase = true) -> raw.drop(2).toULongOrNull(16)
            raw.startsWith("0b", ignoreCase = true) -> raw.drop(2).toULongOrNull(2)
            else -> raw.toULongOrNull(defaultRadix)
        } ?: 0uL
    }
}
