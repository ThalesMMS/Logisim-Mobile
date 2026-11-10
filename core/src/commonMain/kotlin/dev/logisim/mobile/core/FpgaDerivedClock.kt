package dev.logisim.mobile.core

/** The Series 7 fractional multiplier and output divider are in steps of one eighth. */
data class FpgaClockScale(val multiplierEighths: Int, val dividerEighths: Int) {
    val multiplier: String get() = decimalFactor(multiplierEighths)
    val divider: String get() = decimalFactor(dividerEighths)

    val validFactors: Boolean get() =
        multiplierEighths in 16..512 && (dividerEighths == 8 || dividerEighths in 16..1024)

    fun outputFrequencyHz(physicalFrequencyHz: Long): Double =
        physicalFrequencyHz.toDouble() * multiplierEighths / dividerEighths

    internal fun withinClockLimits(physicalFrequencyHz: Long): Boolean {
        if (!validFactors || physicalFrequencyHz !in 10_000_000L..450_000_000L) return false
        val vcoEighthsHz = physicalFrequencyHz * multiplierEighths
        // Use the common Series 7 operating envelope with a global clock buffer.
        // DS181 derives the minimum output from the minimum VCO and maximum divider.
        return vcoEighthsHz in 4_800_000_000L..9_600_000_000L &&
            vcoEighthsHz <= 450_000_000L * dividerEighths
    }

    companion object {
        fun parse(multiplier: String, divider: String): FpgaClockScale? {
            val multiply = eighths(multiplier) ?: return null
            val divide = eighths(divider) ?: return null
            return FpgaClockScale(multiply, divide).takeIf { it.validFactors }
        }

        private val factorPattern = Regex("[0-9]{1,3}([.,][0-9]{1,3})?")

        private fun eighths(raw: String): Int? {
            val value = raw.trim()
            if (!factorPattern.matches(value)) return null
            val parts = value.replace(',', '.').split('.')
            val fraction = parts.getOrNull(1).orEmpty().padEnd(3, '0').toInt()
            if (fraction % 125 != 0) return null
            return parts[0].toInt() * 8 + fraction / 125
        }

        private fun decimalFactor(eighths: Int): String =
            "${eighths / 8}.${((eighths % 8) * 125).toString().padStart(3, '0')}"
    }
}

fun FpgaBoardDefinition.supportsDerivedClock(flow: FpgaFlow): Boolean =
    flow == FpgaFlow.VIVADO && device.vendor == FpgaVendor.VIVADO && device.family.endsWith("-7", ignoreCase = true)

internal class FpgaDerivedClockHdl(
    private val index: Int,
    private val input: String,
    private val output: String,
    private val inputPeriodNs: String,
    private val scale: FpgaClockScale,
) {
    private val name = "clock_p$index"
    private val clockIn = "${name}_input"
    private val feedback = "${name}_feedback"
    private val clockOut = "${name}_output"

    fun verilog() = """
        wire $clockIn, $feedback, $clockOut;
        BUFG ${name}_input_buffer (.I($input), .O($clockIn));
        MMCME2_BASE #(
          .CLKIN1_PERIOD($inputPeriodNs),
          .CLKFBOUT_MULT_F(${scale.multiplier}),
          .DIVCLK_DIVIDE(1),
          .CLKOUT0_DIVIDE_F(${scale.divider})
        ) ${name}_mmcm (
          .CLKIN1($clockIn), .CLKFBIN($feedback), .CLKFBOUT($feedback),
          .CLKOUT0($clockOut), .PWRDWN(1'b0), .RST(1'b0)
        );
        BUFG ${name}_output_buffer (.I($clockOut), .O($output));
    """.trimIndent() + "\n"

    fun vhdlDeclarations() = "  signal $clockIn, $feedback, $clockOut : std_logic;\n"

    fun vhdl() = """
        ${name}_input_buffer : BUFG port map (I => $input, O => $clockIn);
        ${name}_mmcm : MMCME2_BASE
          generic map (
            CLKIN1_PERIOD => $inputPeriodNs,
            CLKFBOUT_MULT_F => ${scale.multiplier},
            DIVCLK_DIVIDE => 1,
            CLKOUT0_DIVIDE_F => ${scale.divider}
          )
          port map (
            CLKIN1 => $clockIn, CLKFBIN => $feedback, CLKFBOUT => $feedback,
            CLKOUT0 => $clockOut, PWRDWN => '0', RST => '0',
            CLKFBOUTB => open, LOCKED => open, CLKOUT0B => open,
            CLKOUT1 => open, CLKOUT1B => open, CLKOUT2 => open, CLKOUT2B => open,
            CLKOUT3 => open, CLKOUT3B => open, CLKOUT4 => open, CLKOUT5 => open, CLKOUT6 => open
          );
        ${name}_output_buffer : BUFG port map (I => $clockOut, O => $output);
    """.trimIndent().prependIndent("  ") + "\n"
}
