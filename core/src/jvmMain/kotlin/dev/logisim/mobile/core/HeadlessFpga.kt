package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedArgumentException
import dev.logisim.mobile.core.localization.message
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class HeadlessFpgaError(val stage: String, message: String) : Exception(message)

internal class HeadlessFpga private constructor(
    private val projectFile: Path,
    private val circuitName: String,
    private val boardFile: Path,
    private val mappingFile: Path?,
    private val outputFile: Path,
    private val companionEndpoint: String?,
    private val requestId: String?,
    val cancelFile: Path?,
    private val tickOverrideHz: Int?,
    private val hdlLanguage: HdlLanguage,
) {
    fun generate(stdout: PrintStream, stderr: PrintStream, language: AppLanguage, checkCancellation: () -> Unit) {
        checkCancellation()
        val project = when (val imported = fpgaStage("project", language) { CircCodec.readProject(readHeadlessCircuitFile(projectFile)) }) {
            is CircProjectImportResult.Success -> imported.project
            is CircProjectImportResult.Failure -> throw HeadlessFpgaError("project", imported.diagnostics.joinToString("; "))
        }
        val circuit = project.circuit(circuitName) ?: throw HeadlessFpgaError("project", "No circuit named '$circuitName'.")
        checkCancellation()
        val board = when (val imported = fpgaStage("board", language) { FpgaBoardCodec.read(readHeadlessCircuitFile(boardFile)) }) {
            is FpgaBoardImportResult.Success -> {
                for (notice in imported.warningNotices) stderr.println("warning=fpga stage=board ${notice.resolve(language)}")
                imported.board
            }
            is FpgaBoardImportResult.Failure -> throw HeadlessFpgaError("board", imported.diagnosticNotices.joinToString("; ") { it.resolve(language) })
        }
        val flow = when (board.device.vendor) {
            FpgaVendor.ALTERA -> FpgaFlow.QUARTUS
            FpgaVendor.XILINX -> FpgaFlow.ISE
            FpgaVendor.VIVADO -> FpgaFlow.VIVADO
            FpgaVendor.OPEN_FPGA -> if (board.device.family.contains("ice", ignoreCase = true)) FpgaFlow.OPEN_ICE40 else FpgaFlow.OPEN_ECP5
            FpgaVendor.UNKNOWN -> throw HeadlessFpgaError("board", "No FPGA flow is available for '${board.name}'.")
        }
        checkCancellation()
        val selected = project.copy(mainCircuitName = circuitName)
        val mappingXml by lazy { mappingFile?.let { fpgaStage("mapping", language) { readHeadlessCircuitFile(it) } } }
        val analysis = if (project.circuits.any { document -> document.components.any { it.kind == ComponentKind.CLOCK } })
            fpgaStage("drc", language) { FpgaCircuitRules.analyzeConnectivity(selected) } else null
        val dedicatedClocks = analysis?.dedicatedClocks.orEmpty()
        val rootClockPorts = if (dedicatedClocks.isEmpty()) emptyMap() else HdlExporter.rootStimulusPorts(selected)
        val mappedClocks = if (rootClockPorts.isEmpty()) emptySet() else fpgaStage("mapping", language) {
            mappedFpgaClockPorts(circuit, board, mappingXml, rootClockPorts)
        }
        if (mappedClocks.isNotEmpty() && ((tickOverrideHz ?: 0) > 0 || mappedClocks.size != dedicatedClocks.size)) {
            throw HeadlessFpgaError("mapping", "Synthesized FPGA ticks cannot be combined with a mapped Clock component.")
        }
        val clockHdl = if (dedicatedClocks.isNotEmpty() && mappedClocks.isEmpty()) fpgaStage("hdl", language) {
            HdlExporter.exportWithFpgaClocks(selected, hdlLanguage, checkNotNull(analysis))
        } else null
        val review = fpgaStage("hdl", language) {
            if (clockHdl == null) FpgaCircuitRules.review(selected, hdlLanguage)
            else {
                val export = clockHdl.export
                val diagnostics = checkNotNull(analysis).diagnostics.toMutableList()
                if (export.successful && export.ports.isEmpty()) {
                    diagnostics += FpgaCircuitDiagnostic(FpgaDiagnosticSeverity.ERROR, message("fpga_core_no_circuit_io"),
                        DiagnosticTarget(circuit.id))
                }
                val design = if (export.successful && diagnostics.none { it.severity == FpgaDiagnosticSeverity.ERROR })
                    export.fpgaDesign(hdlLanguage) else null
                FpgaCircuitReview(design, diagnostics, export.diagnostics, export.ports)
            }
        }
        val hdlErrors = review.hdlDiagnostics.filter { it.severity == HdlDiagnosticSeverity.ERROR }
        if (hdlErrors.isNotEmpty()) throw HeadlessFpgaError("hdl", hdlErrors.joinToString("; ") { "${it.code}: ${it.notice.resolve(language)}" })
        for (diagnostic in review.hdlDiagnostics) {
            stderr.println("warning=fpga stage=hdl ${diagnostic.code}: ${diagnostic.notice.resolve(language)}")
        }
        val design = review.design ?: throw HeadlessFpgaError("drc", review.diagnostics
            .filter { it.severity == FpgaDiagnosticSeverity.ERROR }.joinToString("; ") { it.notice.resolve(language) })
        for (diagnostic in review.diagnostics) {
            stderr.println("warning=fpga stage=drc ${diagnostic.notice.resolve(language)}")
        }
        val legacyClockPorts = if (clockHdl == null) emptyList() else rootClockPorts.map { (component, port) ->
            HdlExportPort(port, PortDirection.INPUT, 1, circuit.id, component)
        }
        val mappingDesign = if (legacyClockPorts.isEmpty()) design else design.copy(ports = design.ports + legacyClockPorts.map {
            FpgaDesignPort(it.name, FpgaPinDirection.INPUT)
        })
        val codec = FpgaMappingCodec(mappingDesign, circuit, review.hdlPorts + legacyClockPorts)
        checkCancellation()
        val mapping = when (val result = fpgaStage("mapping", language) {
            val xml = mappingXml
            if (xml == null) codec.loadSaved(board, flow) else codec.import(xml, board, flow)
        }) {
            is FpgaMappingResult.Loaded -> result.mapping
            is FpgaMappingResult.Failure -> throw HeadlessFpgaError("mapping", result.notices.joinToString("; ") { it.resolve(language) })
            is FpgaMappingResult.Saved -> error("FPGA mapping reader returned a saved document.")
        }
        if (legacyClockPorts.any { port ->
                FpgaSignalBit(port.name) in mapping.bindings || port.name in mapping.clocks || port.name in mapping.clockScales
            }) throw HeadlessFpgaError("mapping", "Synthesized FPGA ticks cannot be combined with a mapped Clock component.")
        val tickPlan = clockHdl?.let { generated ->
            val requestedHz = fpgaStage("project", language) { FpgaTickTiming.requestedFrequencyHz(circuit, tickOverrideHz) }
            val oscillator = board.clock ?: throw HeadlessFpgaError("plan", "FPGA tick synthesis needs a board oscillator.")
            val timing = fpgaStage("plan", language) { FpgaTickTiming.forOscillator(oscillator.frequencyHz, requestedHz) }
            FpgaTickPlan(timing, generated.oscillatorPort, generated.waveforms)
        }
        val clockedDesign = design.copy(ports = design.ports.map { port ->
            port.copy(clockFrequencyHz = mapping.clocks[port.name], clockScale = mapping.clockScales[port.name])
        })
        checkCancellation()
        val plan = when (val result = fpgaStage("plan", language) {
            FpgaProjectGenerator.generate(clockedDesign, board, mapping.bindings, flow, tickPlan)
        }) {
            is FpgaPlanResult.Success -> result.plan
            is FpgaPlanResult.Failure -> throw HeadlessFpgaError("plan", result.diagnostics.joinToString("; ") {
                "${it.code}: ${it.notice.resolve(language)}"
            })
        }
        for (diagnostic in plan.diagnostics) stderr.println("warning=fpga stage=plan ${diagnostic.code}: ${diagnostic.notice.resolve(language)}")
        checkCancellation()
        val target = fpgaStage("output", language) { archiveTarget() }
        val build = companionEndpoint?.let { endpoint ->
            val request = fpgaStage("build", language) { plan.companionRequest(FpgaBuildGoal.BITSTREAM) }
            buildHeadlessFpga(endpoint, plan, request, requestId, stderr, language, checkCancellation)
        }
        val archive = fpgaStage("archive", language) { build?.let { fpgaArchive(it) } ?: plan.archive() }
        checkCancellation()
        fpgaStage("output", language) { writeArchive(target, archive, checkCancellation) }
        val status = if (build == null) "fpga-hdl-only" else "fpga-built"
        stdout.println("status=$status flow=${flow.name.lowercase()} files=${build?.size ?: plan.files.size}")
    }

    private fun archiveTarget(): Path {
        val target = outputFile.toAbsolutePath().normalize()
        for (input in listOfNotNull(projectFile, boardFile, mappingFile, cancelFile)) {
            require(target != input.toAbsolutePath().normalize() &&
                (!Files.exists(target) || !Files.exists(input) || !Files.isSameFile(input, target))) {
                "The FPGA archive must use a different path from its inputs."
            }
        }
        val parent = requireNotNull(target.parent) { "The FPGA archive destination must name a file." }
        require(Files.isDirectory(parent) && !Files.isDirectory(target)) { "The FPGA archive requires a file in an existing directory." }
        return target
    }

    private fun writeArchive(target: Path, bytes: ByteArray, checkCancellation: () -> Unit) {
        val temporary = Files.createTempFile(target.parent, ".logisim-fpga-", ".tmp")
        try {
            Files.write(temporary, bytes)
            checkCancellation()
            archiveTarget()
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        fun parse(arguments: Array<String>, startup: HeadlessStartup): HeadlessFpga? {
            if (arguments.firstOrNull() !in setOf("--test-fpga", "-f")) return null
            var index = 1
            fun value(option: String): String = arguments.getOrNull(index++)?.takeUnless { it.startsWith('-') || it.isBlank() }
                ?: throw HeadlessFpgaError("arguments", "Missing value for '$option'.")
            fun path(option: String) = fpgaStage("arguments", AppLanguage.ENGLISH) { Path.of(value(option)) }
            val project = path("PROJECT")
            val circuit = value("CIRCUIT")
            val board = path("BOARD_XML")
            var hdlOnly = false
            var mapping: Path? = null
            var output: Path? = null
            var cancel: Path? = null
            var companion: String? = null
            var requestId: String? = null
            var tickOverrideHz: Int? = null
            var hdlLanguage = HdlLanguage.VERILOG
            var approveBuild = false
            val seen = mutableSetOf<String>()
            while (index < arguments.size) {
                val afterStartup = startup.consume(arguments, index)
                if (afterStartup != null) {
                    index = afterStartup
                    continue
                }
                val option = arguments[index++]
                if (!seen.add(option)) throw HeadlessFpgaError("arguments", "Repeated option '$option'.")
                when (option) {
                    "HDLONLY" -> hdlOnly = true
                    "--mapping" -> mapping = path(option)
                    "--output" -> output = path(option)
                    "--cancel-file" -> cancel = path(option)
                    "--companion" -> companion = value(option)
                    "--approve-build" -> approveBuild = true
                    "--request-id" -> requestId = value(option)
                    "--hdl-language" -> hdlLanguage = when (val name = value(option).lowercase()) {
                        "verilog" -> HdlLanguage.VERILOG
                        "vhdl" -> HdlLanguage.VHDL
                        else -> throw HeadlessFpgaError("arguments", "Unknown FPGA HDL language '$name'.")
                    }
                    else -> {
                        // Evolution stores the override as a signed Int. Reject unsigned wraparound.
                        val frequency = option.toIntOrNull()?.takeUnless { option.startsWith('-') }
                            ?: throw HeadlessFpgaError("arguments", "Unknown FPGA argument '$option'.")
                        if (tickOverrideHz != null) throw HeadlessFpgaError("arguments", "Repeated FPGA tick frequency.")
                        tickOverrideHz = frequency
                    }
                }
            }
            val destination = output ?: throw HeadlessFpgaError("arguments", "FPGA requires --output FILE.")
            if (hdlOnly && (companion != null || approveBuild || requestId != null)) {
                throw HeadlessFpgaError("arguments", "HDLONLY cannot be combined with companion build options.")
            }
            if (hdlLanguage == HdlLanguage.VHDL && !hdlOnly) {
                throw HeadlessFpgaError("arguments", "VHDL FPGA export requires HDLONLY.")
            }
            if (!hdlOnly && (companion == null || !approveBuild)) {
                throw HeadlessFpgaError("build", "Use --companion URL --approve-build to build, or HDLONLY to export HDL locally.")
            }
            if (requestId != null && !Regex("[A-Za-z0-9._-]{1,64}").matches(requestId)) {
                throw HeadlessFpgaError("arguments", "Invalid FPGA request identifier.")
            }
            return HeadlessFpga(project, circuit, board, mapping, destination, companion, requestId, cancel, tickOverrideHz, hdlLanguage)
        }
    }
}

internal inline fun <T> fpgaStage(stage: String, language: AppLanguage, action: () -> T): T = try {
    action()
} catch (error: LocalizedArgumentException) {
    throw HeadlessFpgaError(stage, error.text.resolve(language))
} catch (error: IllegalArgumentException) {
    throw HeadlessFpgaError(stage, error.message.orEmpty())
} catch (error: IOException) {
    throw HeadlessFpgaError(stage, error.message.orEmpty())
}
