package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FpgaCompanionTest {
    private fun plan(flow: FpgaFlow = FpgaFlow.OPEN_ICE40): FpgaBuildPlan {
        val board = FpgaBoardDefinition("Fixture", FpgaDevice(FpgaVendor.OPEN_FPGA, "ice40", "hx1k", "tq144", "1"),
            null, listOf(FpgaBoardResource("gpio", "Pin", "GPIO", null,
                listOf(FpgaBoardPin("gpio", 0, "21", FpgaPinDirection.INOUT, FpgaElectrical())))))
        val design = FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG,
            listOf(FpgaSourceFile("hierarchy/user_top.v", "module user_top(output y); assign y=1'b1; endmodule")),
            listOf(FpgaDesignPort("y", FpgaPinDirection.OUTPUT)))
        val generated = FpgaProjectGenerator.generate(design, board, mapOf(FpgaSignalBit("y") to FpgaPinBinding.Physical("21")), FpgaFlow.OPEN_ICE40)
        return (generated as FpgaPlanResult.Success).plan.copy(flow = flow)
    }

    @Test fun uploadsOnlySelfContainedSourcesAndNeverClientProvidedBuildScripts() {
        val request = plan().companionRequest()
        assertEquals("fpga-synthesize", request.operation)
        assertEquals(listOf("source-0000.v", "source-0001.v"), request.inputs.map { it.name })
        assertEquals(CompanionJsonString("synthesis"), request.parameters["goal"])
        assertEquals(CompanionJsonArray(listOf(CompanionJsonString("source-0000.v"), CompanionJsonString("source-0001.v"))), request.parameters["sources"])
        assertEquals(CompanionJsonString("logisim_fpga_top"), request.parameters["top"])
        assertFalse("constraint" in request.parameters)
        assertTrue(request.inputs.none { it.name.endsWith(".ys") || it.name.endsWith(".tcl") })
    }

    @Test fun bitstreamRequestAddsOnlyTheFlowConstraintAndDoesNotProgram() {
        val request = plan().companionRequest(FpgaBuildGoal.BITSTREAM)
        assertEquals(listOf("source-0000.v", "source-0001.v", "pins.pcf"), request.inputs.map { it.name })
        assertEquals(CompanionJsonString("pins.pcf"), request.parameters["constraint"])
        assertEquals(CompanionJsonString("bitstream"), request.parameters["goal"])
        assertFalse("target" in request.parameters)
        assertFalse("flash" in request.parameters)
    }

    @Test fun vivadoBuildUsesOnlyFlattenedHdlAndBasys3Xdc() {
        val board = FpgaBoardDefinition("BASYS3", FpgaDevice(FpgaVendor.VIVADO, "Artix-7", "xc7a35t", "cpg236", "-1"),
            null, listOf(FpgaBoardResource("led", "LED", "LD0", null,
                listOf(FpgaBoardPin("led", 0, "U16", FpgaPinDirection.OUTPUT, FpgaElectrical(ioStandard = "LVCMOS33"))))))
        val design = FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG,
            listOf(FpgaSourceFile("design/user_top.v", "module user_top(output y); assign y=1'b1; endmodule")),
            listOf(FpgaDesignPort("y", FpgaPinDirection.OUTPUT)))
        val result = FpgaProjectGenerator.generate(design, board,
            mapOf(FpgaSignalBit("y") to FpgaPinBinding.Physical("U16")), FpgaFlow.VIVADO)
        val request = (result as FpgaPlanResult.Success).plan.companionRequest(FpgaBuildGoal.BITSTREAM)
        assertEquals("fpga-vivado-build", request.operation)
        assertEquals(listOf("source-0000.v", "source-0001.v", "pins.xdc"), request.inputs.map { it.name })
        assertEquals(CompanionJsonString("xc7a35tcpg236-1"), request.parameters["part"])
        assertFalse("target" in request.parameters)
        assertFalse("flash" in request.parameters)
        assertFailsWith<IllegalArgumentException> { result.plan.companionRequest(FpgaBuildGoal.SYNTHESIS) }
    }

    @Test fun vivadoRequestUsesTheAlchitryBoardPackage() {
        val board = FpgaBoardDefinition("ALCHITRY_AU_IO",
            FpgaDevice(FpgaVendor.VIVADO, "Artix-7", "xc7a35t", "ftg256", "-1"), null,
            listOf(FpgaBoardResource("led", "LED", "LD0", null,
                listOf(FpgaBoardPin("led", 0, "T5", FpgaPinDirection.OUTPUT, FpgaElectrical(ioStandard = "LVCMOS33"))))))
        val design = FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG,
            listOf(FpgaSourceFile("user_top.v", "module user_top(output y); assign y=1'b1; endmodule")),
            listOf(FpgaDesignPort("y", FpgaPinDirection.OUTPUT)))
        val result = FpgaProjectGenerator.generate(design, board,
            mapOf(FpgaSignalBit("y") to FpgaPinBinding.Physical("T5")), FpgaFlow.VIVADO)
        val request = (result as FpgaPlanResult.Success).plan.companionRequest(FpgaBuildGoal.BITSTREAM)
        assertEquals(CompanionJsonString("xc7a35tftg256-1"), request.parameters["part"])
        assertEquals(listOf("source-0000.v", "source-0001.v", "pins.xdc"), request.inputs.map { it.name })
    }

    @Test fun basys2IseUploadsOnlySourcesAndGeneratedPinsForAnApprovedBitstreamBuild() {
        val board = FpgaBoardDefinition("BASYS2", FpgaDevice(FpgaVendor.XILINX, "Spartan3E", "XC3S100E", "CP132", "4"),
            null, listOf(FpgaBoardResource("led", "LED", "LD0", null,
                listOf(FpgaBoardPin("led", 0, "M5", FpgaPinDirection.OUTPUT, FpgaElectrical())))))
        val design = FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG,
            listOf(FpgaSourceFile("user_top.v", "module user_top(output y); assign y=1'b1; endmodule")),
            listOf(FpgaDesignPort("y", FpgaPinDirection.OUTPUT)))
        val generated = FpgaProjectGenerator.generate(design, board, mapOf(FpgaSignalBit("y") to FpgaPinBinding.Physical("M5")), FpgaFlow.ISE)
        val plan = (generated as FpgaPlanResult.Success).plan
        val request = plan.companionRequest(FpgaBuildGoal.BITSTREAM)
        assertEquals(listOf(FpgaBuildGoal.BITSTREAM), FpgaFlow.ISE.companionBuildGoals())
        assertEquals("fpga-ise-build", request.operation)
        assertEquals(listOf("source-0000.v", "source-0001.v", "pins.ucf"), request.inputs.map { it.name })
        assertEquals(setOf("language", "sources"), request.parameters.keys)
        assertFailsWith<IllegalArgumentException> { plan.companionRequest() }
        assertFailsWith<IllegalArgumentException> {
            plan.copy(board = board.copy(device = board.device.copy(part = "XC3S250E"))).companionRequest(FpgaBuildGoal.BITSTREAM)
        }
    }

    @Test fun microlabtleIseUsesCpldFittingAndJedecGeneration() {
        val board = FpgaBoardDefinition("MICROLABTLE", FpgaDevice(FpgaVendor.XILINX, "Coolrunner", "XC2C64", "VQ44", "5"),
            null, listOf(FpgaBoardResource("led", "LED", "LD0", null,
                listOf(FpgaBoardPin("led", 0, "P5", FpgaPinDirection.OUTPUT, FpgaElectrical())))))
        val design = FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG,
            listOf(FpgaSourceFile("user_top.v", "module user_top(output y); assign y=1'b1; endmodule")),
            listOf(FpgaDesignPort("y", FpgaPinDirection.OUTPUT)))
        val generated = FpgaProjectGenerator.generate(design, board, mapOf(FpgaSignalBit("y") to FpgaPinBinding.Physical("P5")), FpgaFlow.ISE)
        val plan = (generated as FpgaPlanResult.Success).plan
        val request = plan.companionRequest(FpgaBuildGoal.BITSTREAM)
        assertEquals("fpga-ise-cpld-build", request.operation)
        assertEquals(listOf("source-0000.v", "source-0001.v", "pins.ucf"), request.inputs.map { it.name })
        assertEquals(listOf("xst", "ngdbuild", "cpldfit", "hprep6"), plan.stages.map { it.tool })
        assertEquals(listOf("logisim.jed"), plan.stages.last().outputFiles)
        assertFailsWith<IllegalArgumentException> {
            plan.copy(board = board.copy(device = board.device.copy(packageName = "VQ100"))).companionRequest(FpgaBuildGoal.BITSTREAM)
        }
    }

    @Test fun spartanIseBoardsSubmitTheReviewedProfilesWithGeneratedPins() {
        data class Target(val name: String, val device: FpgaDevice, val pull: FpgaPull,
                          val input: String, val output: String, val profile: String)
        val targets = listOf(
            Target("DIGILENT_SPARTAN_3E_STARTER", FpgaDevice(FpgaVendor.XILINX, "Spartan", "XC3S500E", "FG320", "5"),
                FpgaPull.FLOAT, "N17", "F9", "digilent-spartan-3e-starter"),
            Target("GECKO4LED", FpgaDevice(FpgaVendor.XILINX, "Spartan3", "XC3S5000", "FG676", "5"),
                FpgaPull.DOWN, "K1", "AC25", "gecko4led"),
        )
        val design = FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG,
            listOf(FpgaSourceFile("user_top.v", "module user_top(input a, output y); assign y=a; endmodule")),
            listOf(FpgaDesignPort("a", FpgaPinDirection.INPUT), FpgaDesignPort("y", FpgaPinDirection.OUTPUT)))
        for (target in targets) {
            val button = FpgaBoardResource("button", "Button", "A", null,
                listOf(FpgaBoardPin("button", 0, target.input, FpgaPinDirection.INPUT, FpgaElectrical())))
            val led = FpgaBoardResource("led", "Led", "Y", null,
                listOf(FpgaBoardPin("led", 0, target.output, FpgaPinDirection.OUTPUT, FpgaElectrical())))
            val board = FpgaBoardDefinition(target.name, target.device, null, listOf(button, led), target.pull)
            val bindings = mapOf(FpgaSignalBit("a") to FpgaPinBinding.Physical(target.input),
                FpgaSignalBit("y") to FpgaPinBinding.Physical(target.output))
            val generated = FpgaProjectGenerator.generate(design, board, bindings, FpgaFlow.ISE)
            val plan = (generated as FpgaPlanResult.Success).plan
            val request = plan.companionRequest(FpgaBuildGoal.BITSTREAM)
            assertEquals("fpga-ise-build", request.operation)
            assertEquals(CompanionJsonString(target.profile), request.parameters["profile"])
            assertEquals(setOf("language", "sources", "profile"), request.parameters.keys)
            assertEquals(listOf("source-0000.v", "source-0001.v", "pins.ucf"), request.inputs.map { it.name })
            val pins = plan.files.single { it.path == "pins.ucf" }.content
            assertTrue(pins.contains("LOC = \"${target.input}\""))
            assertTrue(pins.contains("LOC = \"${target.output}\""))
            assertFailsWith<IllegalArgumentException> {
                plan.copy(board = board.copy(unusedPinPull = FpgaPull.UP)).companionRequest(FpgaBuildGoal.BITSTREAM)
            }
        }
    }

    @Test fun unsupportedVendorFlowsCannotBeSubmittedAsOpenToolchains() {
        assertFailsWith<IllegalArgumentException> { plan(FpgaFlow.VIVADO).companionRequest(FpgaBuildGoal.BITSTREAM) }
    }

    @Test fun de0CvPackageAndGeneratedConstraintsAreAcceptedWithoutUploadingQuartusBuildScripts() {
        val plan = quartusPlan("""
            <DE0-CV>
              <BoardInformation>
                <FPGAInformation Vendor="ALTERA" Family="Cyclone V" Package="FBGA 484" Part="5CEBA4F23C7" Speedgrade="C7"/>
              </BoardInformation>
              <IOComponents>
                <Button InputPinSet="PIN_U13" Rect_x_y_w_h="319,346,20,35"/>
                <Led OutputPinSet="PIN_W2" Rect_x_y_w_h="272,319,15,15"/>
              </IOComponents>
            </DE0-CV>
        """.trimIndent(), "PIN_U13", "PIN_W2")
        val request = plan.companionRequest(FpgaBuildGoal.BITSTREAM)
        assertEquals("fpga-quartus-build", request.operation)
        assertEquals(listOf("source-0000.v", "source-0001.v", "pins.qsf", "timing.sdc"), request.inputs.map { it.name })
        assertEquals(setOf("language", "sources"), request.parameters.keys)
        assertTrue(plan.files.single { it.path == "pins.qsf" }.content.contains("PIN_U13 -to fpga_p0_b0"))
        assertTrue(plan.files.single { it.path == "pins.qsf" }.content.contains("PIN_W2 -to fpga_p1_b0"))
        assertFailsWith<IllegalArgumentException> { plan.companionRequest(FpgaBuildGoal.SYNTHESIS) }
    }

    @Test fun beMicroSdkBuildUsesTheCatalogDeviceAndQuartusFamily() {
        val plan = quartusPlan("""
            <BEMICRO_SDK>
              <BoardInformation>
                <FPGAInformation Vendor="ALTERA" Family="Cyclone IV" Package="FBGA 256" Part="EP4CE22F17C7" Speedgrade="7"/>
              </BoardInformation>
              <IOComponents>
                <Button InputPinSet="PIN_C2" Rect_x_y_w_h="0,0,20,35"/>
                <LED OutputPinSet="PIN_N15" Rect_x_y_w_h="20,0,15,15"/>
              </IOComponents>
            </BEMICRO_SDK>
        """.trimIndent(), "PIN_C2", "PIN_N15")
        val request = plan.companionRequest(FpgaBuildGoal.BITSTREAM)
        assertEquals("fpga-quartus-build", request.operation)
        assertEquals(CompanionJsonString("ep4ce22f17c7"), request.parameters["target"])
        assertEquals(listOf("source-0000.v", "source-0001.v", "pins.qsf", "timing.sdc"), request.inputs.map { it.name })
        val qsf = plan.files.single { it.path == "build.qsf" }.content
        assertTrue("set_global_assignment -name FAMILY \"Cyclone IV E\"" in qsf)
        assertTrue("set_global_assignment -name DEVICE EP4CE22F17C7" in qsf)
        assertEquals(listOf("output_files/build.sof"), plan.stages.single().outputFiles)
        assertFailsWith<IllegalArgumentException> {
            val wrongPackage = plan.board.device.copy(packageName = "FBGA 484")
            plan.copy(board = plan.board.copy(device = wrongPackage)).companionRequest(FpgaBuildGoal.BITSTREAM)
        }
    }

    @Test fun maxVUsesTheApprovedQuartusProfileAndExportsPof() {
        val plan = quartusPlan("""
            <MAX_V_CONSOLE>
              <BoardInformation>
                <FPGAInformation Vendor="ALTERA" Family="MAX V" Package="FBGA 256" Part="5M570ZF256C5" Speedgrade="5"/>
              </BoardInformation>
              <IOComponents>
                <Button InputPinSet="PIN_N12" Rect_x_y_w_h="490,86,15,23"/>
                <LED OutputPinSet="PIN_P9" Rect_x_y_w_h="491,21,15,19"/>
              </IOComponents>
            </MAX_V_CONSOLE>
        """.trimIndent(), "PIN_N12", "PIN_P9")
        assertEquals(listOf("output_files/build.pof"), plan.stages.single().outputFiles)
        val request = plan.companionRequest(FpgaBuildGoal.BITSTREAM)
        assertEquals("fpga-quartus-build", request.operation)
        assertEquals(CompanionJsonString("max-v-5m570z"), request.parameters["target"])
        assertEquals(listOf("source-0000.v", "source-0001.v", "pins.qsf", "timing.sdc"), request.inputs.map { it.name })
        assertFalse(plan.programsHardware)
    }

    @Test fun max7000ExportsPofAndRequestsTheCatalogPart() {
        for ((name, family, part, packageName, target) in listOf(
            listOf("EPM2525_CONSOLE", "MAX 7000", "EPM7128SLC84-7", "PQFP 84", "max-7000-epm7128s"),
            listOf("EPM2525_FULL", "MAX 7000", "EPM7128SLC84-7", "PQFP 84", "max-7000-epm7128s"),
            listOf("SLO_MAX7000S", "MAX7000S", "EPM7256SQC208-7", "RQFP 208", "max-7000-epm7256s"),
        )) {
            val plan = quartusPlan("""
                <$name>
                  <BoardInformation>
                    <FPGAInformation Vendor="ALTERA" Family="$family" Package="$packageName" Part="$part" Speedgrade="7"/>
                  </BoardInformation>
                  <IOComponents>
                    <Button InputPinSet="PIN_8" Rect_x_y_w_h="8,8,8,8"/>
                    <LED OutputPinSet="PIN_9" Rect_x_y_w_h="17,17,8,8"/>
                  </IOComponents>
                </$name>
            """.trimIndent(), "PIN_8", "PIN_9")
            assertEquals(listOf("output_files/build.pof"), plan.stages.single().outputFiles)
            val qsf = plan.files.single { it.path == "build.qsf" }.content
            assertTrue(qsf.contains("FAMILY \"MAX7000S\""))
            assertTrue(qsf.contains("DEVICE $part"))
            val request = plan.companionRequest(FpgaBuildGoal.BITSTREAM)
            assertEquals(CompanionJsonString(target), request.parameters["target"])
            assertEquals("design.pof", request.quartusProgrammingFilename())
            assertTrue(request.inputs.any { it.name == "pins.qsf" })
        }
    }

    private fun quartusPlan(boardXml: String, input: String, output: String): FpgaBuildPlan {
        val imported = FpgaBoardCodec.read(boardXml) as FpgaBoardImportResult.Success
        val design = FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG,
            listOf(FpgaSourceFile("user_top.v", "module user_top(input a, output y); assign y=a; endmodule")),
            listOf(FpgaDesignPort("a", FpgaPinDirection.INPUT), FpgaDesignPort("y", FpgaPinDirection.OUTPUT)))
        val generated = FpgaProjectGenerator.generate(design, imported.board, mapOf(
            FpgaSignalBit("a") to FpgaPinBinding.Physical(input),
            FpgaSignalBit("y") to FpgaPinBinding.Physical(output),
        ), FpgaFlow.QUARTUS)
        return (generated as FpgaPlanResult.Success).plan
    }
}
