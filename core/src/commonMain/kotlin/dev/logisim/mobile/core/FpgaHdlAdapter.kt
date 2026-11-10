package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.message
import dev.logisim.mobile.core.localization.requireLocalized

/** Clock rates describe the physical input selected by the user, never the simulator tick rate. */
fun HdlExportResult.fpgaDesign(
    language: HdlLanguage,
    clockFrequenciesHz: Map<String, Long> = emptyMap(),
    options: FpgaGenerationOptions = FpgaGenerationOptions(),
): FpgaDesignArtifact {
    requireLocalized(successful) { message("fpga_adapter_failed_hdl") }
    requireLocalized(clockFrequenciesHz.all { (name, frequency) ->
        frequency > 0 && ports.any { it.name == name && it.direction == PortDirection.INPUT && it.width == 1 }
    }) { message("fpga_adapter_invalid_clock") }
    return FpgaDesignArtifact(
        topLevelName,
        when (language) { HdlLanguage.VERILOG -> FpgaHdlLanguage.VERILOG; HdlLanguage.VHDL -> FpgaHdlLanguage.VHDL },
        files.map { FpgaSourceFile(it.path, it.content) },
        ports.map { port ->
            FpgaDesignPort(port.name, when (port.direction) {
                PortDirection.INPUT -> FpgaPinDirection.INPUT
                PortDirection.OUTPUT -> FpgaPinDirection.OUTPUT
                PortDirection.INOUT -> FpgaPinDirection.INOUT
            }, port.width, clockFrequenciesHz[port.name], port.reptarBank, vhdlVector = language == HdlLanguage.VHDL)
        },
        options.vhdlKeywordsUpperCase,
    )
}
