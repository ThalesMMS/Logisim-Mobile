package dev.logisim.mobile.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FpgaDerivedClockTest {
    private val board = assertIs<FpgaBoardImportResult.Success>(
        FpgaBoardCodec.read(checkNotNull(javaClass.getResource("/fpga/BASYS3.xml")).readText()),
    ).board
    private val oscillator = FpgaDesignPort(
        "clk", FpgaPinDirection.INPUT, clockFrequencyHz = 100_000_000, clockScale = FpgaClockScale(64, 128),
    )
    private val ports = listOf(oscillator, FpgaDesignPort("led", FpgaPinDirection.OUTPUT))
    private val mapping = mapOf(
        FpgaSignalBit("clk") to FpgaPinBinding.Physical("W5"),
        FpgaSignalBit("led") to FpgaPinBinding.Physical("U16"),
    )

    private fun design(language: FpgaHdlLanguage) =
        FpgaDesignArtifact("dut", language, listOf(FpgaSourceFile("dut.v", "// DUT")), ports)

    @Test
    fun separatesTheBasys3PrimaryClockFromTheInternalClockInBothLanguages() {
        for (language in FpgaHdlLanguage.entries) {
            val plan = assertIs<FpgaPlanResult.Success>(
                FpgaProjectGenerator.generate(design(language), board, mapping, FpgaFlow.VIVADO),
            ).plan
            val xdc = plan.files.single { it.path == "pins.xdc" }.content
            assertTrue("PACKAGE_PIN W5 [get_ports {fpga_p0_b0}]" in xdc)
            assertEquals(listOf("create_clock -period 10.0 [get_ports {fpga_p0_b0}]"),
                xdc.lineSequence().filter { it.startsWith("create_clock") }.toList())
            val wrapper = plan.files.single { it.path.startsWith(FpgaProjectGenerator.TOP_LEVEL) }.content
            assertTrue("MMCME2_BASE" in wrapper && "8.000" in wrapper && "16.000" in wrapper)
            assertTrue("assign design_p0 = fpga_p0_b0" !in wrapper && "design_p0 <= fpga_p0_b0" !in wrapper)
            assertEquals(50_000_000.0, oscillator.clockScale?.outputFrequencyHz(100_000_000))
        }
    }

    @Test
    fun generatesTheMinimumBasys3OutputClock() {
        val scale = FpgaClockScale(multiplierEighths = 48, dividerEighths = 1024)
        val source = design(FpgaHdlLanguage.VERILOG)
        val minimum = source.copy(ports = listOf(oscillator.copy(clockScale = scale), ports[1]))
        val plan = assertIs<FpgaPlanResult.Success>(
            FpgaProjectGenerator.generate(minimum, board, mapping, FpgaFlow.VIVADO),
        ).plan
        val wrapper = plan.files.single { it.path == "${FpgaProjectGenerator.TOP_LEVEL}.v" }.content
        assertTrue(".CLKFBOUT_MULT_F(6.000)" in wrapper)
        assertTrue(".CLKOUT0_DIVIDE_F(128.000)" in wrapper)
        assertEquals(4_687_500.0, scale.outputFrequencyHz(100_000_000))
    }

    @Test
    fun validatesFractionalFactorsAndRejectsAnInvalidVcoOrBoardProfile() {
        val scaled = assertIs<FpgaClockScale>(FpgaClockScale.parse("8,125", "16.250"))
        assertEquals(50_000_000.0, scaled.outputFrequencyHz(100_000_000))
        assertNull(FpgaClockScale.parse("8.1", "16"))
        assertNull(FpgaClockScale.parse("8", "1.125"))
        val source = design(FpgaHdlLanguage.VERILOG)
        val badVco = source.copy(ports = listOf(oscillator.copy(clockScale = FpgaClockScale(16, 128)), ports[1]))
        val unsupported = board.copy(device = board.device.copy(family = "UltraScale"))
        val errors = FpgaDesignRules.check(badVco, unsupported, mapping, FpgaFlow.VIVADO)
        assertTrue(errors.any { it.code == "invalid-derived-clock" })
        assertTrue(errors.any { it.code == "derived-clock-unsupported" })
    }
}
