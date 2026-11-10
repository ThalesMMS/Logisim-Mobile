package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.message

/**
 * Dedicated Clock components fed by the Evolution tick generator instead of a physical pin.
 * [legacy] is the review that exposes root Clocks as inputs, kept for maps that bind them to pins.
 */
class FpgaClockTicks internal constructor(
    internal val circuit: CircuitDocument,
    internal val oscillatorPort: String?,
    internal val waveforms: List<FpgaClockHdlWaveform>,
    internal val rootClockPorts: Map<String, String>,
    internal val legacy: FpgaCircuitReview?,
) {
    /** Download frequency, then simulation frequency, in the order Evolution reads them. */
    val savedFrequencyHz: Double? get() = runCatching { FpgaTickTiming.requestedFrequencyHz(circuit) }.getOrNull()

    /** The tick rate the board oscillator gives for a requested frequency, or null when none is possible. */
    fun effectiveFrequencyHz(board: FpgaBoardDefinition, requestedHz: Double): Double? =
        timing(board, requestedHz)?.let { it.oscillatorFrequencyHz.toDouble() / it.reloadCycles }

    /** Oscillator cycles between two ticks for a requested frequency. */
    fun reloadCycles(board: FpgaBoardDefinition, requestedHz: Double): Int? = timing(board, requestedHz)?.reloadCycles

    internal fun plan(board: FpgaBoardDefinition, requestedHz: Double): FpgaTickPlan? =
        timing(board, requestedHz)?.let { FpgaTickPlan(it, oscillatorPort, waveforms) }

    /** Clock ports the saved map of [board] binds. Those maps keep the physical Clock input. */
    internal fun mappedPorts(board: FpgaBoardDefinition): Set<String> =
        runCatching { mappedFpgaClockPorts(circuit, board, null, rootClockPorts) }.getOrDefault(emptySet())

    private fun timing(board: FpgaBoardDefinition, requestedHz: Double): FpgaTickTiming? {
        val oscillator = board.clock ?: return null
        return runCatching { FpgaTickTiming.forOscillator(oscillator.frequencyHz, requestedHz) }.getOrNull()
    }
}

/**
 * Selects the review for [board]. A saved map that binds a root Clock keeps the physical input.
 * Otherwise dedicated Clocks use the tick generator, as in Evolution.
 */
fun FpgaCircuitReview.forBoard(board: FpgaBoardDefinition?): FpgaCircuitReview {
    val ticks = clockTicks ?: return this
    val legacy = ticks.legacy ?: return this
    return if (board != null && ticks.mappedPorts(board).isNotEmpty()) legacy else this
}

/** The mapping codec for this review. Unbound Clock entries of older maps are skipped in tick mode. */
fun FpgaCircuitReview.mappingCodec(document: CircuitDocument): FpgaMappingCodec? = design?.let { design ->
    FpgaMappingCodec(design, document, hdlPorts,
        clockTicks?.rootClockPorts.orEmpty().entries.associate { (component, port) -> port to component })
}

/** Stores the FPGA tick frequency like the Evolution clock panel. */
fun CircuitDocument.withFpgaTickFrequency(frequencyHz: Double): CircuitDocument {
    require(frequencyHz.isFinite() && frequencyHz > 0) { "FPGA tick frequency must be finite and positive." }
    fun saved(name: String) = projectAttribute(name)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
    val download = saved("downloadFrequency")
    val simulation = saved("simulationFrequency")
    val changed = (download > 0 && frequencyHz != download) || (frequencyHz != simulation && frequencyHz != download)
    return if (changed) withDesktopAttribute("downloadFrequency", frequencyHz.toString()) else this
}

internal fun FpgaCircuitRules.tickReview(
    project: CircuitProject,
    language: HdlLanguage,
    registry: ComponentRegistry,
    legacy: FpgaCircuitReview?,
    options: FpgaGenerationOptions = FpgaGenerationOptions(),
): FpgaCircuitReview? {
    if (project.circuits.none { document -> document.components.any { it.kind == ComponentKind.CLOCK } }) return null
    val analysis = analyzeConnectivity(project, registry)
    if (analysis.dedicatedClocks.isEmpty()) return null
    val clockHdl = HdlExporter.exportWithFpgaClocks(project, language, analysis, registry, options)
    val export = clockHdl.export
    val diagnostics = with(FpgaCircuitRules) { analysis.diagnostics.suppressed(options) }.toMutableList()
    if (export.successful && export.ports.isEmpty()) {
        diagnostics += FpgaCircuitDiagnostic(FpgaDiagnosticSeverity.ERROR, message("fpga_core_no_circuit_io"),
            DiagnosticTarget(project.mainCircuit.id))
    }
    val design = if (export.successful && diagnostics.none { it.severity == FpgaDiagnosticSeverity.ERROR })
        export.fpgaDesign(language, options = options) else null
    val ticks = FpgaClockTicks(project.mainCircuit, clockHdl.oscillatorPort, clockHdl.waveforms,
        HdlExporter.rootStimulusPorts(project), legacy)
    return FpgaCircuitReview(design, diagnostics, export.diagnostics, export.ports, ticks)
}

internal fun noTickOscillator(): FpgaPlanResult.Failure = FpgaPlanResult.Failure(listOf(
    FpgaDiagnostic("tick-oscillator", FpgaDiagnosticSeverity.ERROR, message("fpga_tick_no_oscillator")),
))
