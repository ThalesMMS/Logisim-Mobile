library ieee;
use ieee.std_logic_1164.all;

entity reptar_local_bus_test is
end entity;

architecture test of reptar_local_bus_test is
  signal host_input : std_logic_vector(12 downto 0) := "1000000011010";
  signal host_output : std_logic_vector(1 downto 0);
  signal host_data, external_data, data_in, data_out : std_logic_vector(15 downto 0) := (others => 'Z');
  signal controls : std_logic_vector(3 downto 0);
  signal wait_request, interrupt_request, data_tristate : std_logic := '1';
  signal address_out : std_logic_vector(8 downto 0);
begin
  host_data <= external_data;
  bridge : entity work.mobile_reptar_local_bus
    port map (
      host_input => host_input, host_output => host_output, host_data => host_data,
      chip_select => controls(0), address_latch => controls(1),
      output_enable => controls(2), write_enable => controls(3),
      wait_request => wait_request, interrupt_request => interrupt_request, data_tristate => data_tristate,
      data_out => data_out, data_in => data_in, address_out => address_out
    );

  stimulus : process
  begin
    external_data <= x"A639";
    data_in <= x"3456";
    interrupt_request <= '0';
    wait for 1 ns;
    assert address_out = "100000001" and controls = "1010" severity failure;
    assert host_output = "10" and data_out = x"A639" severity failure;

    external_data <= (others => 'Z');
    data_tristate <= '0';
    wait_request <= '0';
    interrupt_request <= '1';
    host_input <= "0111111100101";
    wait for 1 ns;
    assert address_out = "011111110" and controls = "0101" severity failure;
    assert host_output = "01" and host_data = x"3456" and data_out = x"3456" severity failure;

    data_tristate <= '1';
    wait for 1 ns;
    assert host_data = (host_data'range => 'Z') severity failure;
    report "Reptar nine-bit address, controls, WAIT/IRQ and 16 bidirectional data bits passed";
    wait;
  end process;
end architecture;
