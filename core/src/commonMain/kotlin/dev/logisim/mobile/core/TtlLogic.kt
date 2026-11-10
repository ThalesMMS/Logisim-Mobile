package dev.logisim.mobile.core

/** Digital TTL behavior. Values are addressed by physical pin number. */
object TtlLogic {
    fun evaluate(
        model: String,
        pins: Map<Int, LogicValue>,
        state: TtlState = TtlState(),
        attributes: Map<String, String> = emptyMap(),
        elapsedTimeMillis: Long = 0,
    ): TtlResult {
        val spec = TtlCatalog.spec(model)
        if (attributes["VccGndPorts"] == "true" &&
            (pins.pin(spec.gndPin) != LogicValue.LOW || pins.pin(spec.vccPin) != LogicValue.HIGH)) {
            val released = spec.outputPins.associateWith { LogicValue.UNKNOWN } + spec.inoutPins.associateWith {
                state.data["drive:$it"]?.bit(0) ?: LogicValue.UNKNOWN
            }
            // The released ports read back as unknown until the chip drives them again.
            return TtlResult(released, state.copy(data = state.data +
                spec.outputPins.associate { "drive:$it" to LogicVector.unknown(1) }))
        }
        val result = TtlCombinational.evaluate(model, pins)
            ?: TtlSequential.evaluate(model, pins, state, attributes, elapsedTimeMillis)
            ?: error("TTL sem implementação: $model")
        return result.copy(state = result.state.copy(data = result.state.data +
            result.outputs.mapKeys { "drive:${it.key}" }.mapValues { LogicVector.fromBit(it.value) }))
    }
}
