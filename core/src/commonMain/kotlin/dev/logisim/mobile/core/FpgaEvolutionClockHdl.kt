package dev.logisim.mobile.core

/** Synthesized clock tree for the FPGA wrapper. Clock waveform indices follow the design's port bindings. */
internal object FpgaEvolutionClockHdl {
    data class Waveform(val highTicks: Int, val lowTicks: Int, val phaseOffset: Int) {
        init {
            require(highTicks > 0 && lowTicks > 0 && phaseOffset >= 0)
        }
    }

    const val tickSignal = "logisim_fpga_tick"

    fun waveSignal(index: Int) = "logisim_fpga_clock_${index}_wave"
    fun invertedWaveSignal(index: Int) = "logisim_fpga_clock_${index}_inverted"
    fun riseSignal(index: Int) = "logisim_fpga_clock_${index}_rise"
    fun fallSignal(index: Int) = "logisim_fpga_clock_${index}_fall"

    fun verilog(clockInput: String, reload: Int, waveforms: List<Waveform>): String {
        require(reload > 0)
        if (waveforms.isEmpty()) return ""
        return buildString {
            val counter = "${tickSignal}_count"
            val width = counterWidth(reload - 1)
            append(
                """
                reg [${width - 1}:0] $counter;
                reg $tickSignal;
                initial begin
                  $counter = ${width}'d0;
                  $tickSignal = 1'b0;
                end
                always @(posedge $clockInput) begin
                  $tickSignal <= ($counter == ${width}'d0);
                  $counter <= ($counter == ${width}'d0) ? ${width}'d${reload - 1} : $counter - ${width}'d1;
                end
                """.trimIndent()
            )
            append('\n')
            waveforms.forEachIndexed { index, waveform ->
                append(waveform.verilog(index, clockInput))
                append('\n')
            }
        }
    }

    private fun Waveform.verilog(index: Int, clockInput: String): String {
        val wave = waveSignal(index)
        val inverted = invertedWaveSignal(index)
        val rise = riseSignal(index)
        val fall = fallSignal(index)
        val counter = "logisim_fpga_clock_${index}_count"
        val level = "logisim_fpga_clock_${index}_level"
        val buffered = "logisim_fpga_clock_${index}_buffer"
        val width = counterWidth(maxOf(highTicks - 1, lowTicks - 1, phaseOffset))
        // The phase delay holds the initial low level for extra ticks before the first transition.
        // The uninitialized buffer preserves the Evolution Verilog startup transient.
        return """
            reg [${width - 1}:0] $counter;
            reg $level, $buffered, $wave, $inverted, $rise, $fall;
            initial begin
              $counter = ${width}'d$phaseOffset;
              $level = 1'b0;
              $wave = 1'b0;
              $inverted = 1'b0;
              $rise = 1'b0;
              $fall = 1'b0;
            end
            always @(posedge $clockInput) begin
              if ($tickSignal) begin
                if ($counter == ${width}'d0) begin
                  $level <= ~$level;
                  $counter <= $level ? ${width}'d${lowTicks - 1} : ${width}'d${highTicks - 1};
                end else begin
                  $counter <= $counter - ${width}'d1;
                end
              end
              $buffered <= $level;
              $wave <= $buffered;
              $inverted <= ~$buffered;
              $rise <= ~$buffered & $level;
              $fall <= $buffered & ~$level;
            end
        """.trimIndent()
    }

    fun vhdlDeclarations(waveforms: List<Waveform>): String = buildString {
        appendLine("  signal $tickSignal : std_logic;")
        for (index in waveforms.indices) {
            appendLine("  signal ${waveSignal(index)}, ${invertedWaveSignal(index)}, ${riseSignal(index)}, ${fallSignal(index)} : std_logic;")
        }
    }

    fun vhdl(clockInput: String, reload: Int, waveforms: List<Waveform>): String = buildString {
        require(reload > 0)
        val width = counterWidth(reload - 1)
        appendLine("""
            |  fpga_ticker : block
            |    signal count : std_logic_vector(${width - 1} downto 0);
            |    signal count_zero : std_logic;
            |  begin
            |    count_zero <= '1' when count = std_logic_vector(to_unsigned(0, $width)) else '0';
            |    process($clockInput)
            |    begin
            |      if rising_edge($clockInput) then
            |        $tickSignal <= count_zero;
            |        if $tickSignal /= '0' and $tickSignal /= '1' then
            |          count <= (others => '0');
            |        elsif count_zero = '1' then
            |          count <= std_logic_vector(to_unsigned(${reload - 1}, $width));
            |        else
            |          count <= std_logic_vector(unsigned(count) - 1);
            |        end if;
            |      end if;
            |    end process;
            |  end block;
        """.trimMargin())
        for ((index, waveform) in waveforms.withIndex()) {
            appendLine(waveform.vhdl(index, clockInput))
        }
    }

    private fun Waveform.vhdl(index: Int, clockInput: String): String {
        val width = counterWidth(maxOf(highTicks - 1, lowTicks - 1))
        val phaseShift = if (phaseOffset == 0) "" else
            "phase($phaseOffset downto 1) <= phase(${phaseOffset - 1} downto 0);"
        // Evolution VHDL recovers from an unknown initial level and shifts phase on tick pulses.
        return """
            |  fpga_clock_$index : block
            |    signal count : std_logic_vector(${width - 1} downto 0);
            |    signal phase : std_logic_vector($phaseOffset downto 0);
            |    signal buffered : std_logic;
            |  begin
            |    process($clockInput)
            |    begin
            |      if rising_edge($clockInput) then
            |        buffered <= phase($phaseOffset);
            |        ${waveSignal(index)} <= buffered;
            |        ${invertedWaveSignal(index)} <= not buffered;
            |        ${riseSignal(index)} <= not buffered and phase($phaseOffset);
            |        ${fallSignal(index)} <= buffered and not phase($phaseOffset);
            |        if phase(0) /= '0' and phase(0) /= '1' then
            |          phase <= (others => '1');
            |          count <= (others => '0');
            |        elsif $tickSignal = '1' then
            |          $phaseShift
            |          if count = std_logic_vector(to_unsigned(0, $width)) then
            |            phase(0) <= not phase(0);
            |            if phase(0) = '1' then
            |              count <= std_logic_vector(to_unsigned(${lowTicks - 1}, $width));
            |            else
            |              count <= std_logic_vector(to_unsigned(${highTicks - 1}, $width));
            |            end if;
            |          else
            |            count <= std_logic_vector(unsigned(count) - 1);
            |          end if;
            |        end if;
            |      end if;
            |    end process;
            |  end block;
        """.trimMargin()
    }

    private fun counterWidth(maxValue: Int): Int =
        (Int.SIZE_BITS - maxValue.countLeadingZeroBits()).coerceAtLeast(1)
}
