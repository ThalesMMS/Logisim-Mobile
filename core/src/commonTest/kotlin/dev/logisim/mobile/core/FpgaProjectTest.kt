package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FpgaProjectTest {
    private val boardXml = """
        <TEST_BOARD><BoardInformation>
          <ClockInformation FPGApin="F1" Frequency="50000000" IOStandard="LVCMOS33" PullBehavior="Float"/>
          <FPGAInformation Vendor="OPENFPGA" Family="epc5" Part="85k" Package="CABGA381" Speedgrade="5"/>
          <UnusedPins PullBehavior="Pull Up"/>
        </BoardInformation><IOComponents>
          <Button Label="Reset" InputPinSet="G1" ActivityLevel="Active low" FPGAPinIOStandard="LVCMOS33" FPGAPinPullBehavior="Pull Up" Rect_x_y_w_h="10,20,30,40"/>
          <LED Label="Result" OutputPinSet="A1,A2" FPGAPinIOStandard="LVCMOS33" FPGAPinDriveStrength="4 mA"/>
          <Pin Label="GPIO" BiDirPinSet="C3" FPGAPinIOStandard="LVCMOS33"/>
        </IOComponents><BoardPicture><opaque value="preserved"/></BoardPicture></TEST_BOARD>
    """.trimIndent()
    private fun board() = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(boardXml)).board
    private fun design() = FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG,
        listOf(FpgaSourceFile("user_top.v", "module user_top(input clk, input reset, output [1:0] led); assign led = {clk,reset}; endmodule\n")),
        listOf(FpgaDesignPort("clk", FpgaPinDirection.INPUT, clockFrequencyHz = 50_000_000),
            FpgaDesignPort("reset", FpgaPinDirection.INPUT), FpgaDesignPort("led", FpgaPinDirection.OUTPUT, 2)))
    private fun bindings() = mapOf(
        FpgaSignalBit("clk") to FpgaPinBinding.Physical("F1"),
        FpgaSignalBit("reset") to FpgaPinBinding.Physical("G1"),
        FpgaSignalBit("led", 0) to FpgaPinBinding.Physical("A1"),
        FpgaSignalBit("led", 1) to FpgaPinBinding.Physical("A2"),
    )

    @Test fun importsElectricalMetadataWithoutDiscardingTheOriginalBoard() {
        val board = board()
        assertEquals("TEST_BOARD", board.name)
        assertEquals(FpgaVendor.OPEN_FPGA, board.device.vendor)
        assertEquals("85k", board.device.part)
        assertEquals("CABGA381", board.device.packageName)
        assertEquals(1, board.device.jtagPosition)
        assertEquals(50_000_000, board.clock?.frequencyHz)
        assertEquals(FpgaPull.UP, board.unusedPinPull)
        assertEquals(4, board.pins.size)
        assertEquals(listOf("G1", "A1", "A2", "C3"), board.pins.map { it.location })
        assertEquals(FpgaRectangle(10, 20, 30, 40), board.resources.first().rectangle)
        assertTrue(board.pins.first().electrical.activeLow)
        assertEquals(4, board.pins[1].electrical.driveMilliamps)
        assertEquals(boardXml, board.originalXml)
    }

    @Test fun supportsLegacySingleAndIndexedPinFields() {
        val xml = boardXml.replace("InputPinSet=\"G1\"", "FPGAPinName=\"G1\"")
            .replace("OutputPinSet=\"A1,A2\"", "NrOfPins=\"2\" FPGAPin_0=\"A1\" FPGAPin_1=\"A2\"")
        val board = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(xml)).board
        assertEquals(listOf(FpgaPinDirection.INPUT, FpgaPinDirection.OUTPUT, FpgaPinDirection.OUTPUT, FpgaPinDirection.INOUT), board.pins.map { it.direction })
    }

    @Test fun rejectsMalformedOrIncompleteBoardRatherThanDroppingResources() {
        for (xml in listOf(boardXml.replace("Frequency=\"50000000\"", "Frequency=\"0\""),
            boardXml.replace("OutputPinSet=\"A1,A2\"", ""), boardXml.replace("30,40", "0,40"),
            boardXml.replace("LVCMOS33", "UNRECOGNIZED"), "<!DOCTYPE board><board/>")) {
            assertIs<FpgaBoardImportResult.Failure>(FpgaBoardCodec.read(xml))
        }
    }

    @Test fun generatesDeterministicPolarityWrapperAndEcp5Constraints() {
        val plan = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(design(), board(), bindings(), FpgaFlow.OPEN_ECP5)).plan
        assertTrue(plan.canSynthesize)
        assertFalse(plan.programsHardware)
        assertEquals("logisim_fpga_top", plan.topLevelName)
        val files = plan.files.associate { it.path to it.content }
        assertTrue(files.getValue("logisim_fpga_top.v").contains("assign design_p1 = ~fpga_p1_b0;"))
        assertTrue(files.getValue("logisim_fpga_top.v").contains(".led(design_p2)"))
        assertEquals("""
            BLOCK RESETPATHS;
            BLOCK ASYNCPATHS;
            LOCATE COMP "fpga_p0_b0" SITE "F1";
            IOBUF PORT "fpga_p0_b0" PULLMODE=NONE IO_TYPE=LVCMOS33;
            FREQUENCY PORT "fpga_p0_b0" 50 MHZ;
            LOCATE COMP "fpga_p1_b0" SITE "G1";
            IOBUF PORT "fpga_p1_b0" PULLMODE=UP IO_TYPE=LVCMOS33;
            LOCATE COMP "fpga_p2_b0" SITE "A1";
            IOBUF PORT "fpga_p2_b0" PULLMODE=NONE IO_TYPE=LVCMOS33 DRIVE=4;
            LOCATE COMP "fpga_p2_b1" SITE "A2";
            IOBUF PORT "fpga_p2_b1" PULLMODE=NONE IO_TYPE=LVCMOS33 DRIVE=4;
        """.trimIndent() + "\n", files.getValue("pins.lpf"))
        assertEquals(listOf("yosys", "nextpnr-ecp5", "ecppack"), plan.stages.map { it.tool })
        assertEquals(listOf("--85k", "--package", "CABGA381", "--json", "synthesized.json", "--lpf", "pins.lpf", "--textcfg", "routed.config"), plan.stages[1].arguments)
        assertEquals(listOf("--compress", "--freq", "62.0", "--input", "routed.config", "--bit", "design.bit"), plan.stages[2].arguments)
        val recipe = BundleZip.read(plan.archive()).getValue("build-ecp5.sh").decodeToString()
        assertTrue(recipe.contains("CDPATH= cd -- \"\$(dirname -- \"\$0\")\"\n"))
        assertTrue(recipe.endsWith("ecppack '--compress' '--freq' '62.0' '--input' 'routed.config' '--bit' 'design.bit'\n"))
        assertEquals(listOf("source-0000.v", "source-0001.v", "pins.lpf"),
            plan.companionRequest(FpgaBuildGoal.BITSTREAM).inputs.map { it.name })
        assertEquals(plan, assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(design(), board(), bindings(), FpgaFlow.OPEN_ECP5)).plan)
    }

    @Test fun reportsAllMissingBitsAndDoesNotGeneratePartialProjects() {
        val failure = assertIs<FpgaPlanResult.Failure>(FpgaProjectGenerator.generate(design(), board(), emptyMap(), FpgaFlow.OPEN_ECP5))
        assertEquals(4, failure.diagnostics.count { it.code == "unmapped-port" })
        assertEquals(setOf(FpgaSignalBit("clk"), FpgaSignalBit("reset"), FpgaSignalBit("led", 0), FpgaSignalBit("led", 1)), failure.diagnostics.mapNotNull { it.signal }.toSet())
    }

    @Test fun detectsDuplicatePinsIncompatibleDirectionAndExtraBits() {
        val invalid = bindings() + (FpgaSignalBit("led", 0) to FpgaPinBinding.Physical("G1")) +
            (FpgaSignalBit("missing", 3) to FpgaPinBinding.Open)
        val codes = FpgaDesignRules.check(design(), board(), invalid, FpgaFlow.OPEN_ECP5).map { it.code }
        assertTrue("pin-already-used" in codes)
        assertTrue("pin-direction" in codes)
        assertTrue("unknown-signal" in codes)
    }

    @Test fun constantsAndOpenOutputsNeverAllocatePackagePins() {
        val map = bindings() + (FpgaSignalBit("reset") to FpgaPinBinding.Constant(true)) + (FpgaSignalBit("led", 1) to FpgaPinBinding.Open)
        val plan = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(design(), board(), map, FpgaFlow.OPEN_ECP5)).plan
        assertEquals(2, plan.mappedPins.size)
        assertTrue(plan.files.single { it.path == "logisim_fpga_top.v" }.content.contains("assign design_p1 = 1'b1;"))
        assertFalse(plan.files.single { it.path == "pins.lpf" }.content.contains("A2"))
    }

    @Test fun invalidConstantAndOpenMappingsAreRejected() {
        val map = bindings() + (FpgaSignalBit("reset") to FpgaPinBinding.Open) + (FpgaSignalBit("led", 1) to FpgaPinBinding.Constant(false))
        assertEquals(setOf("open-input", "constant-output"), FpgaDesignRules.check(design(), board(), map, FpgaFlow.OPEN_ECP5)
            .filter { it.severity == FpgaDiagnosticSeverity.ERROR }.map { it.code }.toSet())
    }

    @Test fun clockFrequencyMismatchCannotBePresentedAsSuccessfulTiming() {
        val design = design().copy(ports = design().ports.map { if (it.name == "clk") it.copy(clockFrequencyHz = 12_000_000) else it })
        assertTrue(FpgaDesignRules.check(design, board(), bindings(), FpgaFlow.OPEN_ECP5).any { it.code == "clock-frequency-mismatch" })
    }

    @Test fun ice40ProjectsPreservePhysicalClockFrequencyInTheirExportedConstraints() {
        for ((frequency, megahertz) in listOf(50_000_000L to "50", 12_288_000L to "12.288")) {
            val ice40 = board().copy(
                device = FpgaDevice(FpgaVendor.OPEN_FPGA, "ice40", "hx1k", "tq144", "1"),
                clock = board().clock!!.copy(frequencyHz = frequency),
                resources = board().resources.map { resource ->
                    resource.copy(pins = resource.pins.map { it.copy(electrical = it.electrical.copy(driveMilliamps = null)) })
                },
                unusedPinPull = FpgaPull.FLOAT,
            )
            val clocked = design().copy(ports = design().ports.map {
                if (it.name == "clk") it.copy(clockFrequencyHz = frequency) else it
            })
            val plan = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(clocked, ice40, bindings(), FpgaFlow.OPEN_ICE40)).plan
            assertEquals("""
                set_io fpga_p0_b0 F1
                set_frequency fpga_p0_b0 $megahertz
                set_io -pullup yes fpga_p1_b0 G1
                set_io fpga_p2_b0 A1
                set_io fpga_p2_b1 A2
            """.trimIndent() + "\n", plan.files.single { it.path == "pins.pcf" }.content)
        }
    }

    @Test fun rejectsPathsAndDeviceStringsThatCouldAlterToolScripts() {
        val dangerous = design().copy(files = listOf(FpgaSourceFile("../injected.v", "module bad; endmodule")))
        assertTrue(FpgaDesignRules.check(dangerous, board(), bindings(), FpgaFlow.OPEN_ECP5).any { it.code == "unsafe-source-path" })
        val board = board().copy(device = board().device.copy(packageName = "CABGA381;exec"))
        assertTrue(FpgaDesignRules.check(design(), board, bindings(), FpgaFlow.OPEN_ECP5).any { it.code == "invalid-device" })
        for (path in listOf("synthesis.ys", "BUILD-CPLD.SH", "BUILD-ECP5.SH")) {
            val reserved = design().copy(files = listOf(FpgaSourceFile(path, "exec touch bad")))
            assertTrue(FpgaDesignRules.check(reserved, board(), bindings(), FpgaFlow.OPEN_ECP5).any { it.code == "reserved-source-name" })
        }
    }

    @Test fun preservesBusesAndBidirectionalActualsInBothHdlLanguages() {
        val artifact = FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG, listOf(FpgaSourceFile("user_top.v", "module user_top(inout io); endmodule")),
            listOf(FpgaDesignPort("io", FpgaPinDirection.INOUT)))
        val map = mapOf(FpgaSignalBit("io") to FpgaPinBinding.Physical("C3"))
        val verilog = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(artifact, board(), map, FpgaFlow.OPEN_ECP5)).plan
        assertTrue(verilog.files.single { it.path == "logisim_fpga_top.v" }.content.contains(".io(fpga_p0_b0)"))
        val vhdl = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(artifact.copy(language = FpgaHdlLanguage.VHDL, files = listOf(FpgaSourceFile("-user_top.vhd", ""))), board(), map, FpgaFlow.OPEN_ECP5)).plan
        assertTrue(vhdl.files.single { it.path == "logisim_fpga_top.vhd" }.content.contains("io => fpga_p0_b0"))
        assertEquals("ghdl", vhdl.stages.first().tool)
        val recipe = BundleZip.read(vhdl.archive()).getValue("build-ecp5.sh").decodeToString()
        val translation = recipe.lineSequence().first { it.startsWith("ghdl ") }
        assertTrue(translation.contains("'./-user_top.vhd'"))
        assertTrue(translation.endsWith(" > 'translated.v'"))
        assertIs<FpgaPlanResult.Failure>(FpgaProjectGenerator.generate(artifact, board(), map.mapValues { FpgaPinBinding.Physical("C3", invert = true) }, FpgaFlow.OPEN_ECP5))
    }

    @Test fun generatesVendorConstraintsWithoutRunningVendorTools() {
        for ((flow, vendor, file, token) in listOf(
            listOf(FpgaFlow.VIVADO, FpgaVendor.VIVADO, "pins.xdc", "set_property PACKAGE_PIN"),
            listOf(FpgaFlow.ISE, FpgaVendor.XILINX, "pins.ucf", "LOC = \"G1\""),
            listOf(FpgaFlow.QUARTUS, FpgaVendor.ALTERA, "pins.qsf", "set_location_assignment PIN_G1"),
        )) {
            val device = board().device.copy(vendor = vendor as FpgaVendor).let {
                if (vendor == FpgaVendor.XILINX) it.copy(part = "xc3s500e", packageName = "pq208", speedGrade = "4") else it
            }
            val changed = board().copy(device = device)
            val plan = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(design(), changed, bindings(), flow as FpgaFlow)).plan
            assertTrue(plan.files.single { it.path == file }.content.contains(token as String))
            if (flow == FpgaFlow.ISE) {
                assertTrue(plan.files.single { it.path == "build.xst" }.content.contains("-p xc3s500e-pq208-4\n"))
                assertEquals(listOf("xst", "ngdbuild", "map", "par", "bitgen"), plan.stages.map { it.tool })
                assertEquals(listOf("design.bit"), plan.stages.last().outputFiles)
                assertTrue(plan.files.none { it.path == "build-cpld.sh" })
            }
            assertFalse(plan.programsHardware)
        }
    }

    @Test fun renamedIseCpldExportsTheSameJedecRecipeAndPinsWithoutCompanionApproval() {
        val xml = """
            <MICROLABTLE><BoardInformation>
              <ClockInformation FPGApin="P43" Frequency="12000000" IOStandard="Default" PullBehavior="Float"/>
              <FPGAInformation Vendor="XILINX" Family="Coolrunner" Part="XC2C64" Package="VQ44" Speedgrade="5"/>
              <UnusedPins PullBehavior="Float"/>
            </BoardInformation><IOComponents>
              <Button InputPinSet="P19"/>
              <LED OutputPinSet="P5"/>
            </IOComponents></MICROLABTLE>
        """.trimIndent()
        val circuit = FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG,
            listOf(FpgaSourceFile("user_top.v", "module user_top(input a, output y); assign y = a; endmodule\n")),
            listOf(FpgaDesignPort("a", FpgaPinDirection.INPUT), FpgaDesignPort("y", FpgaPinDirection.OUTPUT)))
        val pinBindings = mapOf(FpgaSignalBit("a") to FpgaPinBinding.Physical("P19"),
            FpgaSignalBit("y") to FpgaPinBinding.Physical("P5"))
        fun generate(boardXml: String): FpgaBuildPlan {
            val board = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(boardXml)).board
            return assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(circuit, board, pinBindings, FpgaFlow.ISE)).plan
        }

        val original = generate(xml)
        val renamed = generate(xml.replace("MICROLABTLE", "CUSTOM_CPLD"))
        assertEquals(original.stages, renamed.stages)
        assertEquals(listOf("xst", "ngdbuild", "cpldfit", "hprep6"), renamed.stages.map { it.tool })
        assertEquals(listOf("logisim.jed"), renamed.stages.last().outputFiles)
        assertEquals(listOf("-p", "XC2C64-5-VQ44", "-intstyle", "ise", "-terminate", "float", "-loc", "on",
            "-log", "logisim_cpldfit.log", "logisim.ngd"), renamed.stages[2].arguments)
        val files = BundleZip.read(renamed.archive())
        assertEquals("NET \"fpga_p0_b0\" LOC = \"P19\";\nNET \"fpga_p1_b0\" LOC = \"P5\";\n",
            files.getValue("pins.ucf").decodeToString())
        val script = files.getValue("build-cpld.sh").decodeToString()
        assertEquals(BundleZip.read(original.archive()).getValue("build-cpld.sh").decodeToString(), script)
        assertTrue(script.contains("cpldfit '-p' 'XC2C64-5-VQ44' '-intstyle' 'ise' '-terminate' 'float'"))
        assertTrue(script.endsWith("hprep6 '-i' 'logisim.vm6'\n"))
        assertEquals(listOf("source-0000.v", "source-0001.v", "pins.ucf"),
            original.companionRequest(FpgaBuildGoal.BITSTREAM).inputs.map { it.name })
        assertFailsWith<IllegalArgumentException> { renamed.companionRequest(FpgaBuildGoal.BITSTREAM) }
    }

    @Test fun customIseCpldFitterUsesItsOwnDeviceAndUnusedPinTermination() {
        val device = FpgaDevice(FpgaVendor.XILINX, "CoolRunner XPLA3", "xcr3128xl", "vq100", "10")
        for ((pull, termination) in listOf(FpgaPull.UP to "pullup", FpgaPull.DOWN to "pulldown")) {
            val custom = board().copy(device = device, unusedPinPull = pull)
            val plan = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(design(), custom, bindings(), FpgaFlow.ISE)).plan
            assertEquals(listOf("-p", "XCR3128XL-10-VQ100", "-intstyle", "ise", "-terminate", termination, "-loc", "on",
                "-log", "logisim_cpldfit.log", "logisim.ngd"), plan.stages.single { it.tool == "cpldfit" }.arguments)
            assertTrue(plan.files.single { it.path == "build.xst" }.content.contains("-p xcr3128xl-vq100-10\n"))
        }
    }
}
