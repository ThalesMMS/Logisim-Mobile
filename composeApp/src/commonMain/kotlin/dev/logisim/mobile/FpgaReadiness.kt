package dev.logisim.mobile

import dev.logisim.mobile.core.FpgaDiagnostic
import dev.logisim.mobile.core.FpgaDiagnosticSeverity
import dev.logisim.mobile.core.FpgaSignalBit
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.message

/**
 * Whether the mapping page can generate a project, and every reason it cannot: the design rule errors, clock inputs
 * without a valid frequency or scale, and the board tick. A board catalog that is still loading is a state, not a
 * problem. Export and Prepare use [ready]; the status shows [problemCount] and [reasons], so it never reads "0 problems"
 * while generation is blocked.
 */
internal data class FpgaReadiness(
    val loading: Boolean,
    /** Design rule errors, without those that only repeat a clock input reason. */
    val ruleErrors: List<FpgaDiagnostic>,
    /** Clock input and tick reasons, which the design rules do not report. */
    val reasons: List<FpgaReason>,
) {
    val problemCount: Int get() = ruleErrors.size + reasons.size
    val ready: Boolean get() = !loading && problemCount == 0
}

/** A reason generation is blocked that is not a design rule; [signal] is the clock input it is about. */
internal data class FpgaReason(val code: String, val text: LocalizedText, val signal: FpgaSignalBit? = null)

/** Diagnostic codes the rules raise for a clock input whose frequency or scale was left invalid. */
private val clockInputCodes = setOf("invalid-clock-frequency", "invalid-derived-clock", "derived-clock-source-required")

internal fun fpgaReadiness(
    loading: Boolean,
    diagnostics: List<FpgaDiagnostic>,
    clocks: Map<String, FpgaClockInput>,
    usesTick: Boolean,
    tickHz: Double?,
    boardHasOscillator: Boolean,
): FpgaReadiness {
    val clockReasons = clocks.entries.filter { !it.value.valid }.sortedBy { it.key }
    val blockedPorts = clockReasons.mapTo(mutableSetOf()) { it.key }
    val reasons = clockReasons.map { (port, input) ->
        if (input.frequencyHz == null) FpgaReason("clock-frequency", message("fpga_blocker_clock_frequency", "port" to port), FpgaSignalBit(port))
        else FpgaReason("clock-scale", message("fpga_blocker_clock_scale", "port" to port), FpgaSignalBit(port))
    } + listOfNotNull(
        FpgaReason("tick-frequency", message("fpga_tick_frequency_invalid")).takeIf { usesTick && tickHz == null },
        FpgaReason("tick-oscillator", message("fpga_tick_no_oscillator")).takeIf { usesTick && !boardHasOscillator },
    )
    // The panel writes 0 Hz or a 0/0 scale for an invalid clock input; its rule errors repeat the clock reason.
    val errors = diagnostics.filter { it.severity == FpgaDiagnosticSeverity.ERROR }.filterNot { diagnostic ->
        blockedPorts.isNotEmpty() && diagnostic.code in clockInputCodes &&
            (diagnostic.signal == null || diagnostic.signal?.port in blockedPorts)
    }
    return FpgaReadiness(loading, errors, reasons)
}
