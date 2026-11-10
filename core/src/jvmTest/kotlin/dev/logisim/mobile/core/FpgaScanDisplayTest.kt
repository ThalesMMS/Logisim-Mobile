package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FpgaScanDisplayTest {
    private val board = assertIs<FpgaBoardImportResult.Success>(
        FpgaBoardCodec.read(checkNotNull(javaClass.getResource("/fpga/GECKO5_EDUCATION.xml")).readText()),
    ).board
    private val matrix = board.resources.single { it.type == "LedArray" }
    private val oscillator = FpgaDesignPort("osc", FpgaPinDirection.INPUT, clockFrequencyHz = 50_000_000)
    private val output = FpgaDesignPort("rgb", FpgaPinDirection.OUTPUT, 2)
    private val design = FpgaDesignArtifact(
        "matrix", FpgaHdlLanguage.VERILOG,
        listOf(FpgaSourceFile("matrix.v", "module matrix(input osc, output [1:0] rgb); assign rgb = 2'b11; endmodule")),
        listOf(oscillator, output),
    )
    private val bindings = mapOf(
        FpgaSignalBit("osc") to FpgaPinBinding.Physical("F1"),
        FpgaSignalBit("rgb", 0) to FpgaPinBinding.Display(matrix.id, 0),
        FpgaSignalBit("rgb", 1) to FpgaPinBinding.Display(matrix.id, 359),
    )

    @Test
    fun matrixReservesTheImportedPinsAndSharesTheExistingOscillator() {
        val plan = assertIs<FpgaPlanResult.Success>(
            FpgaProjectGenerator.generate(design, board, bindings, FpgaFlow.OPEN_ECP5),
        ).plan
        val constraints = plan.files.single { it.path == "pins.lpf" }.content
        assertEquals(1, constraints.lineSequence().count { it.startsWith("LOCATE") && """SITE "F1"""" in it })
        assertTrue("""LOCATE COMP "fpga_scan_0_p0" SITE "B6";""" in constraints)
        assertTrue("""LOCATE COMP "fpga_scan_0_p33" SITE "D7";""" in constraints)
        assertEquals(35, constraints.lineSequence().count { it.startsWith("LOCATE") })
        assertEquals(mapOf(FpgaSignalBit("osc") to "fpga_p0_b0"), plan.mappedPins)
    }

    @Test
    fun scanDriverUsesTheBitOfAOneBitVhdlVector() {
        val source = FpgaSourceFile("matrix.vhd", """
            library ieee;
            use ieee.std_logic_1164.all;
            entity matrix is port(rgb : out std_logic_vector(0 downto 0)); end entity;
            architecture rtl of matrix is begin rgb(0) <= '1'; end architecture;
        """.trimIndent())
        val singleOutput = design.copy(
            language = FpgaHdlLanguage.VHDL,
            files = listOf(source),
            ports = listOf(output.copy(width = 1, vhdlVector = true)),
        )
        val mapping = mapOf(FpgaSignalBit("rgb") to FpgaPinBinding.Display(matrix.id, 0, rgbPixel = true))
        val plan = assertIs<FpgaPlanResult.Success>(
            FpgaProjectGenerator.generate(singleOutput, board, mapping, FpgaFlow.OPEN_ECP5),
        ).plan
        val wrapper = plan.files.single { it.path == "${plan.topLevelName}.vhd" }.content
        for (channel in listOf(0, 120, 240)) {
            assertTrue("fpga_scan_0_data($channel) <= design_p0(0);" in wrapper)
        }
    }

    @Test
    fun rejectsDuplicateLogicalChannelsAndPhysicalOutputsOnTheSameDisplay() {
        val signal = FpgaSignalBit("rgb", 1)
        val duplicate = bindings + (signal to FpgaPinBinding.Display(matrix.id, 0))
        val conflictingPin = bindings + (signal to FpgaPinBinding.Physical("D7"))
        for ((mapping, expectedCode) in listOf(duplicate to "duplicate-display-channel",
            conflictingPin to "display-pin-already-used")) {
            val failure = assertIs<FpgaPlanResult.Failure>(
                FpgaProjectGenerator.generate(design, board, mapping, FpgaFlow.OPEN_ECP5),
            )
            assertTrue(failure.diagnostics.any { it.code == expectedCode }, failure.diagnostics.toString())
        }
    }
}
