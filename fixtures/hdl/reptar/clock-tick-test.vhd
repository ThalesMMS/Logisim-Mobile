library ieee;
use ieee.std_logic_1164.all;
entity tb is end entity;
architecture test of tb is
  signal osc : std_logic := '0';
  signal p0 : std_logic_vector(12 downto 0) := (others => '1');
  signal p1 : std_logic_vector(1 downto 0);
  signal d : std_logic_vector(15 downto 0) := (others => 'Z');
  signal done : boolean := false;
begin
  osc <= not osc after 5 ns when not done;
  dut : entity work.logisim_fpga_top port map (
    fpga_p0_b0 => p0(0), fpga_p0_b1 => p0(1), fpga_p0_b2 => p0(2), fpga_p0_b3 => p0(3), fpga_p0_b4 => p0(4),
    fpga_p0_b5 => p0(5), fpga_p0_b6 => p0(6), fpga_p0_b7 => p0(7), fpga_p0_b8 => p0(8), fpga_p0_b9 => p0(9),
    fpga_p0_b10 => p0(10), fpga_p0_b11 => p0(11), fpga_p0_b12 => p0(12),
    fpga_p1_b0 => p1(0), fpga_p1_b1 => p1(1),
    fpga_p2_b0 => d(0), fpga_p2_b1 => d(1), fpga_p2_b2 => d(2), fpga_p2_b3 => d(3), fpga_p2_b4 => d(4),
    fpga_p2_b5 => d(5), fpga_p2_b6 => d(6), fpga_p2_b7 => d(7), fpga_p2_b8 => d(8), fpga_p2_b9 => d(9),
    fpga_p2_b10 => d(10), fpga_p2_b11 => d(11), fpga_p2_b12 => d(12), fpga_p2_b13 => d(13), fpga_p2_b14 => d(14),
    fpga_p2_b15 => d(15), fpga_p3_b0 => osc);
  check : process
    variable last : time := 0 ns;
    variable driven, released : natural := 0;
    variable previous : std_logic_vector(15 downto 0) := (others => 'U');
  begin
    for i in 1 to 400 loop
      wait until falling_edge(osc);
      if d /= previous then
        if d = x"3456" then driven := driven + 1;
        elsif d = (d'range => 'Z') then released := released + 1;
        else report "unexpected bus value" severity note; end if;
        if driven + released > 2 then
          assert now - last = 80 ns report "interval " & time'image(now - last) severity failure;
        end if;
        last := now; previous := d;
      end if;
    end loop;
    assert driven >= 20 and released >= 20 severity failure;
    report "REPTAR_CLOCK_PASS driven=" & natural'image(driven) & " released=" & natural'image(released);
    done <= true; wait;
  end process;
end architecture;
