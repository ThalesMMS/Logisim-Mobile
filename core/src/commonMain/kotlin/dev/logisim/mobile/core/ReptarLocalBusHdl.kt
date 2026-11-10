package dev.logisim.mobile.core

enum class ReptarPinBank(val suffix: String, val direction: PortDirection, val width: Int) {
    HOST_INPUT("host_input", PortDirection.INPUT, 13),
    HOST_OUTPUT("host_output", PortDirection.OUTPUT, 2),
    HOST_DATA("host_data", PortDirection.INOUT, 16),
}

internal object ReptarLocalBusHdl {
    const val ENTITY = "mobile_reptar_local_bus"

    val source = """
        library ieee;
        use ieee.std_logic_1164.all;
        library unisim;
        use unisim.vcomponents.all;

        entity mobile_reptar_local_bus is
          port (
            host_input : in std_logic_vector(12 downto 0);
            host_output : out std_logic_vector(1 downto 0);
            host_data : inout std_logic_vector(15 downto 0);
            chip_select, address_latch, output_enable, write_enable : out std_logic;
            wait_request, interrupt_request, data_tristate : in std_logic;
            data_out : out std_logic_vector(15 downto 0);
            data_in : in std_logic_vector(15 downto 0);
            address_out : out std_logic_vector(8 downto 0)
          );
        end entity;

        architecture rtl of mobile_reptar_local_bus is
        begin
          chip_select <= host_input(0);
          address_latch <= host_input(1);
          output_enable <= host_input(2);
          write_enable <= host_input(3);
          address_out <= host_input(12 downto 4);
          host_output(0) <= not wait_request;
          host_output(1) <= not interrupt_request;

          data_buffers : for bit_index in 0 to 15 generate
            pad : IOBUF
              generic map (DRIVE => 12, IOSTANDARD => "LVCMOS18", SLEW => "FAST")
              port map (
                I => data_in(bit_index), T => data_tristate,
                O => data_out(bit_index), IO => host_data(bit_index)
              );
          end generate;
        end architecture;
    """.trimIndent()
}
