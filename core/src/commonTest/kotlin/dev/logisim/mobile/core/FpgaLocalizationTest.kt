package dev.logisim.mobile.core

import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.Messages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FpgaLocalizationTest {
    private fun board(electrical: FpgaElectrical = FpgaElectrical()) = FpgaBoardDefinition(
        "iCE40", FpgaDevice(FpgaVendor.OPEN_FPGA, "ice40", "hx1k", "tq144", "1"), null,
        listOf(FpgaBoardResource("io", "Pin", "GPIO", null,
            listOf(FpgaBoardPin("io", 0, "21", FpgaPinDirection.INOUT, electrical),
                FpgaBoardPin("io", 1, "22", FpgaPinDirection.INOUT, electrical)))))

    private fun design() = FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG,
        listOf(FpgaSourceFile("user_top.v", "module user_top(input a, output y); assign y = a; endmodule")),
        listOf(FpgaDesignPort("a", FpgaPinDirection.INPUT), FpgaDesignPort("y", FpgaPinDirection.OUTPUT)))

    private fun bindings() = mapOf(FpgaSignalBit("a") to FpgaPinBinding.Physical("21"),
        FpgaSignalBit("y") to FpgaPinBinding.Physical("22"))

    @Test fun rendersTheSameDiagnosticInBothLanguagesWithoutReplacingUserBraces() {
        val artifact = design().copy(files = listOf(FpgaSourceFile("../{path}.v", "")))
        val diagnostic = FpgaDesignRules.check(artifact, board(), bindings(), FpgaFlow.OPEN_ICE40)
            .single { it.code == "unsafe-source-path" }
        assertEquals("Invalid HDL source path: ../{path}.v.", diagnostic.notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Caminho HDL inválido: ../{path}.v.", diagnostic.notice.resolve(AppLanguage.PORTUGUESE))
        assertEquals("Caminho HDL inválido: ../{path}.v.", diagnostic.message)

        val port = FpgaDesignPort("Entrada{bit}", FpgaPinDirection.INPUT)
        val unmapped = FpgaDesignRules.check(design().copy(ports = listOf(port)), board(), emptyMap(), FpgaFlow.OPEN_ICE40)
            .single { it.code == "unmapped-port" }
        assertEquals("Unmapped terminal: Entrada{bit}[0].", unmapped.notice.resolve(AppLanguage.ENGLISH))
    }

    @Test fun keepsImportedBoardErrorsAndWarningsLocalizable() {
        val xml = """<board><BoardInformation><FPGAInformation Vendor="OPENFPGA" Family="ice40" Part="hx1k" Package="tq144" Speedgrade="1"/></BoardInformation><IOComponents><Pin BiDirPinSet="21" FPGAPinIOStandard="{standard}"/></IOComponents></board>"""
        val failure = assertIs<FpgaBoardImportResult.Failure>(FpgaBoardCodec.read(xml))
        assertEquals("fpga_core_unrecognized_io_standard", failure.diagnosticNotices.single().key)
        assertEquals("{standard}", failure.diagnosticNotices.single().arguments["standard"])
        assertEquals("Unrecognized I/O standard: {standard}.", failure.diagnosticNotices.single().resolve(AppLanguage.ENGLISH))
        assertEquals("Padrão elétrico não reconhecido: {standard}.", failure.diagnostics.single())

        val missingInformation = xmlFailureNotice("<board/>")
        assertEquals("fpga_core_board_information", missingInformation.key)
        assertTrue(missingInformation.localizedArguments.isEmpty())

        val noResources = xml.substringBefore("<IOComponents>") + "</board>"
        val success = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(noResources))
        assertEquals("The board declares no I/O resources.", success.warningNotices.single().resolve(AppLanguage.ENGLISH))
        assertEquals("A placa não declara recursos de I/O.", success.warnings.single())

    }

    @Test fun wrapsXmlSyntaxErrorsWithBoardContextAndTheirTypedParserCause() {
        val cases = listOf(
            Triple("<!DOCTYPE board><board/>", Messages.CodecXmlNameInvalid,
                "Invalid XML name." to "Nome XML inválido."),
            Triple("<board></other>", Messages.CodecXmlClosingInvalid,
                "Incorrect XML closing element: other, expected board." to "Fechamento XML incorreto: other, esperado board."),
        )
        for ((xml, expectedKey, texts) in cases) {
            val notice = xmlFailureNotice(xml)
            assertEquals("fpga_core_xml_import_failed", notice.key)
            assertTrue("reason" !in notice.arguments)
            val reason = assertIs<LocalizedText>(notice.localizedArguments["reason"])
            assertEquals(expectedKey, reason.key)
            assertEquals("Unable to import the FPGA board XML: ${texts.first}", notice.resolve(AppLanguage.ENGLISH))
            assertEquals("Não foi possível importar o XML da placa FPGA: ${texts.second}", notice.resolve(AppLanguage.PORTUGUESE))
        }
    }

    @Test fun preservesParserDataBracesAcrossTheNestedBoardDiagnostic() {
        val notice = xmlFailureNotice("<board>&{reason};</board>")
        val reason = assertIs<LocalizedText>(notice.localizedArguments["reason"])
        assertEquals(Messages.CodecXmlEntityUnsupported, reason.key)
        assertEquals("{reason}", reason.arguments["entity"])
        assertEquals("Unable to import the FPGA board XML: Unsupported XML entity: &{reason};.", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Não foi possível importar o XML da placa FPGA: Entidade XML não suportada: &{reason};.", notice.resolve(AppLanguage.PORTUGUESE))
        assertEquals("Unable to import the FPGA board XML: Unsupported XML entity: &{reason};.", notice.resolve(AppLanguage.ENGLISH))
    }

    @Test fun describesTheBoardSizeLimitWithoutCallingItACircuitFile() {
        val notice = xmlFailureNotice(" ".repeat(2_000_001))
        assertEquals("fpga_core_board_xml_size", notice.localizedArguments["reason"]?.key)
        assertEquals("Unable to import the FPGA board XML: The board definition exceeds the 2 MB text limit.", notice.resolve(AppLanguage.ENGLISH))
        assertEquals("Não foi possível importar o XML da placa FPGA: A definição da placa excede o limite de 2 MB de texto.", notice.resolve(AppLanguage.PORTUGUESE))
    }

    private fun xmlFailureNotice(xml: String) =
        assertIs<FpgaBoardImportResult.Failure>(FpgaBoardCodec.read(xml)).diagnosticNotices.single()

    @Test fun rejectsElectricalSettingsThatTheSelectedFlowWouldDrop() {
        for ((electrical, code) in listOf(
            FpgaElectrical(pull = FpgaPull.DOWN) to "unsupported-pull",
            FpgaElectrical(driveMilliamps = 8) to "unsupported-drive",
            FpgaElectrical(ioStandard = "LVCMOS18") to "unsupported-io-standard",
        )) {
            val diagnostics = FpgaDesignRules.check(design(), board(electrical), bindings(), FpgaFlow.OPEN_ICE40)
            assertEquals(setOf(code), diagnostics.map { it.code }.toSet())
            assertIs<FpgaPlanResult.Failure>(FpgaProjectGenerator.generate(design(), board(electrical), bindings(), FpgaFlow.OPEN_ICE40))
        }
        val quartus = board(FpgaElectrical(pull = FpgaPull.DOWN)).let { it.copy(device = it.device.copy(vendor = FpgaVendor.ALTERA)) }
        assertEquals(setOf("unsupported-pull"),
            FpgaDesignRules.check(design(), quartus, bindings(), FpgaFlow.QUARTUS).map { it.code }.toSet())
    }

    @Test fun reportsUnusedPullSettingsThatOpenFlowsCannotApply() {
        val board = board().copy(unusedPinPull = FpgaPull.UP)
        val plan = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(design(), board, bindings(), FpgaFlow.OPEN_ICE40)).plan
        assertTrue(plan.canSynthesize)
        val warning = plan.diagnostics.single()
        assertEquals(FpgaDiagnosticSeverity.WARNING, warning.severity)
        assertEquals("unused-pin-pull-not-applied", warning.code)
        assertEquals("This open flow does not apply the board's pull setting for unused pins.",
            warning.notice.resolve(AppLanguage.ENGLISH))
    }

    @Test fun preservesUnusedPinPullPolicyInQuartusAndIseOutputs() {
        for ((pull, setting) in listOf(FpgaPull.FLOAT to "TRI-STATED", FpgaPull.UP to "PULLUP", FpgaPull.DOWN to "PULLDOWN")) {
            val quartus = board().let { it.copy(device = it.device.copy(vendor = FpgaVendor.ALTERA), unusedPinPull = pull) }
            val plan = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(design(), quartus, bindings(), FpgaFlow.QUARTUS)).plan
            assertTrue(plan.files.single { it.path == "pins.qsf" }.content.contains(
                "set_global_assignment -name RESERVE_ALL_UNUSED_PINS \"AS INPUT $setting\"\n"))

            val ise = quartus.copy(device = quartus.device.copy(vendor = FpgaVendor.XILINX))
            val isePlan = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(design(), ise, bindings(), FpgaFlow.ISE)).plan
            val expected = listOf("-w") + when (pull) {
                FpgaPull.FLOAT -> emptyList()
                FpgaPull.UP -> listOf("-g", "UnusedPin:PULLUP")
                FpgaPull.DOWN -> listOf("-g", "UnusedPin:PULLDOWN")
            } + listOf("routed.ncd", "design.bit")
            assertEquals(expected, isePlan.stages.single { it.tool == "bitgen" }.arguments)
        }
    }
}
