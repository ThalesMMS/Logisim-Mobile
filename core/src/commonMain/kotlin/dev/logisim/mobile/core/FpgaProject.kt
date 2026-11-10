package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message

private const val ISE_CPLD_BUILD_SCRIPT = "build-cpld.sh"
private const val ECP5_BUILD_SCRIPT = "build-ecp5.sh"

enum class FpgaHdlLanguage { VERILOG, VHDL }
enum class FpgaFlow { OPEN_ECP5, OPEN_ICE40, VIVADO, ISE, QUARTUS }
enum class FpgaDiagnosticSeverity { ERROR, WARNING }

data class FpgaSourceFile(val path: String, val content: String)
data class FpgaDesignPort(
    val name: String,
    val direction: FpgaPinDirection,
    val width: Int = 1,
    val clockFrequencyHz: Long? = null,
    val reptarBank: ReptarPinBank? = null,
    val vhdlVector: Boolean = false,
    val clockScale: FpgaClockScale? = null,
)
data class FpgaDesignArtifact(
    val topLevelName: String,
    val language: FpgaHdlLanguage,
    val files: List<FpgaSourceFile>,
    val ports: List<FpgaDesignPort>,
    /** Casing of VHDL keywords in the generated top level, as in Evolution's FPGA options. */
    val vhdlKeywordsUpperCase: Boolean = true,
)

internal data class FpgaTickPlan(
    val timing: FpgaTickTiming,
    val oscillatorPort: String?,
    val waveforms: List<FpgaClockHdlWaveform>,
)

data class FpgaSignalBit(val port: String, val bit: Int = 0)

sealed interface FpgaPinBinding {
    data class Physical(
        val location: String,
        val resourceId: String? = null,
        val invert: Boolean? = null,
        val electrical: FpgaElectrical? = null,
    ) : FpgaPinBinding
    data class Display(val resourceId: String, val channel: Int, val rgbPixel: Boolean = false) : FpgaPinBinding
    data class Constant(val high: Boolean) : FpgaPinBinding
    data object Open : FpgaPinBinding
}

/** The board exposes one LocalBus resource. XML order is the bus bit order. */
fun FpgaDesignArtifact.reptarBindings(board: FpgaBoardDefinition): Map<FpgaSignalBit, FpgaPinBinding.Physical> {
    val banks = ports.filter { it.reptarBank != null }
    if (banks.size != ReptarPinBank.entries.size || banks.map { it.reptarBank }.toSet().size != banks.size ||
        language != FpgaHdlLanguage.VHDL || !board.isReptarSpartan6) return emptyMap()
    val resource = board.resources.singleOrNull { it.type == "LocalBus" } ?: return emptyMap()
    val bindings = mutableMapOf<FpgaSignalBit, FpgaPinBinding.Physical>()
    for (port in banks) {
        val bank = requireNotNull(port.reptarBank)
        val pins = resource.pins.filter { it.direction == port.direction }.sortedBy { it.index }
        if (port.direction.name != bank.direction.name || port.width != bank.width || pins.size != port.width ||
            pins.any { it.electrical.ioStandard != "LVCMOS18" || !it.electrical.activeLow }) return emptyMap()
        for ((bit, pin) in pins.withIndex()) bindings[FpgaSignalBit(port.name, bit)] =
            FpgaPinBinding.Physical(pin.location, pin.resourceId)
    }
    return bindings
}

data class FpgaDiagnostic(
    val code: String,
    val severity: FpgaDiagnosticSeverity,
    val notice: LocalizedText,
    val signal: FpgaSignalBit? = null,
    val pin: String? = null,
) {
    val message: String get() = notice.resolve(AppLanguage.PORTUGUESE)
}

data class FpgaBuildStage(val id: String, val tool: String, val arguments: List<String>, val outputFiles: List<String>)
data class FpgaBuildPlan(
    val board: FpgaBoardDefinition,
    val flow: FpgaFlow,
    val topLevelName: String,
    val files: List<FpgaSourceFile>,
    val stages: List<FpgaBuildStage>,
    val mappedPins: Map<FpgaSignalBit, String>,
    val diagnostics: List<FpgaDiagnostic>,
) {
    val canSynthesize: Boolean get() = diagnostics.none { it.severity == FpgaDiagnosticSeverity.ERROR }
    val programsHardware: Boolean get() = false
}

sealed interface FpgaPlanResult {
    data class Success(val plan: FpgaBuildPlan) : FpgaPlanResult
    data class Failure(val diagnostics: List<FpgaDiagnostic>) : FpgaPlanResult
}

object FpgaDesignRules {
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val physicalLocation = Regex("[A-Za-z0-9_]+")
    private val partField = Regex("[A-Za-z0-9_.+-]+")

    fun check(
        design: FpgaDesignArtifact,
        board: FpgaBoardDefinition,
        bindings: Map<FpgaSignalBit, FpgaPinBinding>,
        flow: FpgaFlow,
    ): List<FpgaDiagnostic> = buildList {
        fun error(code: String, notice: LocalizedText, signal: FpgaSignalBit? = null, pin: String? = null) {
            add(FpgaDiagnostic(code, FpgaDiagnosticSeverity.ERROR, notice, signal, pin))
        }
        if (!identifier.matches(design.topLevelName)) error("invalid-top", message("fpga_core_invalid_top"))
        if (design.topLevelName == FpgaProjectGenerator.TOP_LEVEL) error("reserved-top", message("fpga_core_reserved_top"))
        if (design.files.isEmpty()) error("missing-source", message("fpga_core_missing_source"))
        if (design.files.map { it.path }.distinct().size != design.files.size) error("duplicate-source", message("fpga_core_duplicate_source"))
        val generatedNames = setOf(
            "${FpgaProjectGenerator.TOP_LEVEL}.v", "${FpgaProjectGenerator.TOP_LEVEL}.vhd",
            "pins.lpf", "pins.pcf", "pins.xdc", "pins.ucf", "pins.qsf", "synthesis.ys", "translated.v",
            "build.tcl", "build.qsf", "build.qpf", "build.prj", "build.xst", "timing.sdc",
            ISE_CPLD_BUILD_SCRIPT, ECP5_BUILD_SCRIPT,
        )
        for (file in design.files) {
            if (!safePath(file.path)) error("unsafe-source-path", message("fpga_core_unsafe_source_path", "path" to file.path))
            if (file.path.lowercase() in generatedNames) error("reserved-source-name", message("fpga_core_reserved_source_name", "path" to file.path))
            if (file.content.length > 2_000_000) error("source-too-large", message("fpga_core_source_too_large", "path" to file.path))
        }
        if (design.files.sumOf { it.content.length.toLong() } > 16_000_000L) error("project-too-large", message("fpga_core_project_too_large"))
        if (design.ports.isEmpty()) error("missing-ports", message("fpga_core_missing_ports"))
        if (design.ports.size > 1024 || design.ports.sumOf { it.width.coerceAtLeast(0).toLong() } > 8192L) {
            error("too-many-ports", message("fpga_core_too_many_ports"))
            return@buildList
        }
        if (design.ports.map { it.name }.distinct().size != design.ports.size) error("duplicate-port", message("fpga_core_duplicate_port"))
        val packagePattern = if (flow == FpgaFlow.QUARTUS) Regex("[A-Za-z0-9_.+-]+(?: [A-Za-z0-9_.+-]+)*") else partField
        if (!partField.matches(board.device.part) || !packagePattern.matches(board.device.packageName) ||
            !partField.matches(board.device.speedGrade) || !Regex("[A-Za-z0-9 _+.-]{1,128}").matches(board.device.family))
            error("invalid-device", message("fpga_core_invalid_device"))
        val appropriateVendor = when (flow) {
            FpgaFlow.OPEN_ECP5, FpgaFlow.OPEN_ICE40 -> board.device.vendor == FpgaVendor.OPEN_FPGA
            FpgaFlow.VIVADO -> board.device.vendor == FpgaVendor.VIVADO
            FpgaFlow.ISE -> board.device.vendor == FpgaVendor.XILINX
            FpgaFlow.QUARTUS -> board.device.vendor == FpgaVendor.ALTERA
        }
        if (!appropriateVendor) error("vendor-flow-mismatch", message("fpga_core_vendor_flow_mismatch"))
        if (flow in setOf(FpgaFlow.OPEN_ECP5, FpgaFlow.OPEN_ICE40) && board.unusedPinPull != FpgaPull.FLOAT) {
            add(FpgaDiagnostic("unused-pin-pull-not-applied", FpgaDiagnosticSeverity.WARNING,
                message("fpga_core_unused_pin_pull_not_applied")))
        }
        if (flow == FpgaFlow.OPEN_ECP5 && board.device.part.lowercase() !in setOf("12k", "25k", "45k", "85k", "um-25k", "um-45k", "um-85k"))
            error("unsupported-device", message("fpga_core_unsupported_ecp5", "part" to board.device.part))
        if (flow == FpgaFlow.OPEN_ICE40 && board.device.part.lowercase() !in setOf("lp384", "lp1k", "lp4k", "lp8k", "hx1k", "hx4k", "hx8k", "up3k", "up5k", "u4k"))
            error("unsupported-device", message("fpga_core_unsupported_ice40", "part" to board.device.part))
        if (design.ports.any { it.reptarBank != null }) {
            val expected = design.reptarBindings(board)
            if (flow != FpgaFlow.ISE || expected.isEmpty())
                error("reptar-board", message("fpga_core_reptar_board"))
            if (board.clock == null)
                error("reptar-clock", message("fpga_core_clock_pin_mismatch"))
            for ((signal, pin) in expected) {
                val binding = bindings[signal] ?: continue
                if (binding !is FpgaPinBinding.Physical || !binding.location.equals(pin.location, true) ||
                    binding.resourceId?.let { it != pin.resourceId } == true || binding.invert != null || binding.electrical != null)
                    error("reptar-pin", message("fpga_core_reptar_pin", "pin" to pin.location), signal, pin.location)
            }
        }
        val expectedSignals = mutableSetOf<FpgaSignalBit>()
        val occupied = mutableMapOf<String, FpgaSignalBit>()
        val displays = board.resources.mapNotNull(FpgaBoardResource::scannedDisplay).associateBy { it.resource.id }
        val mappedDisplayChannels = mutableSetOf<FpgaPinBinding.Display>()
        for (port in design.ports) {
            if (!identifier.matches(port.name)) error("invalid-port-name", message("fpga_core_invalid_port_name", "port" to port.name))
            if (port.width !in 1..4096) { error("invalid-port-width", message("fpga_core_invalid_port_width", "port" to port.name)); continue }
            if (port.clockFrequencyHz != null && (port.direction != FpgaPinDirection.INPUT || port.width != 1))
                error("invalid-clock-port", message("fpga_core_invalid_clock_port", "port" to port.name))
            if (port.clockFrequencyHz != null && port.clockFrequencyHz !in 1..1_000_000_000_000L)
                error("invalid-clock-frequency", message("fpga_core_clock_frequency_range"))
            port.clockScale?.let { scale ->
                val signal = FpgaSignalBit(port.name)
                val frequency = port.clockFrequencyHz
                if (!board.supportsDerivedClock(flow))
                    error("derived-clock-unsupported", message("fpga_derived_clock_unsupported"), signal)
                if (frequency == null || bindings[signal] !is FpgaPinBinding.Physical)
                    error("derived-clock-source-required", message("fpga_core_clock_pin_mismatch"), signal)
                else if (!scale.withinClockLimits(frequency))
                    error("invalid-derived-clock", message("fpga_derived_clock_invalid"), signal)
            }
            for (bit in 0 until port.width) {
                val signal = FpgaSignalBit(port.name, bit)
                expectedSignals += signal
                when (val binding = bindings[signal]) {
                    null -> error("unmapped-port", message("fpga_core_unmapped_port", "port" to port.name, "bit" to bit), signal)
                    FpgaPinBinding.Open -> if (port.direction == FpgaPinDirection.INPUT)
                        error("open-input", message("fpga_core_open_input"), signal)
                    is FpgaPinBinding.Constant -> {
                        if (port.direction != FpgaPinDirection.INPUT)
                            error("constant-output", message("fpga_core_constant_output"), signal)
                        if (port.clockFrequencyHz != null)
                            error("clock-source-required", message("fpga_core_clock_pin_mismatch"), signal)
                    }
                    is FpgaPinBinding.Display -> {
                        val display = displays[binding.resourceId]
                        if (port.direction != FpgaPinDirection.OUTPUT || display == null ||
                            binding.channel !in 0 until display.channelLimit(binding.rgbPixel)) {
                            error("invalid-display-channel", message("fpga_invalid_display_channel"), signal)
                        } else {
                            val channels = display.dataChannels(binding).map { FpgaPinBinding.Display(binding.resourceId, it) }
                            if (channels.any { it in mappedDisplayChannels })
                                error("duplicate-display-channel", message("fpga_duplicate_display_channel", "channel" to binding.channel), signal)
                            else mappedDisplayChannels.addAll(channels)
                        }
                    }
                    is FpgaPinBinding.Physical -> {
                        val location = binding.location.uppercase()
                        if (!physicalLocation.matches(location)) error("invalid-pin", message("fpga_core_invalid_pin"), signal, location)
                        val candidates = board.pins.filter { it.location.equals(location, ignoreCase = true) &&
                            (binding.resourceId == null || it.resourceId == binding.resourceId) }
                        val isBoardClock = board.clock?.location?.equals(location, true) == true
                        if (candidates.isEmpty() && !(isBoardClock && port.clockFrequencyHz != null))
                            error("unknown-pin", message("fpga_core_unknown_pin", "pin" to location), signal, location)
                        if (candidates.isNotEmpty() && candidates.none { compatible(port.direction, it.direction) })
                            error("pin-direction", message("fpga_core_pin_direction"), signal, location)
                        val previous = occupied.put(location, signal)
                        if (previous != null) error("pin-already-used", message("fpga_core_pin_already_used", "pin" to location, "port" to previous.port, "bit" to previous.bit), signal, location)
                        val selected = candidates.firstOrNull { compatible(port.direction, it.direction) }
                        val declaredElectrical = selected?.electrical ?: board.clock?.takeIf { isBoardClock }?.electrical
                        val electrical = binding.electrical ?: declaredElectrical
                        if (electrical?.ioStandard != null && electrical.ioStandard !in setOf("LVCMOS12", "LVCMOS15", "LVCMOS18", "LVCMOS25", "LVCMOS33", "LVTTL"))
                            error("invalid-io-standard", message("fpga_core_invalid_io_standard"), signal, location)
                        if (electrical?.driveMilliamps != null && electrical.driveMilliamps !in setOf(2, 4, 8, 16, 24))
                            error("invalid-drive", message("fpga_core_invalid_drive"), signal, location)
                        if (flow == FpgaFlow.OPEN_ICE40) {
                            if (electrical?.pull == FpgaPull.DOWN)
                                error("unsupported-pull", message("fpga_core_ice40_pull_down"), signal, location)
                            if (electrical?.driveMilliamps != null)
                                error("unsupported-drive", message("fpga_core_ice40_drive"), signal, location)
                            if (electrical?.ioStandard != null && electrical.ioStandard != "LVCMOS33")
                                error("unsupported-io-standard", message("fpga_core_ice40_io_standard"), signal, location)
                        }
                        if (flow == FpgaFlow.QUARTUS && electrical?.pull == FpgaPull.DOWN)
                            error("unsupported-pull", message("fpga_core_quartus_pull_down"), signal, location)
                        val inverted = binding.invert ?: (selected?.electrical?.activeLow == true && port.reptarBank != ReptarPinBank.HOST_DATA)
                        if (port.direction == FpgaPinDirection.INOUT && inverted)
                            error("inverted-inout", message("fpga_core_inverted_inout"), signal, location)
                        if (port.clockScale != null && inverted)
                            error("derived-clock-inverted", message("fpga_derived_clock_inverted"), signal, location)
                        if (binding.electrical?.ioStandard != null && declaredElectrical?.ioStandard != null &&
                            binding.electrical.ioStandard != declaredElectrical.ioStandard)
                            error("io-standard-mismatch", message("fpga_core_io_standard_mismatch"), signal, location)
                        if (port.clockFrequencyHz != null) {
                            if (!isBoardClock) error("clock-pin-mismatch", message("fpga_core_clock_pin_mismatch"), signal, location)
                            if (port.clockFrequencyHz != board.clock?.frequencyHz)
                                error("clock-frequency-mismatch", message("fpga_core_clock_frequency_mismatch"), signal, location)
                        }
                    }
                }
            }
        }
        if (mappedDisplayChannels.isNotEmpty()) {
            val clock = board.clock
            if (clock == null || clock.frequencyHz !in 1_000..1_000_000_000_000L || !physicalLocation.matches(clock.location))
                error("missing-display-clock", message("fpga_display_clock_required"))
            val usedDisplays = mappedDisplayChannels.map { it.resourceId }.distinct().map { displays.getValue(it) }
            if (usedDisplays.sumOf { it.channelCount } > 8192)
                error("too-many-display-channels", message("fpga_core_too_many_ports"))
            val scanPins = mutableSetOf<String>()
            for (display in usedDisplays) for (pin in display.resource.pins) {
                val location = pin.location.uppercase()
                if (!physicalLocation.matches(location))
                    error("invalid-pin", message("fpga_core_invalid_pin"), pin = location)
                if (!scanPins.add(location) || location in occupied || location.equals(clock?.location, true))
                    error("display-pin-already-used", message("fpga_display_pin_conflict", "pin" to location), pin = location)
                if (flow == FpgaFlow.OPEN_ICE40) {
                    if (pin.electrical.pull == FpgaPull.DOWN)
                        error("unsupported-pull", message("fpga_core_ice40_pull_down"), pin = location)
                    if (pin.electrical.driveMilliamps != null)
                        error("unsupported-drive", message("fpga_core_ice40_drive"), pin = location)
                    if (pin.electrical.ioStandard != null && pin.electrical.ioStandard != "LVCMOS33")
                        error("unsupported-io-standard", message("fpga_core_ice40_io_standard"), pin = location)
                }
            }
            val clockUser = occupied[clock?.location?.uppercase()]
            if (clockUser != null && design.ports.none { it.name == clockUser.port && it.clockFrequencyHz == clock?.frequencyHz })
                error("display-clock-already-used", message("fpga_display_pin_conflict", "pin" to clock?.location.orEmpty()))
        }
        for (signal in bindings.keys - expectedSignals) error("unknown-signal", message("fpga_core_unknown_signal"), signal)
    }

    internal fun safePath(path: String): Boolean = path.length in 1..180 && path.split('/').all {
        it.isNotBlank() && it !in setOf(".", "..") && Regex("[A-Za-z0-9_.-]+").matches(it)
    } && !path.startsWith('/') && '\\' !in path

    private fun compatible(port: FpgaPinDirection, pin: FpgaPinDirection): Boolean =
        pin == FpgaPinDirection.INOUT || port == pin
}

object FpgaProjectGenerator {
    const val TOP_LEVEL = "logisim_fpga_top"
    private val iseCpldPartPrefixes = listOf("XC2C", "XA2C", "XCR3", "XC9500", "XA9500")

    fun generate(
        design: FpgaDesignArtifact,
        board: FpgaBoardDefinition,
        bindings: Map<FpgaSignalBit, FpgaPinBinding>,
        flow: FpgaFlow,
    ): FpgaPlanResult = generate(design, board, bindings, flow, null)

    /** Feeds the dedicated Clocks of [ticks] from the board oscillator at [tickFrequencyHz]. */
    fun generate(
        design: FpgaDesignArtifact,
        board: FpgaBoardDefinition,
        bindings: Map<FpgaSignalBit, FpgaPinBinding>,
        flow: FpgaFlow,
        ticks: FpgaClockTicks?,
        tickFrequencyHz: Double,
    ): FpgaPlanResult {
        if (ticks == null) return generate(design, board, bindings, flow, null)
        val plan = ticks.plan(board, tickFrequencyHz) ?: return noTickOscillator()
        return try {
            generate(design, board, bindings, flow, plan)
        } catch (failure: IllegalArgumentException) {
            FpgaPlanResult.Failure(listOf(FpgaDiagnostic("tick-plan", FpgaDiagnosticSeverity.ERROR,
                failure.localizedDescription(message("fpga_tick_no_oscillator")))))
        }
    }

    internal fun generate(
        design: FpgaDesignArtifact,
        board: FpgaBoardDefinition,
        bindings: Map<FpgaSignalBit, FpgaPinBinding>,
        flow: FpgaFlow,
        tickPlan: FpgaTickPlan?,
    ): FpgaPlanResult {
        // Evolution exposes the board oscillator for Reptar even without a Clock component.
        val reptarOscillatorOnly = tickPlan == null && board.clock != null && design.ports.any { it.reptarBank != null }
        val oscillatorSignal = if (tickPlan != null || reptarOscillatorOnly) {
            val oscillator = requireNotNull(board.clock) { "FPGA tick synthesis needs a board oscillator." }
            if (tickPlan != null) {
                require(tickPlan.waveforms.isNotEmpty()) { "FPGA tick synthesis requires a dedicated clock waveform." }
                require(tickPlan.timing.oscillatorFrequencyHz == oscillator.frequencyHz) {
                    "The FPGA tick plan does not use the selected board oscillator frequency."
                }
            }
            val existing = bindings.entries.firstOrNull { (_, binding) ->
                binding is FpgaPinBinding.Physical && binding.location.equals(oscillator.location, ignoreCase = true)
            }
            if (existing != null) {
                val port = design.ports.singleOrNull { port -> port.name == existing.key.port }
                val compatible = port != null && port.direction == FpgaPinDirection.INPUT && port.width == 1 &&
                    port.clockFrequencyHz == oscillator.frequencyHz
                if (reptarOscillatorOnly && !compatible) return FpgaPlanResult.Failure(listOf(
                    FpgaDiagnostic("reptar-clock-conflict", FpgaDiagnosticSeverity.ERROR,
                        message("fpga_core_clock_pin_mismatch"), existing.key, oscillator.location),
                ))
                require(compatible) {
                    "Board oscillator pin ${oscillator.location} is already used by an incompatible FPGA port."
                }
            }
            existing?.key ?: FpgaSignalBit("logisim_fpga_board_oscillator")
        } else null
        val needsOscillatorPort = oscillatorSignal != null && oscillatorSignal !in bindings
        val pinDesign = if (needsOscillatorPort) design.copy(ports = design.ports + FpgaDesignPort(
            checkNotNull(oscillatorSignal).port, FpgaPinDirection.INPUT,
            clockFrequencyHz = checkNotNull(board.clock).frequencyHz,
        )) else design
        val pinBindings = if (needsOscillatorPort) bindings +
            (checkNotNull(oscillatorSignal) to FpgaPinBinding.Physical(checkNotNull(board.clock).location))
        else bindings
        val diagnostics = FpgaDesignRules.check(pinDesign, board, pinBindings, flow)
        if (diagnostics.any { it.severity == FpgaDiagnosticSeverity.ERROR }) return FpgaPlanResult.Failure(diagnostics)
        val pins = pinDesign.ports.flatMapIndexed { portIndex, port -> (0 until port.width).mapNotNull { bit ->
            val binding = pinBindings[FpgaSignalBit(port.name, bit)] as? FpgaPinBinding.Physical ?: return@mapNotNull null
            PhysicalPin(port, portIndex, bit, binding, board.pins.firstOrNull { pin ->
                pin.location.equals(binding.location, true) && (binding.resourceId == null || binding.resourceId == pin.resourceId) &&
                    (pin.direction == FpgaPinDirection.INOUT || pin.direction == port.direction)
            }, board.clock?.takeIf { it.location.equals(binding.location, true) }?.electrical ?: FpgaElectrical())
        } }
        val tickClock = oscillatorSignal?.takeIf { tickPlan != null }?.let { signal ->
            val pin = pins.single { it.port.name == signal.port && it.bit == signal.bit }
            require(!pin.inverted) { "Board oscillator pin ${pin.binding.location} cannot be shared with an inverted FPGA port." }
            pin.externalName
        }
        val (extraPins, scans) = scanOutputs(design, board, bindings, pins)
        val physicalPins = pins + extraPins
        val derivedClocks = pins.mapNotNull { pin ->
            val scale = pin.port.clockScale ?: return@mapNotNull null
            val period = periodNanoseconds(checkNotNull(pin.port.clockFrequencyHz))
            val internalClock = "design_p${pin.portIndex}" +
                if (design.language == FpgaHdlLanguage.VHDL && pin.port.vhdlVector) "(0)" else ""
            FpgaDerivedClockHdl(pin.portIndex, pin.externalName, internalClock,
                if ('.' in period) period else "$period.0", scale)
        }
        val wrapperPath = if (design.language == FpgaHdlLanguage.VERILOG) "$TOP_LEVEL.v" else "$TOP_LEVEL.vhd"
        val wrapper = when (design.language) {
            FpgaHdlLanguage.VERILOG -> verilogWrapper(design, bindings, physicalPins, scans, derivedClocks, tickPlan, tickClock)
            FpgaHdlLanguage.VHDL -> VhdlKeywordCase.apply(vhdlWrapper(design, bindings, physicalPins, scans, derivedClocks,
                tickPlan, tickClock), design.vhdlKeywordsUpperCase)
        }
        val constraintName = when (flow) {
            FpgaFlow.OPEN_ECP5 -> "pins.lpf"
            FpgaFlow.OPEN_ICE40 -> "pins.pcf"
            FpgaFlow.VIVADO -> "pins.xdc"
            FpgaFlow.ISE -> "pins.ucf"
            FpgaFlow.QUARTUS -> "pins.qsf"
        }
        val files = design.files + FpgaSourceFile(wrapperPath, wrapper) + FpgaSourceFile(constraintName, constraints(board, physicalPins, flow))
        val additional = mutableListOf<FpgaSourceFile>()
        val stages = buildStages(design, board, flow, physicalPins, files.filter { it.path != constraintName }.map { it.path }, additional)
        val plan = FpgaBuildPlan(board, flow, TOP_LEVEL, files + additional, stages,
            pins.filter { it.portIndex < design.ports.size }.associate {
                FpgaSignalBit(it.port.name, it.bit) to it.externalName
            }, diagnostics)
        return FpgaPlanResult.Success(plan)
    }

    private fun scanOutputs(
        design: FpgaDesignArtifact,
        board: FpgaBoardDefinition,
        bindings: Map<FpgaSignalBit, FpgaPinBinding>,
        pins: List<PhysicalPin>,
    ): Pair<List<PhysicalPin>, List<FpgaScanHdl>> {
        val scanBindings = bindings.mapNotNull { (signal, binding) ->
            (binding as? FpgaPinBinding.Display)?.let { it to signal }
        }.groupBy({ it.first.resourceId }, { it })
        if (scanBindings.isEmpty()) return emptyList<PhysicalPin>() to emptyList()
        val clock = checkNotNull(board.clock)
        val extraPins = mutableListOf<PhysicalPin>()
        val clockName = pins.firstOrNull {
            it.port.clockFrequencyHz != null && it.binding.location.equals(clock.location, true)
        }?.externalName ?: "fpga_scan_clock".also {
            extraPins += PhysicalPin(
                FpgaDesignPort(it, FpgaPinDirection.INPUT, clockFrequencyHz = clock.frequencyHz),
                -1, 0, FpgaPinBinding.Physical(clock.location), null, clock.electrical, it,
            )
        }
        val portIndices = design.ports.mapIndexed { index, port -> port.name to index }.toMap()
        val scans = board.resources.mapNotNull(FpgaBoardResource::scannedDisplay).filter { it.resource.id in scanBindings }
            .mapIndexed { index, display ->
                val prefix = "fpga_scan_$index"
                for (pin in display.resource.pins) {
                    val name = "${prefix}_p${pin.index}"
                    extraPins += PhysicalPin(
                        FpgaDesignPort(name, FpgaPinDirection.OUTPUT), -1, pin.index,
                        FpgaPinBinding.Physical(pin.location), pin, pin.electrical, name,
                    )
                }
                val channels = buildMap {
                    for ((binding, signal) in scanBindings.getValue(display.resource.id)) {
                        val portIndex = portIndices.getValue(signal.port)
                        val port = design.ports[portIndex]
                        val source = "design_p$portIndex" + when {
                            design.language == FpgaHdlLanguage.VHDL && (port.width > 1 || port.vhdlVector) -> "(${signal.bit})"
                            port.width > 1 -> "[${signal.bit}]"
                            else -> ""
                        }
                        for (channel in display.dataChannels(binding)) put(channel, source)
                    }
                }
                FpgaScanHdl(prefix, display, clockName, clock.frequencyHz / 1_000, channels)
            }
        return extraPins to scans
    }

    private data class PhysicalPin(
        val port: FpgaDesignPort,
        val portIndex: Int,
        val bit: Int,
        val binding: FpgaPinBinding.Physical,
        val boardPin: FpgaBoardPin?,
        val fallbackElectrical: FpgaElectrical,
        val externalName: String = "fpga_p${portIndex}_b$bit",
    ) {
        val inverted get() = binding.invert ?: (boardPin?.electrical?.activeLow == true && port.reptarBank != ReptarPinBank.HOST_DATA)
        val electrical get() = binding.electrical ?: boardPin?.electrical ?: fallbackElectrical
    }

    private fun verilogWrapper(
        design: FpgaDesignArtifact,
        bindings: Map<FpgaSignalBit, FpgaPinBinding>,
        pins: List<PhysicalPin>,
        scans: List<FpgaScanHdl>,
        clocks: List<FpgaDerivedClockHdl>,
        tickPlan: FpgaTickPlan?,
        tickClock: String?,
    ): String = buildString {
        append("module $TOP_LEVEL(\n")
        append(pins.joinToString(",\n") { "  ${direction(it.port.direction)} wire ${it.externalName}" })
        append("\n);\n")
        for ((index, port) in design.ports.withIndex()) {
            if (port.direction != FpgaPinDirection.INOUT) append("wire ${range(port.width)}design_p$index;\n")
            for (bit in 0 until port.width) {
                val signal = FpgaSignalBit(port.name, bit)
                val binding = bindings.getValue(signal)
                val physical = pins.firstOrNull { it.portIndex == index && it.bit == bit }
                val internal = "design_p$index" + if (port.width == 1) "" else "[$bit]"
                when (port.direction) {
                    FpgaPinDirection.INPUT -> {
                        if (port.clockScale != null) continue
                        val expression = when (binding) {
                            is FpgaPinBinding.Constant -> if (binding.high) "1'b1" else "1'b0"
                            is FpgaPinBinding.Physical -> (if (physical!!.inverted) "~" else "") + physical.externalName
                            is FpgaPinBinding.Display, FpgaPinBinding.Open -> error("Invalid binding for validated input")
                        }
                        append("assign $internal = $expression;\n")
                    }
                    FpgaPinDirection.OUTPUT -> if (physical != null) append("assign ${physical.externalName} = ${if (physical.inverted) "~" else ""}$internal;\n")
                    FpgaPinDirection.INOUT -> if (binding == FpgaPinBinding.Open) append("wire unused_p${index}_b$bit;\n")
                }
            }
        }
        if (tickPlan != null) append(FpgaEvolutionClockHdl.verilog(
            checkNotNull(tickClock), tickPlan.timing.reloadCycles, tickPlan.waveforms.map { it.waveform },
        ))
        append("${design.topLevelName} design_instance(\n")
        val connections = design.ports.mapIndexed { index, port ->
            val connection = if (port.direction != FpgaPinDirection.INOUT) "design_p$index" else {
                val bits = (port.width - 1 downTo 0).map { bit ->
                    pins.firstOrNull { it.portIndex == index && it.bit == bit }?.externalName ?: "unused_p${index}_b$bit"
                }
                if (port.width == 1) bits.single() else bits.joinToString(", ", "{", "}")
            }
            "  .${port.name}($connection)"
        }
        val tickConnections = if (tickPlan == null) emptyList() else buildList {
            tickPlan.oscillatorPort?.let { add("  .$it(${checkNotNull(tickClock)})") }
            for ((index, clock) in tickPlan.waveforms.withIndex()) {
                add("  .${clock.levelPort}(${FpgaEvolutionClockHdl.waveSignal(index)})")
                add("  .${clock.invertedLevelPort}(${FpgaEvolutionClockHdl.invertedWaveSignal(index)})")
                add("  .${clock.risePort}(${FpgaEvolutionClockHdl.riseSignal(index)})")
                add("  .${clock.fallPort}(${FpgaEvolutionClockHdl.fallSignal(index)})")
            }
        }
        append((connections + tickConnections).joinToString(",\n"))
        append("\n);\n")
        scans.forEach { append(it.verilog()) }
        clocks.forEach { append(it.verilog()) }
        append("endmodule\n")
    }

    private fun vhdlWrapper(
        design: FpgaDesignArtifact,
        bindings: Map<FpgaSignalBit, FpgaPinBinding>,
        pins: List<PhysicalPin>,
        scans: List<FpgaScanHdl>,
        clocks: List<FpgaDerivedClockHdl>,
        tickPlan: FpgaTickPlan?,
        tickClock: String?,
    ): String = buildString {
        append("library ieee;\nuse ieee.std_logic_1164.all;\n")
        if (scans.isNotEmpty() || tickPlan != null) append("use ieee.numeric_std.all;\n")
        if (clocks.isNotEmpty()) append("library unisim;\nuse unisim.vcomponents.all;\n")
        append("entity $TOP_LEVEL is\n")
        if (pins.isNotEmpty()) {
            append("  port (\n")
            append(pins.joinToString(";\n") { "    ${it.externalName}: ${vhdlDirection(it.port.direction)} std_logic" })
            append("\n  );\n")
        }
        append("end entity;\narchitecture rtl of $TOP_LEVEL is\n")
        for ((index, port) in design.ports.withIndex()) {
            if (port.direction != FpgaPinDirection.INOUT) append("  signal design_p$index: ${if (port.width == 1 && !port.vhdlVector) "std_logic" else "std_logic_vector(${port.width - 1} downto 0)"};\n")
            else for (bit in 0 until port.width) if (bindings[FpgaSignalBit(port.name, bit)] == FpgaPinBinding.Open)
                append("  signal unused_p${index}_b$bit: std_logic;\n")
        }
        scans.forEach { append(it.vhdlDeclarations()) }
        clocks.forEach { append(it.vhdlDeclarations()) }
        val waveforms = tickPlan?.waveforms.orEmpty().map { it.waveform }
        if (tickPlan != null) append(FpgaEvolutionClockHdl.vhdlDeclarations(waveforms))
        append("begin\n")
        clocks.forEach { append(it.vhdl()) }
        if (tickPlan != null) append(FpgaEvolutionClockHdl.vhdl(
            checkNotNull(tickClock), tickPlan.timing.reloadCycles, waveforms,
        ))
        for ((index, port) in design.ports.withIndex()) for (bit in 0 until port.width) {
            val binding = bindings.getValue(FpgaSignalBit(port.name, bit))
            val physical = pins.firstOrNull { it.portIndex == index && it.bit == bit }
            val internal = "design_p$index" + if (port.width == 1 && !port.vhdlVector) "" else "($bit)"
            if (port.direction == FpgaPinDirection.INPUT) {
                if (port.clockScale != null) continue
                val expression = when (binding) {
                    is FpgaPinBinding.Constant -> if (binding.high) "'1'" else "'0'"
                    is FpgaPinBinding.Physical -> (if (physical!!.inverted) "not " else "") + physical.externalName
                    is FpgaPinBinding.Display, FpgaPinBinding.Open -> error("Invalid binding for validated input")
                }
                append("  $internal <= $expression;\n")
            } else if (port.direction == FpgaPinDirection.OUTPUT && physical != null)
                append("  ${physical.externalName} <= ${if (physical.inverted) "not " else ""}$internal;\n")
        }
        append("  design_instance: entity work.${design.topLevelName} port map (\n")
        val connections = design.ports.flatMapIndexed { index, port ->
            if (port.direction != FpgaPinDirection.INOUT) listOf("    ${port.name} => design_p$index")
            else (0 until port.width).map { bit ->
                val name = pins.firstOrNull { it.portIndex == index && it.bit == bit }?.externalName ?: "unused_p${index}_b$bit"
                "    ${port.name}${if (port.width == 1 && !port.vhdlVector) "" else "($bit)"} => $name"
            }
        }
        val tickConnections = buildList {
            tickPlan?.oscillatorPort?.let { add("    $it => ${checkNotNull(tickClock)}") }
            for ((index, clock) in tickPlan?.waveforms.orEmpty().withIndex()) {
                add("    ${clock.levelPort} => ${FpgaEvolutionClockHdl.waveSignal(index)}")
                add("    ${clock.invertedLevelPort} => ${FpgaEvolutionClockHdl.invertedWaveSignal(index)}")
                add("    ${clock.risePort} => ${FpgaEvolutionClockHdl.riseSignal(index)}")
                add("    ${clock.fallPort} => ${FpgaEvolutionClockHdl.fallSignal(index)}")
            }
        }
        append((connections + tickConnections).joinToString(",\n"))
        append("\n  );\n")
        scans.forEach { append(it.vhdl()) }
        append("end architecture;\n")
    }

    private fun constraints(board: FpgaBoardDefinition, pins: List<PhysicalPin>, flow: FpgaFlow): String = buildString {
        if (flow == FpgaFlow.OPEN_ECP5) append("BLOCK RESETPATHS;\nBLOCK ASYNCPATHS;\n")
        for (pin in pins) {
            val name = pin.externalName
            val location = pin.binding.location.uppercase()
            val electrical = pin.electrical
            when (flow) {
                FpgaFlow.OPEN_ECP5 -> {
                    append("LOCATE COMP \"$name\" SITE \"$location\";\n")
                    append("IOBUF PORT \"$name\" PULLMODE=${when (electrical.pull) { FpgaPull.UP -> "UP"; FpgaPull.DOWN -> "DOWN"; FpgaPull.FLOAT -> "NONE" }}")
                    electrical.ioStandard?.let { append(" IO_TYPE=$it") }
                    electrical.driveMilliamps?.let { append(" DRIVE=$it") }
                    append(";\n")
                    pin.port.clockFrequencyHz?.let { append("FREQUENCY PORT \"$name\" ${mhz(it)} MHZ;\n") }
                }
                FpgaFlow.OPEN_ICE40 -> {
                    append("set_io ${if (electrical.pull == FpgaPull.UP) "-pullup yes " else ""}$name $location\n")
                    pin.port.clockFrequencyHz?.let { append("set_frequency $name ${mhz(it)}\n") }
                }
                FpgaFlow.VIVADO -> {
                    append("set_property PACKAGE_PIN $location [get_ports {$name}]\n")
                    electrical.ioStandard?.let { append("set_property IOSTANDARD $it [get_ports {$name}]\n") }
                    if (electrical.pull != FpgaPull.FLOAT) append("set_property ${if (electrical.pull == FpgaPull.UP) "PULLUP" else "PULLDOWN"} true [get_ports {$name}]\n")
                    electrical.driveMilliamps?.let { append("set_property DRIVE $it [get_ports {$name}]\n") }
                    pin.port.clockFrequencyHz?.let { append("create_clock -period ${periodNanoseconds(it)} [get_ports {$name}]\n") }
                }
                FpgaFlow.ISE -> {
                    append("NET \"$name\" LOC = \"$location\"")
                    electrical.ioStandard?.let { append(" | IOSTANDARD = $it") }
                    electrical.driveMilliamps?.let { append(" | DRIVE = $it") }
                    if (electrical.pull != FpgaPull.FLOAT) append(" | ${if (electrical.pull == FpgaPull.UP) "PULLUP" else "PULLDOWN"}")
                    append(";\n")
                    pin.port.clockFrequencyHz?.let { append("NET \"$name\" TNM_NET = \"clock_$name\";\nTIMESPEC \"TS_$name\" = PERIOD \"clock_$name\" ${periodNanoseconds(it)} ns HIGH 50%;\n") }
                }
                FpgaFlow.QUARTUS -> {
                    append("set_location_assignment ${if (location.startsWith("PIN_")) location else "PIN_$location"} -to $name\n")
                    electrical.ioStandard?.let { append("set_instance_assignment -name IO_STANDARD \"$it\" -to $name\n") }
                    electrical.driveMilliamps?.let { append("set_instance_assignment -name CURRENT_STRENGTH_NEW \"$it MA\" -to $name\n") }
                    if (electrical.pull == FpgaPull.UP) append("set_instance_assignment -name WEAK_PULL_UP_RESISTOR ON -to $name\n")
                }
            }
        }
        if (flow == FpgaFlow.QUARTUS) {
            val unused = when (board.unusedPinPull) {
                FpgaPull.UP -> "PULLUP"
                FpgaPull.DOWN -> "PULLDOWN"
                FpgaPull.FLOAT -> "TRI-STATED"
            }
            val assignment = if (board.device.quartusMax7000BuildTarget() != null)
                "RESERVE_ALL_UNUSED_PINS_NO_OUTPUT_GND" else "RESERVE_ALL_UNUSED_PINS"
            append("set_global_assignment -name $assignment \"AS INPUT $unused\"\n")
        }
    }

    private fun buildStages(design: FpgaDesignArtifact, board: FpgaBoardDefinition, flow: FpgaFlow, pins: List<PhysicalPin>, sourcePaths: List<String>, files: MutableList<FpgaSourceFile>): List<FpgaBuildStage> {
        if (flow in setOf(FpgaFlow.OPEN_ECP5, FpgaFlow.OPEN_ICE40)) {
            val translated = design.language == FpgaHdlLanguage.VHDL
            val synthSources = if (translated) listOf("translated.v") else sourcePaths
            val synth = buildString {
                for (path in synthSources) append("read_verilog -sv \"$path\"\n")
                append("hierarchy -check -top $TOP_LEVEL\n")
                append("${if (flow == FpgaFlow.OPEN_ECP5) "synth_ecp5" else "synth_ice40"} -top $TOP_LEVEL -json synthesized.json\n")
                append("check -assert\n")
            }
            files += FpgaSourceFile("synthesis.ys", synth)
            val stages = buildList {
                if (translated) {
                    val sources = sourcePaths.map { if (it.startsWith('-')) "./$it" else it }
                    add(FpgaBuildStage("translate-vhdl", "ghdl",
                        listOf("--synth", "--std=08", "--out=verilog") + sources + listOf("-e", TOP_LEVEL), listOf("translated.v")))
                }
                add(FpgaBuildStage("synthesize", "yosys", listOf("-s", "synthesis.ys"), listOf("synthesized.json")))
                if (flow == FpgaFlow.OPEN_ECP5) {
                    add(FpgaBuildStage("place-route", "nextpnr-ecp5", listOf("--${board.device.part.lowercase()}", "--package", board.device.packageName.uppercase(), "--json", "synthesized.json", "--lpf", "pins.lpf", "--textcfg", "routed.config"), listOf("routed.config")))
                    add(FpgaBuildStage("pack", "ecppack",
                        listOf("--compress", "--freq", "62.0", "--input", "routed.config", "--bit", "design.bit"),
                        listOf("design.bit")))
                } else {
                    add(FpgaBuildStage("place-route", "nextpnr-ice40", listOf("--${board.device.part.lowercase()}", "--package", board.device.packageName.lowercase(), "--json", "synthesized.json", "--pcf", "pins.pcf", "--asc", "routed.asc"), listOf("routed.asc")))
                    add(FpgaBuildStage("pack", "icepack", listOf("routed.asc", "design.bin"), listOf("design.bin")))
                }
            }
            if (flow == FpgaFlow.OPEN_ECP5) files += posixBuildRecipe(ECP5_BUILD_SCRIPT, stages)
            return stages
        }
        val readSources = sourcePaths.joinToString("\n") { "read_${if (it.endsWith(".vhd") || it.endsWith(".vhdl")) "vhdl" else "verilog"} {$it}" }
        return when (flow) {
            FpgaFlow.VIVADO -> {
                files += FpgaSourceFile("build.tcl", "$readSources\nread_xdc {pins.xdc}\nsynth_design -top $TOP_LEVEL -part ${board.device.part}${board.device.packageName}${board.device.speedGrade}\nopt_design\nplace_design\nroute_design\nreport_timing_summary -file timing.txt\nwrite_bitstream -force design.bit\n")
                listOf(FpgaBuildStage("vendor-build", "vivado", listOf("-mode", "batch", "-source", "build.tcl", "-nojournal"), listOf("design.bit", "timing.txt")))
            }
            FpgaFlow.QUARTUS -> {
                val family = if (board.device.quartusMax7000BuildTarget() != null) "MAX7000S" else board.device.quartusFamilyName
                files += FpgaSourceFile("build.qsf", "set_global_assignment -name FAMILY \"$family\"\nset_global_assignment -name DEVICE ${board.device.part}\nset_global_assignment -name TOP_LEVEL_ENTITY $TOP_LEVEL\nset_global_assignment -name SDC_FILE timing.sdc\n" + sourcePaths.joinToString("\n") { "set_global_assignment -name ${if (it.endsWith(".vhd") || it.endsWith(".vhdl")) "VHDL_FILE" else "SYSTEMVERILOG_FILE"} {$it}" } + "\nsource {pins.qsf}\n")
                files += FpgaSourceFile("build.qpf", "PROJECT_REVISION = \"build\"\n")
                files += FpgaSourceFile("timing.sdc", pins.mapNotNull { pin -> pin.port.clockFrequencyHz?.let { "create_clock -name ${pin.port.name} -period ${periodNanoseconds(it)} [get_ports {${pin.externalName}}]" } }.joinToString("\n", postfix = "\nderive_clock_uncertainty\n"))
                val extension = board.quartusProgrammingExtension()
                listOf(FpgaBuildStage("vendor-build", "quartus_sh", listOf("--flow", "compile", "build"), listOf("output_files/build.$extension")))
            }
            FpgaFlow.ISE -> {
                files += FpgaSourceFile("build.prj", sourcePaths.joinToString("\n") { "${if (it.endsWith(".vhd") || it.endsWith(".vhdl")) "vhdl" else "verilog"} work \"$it\"" } + "\n")
                files += FpgaSourceFile("build.xst", "run\n-ifn build.prj\n-ifmt mixed\n-ofn synthesized.ngc\n-ofmt NGC\n-p ${board.device.part}-${board.device.packageName}-${board.device.speedGrade}\n-top $TOP_LEVEL\n")
                val part = board.device.part.uppercase()
                if (iseCpldPartPrefixes.any { part.startsWith(it) }) {
                    val fitterPart = "$part-${board.device.speedGrade}-${board.device.packageName.uppercase()}"
                    val termination = when (board.unusedPinPull) {
                        FpgaPull.UP -> "pullup"
                        FpgaPull.DOWN -> "pulldown"
                        FpgaPull.FLOAT -> "float"
                    }
                    val stages = listOf(
                        FpgaBuildStage("synthesize", "xst", listOf("-ifn", "build.xst"), listOf("synthesized.ngc")),
                        FpgaBuildStage("translate", "ngdbuild", listOf("-intstyle", "ise", "-uc", "pins.ucf", "synthesized.ngc", "logisim.ngd"), listOf("logisim.ngd")),
                        FpgaBuildStage("cpld-fit", "cpldfit", listOf("-p", fitterPart, "-intstyle", "ise", "-terminate", termination, "-loc", "on",
                            "-log", "logisim_cpldfit.log", "logisim.ngd"), listOf("logisim.vm6")),
                        FpgaBuildStage("generate-jedec", "hprep6", listOf("-i", "logisim.vm6"), listOf("logisim.jed")),
                    )
                    files += posixBuildRecipe(ISE_CPLD_BUILD_SCRIPT, stages)
                    return stages
                }
                listOf(FpgaBuildStage("synthesize", "xst", listOf("-ifn", "build.xst"), listOf("synthesized.ngc")),
                    FpgaBuildStage("translate", "ngdbuild", listOf("-uc", "pins.ucf", "synthesized.ngc", "translated.ngd"), listOf("translated.ngd")),
                    FpgaBuildStage("map", "map", listOf("-o", "mapped.ncd", "translated.ngd"), listOf("mapped.ncd")),
                    FpgaBuildStage("place-route", "par", listOf("-w", "mapped.ncd", "routed.ncd"), listOf("routed.ncd")),
                    FpgaBuildStage("pack", "bitgen", listOf("-w") + when (board.unusedPinPull) {
                        FpgaPull.UP -> listOf("-g", "UnusedPin:PULLUP")
                        FpgaPull.DOWN -> listOf("-g", "UnusedPin:PULLDOWN")
                        FpgaPull.FLOAT -> emptyList()
                    } + listOf("routed.ncd", "design.bit"), listOf("design.bit")))
            }
            else -> error("Open flow already returned")
        }
    }

    private fun posixBuildRecipe(path: String, stages: List<FpgaBuildStage>): FpgaSourceFile {
        fun quote(argument: String) = "'${argument.replace("'", "'\\''")}'"
        return FpgaSourceFile(path, buildString {
            appendLine("#!/bin/sh")
            appendLine("set -eu")
            appendLine("CDPATH= cd -- \"\$(dirname -- \"\$0\")\"")
            for (stage in stages) {
                append(stage.tool)
                for (argument in stage.arguments) append(' ').append(quote(argument))
                if (stage.id == "translate-vhdl") append(" > ").append(quote(stage.outputFiles.single()))
                appendLine()
            }
        })
    }

    private fun direction(direction: FpgaPinDirection): String = when (direction) {
        FpgaPinDirection.INPUT -> "input"; FpgaPinDirection.OUTPUT -> "output"; FpgaPinDirection.INOUT -> "inout"
    }
    private fun vhdlDirection(direction: FpgaPinDirection): String = when (direction) {
        FpgaPinDirection.INPUT -> "in"; FpgaPinDirection.OUTPUT -> "out"; FpgaPinDirection.INOUT -> "inout"
    }
    private fun range(width: Int) = if (width == 1) "" else "[${width - 1}:0] "
    private fun mhz(hz: Long): String = if (hz % 1_000_000L == 0L) (hz / 1_000_000L).toString()
        else "${hz / 1_000_000L}.${(hz % 1_000_000L).toString().padStart(6, '0').trimEnd('0')}"
    private fun periodNanoseconds(hz: Long): String = (1_000_000_000.0 / hz).toString()
}
