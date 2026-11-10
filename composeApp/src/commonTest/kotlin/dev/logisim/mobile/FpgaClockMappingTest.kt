package dev.logisim.mobile

import dev.logisim.mobile.core.FpgaBoardDefinition
import dev.logisim.mobile.core.FpgaBoardPin
import dev.logisim.mobile.core.FpgaBoardResource
import dev.logisim.mobile.core.FpgaClock
import dev.logisim.mobile.core.FpgaDesignArtifact
import dev.logisim.mobile.core.FpgaDesignPort
import dev.logisim.mobile.core.FpgaDevice
import dev.logisim.mobile.core.FpgaElectrical
import dev.logisim.mobile.core.FpgaFlow
import dev.logisim.mobile.core.FpgaHdlLanguage
import dev.logisim.mobile.core.FpgaPinBinding
import dev.logisim.mobile.core.FpgaPinDirection
import dev.logisim.mobile.core.FpgaPlanResult
import dev.logisim.mobile.core.FpgaProjectGenerator
import dev.logisim.mobile.core.FpgaSignalBit
import dev.logisim.mobile.core.FpgaSourceFile
import dev.logisim.mobile.core.FpgaVendor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FpgaClockMappingTest {
    private val board = FpgaBoardDefinition("Clock fixture",
        FpgaDevice(FpgaVendor.OPEN_FPGA, "ECP5", "85k", "CABGA381", "5"),
        FpgaClock("F1", 50_000_000, FpgaElectrical()),
        listOf(FpgaBoardResource("led", "LED", "Result", null,
            listOf(FpgaBoardPin("led", 0, "A1", FpgaPinDirection.OUTPUT, FpgaElectrical())))))
    private val design = FpgaDesignArtifact("user_top", FpgaHdlLanguage.VERILOG,
        listOf(FpgaSourceFile("user_top.v", "module user_top(input clk, output y); assign y=clk; endmodule")),
        listOf(FpgaDesignPort("clk", FpgaPinDirection.INPUT), FpgaDesignPort("y", FpgaPinDirection.OUTPUT)))
    private val bindings = mapOf(FpgaSignalBit("clk") to FpgaPinBinding.Physical("F1"),
        FpgaSignalBit("y") to FpgaPinBinding.Physical("A1"))

    @Test fun explicitBoardClockProducesItsFrequencyConstraint() {
        val configured = design.withClockInputs(mapOf("clk" to FpgaClockInput(true, "50000000")))
        val result = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(configured, board, bindings, FpgaFlow.OPEN_ECP5))
        assertTrue(result.plan.files.single { it.path == "pins.lpf" }.content.contains("FREQUENCY PORT \"fpga_p0_b0\" 50 MHZ;"))
    }

    @Test fun invalidClockTextCannotGenerateAProjectAsAnOrdinaryInput() {
        for (frequency in listOf("", "0", "-1", "NaN", "1e6", "1000000000001", "999999999999999999999")) {
            val clock = FpgaClockInput(true, frequency)
            assertFalse(clock.valid, frequency)
            val configured = design.withClockInputs(mapOf("clk" to clock))
            val result = assertIs<FpgaPlanResult.Failure>(FpgaProjectGenerator.generate(configured, board, bindings, FpgaFlow.OPEN_ECP5))
            assertTrue(result.diagnostics.any { it.code == "clock-frequency-mismatch" }, frequency)
        }
    }

    @Test fun signalModeAllowsAConstantInputWithoutAClockConstraint() {
        val configured = design.withClockInputs(mapOf("clk" to FpgaClockInput(false, "invalid retained text")))
        val signalBindings = bindings + (FpgaSignalBit("clk") to FpgaPinBinding.Constant(true))
        val result = assertIs<FpgaPlanResult.Success>(FpgaProjectGenerator.generate(configured, board, signalBindings, FpgaFlow.OPEN_ECP5))
        assertEquals(1, result.plan.mappedPins.size)
        assertFalse(result.plan.files.single { it.path == "pins.lpf" }.content.contains("FREQUENCY"))
    }
}
