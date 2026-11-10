package dev.logisim.mobile.core

internal object FpgaEvolutionRamLineHdl {
    data class Line(val input: String, val output: String, val enable: String? = null)

    fun verilog(
        prefix: String,
        configuration: MemoryConfiguration,
        clock: String,
        tick: String,
        address: String,
        writeEnable: String,
        lines: List<Line>,
    ): String = buildString {
        requireLineConfiguration(configuration, lines)
        val laneCount = lines.size
        val addressBits = configuration.addressBits
        val addressHigh = addressBits - 1
        val offsetBits = addressBits + 1
        val dataHigh = configuration.dataBits - 1
        val lastWord = (1 shl addressBits) - 1
        val terminalOffset = laneCount and ((1 shl offsetBits) - 1)
        val words = "${prefix}_line_words"
        val tickDelay = "${prefix}_line_ticks"
        val offset = "${prefix}_line_offset"
        val writeBase = "${prefix}_line_write_base"
        val readBase = "${prefix}_line_read_base"
        val writeAddress = "${prefix}_line_write_address"
        val readAddress = "${prefix}_line_read_address"
        val capturedWrite = "${prefix}_line_write"
        val ramDataIn = "${prefix}_line_data_in"
        val ramDataOut = "${prefix}_line_data_out"
        val dataInputs = lines.indices.map { "${prefix}_line_in_$it" }
        val dataOutputs = lines.indices.map { "${prefix}_line_out_$it" }
        val enables = lines.indices.map { "${prefix}_line_enable_$it" }

        appendLine("reg [$dataHigh:0] $words [0:$lastWord];")
        appendLine("reg [$laneCount:0] $tickDelay;")
        appendLine("reg [$addressBits:0] $offset;")
        appendLine("reg [$addressHigh:0] $writeBase, $readBase;")
        appendLine("reg $capturedWrite;")
        appendLine("reg [$dataHigh:0] $ramDataOut;")
        // The reference truncates the address addition to the physical RAM address width.
        appendLine("wire [$addressHigh:0] $writeAddress = $writeBase + $offset[$addressHigh:0];")
        appendLine("wire [$addressHigh:0] $readAddress = $readBase + $offset[$addressHigh:0];")
        for ((index, line) in lines.withIndex()) {
            appendLine("reg [$dataHigh:0] ${dataInputs[index]}, ${dataOutputs[index]};")
            if (line.enable != null) appendLine("reg ${enables[index]};")
            appendLine("assign ${line.output} = ${dataOutputs[index]};")
        }
        val writeTick = if (laneCount == 1) "$tickDelay[1]" else lines.indices.joinToString(" | ", "(", ")") {
            "(${enables[it]} & $tickDelay[${it + 1}])"
        }
        if (laneCount == 1) {
            appendLine("wire [$dataHigh:0] $ramDataIn = ${dataInputs.single()};")
        } else {
            appendLine("reg [$dataHigh:0] $ramDataIn;")
            appendLine("always @* begin")
            appendLine("  case ($offset)")
            for (index in 1 until laneCount) {
                appendLine("    ${offsetBits}'d$index: $ramDataIn <= ${dataInputs[index]};")
            }
            appendLine("    default: $ramDataIn <= ${dataInputs.first()};")
            appendLine("  endcase")
            appendLine("end")
        }

        appendLine("always @(negedge $clock) $ramDataOut <= $words[$readAddress];")
        appendLine("always @(posedge $clock) begin")
        appendLine("  if ($tickDelay[0] == 1'b1) $readBase <= $address;")
        appendLine("  if ($tick == 1'b1) begin")
        appendLine("    $writeBase <= $address;")
        appendLine("    $capturedWrite <= $writeEnable;")
        for ((index, line) in lines.withIndex()) {
            appendLine("    ${dataInputs[index]} <= ${line.input};")
            line.enable?.let { appendLine("    ${enables[index]} <= $it;") }
        }
        appendLine("  end")
        appendLine("  $tickDelay <= {$tickDelay[${laneCount - 1}:0], $tick};")
        appendLine("  $offset <= ($tickDelay[0] == 1'b1) ? ${offsetBits}'d0 :")
        appendLine("      ($offset != ${offsetBits}'d$terminalOffset) ? $offset + ${offsetBits}'d1 : $offset;")
        appendLine("  if (($capturedWrite & $writeTick) == 1'b1) $words[$writeAddress] <= $ramDataIn;")
        for ((index, output) in dataOutputs.withIndex()) {
            appendLine("  $output <= ($tickDelay[${index + 1}] == 1'b1) ? $ramDataOut : $output;")
        }
        appendLine("end")
    }

    fun vhdl(
        prefix: String,
        configuration: MemoryConfiguration,
        clock: String,
        tick: String,
        address: String,
        writeEnable: String,
        lines: List<Line>,
    ): String = buildString {
        requireLineConfiguration(configuration, lines)
        val laneCount = lines.size
        val addressBits = configuration.addressBits
        val addressHigh = addressBits - 1
        val offsetBits = addressBits + 1
        val dataHigh = configuration.dataBits - 1
        val lastWord = (1 shl addressBits) - 1
        val writeTick = if (laneCount == 1) "tick_pipeline(1)" else lines.indices.joinToString(" or ", "(", ")") {
            "(sampled_enable($it) and tick_pipeline(${it + 1}))"
        }
        appendLine("""
            |${prefix}_fpga_ram : block
            |  type word_memory is array (0 to $lastWord) of std_logic_vector($dataHigh downto 0);
            |  type line_values is array (0 to ${laneCount - 1}) of std_logic_vector($dataHigh downto 0);
            |  signal memory : word_memory;
            |  signal tick_pipeline : std_logic_vector($laneCount downto 0);
            |  signal address_offset : std_logic_vector($addressBits downto 0);
            |  signal write_base, read_base, write_address, read_address : std_logic_vector($addressHigh downto 0);
            |  signal sampled_data, line_outputs : line_values;
            |  signal write_data, read_data : std_logic_vector($dataHigh downto 0);
            |  signal sampled_write, memory_write : std_logic;
        """.trimMargin())
        if (laneCount > 1) appendLine("  signal sampled_enable : std_logic_vector(${laneCount - 1} downto 0);")
        appendLine("begin")
        // Keeping the addition at the physical address width preserves wraparound.
        appendLine("  write_address <= std_logic_vector(unsigned(write_base) + unsigned(address_offset($addressHigh downto 0)));")
        appendLine("  read_address <= std_logic_vector(unsigned(read_base) + unsigned(address_offset($addressHigh downto 0)));")
        appendLine("  memory_write <= sampled_write and $writeTick;")
        if (laneCount == 1) {
            appendLine("  write_data <= sampled_data(0);")
        } else {
            appendLine("  with address_offset select write_data <=")
            for (index in 1 until laneCount) {
                val offset = index.toString(2).padStart(offsetBits, '0')
                appendLine("    sampled_data($index) when \"$offset\",")
            }
            appendLine("    sampled_data(0) when others;")
        }
        for ((index, line) in lines.withIndex()) {
            appendLine("  ${line.output} <= line_outputs($index);")
        }
        appendLine("""
            |  process($clock)
            |  begin
            |    if falling_edge($clock) then
            |      read_data <= memory(to_integer(to_01(unsigned(read_address), '0')));
            |    end if;
            |  end process;
            |
            |  process($clock)
            |  begin
            |    if rising_edge($clock) then
            |      tick_pipeline <= tick_pipeline(${laneCount - 1} downto 0) & $tick;
            |      if tick_pipeline(0) = '1' then
            |        read_base <= $address;
            |        address_offset <= (others => '0');
            |      elsif unsigned(address_offset) < to_unsigned($laneCount, $offsetBits) then
            |        address_offset <= std_logic_vector(unsigned(address_offset) + 1);
            |      end if;
            |      if $tick = std_logic'('1') then
            |        write_base <= $address;
            |        sampled_write <= $writeEnable;
        """.trimMargin())
        for ((index, line) in lines.withIndex()) {
            appendLine("        sampled_data($index) <= ${line.input};")
            line.enable?.let { appendLine("        sampled_enable($index) <= $it;") }
        }
        appendLine("      end if;")
        appendLine("      if memory_write = '1' then")
        appendLine("        memory(to_integer(to_01(unsigned(write_address), '0'))) <= write_data;")
        appendLine("      end if;")
        for (index in lines.indices) {
            appendLine("      if tick_pipeline(${index + 1}) = '1' then")
            appendLine("        line_outputs($index) <= read_data;")
            appendLine("      end if;")
        }
        appendLine("    end if;")
        appendLine("  end process;")
        append("end block;")
    }

    private fun requireLineConfiguration(configuration: MemoryConfiguration, lines: List<Line>) {
        require(configuration.enables == MemoryEnables.LINE &&
            configuration.bus != MemoryBus.CONTROLLED && !configuration.clearPin &&
            !configuration.readOnly && !configuration.dualPort) {
            "FPGA line-enabled RAM requires a single port without a controlled data bus or clear pin."
        }
        require(lines.size == configuration.dataLines && lines.all { (it.enable == null) == (lines.size == 1) }) {
            "FPGA RAM line connections do not match the configured data lines."
        }
    }
}
