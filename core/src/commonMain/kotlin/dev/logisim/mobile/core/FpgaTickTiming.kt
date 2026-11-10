package dev.logisim.mobile.core

internal class FpgaTickTiming private constructor(
    val oscillatorFrequencyHz: Long,
    val cappedTickFrequencyHz: Double,
    val reloadCycles: Int,
) {
    companion object {
        fun requestedFrequencyHz(circuit: CircuitDocument, overrideHz: Int? = null): Double {
            require(overrideHz == null || overrideHz >= 0) { "FPGA tick frequency cannot be negative." }
            if (overrideHz != null && overrideHz > 0) return overrideHz.toDouble()

            fun savedFrequency(name: String): Double? {
                val value = circuit.projectAttribute(name) ?: return null
                val frequency = requireNotNull(value.toDoubleOrNull()?.takeIf { it.isFinite() }) {
                    "Circuit attribute '$name' must be a finite FPGA tick frequency."
                }
                return frequency.takeIf { it > 0 }
            }
            return savedFrequency("downloadFrequency") ?: savedFrequency("simulationFrequency") ?: 1.0
        }

        fun forOscillator(oscillatorHz: Long, requestedHz: Double): FpgaTickTiming {
            require(oscillatorHz >= 4) { "FPGA tick synthesis needs a board oscillator of at least 4 Hz." }
            require(requestedHz.isFinite() && requestedHz > 0) { "FPGA tick frequency must be finite and positive." }
            val cappedHz = minOf(requestedHz, (oscillatorHz / 4).toDouble())
            val reload = (oscillatorHz / cappedHz).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
            return FpgaTickTiming(oscillatorHz, cappedHz, reload)
        }
    }
}
