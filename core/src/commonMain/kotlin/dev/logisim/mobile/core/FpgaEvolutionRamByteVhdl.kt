package dev.logisim.mobile.core

/** VHDL block for the BYTE RAM profile on an Evolution FPGA clock tree. */
internal object FpgaEvolutionRamByteVhdl {
    fun vhdl(
        prefix: String,
        configuration: MemoryConfiguration,
        clock: String,
        tick: String,
        address: String,
        data: String,
        write: String,
        output: String,
        outputEnable: String,
        byteEnables: List<String>,
    ): String = buildString {
        val byteCount = configuration.byteEnableCount
        require(configuration.enables == MemoryEnables.BYTE && configuration.bus == MemoryBus.SEPARATE &&
            configuration.synchronous && !configuration.clearPin &&
            !configuration.readOnly && !configuration.dualPort && byteEnables.size == byteCount) {
            "FPGA VHDL byte RAM requires a single port with separate buses, an edge trigger and no clear pin."
        }
        val width = configuration.dataBits
        val addressHigh = configuration.addressBits - 1
        val lastWord = (1 shl configuration.addressBits) - 1
        val lanes = List(maxOf(1, byteCount)) { byte ->
            val low = byte * 8
            low..(if (byteCount == 0) width - 1 else minOf(low + 7, width - 1))
        }
        // Evolution removes the read/write order attribute when asynchronous read is enabled.
        val writeStage = if (!configuration.asynchronousRead && configuration.readBehavior == MemoryReadBehavior.AFTER_WRITE)
            0 else 2
        val memoryAddress = if (configuration.asynchronousRead) "memory_address" else "sampled_address"
        appendLine("${prefix}_fpga_ram : block")
        appendLine("  signal tick_pipeline : std_logic_vector(2 downto 0);")
        appendLine("  signal sampled_address : std_logic_vector($addressHigh downto 0);")
        if (configuration.asynchronousRead) {
            appendLine("  signal sampled_read_address, memory_address : std_logic_vector($addressHigh downto 0);")
        }
        appendLine("  signal sampled_data, memory_data : std_logic_vector(${width - 1} downto 0);")
        appendLine("  signal sampled_write : std_logic;")
        if (byteCount > 0) {
            appendLine("  signal sampled_oe : std_logic;")
            appendLine("  signal sampled_bytes : std_logic_vector(${byteCount - 1} downto 0);")
        }
        for ((byte, lane) in lanes.withIndex()) {
            val laneHigh = lane.last - lane.first
            appendLine("  type bank_${byte}_type is array (0 to $lastWord) of std_logic_vector($laneHigh downto 0);")
            appendLine("  signal bank_$byte : bank_${byte}_type;")
        }
        appendLine("begin")
        if (configuration.asynchronousRead) {
            appendLine("  memory_address <= sampled_address when tick_pipeline($writeStage) = '1' else sampled_read_address;")
        }
        appendLine("  process($clock)")
        appendLine("    variable word_index : natural range 0 to $lastWord;")
        appendLine("  begin")
        appendLine("    if rising_edge($clock) then")
        // numeric_std.to_integer uses address zero for a metavalue. Keep that result without a startup warning.
        appendLine("      word_index := to_integer(to_01(unsigned($memoryAddress), '0'));")
        appendLine("      tick_pipeline <= tick_pipeline(1 downto 0) & $tick;")
        if (configuration.asynchronousRead) {
            appendLine("      if tick_pipeline(0) = '1' then")
            appendLine("        sampled_read_address <= $address;")
            appendLine("      end if;")
        }
        appendLine("      if $tick = '1' then")
        appendLine("        sampled_address <= $address;")
        appendLine("        sampled_data <= $data;")
        appendLine("        sampled_write <= $write;")
        if (byteCount > 0) {
            appendLine("        sampled_oe <= $outputEnable;")
            // The caller supplies the physical BE terminal order. Evolution reverses it for memory lanes.
            byteEnables.asReversed().forEachIndexed { byte, enable ->
                appendLine("        sampled_bytes($byte) <= $enable;")
            }
        }
        appendLine("      end if;")
        for ((byte, lane) in lanes.withIndex()) {
            val range = "${lane.last} downto ${lane.first}"
            val word = "bank_$byte(word_index)"
            val mask = if (byteCount > 0) " and sampled_bytes($byte) = '1'" else ""
            // Evolution ties OE high when the RAM has no byte enables.
            val outputMask = if (byteCount > 0) " and sampled_oe = '1'$mask" else ""
            appendLine("      if tick_pipeline($writeStage) = '1' and sampled_write = '1'$mask then")
            appendLine("        $word <= sampled_data($range);")
            appendLine("      end if;")
            appendLine("      memory_data($range) <= $word;")
            appendLine("      if tick_pipeline(2) = '1'$outputMask then")
            appendLine("        $output($range) <= memory_data($range);")
            appendLine("      end if;")
        }
        appendLine("    end if;")
        appendLine("  end process;")
        append("end block;")
    }
}
