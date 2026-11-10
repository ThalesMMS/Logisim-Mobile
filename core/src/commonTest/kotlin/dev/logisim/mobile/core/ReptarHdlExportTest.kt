package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReptarHdlExportTest {
    @Test
    fun exportsVhdlAndMapsTheBoardLocalBusByDirectionAndBitOrder() {
        val document = CircuitDocument(
            name = "reptar",
            components = listOf(
                CircuitComponent("bus", ExternalIoComponents.ReptarLocalBus, GridPoint(120, 60), label = "LocalBus"),
                CircuitComponent("pin", ComponentKind.INPUT, GridPoint(80, 130), label = "enable"),
                CircuitComponent("data", ComponentKind.CONSTANT, GridPoint(80, 120),
                    attributes = mapOf("width" to "16", "value" to "0x3456")),
            ),
            wires = listOf(
                WireSegment(GridPoint(80, 130), GridPoint(120, 130)),
                WireSegment(GridPoint(80, 120), GridPoint(120, 120)),
            ),
        )
        val project = CircuitProject(listOf(document))
        assertFalse(HdlExporter.export(project, HdlLanguage.VERILOG).successful)

        val exported = HdlExporter.export(project, HdlLanguage.VHDL)
        assertTrue(exported.successful, exported.diagnostics.toString())
        val module = exported.files.single { it.path == "${exported.topLevelName}.vhd" }.content
        assertTrue("SIGNAL v_bus_data_in : std_logic_vector(15 DOWNTO 0);" in module)
        assertTrue(Regex("""v_bus_data_in <= std_logic_vector'\(s_\d+( & s_\d+){15}\);""").containsMatchIn(module))
        assertTrue("data_in => v_bus_data_in," in module)
        assertEquals(setOf(13, 2, 16), exported.ports.filter { it.reptarBank != null }.map { it.width }.toSet())
        val design = exported.fpgaDesign(HdlLanguage.VHDL)
        val board = assertIs<FpgaBoardImportResult.Success>(FpgaBoardCodec.read(boardXml)).board
        val busBindings = design.reptarBindings(board)
        assertEquals(31, busBindings.size)
        val bindings = busBindings + (FpgaSignalBit("p_enable") to FpgaPinBinding.Physical("C24"))
        val plan = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(design, board, bindings, FpgaFlow.ISE)).plan
        val wrapper = plan.files.single { it.path == "${plan.topLevelName}.vhd" }.content
        val constraints = plan.files.single { it.path == "pins.ucf" }.content
        val oscillator = "fpga_p${design.ports.size}_b0"
        assertTrue("$oscillator: IN std_logic" in wrapper)
        assertTrue("NET \"$oscillator\" LOC = \"E16\" | IOSTANDARD = LVCMOS33;" in constraints)
        assertTrue("NET \"$oscillator\" TNM_NET = \"clock_$oscillator\";" in constraints)
        assertTrue("TIMESPEC \"TS_$oscillator\" = PERIOD \"clock_$oscillator\" 10.0 ns HIGH 50%;" in constraints)
        assertEquals(bindings.keys, plan.mappedPins.keys)
        assertFalse("logisim_fpga_tick" in wrapper)
        for ((signal, pin) in busBindings) {
            val external = plan.mappedPins.getValue(signal)
            assertTrue("NET \"$external\" LOC = \"${pin.location}\" | IOSTANDARD = LVCMOS18;" in constraints)
            if ("host_data" in signal.port) assertTrue("${signal.port}(${signal.bit}) => $external" in wrapper)
        }
        val highAddress = FpgaSignalBit("reptar_localbus_host_input", 12)
        val wait = FpgaSignalBit("reptar_localbus_host_output", 0)
        assertTrue("design_p1(12) <= NOT ${plan.mappedPins.getValue(highAddress)};" in wrapper)
        assertTrue("${plan.mappedPins.getValue(wait)} <= NOT design_p2(0);" in wrapper)
        assertEquals("W22", busBindings.getValue(FpgaSignalBit("reptar_localbus_host_input", 12)).location)
        assertEquals("AK29", busBindings.getValue(FpgaSignalBit("reptar_localbus_host_data", 0)).location)
        assertEquals("AD27", busBindings.getValue(FpgaSignalBit("reptar_localbus_host_data", 15)).location)
        assertTrue("SIGNAL design_p0: std_logic_vector(0 DOWNTO 0);" in wrapper)
        assertEquals("xst", plan.stages.first().tool)

        val request = plan.companionRequest(FpgaBuildGoal.BITSTREAM)
        assertEquals("fpga-ise-build", request.operation)
        assertEquals(CompanionJsonString("reptar-spartan-6"), request.parameters["profile"])
        assertEquals(CompanionJsonString("vhdl"), request.parameters["language"])
        assertEquals(setOf("language", "sources", "profile"), request.parameters.keys)
        assertTrue(request.inputs.dropLast(1).all { it.name.endsWith(".vhd") })
        assertEquals("pins.ucf", request.inputs.last().name)
        val wrongSpeed = board.copy(device = board.device.copy(speedGrade = "2"))
        assertFalse(wrongSpeed.supportsCompanionBuild(FpgaFlow.ISE))
        assertFailsWith<IllegalArgumentException> { plan.copy(board = wrongSpeed).companionRequest(FpgaBuildGoal.BITSTREAM) }

        val redirected = bindings + (FpgaSignalBit("reptar_localbus_host_data") to FpgaPinBinding.Physical("AD27"))
        val failure = assertIs<FpgaPlanResult.Failure>(FpgaProjectGenerator.generate(design, board, redirected, FpgaFlow.ISE))
        assertTrue(failure.diagnostics.any { it.code == "reptar-pin" })

        val missingClock = assertIs<FpgaPlanResult.Failure>(
            FpgaProjectGenerator.generate(design, board.copy(clock = null), bindings, FpgaFlow.ISE),
        )
        assertTrue(missingClock.diagnostics.any { it.code == "reptar-clock" })

        val oscillatorBinding = bindings + (FpgaSignalBit("p_enable") to FpgaPinBinding.Physical("E16"))
        val occupiedClock = assertIs<FpgaPlanResult.Failure>(
            FpgaProjectGenerator.generate(design, board, oscillatorBinding, FpgaFlow.ISE),
        )
        assertTrue(occupiedClock.diagnostics.any { it.code == "reptar-clock-conflict" })

        val clockInput = exported.fpgaDesign(HdlLanguage.VHDL, mapOf("p_enable" to 100_000_000L))
        val sharedClock = assertIs<FpgaPlanResult.Success>(
            FpgaProjectGenerator.generate(clockInput, board, oscillatorBinding, FpgaFlow.ISE),
        ).plan
        val sharedConstraints = sharedClock.files.single { it.path == "pins.ucf" }.content
        assertEquals(1, sharedConstraints.lineSequence().count { "LOC = \"E16\"" in it })
        assertEquals(oscillatorBinding.keys, sharedClock.mappedPins.keys)
    }

    private val boardXml = """
        <REPTAR_SPARTAN_6>
          <BoardInformation>
            <ClockInformation FPGApin="E16" Frequency="100000000" IOStandard="LVCMOS33" PullBehavior="Float"/>
            <FPGAInformation Vendor="XILINX" Family="Spartan6" Part="xc6slx150t" Package="FGG900" Speedgrade="3"/>
          </BoardInformation>
          <IOComponents>
            <Button InputPinSet="C24" Rect_x_y_w_h="496,247,20,20"/>
            <LocalBus ActivityLevel="Active low" FPGAPinIOStandard="LVCMOS18"
                InputPinSet="R25,W21,P24,P25,AG27,AE26,AG29,AH30,AK27,AJ28,AK28,T24,W22"
                OutputPinSet="R24,AB30"
                BiDirPinSet="AK29,AJ30,AJ29,AG28,AG30,AE25,Y22,Y24,AH27,V24,U24,T25,Y25,V23,U25,AD27"
                Rect_x_y_w_h="626,45,15,200"/>
          </IOComponents>
        </REPTAR_SPARTAN_6>
    """.trimIndent()
}
